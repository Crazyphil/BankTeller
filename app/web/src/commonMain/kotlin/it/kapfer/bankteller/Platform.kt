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
