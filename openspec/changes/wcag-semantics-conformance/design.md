# Design

## Context

The color-conformance change (archived 2026-11-21) established the WCAG 2.2 AA frame and the `accessibility` capability with its verdict policy, the contrast guarantee, and the wash-mounting rule. The semantic/interaction side was left open. Since then, two evidence-gathering efforts grounded this change:

1. **Live spike (2026-11-21/23)**: the running app inspected through the ComposeViewport shadow root (the a11y overlay `cmp_a11y_root` lives inside; `mode: OPEN`, so `host.shadowRoot` reaches it — the earlier "canvas exposes nothing" conclusion from a prior session was a probing artifact, not product truth). Fresh-boot findings: mirrored nodes carry **real pixel geometry** (`setSizeAndPosition` from `boundsInRoot` — an initial 0×0 reading was a dead-viewport measurement artifact, re-verified at 368×40 etc.); `contentDescription` projects **verbatim** to `role`/`aria-label` (ThemeToggle's "Theme mode: System" observed); TextFields expose as `role="textbox"` with **no accessible name** (the M3 label renders as a separate unassociated node); the overlay's click listeners **work** — clicking the ThemeToggle's overlay node executed the real toggle cycle and persisted `bankteller-theme` (agent-controllability proof); keyboard Tab traversal does nothing (focus stays on body), and the CDP-level accessibility snapshot shows empty generics (opacity-0 + shadow-root pruning).

2. **Source research (compose-multiplatform-core v1.11.1 shipped sources vs jb-main, issue trail #3035/#3226/#3381/#3428/#3235, CMP-10172/#10413/#10226)**: the whole bridge lives in `webMain` — **shared source for the JS and wasmJs targets** (answering the raised question: a legacy-JS build would project identical semantics; switching targets changes nothing a11y-wise, so wasmJs stays). The 1.11.1 mapping table is known exhaustively (Text, ContentDescription→aria-label, OnClick→listener+button-role, Role set incl. Tab/Heading/textbox, geometry per sync); `Disabled`/`Selected`/`LiveRegion`/`StateDescription`/heading-level are **unmapped in 1.11.1** (superseded version note: originally recorded as arriving with 1.12 — the implementation binary-verified #3381/#3428 first ship in 1.13, per D2's correction); the container is 0×0 until 1.12 (CMP-10172); **no tabindex or focus-sync wiring exists for non-editable nodes in 1.11.1 — and jb-main adds tab stops only for sliders**, making keyboard traversal the framework's gap; the hidden text backing-input materializes only when a field gains Compose focus; `isA11YEnabled` (default true) toggles the whole mirror; the container carries a hardcoded `aria-live="polite"`.

## Goals / Non-Goals

Goals:
- Every app-declarable semantic (names, roles, states, headings, announcements) declared and bridged — the app's half of the contract, done completely.
- Enforcement that outlives this change: JVM semantic contracts for the primitive layer + a declaration duty for anything the primitives don't cover.
- The CMP ≥1.12 upgrade as this change's first task (user ruling: regular dependency updates) so the primitive layer lands on the richer bridge from day one.
- Verdicts recorded for every criterion this work resolves or discovers — including the honest framework-blocked records.

Non-Goals:
- Keyboard traversal/focus remediation — framework-blocked (source-verified; upstream trail recorded). No in-app workaround: synthesizing DOM tab-order outside Compose's focus system would fight the technical base (the "practical" ruling).
- Screen-reader end-to-end validation (ChromeVox/NVDA) — the bridge's projection is verified at the DOM/source level; a manual verification task exercises a reader as a smoke check, but perfecting reader behavior under framework gaps is out of scope.
- Any visual change — semantics are non-visual; if a primitive forces a perceptible change, that is a defect in the primitive.
- Refactoring screens beyond declaration-level edits — this is not a component rewrite.
- Compose HTML / legacy JS target evaluation — closed by research (shared bridge source); recorded, not re-litigated.

## Decisions

### D1 — Primitives-first, not per-screen declarations

**Decision**: the change builds a semantic primitive layer (`ui/components/semantics/`) — SemanticIconAction, StatusText, heading, tab/nav-row semantics, field-name merging, list/collection semantics on list-bearing containers — and migrates screens onto it, rather than sprinkling `Modifier.semantics` per call site.

**Rationale**: conformance by construction (the user's own architectural instinct: "build the app from such components and we're more or less automatically compliant"); the JVM contracts test the primitive layer once instead of every screen; new development inherits conformance by default, leaving the declaration duty a small-surface fallback.

**Alternatives**: per-screen declarations — rejected: unreachable enforcement (nothing stops a new screen from forgetting), untestable in aggregate, and the review burden lands on every future change instead of the primitive layer once.

### D2 — The framework version is the a11y feature-flag (CMP ≥1.12 first)

**Decision**: upgrade to CMP ≥1.12 as the first implementation task, then build the primitives on the richer bridge.

**Rationale**: the container geometry fix (CMP-10172) and the 1.11.1-to-1.12 mapping consolidation arrive with 1.12 — building `StatusText` and tab semantics against 1.11.1 would mean writing declarations that partially don't bridge and re-verifying after upgrade anyway. **Version-attribution correction (implementation finding, binary-verified)**: node-level `liveRegion -> aria-live` (#3428) and `aria-selected`/`aria-disabled`/`aria-readonly` (#3381) first ship in **1.13** (confirmed against the 1.12.0-1.12.2 klibs: no `getAriaLive`, no `aria-selected` mapping; the artifacts' original "1.12" attribution was wrong). At 1.12.x the operative paths are container-mediated: StatusText announcements ride the overlay container's hardcoded `aria-live="polite"` (live-verified mid-sync), and declared states (tab/nav `Selected`) remain app-side truth — contract-tested in the Compose tree but NOT projected to the DOM until 1.13. User policy: regular dependency updates.

**Verification plan**: full `jvmTest`, wasmJs compile, `containerWasmJsBrowserTest` (if container engine available), dev-boot smoke with a fresh live probe of the overlay (re-run the spike's geometry + label assertions against the upgraded bridge — the spike's probe scripts become the post-upgrade verification). A regression in the contrast test or visual surface fails the task.

**Risk**: the 1.11→1.12 jump may carry breaking changes (the 1.12 release notes list v2 dialog API changes on desktop; scroll-internals rework 1.11 may still be settling). Mitigation: the upgrade task is isolated (one commit), the contrast test + browser tests guard the surface, and any breakage is scoped to the bump, not entangled with the semantics migration.

### D3 — Field names via semantics merging, not native label elements

**Decision**: the field wrapper merges the label into the editable node's semantics (label text as the field's accessible name via the semantics-tree merge: a wrapper Box around the field's label composable and the field, merging description into the editable node) rather than trying to bridge a DOM label/`for` association (impossible: the bridge mirrors the Compose tree's shape; no `for` mechanism exists).

**Rationale**: the spike found `role="textbox"` with no name — the fix must live in what the bridge can project: the semantics node itself. Merging is the Compose-idiomatic mechanism (the same merge that makes M3 buttons name themselves from their content).

### D4 — Keyboard/focus: verdict, not workaround

**Decision**: 2.1.1/2.4.3 record as *exempted (framework-blocked)* with the upstream trail; 2.4.7 *exempted* with reasoning (Compose focus indication draws on canvas; no DOM focus ring exists for canvas-internal elements — the input fields' brass focus outline is the Compose-drawn equivalent). No synthetic tab-order injected via the overlay (overriding `tabindex` on mirrored nodes from app JS would be possible — the overlay is reachable DOM — but it fights Compose's focus model, desynchronizes on every re-sync, and breaks under the framework's own future fix).

**Rationale**: the "practical" ruling from the conformance frame — no excessive effort fighting the technical base. The re-examination clause this change's own delta adds to the conformance target (per-upgrade re-examination of framework-blocked verdicts) makes revisiting automatic: jb-main already adds slider tab stops, so the pattern is arriving incrementally upstream; when non-slider tab stops land, the verdict flips and a follow-up change implements traversal.

**Agent-controllability is NOT blocked**: the spike proved overlay-click drives the app (no focus needed — action listeners are registered on the mirrored nodes). This is recorded as the change's second beneficiary: enforcing the declaration layer simultaneously makes the app agent-readable/writable. The user-added deliverable extends this into institutional memory: a workspace skill (`.agents/skills/agent-driving/SKILL.md`) capturing the tested driving method — shadow-root path, overlay reads, node clicks, the environment quirks (Tab dead, CDP snapshot blind, HEAD 404s, hidden-pane 0×0) — so future agents inherit the operational knowledge instead of re-deriving it (the `agent-driving` capability; born from watching the implementation agent relearn the spike's lessons at runtime). Its staying-current rides the same upgrade event as the framework-blocked verdicts' re-examination: the change that performs a Compose upgrade re-verifies the skill's claims per its living-document scenario.

### D5 — Enforcement: JVM contracts, mirroring the contrast-test culture

**Decision**: `SemanticsContractTest.kt` in the existing `:app:web` test-only JVM target (registered by amending the module's `jvmTest` include filter, which currently whitelists only the color-era hermetic pair — without the filter amendment the new test is silently excluded) asserts each primitive's declared semantics (role, name, state, liveRegion presence, and collection semantics on the list-bearing containers) via compose's on-JVM semantics tree / ui-test API. Primitives instantiate in test harnesses with representative parameters; their semantics trees are asserted structural (node exists with role/name/liveRegion/collection) rather than pixel-level.

**Shape**: the assertions declare the CONTRACT, not bridge behavior — the JVM tree is the Compose-side truth (what the bridge mirrors); bridge behavior was verified by the live spike and stays a manual-verification concern (bridge internals are upstream code).

Collection semantics are contract-tested like the other primitives — the migrated container composables (the account card row, the accounts list) instantiate in the harness and their collection semantics are asserted (D1's enumeration includes list/collection as a primitive class; the sweep task 2.6 covers the screens' adoption, the contract test fixes the container-level declaration).

**Alternative rejected**: DOM-level bridge testing (instantiate the real browser bridge and assert DOM output) — that's an integration test against upstream code, brittle across versions and impossible in the hermetic JVM target.

### D6 — The declaration duty as the review-gate half

**Decision**: the `accessibility` capability gains the UI declaration duty (spec-level requirement): new-surface changes declare primitive usage + async-announcement behavior; openspec-reviewer verifies completeness as part of the existing review cycle. Primitive-composed surfaces satisfy the STRUCTURAL duty by construction (duty's small surface); the async-announcement declaration is never waived by composition (StatusText placement is per-surface judgment) — matching the accessibility delta's carve-out.

**Rationale**: the user's two-channel enforcement ruling — tests where testable (primitives, D5), spec preconditions where not (novel patterns, D6). The existing reviewer infrastructure (which caught the color change's compound-mount subtlety) is the natural enforcement point.

## Risks / Trade-offs

- [CMP 1.12 upgrade introduces regressions (visual, scroll, or behavior)] → Mitigation: isolated bump task, full test suite + spike-probe re-verification; contrast test guards the color surface; open an issue trail if bridge behavior shifts and re-run the live probe.
- [Framework-blocked criteria age badly if upstream fixes land unnoticed] → Mitigation: the verdict records list the upstream issues; the re-examination clause ties verdicts to upgrade events (which happen per the regular-update policy).
- [liveRegion declarations spam when over-applied (every status change announced like a to-do list)] → Mitigation: StatusText is for async application-state changes (sync lines, busy states), not every text that happens to change; the design record states politeness=Polite and the "announce state changes the user can act on or that confirm actions" bar; over-use is the reviewer's call under the declaration duty.
- [Semantics merging on text fields surprises the touch/click behavior] → Mitigation: semantic merging is non-interactive metadata; only the merge of CONFIGURATION is at play — no handler rewiring; the manual verification includes normal keyboard flow through the login form (FocusRequester paths must still work).
- [Primitives over-abstract and fight the design system's existing components] → Mitigation: primitives wrap/extend the existing components-where-possible (SemanticIconAction wraps IconButton; StatusText is a retrofit of the existing sync-line/indicator composables' internals), not a parallel component universe; the visual output must be pixel-identical.
- [Contract tests assert structure, not user experience — a declared-but-ugly-for-AT experience passes tests] → Mitigation: the manual verification section includes a screen-reader smoke pass; the duty's review half catches semantic nonsense (a wrong name is a declaration error, not a missing one) to the extent reviews can.

## Migration Plan

Pure additive UI-semantics + dependency bump; no data, API, or server changes. Staged: (1) CMP bump behind its own verification; (2) primitive layer + contracts test; (3) screen migrations screen-by-screen (each independently mergeable). Rollback of any stage is a revert; the final state leaves no legacy code paths (the primitives replace ad-hoc declarations only, and existing ThemeToggle contentDescription remains as the already-conformant example that migrates into SemanticIconAction).

## Open Questions

- ~~Whether Compose exposes heading LEVELS (`aria-level`)~~ **Answered by implementation (1.12.2, live-verified)**: heading projects level-agnostically — `role="heading"` carries no `aria-level` at 1.12.x (observed on the login headline, dashboard greeting, wizard titles). The primitives' levelless `heading()` declaration remains correct; `aria-level` re-examines at the 1.13 upgrade alongside the other projection gaps.
- Whether the click-on-overlay mechanism works for ALL interactive controls or only those with OnClick semantics (e.g. — radio buttons' selection is a SetSelected action, not OnClick) — the agent-driving manual verification will map what's clickable via overlay vs. what needs future action-broker work; recorded as a spike-adjacent discovery, not a blocker (agent-controllability is a beneficiary, not a conformance requirement).
