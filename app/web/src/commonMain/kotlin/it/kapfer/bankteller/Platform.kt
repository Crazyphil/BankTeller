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
