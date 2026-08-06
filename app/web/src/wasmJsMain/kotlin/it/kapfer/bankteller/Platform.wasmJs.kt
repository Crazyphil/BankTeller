package it.kapfer.bankteller

import web.window.window
import js.reflect.unsafeCast

class WasmPlatform : Platform {
    override val name: String = "Web with Kotlin/Wasm"
}

actual fun getPlatform(): Platform = WasmPlatform()

actual fun getCurrentPathname(): String = window.location.pathname
actual fun getCurrentSearch(): String = window.location.search
actual fun openUrlInNewTab(url: String) { window.open(url = url, target = unsafeCast("_blank")) }
