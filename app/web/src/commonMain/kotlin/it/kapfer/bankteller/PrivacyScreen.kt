package it.kapfer.bankteller

import androidx.compose.runtime.Composable
import it.kapfer.bankteller.ui.components.LegalDocument
import it.kapfer.bankteller.ui.components.LegalSection
import it.kapfer.bankteller.ui.components.ScreenShell
import it.kapfer.bankteller.ui.components.ThemeToggleOverlay
import it.kapfer.bankteller.ui.theme.Dimens

/**
 * Placeholder privacy-policy screen rendered at the `/privacy` public route.
 *
 * Design decision D14: public routes are rendered by the SPA, not as server-rendered HTML.
 * This composable is returned early from [App] when `getCurrentPathname() == "/privacy"`,
 * bypassing the auth gate entirely.
 *
 * Styled per Sovereign Letterhead (tasks 10.4/10.5, D13): letterhead header, brass
 * double-rule, `readingMaxWidth` column, Fraunces `headlineLarge` title, JetBrains
 * Mono "Last updated", sections with 2dp primary left ticks, and a compact Mono
 * legal footer. Actual legal text stays a placeholder.
 */
@Composable
fun PrivacyScreen() {
    ThemeToggleOverlay {
        ScreenShell(
            maxWidth = Dimens.readingMaxWidth,
            scrollable = true,
        ) {
            LegalDocument(
                title = "Privacy Policy",
                lastUpdated = "September 2026",
                footer = "© 2026 BankTeller · Self-hosted personal use · " +
                        "Replace this disclosure with your actual privacy line before deploying.",
            ) {
                LegalSection(
                    title = "1. Scope of this policy",
                    body = "BankTeller is a personal-use project; this is a placeholder privacy policy. " +
                            "Replace this text with your actual privacy policy before deploying to production.",
                )
                LegalSection(
                    title = "2. Data we collect and process",
                    body = "Placeholder section. Describe which data the service collects, how it is " +
                            "processed, and where it is stored once a real policy is written.",
                )
                LegalSection(
                    title = "3. Your rights and choices",
                    body = "Placeholder section. Outline the rights available to you and how to exercise " +
                            "them, including how to request deletion of your data.",
                )
            }
        }
    }
}