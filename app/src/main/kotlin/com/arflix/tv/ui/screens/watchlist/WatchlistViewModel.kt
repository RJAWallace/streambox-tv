package com.arflix.tv.ui.screens.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
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
    val error: String? = null,
    // Toast
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.INFO
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val watchlistRepository: WatchlistRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    /** Sets items + filtered movieItems/tvItems in one shot. */
    private fun updateItems(state: WatchlistUiState, items: List<MediaItem>): WatchlistUiState {
        return state.copy(
            items = items,
            movieItems = items.filter { it.mediaType == MediaType.MOVIE },
            tvItems = items.filter { it.mediaType == MediaType.TV }
        )
    }

    init {
        // Show cached items instantly, then refresh in background
        loadWatchlistInstant()
        // Also observe the repository's StateFlow for live updates
        observeWatchlistChanges()
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
            // Show cached items INSTANTLY (no loading state if we have cache)
            val cachedItems = watchlistRepository.getCachedItems()
            if (cachedItems.isNotEmpty()) {
                _uiState.value = updateItems(WatchlistUiState(isLoading = false), cachedItems)
            } else {
                // Only show loading if no cache
                _uiState.value = WatchlistUiState(isLoading = true)
            }

            // Run local load AND cloud pull IN PARALLEL for faster results
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
                    // Cloud pull updates via StateFlow observer
                } catch (_: Exception) {}
            }

            // Show local items as soon as they're ready
            val localItems = localJob.await()
            if (localItems != null && localItems.isNotEmpty()) {
                _uiState.value = updateItems(WatchlistUiState(isLoading = false), localItems)
            }
            // Don't show empty state yet — wait for cloud pull to finish first

            // Cloud pull completes; StateFlow observer handles UI updates
            cloudJob.await()

            // Now if still empty after both loads, it's genuinely empty
            if (_uiState.value.items.isEmpty()) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
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
                // Optimistic update - remove from local state immediately
                val updatedItems = _uiState.value.items.filter { it.id != item.id || it.mediaType != item.mediaType }
                _uiState.value = updateItems(_uiState.value, updatedItems).copy(
                    toastMessage = "Removed from watchlist",
                    toastType = ToastType.SUCCESS
                )
                // Then sync to backend
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


