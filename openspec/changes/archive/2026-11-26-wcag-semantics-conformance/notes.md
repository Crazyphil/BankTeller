# Implementation Notes (change record)

The change's evidence ledger: task-1.2/1.3 probe + verdict records, the 2.6 inventory,
the 3.2 negative-verification, the 4.2 WCAG verdict ledger, and the 5.x
manual-verification record. Append-only per section; entries dated.

## 1.2 Post-upgrade live probe (CMP 1.12.2, 2026-11-23)

Environment: container loop (`./gradlew :server:containerUp`), fresh `bankteller:latest` image
built from the 1.12.2 frontend; app served at localhost:8080; probe via in-app browser
(Chromium/Electron) piercing the ComposeViewport shadow root (`mode: OPEN` → `host.shadowRoot`
reaches `#cmp_a11y_root`).

Probe results vs the spike's assertion set:

1. **`#cmp_a11y_root` container dimensions NON-ZERO — CONFIRMED (the CMP-10172/#3226 fix).**
   Container measures 1000×700 = exactly the canvas size (`position: absolute; top/left 0;
   width/height: 100%`, plus `opacity: 0; pointer-events: none`), matching the container's
   hardcoded `aria-live="polite"`. On 1.11.1 this container was 0×0.
2. **ThemeToggle `role="button"` + `aria-label` present — CONFIRMED.** The overlay node carries
   `role="button"` and `aria-label="Theme mode: <Mode>"` verbatim from `contentDescription`
   ("Theme mode: System" observed at fresh boot; "Theme mode: Dark"/"Theme mode: Light"
   observed after cycling).
3. **Overlay click listeners work — CONFIRMED (agent-controllability holds on 1.12.2).**
   `overlayNode.click()` executed the real theme cycle and persisted
   (`localStorage["bankteller-theme"]` advanced "dark" → "light" → removed-on-System);
   a reload boots with the persisted mode and the label re-projects. Canvas-level
   pointer events (`pointerdown/up` on the canvas, full flag set) also drive the app.
4. **Overlay node geometry non-zero — CONFIRMED with an environment caveat.** Nodes carry
   `position: fixed; white-space: pre; left/top/width/height` inline styles written by
   `setSizeAndPosition` (e.g. theme button 40×40 at (940,20) inside its 48dp container,
   splash logo 48×48 at (476,16), wordmark 131×33 at (435,88)). In this headless/occluded
   browser window the geometry sync (debounced) applies only after input events pump the
   compositor frame loop — `requestAnimationFrame` is throttled to never-firing while the
   desktop window is not visible, so the Compose frame clock stalls. This is a probing
   artifact of the occluded window (same class as the spike's dead-viewport 0×0 reading),
   not bridge behavior.
5. **`aria-selected`/`aria-live` mappings on state-bearing screens — PARTIALLY verified.**
   `aria-live="polite"` on the container confirmed live. `aria-selected` on the tab bar
   could not be observed in this session: the headless window never advances past
   `Screen.Loading` (the checkAuth/boot coroutines complete their fetches — /api/me and
   /api/onboarding/state both 200 — but recomposition waits on the stalled rAF frame clock).
   Not an app defect: the same binary is interactive when frames pump. Re-verify with a
   VISIBLE browser window in the manual 5.x pass (5.3 exercises the tab bar directly).

1.12.x mapping deltas vs the 1.11.1 research table, observed live:
- Container geometry now sized to the canvas (the #3226 fix — previously 0×0).
- Overlay still mirrors only nodes with declarations (contentDescription/role/textbox);
  plain `Text` nodes project with geometry but no label, matching the research table's
  update mechanism (`setA11YAriaRole` role set: Button/Heading/TextBox/… incl. `List`/`Grid`
  AriaRoleIds present in the 1.12.2 `ui-wasm-js` klib's `platform.accessibility` package —
  the collection → role=list/grid mapping is compiled into this build; heading levels
  (`level` field) exist in `AriaRoleId`/listener internals → re-examine `aria-level`
  projection when headings land in 5.x).

## 1.3 Verdict re-examination at CMP 1.12.2 (framework-blocked records)

- **2.1.1 keyboard / 2.4.3 focus order — verdict UNCHANGED: *exempted (framework-blocked)*,
  last examined at CMP 1.12.2 (2026-11-23).** The 1.12.0–1.12.2 release notes' Web sections
  list no non-slider tab-stop or DOM-focus-sync work (#3466 is Safari backing-input focus;
  #3329 mobile keyboard; #3235 hidden keyboard in mobile browsers). The 1.12.2
  `ui-wasm-js` klib's accessibility package still contains no tabindex/focus wiring
  (`ComposeWebSemanticsListener` surface unchanged in that respect). The jb-main picture
  (slider-only tab stops) stands. Upstream trail to monitor: the Compose web
  keyboard/focus issues (#3035/#3226/#3381/#3428 for the bridge; the broader focus-trail
  issues recorded in the color change's ledger).
- **2.4.7 focus visible — verdict UNCHANGED: *exempted*** (canvas-drawn focus indication;
  the input fields' brass focus outline is the Compose-drawn equivalent — no DOM focus ring
  exists for canvas-internal elements).

## 2.6 Component inventory + migration sweep (recorded 2026-11-23)

The checklist-driven sweep (every icon-only interactive control, every async
status text, every screen title, every list structure → primitive →
declaration), verified against the source tree post-migration:

### Icon-only interactive controls → semanticIconAction (2.1)
| Site | Primitive | Declaration |
|---|---|---|
| `ThemeToggle` IconButton (every screen family) | `semanticIconAction(themeToggleDescription(label))` | "Theme mode: <Mode>" + Role.Button; glyph decorative |
| LoginScreen session-expiry dismiss (Close, raw clickable icon) | `semanticIconAction("Dismiss")` | name + role on the clickable node |
| SettingsScreen remove-caveat dismiss (Close) | `semanticIconAction("Dismiss")` | same |
| SettingsScreen InlineDangerDelete (per-account local-copy delete) | `semanticIconAction("Delete local copy")` | glyph decorative |
| DashboardScreen RefreshControl (both compact + expanded IconButtons) | `semanticIconAction("Refresh balances")` | all three states |
| SandboxCredentialsPanel CopyFieldButton (3 credential rows) | `semanticIconAction("Copy <label>")` | "Copy username" / "Copy password" / "Copy one-time code" |

Named-but-not-icon-only (own visible text supplies the name — no primitive
needed, recorded as sweep findings): BrandedTopBar logout (TextButton),
Settings "Refresh authorization"/"Remove" (Inline Text XS), the account-list
expander, LinkingReopenBox, PopOutButton, DocsLink, onboarding StatusNote
dismiss (TextButton). The ConsentAccordion chevron (icon-in-clickable-row with
existing "Expand"/"Collapse" descriptions on the glyph) — left as-is; the row
text names the control. Decorative glyphs (badges, status icons paired with
text, warning/error icons) intentionally stay `contentDescription = null`.

### Async status text → StatusText (2.2)
| Site | Declaration |
|---|---|
| Dashboard GlobalSyncLine: "Updating balances…" + timestamp line + degraded summary segments | liveRegion Polite, exact existing style/color preserved |
| Dashboard ImportStep: "Connecting to your bank" + "Retrieving your balances…" | liveRegion Polite |
| Dashboard AuthFailureNotice: title + body + guidance texts | liveRegion Polite *(added 2026-11-25, final-review finding 2 — the sweep missed this async failure panel while migrating its structural siblings)* |
| Dashboard missing-accounts dialog: in-dialog missingAccountsError slot | liveRegion Polite *(added 2026-11-25, final-review finding 3a — mirrors the migrated settingsError line)* |
| Sandbox-credentials popup: load-failure title + body | liveRegion Polite *(added 2026-11-25, final-review finding 3b — the popup entered sweep scope via its heading; its only async texts now announce)* |
| Settings remove-caveat panel text; settingsError line | liveRegion Polite |
| Onboarding: linking-step waiting/confirming texts; all four linkError banners | liveRegion Polite |
| Login: loginError + rate-limit messages | liveRegion Polite |

### Screen titles → screenHeading (2.3)
LoginScreen "BankTeller" headline, WizardScaffold title slot (every wizard
step), Dashboard Greeting, Settings compact "Settings" + "Bank connections"
section header, LegalDocument headlineLarge title (Terms + Privacy via the
shared component), CallbackScreen "BankTeller", SandboxCredentialsScreen
bank-name headline (the popup's identity header). *(Correction 2026-11-25,
found in the 5.7 pass: the LegalDocument title edit recorded here had never
landed — the fixer's inventory over-claimed; fixed for real in the same pass
(the shared component's title Text now carries the heading modifier; compile
+ hermetic trio green).)*

### Tabs / navigation → tabItemSemantics / navRowSemantics / backAffordanceSemantics (2.4)
| Site | Declaration |
|---|---|
| AppTabBar TabItem (both entries) | Role.Tab + label-as-name + Selected matching state |
| Settings SettingsSectionList rows (compact drill) | Role.Button (row text names) |
| Settings SectionNavColumn rows (expanded) | Role.Button + Selected |
| Settings SettingsPanel compact back affordance | Role.Button + "Back to Settings sections" |

### Text fields → fieldName (2.5)
Login username ("Username") / password ("Password"), onboarding email
("Email"), bank search ("Search by name, BIC, or country"). Sweep finding:
SandboxCredentialsPanel has NO OutlinedTextFields (credential rows are
read-only value text + copy button) — nothing to apply there.
*(Correction 2026-11-25, final-review findings 1: the inventory above
over-claimed completeness — five `RegistrationReviewStep` fields (Redirect
URL — always reachable in first-run registration; Description, GDPR email,
Privacy URL, Terms URL — PRODUCTION-only) were missed by the sweep despite
the "every OutlinedTextField" rule. Fixed in the same pass: all five now
carry `fieldName` with their label names; the app's field count is 9 of 9
migrated.)*

### List-bearing containers → collectionInfo (2.6)
| Site | Declaration |
|---|---|
| Dashboard AccountCards Column | rowCount = total live+local accounts |
| Settings connection-cards Column | rowCount = banks count |
| Settings ConnectionAccountList Column (per connection card) | rowCount = accounts count |
| Onboarding bank-selection LazyColumn | rowCount = filtered.size |
| Dashboard missing-accounts dialog record list | rowCount = rows.size *(added 2026-11-25, final-review finding 5 — the bounded decision list migrated in the same pass)* |

Checklist verdict: no icon-only interactive control remains unnamed; no
retained async status change remains unannounced; every screen title is a
heading; every list-bearing container declares collection semantics; the tab
bar and nav rows declare state machine-readably.

## 5. Manual-verification record (2026-11-23, agent-driving session)

Environment constraints documented up front: the in-app browser window runs occluded
most of the session — Chromium throttles `requestAnimationFrame` while hidden, and the
Compose frame clock waits on rAF, so screens freeze past the splash until a visibility
window pumps frames (`setTimeout`-backfill patching `window.requestAnimationFrame` proves
the mechanism: with it, the full UI advances). Same binary, works with a visible window.
This is the same probing-artifact class the spike documented (a dead-viewport geometry
reading) — not an app defect, and it interacts with NONE of the declared semantics.

**5.1 login screen — PARTIALLY VERIFIED (DOM projection; screen reader not exercised).** The two form fields expose as
`role="textbox"` with `aria-label` "Username" and "Password" (the fieldName primitive
projecting — in the 1.11 spike these textboxes carried NO name); the theme toggle
announces as a button named "Theme mode: <Current>" (cycled live); the session-expiry
panel's dismiss exposes `role="button"` + `aria-label` "Dismiss"; the "BankTeller"
headline carries `role="heading"`. Fields are ALSO typable through the overlay (see 5.6).
Screen-reader announcement (NVDA/ChromeVox reading rather than DOM projection) — not
exercised: no reader available in this environment; DOM projection is the verified truth.

**5.4 wizard — VERIFIED (reachable portion).** On the bank-selection step: the step
title "Choose your bank" carries `role="heading"`; the eyebrow ("INITIAL SETUP · STEP 5
OF 6") projects in reading order before it (y=64 vs y=141); the search field announces
as "Search by name, BIC, or country" (fieldName); the bank list projects as
`role="list"` — the CollectionInfo → role=list bridge mapping, previously only
source-research, now live-verified; each bank card's logo button is named
("<Bank> logo"), the PSU chips project as buttons. The "waiting/progress announces"
half needs a real bank-link flow (sandbox credentials + external redirect) — not
driven in this session; the linking-progress StatusText declarations are
contract-tested (3.1) and the declaration quality is live-verified on this screen's
siblings.

**5.6 agent-driving smoke — VERIFIED (theme toggle + form + navigation portion).**
Through the shadow-root overlay alone (no keyboard, no real mouse): the theme toggle
was driven through a full three-step cycle with persistence and re-projection at every
step (Light→System key-removed→Dark "dark"→Light "light"); the login form's fields were
focused and typed through the overlay's textile path (the bridge's backing-input
protocol — beforeinput+input sequences); the Login button's overlay node submitted the
real request and the app routed to the wizard. The tab bar half of 5.6 requires an
onboarded bank connection (dashboard surfaces only render authenticated AND onboarded);
deferred — the tab nodes' semantics are contract-tested in 3.1 (Role.Tab + Selected +
label) and the overlay click-through mechanism itself is proven (same clickable
mechanism as the theme toggle). *(Tab-bar half executed 2026-11-23 in the 5.3 close-out
session above: Settings-tab and Dashboard-tab overlay-node clicks both executed and
persisted through full round-trips at expanded AND compact width — 5.3's assertion-5
row.)*

**5.2 dashboard / 5.3 tabs+settings / 5.5 remove caveat — NOT exercised live** (need a
connected bank for the dashboard surfaces; the remove caveat needs a removal with a
failing bank-side delete). Their declarations are the primitives' — every one is
contract-tested in SemanticsContractTest (liveRegion, heading, tab semantics, field
names, collection info), and the bridge mappings those depend on (aria-selected,
aria-live) are compiled into 1.12.2 per source research. Recommend closing these three
in a short user-driven pass with a visible browser (sign in, tab-switch, trigger a
sync, remove a test connection). *(Superseded for 5.2: closed later the same day by the
"5.2 close-out" entry above, which provisioned a sandbox connection and ran the
dashboard assertions live. 5.3/5.5 remain open.)*

**5.7 zero visual change — PARTIALLY VERIFIED (construction + git-diff audit; pixel diff pending a visible window).** Every migrated
diff is semantics-only (modifier additions and Text→StatusText swaps preserving exact
text/style/color); no layout, padding, or typography value changed anywhere in the
sweep (git diff audit). Pixel-diff screenshots were not produced (the occluded window
cannot render); the construction argument plus the container screenshot from task
1.2's session (identical login composition pre/post-upgrade) is the evidence.

**5.1 close-out — cold-run via the agent-driving skill (2026-11-23; screen-reader-equivalent (a11y-overlay projection), not actual reader speech).** A fresh agent session with no prior context loaded `.agents/skills/agent-driving/SKILL.md` and drove the compose app at `http://localhost:8080` through an already-open tab (that session had no browser tools of its own; the parent executed the probe calls on its behalf, following the skill's §1–§5). The §2 golden path (`ComposeViewport` OPEN shadow root → `#cmp_a11y_root`), §3 node read, and §4 overlay-click were followed verbatim; all three assertions PASS on the projected tree:

| # | Assertion | Observation (projected attributes) | Verdict |
|---|---|---|---|
| 1 | Username textbox named | `role="textbox"`, `aria-label="Username"` (non-empty), 368×64 | **PASS** |
| 2 | Password textbox named | `role="textbox"`, `aria-label="Password"` (non-empty), 368×64 | **PASS** |
| 3 | Theme toggle named as button + cycling advances label & persisted state | `role="button"`, `aria-label="Theme mode: <Mode>"`; cycle baseline `"Theme mode: System"` with `localStorage["bankteller-theme"]` absent (System = key removed) → ONE overlay-node click → label re-projected to `"Theme mode: Dark"` (debounce-polled, §4) and `localStorage["bankteller-theme"] = "dark"` | **PASS** |

(Projection note: the node dump listed the toggle as "Theme mode: Dark" while the cycle baseline read "Theme mode: System"; the ordered before/after pair plus the localStorage transition is the authoritative evidence — the dump ordering was not timestamped.)

**Judgment — the login form is understandable without sight: YES (projection-level).** A screen-reader user finds the "BankTeller" heading (`role="heading"`), both fields with their names ("Username", "Password"), a submit button named by its merged text ("Login"), and a self-naming theme toggle ("Theme mode: <Current>"). Gaps not covered by this tree: (a) reader *speech* itself is not exercised — the verdict is screen-reader-equivalent, not actual reader output; (b) the password field projects as a plain `textbox` — its "Password" name plus dot-masked typed content are the only type cues, no aria-* type/required/invalid state is exposed here; (c) no form/landmark grouping is projected (no `role="form"`/region around the fields); (d) the decorative brand logo and glyph are silent by design (`contentDescription = null` — correct under 1.1.1, not a gap); (e) the session-expiry panel is absent in this tree, so its "Dismiss" affordance (previously observed in the 5.1 entry above) was not part of this run.

**Skill-test verdict (doubles as a 5.8-pattern cold test): SUFFICIENT.** The skill reached, read, and drove the app without prior context: §1 (GET / → 200), §2 (shadow-host search + `#cmp_a11y_root`), §3 (node dump: roles/labels/rects), §4 (overlay-node click → real action + persistence), §5 (quirks). Steps that were wrong/unclear/missing: (1) the skill's frontmatter notes that "a JS-evaluation path … (browser tools or an equivalent)" and an unconstrained session are required, but it does not say what a tool-restricted subagent should do — i.e., that the parent must provide the evaluation channel and execute the probe on its behalf; the body never repeats the prerequisite. (2) §5.4's remedy for the occluded-window frame stall is "make the tab actually visible … or run probe assertions that don't need frames"; it does not document the pointer-event pump on the canvas (implied by §4's canvas pointer events) that was actually needed to advance past the splash here. (3) The "System = localStorage key removed" convention appears only parenthetically in the change's probe record, not in the skill. Everything else (§2 one-step shadow-root path, §4 debounce-polling, §5.1–5.3 traps) matched the environment; no CDP-snapshot/blind-Tab/HEAD quirks were triggered this run.

**5.2 close-out — sign in and land on the dashboard (2026-11-23, agent-driving session; screen-reader-equivalent overlay projection, not actual reader speech).** Per the task's ruling, "navigate with the reader" was verified as the a11y tree actually exposed in the DOM (the ComposeViewport shadow-root overlay, skill §2) — roles, names, live regions — against the running container-loop app at localhost:8080. All four assertions PASS on the projected tree:

| # | Task assertion | Observed (projected attributes) | Verdict |
|---|---|---|---|
| 1 | Greeting reads as a heading | Dashboard greeting node carries `role="heading"` with textContent "Welcome, admin!" (div at 733,64 230×35). No `aria-level` is projected (Compose `Heading` is level-agnostic — expected at this bridge). | **PASS** |
| 2 | Account cards' content reads as a coherent list | The account-cards container carries `role="list"` (div at 504,231 688×823) with EXACTLY 8 direct children — the 8 account cards (5 × "Mr. Popcorn / Leksands Sparbank", 3 × "Aino Salo / Nordea"), each card projecting its owner → bank → balance (or error) texts in reading order. Cards carry no `role="listitem"` and no per-item index — the list semantics are container-level (role + children), the bridge projects no item-level mapping. | **PASS** |
| 3 | Balance refresh control announces its action | Refresh control node carries `role="button"` + `aria-label="Refresh balances"` (28×28 at 1164,179) — the 2.6 inventory's `semanticIconAction("Refresh balances")` projecting verbatim. The label and role are STABLE through the entire in-flight window (100 ms samples over 2 s mid-sync: attributes unchanged — no transient relabel/disable projection; the visual waiting indicator which replaces the icon is decorative by design). | **PASS** |
| 4 | Sync-triggered "Updating balances…" change announced politely | Quiet state: no such node (the line reads "Updated just now" + a `· 1 bank outdated` degraded segment, both plain text). Within ~100–200 ms of clicking the refresh node, a text node "Updating balances…" projects INSIDE the app's single live region — `#cmp_a11y_root` with `aria-live="polite"` (`role="presentation"`, opacity-0 overlay) — and on completion is replaced back by the timestamp line inside the same region. Not silent: the insertion/mutation happens inside a live region, so readers announce it; not interrupting: the live value is `polite` (never `assertive`) — exactly WCAG 4.1.3's politeness requirement. | **PASS** |

**Key delta finding (resolves the §1.2 item-5 caveat): node-level `aria-live` does NOT project in this build (CMP 1.12.2).** Throughout the sync cycle the StatusText nodes themselves carry no `aria-live` attribute — the ONLY aria-live region in the entire exposed tree (root query + shadow-root enumeration) is the container's hardcoded `#cmp_a11y_root aria-live="polite"`. Politeness therefore rides the container for every StatusText content change (the #3428 node-level projection is compiled in but does not materialize on the DOM at 1.12.2). Consequence: the 4.1.3 verdict "conforms via liveRegion declarations" STANDS with the container-level mechanism as the operative bridge path — but the ledger's mechanism wording should be read as container-mediated, not node-mapped. Since the whole overlay lives inside the one polite region, changes are announced without interrupting; conversely any overlay content change is announced (a coarser channel than a per-node region — same practical behavior the container design has always had).

**Projection delta for open 5.3 (recorded to avoid rediscovery):** the tab bar's Dashboard/Settings nodes project as `role="button"` with their text as the accessible name (textContent "Dashboard"/"Settings") — NOT `role="tab"`. `Role.Tab` is declared in the Compose tree (contract-tested, 3.1) but the 1.12.2 bridge's AriaRoleIds set carries no Tab mapping; no `aria-selected` was observed on these nodes either when active (`aria-selected` projection remains unconfirmed live — 5.3 should probe the tab state directly rather than expect the tab role).

**5.3 close-out — Dashboard/Settings tabs + Settings navigation + compact back affordance (2026-11-23, agent-driving session against the container-loop app at localhost:8080; screen-reader-equivalent overlay projection, not actual reader speech).** Per the task's ruling, "announces as" was verified as the a11y tree actually exposed in the DOM (roles, names, states, live regions). Logged in via `POST /api/login` (admin), landed on the dashboard as preconditioned (sandbox bank connected — the trees below show the connected banks' cards). Both layout modes exercised — expanded at the tab's natural 1000px width, compact via the iframe-harness technique recorded in the skill's §5.6 quirk (700×700 `file:`→plain-HTTP iframe = 700dp ≤ the 768dp breakpoint; the harness was served from `localhost:8123` because a `file:` harness makes `localhost:8080` a third-party frame whose session cookie Chromium blocks).

| # | Task assertion | Observed (exact projected attributes) | Verdict |
|---|---|---|---|
| 1 | Active tab announces as selected | **FAIL (framework-blocked).** Both tab nodes carry `role="button"`, `aria-label` = their label, `textContent` = their label — and NOTHING ELSE: no `aria-selected`, no `aria-current`, no `aria-pressed`, no `aria-disabled`, no `aria-checked`, no `aria-expanded` on either node, in EITHER layout mode, under EITHER selection (verified on Dashboard-active [Settings node identical] and Settings-active [Dashboard node identical], at expanded AND compact width — plus a full-tree sweep for the state-attribute family on the whole Settings tree returning zero hits). The active and inactive tab project BYTE-IDENTICAL attribute sets; an AT user cannot distinguish selected from unselected in the exposed tree. The declaration exists in the Compose tree (`tabItemSemantics`: `role = Role.Tab`, `selected = selected`) and is contract-tested (3.1 confirms the tree's Selected value tracks state); the 1.12.2 bridge does not project it (no `aria-selected` mapping in the compiled wasm artifact — confirms the §1.2-item-5 caveat and the task-1.2 line "the tab nodes' projections find these NOT exposed"). Same verdict as the ledger's 4.1.2 — *exempted (framework-blocked)* on the criterion, with names/roles conforming inside the basis — re-examined at each CMP upgrade. |
| 2 | Inactive tab present and distinguishable | **PASS (presence) / FAIL-by-assertion-1 (distinguishability).** Both nodes always present: expanded 1000px — inactive Settings `aria-label="Settings"`, 344×50 @ 500,633 beside active Dashboard `aria-label="Dashboard"` 344×50 @ 156,633; compact 700px — both present at 334×50 (@ 16,633 / @ 350,633). The two tabs are distinguishable from each OTHER (different names) but the ACTIVE/INACTIVE pair is not (assertion 1's zero-state-attribute finding). |
| 3 | Settings section navigation announces entries with their states | **PARTIAL PASS / state FAIL-by-assertion-1.** Expanded two-pane: the section-nav row "Bank connections" carries `role="button"`, name = text `Bank connections` — correct — but NO selected state projects (`selected = true` was necessarily in the Compose tree: the single-section set makes it the active section by construction, `SettingsTwoPane` → `activeSection.id`). Compact drill list: the row carries `role="button"`, name = its text; the section list's title carries `role="heading"` ("Settings"). Again: role+name correct, state absent from the DOM. |
| 4 | Back affordance (compact) announces as a button named for its action | **PASS.** When drilled into the Bank-connections section at 700px, the back affordance node carries exactly `role="button"` + `aria-label="Back to Settings sections"` (textContent "Settings"), 77×28 @ 16,64 — the `backAffordanceSemantics("Back to Settings sections")` declaration (2.4) projecting verbatim. A screen-reader-equivalent user hears a button explicitly named for where it goes. Clicking this overlay node executed the real `onBack()` (the tree re-rendered to the section list — the drill state cleared, verified by state probes around the click). |
| 5 | (5.6 folded) Tab-bar click-through executes + state persists | **PASS.** Every overlay-node click on the tab bar executed the real switch, both directions, at BOTH widths: Settings-tab click (expanded: Dashboard tree → Settings two-pane; compact: → drill list), Dashboard-tab click (both back), a round-trip (Settings→Dashboard→Settings), and a final Settings→Dashboard switch — each verified by polling the tree for the destination's signature nodes (heading "Welcome, admin!" / Settings tree). The app state persisted across the switches (each switch stuck until the next click). **Note (app-behavior delta, not an a11y defect):** the COMPACT drill selection did NOT survive the tab round-trip — after Settings→Dashboard→Settings the tree showed the section LIST (no back affordance), not the re-drilled panel; the drill is a navigation-mode artifact of Settings' compact layout, reset on leaving the tab (the `SettingsPanel`'s `onBack` clears `selectedSectionId`), and the tab switch itself persisted. The task's "state persists" assertion is about the tab-bar click-through's destination state — that held on every click. |

**Compact-probe environment record (for the skill's §5.6, written there):** no viewport-resize primitive exists in the browser toolset; CSS-side shrinking (`zoom`, host-element constraints, `innerWidth` overrides + synthetic resize) provably does not re-measure the Compose viewport; the plain-HTTP iframe harness on the app's site (700×700) genuinely re-measures (700dp → compact Settings mode verified: drill list `role="heading"` "Settings" + 668-wide rows), with the `file:` third-party-cookie trap and the iframe rAF stall recorded.

**Findings do NOT close 5.3 (checkbox left open):** assertions 2 (presence), 4 (back affordance — the only fully-new projection under test), and the 5.6 click-through all PASS, but the task's core assertion 1 (selected state announced) and the state half of 3 fail at the bridge — the same framework gap already recorded in the ledger (4.1.2's state-projection aspect, the criterion's blocked aspect). The declarations are app-side correct and contract-tested; the projection gap is the 1.12.2 wasm bridge's (no `aria-selected`, no Tab AriaRoleIds) and re-enters at the next CMP upgrade alongside the other blocked verdicts. Closing 5.3 would assert conformance the DOM does not carry.

**Environment record (how the dashboard was reached — the sandbox provisioning):** the
session provisioned a REAL sandbox bank connection to satisfy the dashboard's
authenticated+connected precondition: `POST /api/link-accounts` (Airbus Bank — the simulated
VR banks rejected the listed test credentials with "Invalid credentials"; `Mock ASPSP`
redirects to a production-style email login behind reCAPTCHA — dead end) → `POST /api/auth`
for **Nordea/FI** (the credential-less sandbox-bank whitelist) → the EB consent page at
`tilisy-sandbox.enablebanking.com` driven by browser click ("Authentifizierung fortsetzen")
→ the bank-side sandbox redirect auto-completed (credential-less) → 307 back to the app with
the merge. The dashboard then showed the sandbox PSU's 8 accounts (5 Leksands Sparbank
cards fail to update — no working consent for that bank — which also exercises the
`· 1 bank outdated` degraded-summary segment live; 3 Nordea balances render normally).
The connection REMAINS in the container DB after this probe: open 5.3/5.5 need a connected
bank (5.5's remove-caveat path can consume this connection — remove it from Settings to
trigger the caveat probe); delete it there when no longer needed. Nothing in the overlay
probing depended on which bank was connected; the assertions above are bank-agnostic.

**5.4 close-out — re-authorization wizard flow (2026-11-23, agent-driving session against the container-loop app at localhost:8080; screen-reader-equivalent overlay projection, not actual reader speech).** Per the task's ruling, "reads"/"announces" was verified as the a11y tree actually exposed in the DOM (roles, names, reading order via geometry, live regions). The flow was driven END-TO-END for real: Settings → Nordea connection card's "Refresh authorization" (`role="button"`, named by its text — the quiet re-auth action projecting) → wizard AuthProgress → "Continue to your bank" click → same-tab redirect to the real EB sandbox consent page (`tilisy-sandbox.enablebanking.com` — external HTML, not the app's overlay; its own buttons carry their own accessible names) → "Authentifizierung fortsetzen" click → 307 back to `/enable-banking-callback?state=…&code=…` → auto-redirect to the dashboard with the Nordea consent renewed and balances re-synced ("Updated just now"). The flow was completed, not abandoned; the connection remains for open 5.3/5.5 (same precondition state as the 5.2 session's environment record).

| # | Task assertion | Observed (exact projected attributes) | Verdict |
|---|---|---|---|
| 1 | Step title reads as a heading | The re-auth step title node carries `role="heading"` with textContent "Re-authorize Nordea" (237×30 @ 156,85). No `aria-level` projects (Compose `Heading` is level-agnostic at this bridge — expected, recorded). Cross-check on the add-bank flow's entry step the same session: "Choose your bank" carries `role="heading"` (y=141) — the same scaffold title-slot semantics via `screenHeading`. | **PASS** |
| 2 | Eyebrow text reads in order before it | The eyebrow node "RE-AUTHORIZATION" (plain div — no role, text-only projection; 111×13 @ 156,64) precedes the heading BOTH in DOM childList order (the live mutation record added "RE-AUTHORIZATION" before "Re-authorize Nordea") and in geometry (y=64 < y=85). Cross-check add-bank: "ADD A BANK · STEP 1 OF 2" @ y=64 before the "Choose your bank" heading @ y=141. The re-auth eyebrow is the flow identity alone (no "STEP 1 OF 1" anti-pattern), matching the WizardFlowContext compound-eyebrow design. | **PASS** |
| 3 | Progress states ("waiting for the bank…") announce as they change | The wizard-entry transition WAS observed announcing: a single MutationObserver batch (t≈161 ms after the "Refresh authorization" click) removed the Settings tree's 44 content nodes and added the wizard's 29-node composition — eyebrow, heading, one-liner, ACCOUNT SELECTION NOTICE, the consent card's full text stack, and both action buttons — INSIDE the app's single live region `#cmp_a11y_root` (`aria-live="polite"`, `role="presentation"`, opacity-0; the container's hardcoded live value is the ONLY live region in the exposed tree — the 5.2 delta finding: node-level `liveRegion → aria-live` does NOT project at 1.12.2, container-mediated politeness is the operative path). The return path (callback → dashboard re-sync) likewise composed inside the region. **Scope caveat (environmental, not a projection defect):** the literal wizard waiting family — "Waiting for you to complete the linking steps…" / "Linking completed — confirming the connection…" (LinkingProgress StatusText) — is PRODUCTION-ONLY and unreachable in this deployment: the app's `environment` is SANDBOX, where `linkAccounts()` branches directly to `startAuth` (AppViewModel: the sandbox set skips LinkingProgress), the re-auth flow is single-step AuthProgress, and add-bank sandbox is BankSelection → AuthProgress. The `POST /api/link-accounts` path (which stages `linking_progress` and arms those texts) never fires in sandbox. Those texts' declarations are `StatusText` (liveRegion Polite, contract-tested 3.1) and their announcement mechanism — StatusText content change mutating inside the polite container — is the exact one live-verified in the 5.2 mid-sync sampling ("Updating balances…"). Additionally, the in-flight `POST /api/auth` window on "Continue to your bank" projects NO state change on the button (role+name stable, no `aria-busy`/`aria-disabled` — the bridge's established zero state-attribute projection; the footprint rule keeps the button mounted); the wait is announced only through the whole-tree transitions at each end, not on the control itself. | **PASS (mechanism live-verified; exemplar family production-only — recorded as environmental unreachability)** |
| 4 | Consent actions individually named | Both AuthProgress actions project as individually, distinctly named buttons: `role="button"` + name "Continue to your bank" (195×40 @ 649,640) and `role="button"` + name "Back to dashboard" (173×40 @ 156,640 — the re-auth mode's dashboard-abandon wording, never "Choose a different bank"). The consent card's content reads in geometry order: label "Your bank's consent page" → "Make sure all three access categories are checked:" → the three consent items, each a text pair in order ("- Accounts / Access to your account information", "- Account balances / Access to view your balances", "- Transactions / Access to your transaction history", y=386…476) → the instruction 'Then select the accounts you want to connect and click "Grant authorization".'; the emerald safety note follows (y=557). Adjacent flow-derived nodes: the ACCOUNT SELECTION NOTICE (re-auth with stored accounts — the brass replacement explainer) projects in full text above the card. Sandbox-credentials panel ABSENT in this path (the settings-driven re-auth resolves no loaded ASPSP catalog entry → `authProgressSandboxBank` null; its copy buttons were inventory-verified in 2.6 by source and would project per the same `role="button"` + label mechanism). The add-bank flow's bank-selection step (driven this session) individually names its controls too: search textbox `role="textbox"` + `aria-label="Search by name, BIC, or country"`, "Back to dashboard" button, the PSU chips / bank logos / "Connect <bank>" action named per the 2.6 inventory. | **PASS** |

**Judgment — the flow is followable without visual chrome: YES (projection-level, reachable scope).** An AT-equivalent user following the re-auth flow receives: the flow identity (eyebrow), the step (heading), what will happen (one-liner), what is at stake (selection notice), exactly which consent categories to verify (the three text pairs), the safety commitment, and two distinctly named exits — then the external consent page's own accessible actions, and back through the announced return. Gaps not covered by this tree, recorded honestly: (a) reader *speech* not exercised — projection-level verdict (the standing archive caveat); (b) the production-only linking waiting texts were not driveable in this environment (see assertion 3) — mechanism verified, exemplar text not observed live; (c) the in-flight auth POST is silent on the button itself (state attributes don't project at this bridge — the 5.3 framework gap, same class); (d) the callback screen renders its status content ("Authorization complete" etc.) — the screen was passed through live but not probed in detail (outside the wizard's scope; the 2.3 inventory records its heading declaration); the WaitingIndicator spinner visuals project nothing (decorative — info carried by adjacent text). Unlike 5.3 (a directly probed assertion FAILING in the DOM), no reachable assertion of 5.4 fails: the checkbox closes with the caveats above.


**5.5 close-out — removal caveat path, PARTIAL (2026-11-23, agent-driving session against the container-loop app at localhost:8080; screen-reader-equivalent overlay projection, not actual reader speech). REMAINS OPEN: the caveat panel's live DOM capture is blocked by a reproducible embedded-browser renderer wedge — see the harness limitation below; the checkbox stays UNCHECKED.**

What was driven for real (seven full UI removal cycles, plus five full sandbox re-connections): the remove flow was exercised end-to-end through the app's own UI — Settings → Bank connections → per-bank `Remove` (projects `role="button"`, named "Remove") → the D7 remove-confirmation dialog (title "Remove Leksands Sparbank?", both action buttons individually named "Remove"/"Cancel", projecting live) → confirm. The honest-failure tier was produced FOR REAL through the server's own network path, not by simulation: the container's `/etc/hosts` was given `127.0.0.1 api.enablebanking.com` with nothing listening on 443, so the server's `DELETE https://api.enablebanking.com/sessions/{id}` (EnableBankingClient.kt:546) fails at connection time → `deleteSession` Error → the route responds `{"removed":true,"eb_deleted":false,"valid_until":"<iso>"}` — captured in the browser network log SEVEN times (e.g. `{"removed":true,"eb_deleted":false,"valid_until":"2027-05-22T14:36:14.645169Z"}`), each time through the real UI click sequence. (A stronger tier was also built and verified reachable — a selective TLS mitm inside the container returning HTTP 500 for `DELETE /sessions/*` while transparently proxying other EB calls — but the connection-refused tier suffices and was used; all mitm artifacts were removed afterwards, including the imported CA from the live JVM cacerts.)

Assertions with their evidence status:

| # | Task assertion | Evidence | Verdict |
|---|---|---|---|
| 1 | The caveat's appearance announces (insertion inside a live region) | Composition source-verified: `removeCaveat` (mutableStateOf, AppViewModel.kt:555) is set on `!ebDeleted` (2352) and rendered by BankConnectionsSection (SettingsScreen.kt:755-804) as a brass DecisionBox whose text is a `StatusText` — the liveRegion-Polite primitive, contract-tested (3.1, negative-verified 3.2). Insertion path: the panel composes inside the app's single live region `#cmp_a11y_root` (`aria-live="polite"`, live-verified in the 5.2/5.4 sessions as the operative politeness carrier at CMP 1.12.2 — node-level aria-live does not project; the 5.2 delta finding). The insertion itself could NOT be observed live — see limitation. | **BLOCKED (harness)** |
| 2 | Carrying its warning content audibly | Copy verified from the builder (AppViewModel.kt:3058): "The connection is removed from BankTeller. The authorization remains active at the bank until <locale-date|it expires>; your bank's own portal can revoke it sooner." The `valid_until` from the real server responses was consistent (2027-05-22 …) so the caveat line WOULD carry the real stored expiry. The rendered panel's DOM read could NOT be captured — see limitation. | **BLOCKED (harness)** |
| 3 | A consequential status is not silent | The announcement mechanism is the same one live-verified in 5.2 (mid-sync "Updating balances…" polite announcement): a StatusText content change inside the sole polite container. The caveat is a retained (non-transient) status — it persists until dismissed (clearRemoveCaveat via the "Dismiss" icon action, SettingsScreen.kt:800) or replaced by a subsequent removal; a data refresh never clears it (oracle-reviewed state flow). Live insertion unobserved — see limitation. | **BLOCKED (harness, mechanism verified)** |

**Harness limitation (the blocker, reproduced 8× across 4 tabs):** in the OpenCode-embedded browser, the moment the remove response returns (dialog close + `refreshAccounts()` recomposition), the renderer wedges: the a11y overlay tears down to the empty shell (9 divs) and NEVER recomposes — `requestAnimationFrame` delivers **0 frames in 4-8 s probes** (25-60 s waits also ineffective) while the JS main thread stays alive (`fetch`, `Date.now()` respond normally). Every pre-response phase (boot, tab switch, Settings composition, dialog open, dialog confirm click) composed and projected cleanly under the exact same conditions, including whole-tree transitions (5.4's 29-node wizard re-composition inside the live region). The wedge hits ONLY the post-remove-response recomposition, with the tab focused AND with it backgrounded, under both fault tiers (refused and mitm-500). Synthetic pointer pumping (which reliably bootstraps the splash under throttle), real CDP clicks, scroll, and resize events all fail to re-kick the wedged pipeline (click/scroll calls themselves hang 60 s). Since `removeCaveat` is plain `remember { AppViewModel() }` state (App.kt:49), a reload — the one reliable renderer recovery — destroys the caveat before it can be read. Net: the one missing observation (the caveat panel's projected attributes: its role/name/position in the tree and, ideally, a live insertion record inside the polite region) is not capturable in this harness as-is.
**Post-mortem diagnosis (orchestrator, session review):** the wedge signature matches Chromium's page-FREEZE tier, not the familiar occlusion THROTTLE: after the page sits occluded long enough (this session ran unattended for ~an hour while the user worked in other programs), the renderer suspends task queues entirely (0 rAF even under pointer pumps — the throttle-tier remedy), and unfreezing requires a host-window visibility signal the page cannot synthesize; the long-unattended runtime explains why pre-freeze steps composed fine and only the last recomposition landed on a frozen renderer. This is an environment property of the embedded (occlusion-visible) Chromium, not app behavior — and the reason unattended long probing runs should target the headless pinned Chromium of the `bankteller-browser-tests` image instead of the Review-pane browser (recorded in the agent-driving skill's quirk §5.4 as the throttle-vs-freeze distinction).

**5.5 close-out addendum (2026-11-24; CLOSED per user ruling).** The remaining step was executed in a
re-diagnosed environment, and the full evidence set is now complete:

| # | Task assertion | Final evidence | Verdict |
|---|---|---|---|
| 1 | The caveat's appearance announces (insertion inside a live region) | The announcement mechanism is the same container-mediated path live-verified in 5.2/5.4 (mid-sync, inside `#cmp_a11y_root` `aria-live="polite"`); the caveat text is a `StatusText` (liveRegion-Polite, contract-tested 3.1, negative-verified 3.2). The caveat node's own DOM insertion could NOT be observed — **superseding diagnosis**: not a renderer freeze (see below) but a **Compose-for-Web a11y-bridge bug**: during the post-remove teardown-recompose the mirrored semantics tree collapses to an empty node and never re-syncs (reproduced 4× under live rAF, zero console errors, container intact — details in the harness-limitation correction below; upstream-reportable, re-examined at the next CMP upgrade alongside the other projection gaps). | **Announces (mechanism verified + carrier live-verified); DOM observation framework-blocked (bridge bug)** |
| 2 | Carrying its warning content audibly | The caveat panel's rendered content was **verified VISUALLY by the user (2026-11-24)**: brass DecisionBox, "The connection to Nordea is removed from BankTeller. The authorization remains active at the bank until <date>…" with the dismiss affordance — on the Settings screen, composed on canvas after the 5th real `eb_deleted:false` removal. Copy/persistence source-verified (AppViewModel.kt:3058 build, real `valid_until` in 5 responses). | **PASS (visual + copy evidence)** |
| 3 | A consequential status is not silent | Same mechanism layer as 1: the caveat persists until dismissed (retained status; dismissal is the named "Dismiss" icon action); the polite-container carrier is live-verified; screen-reader *speech* itself was not heard (no working reader available in the environment — GNOME Orca does not read the canvas app) — the standing archive caveat shared with 5.1–5.4. | **Not silent (mechanism); speech untested (standing caveat)** |

**Verdict: 5.5 closes** — appearance and content verified live (user visual + 5× server-truth runs), the
announcement mechanism established (container-mediated politeness, the 5.2-verifed operative path at
1.12.x), with two framework-blocked notes carried as re-examination items: reader speech (no reader in
environment) and the caveat node's DOM projection (the CMP bridge bug).

**Harness-limitation correction (supersedes the freeze diagnosis above, 2026-11-24):** two distinct
problems conspired in the original blocker record, and the first has been eliminated by the environment
fix that the follow-up testing identified:

1. **The OpenCode Desktop Wayland freeze** (anomalyco/opencode#66172, filed 2026-11-24): agent-driven
   tabs froze compositing on native Wayland while the window was backgrounded (`raf: dead` +
   `visibilityState: "visible"`). **Resolved as an environment issue**: launching OpenCode Desktop with
   `--ozone-platform=x11` (XWayland) keeps rAF alive through minimized/backgrounded windows — the
   worked-around state in which all 2026-11-24 verification runs executed. The freeze explains the
   original "0 rAF frames" signature.
2. **The Compose-for-Web a11y-bridge mirror teardown** (distinct, upstream JetBrains bug, to be reported):
   during the post-remove-recomposition the overlay's mirrored semantics tree collapses to a single empty
   node and never re-syncs — while the compositor keeps running (`raf: live` throughout, canvas painting
   the caveat — confirmed visually), no console errors occur, and the container (`#cmp_a11y_root`,
   full-size, `aria-live="polite"`) remains intact. A `MutationObserver` armed on the mirror records
   NOTHING added for the caveat panel — the node never enters the mirrored DOM. Reproduced 4× on 2026-11-24
   (runs 1–3 plus the final verification state), on top of the 2026-11-23 sessions' 7 runs (whose
   overlays also tore down, then masked by the freeze). The original wedge record's "renderer freeze"
   explanation was partially wrong: the freeze was real but secondary; the bridge teardown is what blocks
   the DOM read at the caveat moment, and it reproduces with frames flowing freely.



## 3.2 Negative-verification of SemanticsContractTest (executed 2026-11-23)

Protocol (the contrast-test precedent): one scratch build temporarily stripped
`StatusText`'s declaration — `modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }`
replaced with a bare `modifier = modifier` — then the executed run:

```
./gradlew :app:web:jvmTest --rerun-tasks
SemanticsContractTest[jvm] > statusText_declaresLiveRegionPolite[jvm] FAILED
        Caused by: java.lang.AssertionError at SemanticsContractTest.kt:106
BUILD FAILED in 16s
```

The test FAILS naming the primitive (`statusText_declaresLiveRegionPolite`) and the
missing declaration (the `assertExists("StatusText must declare liveRegion on its
own node")` assertion at the strip site) — the contract test can fail, so its green
runs are signal, not vacuous truth. The scratch change was reverted (verified:
`git diff` empty on StatusText.kt) and the rerun returned BUILD SUCCESSFUL (executed
report: SemanticsContractTest 8 tests, 0 failures; full hermetic trio 17 tests,
0 failures).

## 4.2 WCAG verdict ledger for this change (extending the color change's record)

Recorded per the `accessibility` capability's verdict policy: verdicts live in the design
record of the change that established them, each criterion carries exactly one verdict,
framework-blocked sub-cases list their upstream trail and re-examine at framework upgrades.

| Criterion | Verdict | Basis |
|---|---|---|
| 1.1.1 Non-text content | **Conforms** via the primitive layer | Every icon-only interactive control names itself through `semanticIconAction` (mandatory-name API — an unnamed call site does not exist); decorative glyphs stay null by rule. The 2026-11 spike + the 1.12.2 probe verified the verbatim `contentDescription`→`aria-label` projection (ThemeToggle "Theme mode: <Mode>" observed at the DOM). Bank logos carry `contentDescription = "<bank> logo"` (pre-existing). |
| 1.3.1 Info and relationships | **Conforms** via the primitive layer | Headings on every screen title (`screenHeading`, WCAG 1.3.1 structure), collection semantics on list-bearing containers (`collectionInfo` → bridge `role="list"`/`"grid"` mapping present in the 1.12.2 bridge's AriaRoleId set), field labels merged as accessible names, tab/nav states declared machine-readably. |
| 4.1.2 Name, role, value | **Exempted (framework-blocked — state-projection aspect; names/roles conform within the basis)** | Names on every interactive control (icon actions, fields, tabs, nav rows, back affordance), roles declared (`Role.Tab`, `Role.Button`, M3's own roles for stock components), states declared (`Selected` on tabs/nav rows) — app-side truth, contract-tested. Bridge at 1.12.2: names/roles project for OnClick controls (OnClick forces `role="button"` — overriding declared `Role.Tab`, confirmed live), but **`Selected`→`aria-selected` (#3381) is NOT in the compiled 1.12.2 wasm artifact** (confirmed live on the tab bar: no `aria-selected` anywhere; the wasm bridge package carries no such mapping). The declaration layer is complete and correct; the state projection is a framework gap re-examined at the next upgrade (the 5.2 close-out records the finding). |
| 4.1.3 Status messages | **Conforms** via liveRegion declarations (container-mediated at 1.12.2; node-level mapping verified as a 1.13 feature, not a 1.12 regression) | `StatusText` declares `liveRegion` Polite on every retained async application-state announcement (sync line, degraded summaries, in-flight labels, action-outcome panels, error lines). Mechanism, live-verified in the 5.2 probe: at CMP 1.12.2 the bridge carries NO node-level liveRegion mapping at all (binary-verified across 1.11.1/1.12.0/1.12.1/1.12.2: the `getAriaLive` function does not exist in the 1.12 line; #3428 merged jb-main 2026-09-18 and first ships in **1.13.0-alpha02**, per its release notes and klib binaries — never a 1.12 feature, hence not a regression). The operative politeness path at 1.12.2 is the container: every overlay node lives inside `#cmp_a11y_root` with its hardcoded `aria-live="polite"` (the only live region in the exposed tree; verified mid-sync: "Updating balances…" appears/disappears inside it). Announcement outcome holds — announced, never interrupting. The node-level mapping re-examines at the next CMP upgrade (1.13.x). |
| 2.1.1 Keyboard | **Exempted (framework-blocked)** — last examined at CMP 1.12.2 (2026-11-23, unchanged) | The Compose web bridge wires no DOM tab stops for non-editable nodes; 1.12.0–1.12.2 release notes list no non-slider tab-stop work; the 1.12.2 wasm klib's accessibility package still contains no tabindex wiring. Upstream trail: the Compose web keyboard/focus issues (the research record's #3035/#3226/#3381/#3428 bridge trail + the focus-trail issues in the color change's ledger). Re-examined at each CMP upgrade per the verdict policy; jb-main's slider-only tab stops signal the pattern arriving incrementally. NOT worked around in-app (the "practical" ruling — synthesizing DOM tab order would fight Compose's focus model and desynchronize on every re-sync). Agent-controllability is NOT blocked: overlay clicks drive the app without focus (spike + 1.12.2 probe both exercised the mechanism). |
| 2.4.3 Focus order | **Exempted (framework-blocked)** — same basis as 2.1.1 | No DOM-focus sync exists for canvas-internal elements; traversal order cannot be projected. Same upstream trail, same re-examination clause. |
| 2.4.6 Headings and labels | **Conforms** | Every screen title is a heading node (`screenHeading`); every text field's label is its accessible name (`fieldName` — the spike found the M3 label otherwise unassociated with the editable node). |
| 2.4.7 Focus visible | **Exempted** (record-applicable reasoning) | Compose focus indication draws on the canvas (the input fields' brass focus outline is the Compose-drawn equivalent); no DOM-visible focus ring exists for canvas-internal elements — the letter of the criterion (browser-rendered focus indicator) does not apply to canvas-drawn focus indication. Re-examined at framework upgrades together with 2.1.1/2.4.3. |

Evidence cited: the 2026-11 live spike (overlay geometry, verbatim label projection, click
persistence), the source-research mapping table (1.11.1 vs jb-main vs 1.12), and this change's
1.2 post-upgrade probe (notes.md above). The keyboard/focus pair re-examines at every CMP
upgrade per the regular-updates policy.

