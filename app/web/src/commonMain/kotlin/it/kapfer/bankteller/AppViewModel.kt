package it.kapfer.bankteller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** The two possible screens in the SPA. */
enum class Screen { Login, Dashboard }

/**
 * ViewModel holding all SPA state and orchestrating API calls.
 *
 * Design decisions:
 * - Uses `mutableStateOf` delegates so Compose recomposition happens automatically.
 * - All `var` properties expose a public getter and a private setter — only this class mutates them.
 * - `viewModelScope` provides structured concurrency tied to the ViewModel lifecycle.
 */
class AppViewModel : ViewModel() {

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    var currentScreen: Screen by mutableStateOf(Screen.Login)
        private set

    var isAuthenticated: Boolean by mutableStateOf(false)
        private set

    var username: String by mutableStateOf("")
        private set

    var loginError: String? by mutableStateOf(null)
        private set

    var isRateLimited: Boolean by mutableStateOf(false)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    // ---------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------

    private val apiClient = ApiClient()

    // ---------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------

    /**
     * Check whether the current session is still valid (called once at startup).
     * Navigates to [Screen.Dashboard] if authenticated, [Screen.Login] otherwise.
     */
    fun checkAuth() {
        viewModelScope.launch {
            val state = apiClient.checkAuth()
            when (state) {
                is AuthState.Authenticated -> {
                    isAuthenticated = true
                    username = state.username
                    currentScreen = Screen.Dashboard
                }
                is AuthState.Unauthenticated -> {
                    isAuthenticated = false
                    username = ""
                    currentScreen = Screen.Login
                }
            }
        }
    }

    /**
     * Attempt to log in with the given credentials.
     * On success the screen switches to [Screen.Dashboard]; on failure an error is shown.
     */
    fun login(inputUsername: String, inputPassword: String) {
        if (inputUsername.isBlank() || inputPassword.isBlank()) {
            loginError = "Username and password are required"
            return
        }

        isLoading = true
        loginError = null
        isRateLimited = false

        viewModelScope.launch {
            val result = apiClient.login(inputUsername, inputPassword)
            isLoading = false

            when (result) {
                LoginResult.Success -> {
                    isAuthenticated = true
                    username = inputUsername
                    currentScreen = Screen.Dashboard
                    loginError = null
                }
                LoginResult.RateLimited -> {
                    isRateLimited = true
                }
                is LoginResult.Failure -> {
                    loginError = result.message
                }
            }
        }
    }

    /**
     * Log out the current user — calls the server and resets local state.
     */
    fun logout() {
        viewModelScope.launch {
            apiClient.logout()
            isAuthenticated = false
            username = ""
            currentScreen = Screen.Login
            loginError = null
            isRateLimited = false
        }
    }
}
