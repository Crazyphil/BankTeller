package it.kapfer.bankteller

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.kapfer.bankteller.ui.components.ActionButton
import it.kapfer.bankteller.ui.components.LocalActionBusy
import it.kapfer.bankteller.ui.components.AppTabBar
import it.kapfer.bankteller.ui.components.BankLogo
import it.kapfer.bankteller.ui.components.BrandedTopBar
import it.kapfer.bankteller.ui.components.CropSignaledScroll
import it.kapfer.bankteller.ui.components.DecisionBox
import it.kapfer.bankteller.ui.components.LedgerScrollbar
import it.kapfer.bankteller.ui.components.denseWash
import it.kapfer.bankteller.ui.components.WashScale
import it.kapfer.bankteller.ui.components.QuietActionButton
import it.kapfer.bankteller.ui.components.ScreenShell
import it.kapfer.bankteller.ui.components.ThemeToggle
import it.kapfer.bankteller.ui.components.WaitingIndicator
import it.kapfer.bankteller.ui.components.semantics.StatusText
import it.kapfer.bankteller.ui.components.semantics.backAffordanceSemantics
import it.kapfer.bankteller.ui.components.semantics.collectionInfo
import it.kapfer.bankteller.ui.components.semantics.navRowSemantics
import it.kapfer.bankteller.ui.components.semantics.screenHeading
import it.kapfer.bankteller.ui.components.semantics.semanticIconAction
import it.kapfer.bankteller.ui.components.appTabBarEntries
import it.kapfer.bankteller.ui.components.dashboardTab
import it.kapfer.bankteller.ui.components.settingsTab
import it.kapfer.bankteller.ui.theme.Dimens
import it.kapfer.bankteller.ui.theme.LocalBankTellerColors

// ===============================================================
// Section registry + layout-mode derivation (pure, unit-testable)
// ===============================================================

/**
 * One Settings section (bank-connection-management D4, spec:
 * app-navigation — Settings surface): a stable id, a display title, and
 * the composable factory building the section's detail panel. The
 * factory receives the view model and the PANEL SCROLL STATE the content
 * must attach its primary scrollable to — the shell's top-bar divider
 * keys on it (D4: offset 0 → seamless). Future sections (notification
 * preferences, auth configuration) append here — the nav column, the
 * drill list, and the selection state all derive from
 * [settingsSections], so nothing needs restructuring.
 */
internal data class SettingsSection(
    val id: String,
    val title: String,
    val content: @Composable (AppViewModel, androidx.compose.foundation.ScrollState) -> Unit,
)

/**
 * The initial (and, for now, complete) section set (D4): EXACTLY ONE —
 * Bank connections.
 */
internal val settingsSections = listOf(
    SettingsSection(
        id = "bank-connections",
        title = "Bank connections",
        content = { viewModel, panelScroll -> BankConnectionsSection(viewModel, panelScroll) },
    ),
)

/** The Settings master-detail's two layout modes (D4). */
internal enum class SettingsLayoutMode { Compact, Expanded }

/**
 * The hand-rolled breakpoint (D4): strictly ABOVE 768dp of viewport width
 * → Expanded; at or below → Compact. NOT the stock window-size-class
 * boundaries (compact<600, expanded≥840) — the library provides the
 * measurement, BankTeller applies the threshold.
 */
internal val SETTINGS_BREAKPOINT_DP = 768

/**
 * Derives the Settings layout mode (D4), pure so the breakpoint ruling is
 * unit-testable. When a pixel measurement is available it governs; the
 * stock Compact class (<600dp) is honored as a Compact shortcut when no
 * measurement exists (defensive — a Compact viewport can never exceed
 * the threshold).
 */
internal fun settingsLayoutMode(
    widthSizeClass: WindowWidthSizeClass?,
    viewportWidthDp: Int?,
): SettingsLayoutMode {
    if (viewportWidthDp != null) {
        return if (viewportWidthDp > SETTINGS_BREAKPOINT_DP) {
            SettingsLayoutMode.Expanded
        } else {
            SettingsLayoutMode.Compact
        }
    }
    return if (widthSizeClass == WindowWidthSizeClass.Compact) {
        SettingsLayoutMode.Compact
    } else {
        SettingsLayoutMode.Expanded
    }
}

/**
 * Whether the shell's external top-bar divider is active for the Settings
 * surface (12.1 re-run finding), pure so the mode-conditional wiring is
 * unit-testable: EXPANDED only. In the expanded two-pane the detail
 * panel's scrollable fills the zone right up to the boundary — the
 * shell's top-bar hairline (keyed on the panel's offset) renders at the
 * scrollable's true crop edge: geometrically correct, the D19 exemption
 * holds. In the COMPACT drill the static back affordance sits between
 * the zone boundary and the scrollable's true top — a shell hairline
 * would render OVER the back button (the trigger state and the rendered
 * position belong to different regions), so compact passes null and the
 * drill's CropSignaledScroll wrap owns the top signal at the
 * scrollable's own bounds.
 */
internal fun shellTopDividerActive(mode: SettingsLayoutMode): Boolean =
    mode == SettingsLayoutMode.Expanded

// ===============================================================
// Settings screen (master-detail, D4)
// ===============================================================

/**
 * Settings surface (bank-connection-management D2/D3/D4, spec:
 * app-navigation): the second top-level authenticated tab, reachable
 * only via the client-side tab bar (the resolver never routes here).
 *
 * Hand-rolled master-detail (D4): Expanded (>768dp) renders a fixed
 * ~200dp section-navigation column beside the weight(1f) detail panel,
 * separated by a 1dp vertical hairline (outline at 8% alpha); Compact
 * (≤768dp) renders a section list → drill whose panel carries an inline
 * back affordance (a leading 14dp auto-mirrored ArrowBack icon + the
 * "Settings" label — the direction glyph is an icon, never a text
 * character). The selected section survives
 * breakpoint changes (rememberSaveable). The shell's top-bar divider
 * keys on the ACTIVE panel's scroll offset via the external-divider
 * hook (offset 0 → seamless, no elevation shadows).
 *
 * Chrome (D2/D3): shares the branded top bar and the bottom tab bar with
 * the dashboard; all other surfaces stay chrome-free.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    // Fetch only when no payload exists yet (first entry after login or a
    // cold boot) — the shared /api/accounts payload is already fresh on
    // every tab switch from the dashboard (stale-while-revalidate keeps
    // it current; a re-fetch on every tab switch would be a redundant
    // round-trip the app-navigation spec rules out). The settings surface
    // fetches nothing beyond what its sections need (the bank
    // connections section consumes the shared payload).
    LaunchedEffect(Unit) {
        if (viewModel.accountsData == null) {
            viewModel.refreshAccounts()
        }
    }

    // Library-based viewport measurement (D4): the stock class boundaries
    // are NOT used — the 768dp threshold is applied by settingsLayoutMode
    // on the pixel measurement (viewportWidthDpFrom is the pure dp
    // derivation).
    val windowSizeClass = calculateWindowSizeClass()
    val mode = settingsLayoutMode(
        windowSizeClass.widthSizeClass,
        // F3: the FRACTIONAL DPR — Compose for Web uses it as the layout
        // density, so the 768dp threshold must classify against the same
        // value the rendered layout follows (Windows 125%/150% scaling).
        viewportWidthDpFrom(viewportWidthPx(), devicePixelRatio()),
    )

    // Section selection survives breakpoint flips (D4) and tab switches —
    // held in the view model: a navigation-local `rememberSaveable` died with
    // the disposed Settings branch on tab switches (the 5.3 close-out finding);
    // view-model state survives the branch round-trip by construction.
    val selectedSection = settingsSections.firstOrNull { it.id == viewModel.settingsSelectedSectionId }

    // The ACTIVE panel's scroll state (D4): the section's detail panel
    // attaches its primary scrollable to this state, and the shell's
    // top-bar divider keys on its offset via the external-divider hook
    // (offset 0 → seamless). One state object for the surface: with the
    // initial single-section set the detail panel is the only panel, so
    // "active" is unambiguous.
    val activePanelScrollState = rememberScrollState()

    ScreenShell(
        scrollable = false,
        // Top-bar divider (MODE-CONDITIONAL, 12.1 re-run finding): the
        // external-divider hook is EXPANDED-ONLY — in the expanded two-
        // pane the detail panel's scrollable fills the zone right up to
        // the boundary, so the shell's top-bar hairline (keyed on the
        // panel's offset) renders at the scrollable's true crop edge:
        // geometrically correct, the D19 exemption holds. In the COMPACT
        // DRILL the back affordance + spacing sit between the zone
        // boundary and the scrollable's true top — the shell hairline
        // would render OVER the static back button (the same geometry
        // defect class as 11.14's bank-list finding: the trigger state
        // and the rendered position belong to different regions). So:
        // compact passes NULL (no shell top divider) and the drill wraps
        // its section content in CropSignaledScroll (hairlines at the
        // scrollable's OWN bounds, below the back button).
        externalDividerOffsetPx = if (shellTopDividerActive(mode)) {
            { activePanelScrollState.value }
        } else {
            null
        },
        // Crop state for the bottom-bar hairline (task 11.10): the ACTIVE
        // panel's canScrollForward — content cropped beneath the tab bar
        // while the panel can still scroll forward; dissolves at the end.
        // BOTH modes: the scrollable's bottom edge IS the zone's bottom
        // boundary in both layouts (nothing sits between them), so the
        // shell-owned bottom hairline is geometrically correct everywhere.
        externalContentCroppedBelow = { activePanelScrollState.canScrollForward },
        topBar = {
            BrandedTopBar(
                actions = {
                    ThemeToggle()
                    TextButton(onClick = { viewModel.logout() }) {
                        Text("Logout")
                    }
                },
            )
        },
        bottomBar = {
            AppTabBar(
                selectedTabId = settingsTab.id,
                // Dispatch by the tapped tab's id — see the dashboard's
                // bottomBar for the toggle-trap rationale. Tapping the
                // active Settings tab is a no-op.
                onTabSelected = { tabId ->
                    if (tabId == dashboardTab.id) {
                        viewModel.openDashboardTab()
                    }
                },
                tabs = appTabBarEntries,
            )
        },
    ) {
        when (mode) {
            SettingsLayoutMode.Expanded -> {
                val active = selectedSection ?: settingsSections.first()
                SettingsTwoPane(
                    viewModel,
                    activeSection = active,
                    panelScrollState = activePanelScrollState,
                    onSectionSelected = { viewModel.settingsSelectedSectionId = it.id },
                )
            }
            SettingsLayoutMode.Compact -> {
                val active = selectedSection
                if (active == null) {
                    SettingsSectionList(
                        panelScrollState = activePanelScrollState,
                        onSectionSelected = { viewModel.settingsSelectedSectionId = it.id },
                    )
                } else {
                    SettingsPanel(
                        viewModel,
                        section = active,
                        panelScrollState = activePanelScrollState,
                        onBack = { viewModel.settingsSelectedSectionId = null },
                    )
                }
            }
        }
    }
}

/**
 * Expanded (>768dp) two-pane layout (D4): the fixed ~200dp section-nav
 * column, a 1dp vertical hairline (the shared theme-aware hairline tone
 * — §6.1.4/§6.1.7; quiet but visible in BOTH themes per task 12.1),
 * then the weight(1f) detail panel.
 *
 * Pane breathing room (task 11.1 walkthrough finding): the nav column
 * carries an 8dp END padding so a hovered row's wash stops short of the
 * divider (the wash otherwise reads as bleeding into the detail panel —
 * the user's "no visual padding between master and detail"), and the
 * detail panel carries a 16dp START padding so its header never sits
 * flush against the hairline. The 1dp hairline IS the divider per the
 * §6.1.7 ruling — quiet by design, never a heavier border.
 */
@Composable
private fun SettingsTwoPane(
    viewModel: AppViewModel,
    activeSection: SettingsSection,
    panelScrollState: androidx.compose.foundation.ScrollState,
    onSectionSelected: (SettingsSection) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        SectionNavColumn(
            activeSectionId = activeSection.id,
            onSectionSelected = onSectionSelected,
            modifier = Modifier
                .width(200.dp)
                .fillMaxHeight()
                // The wash's hard stop: rows fill this column, so the
                // column's end padding keeps the hover wash 8dp clear of
                // the divider (task 11.1).
                .padding(end = Dimens.sm),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                // Theme-aware hairline tone (task 12.1): quiet but VISIBLE
                // in light mode — the old outline-at-8% was imperceptible
                // against the light background.
                .background(LocalBankTellerColors.current.hairline),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // The detail panel's breathing room (task 11.1): the
                // section header starts 16dp clear of the divider — never
                // flush against it.
                .padding(start = Dimens.md),
        ) {
            activeSection.content(viewModel, panelScrollState)
        }
    }
}

/**
 * Compact (≤768dp) drill root (D4): one tappable row per section. With
 * exactly one section the list is short; the pattern holds for future
 * sections — the list attaches to the same panel scroll state so the
 * shell's top-bar divider keys on it (§6.1.4). The [LedgerScrollbar]
 * overlay carries the scrollbar affordance (the Settings screen runs the
 * shell in external-divider mode — `scrollable = false` — so the shell's
 * own scrollbar never renders; the panel owns its affordance, the same
 * wrapper pattern as ScreenShell/WizardScaffold).
 *
 * Navigation-row affordance (task 11.5, §6.1.7): full-row wash at ROW
 * scale + label darkening — the same vocabulary as the expanded nav
 * column. The trailing chevron stays PERSISTENT here (unlike the
 * expanded pane): the drill target is not visible beside the list, so
 * the chevron carries the "there is more" affordance. No leading-edge
 * active indicator — the compact list is pre-selection by definition
 * (selecting drills in); the active row exists only in the expanded
 * two-pane mode.
 */
@Composable
private fun SettingsSectionList(
    panelScrollState: androidx.compose.foundation.ScrollState,
    onSectionSelected: (SettingsSection) -> Unit,
) {
    // EXEMPT from CropSignaledScroll's BOTTOM hairline only (task 11.14's
    // D19 exemption, amended by the 12.1 re-run finding): the shell's
    // bottom-bar crop hairline already signals this panel's bottom edge
    // from this same scroll state (canScrollForward) — a wrapper bottom
    // hairline would render coincident, same-tone, same-trigger: pure
    // redundancy (the never-twice rule). The TOP edge is NOT shell-
    // signaled in compact mode (the shell's external divider is null
    // there — the drill's static back button sits between the zone
    // boundary and the scrollable, the 12.1 finding), so the list
    // wraps in the wrapper: its top hairline renders at the scrollable's
    // own bounds when the list scrolls (future sections), and its
    // scrollable's top IS the zone boundary here (no static chrome above
    // it — geometrically correct). The scrollbar still serves the panel
    // (its own affordance); the BOTTOM hairline alone is the shell's.
    CropSignaledScroll(
        scrollState = panelScrollState,
        showBottomHairline = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        LedgerScrollbar(
            scrollState = panelScrollState,
            modifier = Modifier.matchParentSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(panelScrollState),
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.screenHeading,
                )
                Spacer(modifier = Modifier.height(Dimens.md))
                settingsSections.forEach { section ->
                    val interactionSource = remember { MutableInteractionSource() }
                // Label darkening on hover (task 11.5): onSurfaceVariant →
                // onSurface — the compact drill rows are navigation
                // destinations, the same vocabulary as the expanded nav
                // column.
                var hovered by remember { mutableStateOf(false) }
                LaunchedEffect(interactionSource) {
                    interactionSource.interactions.collect { interaction ->
                        when (interaction) {
                            is HoverInteraction.Enter -> hovered = true
                            is HoverInteraction.Exit -> hovered = false
                            else -> Unit
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = interactionSource, indication = null) {
                            onSectionSelected(section)
                        }
                        .denseWash(interactionSource, scale = WashScale.Row)
                        // Compact drill row: navigation destination whose own
                        // text supplies the name (WCAG 4.1.2).
                        .navRowSemantics()
                        .padding(vertical = Dimens.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (hovered) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    // Drill affordance (glyph-coverage fix): a 14dp
                    // `Icons.Outlined.ChevronRight` — a Material icon,
                    // rendered from the icon font (a literal "›" text
                    // glyph depends on the bundled text-font subset;
                    // icons never do). Matches the expander's chevron
                    // size convention (§6.1.1). PERSISTENT on compact
                    // rows (task 11.5): the drill target is not visible
                    // beside the list, so the chevron carries the
                    // affordance — unlike the expanded pane, where the
                    // detail panel itself answers.
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        }
    }
}

/**
 * Compact drill panel (D4): the inline back affordance above the section
 * CONTENT — the panel renders NO title of its own: the section's
 * standard header row ("Bank connections" titleLarge + "+ Connect bank")
 * is the panel's single title in BOTH layout modes (the no-duplication
 * ruling, task 11.15 — the former drill-local headlineMedium title
 * duplicated it; the expanded panel never had one).
 *
 * Back-affordance construction (glyph-coverage fix): a 14dp
 * `Icons.AutoMirrored.Outlined.ArrowBack` leading icon + the "Settings"
 * label at the Inline Text XS tier (§6.1.1: 28dp dense opt-out,
 * `labelSmall`, trimmed padding, shared dense wash) — the direction glyph
 * is a Material icon, rendered from the icon font and immune to the
 * bundled text-font subsets (the Inter/Fraunces/mono woff2 subsets carry
 * no arrow codepoints — a literal "←" rendered TOFU). The icon is
 * AUTO-MIRRORED so RTL locales read it correctly. Navigation-only: no
 * busy gate, always enabled.
 */
@Composable
private fun SettingsPanel(
    viewModel: AppViewModel,
    section: SettingsSection,
    panelScrollState: androidx.compose.foundation.ScrollState,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        val interactionSource = remember { MutableInteractionSource() }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { onBack() }
                    .denseWash(interactionSource)
                    // Compact back affordance: name says where it goes, beyond
                    // the visible "Settings" label (WCAG 4.1.2).
                    .backAffordanceSemantics("Back to Settings sections")
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(Dimens.xs))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // Back affordance → sm spacing → section content (task 11.15: the
        // drill-local title Text + its md Spacer are DELETED — the
        // section's own header row is the single title in both layout
        // modes).
        Spacer(modifier = Modifier.height(Dimens.sm))
        // CropSignaledScroll wrap (12.1 re-run finding): the shell's top
        // divider is DISABLED in compact mode (see the shell wiring
        // above) because the static back button sits between the zone
        // boundary and the scrollable's true top — the drill's TOP crop
        // signal renders HERE, at the scrollable's OWN bounds (below the
        // back button, never over it; the 11.16 2dp detachment inset
        // applies automatically). The D19 exemption covers the EXPANDED
        // mode only; compact uses the wrapper. The BOTTOM hairline is
        // suppressed (showBottomHairline = false): the scrollable's
        // bottom edge IS the zone's bottom boundary here, and the
        // shell-owned bottom-bar crop hairline (keyed on this same
        // scroll's canScrollForward) is that edge's single signal — the
        // never-twice rule.
        CropSignaledScroll(
            scrollState = panelScrollState,
            showBottomHairline = false,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            section.content(viewModel, panelScrollState)
        }
    }
}

/**
 * Expanded section-navigation column (D4): the fixed anchors — one row
 * per section. Mirrors the pinned-footer principle: stable spatial
 * structure, no layout-shifting navigation.
 *
 * Navigation-row affordance (task 11.5, DESIGN-LANGUAGE §6.1.7): rows
 * are navigation destinations, not static text — full-row quiet wash on
 * hover/press at ROW scale (quieter than the 28dp control tier;
 * perceived intensity scales with area), with the unselected label
 * darkening onSurfaceVariant → onSurface. The ACTIVE row carries the
 * tab bar's active-indicator vocabulary applied VERTICALLY: a 2dp
 * brass hairline on the row's LEADING edge + a `primary` label — never
 * at the trailing divider edge (ambiguous between row and detail-pane
 * border) and never a filled pill or elevation shadow (nav rows are not
 * cards; §8.3's lift stays card-only). NO persistent chevron/notch on
 * unselected rows — redundant beside the visible detail panel.
 */
@Composable
private fun SectionNavColumn(
    activeSectionId: String,
    onSectionSelected: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val brass = LocalBankTellerColors.current.brass
    Column(modifier = modifier.padding(top = Dimens.xs)) {
        settingsSections.forEach { section ->
            val selected = section.id == activeSectionId
            val interactionSource = remember { MutableInteractionSource() }
            // Label darkening on hover: onSurfaceVariant → onSurface
            // (the row itself carries the wash; the label change reads at
            // a glance with the #2E4A7A primary distinguishing the active
            // row in both themes).
            var hovered by remember { mutableStateOf(false) }
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is HoverInteraction.Enter -> hovered = true
                        is HoverInteraction.Exit -> hovered = false
                        else -> Unit
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = interactionSource, indication = null) {
                        onSectionSelected(section)
                    }
                    .denseWash(interactionSource, scale = WashScale.Row)
                    // Expanded nav row: button role + current selection
                    // (WCAG 4.1.2).
                    .navRowSemantics(selected)
                    .padding(vertical = Dimens.sm, horizontal = Dimens.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ACTIVE row: the 2dp brass hairline on the LEADING edge —
                // the tab bar's active-indicator vocabulary, applied
                // vertically. Always laid out (transparent when inactive):
                // zero layout shift on selection changes.
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(20.dp)
                        .background(
                            if (selected) brass else brass.copy(alpha = 0f),
                        ),
                )
                Spacer(modifier = Modifier.width(Dimens.xs))
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        selected -> MaterialTheme.colorScheme.primary
                        hovered -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// ===============================================================
// Bank connections section (D5, spec: bank-connection-management)
// ===============================================================

/**
 * The Settings screen's bank connections section (D5): the section
 * header with the canonical "+ Connect bank" action (top-right), the
 * honest remove caveat / settings error lines, then one connection card
 * per connected bank — including zero-account banks (their info-box
 * card) and banks whose accounts are all kept-as-local-copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BankConnectionsSection(
    viewModel: AppViewModel,
    panelScrollState: androidx.compose.foundation.ScrollState,
) {
    val data = viewModel.accountsData

    // The LedgerScrollbar overlay carries the panel's scrollbar affordance
    // (the Settings screen runs the shell in external-divider mode —
    // `scrollable = false` — so the shell's own scrollbar never renders).
    // ONE wrap covers BOTH layout modes: the expanded two-pane detail panel
    // (SettingsTwoPane → activeSection.content) and the compact drill panel
    // (SettingsPanel → section.content) compose this same section content.
    // The wrapper Box fills the panel; the scrollable Column inside drives
    // the SAME scroll state the shell's divider hook reads — the scrollbar
    // renders at the wrapper's trailing edge.
    // HAIRLINE OWNERSHIP (D19 exemption, amended by the 12.1 re-run
    // finding — EXPANDED-ONLY): in the EXPANDED two-pane this panel's
    // crop edges are the shell's boundaries — the external-divider mode
    // signals the top edge (top-bar divider keys on this scroll's
    // offset) and the bottom-bar crop hairline keys on its
    // canScrollForward — a wrapper here would render coincident,
    // same-tone, same-trigger hairlines: pure redundancy. In the
    // COMPACT drill the shell's top divider is DISABLED (the static back
    // button sits between the zone boundary and the scrollable's true
    // top) and the drill's CropSignaledScroll wrap (in SettingsPanel)
    // owns the top signal at the scrollable's own bounds. This section
    // content itself renders NO hairlines in either mode — the scrollbar
    // is its only overlay.
    LedgerScrollbar(
        scrollState = panelScrollState,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(panelScrollState),
        ) {
        // Section header: title + the canonical connect action (D5/5.4).
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Bank connections",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.screenHeading,
            )
            // "+ Connect bank" (D5/5.4): the canonical label per the
            // spec/design naming — the "+" prefix distinguishes the
            // additive action from the dashboard's first-run "Connect a
            // bank" empty-state CTA at a glance.
            ActionButton(
                onClick = { viewModel.startAddBankFlow() },
                label = "+ Connect bank",
                modifier = Modifier.padding(start = Dimens.sm),
            )
        }
        Spacer(modifier = Modifier.height(Dimens.sm))

        // Honest report after a completed removal whose EB delete failed
        // (D7): a DISMISSIBLE brass warning panel (task 12.4 finding —
        // plain text failed to draw attention; the DecisionBox Tier-3
        // pattern with a dismiss affordance, the AuthFailureNotice
        // precedent). Brass attention tier is semantically right: removed
        // locally, but the bank-side consent may live on — attention, not
        // an error. Rendered until dismissed (clearRemoveCaveat).
        viewModel.removeCaveat?.let { caveat ->
            val brass = LocalBankTellerColors.current.brass
            val brassText = LocalBankTellerColors.current.brassText
            // Two-state brass: gold wash, bronze sideline (accessibility D2).
            DecisionBox(tint = brass, borderColor = brassText, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        // Two-state brass on the wash mount: the glyph reads
                        // brassText (bronze) — the gold glyph on its own wash
                        // over sc measured 2.9969:1, under the 3:1 non-text
                        // threshold (the D2 sideline precedent, applied to
                        // the glyph carrier).
                        tint = brassText,
                        modifier = Modifier.size(18.dp),
                    )
                    StatusText(
                        text = caveat,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    // Dismiss affordance: the §6.1.1 icon-action tier —
                    // 28dp footprint, indication-free, tooltip names the
                    // action (the §6.1.2 utility-control convention).
                    val dismissInteraction = remember { MutableInteractionSource() }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                        tooltip = { PlainTooltip { Text("Dismiss") } },
                        state = rememberTooltipState(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(28.dp)
                                .clickable(
                                    interactionSource = dismissInteraction,
                                    indication = null,
                                ) { viewModel.clearRemoveCaveat() }
                                // Icon-only action (WCAG 1.1.1 / 4.1.2).
                                .semanticIconAction("Dismiss")
                                .padding(6.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(Dimens.sm))
        }
        // Honest settings-surface error line (delete failures, etc.).
        viewModel.settingsError?.let { error ->
            StatusText(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(Dimens.sm))
        }

        when {
            data == null -> {
                // First fetch in flight — quiet zone wait, no fake state.
                WaitingIndicator.Zone()
            }
            data.banks.isEmpty() -> {
                // Zero connections: the section-level empty copy. The
                // canonical "+ Connect bank" action above is the single
                // call to action — no card renders.
                Text(
                    text = "No banks connected yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Column(
                    modifier = Modifier.collectionInfo(data.banks.size),
                    verticalArrangement = Arrangement.spacedBy(Dimens.md),
                ) {
                    data.banks.forEach { bank ->
                        ConnectionCard(viewModel, bank)
                    }
                }
            }
        }
        } // LedgerScrollbar wrapper
    }

    // The remove-confirmation dialog (D7), the local-copy deletion
    // confirmation (task 11.9), and the missing-accounts dialog (D14)
    // render as overlays above whichever surface is current — the
    // Settings screen hosts them because both flows originate here; the
    // missing-accounts signal can also arrive while the dashboard is the
    // current surface (post-import), so the DASHBOARD renders that one.
    RemoveConnectionDialog(viewModel)
    DeleteLocalAccountDialog(viewModel)
}

// ===============================================================
// Connection card (D5) + consent-health badge + local-copy rows
// ===============================================================

/**
 * Consent-health badge derivation for the connection card (D5), pure so
 * the tiers are unit-testable. Reuses the dashboard's consent-health
 * logic (CONSENT_WARNING_DAYS threshold, consent-broken override):
 * - [ConsentBadgeHealth.Dead] — consent-broken flag, or a parseable
 *   `valid_until` in the past (danger);
 * - [ConsentBadgeHealth.Expiring] — a live consent within the 7-day
 *   urgency threshold (brass);
 * - [ConsentBadgeHealth.Active] — a live consent beyond the threshold
 *   (quiet), carrying the countdown days.
 * - null expiry that is not consent-broken degrades to the quiet Active
 *   badge without a day count (never a fabricated countdown).
 */
internal sealed class ConsentBadgeHealth {
    data class Active(val days: Long?) : ConsentBadgeHealth()

    /** Days until expiry (0 = expires today) — the count is the actionable part (task 6.7). */
    data class Expiring(val days: Long) : ConsentBadgeHealth()
    data object Dead : ConsentBadgeHealth()
}

internal fun consentBadgeHealth(validUntil: String?, consentBroken: Boolean): ConsentBadgeHealth {
    if (consentBroken) return ConsentBadgeHealth.Dead
    val days = daysUntil(validUntil) ?: return ConsentBadgeHealth.Active(null)
    if (days < 0) return ConsentBadgeHealth.Dead
    if (days <= CONSENT_WARNING_DAYS) return ConsentBadgeHealth.Expiring(days)
    return ConsentBadgeHealth.Active(days)
}

/** Badge copy for the [consentBadgeHealth] tiers — pure, unit-testable. */
internal fun consentBadgeText(health: ConsentBadgeHealth): String = when (health) {
    is ConsentBadgeHealth.Active -> when (val d = health.days) {
        null -> "Consent active"
        else -> "Consent active · Valid for $d day${if (d == 1L) "" else "s"}"
    }
    is ConsentBadgeHealth.Expiring -> "Consent expiring · ${health.days} day${if (health.days == 1L) "" else "s"} left"
    ConsentBadgeHealth.Dead -> "Consent ended · Re-authorization required"
}

/**
 * Connection card (D5, spec: bank-connection-management — bank
 * connections section): one OutlinedCard per bank. Header zone (40dp
 * logo, bank name titleMedium, "country · PSU type" subtitle,
 * consent-health badge right), BIC + add-date lines (both degrade
 * gracefully when null), the informational read-only accounts list
 * ("ACCOUNTS (N)", capped at 4 visible rows + expander), and the footer
 * action strip (last-synced left; "Refresh authorization" quiet action +
 * "Remove" danger text button right). A zero-account bank renders the
 * info box with the actions still available; local-copy accounts render
 * tagged with the inline extra-small danger delete affordance.
 *
 * Tiered scan signal (localized-sync-errors 5.1, DESIGN-LANGUAGE §6.2.2):
 * the same border+tint mechanism as dashboard account cards at connection
 * granularity — danger for consent-dead/expired, brass for degraded-but-live,
 * healthy unmarked — with ~200ms color transitions. The signal is the ONLY
 * degradation expression here: never per-account status, counts, or rollups.
 */
@Composable
private fun ConnectionCard(viewModel: AppViewModel, bank: BankData) {
    val tier = connectionDegradationTier(bank)

    // Connection-granularity scan signal (task 5.1): theme tokens only.
    val tierColor = when (tier) {
        ConnectionDegradationTier.Danger -> MaterialTheme.colorScheme.error
        ConnectionDegradationTier.Brass -> LocalBankTellerColors.current.brass
        ConnectionDegradationTier.Healthy -> null
    }
    val tintAlpha = when (tier) {
        ConnectionDegradationTier.Danger -> 0.06f
        ConnectionDegradationTier.Brass -> 0.04f
        ConnectionDegradationTier.Healthy -> 0f
    }
    val outline = MaterialTheme.colorScheme.outline
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val borderColor by animateColorAsState(
        targetValue = tierColor ?: outline,
        animationSpec = tween(durationMillis = 200),
        label = "connection-card-status-border",
    )
    val containerColor by animateColorAsState(
        targetValue = tierColor?.copy(alpha = tintAlpha)?.compositeOver(surfaceContainer) ?: surfaceContainer,
        animationSpec = tween(durationMillis = 200),
        label = "connection-card-status-tint",
    )

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.outlinedCardColors(
            containerColor = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.md),
            verticalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            ConnectionHeader(bank)
            ConnectionMetadataLines(bank)
            if (bank.accounts.isEmpty()) {
                // Zero-account bank (D5): the info box — the Tier-4
                // nested-card convention (shape medium, 1dp outline border,
                // surfaceContainer), the same treatment as the other
                // Tier-4 sites; the actions below stay available — the
                // connection itself is healthy.
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Text(
                        text = "No accounts shared under this authorization",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Dimens.md),
                    )
                }
            } else {
                ConnectionAccountList(viewModel, bank)
            }
            ConnectionFooter(viewModel, bank, tier)
        }
    }
}

@Composable
private fun ConnectionHeader(bank: BankData) {
    // TOP-anchored (wcag-color-conformance manual-verification 6.1 finding, the
    // long-name overflow case): the badge pins to the header's TOP RIGHT no
    // matter how many lines the bank name wraps to — a vertically CENTERed
    // row would sink the badge toward the middle of a tall wrapped name
    // block. Top alignment anchors logo, text block, and badge to one top
    // edge: the badge reads as belonging to the header's top-right corner
    // for every bank, short-named or wrapped.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Dimens.md),
    ) {
        BankLogo(
            bankName = bank.bankName,
            logoUrl = bank.logo,
            bic = bank.bic,
            size = 40.dp,
            clipLogo = false,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bank.bankName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = connectionSubtitle(bank.bankCountry, bank.psuType),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Consent-health badge (D5, wcag-color-conformance 3.1–3.2): quiet
        // plain text when healthy, a 12% brass pill with a leading warning
        // glyph when expiring, the same pill in danger when dead — see
        // [ConnectionConsentBadge] for the two-state brass and fixed-
        // footprint rules. The row's TOP alignment (above) keeps the badge
        // pinned to the header's top-right under name wrap.
        val health = consentBadgeHealth(bank.validUntil, bank.consentBroken)
        ConnectionConsentBadge(health)
    }
}

/**
 * The badge's fixed row height. The expiring/dead pill (labelSmall +
 * 2dp vertical padding) naturally measures 20.dp; the healthy plain text
 * is shorter. The badge must never inflate the card header row or shift
 * layout as a bank moves between consent states (wcag-color-conformance
 * task 3.2), so [ConnectionConsentBadge] pins both renderings to this
 * single bounding height.
 */
private val ConsentBadgeHeight = 20.dp

/**
 * Consent-health badge (D5; wcag-color-conformance 3.1–3.2): the row-
 * trailing signal for a bank's authorization state.
 *
 * - Healthy renders as quiet plain TEXT (no pill, no glyph) — the calm
 *   default, onSurfaceVariant.
 * - Expiring renders as a 12% brass wash pill with a leading outlined
 *   [Icons.Outlined.WarningAmber] glyph and "Consent expiring · N days
 *   left" in brassText.
 * - Dead/expired renders the same pill treatment with an outlined
 *   [Icons.Outlined.ErrorOutline] glyph and "Consent ended · Re-
 *   authorization required" in danger.
 *
 * The warning states read through glyph shape and container geometry, not
 * hue alone — the semantic-color-is-never-the-only-carrier guarantee. The
 * earlier text-only badge relied on a brass hue that both failed 4.5:1 and
 * was indistinguishable from regular text (user + designer ruling, spec
 * scenario "Expiring consent badge warns without hue alone").
 *
 * Fixed-footprint rule (task 3.2): both renderings live inside the same
 * fixed-height box ([ConsentBadgeHeight], the pill's natural height) — the
 * healthy plain text and the taller pill+glyph occupy identical row height,
 * so the card header never inflates or shifts between states.
 */
@Composable
private fun ConnectionConsentBadge(health: ConsentBadgeHealth) {
    Box(
        modifier = Modifier.height(ConsentBadgeHeight),
        contentAlignment = Alignment.CenterEnd,
    ) {
        when (health) {
            is ConsentBadgeHealth.Active -> Text(
                text = consentBadgeText(health),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is ConsentBadgeHealth.Expiring -> ConnectionConsentBadgePill(
                icon = Icons.Outlined.WarningAmber,
                label = consentBadgeText(health),
                // Two-state brass (design D3): glyph + label are TEXT
                // carriers → bronze; the pill tint stays ornament gold.
                textColor = LocalBankTellerColors.current.brassText,
                tint = LocalBankTellerColors.current.brass,
            )
            ConsentBadgeHealth.Dead -> ConnectionConsentBadgePill(
                icon = Icons.Outlined.ErrorOutline,
                label = consentBadgeText(health),
                textColor = MaterialTheme.colorScheme.error,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The pill variant of the consent badge — the shared SANDBOX-chip /
 * BankStatusBadge treatment (shapes.small, 12% wash, glyph + labelSmall
 * label). The glyph and label share the semantic state color
 * (brassText / danger); the wash stays at 12% of the ornament/danger
 * token. The glyph is decorative (`contentDescription = null`): the badge
 * text itself carries the semantics (aria work is out of scope here).
 */
@Composable
private fun ConnectionConsentBadgePill(
    icon: ImageVector,
    label: String,
    textColor: Color,
    tint: Color,
) {
    // OPAQUE pill (D3 pill-opacity ruling, wcag-color-conformance 3.3):
    // the background is the token at 12% composited over PLAIN
    // surfaceContainer — a solid color, never a translucent wash. The
    // badge renders inside tier-tinted connection cards; a translucent
    // pill would compound with the card's tier tint and drop the dead
    // badge's danger text to ≈4.09 (dark) / ≈4.16 (light). The opaque
    // pill is invariant under the card's tint by construction.
    val pillBackground = tint.copy(alpha = 0.12f).compositeOver(
        MaterialTheme.colorScheme.surfaceContainer,
    )
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(pillBackground)
            .padding(horizontal = Dimens.xs, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}

/**
 * "country · PSU type" subtitle (D5), pure so the degradation is
 * unit-testable: both parts omit cleanly (§6.2.1) — a null PSU type
 * renders the country alone, and a blank country renders the PSU type
 * alone; neither is ever a placeholder.
 */
internal fun connectionSubtitle(country: String, psuType: String?): String {
    val type = when (psuType?.lowercase()) {
        "personal" -> "personal"
        "business" -> "business"
        null, "" -> null
        else -> psuType
    }
    return when {
        country.isBlank() && type == null -> ""
        country.isBlank() -> type!!
        type == null -> country
        else -> "$country · $type"
    }
}

/** BIC + add-date lines (D5): both degrade gracefully when null — no placeholder, no error. */
@Composable
private fun ConnectionMetadataLines(bank: BankData) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        bank.bic?.takeIf { it.isNotBlank() }?.let { bic ->
            Text(
                text = "BIC: $bic",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        bank.connectedAt?.let { connectedAt ->
            Text(
                text = "Connected ${connectionAddedLine(connectedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Add-date copy (D5): the connection's add date rendered as a calendar
 * date via the platform's locale-aware formatter. The degradation (null
 * on unrepresentable timestamps → the caller omits the line) is
 * unit-testable.
 */
internal fun connectionAddedLine(connectedAt: Long): String? =
    formatDateForLocale(connectedAt, browserLocaleTag())

/** The number of account rows visible before the expander reveals the rest (D5). */
internal const val CONNECTION_CARD_VISIBLE_ROWS = 4

/**
 * Read-only accounts list (D5): "ACCOUNTS (N)" label, per row account
 * name, masked IBAN in mono, trailing balance in mono tabular figures,
 * capped at [CONNECTION_CARD_VISIBLE_ROWS] visible rows with a
 * "Show N more accounts" expander revealing the rest in place. Rows for
 * kept-as-local-copy accounts (in_consent=0) carry the "Local copy" tag,
 * the stale balance in onSurfaceVariant, and the inline extra-small
 * danger delete affordance.
 */
@Composable
private fun ConnectionAccountList(viewModel: AppViewModel, bank: BankData) {
    var expanded by remember { mutableStateOf(false) }
    val visibleCount = if (expanded) bank.accounts.size else minOf(bank.accounts.size, CONNECTION_CARD_VISIBLE_ROWS)
    val hiddenCount = bank.accounts.size - visibleCount

    Column(
        modifier = Modifier.collectionInfo(bank.accounts.size),
        verticalArrangement = Arrangement.spacedBy(Dimens.xs),
    ) {
        Text(
            text = "ACCOUNTS (${bank.accounts.size})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        bank.accounts.take(visibleCount).forEach { account ->
            ConnectionAccountRow(viewModel, account)
        }
        if (!expanded && hiddenCount > 0) {
            // Expander affordance (task 11.2): a persistent NON-COLOR
            // affordance — the trailing 14dp ExpandMore chevron — plus
            // underline-on-hover and the shared dense wash; the light
            // theme's ink-navy primary text alone reads as body text, so
            // interactivity never depends on the color shift. The primary
            // is NEVER brightened (11.3's palette ruling — the guarantee
            // lives in the token, and the chevron/underline/wash are the
            // affordances).
            val interactionSource = remember { MutableInteractionSource() }
            var hovered by remember { mutableStateOf(false) }
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is HoverInteraction.Enter -> hovered = true
                        is HoverInteraction.Exit -> hovered = false
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                modifier = Modifier
                    .padding(top = Dimens.xs)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(interactionSource = interactionSource, indication = null) {
                        expanded = true
                    }
                    .denseWash(interactionSource),
            ) {
                Text(
                    text = "Show $hiddenCount more account${if (hiddenCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        textDecoration = if (hovered) TextDecoration.Underline else null,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectionAccountRow(viewModel: AppViewModel, account: AccountData) {
    val isLocalCopy = account.inConsent == 0L
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isLocalCopy) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            account.iban?.let { iban ->
                Text(
                    text = maskIban(iban),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = jetBrainsMonoFamily()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isLocalCopy) {
                Text(
                    text = "Local copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            if (account.balanceAmount != null) {
                Text(
                    text = formatAmount(account.balanceAmount, account.balanceCurrency ?: account.currency),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = jetBrainsMonoFamily(),
                        fontFeatureSettings = "tnum",
                    ),
                    // Local copies' frozen figures render in the stale
                    // convention (onSurfaceVariant) — never presented as
                    // currently synchronized.
                    color = if (isLocalCopy) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (isLocalCopy) {
                // Inline extra-small danger delete (D5/D9): OPENS the
                // confirmation dialog (task 11.9's ruling — one-click
                // deletion never happens). The only per-account deletion
                // path in the system, gated by its confirm action.
                InlineDangerDelete(
                    enabled = viewModel.deletingLocalAccountId == null,
                    busy = viewModel.deletingLocalAccountId == account.id,
                    onClick = { viewModel.requestDeleteLocalAccount(account) },
                )
            }
        }
    }
}

// ===============================================================
// Remove-confirmation dialog (D7) + automation guard (D10)
// ===============================================================

/**
 * Remove-confirmation dialog (D7, spec: bank-connection-management —
 * remove connection): honest best-effort copy — the connection closes;
 * the bank-side consent is closed "where supported", never promised.
 * Both actions ship enabled (D10 automation guard: no automations exist
 * yet; the guard principle is documented at [guardedByAutomation]).
 */
@Composable
private fun RemoveConnectionDialog(viewModel: AppViewModel) {
    val target = viewModel.removeDialogBank ?: return
    val busy = viewModel.removingBankName != null

    AlertDialog(
        onDismissRequest = { if (!busy) viewModel.dismissRemoveDialog() },
        title = { Text("Remove ${target.first}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                Text(
                    text = removeDialogBody(target.first),
                )
                Text(
                    text = "The bank-side consent is closed where supported — banks differ in how they handle removed connections.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                viewModel.removeError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            // Both actions ship ENABLED (D10): no automations exist yet.
            // Danger tint via the button-colors API (task 12.4 finding):
            // a hardcoded label color would override M3's disabled content
            // tint — the label must visibly dim while the request is in
            // flight (enabled = !busy), matching the Cancel side.
            TextButton(
                onClick = { viewModel.confirmRemoveConnection() },
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { viewModel.dismissRemoveDialog() },
                enabled = !busy,
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Honest best-effort remove copy (D7), pure so the wording is
 * unit-testable: the connection closes and everything derived from it
 * disappears from BankTeller — never a promise that the bank side
 * closed the consent.
 */
internal fun removeDialogBody(bankName: String): String =
    "The connection to $bankName closes in BankTeller. Its accounts and stored balances are removed from the app."

// ===============================================================
// Local-copy deletion confirmation (task 11.9 — one-click deletion
// never happens)
// ===============================================================

/**
 * The local-copy deletion dialog's copy (task 11.9), pure so the
 * identity fallback is unit-testable: quiet honest copy NAMING the
 * account — its display name and masked IBAN, each part omitted cleanly
 * per §6.2.1 when absent (nameless accounts degrade to the masked IBAN;
 * IBAN-less to the name; neither to the account row id — never a
 * fabricated label).
 */
internal fun deleteLocalAccountDialogBody(name: String?, iban: String?): String {
    val trimmed = name?.trim().orEmpty()
    val maskedIban = iban?.takeIf { it.replace(" ", "").isNotEmpty() }?.let(::maskIban)
    // Honest irreversibility copy (task 11.9): names what is deleted AND
    // states it cannot be undone — the dialog is the only gate before
    // irreversible data loss, so the consequence must be read before the
    // confirm click, never discovered after.
    return when {
        trimmed.isNotEmpty() && maskedIban != null ->
            "The account $trimmed ($maskedIban) and its stored balances are removed from BankTeller. This cannot be undone."
        trimmed.isNotEmpty() ->
            "The account $trimmed and its stored balances are removed from BankTeller. This cannot be undone."
        maskedIban != null ->
            "The account $maskedIban and its stored balances are removed from BankTeller. This cannot be undone."
        else ->
            "This account and its stored balances are removed from BankTeller. This cannot be undone."
    }
}

/**
 * Local-copy deletion confirmation dialog (task 11.9 ruling: the inline
 * delete affordance OPENS this dialog — one-click deletion never
 * happens). The remove-confirmation dialog's grammar (§6.1.8.4's dialog
 * family): quiet copy naming the account (display name + masked IBAN),
 * danger "Delete" confirm / "Cancel" dismiss. Every button disables
 * while the deletion request is in flight (the uniform dialog busy rule, DESIGN-LANGUAGE §6.1.8.4);
 * onDismissRequest stays busy-gated. A failed deletion keeps the dialog
 * open (honest error in the section, retryable).
 */
@Composable
private fun DeleteLocalAccountDialog(viewModel: AppViewModel) {
    val account = viewModel.deleteDialogAccount ?: return
    val busy = viewModel.deletingLocalAccountId != null

    AlertDialog(
        onDismissRequest = { if (!busy) viewModel.dismissDeleteDialog() },
        title = { Text("Delete local copy?") },
        text = {
            Text(
                text = deleteLocalAccountDialogBody(account.name, account.iban),
            )
        },
        confirmButton = {
            // Danger tint via the button-colors API (task 12.4): the
            // disabled state applies M3's disabled content alpha — the
            // label visibly dims while the deletion is in flight,
            // matching Cancel.
            TextButton(
                onClick = { viewModel.confirmDeleteLocalAccount() },
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { viewModel.dismissDeleteDialog() },
                enabled = !busy,
            ) {
                Text("Cancel")
            }
        },
    )
}

// ===============================================================
// Missing-accounts dialog (D14) — rendered on the DASHBOARD (post-import)
// ===============================================================

/**
 * One enumerated row of the missing-accounts dialog (task 6.4): the user
 * decides over these SPECIFIC records, never an opaque count. Built by
 * [missingAccountsDialogRows] — pure so the row assembly is
 * unit-testable.
 */
internal data class MissingAccountRow(
    /** Local account row id — the deletion handle. */
    val id: Long,
    /** Display name (the signal's account name); null when the server sent none. */
    val name: String?,
    /** Masked IBAN; null when the signal carried none. */
    val maskedIban: String?,
    /**
     * Last-known balance, joined from the current accounts payload by id
     * (the kept accounts' balances rows survive the merge). Null when no
     * balance row exists — the balance line omits cleanly (§6.2.1).
     */
    val balance: String?,
    /** True = went missing with this authorization; false = already kept. */
    val newlyMissing: Boolean,
)

/**
 * Per-row marker copy (task 6.4), pure so the partition is unit-testable:
 * the quiet labelSmall tag distinguishing newly missing accounts from
 * accounts already kept as a local copy — a repeated detection lists
 * both, and the user is never left wondering which data is new.
 */
internal fun missingAccountRowMarker(newlyMissing: Boolean): String =
    if (newlyMissing) "went missing with this authorization" else "already kept as a local copy"

/**
 * Builds the dialog's enumerated row models (task 6.4), pure so the join
 * is unit-testable: each signal entry maps to a [MissingAccountRow],
 * with the last-known balance JOINED from the current accounts payload
 * by id (the kept accounts' balances rows survive the merge; a local
 * copy is outside the consent filter but its frozen figure is exactly
 * what "their data" means). No matching account row → no balance (the
 * balance line omits cleanly, §6.2.1 — no placeholder, no error).
 */
internal fun missingAccountsDialogRows(
    missing: List<MissingAccount>,
    currentAccounts: List<AccountData>,
): List<MissingAccountRow> = missing.map { entry ->
    val joined = currentAccounts.firstOrNull { it.id == entry.id }
    MissingAccountRow(
        id = entry.id,
        name = entry.name,
        maskedIban = entry.iban?.let(::maskIban),
        balance = joined?.balanceAmount?.let { amount ->
            formatAmount(amount, joined.balanceCurrency ?: joined.currency)
        },
        newlyMissing = entry.newlyMissing,
    )
}

/**
 * Missing-accounts dialog summary line (D8/D14, task 6.4), pure so the
 * partition is unit-testable: the lead-in above the enumerated rows —
 * the bank of the re-authorization (never an account holder's name) and
 * the newly-missing / already-kept counts.
 *
 * [bankName] non-null renders "Your new account selection at <bankName>
 * kept data BankTeller no longer has access to."; null degrades honestly
 * by dropping the "at X" clause entirely — never an account name, never
 * a fabricated label. The specific records are enumerated in the rows
 * ([missingAccountsDialogRows]); this line carries the summary, not the
 * identities.
 */
internal fun missingAccountsDialogBody(bankName: String?, missing: List<MissingAccount>): String {
    val newly = missing.count { it.newlyMissing }
    val kept = missing.count { !it.newlyMissing }
    return buildString {
        append(
            if (bankName != null) {
                "Your new account selection at $bankName kept data BankTeller no longer has access to. "
            } else {
                "Your new account selection kept data BankTeller no longer has access to. "
            },
        )
        append("These accounts are kept as local copies — their last-known balances stay visible, but they no longer update.")
        if (newly > 0) {
            append(" ")
            append("$newly account${if (newly == 1) "" else "s"} went missing with this authorization.")
        }
        if (kept > 0) {
            append(" ")
            append("$kept account${if (kept == 1) " was" else "s were"} already kept as a local copy from an earlier authorization.")
        }
    }
}

/**
 * All-kept explanatory line (13.5, review F3 — user-ruled): rendered in
 * the dialog body when the signal carries ONLY already-kept entries
 * (zero newlyMissing) — the state explanation for the DISABLED danger
 * action, quiet (labelSmall/bodySmall onSurfaceVariant at the call
 * site — a state explanation, not a warning). Exact spec wording.
 */
internal const val ALL_KEPT_EXPLANATORY_LINE =
    "No new accounts went missing with this authorization — the listed accounts were already kept as local copies."

/**
 * No-op guard predicate (13.5, review F3): TRUE when the signal carries
 * at least one newly-missing entry — the danger action's enabled
 * condition. An all-kept signal (zero newlyMissing) renders the action
 * DISABLED, never hidden (its absence would invite "where did it
 * go?"), never a clickable no-op. Pure so the guard is unit-testable.
 */
internal fun hasNewlyMissing(missing: List<MissingAccount>): Boolean =
    missing.any { it.newlyMissing }

// ===============================================================
// Footer action strip (D5) + refresh/remove actions
// ===============================================================

/**
 * Footer action strip (D5, spec: refresh authorization action + remove
 * connection; task 5.6): last-synced timestamp on the left
 * (`labelSmall`), then BOTH per-bank actions at the Inline Text XS tier
 * (DESIGN-LANGUAGE §6.1.1 — 28dp height, `labelSmall` label, trimmed
 * padding, dense-container opt-out, `shapes.small`): "Refresh
 * authorization" (EXPLICIT text + a leading 14dp `Icons.Outlined.Key`
 * glyph — naming discipline: the authorization refresh never renders
 * unlabeled; refreshing the authorization renews the EB session, a
 * consent act, never a data sync) and "Remove" (danger text + a leading
 * 14dp `Icons.Outlined.Delete` glyph — the glyph aids scanning; the
 * danger-colored text keeps the anchor). The 8dp row gap keeps the strip
 * a SINGLE quiet line (28dp controls — row-height invariant).
 *
 * The refresh action renders for EVERY connected bank
 * (bank-connection-management 11.14 — user overrule of the old
 * live-only gating; the spec itself changed): the direct-entry
 * re-authorization path is reason-agnostic, so a dead or expired
 * consent needs the SAME action — the dashboard's consent-dead/expired
 * notices remain ADDITIONAL triggers, never the exclusive one. The
 * action fires POST /api/auth, so the dense control keeps the global
 * "one action at a time" busy gate ([LocalActionBusy]) that
 * [ActionButton]/[QuietActionButton] provide
 * automatically — there is no dense-tier busy-gated component, so the
 * gate is read directly here. "Remove" always renders and stays
 * enabled: it only OPENS the
 * confirmation dialog — the dialog's confirm action is the destructive
 * gate (unchanged) and carries the busy disabling.
 *
 * The strip carries NO balance-refresh control: a balance sync is a data
 * concern of the dashboard, not of a per-bank connection card (naming
 * discipline — "refresh" never appears unlabeled next to "Refresh
 * authorization").
 *
 * Action elevation (localized-sync-errors 5.1, §6.1.8): on a danger-tier
 * connection the "Refresh authorization" action renders as a prominent
 * button (primary fill, explicit text, still the 14dp key glyph — never a
 * circular-arrow glyph); healthy and brass cards keep the quiet Inline
 * Text XS action.
 */
@Composable
private fun ConnectionFooter(viewModel: AppViewModel, bank: BankData, tier: ConnectionDegradationTier) {
    val lastSynced = bank.lastSyncAt
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        Text(
            text = if (lastSynced != null) {
                "Last synced ${relativeTimeSince(getCurrentEpochMillis(), lastSynced)}"
            } else {
                "Never synced"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // "Refresh authorization" — quiet Inline Text XS on healthy/brass
        // connections; elevated to a prominent button on danger-tier
        // connections (localized-sync-errors 5.1). Both render the explicit
        // text + 14dp key glyph and fire the same direct-entry flow.
        when (connectionRefreshActionTier(tier)) {
            ConnectionRefreshActionTier.Quiet -> QuietRefreshAuthorizationAction(viewModel, bank)
            ConnectionRefreshActionTier.Prominent -> ProminentRefreshAuthorizationAction(viewModel, bank)
        }
        // "Remove" — Inline Text XS (task 5.6): danger text + the 14dp
        // delete glyph; opens the confirmation dialog (the gate). Shared
        // dense wash (§6.1.1, task 11.2).
        run {
            val interactionSource = remember { MutableInteractionSource() }
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) { viewModel.requestRemoveConnection(bank) }
                        .denseWash(interactionSource)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(Dimens.xs))
                    Text(
                        "Remove",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Quiet "Refresh authorization" (Inline Text XS, §6.1.1): 28dp dense
 * opt-out, labelSmall, trimmed padding, explicit text + leading 14dp key
 * glyph, busy-gated (fires POST /api/auth), shared dense wash.
 */
@Composable
private fun QuietRefreshAuthorizationAction(viewModel: AppViewModel, bank: BankData) {
    val interactionSource = remember { MutableInteractionSource() }
    val busy = LocalActionBusy.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = !busy,
                ) { viewModel.startReauthorizationFlow(bank) }
                .denseWash(interactionSource)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Key,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (busy) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(modifier = Modifier.width(Dimens.xs))
            Text(
                "Refresh authorization",
                style = MaterialTheme.typography.labelSmall,
                color = if (busy) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/**
 * Prominent "Refresh authorization" (danger-tier connection, task 5.1):
 * the SAME 28dp Inline Text XS footprint as the quiet action (user ruling,
 * 8.4 manual pass: prominence comes from the filled primary treatment and
 * its color, never from a larger bounding box — the row-height invariant
 * bounds the action strip at a single quiet line on every card); the
 * explicit text and the 14dp key glyph stay — the consent act stays
 * labeled and never uses the circular-arrow sync glyph.
 */
@Composable
private fun ProminentRefreshAuthorizationAction(viewModel: AppViewModel, bank: BankData) {
    val busy = LocalActionBusy.current
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !busy,
                ) { viewModel.startReauthorizationFlow(bank) }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(Dimens.xs))
            Text(
                text = "Refresh authorization",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * Inline extra-small danger delete affordance (D5/D9): a 28dp-tier
 * icon-only delete control in danger tint (§6.1.1 dense opt-out). The
 * ONLY unconditional per-account deletion path in the system — local
 * copies are never re-fetched, so deletion is the only way to part with
 * them.
 *
 * Automation guard principle (D10, spec: automation guard): the day an
 * active automation references this account, THIS control is the site
 * the guard fires — disabled with the generic "used by automations"
 * treatment (see [automationGuardNote]); the enumerated automation list
 * renders only where the user can act on it (a future workflows
 * feature). Today no automations exist, so the control ships enabled —
 * the [enabled] parameter is the single seam the workflow feature
 * binds; no dead disabled-machinery is built ahead of need.
 */
@Composable
private fun InlineDangerDelete(
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    // Shared dense wash (§6.1.1, task 11.2) instead of the IconButton's
    // ripple — one mechanism for the whole 28dp tier.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !busy,
            ) { onClick() }
            .denseWash(interactionSource)
            // Icon-only delete action: name + button role on the clickable
            // node (WCAG 1.1.1 / 4.1.2).
            .semanticIconAction("Delete local copy"),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            WaitingIndicator.Inline()
        } else {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.error
                } else {
                    // The generic disabled treatment for the automation
                    // guard (D10): dimmed, non-interactive — plus the
                    // guard's reference copy where it can be read.
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The automation guard's generic reference copy (D10, spec: automation
 * guard): "used by automations" — never an enumerated automation list on
 * a screen where the user cannot act on it. Documented as the single
 * copy constant the future workflow feature reuses at every guard site;
 * not rendered anywhere yet (no automations exist — both dialog actions
 * ship enabled).
 */
internal const val automationGuardNote = "Used by automations"
