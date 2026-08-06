package it.kapfer.bankteller

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import bankteller.app.web.generated.resources.*
import org.jetbrains.compose.resources.Font

/**
 * Returns a [FontFamily] backed by Roboto Mono variable fonts (upright + italic,
 * weight 100–700). Use in place of `FontFamily.Monospace` to get a real monospace
 * face in Compose-for-Web (Skiko/Skia does not include a monospace face by default).
 */
@Composable
fun robotoMonoFamily(): FontFamily = FontFamily(
    Font(Res.font.RobotoMono, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.RobotoMono, FontWeight.Bold, FontStyle.Normal),
    Font(Res.font.RobotoMono_Italic, FontWeight.Normal, FontStyle.Italic),
    Font(Res.font.RobotoMono_Italic, FontWeight.Bold, FontStyle.Italic),
)
