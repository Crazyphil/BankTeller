package it.kapfer.bankteller

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsProperties.CollectionInfo
import androidx.compose.ui.semantics.SemanticsProperties.ContentDescription
import androidx.compose.ui.semantics.SemanticsProperties.Heading
import androidx.compose.ui.semantics.SemanticsProperties.LiveRegion
import androidx.compose.ui.semantics.SemanticsProperties.Selected
import androidx.compose.ui.semantics.SemanticsProperties.Text as SemanticsText
import androidx.compose.ui.semantics.SemanticsProperties.Role as SemanticsRole
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.SemanticsMatcher.Companion.keyIsDefined
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import it.kapfer.bankteller.ui.components.AppTabBar
import it.kapfer.bankteller.ui.components.appTabBarEntries
import it.kapfer.bankteller.ui.components.semantics.StatusText
import it.kapfer.bankteller.ui.components.semantics.collectionInfo
import it.kapfer.bankteller.ui.components.semantics.fieldName
import it.kapfer.bankteller.ui.components.semantics.navRowSemantics
import it.kapfer.bankteller.ui.components.semantics.screenHeading
import it.kapfer.bankteller.ui.components.semantics.semanticIconAction
import it.kapfer.bankteller.ui.components.semantics.themeToggleDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Semantics contracts for the `semantic-ui-primitives` layer
 * (wcag-semantics-conformance D5, spec: primitive-semantics contracts on
 * JVM).
 *
 * Each primitive's DECLARED semantics is asserted structurally on the JVM
 * semantics tree — the Compose-side truth the web bridge mirrors. A refactor
 * that strips a declaration fails HERE, naming the primitive and the missing
 * property — the semantics equivalent of the contrast test; new primitives
 * join this class's declared set (the enforcement requirement).
 *
 * The assertions declare the CONTRACT, not bridge behavior: bridge internals
 * are upstream code (`webMain` of compose-multiplatform-core), verified by
 * the 2026-11 live spike and the 1.12.2 post-upgrade probe (task 1.2, the
 * change's notes.md) and a manual-verification concern.
 *
 * Zero browser or ViewModel dependencies: the composables instantiated are
 * the primitives themselves plus the migrated tab bar — the same hermeticity
 * bar as the contrast pair (see app/web/build.gradle.kts's jvmTest filter,
 * the hermetic trio). The `onNode`/`onAllNodes` finders and
 * `assertExists`/`fetchSemanticsNode` assertions are [runComposeUiTest]'s
 * receiver members (androidx.compose.ui.test).
 */
@OptIn(ExperimentalTestApi::class)
class SemanticsContractTest {

    // ------------------------------------------------------------------
    // semanticIconAction (2.1) — WCAG 1.1.1 / 4.1.2
    // ------------------------------------------------------------------

    /**
     * The icon-action primitive exposes a NON-EMPTY accessible name AND the
     * button role on its node. The name parameter is mandatory (no unnamed
     * overload exists), so every instance the call sites create is a named
     * one; this lock fails if the declaration itself is stripped.
     */
    @Test
    fun semanticIconAction_exposesNameAndButtonRole() = runComposeUiTest {
        setContent {
            Text(text = "", modifier = Modifier.semanticIconAction("Dismiss"))
        }
        val node = onNode(expectValue(ContentDescription, listOf("Dismiss")))
            .assertExists("semanticIconAction must declare its mandatory accessible name")
            .fetchSemanticsNode()
        assertEquals(Role.Button, node.config.getOrNull(SemanticsRole), "the primitive declares the button role")
    }

    /**
     * The theme toggle's accessible name keeps the spike-verified wording —
     * the one icon action whose string is a spec-level contract
     * (design-system-theme: "Theme mode: <Mode>").
     */
    @Test
    fun themeToggleDescription_keepsVerifiedWording() {
        assertEquals("Theme mode: System", themeToggleDescription("System"))
        assertEquals("Theme mode: Light", themeToggleDescription("Light"))
        assertEquals("Theme mode: Dark", themeToggleDescription("Dark"))
    }

    // ------------------------------------------------------------------
    // StatusText (2.2) — WCAG 4.1.3
    // ------------------------------------------------------------------

    /**
     * StatusText declares liveRegion-Polite on its own text node, mirrored
     * to `aria-live` by the ≥1.12 bridge; a plain Text carries no liveRegion
     * — the announcement contract is per-primitive, not tree-global.
     */
    @Test
    fun statusText_declaresLiveRegionPolite() = runComposeUiTest {
        setContent {
            Column {
                StatusText(text = "Updating balances…")
                Text(text = "Settings section title")
            }
        }
        val status = onNode(keyIsDefined(LiveRegion))
            .assertExists("StatusText must declare liveRegion on its own node")
            .fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, status.config.getOrNull(LiveRegion), "politeness is Polite (never interrupting)")
        assertEquals(
            listOf(AnnotatedString("Updating balances…")),
            status.config.getOrNull(SemanticsText),
            "the liveRegion node is StatusText's own text node",
        )
        val plain = onNode(textMatcher("Settings section title")).fetchSemanticsNode()
        assertNull(plain.config.getOrNull(LiveRegion), "static Text must not declare liveRegion — StatusText alone announces")
    }

    // ------------------------------------------------------------------
    // screenHeading (2.3) — WCAG 1.3.1 / 2.4.6
    // ------------------------------------------------------------------

    /**
     * The heading modifier marks the existing visual title as a heading
     * node — no new visuals, semantics only. A plain Text carries no
     * heading property.
     */
    @Test
    fun screenHeading_setsHeadingProperty() = runComposeUiTest {
        setContent {
            Column {
                Text(text = "BankTeller", modifier = Modifier.screenHeading)
                Text(text = "Below the fold")
            }
        }
        val heading = onNode(keyIsDefined(Heading))
            .assertExists("screenHeading must set SemanticsProperties.Heading")
            .fetchSemanticsNode()
        assertEquals(listOf(AnnotatedString("BankTeller")), heading.config.getOrNull(SemanticsText))
        val plain = onNode(textMatcher("Below the fold")).fetchSemanticsNode()
        assertNull(plain.config.getOrNull(Heading))
    }

    // ------------------------------------------------------------------
    // tabItemSemantics + navRowSemantics (2.4) — WCAG 4.1.2
    // ------------------------------------------------------------------

    /**
     * The migrated tab bar: every tab item's node carries Role.Tab, the
     * label as its accessible name, and the Selected state matching the
     * active tab — machine-readable without visual inspection (the brass
     * indicator is redundant with, not a substitute for, the state).
     */
    @Test
    fun appTabBar_tabItemsDeclareRoleNameAndSelection() = runComposeUiTest {
        setContent {
            AppTabBar(
                selectedTabId = "settings",
                onTabSelected = { },
                tabs = appTabBarEntries,
            )
        }
        val tabs = onAllNodes(roleTabMatcher).fetchSemanticsNodes()
        assertEquals(2, tabs.size, "both tab items expose Role.Tab")
        val byName = tabs.associateBy { node ->
            node.config.getOrNull(ContentDescription)?.firstOrNull()
                ?: error("tab item carries its label as accessible name")
        }
        val dashboard = byName.getValue("Dashboard")
        val settings = byName.getValue("Settings")
        assertEquals(true, settings.config.getOrNull(Selected), "active tab declares Selected=true")
        assertEquals(false, dashboard.config.getOrNull(Selected), "inactive tab declares Selected=false")
    }

    /**
     * navRowSemantics: the selected-state variant declares Role.Button plus
     * the current selection; the plain variant declares the role alone
     * (the merged row text supplies the name).
     */
    @Test
    fun navRowSemantics_declaresRoleAndSelection() = runComposeUiTest {
        setContent {
            Column {
                Text(text = "Bank connections", modifier = Modifier.navRowSemantics(selected = true))
                Text(text = "Notifications", modifier = Modifier.navRowSemantics())
            }
        }
        val active = onNode(textMatcher("Bank connections")).fetchSemanticsNode()
        assertEquals(Role.Button, active.config.getOrNull(SemanticsRole))
        assertEquals(true, active.config.getOrNull(Selected), "the selected variant declares Selected=true")
        val plain = onNode(textMatcher("Notifications")).fetchSemanticsNode()
        assertEquals(Role.Button, plain.config.getOrNull(SemanticsRole))
        assertNull(plain.config.getOrNull(Selected), "the plain variant declares role only")
    }

    // ------------------------------------------------------------------
    // fieldName (2.5) — WCAG 4.1.2 / 2.4.6
    // ------------------------------------------------------------------

    /**
     * The field wrapper merges the accessible name onto the field's own
     * node: contentDescription declared alongside the node's own content
     * (the spike found the M3 label otherwise renders as a separate
     * unassociated node, leaving the textbox unnamed).
     */
    @Test
    fun fieldName_declaresNameOnOwnNode() = runComposeUiTest {
        setContent {
            Column {
                Text(text = "Username", modifier = Modifier.fieldName("Username"))
            }
        }
        val named = onNode(expectValue(ContentDescription, listOf("Username")))
            .assertExists("fieldName must declare the field's accessible name")
            .fetchSemanticsNode()
        assertEquals(
            listOf(AnnotatedString("Username")),
            named.config.getOrNull(SemanticsText),
            "the name and the node's own text are both present (merge semantics)",
        )
    }

    // ------------------------------------------------------------------
    // collectionInfo (2.6) — WCAG 1.3.1
    // ------------------------------------------------------------------

    /**
     * List-bearing containers declare collection semantics so screen
     * readers announce list structure (the bridge maps collections to
     * role=list/grid per the source-research mapping).
     */
    @Test
    fun collectionInfo_declaresItemCount() = runComposeUiTest {
        setContent {
            Column(modifier = Modifier.collectionInfo(3)) {
                Text("Card A")
                Text("Card B")
                Text("Card C")
            }
        }
        val collection = onNode(keyIsDefined(CollectionInfo))
            .assertExists("collectionInfo must declare CollectionInfo")
            .fetchSemanticsNode()
        assertEquals(
            CollectionInfo(rowCount = 3, columnCount = 1),
            collection.config.getOrNull(CollectionInfo),
            "collectionInfo declares rowCount=itemCount, single column",
        )
    }
}

// ----------------------------------------------------------------------
// Matchers (androidx.compose.ui.test common API — companion builders)
// ----------------------------------------------------------------------

/** Node whose text list contains [value]. */
private fun textMatcher(value: String) =
    androidx.compose.ui.test.SemanticsMatcher("Text = '$value'") { node ->
        node.config.getOrNull(SemanticsText)
            ?.any { it.text == value } == true
    }

/** Node declaring Role.Tab. */
private val roleTabMatcher =
    androidx.compose.ui.test.SemanticsMatcher("Role = Tab") { node ->
        node.config.getOrNull(SemanticsRole) == Role.Tab
    }
