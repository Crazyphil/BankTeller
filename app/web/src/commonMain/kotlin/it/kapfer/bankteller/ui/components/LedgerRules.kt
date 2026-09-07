package it.kapfer.bankteller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

/**
 * Ledger double-rule accent: two hairline brass rules at 30% opacity (design D13).
 * Used on the Sovereign Letterhead (privacy/terms) and across the top edge of the
 * callback status card.
 *
 * The 1dp rule heights and 2dp gap are ledger hairlines that deliberately fall
 * below the `Dimens` spacing ladder (xs = 4dp); they stay inline per task 15.1's
 * one-off-value exception.
 */
@Composable
fun BrassDoubleRule(modifier: Modifier = Modifier) {
    val brass = LocalBankTellerColors.current.brass
    val ruleColor = brass.copy(alpha = 0.3f)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp), // hairline gap — below Dimens ladder
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp) // hairline — below Dimens ladder
                .background(ruleColor),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp) // hairline — below Dimens ladder
                .background(ruleColor),
        )
    }
}