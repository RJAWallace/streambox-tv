package com.arflix.tv.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.model.ProfileColors
import com.arflix.tv.data.repository.AuthRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.ProfileRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.data.repository.IptvRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

data class ProfileUiState(
    val profiles: List<Profile> = emptyList(),
    val activeProfile: Profile? = null,
    val isLoading: Boolean = true,
    val isManageMode: Boolean = false,
    // Add profile dialog state
    val showAddDialog: Boolean = false,
    val newProfileName: String = "",
    val selectedColorIndex: Int = 0,
    val selectedAvatarId: Int = 0, // 0 = legacy letter, 1-24 = cartoon avatar
    val isKidsProfile: Boolean = false,
    // Edit profile dialog state
    val editingProfile: Profile? = null,
    // Code entry
    val isLoggedIn: Boolean = false,
    val showCodeDialog: Boolean = false,
    val codeValidating: Boolean = false,
    val codeError: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val profileManager: ProfileManager,
    private val traktRepository: TraktRepository,
    private val watchlistRepository: WatchlistRepository,
    private val iptvRepository: IptvRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val gson = Gson()

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfiles()
        observeProfiles()
        observeAuthState()
        pullProfilesFromCloud()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authRepository.authState.collect { state ->
                val isLoggedIn = state is com.arflix.tv.data.repository.AuthState.Authenticated
                _uiState.value = _uiState.value.copy(isLoggedIn = isLoggedIn)
                if (isLoggedIn) {
                    // Re-pull profiles from cloud after login
                    pullProfilesFromCloud()
                }
            }
        }
    }

    fun showCodeDialog() {
        _uiState.value = _uiState.value.copy(showCodeDialog = true, codeError = null)
    }

    fun hideCodeDialog() {
        _uiState.value = _uiState.value.copy(showCodeDialog = false, codeError = null)
    }

    fun loginWithCode(code: String) {
        val trimmed = code.trim().uppercase()
        if (trimmed.length != 5 || !trimmed.all { it.isLetter() }) {
            _uiState.value = _uiState.value.copy(codeError = "Enter a 5-letter code")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(codeValidating = true, codeError = null)
            val result = authRepository.loginWithCode(trimmed)
            if (result.isSuccess) {
                // Check if cloud has existing profiles
                val cloudPayload = authRepository.loadAccountSyncPayload().getOrNull()
                val cloudHasProfiles = if (!cloudPayload.isNullOrBlank()) {
                    runCatching {
                        val root = JSONObject(cloudPayload)
                        val arr = root.optJSONArray("profiles")
                        arr != null && arr.length() > 0
                    }.getOrDefault(false)
                } else false

                if (cloudHasProfiles) {
                    // Cloud has profiles → pull them down (cross-device sync)
                    pullProfilesFromCloud()
                } else {
                    // Cloud is empty → push local profiles up so they're synced
                    profileRepository.syncProfilesToCloud()
                }
                _uiState.value = _uiState.value.copy(
                    codeValidating = false,
                    showCodeDialog = false,
                    codeError = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    codeValidating = false,
                    codeError = "Invalid code"
                )
            }
        }
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val profiles = profileRepository.getProfiles()
            val activeProfile = profileRepository.getActiveProfile()
            _uiState.value = _uiState.value.copy(
                profiles = profiles,
                activeProfile = activeProfile,
                isLoading = false
            )
        }
    }

    private fun observeProfiles() {
        viewModelScope.launch {
            profileRepository.profiles.collect { profiles ->
                _uiState.value = _uiState.value.copy(profiles = profiles)
            }
        }
        viewModelScope.launch {
            profileRepository.activeProfile.collect { profile ->
                _uiState.value = _uiState.value.copy(activeProfile = profile)
            }
        }
    }

    /**
     * Pull profiles from the cloud sync payload on screen open.
     * This enables cross-device sync: profiles added/edited on Device A
     * appear on Device B when the "Who's watching?" screen opens.
     * Only restores the profiles list and active profile ID — the full
     * settings/addons/catalogs restore still happens in SettingsViewModel.
     */
    private fun pullProfilesFromCloud() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val payloadResult = authRepository.loadAccountSyncPayload()
                val payload = payloadResult.getOrNull()
                if (payload.isNullOrBlank()) return@runCatching

                val root = JSONObject(payload)
                val cloudProfilesJson = root.optJSONArray("profiles")?.toString()
                if (cloudProfilesJson.isNullOrBlank()) return@runCatching

                val type = object : TypeToken<List<Profile>>() {}.type
                val cloudProfiles: List<Profile> = gson.fromJson(cloudProfilesJson, type) ?: return@runCatching
                if (cloudProfiles.isEmpty()) return@runCatching

                val localProfiles = profileRepository.getProfiles()

                // Only replace local profiles if cloud has different data.
                // Compare by ID sets and names to detect additions, deletions, or renames.
                val cloudIds = cloudProfiles.map { it.id }.toSet()
                val localIds = localProfiles.map { it.id }.toSet()
                val cloudSignature = cloudProfiles.map { "${it.id}:${it.name}:${it.avatarId}:${it.avatarColor}" }.toSet()
                val localSignature = localProfiles.map { "${it.id}:${it.name}:${it.avatarId}:${it.avatarColor}" }.toSet()

                if (cloudSignature != localSignature || cloudIds != localIds) {
                    // Preserve the local active profile ID if it exists in the cloud
                    // profile set. Don't force the other device's active selection.
                    val localActiveId = profileRepository.getActiveProfileId()
                    val effectiveActiveId = if (localActiveId != null && cloudIds.contains(localActiveId)) {
                        localActiveId
                    } else {
                        root.optString("activeProfileId").ifBlank { null }
                    }
                    profileRepository.replaceProfilesFromCloud(cloudProfiles, effectiveActiveId)
                    System.err.println("[CLOUD-SYNC] Profiles updated from cloud: ${cloudProfiles.size} profiles (was ${localProfiles.size} local)")
                } else {
                    System.err.println("[CLOUD-SYNC] Profiles already in sync, no update needed")
                }
            }.onFailure { error ->
                System.err.println("[CLOUD-SYNC] Failed to pull profiles from cloud: ${error.message}")
            }
        }
    }

    /**
     * Preload Continue Watching data when a profile is focused (before selection).
     * This enables instant display when the user actually selects the profile.
     */
    fun preloadForProfile(profile: Profile) {
        viewModelScope.launch {
            traktRepository.preloadContinueWatchingForProfile(profile.id)
        }
    }

    fun selectProfile(profile: Profile) {
        val previousProfileId = profileManager.getProfileIdSync()
        val isSameProfile = previousProfileId == profile.id

        // CRITICAL: Clear ALL profile caches BEFORE switching to ensure complete isolation
        // This prevents Profile 1's Trakt data from showing in Profile 2
        // Skip cache invalidation when re-selecting the same profile (e.g., app restart)
        // to preserve warm in-memory IPTV/VOD caches.
        traktRepository.clearAllProfileCaches()
        watchlistRepository.clearWatchlistCache()
        if (!isSameProfile) {
            iptvRepository.invalidateCache()
        }

        // Update ProfileManager's cache with the new profile ID
        // This ensures all profile-scoped keys use the correct prefix immediately
        profileManager.setCurrentProfileId(profile.id)

        // Activate preloaded cache for instant Continue Watching display
        // This transfers any preloaded data to the active cache before HomeViewModel loads
        traktRepository.activatePreloadedCache(profile.id)

        // Persist the active profile selection
        viewModelScope.launch {
            profileRepository.setActiveProfile(profile.id)
        }
        // Warm IPTV channels from disk cache, then do a background network refresh.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                iptvRepository.warmupFromCacheOnly()
                iptvRepository.loadSnapshot(
                    forcePlaylistReload = false,
                    forceEpgReload = false
                )
                System.err.println("[IPTV-STARTUP] Profile ${profile.name}: channels loaded")
            }
        }
        // Pre-fetch Xtream VOD movie catalog + series catalog in PARALLEL with channel loading.
        // Uses separate xtreamDataMutex so it doesn't block channel/EPG operations.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                iptvRepository.warmXtreamVodCachesIfPossible()
                System.err.println("[IPTV-STARTUP] Profile ${profile.name}: VOD catalogs warmed")
            }
        }
    }

    fun switchProfile() {
        // Clear all caches when leaving a profile to prevent data leakage
        traktRepository.clearAllProfileCaches()
        watchlistRepository.clearWatchlistCache()
        iptvRepository.invalidateCache()

        viewModelScope.launch {
            profileRepository.clearActiveProfile()
        }
    }

    // ========== Add Profile ==========

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(
            showAddDialog = true,
            newProfileName = "",
            selectedColorIndex = (_uiState.value.profiles.size) % ProfileColors.colors.size,
            selectedAvatarId = 0,
            isKidsProfile = false
        )
    }

    fun hideAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun setNewProfileName(name: String) {
        _uiState.value = _uiState.value.copy(newProfileName = name)
    }

    fun setSelectedColorIndex(index: Int) {
        _uiState.value = _uiState.value.copy(selectedColorIndex = index)
    }

    fun setSelectedAvatarId(id: Int) {
        _uiState.value = _uiState.value.copy(selectedAvatarId = id)
    }

    fun createProfile() {
        val state = _uiState.value
        if (state.newProfileName.isBlank()) return

        viewModelScope.launch {
            profileRepository.createProfile(
                name = state.newProfileName.trim(),
                avatarColor = ProfileColors.getByIndex(state.selectedColorIndex),
                avatarId = state.selectedAvatarId,
                isKidsProfile = false
            )
            _uiState.value = _uiState.value.copy(showAddDialog = false)
        }
    }

    // ========== Edit Profile ==========

    fun showEditDialog(profile: Profile) {
        _uiState.value = _uiState.value.copy(
            editingProfile = profile,
            newProfileName = profile.name,
            selectedColorIndex = ProfileColors.colors.indexOf(profile.avatarColor).takeIf { it >= 0 } ?: 0,
            selectedAvatarId = profile.avatarId,
            isKidsProfile = false
        )
    }

    fun hideEditDialog() {
        _uiState.value = _uiState.value.copy(editingProfile = null)
    }

    fun updateProfile() {
        val state = _uiState.value
        val editing = state.editingProfile ?: return
        if (state.newProfileName.isBlank()) return

        viewModelScope.launch {
            profileRepository.updateProfile(
                editing.copy(
                    name = state.newProfileName.trim(),
                    avatarColor = ProfileColors.getByIndex(state.selectedColorIndex),
                    avatarId = state.selectedAvatarId,
                    isKidsProfile = false
                )
            )
            _uiState.value = _uiState.value.copy(editingProfile = null)
        }
    }

    // ========== Manage Mode ==========

    fun toggleManageMode() {
        _uiState.value = _uiState.value.copy(isManageMode = !_uiState.value.isManageMode)
    }

    fun exitManageMode() {
        _uiState.value = _uiState.value.copy(isManageMode = false)
    }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch {
            val activeId = _uiState.value.activeProfile?.id
            profileRepository.deleteProfile(profile.id)
            if (activeId == profile.id) {
                traktRepository.clearAllProfileCaches()
                watchlistRepository.clearWatchlistCache()
                iptvRepository.invalidateCache()
                profileManager.setCurrentProfileId("default")
            }
        }
    }
}
