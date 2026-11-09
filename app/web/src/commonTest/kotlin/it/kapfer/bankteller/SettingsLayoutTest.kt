package it.kapfer.bankteller

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import it.kapfer.bankteller.ui.components.showCropBottomHairline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-derivation tests for the Settings surface (bank-connection-management
 * 4.4/10.2): the hand-rolled 768dp breakpoint (NOT the stock 600/840
 * window-size-class boundaries), the section registry's single-section
 * contract, and the connection-card copy builders.
 */
class SettingsLayoutTest {

    // ---------------------------------------------------------------
    // 768dp breakpoint (D4)
    // ---------------------------------------------------------------

    @Test
    fun breakpoint_is_expanded_strictly_above_768dp() {
        assertEquals(SettingsLayoutMode.Expanded, settingsLayoutMode(null, viewportWidthDp = 769))
    }

    @Test
    fun breakpoint_is_compact_at_or_below_768dp() {
        assertEquals(SettingsLayoutMode.Compact, settingsLayoutMode(null, viewportWidthDp = 768))
        assertEquals(SettingsLayoutMode.Compact, settingsLayoutMode(null, viewportWidthDp = 320))
        assertEquals(SettingsLayoutMode.Compact, settingsLayoutMode(null, viewportWidthDp = 0))
    }

    @Test
    fun stock_compact_class_is_a_compact_shortcut_when_unmeasured() {
        // No pixel measurement (defensive): the stock Compact class (<600dp)
        // can never exceed the threshold — it maps to Compact; every other
        // class falls back to Expanded.
        assertEquals(SettingsLayoutMode.Compact, settingsLayoutMode(WindowWidthSizeClass.Compact, viewportWidthDp = null))
        assertEquals(SettingsLayoutMode.Expanded, settingsLayoutMode(WindowWidthSizeClass.Medium, viewportWidthDp = null))
    }

    @Test
    fun measurement_outranks_the_stock_class() {
        // The pixel measurement governs when present (D4): a 700dp viewport
        // is Medium by stock classes but Compact by the 768dp threshold.
        assertEquals(
            SettingsLayoutMode.Compact,
            settingsLayoutMode(WindowWidthSizeClass.Medium, viewportWidthDp = 700),
        )
        // And a 900dp viewport is Expanded regardless of the class.
        assertEquals(
            SettingsLayoutMode.Expanded,
            settingsLayoutMode(WindowWidthSizeClass.Medium, viewportWidthDp = 900),
        )
    }

    @Test
    fun viewport_dp_derivation_scales_by_device_pixel_ratio() {
        assertEquals(768, viewportWidthDpFrom(px = 768, devicePixelRatio = 1.0))
        assertEquals(384, viewportWidthDpFrom(px = 768, devicePixelRatio = 2.0))
        assertNull(viewportWidthDpFrom(px = null, devicePixelRatio = 2.0))
        assertNull(viewportWidthDpFrom(px = 0, devicePixelRatio = 1.0))
    }

    @Test
    fun viewport_dp_derivation_fractional_dpr_classifies_the_correct_side_of_the_threshold() {
        // F3: the FRACTIONAL DPR (Windows 125%/150% scaling) — Compose for
        // Web uses it as the layout density, so the 768dp threshold must
        // classify against the same value the rendered layout follows.
        // The motivating bug: a 1200px window at DPR 1.5 is really 800dp
        // (Expanded) — the old Int-rounded derivation computed 600dp
        // (Compact) and misclassified the Settings layout.
        assertEquals(800, viewportWidthDpFrom(px = 1200, devicePixelRatio = 1.5))
        assertEquals(
            SettingsLayoutMode.Expanded,
            settingsLayoutMode(WindowWidthSizeClass.Medium, viewportWidthDpFrom(px = 1200, devicePixelRatio = 1.5)),
        )
        // DPR 1.25 (Windows 125%): a 1000px window is 800dp — Expanded.
        assertEquals(800, viewportWidthDpFrom(px = 1000, devicePixelRatio = 1.25))
        // And just below the threshold: 1151px at 1.5 → 767dp — Compact.
        assertEquals(767, viewportWidthDpFrom(px = 1151, devicePixelRatio = 1.5))
        assertEquals(
            SettingsLayoutMode.Compact,
            settingsLayoutMode(WindowWidthSizeClass.Medium, viewportWidthDpFrom(px = 1151, devicePixelRatio = 1.5)),
        )
        // A DPR below 1 (zoomed-out viewports) clamps to 1.0 — never a
        // density that would inflate dp widths.
        assertEquals(1200, viewportWidthDpFrom(px = 1200, devicePixelRatio = 0.5))
    }

    // ---------------------------------------------------------------
    // Section registry (D4): exactly one initial section
    // ---------------------------------------------------------------

    @Test
    fun initial_section_set_is_exactly_bank_connections() {
        assertEquals(1, settingsSections.size)
        assertEquals("bank-connections", settingsSections.single().id)
        assertEquals("Bank connections", settingsSections.single().title)
    }

    // ---------------------------------------------------------------
    // Mode-conditional crop-signal ownership (12.1 re-run finding)
    // ---------------------------------------------------------------

    @Test
    fun shellTopDivider_isExpandedOnly_compactDrillOwnsItsTopSignal() {
        // The compact drill's static back affordance sits between the
        // zone boundary and the scrollable's true top — the shell's
        // top-bar hairline would render OVER the back button (the
        // trigger state and the rendered position belong to different
        // regions; the same defect class as 11.14's bank-list finding).
        // EXPANDED keeps the shell divider (the detail panel's
        // scrollable fills the zone to the boundary — the D19
        // exemption's premise holds there); COMPACT disables it and the
        // drill's CropSignaledScroll wrap renders the top hairline at
        // the scrollable's OWN bounds, below the back button.
        assertEquals(true, shellTopDividerActive(SettingsLayoutMode.Expanded))
        assertEquals(false, shellTopDividerActive(SettingsLayoutMode.Compact))
    }

    @Test
    fun bottomBarCropHairline_shellOwnedInBothModes() {
        // The scrollable's bottom edge IS the zone's bottom boundary in
        // BOTH layout modes (nothing sits between them) — the shell's
        // bottom-bar crop hairline (keyed on the active panel's
        // canScrollForward via externalContentCroppedBelow, wired in
        // BOTH modes) is geometrically correct everywhere. The compact
        // drill's wrapper suppresses its own bottom hairline
        // (showBottomHairline = false — structural wiring at the call
        // site): never twice at the same edge. The predicate below is
        // the shell's trigger semantics, asserted pure: cropped below
        // the fold → visible; at the end → dissolved.
        assertTrue(showCropBottomHairline(rangePx = 1600, offsetPx = 0))
        assertFalse(showCropBottomHairline(rangePx = 1600, offsetPx = 1600))
        assertFalse(showCropBottomHairline(rangePx = 0, offsetPx = 0))
    }

    // ---------------------------------------------------------------
    // Connection-card copy builders (D5)
    // ---------------------------------------------------------------

    @Test
    fun connection_subtitle_joins_country_and_psu_type_and_omits_cleanly() {
        assertEquals("Germany · personal", connectionSubtitle("Germany", "personal"))
        assertEquals("Germany · business", connectionSubtitle("Germany", "BUSINESS"))
        assertEquals("Germany", connectionSubtitle("Germany", null))
        assertEquals("Germany", connectionSubtitle("Germany", ""))
        assertEquals("personal", connectionSubtitle("", "personal"))
        // An unknown PSU type renders VERBATIM (the server contract is
        // personal/business/null, but an honest echo beats a dropped
        // value the server did send).
        assertEquals("Nord Bank · corporate", connectionSubtitle("Nord Bank", "corporate"))
    }

    @Test
    fun connection_card_caps_at_four_rows() {
        assertEquals(4, CONNECTION_CARD_VISIBLE_ROWS)
    }

    @Test
    fun missing_accounts_dialog_body_distinguishes_new_from_already_kept() {
        val body = missingAccountsDialogBody(
            bankName = "Demo Bank",
            missing = listOf(
                MissingAccount(1L, "Salary", "DE11", newlyMissing = true),
                MissingAccount(2L, "Savings", "DE22", newlyMissing = false),
            ),
        )
        // The BANK of the re-authorization renders in the lead — never an
        // account holder's name.
        assertTrue(body.contains("Your new account selection at Demo Bank kept data BankTeller no longer has access to."))
        assertTrue(body.contains("1 account went missing with this authorization"))
        assertTrue(body.contains("1 account was already kept as a local copy from an earlier authorization"))
        assertTrue(body.contains("kept as local copies"))
    }

    @Test
    fun missing_accounts_dialog_body_null_bank_name_degrades_without_the_at_clause() {
        // No flow bank known (cold boot): the "at X" clause drops entirely —
        // never an account name, never a fabricated label.
        val body = missingAccountsDialogBody(
            bankName = null,
            missing = listOf(MissingAccount(1L, "Salary", "DE11", newlyMissing = true)),
        )
        assertTrue(body.contains("Your new account selection kept data BankTeller no longer has access to."))
        assertFalse(body.contains(" at "))
        // The account names never leak into the bank position.
        assertFalse(body.contains("at Salary"))
    }

    @Test
    fun missing_accounts_dialog_rows_enumerate_identities_with_joined_balances() {
        // Task 6.4: the user decides over SPECIFIC records, never an opaque
        // count — the rows carry name, masked IBAN, the last-known balance
        // JOINED from the current payload by id, and the marker.
        val missing = listOf(
            MissingAccount(1L, "Salary", "DE89370400440532013000", newlyMissing = true),
            MissingAccount(2L, "Savings", "DE22", newlyMissing = false),
            MissingAccount(3L, null, null, newlyMissing = true),
        )
        val currentAccounts = listOf(
            AccountData(
                id = 1L,
                name = "Salary",
                iban = "DE89370400440532013000",
                currency = "EUR",
                balanceAmount = "1500.00",
                balanceCurrency = "EUR",
                balanceType = null,
                referenceDate = null,
                fetchedAt = null,
            ),
            AccountData(
                id = 2L,
                name = "Savings",
                iban = "DE22",
                currency = "EUR",
                balanceAmount = null, // no surviving balance row → no line
                balanceCurrency = null,
                balanceType = null,
                referenceDate = null,
                fetchedAt = null,
            ),
        )
        val rows = missingAccountsDialogRows(missing, currentAccounts)
        assertEquals(3, rows.size)
        // Row 1: full join — name, masked IBAN, balance.
        assertEquals("Salary", rows[0].name)
        assertEquals("DE89 •••• •••• 3000", rows[0].maskedIban)
        assertTrue(rows[0].balance != null)
        assertTrue(rows[0].newlyMissing)
        // Row 2: joined account exists but carries no balance → no line.
        assertEquals("Savings", rows[1].name)
        assertEquals(null, rows[1].balance)
        assertFalse(rows[1].newlyMissing)
        // Row 3: no matching account row at all → no balance; null name
        // and IBAN degrade (the row still enumerates the record by id).
        assertEquals(null, rows[2].name)
        assertEquals(null, rows[2].maskedIban)
        assertEquals(null, rows[2].balance)
        assertTrue(rows[2].newlyMissing)
        assertEquals(3L, rows[2].id)
    }

    @Test
    fun missing_account_row_marker_partitions_new_from_already_kept() {
        assertEquals("went missing with this authorization", missingAccountRowMarker(newlyMissing = true))
        assertEquals("already kept as a local copy", missingAccountRowMarker(newlyMissing = false))
    }

    // ---------------------------------------------------------------
    // No-op guard on the danger action (13.5, review F3 — user-ruled)
    // ---------------------------------------------------------------

    @Test
    fun all_kept_signal_disablesTheDangerAction_andRendersTheExplanatoryLine() {
        // An all-kept signal (zero newlyMissing): the danger action is a
        // clickable NO-OP — it renders DISABLED (never hidden — its
        // absence would invite "where did it go?"), the explanatory line
        // carries the why, and Keep stays the only active action.
        val allKept = listOf(
            MissingAccount(1L, "Salary", "DE11", newlyMissing = false),
            MissingAccount(2L, "Savings", "DE22", newlyMissing = false),
        )
        assertFalse(hasNewlyMissing(allKept))
        // The line is the exact spec wording, rendered into the dialog
        // body in that state (a state explanation, not a warning).
        assertEquals(
            "No new accounts went missing with this authorization — the listed accounts were already kept as local copies.",
            ALL_KEPT_EXPLANATORY_LINE,
        )
    }

    @Test
    fun mixed_and_newly_missing_signals_keepTheDangerActionEnabled_noExplanatoryLine() {
        // Mixed signal (≥1 newlyMissing): enabled as before, and the
        // explanatory line renders ONLY in the all-kept state — a normal
        // mixed signal never shows it.
        val mixed = listOf(
            MissingAccount(1L, "Salary", "DE11", newlyMissing = true),
            MissingAccount(2L, "Savings", "DE22", newlyMissing = false),
        )
        assertTrue(hasNewlyMissing(mixed))
        assertTrue(hasNewlyMissing(listOf(MissingAccount(1L, "Salary", "DE11", newlyMissing = true))))
        // An empty list cannot occur on the real path (the dialog renders
        // only on a non-empty signal); the predicate still reads false —
        // the guard never enables on nothing.
        assertFalse(hasNewlyMissing(emptyList()))
    }

    @Test
    fun missing_accounts_dialog_body_pluralizes() {
        val body = missingAccountsDialogBody(
            bankName = "Demo Bank",
            missing = listOf(
                MissingAccount(1L, "A", null, newlyMissing = true),
                MissingAccount(2L, "B", null, newlyMissing = true),
                MissingAccount(3L, "C", null, newlyMissing = false),
                MissingAccount(4L, "D", null, newlyMissing = false),
            ),
        )
        assertTrue(body.contains("2 accounts went missing with this authorization"))
        assertTrue(body.contains("2 accounts were already kept as a local copy from an earlier authorization"))
    }

    @Test
    fun remove_dialog_body_names_the_bank_and_never_promises_bank_side_closure() {
        val body = removeDialogBody("Demo Bank")
        assertTrue(body.contains("Demo Bank"))
        assertTrue(body.contains("removed from the app"))
    }

    @Test
    fun automation_guard_note_is_the_generic_reference() {
        assertEquals("Used by automations", automationGuardNote)
    }

    @Test
    fun remove_caveat_line_carries_the_exact_spec_wording() {
        // D7: "the authorization remains active at the bank until
        // <valid_until>; your bank's own portal can revoke it sooner".
        val line = removeCaveatLine("2999-01-01T00:00:00Z")
        assertTrue(line.startsWith("The connection is removed from BankTeller."))
        assertTrue(line.contains("The authorization remains active at the bank until"))
        assertTrue(line.endsWith("your bank's own portal can revoke it sooner."))
    }

    @Test
    fun remove_caveat_line_degrades_without_a_date() {
        // An unparseable/absent expiry renders the same sentence without a
        // fabricated date.
        val line = removeCaveatLine(null)
        assertTrue(line.contains("until it expires"))
        assertTrue(line.endsWith("your bank's own portal can revoke it sooner."))
    }

    // ---------------------------------------------------------------
    // Local-copy deletion confirmation dialog copy (task 11.3 —
    // one-click deletion never happens)
    // ---------------------------------------------------------------

    @Test
    fun delete_dialog_body_names_the_account_with_name_and_masked_iban() {
        val body = deleteLocalAccountDialogBody("Salary account", "DE89370400440532013000")
        assertTrue(body.contains("Salary account"))
        assertTrue(body.contains("DE89 •••• •••• 3000"))
        assertTrue(body.endsWith("This cannot be undone."))
    }

    @Test
    fun delete_dialog_body_parts_omit_cleanly() {
        // §6.2.1: name/IBAN each omit when absent — never a placeholder.
        val nameOnly = deleteLocalAccountDialogBody("Salary account", null)
        assertTrue(nameOnly.contains("Salary account"))
        assertFalse(nameOnly.contains("•"))
        val ibanOnly = deleteLocalAccountDialogBody(null, "DE89370400440532013000")
        assertTrue(ibanOnly.contains("DE89 •••• •••• 3000"))
        assertFalse(ibanOnly.contains("null"))
    }

    @Test
    fun delete_dialog_body_degrades_to_the_generic_sentence() {
        // Neither name nor IBAN: honest generic copy — never a fabricated
        // account label.
        val body = deleteLocalAccountDialogBody(null, null)
        assertEquals(
            "This account and its stored balances are removed from BankTeller. This cannot be undone.",
            body,
        )
    }
}
