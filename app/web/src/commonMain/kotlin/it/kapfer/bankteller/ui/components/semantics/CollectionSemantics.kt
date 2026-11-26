package it.kapfer.bankteller.ui.components.semantics

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics

/**
 * Declares a single-column collection's item count — WCAG 1.3.1 Info and
 * Relationships — so assistive tech announces "N items" for a list of
 * cards/rows rather than an unstructured run of children.
 */
fun Modifier.collectionInfo(itemCount: Int): Modifier = semantics {
    collectionInfo = CollectionInfo(rowCount = itemCount, columnCount = 1)
}