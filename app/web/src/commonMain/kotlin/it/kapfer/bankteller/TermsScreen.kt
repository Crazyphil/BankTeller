package it.kapfer.bankteller

import androidx.compose.runtime.Composable
import it.kapfer.bankteller.ui.components.LegalDocument
import it.kapfer.bankteller.ui.components.LegalSection
import it.kapfer.bankteller.ui.components.ScreenShell
import it.kapfer.bankteller.ui.components.ThemeToggleOverlay
import it.kapfer.bankteller.ui.theme.Dimens

/**
 * Placeholder terms-of-service screen rendered at the `/terms` public route.
 *
 * Design decision D14: public routes are rendered by the SPA, not as server-rendered HTML.
 * This composable is returned early from [App] when `getCurrentPathname() == "/terms"`,
 * bypassing the auth gate entirely.
 *
 * Styled per Sovereign Letterhead (tasks 10.4/10.6, D13) — same structure as
 * [PrivacyScreen]: letterhead header, brass double-rule, `readingMaxWidth` column,
 * Fraunces `headlineLarge` title, JetBrains Mono "Last updated", sections with 2dp
 * primary left ticks, and a compact Mono legal footer.
 */
@Composable
fun TermsScreen() {
    ThemeToggleOverlay {
        ScreenShell(
            maxWidth = Dimens.readingMaxWidth,
            scrollable = true,
        ) {
            LegalDocument(
                title = "Terms of Service",
                lastUpdated = "September 2026",
                footer = "© 2026 BankTeller · Self-hosted personal use · " +
                        "Replace this disclaimer with your actual terms line before deploying.",
            ) {
                LegalSection(
                    title = "1. Acceptance of terms",
                    body = "BankTeller is a personal-use project; this is a placeholder " +
                            "terms-of-service document. Replace this text with your actual terms " +
                            "before deploying to production.",
                )
                LegalSection(
                    title = "2. Use of the service",
                    body = "Placeholder section. Describe permitted and prohibited uses once a real " +
                            "terms document is written.",
                )
                LegalSection(
                    title = "3. Limitation of liability",
                    body = "Placeholder section. Outline disclaimers and the limits of liability " +
                            "applicable to the service.",
                )
            }
        }
    }
}