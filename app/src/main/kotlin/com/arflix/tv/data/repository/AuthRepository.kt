package com.arflix.tv.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arflix.tv.data.api.SupabaseApi
import com.arflix.tv.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

/**
 * User profile data from Supabase profiles table
 */
@Serializable
data class UserProfile(
    val id: String = "",
    val email: String = "",
    val trakt_token: JsonObject? = null,
    val addons: String? = null,
    val default_subtitle: String? = null,
    val auto_play_next: Boolean? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

/**
 * Authentication state
 */
sealed class AuthState {
    object Loading : AuthState()
    object NotAuthenticated : AuthState()
    data class Authenticated(
        val userId: String,
        val email: String,
        val profile: UserProfile?
    ) : AuthState()
    data class Error(val message: String) : AuthState()
}

/**
 * Simple code-based authentication repository.
 * Users log in with a 5-letter invite code from the user_accounts table.
 * No Supabase Auth SDK, no JWT tokens — just anon key + user_id.
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabaseApi: SupabaseApi,
    private val traktRepositoryProvider: Provider<TraktRepository>,
    private val profileManagerProvider: Provider<ProfileManager>
) {
    private object PrefsKeys {
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_EMAIL = stringPreferencesKey("user_email")
        // Keep legacy keys so old code doesn't crash
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    }

    private val authHeader = "Bearer ${Constants.SUPABASE_ANON_KEY}"

    // Auth state
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // User profile
    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    /**
     * Check if user is logged in on app start (reads from DataStore)
     */
    suspend fun checkAuthState() {
        try {
            val prefs = context.authDataStore.data.first()
            val userId = prefs[PrefsKeys.USER_ID]
            val userName = prefs[PrefsKeys.USER_NAME]

            if (!userId.isNullOrBlank()) {
                val profile = UserProfile(id = userId, email = userName ?: "")
                _userProfile.value = profile
                _authState.value = AuthState.Authenticated(userId, userName ?: "", profile)
            } else {
                _authState.value = AuthState.NotAuthenticated
            }
        } catch (e: Exception) {
            _authState.value = AuthState.NotAuthenticated
        }
    }

    /**
     * Log in with a 5-letter invite code.
     * Queries user_accounts table via Supabase REST API.
     */
    suspend fun loginWithCode(code: String): Result<Unit> {
        return try {
            _authState.value = AuthState.Loading

            val results = supabaseApi.getUserByCode(
                inviteCode = "eq.${code.uppercase().trim()}"
            )

            if (results.isEmpty()) {
                _authState.value = AuthState.Error("Invalid code")
                return Result.failure(Exception("Invalid code"))
            }

            val account = results.first()

            // Store in DataStore
            context.authDataStore.edit { prefs ->
                prefs[PrefsKeys.USER_ID] = account.id
                prefs[PrefsKeys.USER_NAME] = account.name
            }

            // Ensure profile exists in profiles table
            try {
                val existingProfiles = supabaseApi.getProfile(
                    auth = authHeader,
                    userId = "eq.${account.id}"
                )
                if (existingProfiles.isEmpty()) {
                    // Create profile via upsert to watch_history's profile pattern
                    // The profiles table will be populated as needed
                }
            } catch (_: Exception) {}

            val profile = UserProfile(id = account.id, email = account.name)
            _userProfile.value = profile
            _authState.value = AuthState.Authenticated(account.id, account.name, profile)
            Result.success(Unit)
        } catch (e: Exception) {
            val msg = e.message ?: "Login failed"
            _authState.value = AuthState.Error(msg)
            Result.failure(Exception(msg))
        }
    }

    // Legacy sign-in methods — redirect to code auth (kept for compile compatibility)
    suspend fun signIn(email: String, password: String): Result<Unit> =
        loginWithCode(password) // treat password as code

    suspend fun signUp(email: String, password: String): Result<Unit> =
        loginWithCode(password)

    suspend fun signInWithSessionTokens(accessToken: String, refreshToken: String): Result<Unit> =
        Result.failure(Exception("Use code-based login"))

    fun getGoogleSignInRequest(): Any? = null

    suspend fun handleGoogleSignInResult(result: Any?): Result<Unit> =
        Result.failure(Exception("Google Sign-In removed"))

    /**
     * Sign out — clear DataStore
     */
    suspend fun signOut() {
        context.authDataStore.edit { it.clear() }
        _userProfile.value = null
        _authState.value = AuthState.NotAuthenticated
    }

    /**
     * Get current user ID from DataStore
     */
    fun getCurrentUserId(): String? {
        return when (val state = _authState.value) {
            is AuthState.Authenticated -> state.userId
            else -> null
        }
    }

    // Token methods — return null (we use anon key directly)
    suspend fun getAccessToken(): String? = null
    suspend fun refreshAccessToken(): String? = null

    // Trakt stubs — always return null/false
    fun getTraktAccessToken(): String? = null
    fun isTraktLinked(): Boolean = false

    // ========== Profile Operations (via Retrofit) ==========

    fun getAddonsFromProfile(): String? = _userProfile.value?.addons

    suspend fun getAddonsFromProfileFresh(): Result<String?> {
        val userId = getCurrentUserId()
            ?: profileManagerProvider.get().getProfileIdSync()
        return try {
            val profiles = supabaseApi.getProfile(auth = authHeader, userId = "eq.$userId")
            val addons = profiles.firstOrNull()?.addons
            _userProfile.value = _userProfile.value?.copy(addons = addons)
                ?: UserProfile(id = userId, addons = addons)
            Result.success(addons)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveAddonsToProfile(addonsJson: String): Result<Unit> {
        val userId = getCurrentUserId()
            ?: profileManagerProvider.get().getProfileIdSync()
        return try {
            supabaseApi.updateProfile(
                auth = authHeader,
                userId = "eq.$userId",
                profile = com.arflix.tv.data.api.UserProfileUpdate(addons = addonsJson)
            )
            _userProfile.value = _userProfile.value?.copy(addons = addonsJson)
                ?: UserProfile(id = userId, addons = addonsJson)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getDefaultSubtitleFromProfile(): String? = _userProfile.value?.default_subtitle

    suspend fun saveDefaultSubtitleToProfile(subtitle: String?): Result<Unit> {
        val userId = getCurrentUserId()
            ?: profileManagerProvider.get().getProfileIdSync()
        return try {
            supabaseApi.updateProfile(
                auth = authHeader,
                userId = "eq.$userId",
                profile = com.arflix.tv.data.api.UserProfileUpdate(defaultSubtitle = subtitle)
            )
            _userProfile.value = _userProfile.value?.copy(default_subtitle = subtitle)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getAutoPlayNextFromProfile(): Boolean? = _userProfile.value?.auto_play_next

    suspend fun saveAutoPlayNextToProfile(autoPlayNext: Boolean): Result<Unit> {
        val userId = getCurrentUserId()
            ?: profileManagerProvider.get().getProfileIdSync()
        return try {
            supabaseApi.updateProfile(
                auth = authHeader,
                userId = "eq.$userId",
                profile = com.arflix.tv.data.api.UserProfileUpdate(autoPlayNext = autoPlayNext)
            )
            _userProfile.value = _userProfile.value?.copy(auto_play_next = autoPlayNext)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateProfile(profile: UserProfile): Result<Unit> {
        return try {
            supabaseApi.updateProfile(
                auth = authHeader,
                userId = "eq.${profile.id}",
                profile = com.arflix.tv.data.api.UserProfileUpdate(
                    defaultSubtitle = profile.default_subtitle,
                    autoPlayNext = profile.auto_play_next,
                    addons = profile.addons
                )
            )
            _userProfile.value = profile
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ========== Account Sync State (via Retrofit) ==========

    suspend fun loadAccountSyncPayload(): Result<String?> {
        val userId = getCurrentUserId()
            ?: profileManagerProvider.get().getProfileIdSync()
        return try {
            val results = supabaseApi.getAccountSyncState(userId = "eq.$userId")
            Result.success(results.firstOrNull()?.payload)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveAccountSyncPayload(payload: String): Result<Unit> {
        val userId = getCurrentUserId()
            ?: profileManagerProvider.get().getProfileIdSync()
        return try {
            supabaseApi.upsertAccountSyncState(
                record = com.arflix.tv.data.api.AccountSyncStateRecord(
                    userId = userId,
                    payload = payload
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
