package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Refresh-control state derivation (localized-sync-errors 4.4; spec:
 * account-overview — refresh affordance). Only in-flight syncs and the ~60s
 * server debounce window disable the control: there is NO rate-limited
 * state, so a rate-limited/erroring bank never blocks refreshing the other
 * banks. The tooltip per state carries no rate-limit horizon.
 */
class RefreshControlStateTest {

    @Test
    fun idle_whenNothingDisablesTheControl() {
        assertEquals(RefreshControlState.Idle, refreshControlState(syncing = false, cooldownRemainingSeconds = null))
        assertEquals(RefreshControlState.Idle, refreshControlState(syncing = false, cooldownRemainingSeconds = 0L))
    }

    @Test
    fun syncing_suppressesTheCooldownPresentation() {
        assertEquals(RefreshControlState.Syncing, refreshControlState(syncing = true, cooldownRemainingSeconds = null))
        assertEquals(RefreshControlState.Syncing, refreshControlState(syncing = true, cooldownRemainingSeconds = 42L))
    }

    @Test
    fun cooldown_disablesOnlyWhileTheDebounceWindowHasTimeLeft() {
        assertEquals(RefreshControlState.Cooldown, refreshControlState(syncing = false, cooldownRemainingSeconds = 1L))
        assertEquals(RefreshControlState.Cooldown, refreshControlState(syncing = false, cooldownRemainingSeconds = 60L))
        assertEquals(RefreshControlState.Idle, refreshControlState(syncing = false, cooldownRemainingSeconds = 0L))
    }

    @Test
    fun onlyIdleIsEnabled() {
        assertTrue(refreshControlEnabled(RefreshControlState.Idle))
        assertFalse(refreshControlEnabled(RefreshControlState.Syncing))
        assertFalse(refreshControlEnabled(RefreshControlState.Cooldown))
    }

    @Test
    fun tooltipsPerState() {
        assertEquals("Refresh balances", refreshControlTooltip(RefreshControlState.Idle))
        assertEquals("Syncing balances…", refreshControlTooltip(RefreshControlState.Syncing))
        assertEquals(
            "Balances recently updated. Refresh available shortly.",
            refreshControlTooltip(RefreshControlState.Cooldown),
        )
    }

    @Test
    fun noTooltipNamesARateLimitOrAHorizon() {
        // The removed rate-limited state's "~2 hours" copy must not survive
        // in any reachable tooltip.
        RefreshControlState.entries.forEach { state ->
            val tooltip = refreshControlTooltip(state)
            assertFalse(tooltip.contains("rate limit", ignoreCase = true), tooltip)
            assertFalse(tooltip.contains("2 hours", ignoreCase = true), tooltip)
            assertFalse(tooltip.contains("~"), tooltip)
        }
    }
}
