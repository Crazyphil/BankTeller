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

/**
 * Returns the browser's device pixel ratio as a FRACTIONAL Double (F3):
 * common hardware reports 1.25, 1.5, 1.75 (Windows 125%/150% scaling
 * among others), and Compose for Web uses the fractional value as the
 * layout density — the rendered layout follows px/DPR while an
 * Int-rounded DPR would misclassify every derived dp width at those
 * ratios (a 1200px window at DPR 1.5 is really 800dp, not the 600dp a
 * `Math.round` derivation computes). Defaults to 1.0 when unavailable
 * (non-web targets, missing window). The single consumer is
 * [viewportWidthDpFrom] (the Settings master-detail breakpoint).
 */
expect fun devicePixelRatio(): Double

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
 * Registers a callback invoked when the browser window regains focus
 * (client-side/watchdog D4). The focus check is the guaranteed watchdog
 * trigger: browsers throttle background-tab timers, but a returning user
 * always fires `focus` — the session liveness is re-evaluated before the user
 * can resume interacting with a dead session. On non-web targets this is an
 * inert no-op (the focus trigger does not exist there).
 */
expect fun registerWindowFocusListener(callback: () -> Unit)

/**
 * URL-decodes a query-parameter value (decodeURIComponent). Returns null when
 * the value is malformed (e.g. a bare `%` sequence) — the caller treats the
 * params as unreadable.
 */
expect fun decodeUriComponent(value: String): String?

/** The browser's preferred locale tag (`navigator.language`), e.g. "de-AT". */
expect fun browserLocaleTag(): String

/**
 * The browser viewport's CSS width in pixels (`window.innerWidth`),
 * used with the display density to derive the viewport width in dp for the
 * Settings master-detail's hand-rolled 768dp breakpoint
 * (bank-connection-management D4). Returns null when unavailable.
 */
expect fun viewportWidthPx(): Int?

/**
 * Viewport width in dp for the Settings master-detail breakpoint (D4):
 * CSS pixels divided by the device pixel ratio. Compose for Web renders
 * CSS px as dp at DPR 1; at higher densities one dp == density CSS px —
 * the FRACTIONAL DPR (F3), matching the density Compose itself uses, so
 * fractional-scaled hardware (Windows 125%/150%) classifies the same
 * side of the 768dp threshold as the rendered layout does. Returns null
 * when the raw measurement is unavailable. Pure in its inputs so the
 * derivation is unit-testable.
 */
fun viewportWidthDpFrom(px: Int?, devicePixelRatio: Double): Int? {
    if (px == null || px <= 0) return null
    val density = devicePixelRatio.coerceAtLeast(1.0)
    return (px / density).toInt()
}

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
 * The amount-only half of [formatAmountForLocale] (task 13.7 — decimal-
 * separator alignment): the locale-formatted number WITHOUT the ISO code
 * suffix. The mixed-currency hero subtotals render their figures in a
 * right-aligned block with the codes as separate trailing labels outside
 * it, so the figure and the code must be derivable as separate strings.
 * Same formatting contract as [formatAmountForLocale] (digit grouping and
 * decimal separators per [localeTag], the fraction normalized to the
 * currency's standard digits via [currencyFractionDigits]); unparseable
 * input falls back to the verbatim number — the code suffix remains the
 * composing caller's concern.
 */
expect fun formatAmountNumberForLocale(amount: String, currency: String, localeTag: String): String

/**
 * Locale-aware calendar date for the bank-side consent caveat line
 * (bank-connection-management D7): the honest "until <date>" bound after a
 * removal whose Enable Banking delete failed. Formats [epochMillis] with the
 * browser locale's standard date style (e.g. "12/3/2026" en-US,
 * "3.12.2026" de-DE) via `Intl.DateTimeFormat` on wasmJs — no weekday, no
 * time-of-day. Returns null when the timestamp is unrepresentable.
 */
expect fun formatDateForLocale(epochMillis: Long, localeTag: String): String?

/**
 * Pure builder for the caveat line's date fragment (bank-connection-management
 * D7): maps the stored consent expiry to its rendered form. Parseable
 * timestamps format via [formatDateForLocale] with the browser locale;
 * absent/unparseable values yield null (the caveat copy degrades to
 * "until it expires" — never a fabricated date).
 */
internal fun consentCaveatDate(epochMillis: Long): String? =
    formatDateForLocale(epochMillis, browserLocaleTag())

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
