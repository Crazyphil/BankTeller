package it.kapfer.bankteller.ui.components.semantics

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle

/**
 * A polite live region for ASYNCHRONOUS application-state announcements — sync
 * progress, in-flight waits, and errors — so assistive tech reads the change
 * without the user's focus (WCAG 4.1.3 Status Messages).
 *
 * Deliberately NOT for static text (titles, labels, instructions): only text
 * whose value changes as application state updates belongs here.
 */
@Composable
fun StatusText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.labelMedium,
) {
    Text(
        text = text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = color,
        style = style,
    )
}