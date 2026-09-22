# Build Performance

## Why

A full build (`./gradlew :server:shadowJar`) takes ~20 minutes, dominated by `jsBrowserProductionWebpack` ("webpack 5.101.3 compiled with 2 warnings in 1196981 ms"). Investigation found the cost is structural, not incidental: the `composeCompatibilityBrowserDistribution` task builds **two** full production webpack bundles (JS + wasmJs) per build; production source maps are enabled by default; JS/wasmJs incremental compilation breaks after container browser-test runs (the in-container compile writes IC state with `/work`-absolute paths into the shared host `build/` tree — root cause diagnosed in design D3, not an upstream bug); `copyWebDist` uses `Copy` semantics so content-hashed stale artifacts (49 wasm files, ~93 MB at review time) accumulate into every jar; and the container browser tests force a full `--rerun-tasks` rebuild inside the container instead of reusing host-built artifacts. The original JS+wasmJs dual-target decision (archive/2026-07-16-foundation, D3) was made when Compose wasmJs was Beta and pre-WasmGC browser support was a concern — both now obsolete (WasmGC shipped in Chrome 119+, Firefox 120+, Safari 18.2+).

## What Changes

- **Drop the `js` target** from `:app:web`; ship wasmJs-only. Rewire `copyWebDist` to consume `wasmJsBrowserDistribution` instead of `composeCompatibilityBrowserDistribution`. **BREAKING**: browsers without WasmGC (pre-Chrome 119 / Firefox 120 / Safari 18.2) can no longer run the app.
- **Disable production source maps by default**, controlled by the Gradle project flag `-PprodDebug=true` (Vaadin-production-flag style; default off = fast, small bundles, setting it enables debuggable prod bundles). Local development continues to use the existing development tasks, which are unaffected.
- **Restore JS/wasmJs incremental-compilation health**: the root cause is diagnosed (container test runs poison the shared IC state with `/work`-absolute paths — design D3); the fix is execute-only container tests (below) plus a one-time cache cleanup, so changed builds recompile only what changed.
- **Change `copyWebDist` from `Copy` to `Sync`** so stale content-hashed artifacts are removed from `server/build/generated/resources/static/` and no longer bloat the jar.
- **Rework the container browser tests to reuse host-built artifacts**: the inner container run executes tests against the already-built outputs instead of rebuilding everything with `--rerun-tasks`. Container-based testing itself (host-browser isolation) is intentional and stays.
- **Skip `:core` browser-test tasks with no test sources and drop `:core`'s dead `js` target** so `build`/`check` neither launches Karma/Chrome nor compiles an unconsumed js klib for nothing. **Remove the `-PnativeBrowserTests` host opt-in** — browser tests execute exclusively in the container.

## Capabilities

### New Capabilities

- `build-pipeline`: Build-time behavior — prod source-map flag, incremental compilation health, artifact sync semantics, container-test artifact reuse, and exclusive container-based browser testing (no native host opt-in).

### Modified Capabilities

- `module-structure`: The `:app:web` module requirement changes from "wasmJs + JS browser targets" to "wasmJs browser target only"; the served distribution changes from the compatibility bundle to the wasmJs distribution; a new requirement pins `:core` to jvm + wasmJs targets.

## Impact

- **Code/config**: `app/web/build.gradle.kts` (target removal incl. `jsMain` source-set block, webpack flag, container test tasks, native opt-in removal), `server/build.gradle.kts` (`copyWebDist` → `Sync`, wasmJs source), `core/build.gradle.kts` (drop `js` target, disable empty browser tests), root `build.gradle.kts` (remove container-only yarn-lock relaxation), `README.md` / `AGENTS.md` / `VISION.md` (command and strategy doc cleanup).
- **Spec deltas**: `module-structure` (target requirement), new `build-pipeline` capability.
- **Browser support**: **BREAKING** — WasmGC-capable browsers only (Chrome/Edge 119+, Firefox 120+, Safari 18.2+). Acceptable for a self-hosted app.
- **Unaffected**: Server runtime behavior, auth, database, onboarding flows; the browser-tests container image itself; the `local-build` image flow (archived change) — `scripts/build-image.sh` keeps calling `:server:shadowJar`, which just gets much faster.
- **Risks**: Dropping `js` removes the pre-WasmGC fallback (accepted); D1 is hard-coupled to the container-test rework (removing the target breaks Gradle configuration while the old container wiring references js tasks — they land together); removing the native opt-in means browser tests require a working container engine (accepted — the container image is the supported test path). The IC fix is diagnosis-backed, not upstream-dependent; if an independent IC issue ever appears, the fallback is relying on the Gradle build cache.
