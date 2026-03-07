package com.arflix.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val error: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mediaRepository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

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
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                // Primary: AI Search (better relevance and ordering)
                // Fallback: TMDB search if AI search returns nothing
                val aiResults = runCatching { mediaRepository.searchAI(query) }.getOrElse { emptyList() }
                val results = if (aiResults.isNotEmpty()) {
                    // AI search returns well-ordered results — keep the addon's ordering.
                    // Supplement with TMDB results for items AI might have missed.
                    val tmdbResults = runCatching { mediaRepository.search(query) }.getOrElse { emptyList() }
                    val aiIds = aiResults.map { "${it.mediaType.name}-${it.id}" }.toSet()
                    val extraTmdb = tmdbResults.filter { "${it.mediaType.name}-${it.id}" !in aiIds }
                    aiResults + extraTmdb
                } else {
                    // AI search unavailable — fall back to TMDB with smart sorting
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

                // Separate into movies and TV shows
                val movies = results.filter { it.mediaType == MediaType.MOVIE }
                val tvShows = results.filter { it.mediaType == MediaType.TV }
                val topForLogos = (movies.take(16) + tvShows.take(16)).distinctBy { "${it.mediaType}_${it.id}" }
                val logoMap = withContext(Dispatchers.IO) {
                    topForLogos.map { item ->
                        async {
                            val key = "${item.mediaType}_${item.id}"
                            val logo = runCatching { mediaRepository.getLogoUrl(item.mediaType, item.id) }
                                .getOrNull()
                            if (logo.isNullOrBlank()) null else key to logo
                        }
                    }.awaitAll().filterNotNull().toMap()
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasSearched = true,
                    results = results,
                    movieResults = movies,
                    tvResults = tvShows,
                    cardLogoUrls = logoMap
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasSearched = true,
                    error = e.message
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
