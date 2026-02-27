package com.arflix.tv.ui.startup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * StartupViewModel - Handles parallel loading during splash screen
 * Pre-loads home categories + hero assets while user is on profile selection
 */
data class StartupState(
    val isLoading: Boolean = true,
    val isReady: Boolean = false,
    val loadingProgress: Float = 0f,
    val loadingMessage: String = "Starting...",
    val categories: List<Category> = emptyList(),
    val heroItem: MediaItem? = null,
    val heroLogoUrl: String? = null,
    val logoCache: Map<String, String> = emptyMap(),
    val isAuthenticated: Boolean = false,
    val error: String? = null
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class StartupViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val imageLoader: ImageLoader,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val networkDispatcher = Dispatchers.IO.limitedParallelism(8)
    private val heroLogoPreloadWidth = 300
    private val heroLogoPreloadHeight = 70
    private val heroBackdropPreloadWidth = 1280
    private val heroBackdropPreloadHeight = 720

    private val _state = MutableStateFlow(StartupState())
    val state: StateFlow<StartupState> = _state.asStateFlow()

    init {
        startParallelLoading()
    }

    private fun startParallelLoading() {
        viewModelScope.launch(networkDispatcher) {
            try {
                updateProgress(0.3f, "Loading catalogs...")

                // Fetch home categories while user is on profile selection screen
                val categories = try {
                    mediaRepository.getHomeCategories()
                } catch (e: Exception) {
                    emptyList()
                }

                if (categories.isNotEmpty()) {
                    val heroItem = categories.firstOrNull()?.items?.firstOrNull()

                    updateProgress(0.6f, "Loading content...")

                    _state.value = _state.value.copy(
                        categories = categories,
                        heroItem = heroItem
                    )

                    // Prefetch hero assets in parallel
                    if (heroItem != null) {
                        prefetchHeroAssets(heroItem)
                    }

                    // Prefetch logos for first 2 rows
                    val logoCache = mutableMapOf<String, String>()
                    val logoJobs = categories.take(2).flatMap { it.items.take(5) }.map { item ->
                        async(networkDispatcher) {
                            try {
                                val key = "${item.mediaType}_${item.id}"
                                val logo = mediaRepository.getLogoUrl(item.mediaType, item.id)
                                if (!logo.isNullOrBlank()) key to logo else null
                            } catch (e: Exception) { null }
                        }
                    }
                    logoJobs.forEach { job ->
                        val result = try { job.await() } catch (e: Exception) { null }
                        if (result != null) logoCache[result.first] = result.second
                    }

                    _state.value = _state.value.copy(
                        logoCache = logoCache,
                        isLoading = false,
                        isReady = true
                    )
                    updateProgress(1.0f, "Ready!")
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isReady = true
                    )
                    updateProgress(1.0f, "Ready!")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    isReady = true,
                    error = e.message
                )
            }
        }
    }

    private fun updateProgress(progress: Float, message: String) {
        _state.value = _state.value.copy(
            loadingProgress = progress,
            loadingMessage = message
        )
    }

    private fun prefetchHeroAssets(heroItem: MediaItem?) {
        if (heroItem == null) return

        val backdropUrl = heroItem.backdrop ?: heroItem.image
        if (!backdropUrl.isNullOrBlank()) {
            val request = ImageRequest.Builder(context)
                .data(backdropUrl)
                .size(heroBackdropPreloadWidth, heroBackdropPreloadHeight)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .build()
            imageLoader.enqueue(request)
        }

        viewModelScope.launch(networkDispatcher) {
            try {
                val logoUrl = mediaRepository.getLogoUrl(heroItem.mediaType, heroItem.id)
                if (!logoUrl.isNullOrBlank()) {
                    val request = ImageRequest.Builder(context)
                        .data(logoUrl)
                        .size(heroLogoPreloadWidth, heroLogoPreloadHeight)
                        .precision(Precision.INEXACT)
                        .allowHardware(true)
                        .build()
                    imageLoader.enqueue(request)
                    val cacheKey = "${heroItem.mediaType}_${heroItem.id}"
                    val currentCache = _state.value.logoCache.toMutableMap()
                    currentCache[cacheKey] = logoUrl
                    _state.value = _state.value.copy(
                        heroLogoUrl = logoUrl,
                        logoCache = currentCache
                    )
                }
            } catch (e: Exception) {
                // Hero logo preload failed
            }
        }
    }
}
