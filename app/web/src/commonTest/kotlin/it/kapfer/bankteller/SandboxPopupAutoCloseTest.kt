package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

/**
 * Task 8.25 — popup auto-close on callback return.
 *
 * The popup maintains an open-state marker in localStorage (set on load,
 * cleared on unload); the callback route re-grasps the popup via its
 * deterministic window name and closes it, guarded by the marker. These
 * tests run in the real browser (karma container), so localStorage is the
 * actual DOM storage. `window.open` in the headless test browser is
 * typically blocked (returns null) — the close attempt must be non-fatal
 * either way, which is exactly what the stale-marker case asserts.
 */
class SandboxPopupAutoCloseTest {

    @Test
    fun markerRoundTripsThroughLocalStorage() {
        localStorageRemove(SANDBOX_POPUP_OPEN_MARKER_KEY)
        assertNull(localStorageGet(SANDBOX_POPUP_OPEN_MARKER_KEY))

        setSandboxPopupOpenMarker()
        assertEquals("1", localStorageGet(SANDBOX_POPUP_OPEN_MARKER_KEY))

        localStorageRemove(SANDBOX_POPUP_OPEN_MARKER_KEY)
        assertNull(localStorageGet(SANDBOX_POPUP_OPEN_MARKER_KEY))
    }

    @Test
    fun absentMarkerSkipsTheCloseAttempt() {
        localStorageRemove(SANDBOX_POPUP_OPEN_MARKER_KEY)
        assertFalse(closeSandboxCredentialsPopupIfOpen())
    }

    @Test
    fun presentMarkerIsConsumedByTheAttemptAndNeverThrows() {
        setSandboxPopupOpenMarker()

        // Stale-marker case: the re-grasp finds no real popup (headless
        // browser blocks window.open) — the attempt must not break the
        // flow, and the marker must be consumed so it self-heals instead
        // of flashing a blank window on every future callback.
        closeSandboxCredentialsPopupIfOpen()

        assertNull(localStorageGet(SANDBOX_POPUP_OPEN_MARKER_KEY))
    }
}
