package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.jetBrainsMonoFamily
import it.kapfer.bankteller.ui.theme.Dimens

/**
 * Sovereign Letterhead scaffold for public legal pages (tasks 10.5/10.6, design D13):
 * letterhead header (32dp logo + "BankTeller" wordmark in Fraunces), a brass
 * double-rule hairline separating letterhead from content, title in Fraunces
 * `headlineLarge`, "Last updated" in JetBrains Mono `bodySmall`, then the document
 * sections. Replaces the old wordmark footer with a compact legal line in JetBrains
 * Mono `labelSmall`.
 */
@Composable
fun LegalDocument(
    title: String,
    lastUpdated: String,
    footer: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.md),
    ) {
        // Letterhead header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            BrandLogo(size = Dimens.xl)
            Text(
                text = "BankTeller",
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Spacer(Modifier.height(Dimens.xs))

        BrassDoubleRule(modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(Dimens.sm))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
        )

        Text(
            text = "Last updated · $lastUpdated",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Dimens.md))

        content()

        Spacer(Modifier.height(Dimens.lg))

        Text(
            text = footer,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = jetBrainsMonoFamily()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A legal-document section: Inter `titleMedium` header with a 2dp `primary` left
 * border tick, body in Inter `bodyLarge` with a ~1.6x line height for legal reading.
 * The 2dp tick width falls below the `Dimens` spacing ladder (xs = 4dp) and stays
 * inline per task 15.1's one-off-value exception.
 */
@Composable
fun LegalSection(
    title: String,
    body: String,
) {
    Row(
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp) // tick width — below Dimens ladder
                .height(Dimens.md)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(Dimens.sm))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.6f, // ~1.6x for legal reading (D13)
            )
        }
    }
}