package it.kapfer.bankteller.ui.components.semantics

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * Gives an icon-only interactive control a MANDATORY accessible name plus the
 * button role — WCAG 1.1.1 Non-text Content and 4.1.2 Name, Role, Value.
 *
 * Apply it to the INTERACTIVE container (an M3 `IconButton`, or a hand-rolled
 * `Modifier.clickable` icon/box). The glyph inside stays decorative
 * (`contentDescription = null`) so the name is announced once, from the node the
 * user can actually activate. The click itself is projected from the existing
 * `clickable`, so no `onClick` semantics action is declared here (that would
 * duplicate the action handling).
 */
fun Modifier.semanticIconAction(name: String): Modifier = semantics {
    contentDescription = name
    role = Role.Button
}

/**
 * The accessible name of the theme toggle (WCAG 1.1.1 / 4.1.2), kept in one
 * place so the visible tooltip and the announced icon action never drift apart.
 * Preserves the established "Theme mode: <label>" wording exactly.
 */
fun themeToggleDescription(label: String): String = "Theme mode: $label"