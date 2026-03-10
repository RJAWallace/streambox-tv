package com.arflix.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.WatchProgress
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.util.RemoteCrashLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<MediaItem> = emptyList(),
    val movieResults: List<MediaItem> = emptyList(),
    val tvResults: List<MediaItem> = emptyList(),
    val cardLogoUrls: Map<String, String> = emptyMap(),
    val error: String? = null,
    /** Which search engine produced the current results: "AI" or "TMDB" */
    val searchMethod: String = ""
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val traktRepository: TraktRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            try { traktRepository.initializeWatchedCache() } catch (_: Exception) {}
        }
    }

    /** Enrich items with watched status from the watched cache. */
    private fun enrichWithWatchedStatus(items: List<MediaItem>): List<MediaItem> {
        return items.map { item ->
            val progress = when (item.mediaType) {
                MediaType.MOVIE -> if (traktRepository.isMovieWatched(item.id)) {
                    WatchProgress.COMPLETED
                } else WatchProgress.NONE
                MediaType.TV -> {
                    val watchedCount = traktRepository.getWatchedEpisodeCount(item.id)
                    val totalEpisodes = item.totalEpisodes ?: 0
                    when {
                        watchedCount == 0 -> WatchProgress.NONE
                        totalEpisodes > 0 && watchedCount >= totalEpisodes -> WatchProgress.COMPLETED
                        watchedCount > 0 -> WatchProgress.IN_PROGRESS
                        else -> WatchProgress.NONE
                    }
                }
            }
            if (progress != item.watchProgress) {
                item.copy(isWatched = progress == WatchProgress.COMPLETED, watchProgress = progress)
            } else item
        }
    }

    fun addChar(char: String) {
        _uiState.value = _uiState.value.copy(
            query = _uiState.value.query + char
        )
        debounceSearch()
    }

    fun deleteChar() {
        if (_uiState.value.query.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                query = _uiState.value.query.dropLast(1)
            )
            debounceSearch()
        }
    }

    fun updateQuery(newQuery: String) {
        _uiState.value = _uiState.value.copy(query = newQuery)
        debounceSearch()
    }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, searchMethod = "")

            try {
                // Primary: AI Search (better relevance and ordering)
                // Fallback: TMDB search if AI search returns nothing
                var searchMethod = "AI"

                // IMPORTANT: Don't use runCatching in coroutines — it swallows
                // CancellationException which breaks structured concurrency.
                var aiResults: List<MediaItem> = emptyList()
                var aiError: Throwable? = null
                try {
                    aiResults = mediaRepository.searchAI(query)
                } catch (ce: CancellationException) {
                    throw ce // Always rethrow cancellation
                } catch (e: Exception) {
                    aiResults = emptyList()
                    aiError = e
                    System.err.println("[Search] AI search failed: ${e.javaClass.simpleName}: ${e.message}")
                    // Only log real errors to Supabase, not cancellations
                    RemoteCrashLogger.error("Search", "AI search failed for '$query': ${e.message}", e)
                }

                val results = if (aiResults.isNotEmpty()) {
                    // AI search returned results — keep the addon's ordering.
                    // Run TMDB in parallel to supplement with items AI might miss.
                    val tmdbResults = try {
                        mediaRepository.search(query)
                    } catch (ce: CancellationException) { throw ce }
                    catch (_: Exception) { emptyList() }

                    val aiIds = aiResults.map { "${it.mediaType.name}-${it.id}" }.toSet()
                    val extraTmdb = tmdbResults.filter { "${it.mediaType.name}-${it.id}" !in aiIds }
                    RemoteCrashLogger.checkpoint("Search", "AI OK: ${aiResults.size} AI + ${extraTmdb.size} TMDB for '$query'")
                    aiResults + extraTmdb
                } else {
                    // AI search unavailable or empty — fall back to TMDB
                    searchMethod = "TMDB"
                    if (aiError != null) {
                        RemoteCrashLogger.error("Search", "Falling back to TMDB for '$query'", aiError)
                    } else {
                        RemoteCrashLogger.checkpoint("Search", "AI returned 0 results, using TMDB for '$query'")
                    }
                    val tmdbResults = mediaRepository.search(query)
                    val queryLower = query.lowercase()
                    tmdbResults.sortedWith(
                        compareBy<MediaItem> { item ->
                            val titleLower = item.title.lowercase()
                            when {
                                titleLower == queryLower -> 0
                                titleLower.startsWith(queryLower) -> 1
                                titleLower.contains(queryLower) -> 2
                                else -> 3
                            }
                        }.thenByDescending { item ->
                            val isDocumentary = item.genreIds.contains(99) || item.genreIds.contains(10763)
                            val titleLower = item.title.lowercase()
                            val isSpecial = titleLower.contains("making of") ||
                                    titleLower.contains("behind the") ||
                                    titleLower.contains("special") ||
                                    titleLower.contains("documentary") ||
                                    titleLower.contains("featurette")
                            if (isDocumentary || isSpecial) item.popularity * 0.1f else item.popularity
                        }.thenByDescending { item ->
                            item.year.toIntOrNull() ?: 0
                        }
                    )
                }

                // Enrich with watched status and separate into movies and TV shows
                val enrichedResults = enrichWithWatchedStatus(results)
                val movies = enrichedResults.filter { it.mediaType == MediaType.MOVIE }
                val tvShows = enrichedResults.filter { it.mediaType == MediaType.TV }
                val topForLogos = (movies.take(16) + tvShows.take(16)).distinctBy { "${it.mediaType}_${it.id}" }
                val logoMap = withContext(Dispatchers.IO) {
                    topForLogos.map { item ->
                        async {
                            val key = "${item.mediaType}_${item.id}"
                            val logo = try {
                                mediaRepository.getLogoUrl(item.mediaType, item.id)
                            } catch (ce: CancellationException) { throw ce }
                            catch (_: Exception) { null }
                            if (logo.isNullOrBlank()) null else key to logo
                        }
                    }.awaitAll().filterNotNull().toMap()
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasSearched = true,
                    results = enrichedResults,
                    movieResults = movies,
                    tvResults = tvShows,
                    cardLogoUrls = logoMap,
                    searchMethod = searchMethod
                )
            } catch (ce: CancellationException) {
                throw ce // Propagate cancellation properly
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasSearched = true,
                    error = e.message,
                    searchMethod = "ERROR"
                )
            }
        }
    }

    private fun debounceSearch() {
        searchJob?.cancel()
        // Immediately show loading to prevent "No results" flash during debounce
        if (_uiState.value.query.length >= 2) {
            _uiState.value = _uiState.value.copy(isLoading = true)
        }
        searchJob = viewModelScope.launch {
            delay(500) // Reduced from 800ms for snappier feel
            if (_uiState.value.query.length >= 2) {
                search()
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun clearSearch() {
        _uiState.value = SearchUiState()
    }
}
