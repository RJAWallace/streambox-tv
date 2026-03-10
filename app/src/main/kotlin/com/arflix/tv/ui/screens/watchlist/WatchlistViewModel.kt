package com.arflix.tv.ui.screens.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.WatchProgress
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.WatchlistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ToastType {
    SUCCESS, ERROR, INFO
}

data class WatchlistUiState(
    val isLoading: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val movieItems: List<MediaItem> = emptyList(),
    val tvItems: List<MediaItem> = emptyList(),
    val showUnwatchedOnly: Boolean = false,
    val error: String? = null,
    // Toast
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.INFO
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val watchlistRepository: WatchlistRepository,
    private val traktRepository: TraktRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    /** Sets items + filtered movieItems/tvItems in one shot, applying watched badges and filter. */
    private fun updateItems(state: WatchlistUiState, items: List<MediaItem>): WatchlistUiState {
        val enriched = enrichWithWatchedStatus(items)
        val filtered = if (state.showUnwatchedOnly) {
            enriched.filter { it.watchProgress != WatchProgress.COMPLETED }
        } else enriched
        return state.copy(
            items = enriched,
            movieItems = filtered.filter { it.mediaType == MediaType.MOVIE },
            tvItems = filtered.filter { it.mediaType == MediaType.TV }
        )
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
                        else -> WatchProgress.IN_PROGRESS
                    }
                }
            }
            item.copy(
                isWatched = progress == WatchProgress.COMPLETED,
                watchProgress = progress
            )
        }
    }

    init {
        loadWatchlistInstant()
        observeWatchlistChanges()
        // Initialize watched cache for badge rendering
        viewModelScope.launch {
            try { traktRepository.initializeWatchedCache() } catch (_: Exception) {}
        }
    }

    private fun observeWatchlistChanges() {
        viewModelScope.launch {
            watchlistRepository.watchlistItems.collect { items ->
                if (items.isNotEmpty() || _uiState.value.items.isEmpty()) {
                    _uiState.value = updateItems(_uiState.value, items).copy(
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun loadWatchlistInstant() {
        viewModelScope.launch {
            val cachedItems = watchlistRepository.getCachedItems()
            if (cachedItems.isNotEmpty()) {
                _uiState.value = updateItems(WatchlistUiState(isLoading = false), cachedItems)
            } else {
                _uiState.value = WatchlistUiState(isLoading = true)
            }

            val localJob = async {
                try {
                    watchlistRepository.getWatchlistItems()
                } catch (e: Exception) {
                    null
                }
            }
            val cloudJob = async {
                try {
                    watchlistRepository.pullWatchlistFromCloud()
                } catch (_: Exception) {}
            }

            val localItems = localJob.await()
            if (localItems != null && localItems.isNotEmpty()) {
                _uiState.value = updateItems(WatchlistUiState(isLoading = false), localItems)
            }

            cloudJob.await()

            if (_uiState.value.items.isEmpty()) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun toggleUnwatchedFilter() {
        val newFilter = !_uiState.value.showUnwatchedOnly
        _uiState.value = updateItems(
            _uiState.value.copy(showUnwatchedOnly = newFilter),
            _uiState.value.items
        )
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val items = watchlistRepository.refreshWatchlistItems()
                _uiState.value = updateItems(_uiState.value, items).copy(
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    toastMessage = "Failed to refresh",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun removeFromWatchlist(item: MediaItem) {
        viewModelScope.launch {
            try {
                val updatedItems = _uiState.value.items.filter { it.id != item.id || it.mediaType != item.mediaType }
                _uiState.value = updateItems(_uiState.value, updatedItems).copy(
                    toastMessage = "Removed from watchlist",
                    toastType = ToastType.SUCCESS
                )
                watchlistRepository.removeFromWatchlist(item.mediaType, item.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to remove from watchlist",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun dismissToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }
}
