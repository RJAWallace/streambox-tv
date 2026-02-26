package com.arflix.tv.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.repository.AuthRepository
import com.arflix.tv.data.repository.AuthState
import com.arflix.tv.data.repository.StreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val authState: AuthState = AuthState.Loading
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val streamRepository: StreamRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                _uiState.update { it.copy(authState = authState) }
            }
        }
    }

    /**
     * Log in with a 5-letter invite code
     */
    fun loginWithCode(code: String) {
        val trimmed = code.trim().uppercase()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(error = "Please enter your access code") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = authRepository.loginWithCode(trimmed)

            if (result.isSuccess) {
                streamRepository.syncAddonsFromCloud()
            }

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    // Legacy methods kept for compile compatibility
    fun signIn(email: String, password: String) = loginWithCode(password)
    fun signUp(email: String, password: String) = loginWithCode(password)
}
