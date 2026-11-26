package it.kapfer.bankteller.ui.components.semantics

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Gives a text field an explicit accessible name — WCAG 4.1.2 Name, Role,
 * Value and 2.4.6 Labels — mirroring the field's visible label so the announced
 * name survives even when the M3 field's inner decoration node is not merged
 * into the editable node.
 */
fun Modifier.fieldName(name: String): Modifier = semantics { contentDescription = name }