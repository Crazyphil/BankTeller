package it.kapfer.bankteller

import web.navigator.navigator
import web.window.window
import js.reflect.unsafeCast

class JsPlatform : Platform {
    private val userAgent = navigator.userAgent
    private val browserList = listOf("Chrome", "Firefox", "Safari", "Edge")

    override val name: String = userAgent.findAnyOf(browserList, ignoreCase = true)
        ?.let { (startIndex) -> userAgent.substring(startIndex).substringBefore(" ") }
        ?: "Unknown"
}

actual fun getPlatform(): Platform = JsPlatform()

actual fun getCurrentPathname(): String = window.location.pathname
actual fun getCurrentSearch(): String = window.location.search
actual fun openUrlInNewTab(url: String) { window.open(url = url, target = unsafeCast("_blank")) }
actual fun redirectTo(url: String) { window.location.href = url }

actual suspend fun preScaleImageBytes(srcBytes: ByteArray, dstWidth: Int, dstHeight: Int): ByteArray? = null

@JsFun("(msg) => console.log(msg)")
private external fun consoleLogJs(msg: String)

actual fun consoleLog(msg: String) {
    consoleLogJs(msg)
}

@JsFun("() => Math.round(window.devicePixelRatio || 1)")
private external fun getDevicePixelRatioJs(): Int

actual fun getDevicePixelRatio(): Int = getDevicePixelRatioJs()
