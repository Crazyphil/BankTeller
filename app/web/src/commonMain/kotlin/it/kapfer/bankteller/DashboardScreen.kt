package it.kapfer.bankteller

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import it.kapfer.bankteller.ui.components.BrandedTopBar
import it.kapfer.bankteller.ui.components.ScreenShell
import it.kapfer.bankteller.ui.components.ThemeToggle
import it.kapfer.bankteller.ui.theme.Dimens

/**
 * Welcome dashboard shown after successful login.
 *
 * Shows a Fraunces welcome headline under the app chrome. Branding and the logout
 * action live in the [BrandedTopBar] (tasks 9.2, 9.2a, design D15) — the theme
 * toggle is the FIRST item in the actions slot, before logout.
 */
@Composable
fun DashboardScreen(viewModel: AppViewModel) {
    ScreenShell(
        topBar = {
            BrandedTopBar(
                actions = {
                    ThemeToggle()
                    TextButton(onClick = { viewModel.logout() }) {
                        Text("Logout")
                    }
                },
            )
        },
    ) {
        Text(
            text = "Welcome, ${viewModel.username}!",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(Dimens.xl))
    }
}