package it.kapfer.bankteller.onboarding

import it.kapfer.bankteller.SANDBOX_POPUP_OPEN_MARKER_KEY
import it.kapfer.bankteller.localStorageGet
import it.kapfer.bankteller.localStorageRemove
import it.kapfer.bankteller.setSandboxPopupOpenMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 8.25 amendment — "Canceling from the wizard closes the popup"
 * (spec: onboarding, scenario at spec.md:328): every back path at the
 * auth-progress step (within-flow back and abandon-to-dashboard) first
 * performs the marker-guarded popup close, then runs the flow's navigation
 * action. These tests run in the real browser (karma container), so the
 * marker round-trips through actual DOM localStorage.
 */
class AuthProgressBackActionTest {

    @Test
    fun backActionClosesAnOpenPopup() {
        setSandboxPopupOpenMarker()

        var actionRan = false
        authProgressBackAction { actionRan = true }()

        // The marker was consumed by the close attempt (window.open in the
        // headless test browser is blocked — the non-fatal attempt is the
        // contract), and the navigation action still ran.
        assertNull(localStorageGet(SANDBOX_POPUP_OPEN_MARKER_KEY))
        assertTrue(actionRan)
    }

    @Test
    fun backActionWithoutAnOpenPopupStillRunsTheAction() {
        localStorageRemove(SANDBOX_POPUP_OPEN_MARKER_KEY)

        var actionRan = false
        authProgressBackAction { actionRan = true }()

        // Absent marker → the close attempt is skipped entirely (no
        // blank-window flash), the action runs regardless.
        assertNull(localStorageGet(SANDBOX_POPUP_OPEN_MARKER_KEY))
        assertTrue(actionRan)
    }

    @Test
    fun popupCloseRunsBeforeTheNavigationAction() {
        val calls = mutableListOf<String>()

        authProgressBackAction(
            action = { calls.add("action") },
            closePopup = {
                calls.add("close")
                false
            },
        )()

        assertEquals(listOf("close", "action"), calls)
    }
}
