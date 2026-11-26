package it.kapfer.bankteller.ui.components.semantics

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * Marks a text node as a section heading — WCAG 1.3.1 Info and Relationships
 * and 2.4.6 Headings and Labels — so assistive tech can build a page outline
 * from the visual title hierarchy. Apply to titles only, never body copy.
 */
val Modifier.screenHeading: Modifier
    get() = semantics { heading() }