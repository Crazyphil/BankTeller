package it.kapfer.bankteller.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AuthProgress account-selection consequence copy (task 8.20, spec:
 * onboarding — SPA AuthProgress step, account-selection consequence):
 * the initial-setup/add-bank flows carry the trailing guidance-card
 * instruction (nothing is at stake — no DecisionBox), while
 * re-authorization with stored accounts keeps the elevated brass
 * ACCOUNT SELECTION NOTICE (stored data at risk). Pinned verbatim so
 * copy drift is caught.
 */
class AuthProgressCopyTest {

    @Test
    fun selectionConsequenceInstruction_initialSetupAndAddBank() {
        assertEquals(
            "Then select every account you want to track in BankTeller and click \"Grant authorization\". " +
                    "To add more accounts later, you will need to authorize this bank again.",
            selectionConsequenceInstruction,
        )
    }

    @Test
    fun accountSelectionNoticeBody_reAuthorization() {
        assertEquals(
            "Your selection at the bank will replace your connected accounts for this bank. " +
                    "Keep all accounts checked that you still want to see in BankTeller.",
            accountSelectionNoticeBody,
        )
    }
}
