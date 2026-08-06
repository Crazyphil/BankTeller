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
  browserDisconnectTolerance: 10,
  // Wait longer for any browser activity during a run (default 10000ms).
  // Guards against slow test execution on a busy host.
  browserNoActivityTimeout: 60000,
  // Keep-alive timeout for the browser socket (default 20000ms).
  browserSocketKeepTimeout: 20000,
  // Initial socket connect timeout for the client (default 20000ms).
  // Give the container time to spin Chrome up to the point where it can
  // hit /socket.io/.
  browserSocketTimeout: 60000,

  // ---- socket.io engine ping timeout (THE root-cause knob) ----
  // Default 5000ms is too short for cold-starting Chrome in a container.
  // Chrome must load the webpack test bundle before it can respond to pings;
  // in a Podman container this routinely exceeds 5s. When the server doesn't
  // receive a pong within pingTimeout ms, engine.io-client fires
  // disconnect("ping timeout"), which is the exact error we were seeing.
  // Raising browserDisconnectTimeout alone only delays the inevitable —
  // this is the knob that prevents the disconnect in the first place.
  pingTimeout: 60000,

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
