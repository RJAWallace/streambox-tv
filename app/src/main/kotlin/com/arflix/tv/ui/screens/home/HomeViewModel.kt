package com.arflix.tv.ui.screens.home

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.WatchProgress
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.TraktSyncService
import com.arflix.tv.data.repository.ContinueWatchingItem
import com.arflix.tv.data.repository.CatalogRepository
import com.arflix.tv.data.repository.StreamRepository
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.data.repository.SyncStatus
import com.arflix.tv.data.repository.WatchHistoryRepository
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelAndJoin
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val isInitialLoad: Boolean = true,
    val categories: List<Category> = emptyList(),
    val error: String? = null,
    // Current hero (may update during transitions)
    val heroItem: MediaItem? = null,
    val heroLogoUrl: String? = null,
    val cardLogoUrls: Map<String, String> = emptyMap(),
    // Previous hero for crossfade (Phase 2.1)
    val previousHeroItem: MediaItem? = null,
    val previousHeroLogoUrl: String? = null,
    // Transition state for animations
    val isHeroTransitioning: Boolean = false,
    val isAuthenticated: Boolean = false,
    // CW check complete — prevents showing trending then scrolling to CW
    val cwCheckComplete: Boolean = false,
    // Server CW data fetched from Supabase (not just disk cache)
    val serverCwLoaded: Boolean = false,
    // Cloud watchlist pulled from Supabase
    val serverWatchlistLoaded: Boolean = false,
    // True when CW + catalogues + watchlist are all loaded — HomeScreen shows loading until this is true
    val initialDataReady: Boolean = false,
    // Toast
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.INFO
)

enum class ToastType {
    SUCCESS, ERROR, INFO
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val catalogRepository: CatalogRepository,
    private val streamRepository: StreamRepository,
    private val traktRepository: TraktRepository,
    private val traktSyncService: TraktSyncService,
    private val iptvRepository: IptvRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val watchlistRepository: WatchlistRepository,
    private val imageLoader: ImageLoader,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private data class HeroDetailsSnapshot(
        val duration: String,
        val releaseDate: String?,
        val imdbRating: String,
        val tmdbRating: String,
        val budget: Long?
    )

    private data class CategoryPaginationState(
        var loadedCount: Int = 0,
        var hasMore: Boolean = true,
        var isLoading: Boolean = false
    )

    private fun isCustomCatalogConfig(cfg: CatalogConfig): Boolean {
        return !cfg.isPreinstalled ||
            cfg.id.startsWith("custom_") ||
            !cfg.sourceUrl.isNullOrBlank() ||
            !cfg.sourceRef.isNullOrBlank()
    }

    private fun hasRealItems(category: Category?): Boolean {
        return category?.items?.any { !it.isPlaceholder } == true
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val isLowRamDevice = activityManager.isLowRamDevice || activityManager.memoryClass <= 256
    // Keep IO concurrency conservative on TV hardware to avoid starving UI thread.
    private val networkParallelism = if (isLowRamDevice) 2 else 3
    private val networkDispatcher = Dispatchers.IO.limitedParallelism(networkParallelism)
    private var lastContinueWatchingItems: List<MediaItem> = emptyList()
    private var lastContinueWatchingUpdateMs: Long = 0L
    private var lastResolvedBaseCategories: List<Category> = emptyList()
    private val CONTINUE_WATCHING_REFRESH_MS = 45_000L
    private val HOME_PLACEHOLDER_ITEM_COUNT = 8

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val _cardLogoUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val cardLogoUrls: StateFlow<Map<String, String>> = _cardLogoUrls.asStateFlow()

    // Debounce job for hero updates (Phase 6.1)
    private var pendingHeroLoadKey: String? = null
    private var heroUpdateJob: Job? = null
    private var heroDetailsJob: Job? = null
    private var prefetchJob: Job? = null
    private var preloadCategoryJob: Job? = null
    private var preloadCategoryPriorityJob: Job? = null
    private var customCatalogsJob: Job? = null
    private var loadHomeJob: Job? = null
    private var refreshContinueWatchingJob: Job? = null
    private var loadHomeRequestId: Long = 0L
    private val HERO_DEBOUNCE_MS = 80L // Short debounce; focus idle is handled in HomeScreen

    // Phase 6.2-6.3: Fast scroll detection
    private var lastFocusChangeTime = 0L
    private var consecutiveFastChanges = 0
    private val FAST_SCROLL_THRESHOLD_MS = 650L  // Under 650ms = fast scrolling
    private val FAST_SCROLL_DEBOUNCE_MS = 620L   // Higher debounce during fast scroll

    private val FOCUS_PREFETCH_COALESCE_MS = if (isLowRamDevice) 70L else 45L

    private val logoPreloadWidth = if (isLowRamDevice) 260 else 300
    private val logoPreloadHeight = if (isLowRamDevice) 60 else 70
    private val cardBackdropWidth = (240 * context.resources.displayMetrics.density)
        .toInt()
        .coerceAtLeast(1)
    private val cardBackdropHeight = (cardBackdropWidth / (16f / 9f))
        .toInt()
        .coerceAtLeast(1)
    private val backdropPreloadWidth = cardBackdropWidth
    private val backdropPreloadHeight = cardBackdropHeight
    private val initialLogoPrefetchRows = 1
    private val initialLogoPrefetchItemsPerRow = if (isLowRamDevice) 3 else 4
    private val initialBackdropPrefetchItems = if (isLowRamDevice) 2 else 2
    private val incrementalLogoPrefetchItems = if (isLowRamDevice) 3 else 4
    private val prioritizedLogoPrefetchItems = if (isLowRamDevice) 6 else 5
    private val incrementalBackdropPrefetchItems = if (isLowRamDevice) 2 else 2
    private val initialCategoryItemCap = if (isLowRamDevice) 28 else 40
    private val categoryPageSize = if (isLowRamDevice) 14 else 20
    private val nearEndThreshold = 4

    // Track current focus for ahead-of-focus preloading
    private var currentRowIndex = 0
    private var currentItemIndex = 0

    // Track if preloaded data was used to avoid duplicate loading
    private var usedPreloadedData = false

    private val maxLogoCacheEntries = if (isLowRamDevice) 220 else 420
    private val logoCacheLock = Any()
    private val logoCache = LinkedHashMap<String, String>(maxLogoCacheEntries + 32, 0.75f, true)
    private var logoCacheRevision: Long = 0L
    private var lastPublishedLogoCacheRevision: Long = -1L
    private val logoFetchInFlight = Collections.synchronizedSet(mutableSetOf<String>())
    private val heroDetailsCache = ConcurrentHashMap<String, HeroDetailsSnapshot>()
    private val savedCatalogById = ConcurrentHashMap<String, CatalogConfig>()
    private val categoryPaginationStates = ConcurrentHashMap<String, CategoryPaginationState>()
    private val preloadedRequests = Collections.synchronizedSet(mutableSetOf<String>())
    private var logoCachePublishJob: Job? = null
    @Volatile
    private var pendingLogoPublishPriority: Boolean = false
    private var lastLogoCachePublishMs: Long = 0L
    private val LOGO_CACHE_PUBLISH_THROTTLE_MS = if (isLowRamDevice) 900L else 420L
    private val LOGO_CACHE_IDLE_REQUIRED_MS = if (isLowRamDevice) 820L else 480L
    private val LOGO_CACHE_FAST_SCROLL_IDLE_MS = if (isLowRamDevice) 260L else 180L

    private fun getCachedLogo(key: String): String? = synchronized(logoCacheLock) {
        logoCache[key]
    }

    private fun hasCachedLogo(key: String): Boolean = synchronized(logoCacheLock) {
        logoCache.containsKey(key)
    }

    private fun putCachedLogo(key: String, value: String): Boolean {
        synchronized(logoCacheLock) {
            val existing = logoCache[key]
            if (existing == value) return false
            logoCache[key] = value
            while (logoCache.size > maxLogoCacheEntries) {
                val oldestKey = logoCache.entries.iterator().next().key
                logoCache.remove(oldestKey)
            }
            logoCacheRevision += 1L
            return true
        }
    }

    private fun putCachedLogos(entries: Map<String, String>): Boolean {
        if (entries.isEmpty()) return false
        var changed = false
        synchronized(logoCacheLock) {
            entries.forEach { (key, value) ->
                if (logoCache[key] != value) {
                    logoCache[key] = value
                    changed = true
                }
            }
            if (changed) {
                while (logoCache.size > maxLogoCacheEntries) {
                    val oldestKey = logoCache.entries.iterator().next().key
                    logoCache.remove(oldestKey)
                }
                logoCacheRevision += 1L
            }
        }
        return changed
    }

    private fun snapshotLogoCache(): Map<String, String> = synchronized(logoCacheLock) {
        LinkedHashMap(logoCache)
    }

    private fun publishLogoCacheSnapshotIfChanged() {
        val snapshot: Map<String, String>
        synchronized(logoCacheLock) {
            if (logoCacheRevision == lastPublishedLogoCacheRevision) return
            snapshot = LinkedHashMap(logoCache)
            lastPublishedLogoCacheRevision = logoCacheRevision
        }
        lastLogoCachePublishMs = SystemClock.elapsedRealtime()
        _cardLogoUrls.value = snapshot
    }

    private fun scheduleLogoCachePublish(highPriority: Boolean = false) {
        if (highPriority) {
            pendingLogoPublishPriority = true
        }
        val idleElapsedAtSchedule = System.currentTimeMillis() - lastFocusChangeTime
        if (highPriority && idleElapsedAtSchedule < LOGO_CACHE_FAST_SCROLL_IDLE_MS) {
            // During rapid D-pad movement, avoid forcing immediate full-map publishes.
            pendingLogoPublishPriority = false
        }
        if (logoCachePublishJob?.isActive == true) {
            if (highPriority) {
                logoCachePublishJob?.cancel()
            } else {
                return
            }
        }
        logoCachePublishJob = viewModelScope.launch {
            val priorityNow = pendingLogoPublishPriority
            pendingLogoPublishPriority = false
            if (priorityNow) {
                val elapsedSincePublish = SystemClock.elapsedRealtime() - lastLogoCachePublishMs
                val priorityThrottleMs = if (isLowRamDevice) 120L else 80L
                val throttleWaitMs = (priorityThrottleMs - elapsedSincePublish).coerceAtLeast(0L)
                val idleElapsedMs = System.currentTimeMillis() - lastFocusChangeTime
                val idleWaitMs = (LOGO_CACHE_FAST_SCROLL_IDLE_MS - idleElapsedMs).coerceAtLeast(0L)
                val waitMs = maxOf(throttleWaitMs, idleWaitMs)
                if (waitMs > 0L) delay(waitMs)
            } else {
                while (true) {
                    val elapsedSincePublish = SystemClock.elapsedRealtime() - lastLogoCachePublishMs
                    val throttleWaitMs = (LOGO_CACHE_PUBLISH_THROTTLE_MS - elapsedSincePublish).coerceAtLeast(0L)
                    val idleElapsedMs = System.currentTimeMillis() - lastFocusChangeTime
                    val idleWaitMs = (LOGO_CACHE_IDLE_REQUIRED_MS - idleElapsedMs).coerceAtLeast(0L)
                    val waitMs = maxOf(throttleWaitMs, idleWaitMs)
                    if (waitMs <= 0L) break
                    delay(waitMs)
                }
            }
            publishLogoCacheSnapshotIfChanged()
        }
    }

    /**
     * Check if initial data (CW + catalogues) is fully loaded.
     * When ready, set initialDataReady = true so HomeScreen can dismiss its loading screen.
     */
    private fun markInitialDataReadyIfComplete() {
        val state = _uiState.value
        if (!state.initialDataReady && state.cwCheckComplete && !state.isInitialLoad
            && state.serverCwLoaded && state.serverWatchlistLoaded) {
            _uiState.value = state.copy(initialDataReady = true)
        }
    }

    // ─── Profile-aware lifecycle ─────────────────────────────────────────────
    // HomeScreen is the NavHost startDestination and never leaves the back stack.
    // HomeViewModel is created ONCE at app launch and reused across profile switches.
    // init{} sets up long-lived observers; profile-specific loading
    // is triggered by ensureLoadedForProfile().

    /** The profile ID that data was last loaded for. */
    @Volatile
    private var loadedForProfileId: String? = null

    /** Track all profile-specific jobs so we can cancel them on profile switch. */
    private var profileLoadJobs = mutableListOf<Job>()
    private var safetyTimeoutJob: Job? = null

    /**
     * Called by HomeScreen when the active profile changes.
     * If the profile is different from what was last loaded, resets state and reloads.
     * If it's the same profile, does nothing (fast path for back-navigation).
     */
    fun ensureLoadedForProfile(profileId: String) {
        com.arflix.tv.util.RemoteCrashLogger.checkpoint("HomeVM", "ensureLoadedForProfile: $profileId (was: $loadedForProfileId)")
        if (loadedForProfileId == profileId) return
        loadedForProfileId = profileId
        reloadForNewProfile()
    }

    /**
     * Full reset + reload for a new profile. Cancels all in-flight work,
     * resets UI state to loading, then starts fresh data loading.
     * This is the Netflix/Disney+ pattern: same ViewModel, fresh data.
     */
    private fun reloadForNewProfile() {
        // Cancel all profile-specific work
        profileLoadJobs.forEach { it.cancel() }
        profileLoadJobs.clear()
        loadHomeJob?.cancel()
        refreshContinueWatchingJob?.cancel()
        heroUpdateJob?.cancel()
        prefetchJob?.cancel()
        preloadCategoryJob?.cancel()
        preloadCategoryPriorityJob?.cancel()
        customCatalogsJob?.cancel()
        safetyTimeoutJob?.cancel()

        // Reset profile-specific caches
        lastContinueWatchingItems = emptyList()
        lastContinueWatchingUpdateMs = 0L
        lastResolvedBaseCategories = emptyList()
        usedPreloadedData = false
        synchronized(logoCacheLock) { logoCache.clear(); logoCacheRevision = 0L; lastPublishedLogoCacheRevision = -1L }
        logoFetchInFlight.clear()
        heroDetailsCache.clear()
        savedCatalogById.clear()
        categoryPaginationStates.clear()
        preloadedRequests.clear()
        pendingHeroLoadKey = null
        currentRowIndex = 0
        currentItemIndex = 0

        // Reset UI state to show loading screen
        _uiState.value = HomeUiState()
        _cardLogoUrls.value = emptyMap()

        // Start profile-specific loading
        startProfileDataLoad()
    }

    /**
     * Loads all profile-specific data: CW from cache, categories, watchlist, etc.
     * Each launch is tracked so it can be cancelled on profile switch.
     */
    private fun startProfileDataLoad() {
        // 1. Load Continue Watching from disk cache immediately
        profileLoadJobs += viewModelScope.launch {
            try {
                val cached = traktRepository.preloadContinueWatchingCache()
                if (cached.isNotEmpty()) {
                    val merged = mergeContinueWatchingResumeData(cached)
                    val cwCategory = Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = merged.map { it.toMediaItem() }.deduplicateItems()
                    )
                    cwCategory.items.forEach { mediaRepository.cacheItem(it) }
                    lastContinueWatchingItems = cwCategory.items
                    lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                    val updated = _uiState.value.categories.toMutableList()
                    val idx = updated.indexOfFirst { it.id == "continue_watching" }
                    if (idx >= 0) updated[idx] = cwCategory else updated.add(0, cwCategory)
                    _uiState.value = _uiState.value.copy(categories = updated)
                }
            } catch (e: Exception) {
                System.err.println("HomeVM: preload CW cache failed: ${e.message}")
            }
            _uiState.value = _uiState.value.copy(cwCheckComplete = true)
            markInitialDataReadyIfComplete()
        }

        // 2. Load home categories (the big one)
        loadHomeData()

        // 3. Refresh CW from Supabase after initial load settles
        profileLoadJobs += viewModelScope.launch {
            delay(3000L)
            try { refreshContinueWatchingOnly() }
            catch (e: Exception) { System.err.println("HomeVM: initial CW refresh failed: ${e.message}") }
        }

        // 4. Refresh CW when Trakt auth completes
        profileLoadJobs += viewModelScope.launch {
            try {
                traktRepository.isAuthenticated.filter { it }.first()
                refreshContinueWatchingOnly()
            } catch (_: Exception) { }
        }

        // 5. Pre-warm watchlist cache + pull from cloud before showing home
        profileLoadJobs += viewModelScope.launch {
            try {
                watchlistRepository.getWatchlistItems()
                watchlistRepository.pullWatchlistFromCloud()
            } catch (_: Exception) {}
            // Mark watchlist as synced so loading screen can dismiss
            _uiState.value = _uiState.value.copy(serverWatchlistLoaded = true)
            markInitialDataReadyIfComplete()
        }

        // 6. Safety timeout — force-show home after 8s no matter what
        safetyTimeoutJob = viewModelScope.launch {
            delay(8000L)
            if (!_uiState.value.initialDataReady) {
                System.err.println("HomeVM: Safety timeout — forcing initialDataReady after 8s")
                _uiState.value = _uiState.value.copy(
                    initialDataReady = true,
                    cwCheckComplete = true,
                    isInitialLoad = false,
                    serverCwLoaded = true,
                    serverWatchlistLoaded = true
                )
            }
        }
    }

    init {
        com.arflix.tv.util.RemoteCrashLogger.checkpoint("HomeVM", "init start")
        // ── ONE-TIME OBSERVERS (survive profile switches) ──
        // These are long-lived collectors that never need to restart.

        // Sync events → refresh CW when Trakt sync completes
        viewModelScope.launch {
            try {
                traktSyncService.syncEvents.collect { status ->
                    if (status == SyncStatus.COMPLETED) {
                        refreshContinueWatchingOnly()
                    }
                }
            } catch (_: Exception) { }
        }

        // Catalog changes → reload home data when user adds/removes/reorders catalogs
        viewModelScope.launch {
            try {
                catalogRepository.observeCatalogs()
                    .map { catalogs ->
                        catalogs.joinToString("|") { "${it.id}:${it.title}:${it.sourceUrl.orEmpty()}" }
                    }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { loadHomeData() }
            } catch (_: Exception) { }
        }

        // IPTV warmup (one-time, low priority)
        viewModelScope.launch(Dispatchers.IO) {
            delay(if (isLowRamDevice) 6000L else 2500L)
            runCatching { iptvRepository.warmupFromCacheOnly() }
        }

        // NOTE: No profile-specific loading here!
        // ensureLoadedForProfile() triggers startProfileDataLoad() when the profile is known.
        com.arflix.tv.util.RemoteCrashLogger.checkpoint("HomeVM", "init complete")
    }

    /**
     * Set preloaded data from StartupViewModel for instant display.
     * Caches logos and hero info for use once loadHomeData() finishes.
     *
     * IMPORTANT: Does NOT set isInitialLoad = false. The loading screen
     * must stay visible until loadHomeData() actually completes with real data.
     * Setting isInitialLoad here was causing premature initialDataReady = true
     * which dismissed the loading screen before data was ready → crash.
     */
    fun setPreloadedData(
        categories: List<Category>,
        heroItem: MediaItem?,
        heroLogoUrl: String?,
        logoCache: Map<String, String>
    ) {

        if (usedPreloadedData) {
            if (logoCache.isNotEmpty()) {
                if (putCachedLogos(logoCache)) {
                    publishLogoCacheSnapshotIfChanged()
                }
            }
            val currentState = _uiState.value
            if (heroLogoUrl != null && currentState.heroLogoUrl == null) {
                _uiState.value = currentState.copy(heroLogoUrl = heroLogoUrl)
            }
            return
        }
        if (categories.isEmpty()) {
            return
        }

        usedPreloadedData = true

        // Cache logos from startup preload — they'll be available once loadHomeData finishes
        putCachedLogos(logoCache)

        // Store hero info as fallback — loadHomeData will overwrite with real data
        if (heroItem != null) {
            _uiState.value = _uiState.value.copy(
                heroItem = heroItem,
                heroLogoUrl = heroLogoUrl
            )
        }
    }

    private fun loadHomeData() {
        loadHomeJob?.cancel()
        val requestId = ++loadHomeRequestId
        loadHomeJob = viewModelScope.launch loadHome@{
            // Skip delay - preloading now happens on profile focus for instant display
            // Only add minimal delay if no preloaded data exists yet
            if (!usedPreloadedData) {
                delay(50) // Minimal delay for LaunchedEffect to potentially set preloaded data
            }
            if (requestId != loadHomeRequestId) return@loadHome

            try {
                // Launch CW Supabase fetch concurrently with category loading.
                // This runs in parallel so server CW data is ready when categories finish.
                val cwRawDeferred = async(networkDispatcher) {
                    try { loadContinueWatchingFromHistoryRaw() }
                    catch (_: Exception) { emptyList() }
                }

                val cachedContinueWatching = traktRepository.preloadContinueWatchingCache()
                val savedCatalogs = withContext(networkDispatcher) {
                    runCatching {
                        val addons = streamRepository.installedAddons.first()
                        catalogRepository.syncAddonCatalogs(addons)
                        catalogRepository.ensurePreinstalledDefaults(
                            mediaRepository.getDefaultCatalogConfigs()
                        )
                    }.getOrElse { mediaRepository.getDefaultCatalogConfigs() }
                }
                savedCatalogById.clear()
                savedCatalogs.forEach { savedCatalogById[it.id] = it }
                categoryPaginationStates.clear()

                // When Home is opened from profile selection, avoid an empty frame by showing
                // profile-ordered skeleton rows immediately while real catalogs load.
                if (_uiState.value.categories.isEmpty()) {
                    val skeletonCategories = buildProfileSkeletonCategories(
                        savedCatalogs = savedCatalogs,
                        cachedContinueWatching = cachedContinueWatching
                    )
                    if (requestId != loadHomeRequestId) return@loadHome
                    if (skeletonCategories.isNotEmpty()) {
                        val skeletonHero = skeletonCategories
                            .asSequence()
                            .flatMap { it.items.asSequence() }
                            .firstOrNull { !it.isPlaceholder }
                        _uiState.value = _uiState.value.copy(
                            isLoading = true,
                            isInitialLoad = false,
                            categories = skeletonCategories,
                            heroItem = skeletonHero,
                            heroLogoUrl = null,
                            error = null
                        )
                    }
                } else {
                    // Keep preloaded/previous UI visible and refresh in background.
                    _uiState.value = _uiState.value.copy(isLoading = false, error = null)
                }

                val currentBaseCategories = _uiState.value.categories.filter { it.id != "continue_watching" }
                val categories = withContext(networkDispatcher) {
                    val baseCategories = runCatching {
                        mediaRepository.getHomeCategories()
                    }.getOrElse { emptyList() }

                    val baseById = LinkedHashMap<String, Category>().apply {
                        currentBaseCategories.forEach { put(it.id, it) }
                        baseCategories.forEach { put(it.id, it) }
                    }

                    val preinstalled = savedCatalogs
                        .filter { it.isPreinstalled }
                        .mapNotNull { baseById[it.id] }
                    val customCatalogConfigs = savedCatalogs.filter { cfg -> isCustomCatalogConfig(cfg) }
                    val stickyCustomById = currentBaseCategories
                        .filter { category ->
                            customCatalogConfigs.any { it.id == category.id } && category.items.isNotEmpty()
                        }
                        .associateBy { it.id }

                    // Only show categories that appear in the user's saved catalog list.
                    // This ensures hidden/removed preinstalled catalogs never flash on screen.
                    val allowedCatalogIds = savedCatalogs.map { it.id }.toSet()
                    val resolved = mutableListOf<Category>()
                    if (preinstalled.isNotEmpty()) {
                        resolved.addAll(preinstalled)
                    } else if (baseCategories.isNotEmpty()) {
                        resolved.addAll(baseCategories.filter { allowedCatalogIds.contains(it.id) })
                    } else if (currentBaseCategories.isNotEmpty()) {
                        resolved.addAll(currentBaseCategories.filter { allowedCatalogIds.contains(it.id) })
                    } else if (lastResolvedBaseCategories.isNotEmpty()) {
                        resolved.addAll(lastResolvedBaseCategories.filter { allowedCatalogIds.contains(it.id) })
                    }
                    customCatalogConfigs.forEach { cfg ->
                        val stickyCategory = stickyCustomById[cfg.id] ?: return@forEach
                        if (resolved.none { it.id == stickyCategory.id }) {
                            resolved.add(stickyCategory)
                        }
                    }
                    resolved
                }
                if (categories.any { it.id != "continue_watching" }) {
                    lastResolvedBaseCategories = categories.filter { it.id != "continue_watching" }
                }
                categories.forEach { category ->
                    if (category.id != "continue_watching") {
                        categoryPaginationStates[category.id] = CategoryPaginationState(
                            loadedCount = category.items.size,
                            hasMore = category.items.size >= categoryPageSize
                        )
                    }
                }
                if (requestId != loadHomeRequestId) return@loadHome

                // Only show continue watching from profile-specific cache
                // Don't use lastContinueWatchingItems fallback to prevent cross-profile data leakage
                if (cachedContinueWatching.isNotEmpty()) {
                    val mergedCachedContinueWatching = mergeContinueWatchingResumeData(cachedContinueWatching)
                    val continueWatchingCategory = Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = mergedCachedContinueWatching.map { it.toMediaItem() }.deduplicateItems()
                    )
                    continueWatchingCategory.items.forEach { mediaRepository.cacheItem(it) }
                    lastContinueWatchingItems = continueWatchingCategory.items
                    lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                    categories.add(0, continueWatchingCategory)
                } else {
                    // Preserve Continue Watching that refreshContinueWatchingOnly() may have
                    // already added (or placeholder CW from setPreloadedData) while we were
                    // loading categories. Including placeholders keeps CW visible at index 0
                    // until real data arrives, preventing focus index shifts that crash the app.
                    val existingCW = _uiState.value.categories.firstOrNull {
                        it.id == "continue_watching" && it.items.isNotEmpty()
                    }
                    if (existingCW != null) {
                        categories.add(0, existingCW)
                    }
                }

                val heroItem = categories.firstOrNull()?.items?.firstOrNull()

                // Preload logos for the first visible rows so card overlays appear immediately.
                val itemsToPreload = categories
                    .take(initialLogoPrefetchRows)
                    .flatMap { it.items.take(initialLogoPrefetchItemsPerRow) }
                val logoJobs = itemsToPreload.map { item ->
                    async(networkDispatcher) {
                        val key = "${item.mediaType}_${item.id}"
                        try {
                            val logoUrl = mediaRepository.getLogoUrl(item.mediaType, item.id)
                            if (logoUrl != null) key to logoUrl else null
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                val logoResults = logoJobs.awaitAll().filterNotNull().toMap()
                if (requestId != loadHomeRequestId) return@loadHome

                // Phase 1.2: Preload actual images with Coil
                preloadLogoImages(logoResults.values.toList())

                // Also preload backdrop images for first row
                val backdropUrls = categories.firstOrNull()?.items?.take(initialBackdropPrefetchItems)?.mapNotNull {
                    it.backdrop ?: it.image
                } ?: emptyList()
                preloadBackdropImages(backdropUrls)

                val heroLogoUrl = heroItem?.let { item ->
                    val key = "${item.mediaType}_${item.id}"
                    getCachedLogo(key) ?: logoResults[key]
                }

                putCachedLogos(logoResults)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isInitialLoad = false,
                    categories = categories,
                    heroItem = heroItem,
                    heroLogoUrl = heroLogoUrl,
                    isAuthenticated = traktRepository.isAuthenticated.first(),
                    error = null
                )
                _cardLogoUrls.value = snapshotLogoCache()
                refreshWatchedBadges()
                val allCatalogs = catalogRepository.getCatalogs()
                loadCustomCatalogsIncrementally(allCatalogs)

                // Await server CW data (launched concurrently at start of loadHomeData).
                // This ensures the loading screen doesn't dismiss until server CW is ready.
                val rawCwItems = cwRawDeferred.await()
                if (requestId != loadHomeRequestId) return@loadHome

                // Phase 1: Display server CW items with cached enriched data for descriptions
                if (rawCwItems.isNotEmpty()) {
                    val cachedEnriched = traktRepository.getCachedContinueWatching()
                    val enrichedById = cachedEnriched.associateBy { it.id }
                    val mergedRaw = rawCwItems.map { raw ->
                        val cached = enrichedById[raw.id]
                        if (cached != null && cached.overview.isNotEmpty()) {
                            raw.copy(
                                overview = cached.overview,
                                year = cached.year.ifEmpty { raw.year },
                                imdbRating = cached.imdbRating.ifEmpty { raw.imdbRating },
                                duration = cached.duration.ifEmpty { raw.duration },
                                episodeTitle = raw.episodeTitle ?: cached.episodeTitle
                            )
                        } else raw
                    }
                    val rawCategory = Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = mergedRaw.map { it.toMediaItem() }.deduplicateItems()
                    )
                    rawCategory.items.forEach { mediaRepository.cacheItem(it) }
                    lastContinueWatchingItems = rawCategory.items
                    lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                    val rawUpdated = _uiState.value.categories.toMutableList()
                    val rawIdx = rawUpdated.indexOfFirst { it.id == "continue_watching" }
                    if (rawIdx >= 0) rawUpdated[rawIdx] = rawCategory else rawUpdated.add(0, rawCategory)
                    val cwIsFirst = rawUpdated.firstOrNull()?.id == "continue_watching"
                    val rawHero = if (cwIsFirst) rawCategory.items.firstOrNull() else null
                    if (rawHero != null) {
                        val heroKey = "${rawHero.mediaType}_${rawHero.id}"
                        _uiState.value = _uiState.value.copy(
                            categories = rawUpdated,
                            heroItem = rawHero,
                            heroLogoUrl = getCachedLogo(heroKey)
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(categories = rawUpdated)
                    }
                }

                // Server CW loaded — allow loading screen to dismiss (if watchlist also ready)
                _uiState.value = _uiState.value.copy(serverCwLoaded = true)
                markInitialDataReadyIfComplete()

                // Phase 2: Enrich CW with TMDB data in background (adds overview, year, rating)
                if (rawCwItems.isNotEmpty()) {
                    viewModelScope.launch {
                        if (requestId != loadHomeRequestId) return@launch

                        val enrichedItems = try {
                            traktRepository.enrichContinueWatchingItems(rawCwItems)
                        } catch (_: Exception) { rawCwItems }
                        if (requestId != loadHomeRequestId) return@launch

                        val supabaseItems = enrichedItems.ifEmpty { rawCwItems }
                        val mergedItems = try { mergeContinueWatchingResumeData(supabaseItems) } catch (_: Exception) { supabaseItems }
                        val continueWatchingCategory = Category(
                            id = "continue_watching",
                            title = "Continue Watching",
                            items = mergedItems.map { it.toMediaItem() }.deduplicateItems()
                        )
                        continueWatchingCategory.items.forEach { mediaRepository.cacheItem(it) }
                        lastContinueWatchingItems = continueWatchingCategory.items
                        lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                        // Persist enriched data to disk so next launch shows all items instantly
                        try { traktRepository.updateContinueWatchingCache(mergedItems) } catch (_: Exception) {}
                        val updated = _uiState.value.categories.toMutableList()
                        val index = updated.indexOfFirst { it.id == "continue_watching" }
                        if (index >= 0) {
                            updated[index] = continueWatchingCategory
                        } else {
                            updated.add(0, continueWatchingCategory)
                        }
                        // Update hero to first CW item when CW is at position 0
                        val cwIsFirst = updated.firstOrNull()?.id == "continue_watching"
                        val newHero = if (cwIsFirst) continueWatchingCategory.items.firstOrNull() else null
                        if (newHero != null) {
                            val heroKey = "${newHero.mediaType}_${newHero.id}"
                            val heroLogo = getCachedLogo(heroKey)
                            _uiState.value = _uiState.value.copy(
                                categories = updated,
                                heroItem = newHero,
                                heroLogoUrl = heroLogo
                            )
                            // Fetch logo if not cached
                            if (heroLogo == null) {
                                launch {
                                    try {
                                        val logo = withContext(networkDispatcher) {
                                            mediaRepository.getLogoUrl(newHero.mediaType, newHero.id)
                                        }
                                        if (logo != null && _uiState.value.heroItem?.id == newHero.id) {
                                            putCachedLogo(heroKey, logo)
                                            _uiState.value = _uiState.value.copy(heroLogoUrl = logo)
                                            scheduleLogoCachePublish()
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        } else {
                            _uiState.value = _uiState.value.copy(categories = updated)
                        }
                    }
                }
              } catch (e: Exception) {
                if (requestId != loadHomeRequestId) return@loadHome
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isInitialLoad = false,
                    serverCwLoaded = true,        // Don't block loading screen on error
                    error = if (_uiState.value.categories.isEmpty()) e.message ?: "Failed to load content" else null
                )
                markInitialDataReadyIfComplete()
            } finally {
            }
        }
    }

    private fun loadCustomCatalogsIncrementally(savedCatalogs: List<CatalogConfig>) {
        customCatalogsJob?.cancel()
        customCatalogsJob = viewModelScope.launch(networkDispatcher) {
            delay(if (isLowRamDevice) 1800L else 700L)
            val customCatalogs = savedCatalogs.filter { cfg -> isCustomCatalogConfig(cfg) }
            if (customCatalogs.isEmpty()) {
                // Even with no custom catalogs, re-publish to remove any stale categories
                // from previous loads that are no longer in savedCatalogs (e.g. hidden preinstalled).
                val allowedIds = savedCatalogs.map { it.id }.toSet()
                val currentCategories = _uiState.value.categories
                val filtered = currentCategories.filter { it.id == "continue_watching" || allowedIds.contains(it.id) }
                if (filtered.size < currentCategories.size) {
                    _uiState.value = _uiState.value.copy(categories = filtered)
                }
                return@launch
            }
            val customIds = customCatalogs.map { it.id }.toSet()
            val existingCustomById = _uiState.value.categories
                .filter { category -> customIds.contains(category.id) && category.items.isNotEmpty() }
                .associateBy { it.id }
            val baseCategories = _uiState.value.categories.filterNot { customIds.contains(it.id) }
            val baseById = baseCategories.associateBy { it.id }

            val loadedById = LinkedHashMap<String, Category>()
            fun publishMerged(currentState: HomeUiState) {
                // Read latest state for Continue Watching to avoid race condition
                // where refreshContinueWatchingOnly() adds CW between snapshot and write.
                val continueWatching = _uiState.value.categories.firstOrNull {
                    it.id == "continue_watching" && it.items.isNotEmpty()
                } ?: currentState.categories.firstOrNull {
                    it.id == "continue_watching" && it.items.isNotEmpty()
                }
                val merged = mutableListOf<Category>()
                if (continueWatching != null) {
                    merged.add(continueWatching)
                }
                savedCatalogs.forEach { cfg ->
                    val category = if (customIds.contains(cfg.id)) {
                        val loaded = loadedById[cfg.id]
                        // Prefer loaded data with real items; if loaded but empty, fall
                        // through to existing data so categories don't flash-disappear.
                        if (loaded != null && loaded.items.isNotEmpty()) loaded
                        else existingCustomById[cfg.id]
                            ?: currentState.categories.firstOrNull { it.id == cfg.id }
                            ?: loaded  // Final fallback: use the empty result to clear placeholders
                    } else {
                        baseById[cfg.id] ?: currentState.categories.firstOrNull { it.id == cfg.id }
                    }
                    val shouldInclude = category?.items?.isNotEmpty() == true
                    if (shouldInclude && category != null) {
                        merged.add(category)
                    }
                }
                if (merged.isNotEmpty()) {
                    currentState.heroItem?.let { hero ->
                        if (merged.none { cat -> cat.items.any { it.id == hero.id && it.mediaType == hero.mediaType } }) {
                            val fallbackHero = merged.firstOrNull()?.items?.firstOrNull()
                            _uiState.value = currentState.copy(categories = merged, heroItem = fallbackHero)
                            return
                        }
                    }
                    _uiState.value = currentState.copy(categories = merged)
                }
            }
            publishMerged(_uiState.value)

            customCatalogs.forEach { catalog ->
                val firstPage = runCatching {
                    mediaRepository.loadCustomCatalogPage(
                        catalog = catalog,
                        offset = 0,
                        limit = initialCategoryItemCap
                    )
                }.getOrNull()
                if (firstPage == null) {
                    // Load FAILED (network error, timeout, etc.) — preserve any existing data
                    // so the category doesn't flash-disappear while it still has cached items.
                    // Don't add to loadedById so publishMerged falls through to existingCustomById.
                    publishMerged(_uiState.value)
                    return@forEach
                }
                if (firstPage.items.isEmpty()) {
                    // Load succeeded but returned no items — mark as resolved-empty
                    // so placeholder rows are removed instead of sticking forever.
                    loadedById[catalog.id] = Category(
                        id = catalog.id,
                        title = catalog.title,
                        items = emptyList()
                    )
                    categoryPaginationStates[catalog.id] = CategoryPaginationState(
                        loadedCount = 0,
                        hasMore = false
                    )
                    publishMerged(_uiState.value)
                    return@forEach
                }

                loadedById[catalog.id] = Category(
                    id = catalog.id,
                    title = catalog.title,
                    items = firstPage.items
                )
                categoryPaginationStates[catalog.id] = CategoryPaginationState(
                    loadedCount = firstPage.items.size,
                    hasMore = firstPage.hasMore
                )
                val current = _uiState.value
                publishMerged(current)
            }
        }
    }

    fun maybeLoadNextPageForCategory(categoryId: String, focusedItemIndex: Int) {
        if (categoryId == "continue_watching") return
        val currentCategory = _uiState.value.categories.firstOrNull { it.id == categoryId } ?: return
        if (currentCategory.items.isEmpty() || currentCategory.items.all { it.isPlaceholder }) return
        if (focusedItemIndex < currentCategory.items.size - nearEndThreshold) return
        loadNextPageForCategory(categoryId)
    }

    private fun loadNextPageForCategory(categoryId: String) {
        val pagination = categoryPaginationStates.getOrPut(categoryId) {
            CategoryPaginationState(
                loadedCount = _uiState.value.categories.firstOrNull { it.id == categoryId }?.items?.size ?: 0
            )
        }
        if (!pagination.hasMore || pagination.isLoading) return

        pagination.isLoading = true
        viewModelScope.launch(networkDispatcher) {
            try {
                val currentCategories = _uiState.value.categories
                val currentCategory = currentCategories.firstOrNull { it.id == categoryId } ?: return@launch

                val result = if (savedCatalogById[categoryId]?.isPreinstalled == true) {
                    val nextPage = (currentCategory.items.size / categoryPageSize) + 1
                    mediaRepository.loadHomeCategoryPage(categoryId, nextPage)
                } else {
                    val catalog = savedCatalogById[categoryId] ?: return@launch
                    mediaRepository.loadCustomCatalogPage(
                        catalog = catalog,
                        offset = currentCategory.items.size,
                        limit = categoryPageSize
                    )
                }

                if (result.items.isEmpty()) {
                    pagination.hasMore = false
                    return@launch
                }

                val seen = currentCategory.items
                    .map { "${it.mediaType.name}_${it.id}" }
                    .toHashSet()
                val uniqueNewItems = result.items.filter { item ->
                    seen.add("${item.mediaType.name}_${item.id}")
                }
                if (uniqueNewItems.isEmpty()) {
                    pagination.hasMore = false
                    return@launch
                }

                val updatedCategories = currentCategories.map { category ->
                    if (category.id == categoryId) {
                        category.copy(items = category.items + uniqueNewItems)
                    } else {
                        category
                    }
                }

                uniqueNewItems.forEach { mediaRepository.cacheItem(it) }
                val logoEntries = uniqueNewItems.take(6).mapNotNull { item ->
                    val key = "${item.mediaType}_${item.id}"
                    if (hasCachedLogo(key) || !logoFetchInFlight.add(key)) return@mapNotNull null
                    val logo = runCatching {
                        mediaRepository.getLogoUrl(item.mediaType, item.id)
                    }.getOrNull()
                    logoFetchInFlight.remove(key)
                    if (logo == null) return@mapNotNull null
                    key to logo
                }
                if (logoEntries.isNotEmpty()) {
                    val logoMap = logoEntries.toMap()
                    if (putCachedLogos(logoMap)) {
                        scheduleLogoCachePublish()
                    }
                    preloadLogoImages(logoMap.values.toList())
                }
                preloadBackdropImages(uniqueNewItems.take(incrementalBackdropPrefetchItems).mapNotNull { it.backdrop ?: it.image })

                pagination.loadedCount = updatedCategories
                    .firstOrNull { it.id == categoryId }
                    ?.items
                    ?.size
                    ?: pagination.loadedCount
                pagination.hasMore = result.hasMore

                _uiState.value = _uiState.value.copy(categories = updatedCategories)
            } catch (_: Exception) {
                // Keep UI stable; user can retry naturally by continuing to browse the row.
            } finally {
                pagination.isLoading = false
            }
        }
    }

    private fun buildProfileSkeletonCategories(
        savedCatalogs: List<com.arflix.tv.data.model.CatalogConfig>,
        cachedContinueWatching: List<ContinueWatchingItem>
    ): List<Category> {
        val placeholderItems = (1..HOME_PLACEHOLDER_ITEM_COUNT).map { index ->
            MediaItem(
                id = -index,
                title = "",
                mediaType = MediaType.MOVIE,
                isPlaceholder = true
            )
        }

        val rows = mutableListOf<Category>()
        if (cachedContinueWatching.isNotEmpty()) {
            rows.add(
                Category(
                    id = "continue_watching",
                    title = "Continue Watching",
                    items = cachedContinueWatching.map { it.toMediaItem() }.deduplicateItems()
                )
            )
        } else {
            rows.add(
                Category(
                    id = "continue_watching",
                    title = "Continue Watching",
                    items = placeholderItems
                )
            )
        }

        savedCatalogs.forEach { cfg ->
            rows.add(
                Category(
                    id = cfg.id,
                    title = cfg.title,
                    items = placeholderItems
                )
            )
        }

        return rows
    }

    /**
     * Phase 1.2: Preload images into Coil's memory/disk cache
     * Uses target display sizes to reduce decode overhead.
     */
    private fun preloadImagesWithCoil(urls: List<String>, width: Int, height: Int) {
        if (preloadedRequests.size > if (isLowRamDevice) 1_200 else 4_000) {
            preloadedRequests.clear()
        }
        val uniqueUrls = urls.filter { url ->
            preloadedRequests.add("$url|${width}x${height}")
        }.take(if (isLowRamDevice) 2 else 4)
        if (uniqueUrls.isEmpty()) return

        uniqueUrls.forEach { url ->
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(width.coerceAtLeast(1), height.coerceAtLeast(1))
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .memoryCachePolicy(if (isLowRamDevice) coil.request.CachePolicy.READ_ONLY else coil.request.CachePolicy.ENABLED)
                .build()
            imageLoader.enqueue(request)
        }
    }

    private fun preloadLogoImages(urls: List<String>) {
        preloadImagesWithCoil(urls, logoPreloadWidth, logoPreloadHeight)
    }

    private fun preloadBackdropImages(urls: List<String>) {
        preloadImagesWithCoil(urls, backdropPreloadWidth, backdropPreloadHeight)
    }

    fun refresh() {
        loadHomeData()
    }

    private var pendingForceRefreshCW = false

    fun refreshContinueWatchingOnly(forceRefresh: Boolean = false) {
        // When force-refreshing (returning from player), immediately swap CW row
        // with placeholders so user sees a loading state instead of stale data
        // that visibly reorders a second later.
        if (forceRefresh) {
            val latestCats = _uiState.value.categories.toMutableList()
            val cwIdx = latestCats.indexOfFirst { it.id == "continue_watching" }
            if (cwIdx >= 0) {
                val existing = latestCats[cwIdx]
                val hasReal = existing.items.isNotEmpty() && existing.items.none { it.isPlaceholder }
                if (hasReal) {
                    val count = existing.items.size.coerceAtMost(HOME_PLACEHOLDER_ITEM_COUNT)
                    val placeholders = (1..count).map { i ->
                        MediaItem(id = -i, title = "", mediaType = MediaType.MOVIE, isPlaceholder = true)
                    }
                    latestCats[cwIdx] = Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = placeholders
                    )
                    _uiState.value = _uiState.value.copy(categories = latestCats)
                }
            }
        }

        // Don't cancel an in-progress fetch — cancelling mid-update can leave categories
        // in an inconsistent state causing crashes. Instead, queue a follow-up refresh.
        if (refreshContinueWatchingJob?.isActive == true) {
            if (forceRefresh) {
                pendingForceRefreshCW = true  // Will re-fetch after current job completes
            }
            return
        }
        pendingForceRefreshCW = false
        refreshContinueWatchingJob = viewModelScope.launch {
            try {
                val now = SystemClock.elapsedRealtime()
                val startCategories = _uiState.value.categories
                val continueWatchingIndexAtStart = startCategories.indexOfFirst { it.id == "continue_watching" }
                val existingContinueWatching = startCategories.getOrNull(continueWatchingIndexAtStart)
                val hasPlaceholders = existingContinueWatching?.items?.any { it.isPlaceholder } == true

                // Allow refresh if we have placeholders (need to replace them),
                // force-refresh on resume (ensures latest CW data after playing), otherwise throttle
                if (!forceRefresh && !hasPlaceholders && now - lastContinueWatchingUpdateMs < CONTINUE_WATCHING_REFRESH_MS) {
                    return@launch
                }

                // Phase 1: Load raw from Supabase (fast, no TMDB enrichment ~500ms)
                val rawCwItems = try {
                    loadContinueWatchingFromHistoryRaw()
                } catch (_: Exception) {
                    emptyList()
                }
                // Merge with cached enriched data for descriptions (in-memory, instant)
                val resolvedContinueWatching = if (rawCwItems.isNotEmpty()) {
                    val cachedEnriched = traktRepository.getCachedContinueWatching()
                    val enrichedById = cachedEnriched.associateBy { it.id }
                    rawCwItems.map { raw ->
                        val cached = enrichedById[raw.id]
                        if (cached != null && cached.overview.isNotEmpty()) {
                            raw.copy(
                                overview = cached.overview,
                                year = cached.year.ifEmpty { raw.year },
                                imdbRating = cached.imdbRating.ifEmpty { raw.imdbRating },
                                duration = cached.duration.ifEmpty { raw.duration },
                                episodeTitle = raw.episodeTitle ?: cached.episodeTitle
                            )
                        } else raw
                    }
                } else emptyList()

                if (resolvedContinueWatching.isNotEmpty()) {
                    val mergedContinueWatching = mergeContinueWatchingResumeData(resolvedContinueWatching)
                    val continueWatchingCategory = Category(
                        id = "continue_watching",
                        title = "Continue Watching",
                        items = mergedContinueWatching.map { it.toMediaItem() }.deduplicateItems()
                    )
                    continueWatchingCategory.items.forEach { mediaRepository.cacheItem(it) }
                    lastContinueWatchingItems = continueWatchingCategory.items
                    lastContinueWatchingUpdateMs = now
                    // Persist to disk so next app launch shows latest CW instantly
                    try { traktRepository.updateContinueWatchingCache(mergedContinueWatching) } catch (_: Exception) {}
                    val latestCategories = _uiState.value.categories.toMutableList()
                    val continueWatchingIndex = latestCategories.indexOfFirst { it.id == "continue_watching" }
                    if (continueWatchingIndex >= 0) {
                        // Skip update if CW items haven't actually changed (avoids recomposition)
                        val existingItems = latestCategories[continueWatchingIndex].items
                        if (existingItems == continueWatchingCategory.items) return@launch
                        latestCategories[continueWatchingIndex] = continueWatchingCategory
                    } else {
                        latestCategories.add(0, continueWatchingCategory)
                    }
                    // Update hero to first CW item when CW is at position 0
                    val cwIsFirst = latestCategories.firstOrNull()?.id == "continue_watching"
                    val newHero = if (cwIsFirst) continueWatchingCategory.items.firstOrNull() else null
                    if (newHero != null) {
                        val heroKey = "${newHero.mediaType}_${newHero.id}"
                        val heroLogo = getCachedLogo(heroKey)
                        _uiState.value = _uiState.value.copy(
                            categories = latestCategories,
                            heroItem = newHero,
                            heroLogoUrl = heroLogo
                        )
                        // Fetch logo if not cached
                        if (heroLogo == null) {
                            viewModelScope.launch {
                                try {
                                    val logo = withContext(networkDispatcher) {
                                        mediaRepository.getLogoUrl(newHero.mediaType, newHero.id)
                                    }
                                    if (logo != null && _uiState.value.heroItem?.id == newHero.id) {
                                        putCachedLogo(heroKey, logo)
                                        _uiState.value = _uiState.value.copy(heroLogoUrl = logo)
                                        scheduleLogoCachePublish()
                                        preloadLogoImages(listOf(logo))
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(categories = latestCategories)
                    }

                    // Prefetch logos for CW items so card overlays render immediately
                    viewModelScope.launch {
                        val cwItems = continueWatchingCategory.items.take(initialLogoPrefetchItemsPerRow)
                        val logoJobs = cwItems.map { item ->
                            async(networkDispatcher) {
                                val key = "${item.mediaType}_${item.id}"
                                if (getCachedLogo(key) != null) return@async null // Already cached
                                try {
                                    val logoUrl = mediaRepository.getLogoUrl(item.mediaType, item.id)
                                    if (logoUrl != null) key to logoUrl else null
                                } catch (_: Exception) { null }
                            }
                        }
                        val newLogos = logoJobs.awaitAll().filterNotNull().toMap()
                        if (newLogos.isNotEmpty()) {
                            if (putCachedLogos(newLogos)) {
                                publishLogoCacheSnapshotIfChanged()
                            }
                            preloadLogoImages(newLogos.values.toList())
                        }
                    }
                    refreshWatchedBadges()

                    // Phase 2: Enrich with TMDB in background (adds overview/year/rating)
                    viewModelScope.launch {
                        val enrichedItems = try {
                            traktRepository.enrichContinueWatchingItems(rawCwItems)
                        } catch (_: Exception) { rawCwItems }
                        val mergedEnriched = mergeContinueWatchingResumeData(enrichedItems)
                        val enrichedCategory = Category(
                            id = "continue_watching",
                            title = "Continue Watching",
                            items = mergedEnriched.map { it.toMediaItem() }.deduplicateItems()
                        )
                        enrichedCategory.items.forEach { mediaRepository.cacheItem(it) }
                        lastContinueWatchingItems = enrichedCategory.items
                        lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                        try { traktRepository.updateContinueWatchingCache(mergedEnriched) } catch (_: Exception) {}
                        val enrichedCategories = _uiState.value.categories.toMutableList()
                        val enrichedIdx = enrichedCategories.indexOfFirst { it.id == "continue_watching" }
                        if (enrichedIdx >= 0 && enrichedCategories[enrichedIdx].items != enrichedCategory.items) {
                            enrichedCategories[enrichedIdx] = enrichedCategory
                            _uiState.value = _uiState.value.copy(categories = enrichedCategories)
                        }
                    }
                } else {
                    // No new data from any source
                    val latestCategories = _uiState.value.categories.toMutableList()
                    val continueWatchingIndex = latestCategories.indexOfFirst { it.id == "continue_watching" }
                    val latestHasPlaceholders = latestCategories
                        .getOrNull(continueWatchingIndex)
                        ?.items
                        ?.any { it.isPlaceholder } == true
                    if (hasPlaceholders) {
                        // We had placeholders but no data loaded - remove the placeholder category
                        if (continueWatchingIndex >= 0) {
                            latestCategories.removeAt(continueWatchingIndex)
                            _uiState.value = _uiState.value.copy(categories = latestCategories)
                        }
                    } else if (!latestHasPlaceholders && continueWatchingIndex >= 0) {
                        // Continue Watching exists with real data - preserve it exactly as is
                        return@launch
                    } else if (lastContinueWatchingItems.isNotEmpty()) {
                        // UI doesn't have Continue Watching but we have last known good items - restore them
                        val continueWatchingCategory = Category(
                            id = "continue_watching",
                            title = "Continue Watching",
                            items = lastContinueWatchingItems
                        )
                        latestCategories.add(0, continueWatchingCategory)
                        _uiState.value = _uiState.value.copy(categories = latestCategories)
                    }
                    // Else: No data anywhere - nothing to show, UI already doesn't have it
                }
            } catch (e: Exception) {
                // Silently fail - don't clear existing data on error
            } finally {
                // If a forceRefresh was requested while this job was running,
                // re-trigger now that we've finished cleanly.
                if (pendingForceRefreshCW) {
                    pendingForceRefreshCW = false
                    refreshContinueWatchingOnly(forceRefresh = true)
                }
            }
        }
    }

    /**
     * Load CW items from watch history WITHOUT enrichment.
     * Fast: only a single Supabase network call + local mapping.
     * Items have title/poster/backdrop from watch history but lack overview/year/rating.
     */
    private suspend fun loadContinueWatchingFromHistoryRaw(): List<ContinueWatchingItem> {
        val allEntries = watchHistoryRepository.getWatchHistory()
        if (allEntries.isEmpty()) return emptyList()

        val threshold = Constants.WATCHED_THRESHOLD / 100f
        val byShow = LinkedHashMap<String, com.arflix.tv.data.repository.WatchHistoryEntry>()
        for (entry in allEntries) {
            val key = "${entry.media_type}:${entry.show_tmdb_id}"
            if (!byShow.containsKey(key)) {
                byShow[key] = entry
            }
        }

        return byShow.values.mapNotNull { entry ->
            val progress = entry.progress.coerceIn(0f, 1f)
            val mediaType = if (entry.media_type == "tv") MediaType.TV else MediaType.MOVIE

            if (progress <= 0f && entry.position_seconds <= 0L) return@mapNotNull null

            if (progress >= threshold && progress > 0f) {
                if (mediaType == MediaType.TV && entry.season != null && entry.episode != null) {
                    ContinueWatchingItem(
                        id = entry.show_tmdb_id,
                        title = entry.title ?: return@mapNotNull null,
                        mediaType = mediaType,
                        progress = 0,
                        resumePositionSeconds = 0L,
                        durationSeconds = 0L,
                        season = entry.season,
                        episode = entry.episode + 1,
                        episodeTitle = null,
                        backdropPath = entry.backdrop_path,
                        posterPath = entry.poster_path,
                        isUpNext = true
                    )
                } else {
                    return@mapNotNull null
                }
            } else {
                ContinueWatchingItem(
                    id = entry.show_tmdb_id,
                    title = entry.title ?: return@mapNotNull null,
                    mediaType = mediaType,
                    progress = (progress * 100f).toInt().coerceIn(0, 100),
                    resumePositionSeconds = entry.position_seconds.coerceAtLeast(0L),
                    durationSeconds = entry.duration_seconds.coerceAtLeast(0L),
                    season = entry.season,
                    episode = entry.episode,
                    episodeTitle = entry.episode_title,
                    backdropPath = entry.backdrop_path,
                    posterPath = entry.poster_path
                )
            }
        }
    }

    /**
     * Load CW items from watch history WITH TMDB enrichment (adds overview, year, rating).
     * Slower: Supabase call + parallel TMDB API calls per item.
     */
    private suspend fun loadContinueWatchingFromHistory(): List<ContinueWatchingItem> {
        return try {
            val raw = loadContinueWatchingFromHistoryRaw()
            if (raw.isEmpty()) return emptyList()
            traktRepository.enrichContinueWatchingItems(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun mergeContinueWatchingResumeData(
        items: List<ContinueWatchingItem>
    ): List<ContinueWatchingItem> {
        if (items.isEmpty()) return emptyList()
        return try {
            // Use full history (not just in-progress) to match the most recent episode per show
            val historyEntries = watchHistoryRepository.getWatchHistory()
            if (historyEntries.isEmpty()) return items

            val sortedHistory = historyEntries.sortedByDescending { it.updated_at ?: it.paused_at.orEmpty() }

            // Build lookup maps keeping the FIRST (most recent) entry per key.
            // associateBy keeps the LAST entry, so we use manual iteration instead.
            val byExactKey = LinkedHashMap<String, com.arflix.tv.data.repository.WatchHistoryEntry>()
            val byShowKey = LinkedHashMap<String, com.arflix.tv.data.repository.WatchHistoryEntry>()
            for (entry in sortedHistory) {
                val exactKey = "${entry.media_type}:${entry.show_tmdb_id}:${entry.season ?: -1}:${entry.episode ?: -1}"
                val showKey = "${entry.media_type}:${entry.show_tmdb_id}"
                if (!byExactKey.containsKey(exactKey)) byExactKey[exactKey] = entry
                if (!byShowKey.containsKey(showKey)) byShowKey[showKey] = entry
            }

            items.map { item ->
                val mediaTypeKey = if (item.mediaType == MediaType.TV) "tv" else "movie"
                val exactKey = "$mediaTypeKey:${item.id}:${item.season ?: -1}:${item.episode ?: -1}"
                val showKey = "$mediaTypeKey:${item.id}"
                val match = byExactKey[exactKey] ?: byShowKey[showKey]
                if (match == null) {
                    item
                } else if (item.isUpNext) {
                    // "Up next" items: only update episodeTitle from the match,
                    // DON'T overwrite season/episode (already set to next ep by raw loader)
                    item.copy(
                        episodeTitle = match.episode_title ?: item.episodeTitle
                    )
                } else {
                    // In-progress items: update progress/position data
                    item.copy(
                        progress = (match.progress * 100f).toInt().coerceIn(0, 100),
                        resumePositionSeconds = match.position_seconds.coerceAtLeast(0L),
                        durationSeconds = match.duration_seconds.coerceAtLeast(0L),
                        episodeTitle = match.episode_title ?: item.episodeTitle
                    )
                }
            }
        } catch (_: Exception) {
            items
        }
    }

    private fun refreshWatchedBadges() {
        viewModelScope.launch(networkDispatcher) {
            try {
            // Initialize watched cache - works for both Trakt and non-Trakt profiles.
            // For non-Trakt, it loads from Supabase watched_movies/watched_episodes.
            traktRepository.initializeWatchedCache()
            val categories = _uiState.value.categories
            if (categories.isEmpty()) return@launch

            val watchedMovies = traktRepository.getWatchedMoviesFromCache()

            // Performance: Build show watch progress map only for unique TV shows
            val showProgress = mutableMapOf<Int, WatchProgress>()
            val seenShows = mutableSetOf<Int>()
            for (category in categories) {
                if (category.id == "continue_watching") continue
                for (item in category.items) {
                    if (item.mediaType == MediaType.TV && seenShows.add(item.id)) {
                        val watchedCount = traktRepository.getWatchedEpisodeCount(item.id)
                        val totalEpisodes = item.totalEpisodes ?: 0
                        showProgress[item.id] = when {
                            watchedCount == 0 -> WatchProgress.NONE
                            totalEpisodes > 0 && watchedCount >= totalEpisodes -> WatchProgress.COMPLETED
                            else -> WatchProgress.IN_PROGRESS
                        }
                    }
                }
            }

            // Performance: Only create new lists/objects when watched status actually changes
            var anyChange = false
            val updatedCategories = categories.map { category ->
                if (category.id == "continue_watching") {
                    category
                } else {
                    var categoryChanged = false
                    val updatedItems = category.items.map { item ->
                        val newProgress = when (item.mediaType) {
                            MediaType.MOVIE -> if (watchedMovies.contains(item.id)) WatchProgress.COMPLETED else WatchProgress.NONE
                            MediaType.TV -> showProgress[item.id] ?: WatchProgress.NONE
                        }
                        val newWatched = newProgress == WatchProgress.COMPLETED
                        if (item.isWatched != newWatched || item.watchProgress != newProgress) {
                            categoryChanged = true
                            item.copy(isWatched = newWatched, watchProgress = newProgress)
                        } else {
                            item
                        }
                    }
                    if (categoryChanged) {
                        anyChange = true
                        category.copy(items = updatedItems)
                    } else {
                        category
                    }
                }
            }

            // Performance: Only update state if something actually changed
            if (!anyChange) return@launch

            val heroItem = _uiState.value.heroItem
            val updatedHero = heroItem?.let { hero ->
                updatedCategories.asSequence()
                    .flatMap { it.items.asSequence() }
                    .firstOrNull { it.id == hero.id && it.mediaType == hero.mediaType }
                    ?: hero
            }

            _uiState.value = _uiState.value.copy(
                categories = updatedCategories,
                heroItem = updatedHero
            )
            } catch (e: Exception) {
                System.err.println("HomeVM: refreshWatchedBadges failed: ${e.message}")
            }
        }
    }

    /**
     * Phase 1.4 & 6.1 & 6.2-6.3: Update hero with adaptive debouncing
     * Uses fast-scroll detection for smoother experience during rapid navigation
     */
    fun updateHeroItem(item: MediaItem) {
        val cacheKey = "${item.mediaType}_${item.id}"
        // Skip if already loading this exact hero item
        if (pendingHeroLoadKey == cacheKey && heroUpdateJob?.isActive == true) return
        pendingHeroLoadKey = cacheKey
        val cachedLogo = getCachedLogo(cacheKey)

        // Phase 6.2-6.3: Detect fast scrolling
        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = currentTime - lastFocusChangeTime
        lastFocusChangeTime = currentTime

        val isFastScrolling = timeSinceLastChange < FAST_SCROLL_THRESHOLD_MS
        if (isFastScrolling) {
            consecutiveFastChanges++
        } else {
            consecutiveFastChanges = 0
        }

        // Adaptive debounce: higher during fast scroll sequences
        val debounceMs = when {
            consecutiveFastChanges > 3 -> FAST_SCROLL_DEBOUNCE_MS  // Very fast scroll
            consecutiveFastChanges > 1 -> HERO_DEBOUNCE_MS + 50    // Moderate fast scroll
            cachedLogo != null -> 0L  // Cached = instant
            else -> HERO_DEBOUNCE_MS  // Normal debounce
        }

        // Phase 1.4: If logo is cached and not fast-scrolling, update immediately
        val fastScrolling = consecutiveFastChanges > 1
        if (cachedLogo != null && !fastScrolling) {
            heroUpdateJob?.cancel()
            performHeroUpdate(item, cachedLogo)
            scheduleHeroDetailsFetch(item, fastScrolling)
            return
        }

        // Phase 6.1 + 6.2-6.3: Adaptive debounce
        heroUpdateJob?.cancel()
        heroDetailsJob?.cancel()
        heroUpdateJob = viewModelScope.launch {
            if (debounceMs > 0) {
                delay(debounceMs)
            }

            // Check if still the current focus after debounce
            val currentCachedLogo = getCachedLogo(cacheKey)
            performHeroUpdate(item, currentCachedLogo)
            scheduleHeroDetailsFetch(item, fastScrolling)

            // Fetch logo async if not cached
            if (currentCachedLogo == null) {
                try {
                    val logoUrl = withContext(networkDispatcher) {
                        mediaRepository.getLogoUrl(item.mediaType, item.id)
                    }
                    if (logoUrl != null && _uiState.value.heroItem?.id == item.id) {
                        putCachedLogo(cacheKey, logoUrl)
                        _uiState.value = _uiState.value.copy(
                            heroLogoUrl = logoUrl,
                            isHeroTransitioning = false
                        )
                        scheduleLogoCachePublish()
                        // Preload the logo image
                        preloadLogoImages(listOf(logoUrl))
                    }
                } catch (e: Exception) {
                    // Logo fetch failed
                }
            }
        }
    }

    private fun performHeroUpdate(item: MediaItem, logoUrl: String?) {
        val currentState = _uiState.value
        val currentHero = currentState.heroItem
        if (currentHero?.id == item.id &&
            currentHero.mediaType == item.mediaType &&
            currentState.heroLogoUrl == logoUrl &&
            !currentState.isHeroTransitioning
        ) {
            return
        }

        // Save previous hero for crossfade animation
        _uiState.value = currentState.copy(
            previousHeroItem = currentState.heroItem,
            previousHeroLogoUrl = currentState.heroLogoUrl,
            heroItem = item,
            heroLogoUrl = logoUrl,
            isHeroTransitioning = true
        )
    }

    private fun scheduleHeroDetailsFetch(item: MediaItem, fastScrolling: Boolean) {
        heroDetailsJob?.cancel()
        heroDetailsJob = viewModelScope.launch(networkDispatcher) {
            val detailsKey = "${item.mediaType}_${item.id}"
            val cachedDetails = heroDetailsCache[detailsKey]
            if (cachedDetails != null) {
                val currentHero = _uiState.value.heroItem
                if (currentHero?.id == item.id && currentHero.mediaType == item.mediaType) {
                    _uiState.value = _uiState.value.copy(
                        heroItem = currentHero.copy(
                            duration = cachedDetails.duration.ifEmpty { currentHero.duration },
                            releaseDate = cachedDetails.releaseDate ?: currentHero.releaseDate,
                            imdbRating = cachedDetails.imdbRating.ifEmpty { currentHero.imdbRating },
                            tmdbRating = cachedDetails.tmdbRating.ifEmpty { currentHero.tmdbRating },
                            budget = cachedDetails.budget ?: currentHero.budget
                        ),
                        isHeroTransitioning = false
                    )
                }
                return@launch
            }

            delay(if (fastScrolling) 1200L else 600L)
            val currentHero = _uiState.value.heroItem
            if (currentHero?.id != item.id) return@launch

            try {
                val details = if (item.mediaType == MediaType.MOVIE) {
                    mediaRepository.getMovieDetails(item.id)
                } else {
                    mediaRepository.getTvDetails(item.id)
                }

                val updatedItem = currentHero.copy(
                    duration = details.duration.ifEmpty { currentHero.duration },
                    releaseDate = details.releaseDate ?: currentHero.releaseDate,
                    imdbRating = details.imdbRating.ifEmpty { currentHero.imdbRating },
                    tmdbRating = details.tmdbRating.ifEmpty { currentHero.tmdbRating },
                    budget = details.budget ?: currentHero.budget
                )
                heroDetailsCache[detailsKey] = HeroDetailsSnapshot(
                    duration = details.duration,
                    releaseDate = details.releaseDate,
                    imdbRating = details.imdbRating,
                    tmdbRating = details.tmdbRating,
                    budget = details.budget
                )
                _uiState.value = _uiState.value.copy(
                    heroItem = updatedItem,
                    isHeroTransitioning = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isHeroTransitioning = false)
            }
        }
    }

    /**
     * Phase 1.3: Ahead-of-focus preloading
     * Call this when focus changes to preload nearby items
     */
    fun onFocusChanged(rowIndex: Int, itemIndex: Int, shouldPrefetch: Boolean = true) {
        currentRowIndex = rowIndex
        currentItemIndex = itemIndex
        lastFocusChangeTime = System.currentTimeMillis()
        if (!shouldPrefetch) {
            prefetchJob?.cancel()
            return
        }

        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(networkDispatcher) {
            delay(FOCUS_PREFETCH_COALESCE_MS)

            val categories = _uiState.value.categories
            if (rowIndex < 0 || rowIndex >= categories.size) return@launch

            val category = categories[rowIndex]

            if (category.items.isEmpty()) return@launch

            // Ensure focused card + next 4 cards get logo priority.
            val startIndex = itemIndex.coerceIn(0, category.items.lastIndex)
            val endIndex = minOf(itemIndex + 4, category.items.lastIndex)
            if (startIndex > endIndex) return@launch

            val focusWindowItems = (startIndex..endIndex)
                .mapNotNull { category.items.getOrNull(it) }

            val itemsToLoad = focusWindowItems.filter { item ->
                val key = "${item.mediaType}_${item.id}"
                !hasCachedLogo(key) && logoFetchInFlight.add(key)
            }

            if (itemsToLoad.isEmpty()) return@launch

            // Fetch logos for focused window
            val logoJobs = itemsToLoad.map { item ->
                async(networkDispatcher) {
                    val key = "${item.mediaType}_${item.id}"
                    try {
                        val logoUrl = mediaRepository.getLogoUrl(item.mediaType, item.id)
                        if (logoUrl != null) key to logoUrl else null
                    } catch (e: Exception) {
                        null
                    } finally {
                        logoFetchInFlight.remove(key)
                    }
                }
            }
            val newLogos = logoJobs.awaitAll().filterNotNull().toMap()

            if (newLogos.isNotEmpty()) {
                if (putCachedLogos(newLogos)) {
                    scheduleLogoCachePublish(highPriority = true)
                }
                // Preload actual images
                preloadLogoImages(newLogos.values.toList())
            }

            // Also preload backdrops for focused window
            val backdropUrls = focusWindowItems
                .take(incrementalBackdropPrefetchItems + 1)
                .mapNotNull { it.backdrop ?: it.image }
            preloadBackdropImages(backdropUrls)
        }
    }

    /**
     * Phase 1.1: Preload logos for category + next 2 categories
     */
    private var lastPreloadLogoTimestamp = 0L

    fun preloadLogosForCategory(categoryIndex: Int, prioritizeVisible: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!prioritizeVisible && now - lastPreloadLogoTimestamp < 350L) return
        lastPreloadLogoTimestamp = now
        if (prioritizeVisible) {
            preloadCategoryPriorityJob?.cancel()
        } else {
            preloadCategoryJob?.cancel()
        }
        val targetJob = viewModelScope.launch(networkDispatcher) {
            delay(
                if (prioritizeVisible) {
                    if (isLowRamDevice) 120L else 70L
                } else {
                    if (isLowRamDevice) 520L else 280L
                }
            )
            val categories = _uiState.value.categories
            if (categoryIndex < 0 || categoryIndex >= categories.size) return@launch
            val category = categories[categoryIndex]
            val maxLogoItems = if (prioritizeVisible) prioritizedLogoPrefetchItems else incrementalLogoPrefetchItems

            val itemsToLoad = category.items.take(maxLogoItems).filter { item ->
                val key = "${item.mediaType}_${item.id}"
                !hasCachedLogo(key) && logoFetchInFlight.add(key)
            }

            if (itemsToLoad.isNotEmpty()) {
                val logoJobs = itemsToLoad.map { item ->
                    async(networkDispatcher) {
                        val key = "${item.mediaType}_${item.id}"
                        try {
                            val logoUrl = mediaRepository.getLogoUrl(item.mediaType, item.id)
                            if (logoUrl != null) key to logoUrl else null
                        } catch (e: Exception) {
                            null
                        } finally {
                            logoFetchInFlight.remove(key)
                        }
                    }
                }
                val newLogos = logoJobs.awaitAll().filterNotNull().toMap()
                if (newLogos.isNotEmpty()) {
                    if (putCachedLogos(newLogos)) {
                        scheduleLogoCachePublish(highPriority = prioritizeVisible)
                    }
                    // Preload actual images
                    preloadLogoImages(newLogos.values.toList())
                }
            }

            // Also preload backdrops
            val backdropItems = if (prioritizeVisible) {
                incrementalBackdropPrefetchItems + 1
            } else {
                incrementalBackdropPrefetchItems
            }
            val backdropUrls = category.items.take(backdropItems).mapNotNull { it.backdrop ?: it.image }
            preloadBackdropImages(backdropUrls)
        }
        if (prioritizeVisible) {
            preloadCategoryPriorityJob = targetJob
        } else {
            preloadCategoryJob = targetJob
        }
    }

    /**
     * Clear hero transition state after animation completes
     */
    fun onHeroTransitionComplete() {
        _uiState.value = _uiState.value.copy(
            previousHeroItem = null,
            previousHeroLogoUrl = null,
            isHeroTransitioning = false
        )
    }

    fun toggleWatchlist(item: MediaItem) {
        viewModelScope.launch {
            try {
                val isInWatchlist = watchlistRepository.isInWatchlist(item.mediaType, item.id)
                if (isInWatchlist) {
                    watchlistRepository.removeFromWatchlist(item.mediaType, item.id)
                } else {
                    watchlistRepository.addToWatchlist(item.mediaType, item.id)
                }
                _uiState.value = _uiState.value.copy(
                    toastMessage = if (isInWatchlist) "Removed from watchlist" else "Added to watchlist",
                    toastType = ToastType.SUCCESS
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to update watchlist",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun toggleWatched(item: MediaItem) {
        viewModelScope.launch {
            try {
                if (item.mediaType == MediaType.MOVIE) {
                    if (item.isWatched) {
                        traktRepository.markMovieUnwatched(item.id)
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Marked as unwatched",
                            toastType = ToastType.SUCCESS
                        )
                    } else {
                        traktRepository.markMovieWatched(item.id)
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Marked as watched",
                            toastType = ToastType.SUCCESS
                        )
                    }
                } else {
                    val nextEp = item.nextEpisode
                    if (nextEp != null) {
                        traktRepository.markEpisodeWatched(item.id, nextEp.seasonNumber, nextEp.episodeNumber)
                        watchHistoryRepository.removeFromHistory(item.id, nextEp.seasonNumber, nextEp.episodeNumber)

                        val updatedCategories = _uiState.value.categories.map { category ->
                            if (category.id == "continue_watching") {
                                category.copy(items = category.items.filter { it.id != item.id })
                            } else {
                                category
                            }
                        }.filter { category ->
                            category.id != "continue_watching" || category.items.isNotEmpty()
                        }

                        _uiState.value = _uiState.value.copy(
                            categories = updatedCategories,
                            toastMessage = "S${nextEp.seasonNumber}E${nextEp.episodeNumber} marked as watched",
                            toastType = ToastType.SUCCESS
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "No episode info available",
                            toastType = ToastType.ERROR
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to update watched status",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun markWatched(item: MediaItem) {
        viewModelScope.launch {
            try {
                if (item.mediaType == MediaType.MOVIE) {
                    if (!item.isWatched) {
                        traktRepository.markMovieWatched(item.id)
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Marked as watched",
                            toastType = ToastType.SUCCESS
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "Already watched",
                            toastType = ToastType.INFO
                        )
                    }
                } else {
                    val nextEp = item.nextEpisode
                    if (nextEp != null) {
                        traktRepository.markEpisodeWatched(item.id, nextEp.seasonNumber, nextEp.episodeNumber)
                        watchHistoryRepository.removeFromHistory(item.id, nextEp.seasonNumber, nextEp.episodeNumber)

                        val updatedCategories = _uiState.value.categories.map { category ->
                            if (category.id == "continue_watching") {
                                category.copy(items = category.items.filter { it.id != item.id })
                            } else {
                                category
                            }
                        }.filter { category ->
                            category.id != "continue_watching" || category.items.isNotEmpty()
                        }

                        _uiState.value = _uiState.value.copy(
                            categories = updatedCategories,
                            toastMessage = "S${nextEp.seasonNumber}E${nextEp.episodeNumber} marked as watched",
                            toastType = ToastType.SUCCESS
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            toastMessage = "No episode info available",
                            toastType = ToastType.ERROR
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to update watched status",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun dismissToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    suspend fun isInWatchlist(item: MediaItem): Boolean {
        return watchlistRepository.isInWatchlist(item.mediaType, item.id)
    }

    fun removeFromContinueWatching(item: MediaItem) {
        viewModelScope.launch {
            try {
                val season = if (item.mediaType == MediaType.TV) item.nextEpisode?.seasonNumber else null
                val episode = if (item.mediaType == MediaType.TV) item.nextEpisode?.episodeNumber else null

                watchHistoryRepository.removeFromHistory(item.id, season, episode)
                traktRepository.deletePlaybackForContent(item.id, item.mediaType)
                traktRepository.dismissContinueWatching(item)

                val updatedCategories = _uiState.value.categories.map { category ->
                    if (category.id == "continue_watching") {
                        category.copy(items = category.items.filter { it.id != item.id })
                    } else {
                        category
                    }
                }.filter { category ->
                    category.id != "continue_watching" || category.items.isNotEmpty()
                }

                _uiState.value = _uiState.value.copy(
                    categories = updatedCategories,
                    toastMessage = "Removed from Continue Watching",
                    toastType = ToastType.SUCCESS
                )
                updatedCategories.firstOrNull { it.id == "continue_watching" }?.let { category ->
                    lastContinueWatchingItems = category.items
                    lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                } ?: run {
                    lastContinueWatchingItems = emptyList()
                    lastContinueWatchingUpdateMs = SystemClock.elapsedRealtime()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to remove from Continue Watching",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    /**
     * Remove duplicate items within a list, keeping the first occurrence.
     * Prevents LazyRow crash: "Key X was already used".
     */
    private fun List<MediaItem>.deduplicateItems(): List<MediaItem> =
        distinctBy { "${it.mediaType.name}-${it.id}" }
}

