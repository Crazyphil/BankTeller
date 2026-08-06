package it.kapfer.bankteller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Callback screen rendered at the `/enable-banking-callback` public route.
 *
 * Design decision D14: public routes are rendered by the SPA, not as server-rendered HTML.
 * This composable is returned early from [App] when `getCurrentPathname() == "/enable-banking-callback"`,
 * bypassing the auth gate entirely.
 *
 * The server already captured the oobCode synchronously (D14); this screen only
 * parses the query string to show a success or error message to the user.
 */
@Composable
fun CallbackScreen() {
    val search = remember { getCurrentSearch() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeContentPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (search.contains("state=") && search.contains("oobCode=")) {
            SuccessContent()
        } else {
            ErrorContent()
        }
    }
}

@Composable
private fun SuccessContent() {
    Text(
        text = "Your login was received",
        style = MaterialTheme.typography.headlineSmall,
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = "Return to your original BankTeller tab to continue the onboarding setup.",
        style = MaterialTheme.typography.bodyMedium,
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "BankTeller",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorContent() {
    Text(
        text = "Login link incomplete",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.error,
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = "This login link is invalid or missing required information. " +
                "Please restart the onboarding flow in BankTeller.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
