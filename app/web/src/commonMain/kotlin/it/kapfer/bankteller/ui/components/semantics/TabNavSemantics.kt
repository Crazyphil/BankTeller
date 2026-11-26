package it.kapfer.bankteller.ui.components.semantics

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/**
 * Bottom-tab item semantics (WCAG 4.1.2 Name, Role, Value): the tab role, the
 * accessible name, and the selected state live on the clickable column node; the
 * glyph inside stays decorative.
 */
fun Modifier.tabItemSemantics(label: String, selected: Boolean): Modifier = semantics {
    role = Role.Tab
    contentDescription = label
    this.selected = selected
}

/**
 * Navigation-row semantics for a row whose own text already names it (WCAG
 * 4.1.2): the button role only — the merged text supplies the name.
 */
fun Modifier.navRowSemantics(): Modifier = semantics { role = Role.Button }

/**
 * Navigation-row semantics for the expanded section column (WCAG 4.1.2): button
 * role plus the current-selection state.
 */
fun Modifier.navRowSemantics(selected: Boolean): Modifier = semantics {
    role = Role.Button
    this.selected = selected
}

/**
 * Compact back affordance (WCAG 4.1.2): button role plus an explicit name that
 * says where the control goes, beyond the visible "Settings" label.
 */
fun Modifier.backAffordanceSemantics(name: String): Modifier = semantics {
    role = Role.Button
    contentDescription = name
}