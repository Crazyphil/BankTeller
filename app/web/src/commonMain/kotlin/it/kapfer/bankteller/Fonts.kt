package it.kapfer.bankteller

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import bankteller.app.web.generated.resources.*
import org.jetbrains.compose.resources.Font

@Composable
fun frauncesFamily(): FontFamily = FontFamily(
    Font(Res.font.Fraunces_Variable, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.Fraunces_Variable, FontWeight.Bold, FontStyle.Normal),
)

@Composable
fun interFamily(): FontFamily = FontFamily(
    Font(Res.font.Inter_Variable, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.Inter_Variable, FontWeight.Bold, FontStyle.Normal),
)

@Composable
fun jetBrainsMonoFamily(): FontFamily = FontFamily(
    Font(Res.font.JetBrainsMono, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.JetBrainsMono, FontWeight.Bold, FontStyle.Normal),
)
