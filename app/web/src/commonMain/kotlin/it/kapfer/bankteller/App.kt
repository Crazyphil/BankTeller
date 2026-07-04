package it.kapfer.bankteller

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * Root composable for the BankTeller SPA.
 *
 * Architecture (design decisions D8, D3, D4):
 * - Two minimal views only: [LoginScreen] and [DashboardScreen].
 * - State-based navigation via [Screen] enum — no navigation library needed.
 * - Authenticated users land on the dashboard; unauthenticated users see the login.
 * - Session checking happens once at startup via [AppViewModel.checkAuth].
 * - httpOnly cookies are handled automatically by the browser (same-origin deployment).
 */
@Composable
fun App() {
    val viewModel = remember { AppViewModel() }

    // Check session on initial composition (task 6.4 – session-aware routing).
    LaunchedEffect(Unit) {
        viewModel.checkAuth()
    }

    MaterialTheme {
        when (viewModel.currentScreen) {
            Screen.Login -> LoginScreen(viewModel)
            Screen.Dashboard -> DashboardScreen(viewModel)
        }
    }
}
