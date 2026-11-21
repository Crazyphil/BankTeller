# Working notes: brass usage inventory (task 2.1)

Per-site verdicts for every `brass` token usage in `app/web/src/commonMain`
(rule: text → `brassText`; ornaments/fills/tints/indicators/glyphs → `brass`;
glyphs on brass WASHES → `brassText` per the review-pass amendment below).

## Review-pass amendment (openspec-reviewer, post-manual-verification)

The reviewer's dual-precision re-run + the new glyph-pair declarations
found **the brass warning glyph on its own wash over surfaceContainer
measures 2.9969:1** — under the 3:1 non-text threshold (the exact
"zero-margin gold" class D2's sideline ruling eliminated). Resolution,
per D2's precedent applied to the glyph carrier: **the warning glyphs
riding brass washes render `brassText` (bronze)** — LoginScreen's
session-expiry panel, SettingsScreen's removeCaveat panel,
OnboardingScreen's StatusNote (`Icons.Filled.Warning`) and
CommitmentStatusWarning (yellow tier). Danger glyphs keep the error
token (3.87+ on their washes after D7). The test's glyph pairs now pin
brassText-on-brass-wash at 3:1.

Also from the review pass:
- **Test precision policy**: `composite()` asserts the renderer's exact
  float blend (design record's binding rule); a sibling
  `compositeQuantized()` (the 8-bit pixel the canvas displays) asserts
  the same pairs as a secondary pass — neither precision may regress
  silently (the #51B089 boundary case is the recorded precedent).
- **Test common-safety**: the test compiles for wasmJs too (commonTest)
  — no `Math.round`/`String.format` JVM idioms; manual fixed-point
  formatting prints 4 decimals (the 0.0001-miss trap).
- **Compound mounts**: the dashboard account card's tier tints
  (danger@6%/brass@4% over `surface` — a different mount than the
  connection card's over-sc) are declared with their status-slot text
  pairs; glyph-on-wash ornament pairs are declared (the 2.9969 finding
  fell out of exactly this declaration).
- **Verification scope**: task 4.1's verify step now also runs
  `compileTestKotlinWasmJs` (the reviewer found JVM-only idioms broke
  the wasmJs test compilation — the containerized Karma suite's
  prerequisite, i.e. every browser test on a container host).
- **Permanent check gate (follow-up review)**: `check` now STRUCTURALLY
  dependsOn `compileTestKotlinWasmJs` — verified to fire on hosts
  WITHOUT a container engine (the container Exec task's enabled=false
  suppresses only its own inner dependsOn bundle; the compile task is
  unconditional), so the finding-1 class (JVM-only idioms in commonTest
  compiling green on jvmTest while breaking wasmJs) fails the ordinary
  `./gradlew check` from now on. The jvmTest filter widens to
  `DecisionBoxBorderColorTest` (the extracted two-state rule's pins).
- **Post-review regression tests (session follow-up)**:
  `DecisionBoxBorderColorTest` (commonTest, six assertions over the
  extracted `resolveDecisionBoxBorderColor` — the composable's
  LocalBankTellerColors wiring itself is untestable in commonTest and
  remains visually verified); and the server-side
  `janitor sweep consumes the one-shot expiry reason` CHARACTERIZATION
  test in SqlDelightSessionStorageTest — that one documents the
  separately-diagnosed session-timeout defect (2026-11-20 council
  analysis: the ~6h janitor prunes the expired row before the user's
  first post-idle request, so the one-shot session_expired reason is
  lost and the login screen cannot render its expiry panel) and belongs
  to the DEFERRED session-timeout fix change against the
  session-management capability — its flip-on-fix message is the red
  starting point for that change; it commits here only as the tripwire,
  and the future change's proposal must cross-reference it.

## TEXT sites (→ `LocalBankTellerColors.current.brassText`)

| Site | Verdict |
| --- | --- |
| `WizardScaffold.kt:112` — wizard eyebrow ("…STEP N OF M", labelSmall uppercase) | text → brassText |
| `DashboardScreen.kt` ZeroAccountNotice title "Your bank did not share any accounts yet" (titleMedium) | text → brassText (migrated with task 1.3) |
| `DashboardScreen.kt` global-sync-line brass segment "· N banks outdated" (labelMedium) | text → brassText |
| `DashboardScreen.kt` `ConsentPanelBody` expiring title (titleMedium, `color = tint` where tint = brass) | text → brassText (wash + sideline already split per task 1.3) |
| `DashboardScreen.kt` `ConsentAccordion` summary title "N bank connections need re-authorization" (titleMedium, `color = tint`) | text → brassText when the summary tier is brass |
| `DashboardScreen.kt` `AccountStatusSlot` status line (labelSmall, `color = tint` where tier = Brass) | text → brassText; the 16dp glyph keeps the ornament token (glyph-paired, 3:1) |
| `OnboardingScreen.kt` "ACCOUNT SELECTION NOTICE" (titleMedium) | text → brassText (sideline migrated with task 1.3) |
| `onboarding/SandboxCredentialsPanel.kt:167` — "SANDBOX TEST CREDENTIALS"/"SANDBOX ACCESS" eyebrow (labelSmall) | text → brassText |
| `SandboxCredentialsScreen.kt` "SANDBOX" chip label (labelSmall on a 12% brass wash) | text → brassText (4.71:1 on the wash; chip bg stays ornament-brass wash) |
| `onboarding/OnboardingScreen.kt` `BankStatusBadge` "Issues" label (labelSmall Medium, textColor = brass when yellow) | text → brassText; the 12dp glyph moves to brassText too (the badge wash is brass 12% — the gold glyph sat at exactly 3.00:1, zero margin; the badge is the §6.1.3 glyph-discipline site, matching the consent-badge treatment) |
| `onboarding/OnboardingScreen.kt` `CommitmentStatusWarning` headline (titleMedium SemiBold, `color = tint` where tint = brass when yellow) | text → brassText; the 18dp Warning glyph keeps the ornament token |

## ORNAMENT sites (stay `brass`)

| Site | Verdict |
| --- | --- |
| `BankTellerMark.kt` — the engraved coin motif (all canvas strokes) | ornament → brass |
| `AppTabBar.kt` — the 2dp active-tab hairline indicator | ornament → brass |
| `SettingsScreen.kt` `SectionNavColumn` — 2dp leading hairline on the active row | ornament → brass |
| `WizardProgressIndicator.kt` — completed-step fills, current-step outline, completed connectors (all canvas; the current-step circle is a graphic, not a number) | ornament → brass |
| `WaitingIndicator.kt` `Zone`/`Inline` — 32dp/16dp spinners (decorative waits) | ornament → brass |
| `LoadingSplash.kt` — 24dp splash spinner | ornament → brass |
| `LedgerRules.kt` — double-rule accent at 30% opacity (Sovereign Letterhead) | ornament → brass |
| `BankAvatar.kt` — avatar background palette (token-derived lerps, all ornamental backgrounds) | ornament → brass |
| `DecisionBox` brass washes (12% tint at every call site) | ornament → brass |
| `DashboardScreen.kt` `HeroFreshnessGlyph` — 18dp WarningAmber glyph (tooltip-paired, glyph carrier) | ornament → brass (icon only) |
| `DashboardScreen.kt` consent panel/accordion washes (`DecisionBox tint`), 18dp `Icons.Filled.Error` glyph in the accordion (glyph-paired with the summary text) | ornament → brass |
| `DashboardScreen.kt` account-card scan signal — 1px tier border + 4–6% tier tint (`SyncPresentationTier.Brass`) | ornament → brass |
| `OnboardingScreen.kt` `BankStatusBadge` — 12% wash background | ornament → brass |
| `OnboardingScreen.kt` `CommitmentStatusWarning`/`StatusNote` — DecisionBox washes, 18dp glyphs, `Icons.Filled.Warning` | ornament → brass |
| `LoginScreen.kt`/`SettingsScreen.kt` warning-panel washes and 18dp WarningAmber glyphs | ornament → brass |
| `SandboxCredentialsScreen.kt` chip container (12% brass wash) | ornament → brass |

## Already non-brass (no change)

- `WizardProgressIndicator` mono "Step N of M" label: `onSurfaceVariant`, not brass.
- Wizard titles/body/onSurface text: standard ink tokens.
- Comments/doc-mentions of brass (`AppViewModel.kt`, `SyncErrorCopy.kt`, `PrivacyScreen.kt`, `CallbackScreen.kt`) — prose only.

## Task 4.2 record (old-palette fail run) — VERIFIED

Scratch worktree revert of the palette (brassText := ornament gold,
emerald/danger at pre-change values, tab alpha 60%), full-matrix test run:

- LIGHT: 13 pairs below threshold — brassText-as-gold on bg/surface/sc
  (3.57/3.79/3.39:1) and on its own brass wash over bg/surface/sc
  (3.14/3.31/3.00:1); emerald on surfaceContainer 4.47:1; emerald over
  its wash on bg/surface/sc (4.04/4.26/3.85:1); danger over wash
  (4.47:1…); tab-bar composited label (onSurfaceVariant@85%-declared pair
  run with the old 60% alpha) 2.71:1.
- DARK (pre-D7 tokens): 3 pairs below threshold — emerald over emerald@12%
  on sc 4.40:1 (the live guarantee-panel mounting), danger over danger@12%
  on surface 4.35:1 and on sc 3.87:1 (the dead-badge mounting — the failure
  that triggered the D7 pass); plus the pre-D7 latent rung pairs (onSV on
  scHighest 4.39:1, emerald on scHighest 4.06:1, danger on scHigh/Highest
  4.04/3.52:1) and the compound-mount class (dead badge danger ≈4.09 dark /
  ≈4.16 light under the translucent pill — verified by hand-computed blend
  chain, the reviewer-found D3 case).
- The test demonstrably fails on the pre-change palette, with exactly the
  predicted pairs named. Scratch reverted; not committed.

Post-D7 status: the full matrix passes in both themes with the rendered-
pixel (8-bit quantized) composite math.
