package it.kapfer.bankteller

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import it.kapfer.bankteller.onboarding.OnboardingScreen

/**
 * Root composable for the BankTeller SPA.
 *
 * Architecture (design decisions D8, D3, D4):
 * - Three views: [LoginScreen], [OnboardingScreen], and [DashboardScreen].
 * - State-based navigation via [Screen] enum — no navigation library needed.
 * - Authenticated users land on either onboarding or the dashboard depending on
 *   the Enable Banking registration status (checked via [AppViewModel.checkAuth]).
 * - Session checking happens once at startup via [AppViewModel.checkAuth].
 * - httpOnly cookies are handled automatically by the browser (same-origin deployment).
 *
 * Public routes (design decision D14):
 * - `/privacy`, `/terms`, and `/enable-banking-callback` are rendered directly
 *   by the SPA, bypassing the auth gate entirely.
 * - Early-return when `getCurrentPathname()` matches one of these routes.
 */
@Composable
fun App() {
    // Public routes bypass the auth gate (design D14, tasks 7.10–7.13).
    // Render the composable for the matching path and return — skip
    // checkAuth() and session-cookie logic.
    when (getCurrentPathname()) {
        "/privacy" -> { MaterialTheme { PrivacyScreen() }; return }
        "/terms" -> { MaterialTheme { TermsScreen() }; return }
        "/enable-banking-callback" -> { MaterialTheme { CallbackScreen() }; return }
    }

    // Auth-gated flow (existing)
    val viewModel = remember { AppViewModel() }

    // Check session on initial composition (task 6.4 – session-aware routing).
    LaunchedEffect(Unit) {
        viewModel.checkAuth()
    }

    MaterialTheme {
        when (viewModel.currentScreen) {
            Screen.Login -> LoginScreen(viewModel)
            Screen.Onboarding -> OnboardingScreen(viewModel)
            Screen.Dashboard -> DashboardScreen(viewModel)
        }
    }
}
