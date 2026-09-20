// Karma config drop-in: reliability knobs + container-robustness flags for
// headless Chrome in a container.
//
// Loaded automatically by useKarma() — the Kotlin/JS plugin concatenates
// every *.js file under karma.config.d/ (relative to the project dir) into
// the generated karma.conf.js, INSIDE the plugin's `module.exports` function
// body and AFTER the plugin's own config.set() calls.
//
// CRITICAL RULES for this drop-in:
//   1. File extension must be .js (NOT .kjs) — the Kotlin 2.4.0 plugin's
//      appendConfigsFromDir filters by getExtension()=="js".
//   2. Do NOT wrap content in `module.exports = function(config) {...}`.
//      That would shadow the plugin's own module.exports and break the
//      config (file patterns, port, preprocessors, webpack, etc. would
//      never be applied). Instead, call `config.set({...})` directly —
//      `config` is already in scope because this code is concatenated INTO
//      the plugin's `module.exports = function(config) {...}` body.
//
// See: https://karma-runner.github.io/latest/config/configuration-file.html
config.set({
  // ---- reliability timeouts ----
  // Wait longer for a browser to reconnect after a disconnect (default 2000ms).
  // Gives a cold-starting headless Chrome time to come up.
  browserDisconnectTimeout: 30000,
  // Tolerate many disconnect/reconnect cycles before giving up (default 0).
  // The wasmJs target loads a large bundle (skiko.wasm ~8MB) which can cause
  // Chrome to disconnect repeatedly during webpack re-bundling between test
  // suites. The tests still pass; the disconnects are a container/webpack
  // timing artifact, not a test failure. We set this high so Karma doesn't
  // abort the run prematurely.
  //
  // IMPORTANT: when the tolerance IS exhausted, Karma silently SKIPS the
  // remaining suites and exits — the run then looks "green" while only a
  // fraction of the tests executed. Each inter-suite re-bundle can produce
  // MULTIPLE disconnect cycles (observed: 10 cycles for one large suite), so
  // this must comfortably exceed (suites × cycles-per-suite).
  browserDisconnectTolerance: 50,
  // Wait longer for any browser activity during a run (default 10000ms).
  // Guards against slow test execution on a busy host — and against long
  // webpack re-bundle windows between suites, during which the browser
  // reports no activity.
  //
  // IMPORTANT: keep this SHORT (60s). This timeout is Karma's watchdog: when
  // the browser goes silent mid-run (stalled tab, dead renderer), a short
  // noActivityTimeout restarts Chrome within a minute and the run resumes
  // from where it stopped. A LONG value (300s) does NOT make the run more
  // reliable — it makes each stall cost 5 minutes instead of 1 (observed:
  // a 300s run stalled at 3 tests for 45+ minutes with zero progress).
  browserNoActivityTimeout: 60000,
  // Keep-alive timeout for the browser socket (default 20000ms).
  browserSocketKeepTimeout: 20000,
  // Initial socket connect timeout for the client (default 20000ms).
  // Give the container time to spin Chrome up to the point where it can
  // hit /socket.io/.
  browserSocketTimeout: 60000,

  // ---- socket.io engine ping timeout ----
  // Default 5000ms is too short for cold-starting Chrome in a container.
  // Chrome must load the webpack test bundle (commons.js ~37MB + skiko.wasm
  // ~8MB) before it can respond to pings; parsing that bundle blocks the
  // tab for well over a minute. With a short pingTimeout the socket dies
  // with "reconnect failed (ping timeout)" and the run deadlocks in a
  // disconnect/reconnect loop with ZERO test progress (observed on host
  // AND in container). 300s lets the socket survive the bundle parse.
  //
  // Empirical matrix (container runs, 75 tests total):
  //   pingTimeout 300s + noActivity 60s  → 21 tests ran, ~2 tests/cycle,
  //                                        steady progress (jstest2)
  //   pingTimeout 300s + noActivity 300s → stalled at 3 tests (jstest3)
  //   pingTimeout 60s  + noActivity 300s → stalled at 3 tests (jstest4)
  //   pingTimeout 60s  + noActivity 300s → stalled at 0 tests, host (hk3)
  // The noActivityTimeout watchdog (60s) is what restarts a stalled Chrome;
  // pingTimeout (300s) is what keeps the socket alive through the parse.
  // BOTH are needed — neither alone works.
  pingTimeout: 300000,

  // ---- container-robustness flags ----
  // useChromeHeadlessNoSandbox() in build.gradle.kts already registers
  // `ChromeHeadlessNoSandbox` (base: ChromeHeadless, flags: ['--no-sandbox'])
  // and adds it to browsers[]. Because config.set() merges customLaunchers,
  // we re-declare the launcher here with the cumulative flag list:
  //   --no-sandbox             (required when running as root in a container)
  //   --disable-gpu            avoid GPU init errors in headless VMs
  //   --disable-dev-shm-usage  avoid /dev/shm exhaustion (default is half RAM)
  customLaunchers: {
    ChromeHeadlessNoSandbox: {
      base: 'ChromeHeadless',
      flags: [
        '--no-sandbox',
        '--disable-gpu',
        '--disable-dev-shm-usage'
      ]
    }
  },
  browsers: ['ChromeHeadlessNoSandbox']
});
