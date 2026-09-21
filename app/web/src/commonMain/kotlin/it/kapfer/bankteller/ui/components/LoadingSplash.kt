package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Cold-start routing splash (design D5): a centered brand lockup shown while
 * [it.kapfer.bankteller.AppViewModel.checkAuth] runs and the destination screen
 * is unknown — the login form is never shown as a placeholder during routing.
 *
 * Layout: 48dp [BrandLogo] → 24dp gap → Fraunces "BankTeller" wordmark
 * (`headlineMedium`) → 32dp gap → 24dp brass [CircularProgressIndicator]
 * (stroke 2.5dp), centered in [ScreenShell] at `Dimens.formMaxWidth`.
 *
 * No interactive elements (nothing to mis-click during routing), no tagline
 * (clutters a 200–500ms flash), and no entrance animation (would be cut off
 * mid-stride on fast connections and read as jitter). When routing completes,
 * the destination screen enters with the standard staggered reveal.
 */
@Composable
fun LoadingSplash() {
    ThemeToggleOverlay {
        ScreenShell(
            maxWidth = Dimens.formMaxWidth,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandLogo(size = Dimens.xxl)

                Spacer(modifier = Modifier.height(Dimens.lg))

                Text(
                    text = "BankTeller",
                    style = MaterialTheme.typography.headlineMedium,
                )

                Spacer(modifier = Modifier.height(Dimens.xl))

                CircularProgressIndicator(
                    color = LocalBankTellerColors.current.brass,
                    modifier = Modifier.size(Dimens.lg),
                    strokeWidth = 2.5.dp,
                )
            }
        }
    }
}