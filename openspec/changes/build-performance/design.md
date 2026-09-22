# Design: Build Performance

## Context

A full `:server:shadowJar` build takes ~20 minutes. Measured and verified causes (see proposal):

- `composeCompatibilityBrowserDistribution` builds **two** production webpack bundles (JS + wasmJs); the JS one alone took 1,196,981 ms in the observed log. The dual-target decision (archive/2026-07-16-foundation D3) predates WasmGC shipping in all evergreen browsers (Chrome 119+, Firefox 120+, Safari 18.2+).
- KGP defaults enable full production source maps (`devtool: 'source-map'` + `source-map-loader` over every module) — expensive on a Compose-sized module graph, and the maps ship dead in the jar.
- JS/wasmJs incremental compilation breaks after container browser-test runs: the in-container compile writes IC state with `/work`-absolute paths into the shared host `build/` tree, so the next host build trips over poisoned caches ("IC internal error: ... absent in incremental cache and klib", missing `last-build.bin`). Root cause diagnosed in D3 — not an upstream SQLDelight/Kotlin bug.
- `copyWebDist` (`server/build.gradle.kts:37-42`) is a `Copy` task; webpack emits content-hashed filenames, so stale artifacts are never overwritten nor deleted — 40 wasm files / 76 MB accumulated in `server/build/generated/resources/static/` and are packaged into every 93 MB jar.
- Container browser tests (`app/web/build.gradle.kts:187`) run `./gradlew ... --no-daemon --rerun-tasks` inside the container — a forced from-scratch rebuild (defeating both build cache and the mounted `bankteller-gradle-cache` volume), twice (js then wasmJs, serialized). Container-based testing itself is intentional (host-browser isolation after an agent killed live Firefox instances) and stays.
- `:core` has zero test sources, yet its browser test tasks appear in `build`/`check` graphs and would launch host Karma/Chrome.

Gradle parallelism, build cache, and configuration cache are already enabled. Webpack's module resolution/DCE is largely single-threaded; Terser already parallelizes. Cores cannot rescue webpack — doing less work can.

## Goals / Non-Goals

**Goals:**
- `:server:shadowJar` wall-clock drops from ~20 min to low single-digit minutes.
- Changed builds recompile only what changed (IC health restored or worked around).
- Deployed jar contains no stale artifacts and no dead source maps.
- Container browser tests execute against host-built artifacts — no in-container rebuild.
- A one-flag switch between fast prod bundles and debuggable prod bundles (user decision, Vaadin-production-flag style).
- wasmJs-only frontend (user decision) with the browser requirement documented.
- Browser tests execute exclusively in the container; the native host opt-in is removed (user decision).

**Non-Goals:**
- Replacing webpack with Vite or other bundlers (community tooling, high migration cost — revisit if Kotlin ships official Vite support, KT-48308/KT-82051).
- Changing the browser-tests container image (`Dockerfile.browser-tests`) beyond what artifact reuse requires.
- Server-side performance; runtime JVM tuning (covered by archived `local-build`).
- CI configuration (none exists).

## Decisions

### D1: Drop the `js` targets; `copyWebDist` consumes `wasmJsBrowserDistribution`
Delete the `js {}` target block and the `jsMain` source-set dependency block (`app/web/build.gradle.kts:91-94`) from `:app:web`; delete the `js {}` target from `core/build.gradle.kts:20-22` — nothing consumes `:core`'s js klib once the frontend is wasmJs-only, and it would otherwise compile a dead target in every `build`. Rewire `server/build.gradle.kts` `copyWebDist` to copy from the wasmJs distribution output. The `composeCompatibilityBrowserDistribution` task disappears from the graph (it hard-requires both targets and skips otherwise). **BREAKING**: pre-WasmGC browsers no longer supported — accepted (self-hosted app, user decision). Spec delta on `module-structure` required. **Coupling**: D1 cannot land alone — the old container-task wiring references js compile tasks (`tasks.named("compileTestKotlinJs")` at `app/web/build.gradle.kts:147` throws at configuration time) and `check` wiring references `containerJsBrowserTest` (`:249-252`); task 1.1 lands together with the 4.x rework (see Migration Plan).

*Alternatives:* keep both targets and only disable source maps (leaves ~20-min JS webpack in every build — rejected); split targets into two Gradle modules to parallelize the webpack runs (complexity without removing the work — rejected).

### D2: Production source maps off by default; `-P` flag to opt into debuggable prod bundles
Default: `webpackTask { sourceMaps = false }` on the wasmJs production task → fast bundling, no maps in the jar. A Gradle project flag (e.g. `-PprodDebug=true`, mirroring Vaadin's production-mode flag) flips the prod webpack task to debuggable settings (`devtool = "source-map"`). Local development is unaffected — `wasmJsBrowserDevelopmentRun` and dev webpack tasks keep their cheap eval-source-maps. The flag is read at task-configuration time; document it in README.

*Alternatives:* `nosources-source-map` middle ground (still pays generation cost — rejected as default; available later via the same flag if wanted); keeping maps always (rejected — the dominant remaining webpack cost after D1).

### D3: Restore incremental compilation health — root cause diagnosed
**Diagnosis (investigated, not an upstream bug):** the IC errors are caused by the container browser tests compiling inside the container while the repo is mounted at `/work` (app/web/build.gradle.kts:174,183). Those `--rerun-tasks --no-daemon` runs write IC state with `/work`-absolute paths into the *same* host `build/` directories the host build then reads. Evidence:
- `app/web/build/kotlin/compileTestKotlinJs/cacheable/` contains IC cache files with baked-in `/work/` paths (found via `grep -rl "/work/" */build/kotlin`).
- A host-only probe (touch one source in `:app:web`, recompile `:app:web:compileKotlinJs :core:compileKotlinJs`) produces **no** new `.kotlin/errors` entries — host IC is healthy when not preceded by a container run.
- The failing paths in the error logs (`compileTestKotlinJs` cacheable dir, `compileKotlinJs/cacheable/last-build.bin`) are exactly the dirs the container compile writes; the SQLDelight-generated `BankTellerDatabase.kt` is merely the first file the poisoned cache lookup trips over — SQLDelight itself is not at fault.
- Evidence coverage: direct `/work/`-path findings cover the `compileTestKotlinJs` IC state; the wasmJs `last-build.bin` failure is attributed to the same mechanism by inference (the same container run compiles both targets). D5 removes the trigger for both regardless.

**Fix (two parts):**
1. **D5 is the cure**: container tests stop compiling entirely (execute-only against host-built outputs) → no process with a foreign root path ever writes IC state again. This removes the trigger permanently.
2. **One-time cleanup**: delete the poisoned IC state so the first post-D5 build starts clean — `./gradlew clean` or at minimum `rm -rf app/web/build/kotlin core/build/kotlin` (host paths then repopulate the caches).

**Success criterion:** a one-line source change triggers an incremental (not full) `compileKotlinWasmJs` round; `.kotlin/errors` stays empty across a container-test cycle followed by a host build. Fallback (only if an independent IC issue ever surfaces): rely on the Gradle build cache — unchanged inputs already hit `FROM-CACHE`, and the webpack task is `@CacheableTask`.

### D4: `copyWebDist` becomes `Sync`
`Copy` → `Sync` in `server/build.gradle.kts` — Gradle's mirror semantics deletes stale content-hashed files from `server/build/generated/resources/static/`. One-time cleanup of the 76 MB of accumulated artifacts happens automatically on the first `Sync` run. Jar shrinks accordingly.

### D5: Container browser tests reuse host artifacts
Keep the container execution model (pinned headless Chromium, host isolation — intentional). **Wiring — settled, not open:** running the Kotlin plugin's `*BrowserTest` task inside the container is ruled out by documented prior failure (app/web/build.gradle.kts:134-138, 155-164): that task invokes Karma through the plugin's worker-daemon path, which fails to trigger karma-webpack bundling in-container (Chrome connects, never receives the bundle → ping timeout). Direct Karma invocation is the proven path. Therefore: the host builds the test bundle (the existing `buildTaskPath` compile/sync tasks, no `--rerun-tasks`), the container mounts the repo as today and runs only the Karma phase (direct `node karma start` against the mounted bundle, incl. the `HOST_REPO_ROOT`→`/work` rewrite). This removes the IC-corruption trigger (see D3) and the 300s-ping-timeout flakiness pressure from double full builds.

**Spike scope (narrow, task 4.1):** (a) *karma.conf.js source* — it is emitted only by the host-side `*BrowserTest` task, which this change never launches; a stale copy is not self-contained (a fresh checkout has no `build/js/`), so the repo must control the config itself: checked-in or generated by a custom task with relative paths (which also eliminates the `HOST_REPO_ROOT`→`/work` sed rewrite). (b) *Node binary source* — the container currently uses the Gradle-downloaded Node from the `bankteller-gradle-cache` volume (`/root/.gradle/nodejs/...`); with no Gradle inside the container, either the browser-tests image bundles Node or the volume remains solely to provide that binary.

**Dead wiring removed with D5:** the in-container Gradle Phase 1, `--tmpfs /work/.gradle`, the `BANKTELLER_BROWSER_TESTS_CONTAINER=1` env var and both its consumers (native-task re-enabling at `app/web/build.gradle.kts:26-34` — moot after D6's opt-in removal — and the yarn-lock relaxation at root `build.gradle.kts:13-39`).

### D6: Skip `:core` browser tests; remove the native host opt-in
`:core` has no test sources; its wasmJs browser test tasks in `build`/`check` graphs launch Karma/Chrome for nothing (and historically risked killing a running browser). Disable them (e.g. `testTask { enabled = false }` guarded on empty test source dirs). Additionally (user decision): remove the `-PnativeBrowserTests=true` opt-in entirely — browser tests execute exclusively in the container. The native `*BrowserTest` tasks are disabled unconditionally, and the enabling wiring (`inInnerContainerBuild` / `runBrowserTestsInContainer`, `app/web/build.gradle.kts:26-34`) is deleted along with its AGENTS.md documentation.

## Risks / Trade-offs

- **[BREAKING] Pre-WasmGC browsers can't run the app** → Accepted by user; requirement (Chrome 119+/Firefox 120+/Safari 18.2+) documented in README and `module-structure` spec delta.
- **IC fix depends on D5 landing** → The diagnosed root cause (container runs writing `/work`-path IC state into shared `build/` dirs) is removed by D5; until then, one-time cache cleanup (task 6.1) restores health temporarily. If an independent IC issue ever surfaces post-D5, fallback: build cache covers unchanged inputs; `.kotlin/errors` monitoring stays. The biggest wins (D1, D2) don't depend on D3.
- **Container-test rework changes a setup agents recently struggled with** → Mitigation: keep the container image and Karma invocation unchanged where possible; verification task runs the container test task end-to-end; the pre-change flow remains recoverable by reverting the D5 commit if artifact reuse proves flaky.
- **D1 cannot land alone** → removing the `js` target breaks Gradle configuration while the old container wiring still references js tasks (`tasks.named("compileTestKotlinJs")` at `app/web/build.gradle.kts:147` throws at configuration time); tasks 1.1 and 4.2 land as one change (see Migration Plan).
- **No native host-browser escape hatch after opt-in removal** → Mitigation: the container image is the single supported test path (one script to build); developers without a container engine skip browser tests rather than risk their host browser.
- **`Sync` deletes files Gradle doesn't know about** → Mitigation: the directory is build output (`server/build/generated/`), fully Gradle-owned; nothing else writes there.
- **`-PprodDebug` flag changes task inputs** → Mitigation: flag value is a task input, so build cache keys stay correct across flag toggles.

## Migration Plan

1. One-time IC cleanup first (task 6.1) — purge the poisoned caches so no build in the sequence compiles against them.
2. Land D1+D2+D4+D5+D6 as one coupled build-script change — D1 cannot land alone (the old container wiring references js tasks and fails at configuration; see Risks). This delivers the wall-clock win.
3. D3 is verification-only after that (tasks 6.2–6.4): the diagnosis is done; confirm IC health holds across a container-test → host-build cycle.
4. Rollback: each decision is an isolated build-script change; revert per-decision (D1 and the D5 rework revert together). No runtime/data impact anywhere.

## Open Questions

- ~~D5 exact wiring (Gradle test task in-container vs. direct Karma invocation)~~ — settled in D5: the plugin's `*BrowserTest` task is ruled out by its documented in-container worker-daemon failure; direct Karma against host-built bundles is the path. Remaining spike details (karma.conf.js source, Node binary source) are scoped in D5 / task 4.1.
- ~~Whether the wasmJs IC `last-build.bin` failure disappears once `--rerun-tasks` runs stop (D5)~~ — answered by the D3 diagnosis: the container compile's `/work`-path IC state is the cause; D5 removes it. Verification lives in task 6.3, not here.
