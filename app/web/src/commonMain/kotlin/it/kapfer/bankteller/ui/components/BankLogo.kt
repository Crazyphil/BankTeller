package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.encodeURLParameter
import it.kapfer.bankteller.createHttpClient
import it.kapfer.bankteller.ui.theme.Dimens
import org.jetbrains.skia.Image as SkiaImage

/**
 * Module-level cache for loaded bank logo ImageBitmaps, keyed by logo URL.
 * Shared by every [BankLogo] call site (wizard + dashboard cards); survives
 * recomposition, cleared on page reload.
 */
private val bankLogoCache = mutableMapOf<String, ImageBitmap>()
private val bankLogoHttpClient by lazy { createHttpClient() }

/**
 * Shared bank logo renderer (task 8.13 — consolidated from the wizard's and
 * the dashboard's duplicated private implementations): loads the remote logo
 * PNG via the server-side `/api/logo` proxy (which scales with high-quality
 * bilinear resampling — Skia's wasmJs downscaling is nearest-neighbor only),
 * and falls back to the [BankAvatar] initials treatment (DESIGN-LANGUAGE
 * §6.2) when the URL is absent, while the fetch is in flight, or when the
 * fetch fails — never an empty placeholder box.
 *
 * @param size The logo slot's edge length — wizard 64dp, dashboard cards 40dp.
 * @param clipLogo Whether the loaded image is clipped to a small rounded
 *   corner (the wizard treatment); the dashboard cards render unclipped.
 */
@Composable
fun BankLogo(
    bankName: String,
    logoUrl: String?,
    bic: String? = null,
    size: Dp = 64.dp,
    clipLogo: Boolean = true,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(logoUrl) { mutableStateOf(bankLogoCache[logoUrl]) }
    var loadFailed by remember(logoUrl) { mutableStateOf(logoUrl == null) }

    LaunchedEffect(logoUrl) {
        if (logoUrl != null && bitmap == null && !loadFailed) {
            try {
                val scaledUrl = "/api/logo?url=" + logoUrl.encodeURLParameter() + "&size=64"
                val bytes = bankLogoHttpClient.get(scaledUrl).readRawBytes()
                val loaded = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                bankLogoCache[logoUrl] = loaded
                bitmap = loaded
            } catch (_: Throwable) {
                loadFailed = true
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = modifier
                .size(size)
                .then(if (clipLogo) Modifier.clip(RoundedCornerShape(Dimens.sm)) else Modifier),
        )
    } else {
        BankAvatar(
            bankName = bankName,
            bic = bic,
            modifier = modifier.size(size),
        )
    }
}
