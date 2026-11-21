package it.kapfer.bankteller

import java.net.URLDecoder
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-only JVM actuals — the production target set is wasmJs-only
 * (module-structure); this file exists so the hermetic WCAG contrast test can
 * run on the JVM (`:app:web:jvmTest`, accessibility change D5).
 *
 * These are INERT throwaways that satisfy the expect/actual contract so
 * commonMain (and with it commonTest) can compile for a JVM target. They are
 * NOT faithful platform implementations: the browser-shaped ones return
 * neutral defaults or throw [UnsupportedOperationException]. Nothing in the
 * jvmTest run (which is filtered to the pure-arithmetic ThemeContrastTest)
 * exercises them, so fidelity is not required — only compilation and
 * non-brokenness.
 */
private class JvmPlatform : Platform {
    override val name: String = "JVM (test-only target)"
}

actual fun getPlatform(): Platform = JvmPlatform()

actual fun getCurrentPathname(): String = "/"

actual fun getCurrentSearch(): String = ""

/** Browser-only. */
actual fun openUrlInNewTab(url: String): Unit =
    throw UnsupportedOperationException("openUrlInNewTab is browser-only (wasmJs); test-only JVM target")

/** Browser-only. */
actual fun redirectTo(url: String): Unit =
    throw UnsupportedOperationException("redirectTo is browser-only (wasmJs); test-only JVM target")

/** Non-web targets return null (mirrors the contract's own fallback). */
actual suspend fun preScaleImageBytes(srcBytes: ByteArray, dstWidth: Int, dstHeight: Int): ByteArray? = null

actual fun consoleLog(msg: String) {
    println("[consoleLog] $msg")
}

actual fun devicePixelRatio(): Double = 1.0

/** In-memory map standing in for `window.localStorage`. */
private val inMemoryLocalStorage = ConcurrentHashMap<String, String>()

actual fun localStorageGet(key: String): String? = inMemoryLocalStorage[key]

actual fun localStorageSet(key: String, value: String) {
    inMemoryLocalStorage[key] = value
}

actual fun localStorageRemove(key: String) {
    inMemoryLocalStorage.remove(key)
}

actual fun getCurrentEpochMillis(): Long = System.currentTimeMillis()

actual fun parseRfc3339ToEpochMillis(value: String?): Long? {
    if (value == null) return null
    return try {
        val instant = runCatching { Instant.parse(value) }.getOrNull()
            ?: OffsetDateTime.parse(value).toInstant()
        instant.toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

/** Fire-and-forget in the browser; a no-op is the safe inert stand-in. */
actual fun copyToClipboard(text: String) = Unit

/** Browser-only (user-gesture popup). */
actual fun openSandboxCredentialsPopup(url: String): Unit =
    throw UnsupportedOperationException("openSandboxCredentialsPopup is browser-only (wasmJs); test-only JVM target")

actual fun setSandboxPopupOpenMarker() = Unit

actual fun closeNamedSandboxCredentialsPopup(): Boolean = false

actual fun setDocumentTitle(title: String) = Unit

actual fun decodeUriComponent(value: String): String? = try {
    URLDecoder.decode(value, Charsets.UTF_8)
} catch (_: Exception) {
    null
}

actual fun browserLocaleTag(): String = Locale.getDefault().toLanguageTag()

actual fun viewportWidthPx(): Int? = null

actual fun formatAmountForLocale(amount: String, currency: String, localeTag: String): String =
    "${formatAmountNumberForLocale(amount, currency, localeTag)} $currency"

actual fun formatAmountNumberForLocale(amount: String, currency: String, localeTag: String): String {
    val value = amount.toDoubleOrNull() ?: return amount
    val nf = NumberFormat.getNumberInstance(Locale.forLanguageTag(localeTag))
    nf.minimumFractionDigits = currencyFractionDigits(currency)
    nf.maximumFractionDigits = currencyFractionDigits(currency)
    return nf.format(value)
}

actual fun formatDateForLocale(epochMillis: Long, localeTag: String): String? = try {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
        .withLocale(Locale.forLanguageTag(localeTag))
    formatter.format(Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneOffset.UTC))
} catch (_: Exception) {
    null
}
