@file:OptIn(ExperimentalWasmJsInterop::class)

package it.kapfer.bankteller

import web.window.window
import js.reflect.unsafeCast
import js.promise.await
import kotlin.js.JsAny
import kotlin.js.ExperimentalWasmJsInterop
import js.promise.Promise

class WasmPlatform : Platform {
    override val name: String = "Web with Kotlin/Wasm"
}

actual fun getPlatform(): Platform = WasmPlatform()

actual fun getCurrentPathname(): String = window.location.pathname
actual fun getCurrentSearch(): String = window.location.search
actual fun openUrlInNewTab(url: String) { window.open(url = url, target = unsafeCast("_blank")) }
actual fun redirectTo(url: String) { window.location.href = url }

/**
 * Creates a JS Uint8Array of the given size.
 */
@JsFun("(size) => new Uint8Array(size)")
private external fun createUint8Array(size: Int): JsAny

/**
 * Sets a single byte in a JS Uint8Array at the given index.
 */
@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setUint8(array: JsAny, index: Int, value: Byte)

/**
 * Converts a Kotlin ByteArray to a JS Uint8Array by creating the typed array
 * in JS and filling it element-by-element. This is the canonical Kotlin/Wasm
 * pattern — there is no direct ByteArray→Uint8Array interop.
 */
private fun byteArrayToUint8Array(bytes: ByteArray): JsAny {
    val uint8Array = createUint8Array(bytes.size)
    for (i in bytes.indices) {
        setUint8(uint8Array, i, bytes[i])
    }
    return uint8Array
}

/**
 * Converts a JS Uint8Array back to a Kotlin ByteArray.
 * Uses @JsFun to build a regular array, then maps in Kotlin.
 */
@JsFun("(arr) => Array.from(arr)")
private external fun uint8ArrayToJsArray(arr: JsAny): JsAny

@JsFun("(arr, i) => arr[i]")
private external fun jsArrayGet(arr: JsAny, i: Int): Int

@JsFun("(arr) => arr.length")
private external fun jsArrayLength(arr: JsAny): Int

private fun uint8ArrayToByteArray(arr: JsAny): ByteArray {
    val jsArr = uint8ArrayToJsArray(arr)
    val len = jsArrayLength(jsArr)
    return ByteArray(len) { jsArrayGet(jsArr, it).toByte() }
}

/**
 * Pre-scales image bytes using the browser's native canvas API with
 * imageSmoothingEnabled=true for proper anti-aliased downscaling.
 */
actual suspend fun preScaleImageBytes(srcBytes: ByteArray, dstWidth: Int, dstHeight: Int): ByteArray? {
    return try {
        consoleLog("preScaleImageBytes: start, ${srcBytes.size} bytes, target ${dstWidth}x${dstHeight}")
        val jsBytes = byteArrayToUint8Array(srcBytes)
        consoleLog("preScaleImageBytes: jsBytes created")
        val result = preScaleImageBytesJs(jsBytes, dstWidth, dstHeight).await()
        consoleLog("preScaleImageBytes: JS returned result")
        val byteArray = uint8ArrayToByteArray(result)
        consoleLog("preScaleImageBytes: converted to ByteArray, ${byteArray.size} bytes")
        byteArray
    } catch (e: Throwable) {
        consoleLog("preScaleImageBytes: FAILED: ${e.message}")
        null
    }
}

@JsFun("(msg) => console.log(msg)")
private external fun consoleLogJs(msg: String)

actual fun consoleLog(msg: String) {
    consoleLogJs(msg)
}

@JsFun("() => Math.round(window.devicePixelRatio || 1)")
private external fun getDevicePixelRatioJs(): Int

actual fun getDevicePixelRatio(): Int = getDevicePixelRatioJs()

/**
 * JS interop: takes a Uint8Array of PNG bytes, pre-scales the image using
 * browser canvas with smoothing, and returns a Uint8Array of scaled PNG bytes.
 */
@JsFun("(srcBytes, dstWidth, dstHeight) => new Promise(function(resolve, reject) { const srcBuffer = srcBytes.buffer.slice(srcBytes.byteOffset, srcBytes.byteOffset + srcBytes.byteLength); const blob = new Blob([srcBuffer], { type: 'image/png' }); createImageBitmap(blob).then(function(imageBitmap) { const canvas = new OffscreenCanvas(dstWidth, dstHeight); const ctx = canvas.getContext('2d'); ctx.imageSmoothingEnabled = true; ctx.imageSmoothingQuality = 'high'; ctx.drawImage(imageBitmap, 0, 0, dstWidth, dstHeight); imageBitmap.close(); canvas.convertToBlob({ type: 'image/png' }).then(function(pngBlob) { pngBlob.arrayBuffer().then(function(arrayBuffer) { resolve(new Uint8Array(arrayBuffer)); }); }).catch(reject); }).catch(reject); })")
private external fun preScaleImageBytesJs(srcBytes: JsAny, dstWidth: Int, dstHeight: Int): Promise<JsAny>
