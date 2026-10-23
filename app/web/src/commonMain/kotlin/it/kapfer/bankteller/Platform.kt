package it.kapfer.bankteller

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/** Current `window.location.pathname` (e.g. "/privacy", "/terms", "/enable-banking-callback"). */
expect fun getCurrentPathname(): String

/** Current `window.location.search` (the query string, including the leading "?"). */
expect fun getCurrentSearch(): String

/** Opens [url] in a new browser tab (target = "_blank"). */
expect fun openUrlInNewTab(url: String)

/** Redirects the current browser tab to [url]. */
expect fun redirectTo(url: String)

/**
 * Pre-scales an image [srcBytes] (PNG/JPEG bytes) to [dstWidth]x[dstHeight] using
 * the browser's native canvas image smoothing (anti-aliased). Returns the scaled
 * image as PNG bytes, or null on failure. On non-web targets, returns null.
 */
expect suspend fun preScaleImageBytes(srcBytes: ByteArray, dstWidth: Int, dstHeight: Int): ByteArray?

/** Logs a message to the browser console (or stdout on non-web targets). */
expect fun consoleLog(msg: String)

/** Returns the browser's device pixel ratio (1, 2, 3, etc.). Returns 1 on non-web targets. */
expect fun getDevicePixelRatio(): Int

/** Reads a value from `window.localStorage`, or null if absent. Synchronous. */
expect fun localStorageGet(key: String): String?

/** Writes a value to `window.localStorage`. Synchronous. */
expect fun localStorageSet(key: String, value: String)

/** Removes a key from `window.localStorage`. Synchronous. */
expect fun localStorageRemove(key: String)

/** Current epoch time in milliseconds. */
expect fun getCurrentEpochMillis(): Long

/**
 * Parses an RFC3339/ISO-8601 UTC timestamp (e.g. `2026-10-20T12:00:00Z`) to
 * epoch milliseconds. Returns null when the string is absent or unparseable.
 */
expect fun parseRfc3339ToEpochMillis(value: String?): Long?

/**
 * Copies [text] to the browser clipboard (navigator.clipboard). Fire-and-forget —
 * the emerald check confirmation is time-based, not result-based.
 */
expect fun copyToClipboard(text: String)

/**
 * Opens the sandbox-credentials popup window with the deterministic window name
 * `bankteller_sandbox_credentials` (~460x580, resizable). Must be called
 * SYNCHRONOUSLY inside a click handler (user-gesture requirement for popup
 * blockers). Re-clicks re-focus the existing window instead of duplicating it.
 */
expect fun openSandboxCredentialsPopup(url: String)

/**
 * localStorage key for the sandbox-credentials popup's open-state marker
 * (task 8.25). The popup sets it on load and clears it on unload; the
 * callback route reads it to decide whether a popup re-grasp is worth
 * attempting.
 */
const val SANDBOX_POPUP_OPEN_MARKER_KEY = "bankteller_sandbox_popup_open"

/**
 * Marks the sandbox-credentials popup as open (task 8.25): sets the
 * localStorage marker, registers an unload listener that clears it, and
 * registers a storage listener that closes the popup when the marker is
 * removed by another same-origin document (the self-close signal — see
 * [closeSandboxCredentialsPopupIfOpen]). Called by the popup route on
 * load. Idempotent.
 */
expect fun setSandboxPopupOpenMarker()

/**
 * Re-grasps the sandbox-credentials popup by its deterministic window name
 * and closes it. Returns true when a window was found and closed, false
 * otherwise (no window, or the close was blocked). Null-safe and silently
 * non-fatal — never breaks the caller's flow.
 */
expect fun closeNamedSandboxCredentialsPopup(): Boolean

/**
 * Callback-route close attempt (task 8.25): guarded by the popup's
 * open-state marker — when the marker is absent (no popup was opened, it
 * was blocked, or it was closed mid-flow) the attempt is skipped entirely,
 * because a naive re-grasp would create and immediately close a blank
 * window (a visible flash). When the marker is present it is consumed and
 * the close attempted; the marker is cleared regardless of the outcome so
 * a stale marker (popup crashed without firing unload) self-heals instead
 * of flashing on every future callback.
 *
 * Two-tier close: removing the marker fires a `storage` event in the
 * popup, which closes ITSELF (registered by [setSandboxPopupOpenMarker]).
 * This is the primary mechanism — it survives the cross-origin round trip
 * through the bank's login page, after which the `window.open` name lookup
 * can miss the real popup and close a fresh blank window instead. The
 * re-grasp attempt still runs as a fallback for a popup that missed the
 * storage event (e.g. listener registration failed).
 */
fun closeSandboxCredentialsPopupIfOpen(): Boolean {
    if (localStorageGet(SANDBOX_POPUP_OPEN_MARKER_KEY) == null) return false
    localStorageRemove(SANDBOX_POPUP_OPEN_MARKER_KEY)
    return closeNamedSandboxCredentialsPopup()
}

/** Sets the browser document title (popup window chrome). */
expect fun setDocumentTitle(title: String)

/**
 * URL-decodes a query-parameter value (decodeURIComponent). Returns null when
 * the value is malformed (e.g. a bare `%` sequence) — the caller treats the
 * params as unreadable.
 */
expect fun decodeUriComponent(value: String): String?

/** The browser's preferred locale tag (`navigator.language`), e.g. "de-AT". */
expect fun browserLocaleTag(): String

/**
 * Locale-aware amount formatting per the account-overview spec's amount
 * formatting requirement: digit grouping and decimal separators follow
 * [localeTag] (via `Intl.NumberFormat` on wasmJs), the fraction is normalized
 * to the currency's standard fraction digits ([currencyFractionDigits] —
 * regardless of the API string's scale), and the ISO currency code renders
 * as a suffix (e.g. "1.234,56 EUR"). Unparseable amounts fall back to the
 * verbatim string plus the currency code.
 */
expect fun formatAmountForLocale(amount: String, currency: String, localeTag: String): String

/**
 * Standard (ISO 4217) fraction digits for [currency]; 2 for the vast
 * majority, 0 for the major zero-decimal currencies, 3 for the three-decimal
 * ones. Unknown codes default to 2.
 */
fun currencyFractionDigits(currency: String): Int = when (currency.uppercase()) {
    "JPY", "KRW", "VND", "CLP", "ISK", "HUF", "TWD", "UGX", "KMF", "RWF", "DJF", "GNF", "PYG", "XAF", "XOF", "XPF" -> 0
    "BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND" -> 3
    else -> 2
}
