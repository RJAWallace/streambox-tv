package com.arflix.tv.ui.screens.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.IptvSnapshot
import com.arflix.tv.data.repository.AuthRepository
import com.arflix.tv.data.repository.IptvConfig
import com.arflix.tv.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

private const val FAVORITES_GROUP_NAME = "My Favorites"

data class TvUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val loadingMessage: String? = null,
    val loadingPercent: Int = 0,
    val config: IptvConfig = IptvConfig(),
    val snapshot: IptvSnapshot = IptvSnapshot(),
    val channelLookup: Map<String, IptvChannel> = emptyMap(),
    val favoritesOnly: Boolean = false,
    val query: String = ""
) {
    val isConfigured: Boolean get() = config.m3uUrl.isNotBlank()

    fun filteredChannels(group: String): List<IptvChannel> {
        val source = if (group == FAVORITES_GROUP_NAME) {
            val favorites = snapshot.favoriteChannels.toHashSet()
            if (favorites.isEmpty()) emptyList() else snapshot.channels.filter { favorites.contains(it.id) }
        } else {
            snapshot.grouped[group].orEmpty()
        }

        val trimmed = query.trim().lowercase()
        if (trimmed.isBlank()) return source

        return source.mapNotNull { channel ->
            val name = channel.name.lowercase()
            val groupName = channel.group.lowercase()
            val score = when {
                name.startsWith(trimmed) -> 100
                name.contains(trimmed) -> 80
                groupName.startsWith(trimmed) -> 60
                groupName.contains(trimmed) -> 45
                else -> 0
            }
            if (score > 0) channel to score else null
        }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun groups(): List<String> {
        val dynamicGroups = snapshot.grouped.keys.toList()
        val favorites = snapshot.favoriteGroups.filter { dynamicGroups.contains(it) }
        val others = dynamicGroups.filterNot { snapshot.favoriteGroups.contains(it) }
        val ordered = favorites + others
        val hasFavoriteChannelsInSnapshot = snapshot.favoriteChannels
            .toHashSet()
            .let { ids -> snapshot.channels.any { ids.contains(it.id) } }
        return if (hasFavoriteChannelsInSnapshot) {
            listOf(FAVORITES_GROUP_NAME) + ordered
        } else {
            ordered
        }
    }
}

@HiltViewModel
class TvViewModel @Inject constructor(
    private val iptvRepository: IptvRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvUiState(isLoading = true))
    val uiState: StateFlow<TvUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var epgRefreshJob: Job? = null
    private var warmVodJob: Job? = null
    private var periodicEpgJob: Job? = null
    private var pendingForcedReload: Boolean = false
    /** Set to true once the init coroutine finishes its initial data load.
     *  Prevents the config observer's auto-heal from racing with init. */
    @Volatile
    private var initialLoadDone: Boolean = false

    companion object {
        /** EPG auto-refresh interval while the TV page is active */
        private const val EPG_PERIODIC_REFRESH_MS = 30L * 60_000L // 30 minutes
    }

    init {
        observeConfigAndFavorites()
        viewModelScope.launch {
            // The IPTV data (channels + EPG + VOD catalogs) was already loaded during
            // profile selection. Just grab the cached snapshot — instant, no network.
            val cached = iptvRepository.getCachedSnapshotOrNull()
            if (cached != null && cached.channels.isNotEmpty()) {
                val config = iptvRepository.observeConfig().first()
                val lookup = withContext(Dispatchers.Default) {
                    cached.channels.associateBy { it.id }
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = null,
                    snapshot = cached,
                    channelLookup = lookup,
                    loadingMessage = null,
                    loadingPercent = 0
                )
                System.err.println("[IPTV] TV page opened: showing ${cached.channels.size} cached channels, EPG entries=${cached.nowNext.size}")
                // Refresh EPG in background if missing or stale
                val hasPotentialEpg = config.epgUrl.isNotBlank() || config.m3uUrl.contains("get.php", ignoreCase = true) || config.m3uUrl.contains("player_api.php", ignoreCase = true)
                if (hasPotentialEpg && cached.channels.isNotEmpty()) {
                    val hasEpg = hasAnyEpgData(cached)
                    val epgIsStale = iptvRepository.isEpgStaleForBackgroundRefresh()
                    val shouldRefresh = !hasEpg || epgIsStale
                    System.err.println("[EPG] Startup: hasEpg=$hasEpg epgIsStale=$epgIsStale shouldRefresh=$shouldRefresh")
                    if (shouldRefresh) {
                        refreshEpgLightweight()
                    }
                }
                // Full channel reload only if playlist itself is stale (>24h)
                if (iptvRepository.isSnapshotStale(cached)) {
                    refresh(force = false, showLoading = false)
                }
            } else if (cached != null && cached.channels.isEmpty()) {
                // Config exists but no channels — need network load
                val config = iptvRepository.observeConfig().first()
                if (config.m3uUrl.isNotBlank()) {
                    refresh(force = true, showLoading = true)
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            } else {
                // No cached data at all — full load (first time or cache was cleared)
                refresh(force = false, showLoading = true)
            }
            initialLoadDone = true
            // Start periodic EPG refresh (every 30 min) while this screen is active
            startPeriodicEpgRefresh()
        }
    }

    private fun observeConfigAndFavorites() {
        viewModelScope.launch {
            combine(
                iptvRepository.observeConfig(),
                iptvRepository.observeFavoriteGroups(),
                iptvRepository.observeFavoriteChannels()
            ) { config, favoriteGroups, favoriteChannels ->
                Triple(config, favoriteGroups, favoriteChannels)
            }.collect { (config, favoriteGroups, favoriteChannels) ->
                val snapshot = _uiState.value.snapshot.copy(
                    favoriteGroups = favoriteGroups,
                    favoriteChannels = favoriteChannels
                )
                _uiState.value = _uiState.value.copy(config = config, snapshot = snapshot)

                // Auto-heal cases where the app has IPTV config but an empty in-memory snapshot.
                // Only trigger AFTER initial load is done to prevent racing with the init coroutine
                // (which would see an empty snapshot and force a redundant full network reload,
                // wiping EPG data and showing an unnecessary loading spinner on revisit).
                if (initialLoadDone && config.m3uUrl.isNotBlank() && snapshot.channels.isEmpty() && refreshJob?.isActive != true) {
                    refresh(force = true, showLoading = true)
                }
            }
        }
    }

    fun refresh(force: Boolean, showLoading: Boolean = true) {
        if (refreshJob?.isActive == true) return
        if (force) {
            epgRefreshJob?.cancel()
        }

        refreshJob = viewModelScope.launch {
            if (showLoading) {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    loadingMessage = "Starting IPTV load...",
                    loadingPercent = 2
                )
            }
            runCatching {
                iptvRepository.loadSnapshot(
                    forcePlaylistReload = force,
                    // Keep TV startup responsive: load channels first, fetch EPG separately.
                    forceEpgReload = false
                ) { progress ->
                    if (showLoading) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = true,
                            loadingMessage = progress.message,
                            loadingPercent = progress.percent ?: _uiState.value.loadingPercent
                        )
                    }
                }
            }.onSuccess { snapshot ->
                val lookup = withContext(Dispatchers.Default) {
                    snapshot.channels.associateBy { it.id }
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = null,
                    snapshot = snapshot,
                    channelLookup = lookup,
                    loadingMessage = null,
                    loadingPercent = 0
                )
                warmXtreamVodCache()
                maybeRefreshEpgInBackground(snapshot)
                if (!force && _uiState.value.isConfigured && snapshot.channels.isEmpty()) {
                    // Soft refresh returned empty even though IPTV is configured:
                    // schedule one forced reload to bypass stale in-memory paths.
                    pendingForcedReload = true
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = error.message ?: "Failed to load IPTV",
                    loadingMessage = null,
                    loadingPercent = 0
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                refreshJob = null
                if (pendingForcedReload) {
                    pendingForcedReload = false
                    refresh(force = true, showLoading = true)
                }
            }
        }
    }

    private fun warmXtreamVodCache() {
        if (warmVodJob?.isActive == true) return
        warmVodJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { iptvRepository.warmXtreamVodCachesIfPossible() }
        }.also { job ->
            job.invokeOnCompletion { warmVodJob = null }
        }
    }

    /**
     * Periodically refresh EPG every 30 minutes while the TV page is active.
     * This keeps the program guide current without requiring a full page reload.
     * The job is automatically cancelled when the ViewModel is cleared (user navigates away).
     */
    private fun startPeriodicEpgRefresh() {
        if (periodicEpgJob?.isActive == true) return
        periodicEpgJob = viewModelScope.launch {
            while (isActive) {
                delay(EPG_PERIODIC_REFRESH_MS)
                val config = _uiState.value.config
                val hasPotentialEpg = config.epgUrl.isNotBlank() || config.m3uUrl.contains("get.php", ignoreCase = true) || config.m3uUrl.contains("player_api.php", ignoreCase = true)
                if (hasPotentialEpg && _uiState.value.snapshot.channels.isNotEmpty()) {
                    System.err.println("[EPG] Periodic 30-min refresh triggered")
                    refreshEpgLightweight()
                }
            }
        }
    }

    /**
     * Called after a full refresh (loadSnapshot) completes to backfill EPG data
     * if the snapshot doesn't have any. Uses the lightweight path to avoid
     * re-acquiring the loadMutex.
     */
    private fun maybeRefreshEpgInBackground(snapshot: IptvSnapshot) {
        val config = _uiState.value.config
        val hasPotentialEpg = config.epgUrl.isNotBlank() || config.m3uUrl.contains("get.php", ignoreCase = true) || config.m3uUrl.contains("player_api.php", ignoreCase = true)
        if (!hasPotentialEpg) return
        if (snapshot.channels.isEmpty() || hasAnyEpgData(snapshot)) return
        // Snapshot from loadSnapshot has no EPG – use lightweight path to fetch it
        refreshEpgLightweight()
    }

    /**
     * Lightweight EPG refresh that does NOT go through the heavy loadSnapshot/loadMutex path.
     * This allows the TV screen to remain responsive while EPG updates in the background.
     */
    private fun refreshEpgLightweight() {
        if (epgRefreshJob?.isActive == true) return

        epgRefreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loadingMessage = "Refreshing EPG...",
                loadingPercent = 90
            )
            runCatching {
                iptvRepository.refreshEpgOnly(
                    onProgress = { progress ->
                        _uiState.value = _uiState.value.copy(
                            loadingMessage = progress.message,
                            loadingPercent = progress.percent ?: _uiState.value.loadingPercent
                        )
                    },
                    onEpgSnapshot = { intermediate ->
                        // Progressive update: push partial EPG results to UI immediately
                        // (e.g., short EPG now/next arrives before XMLTV full schedule)
                        val lookup = intermediate.channels.associateBy { it.id }
                        _uiState.value = _uiState.value.copy(
                            snapshot = intermediate,
                            channelLookup = lookup
                        )
                        System.err.println("[EPG] Progressive EPG update: ${intermediate.nowNext.size} channels")
                    }
                )
            }.onSuccess { refreshed ->
                if (refreshed != null) {
                    val lookup = withContext(Dispatchers.Default) {
                        refreshed.channels.associateBy { it.id }
                    }
                    _uiState.value = _uiState.value.copy(
                        error = null,
                        snapshot = refreshed,
                        channelLookup = lookup,
                        loadingMessage = null,
                        loadingPercent = 0
                    )
                    System.err.println("[EPG] Lightweight refresh completed (${refreshed.nowNext.size} channels with EPG)")
                } else {
                    _uiState.value = _uiState.value.copy(
                        loadingMessage = null,
                        loadingPercent = 0
                    )
                }
            }.onFailure {
                System.err.println("[EPG] Lightweight refresh failed: ${it.message}")
                _uiState.value = _uiState.value.copy(
                    loadingMessage = null,
                    loadingPercent = 0
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (epgRefreshJob === job) {
                    epgRefreshJob = null
                }
            }
        }
    }

    private fun hasAnyEpgData(snapshot: IptvSnapshot): Boolean {
        if (snapshot.nowNext.isEmpty()) return false
        return snapshot.nowNext.values.any { item ->
            item.now != null || item.next != null || item.later != null || item.upcoming.isNotEmpty()
        }
    }

    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun toggleFavoriteGroup(groupName: String) {
        viewModelScope.launch {
            iptvRepository.toggleFavoriteGroup(groupName)
            syncIptvFavoritesToCloud()
        }
    }

    fun toggleFavoriteChannel(channelId: String) {
        viewModelScope.launch {
            iptvRepository.toggleFavoriteChannel(channelId)
            syncIptvFavoritesToCloud()
        }
    }

    private suspend fun syncIptvFavoritesToCloud() {
        if (authRepository.getCurrentUserId().isNullOrBlank()) return
        runCatching {
            val config = iptvRepository.observeConfig().first()
            val favoriteGroups = iptvRepository.observeFavoriteGroups().first()
            val favoriteChannels = iptvRepository.observeFavoriteChannels().first()
            val existingPayload = authRepository.loadAccountSyncPayload().getOrNull().orEmpty()
            val root = if (existingPayload.isNotBlank()) JSONObject(existingPayload) else JSONObject().apply {
                put("version", 1)
            }
            root.put("updatedAt", System.currentTimeMillis())
            root.put("iptvM3uUrl", config.m3uUrl)
            root.put("iptvEpgUrl", config.epgUrl)
            root.put("iptvFavoriteGroups", JSONArray(favoriteGroups))
            root.put("iptvFavoriteChannels", JSONArray(favoriteChannels))
            authRepository.saveAccountSyncPayload(root.toString())
        }
    }
}
