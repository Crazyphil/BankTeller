// Repo-controlled Karma config for containerized wasmJs browser tests
// (build-performance D5 / task 4.2).
//
// The Kotlin plugin only generates karma.conf.js when its native
// *BrowserTest task runs — which this project never does (tests execute
// exclusively via :app:web:containerWasmJsBrowserTest). This file replaces
// the generated config for the container run, with two differences:
//   1. All paths are container paths (/work/...) or resolved from
//      KOTLIN_TOOLING_DIR — no host-absolute paths baked in, so no sed
//      rewriting is needed.
//   2. The reliability knobs from karma.config.d/ci.conf.js are merged in
//      directly (that drop-in is only concatenated into the GENERATED
//      config, which is never produced in the container-only workflow).
//
// Shape mirrors the KGP-generated config (KotlinKarma.kt writeConfig,
// Kotlin 2.4.0) — if Karma/webpack behavior breaks after a Kotlin upgrade,
// diff against a freshly generated build/wasm/packages/*/karma.conf.js.

module.exports = function (config) {
  // Kotlin npm tooling node_modules (karma, webpack, kotlin-web-helpers),
  // set by containerWasmJsBrowserTest: the yarn hash dir changes with
  // dependency versions, so it must be resolved at runtime, not baked in.
  const tooling = process.env.KOTLIN_TOOLING_DIR;
  if (!tooling) {
    throw new Error("KOTLIN_TOOLING_DIR is not set — containerWasmJsBrowserTest must set it to the Kotlin npm tooling node_modules dir");
  }
  const karmaRunner = tooling + "/kotlin-web-helpers/dist/kotlin-test-karma-runner.js";

  config.set({
    singleRun: true,
    autoWatch: false,
    // Container mount point of the repo (see containerWasmJsBrowserTest).
    basePath: "/work/build/wasm/packages/BankTeller-app-web-test",
    files: [
      karmaRunner,
      // Relative to basePath; written by containerWasmJsBrowserTest before
      // Karma starts (KGP's createLoadWasm equivalent).
      "static/load.mjs",
    ],
    frameworks: ["webpack-output", "mocha", "webpack"],
    client: {
      args: [],
    },
    browsers: ["ChromeHeadlessNoSandbox"],
    customLaunchers: {
      ChromeHeadlessNoSandbox: {
        base: "ChromeHeadless",
        flags: [
          "--no-sandbox", // required when running as root in a container
          "--disable-gpu", // avoid GPU init errors in headless VMs
          "--disable-dev-shm-usage", // avoid /dev/shm exhaustion
        ],
      },
    },
    customContextFile: tooling + "/kotlin-web-helpers/dist/static/context.html",
    customDebugFile: tooling + "/kotlin-web-helpers/dist/static/debug.html",
    failOnFailingTestSuite: false,
    failOnEmptyTestSuite: false,
    reporters: ["karma-kotlin-reporter"],
    preprocessors: {
      [karmaRunner]: ["webpack", "sourcemap"],
      "static/load.mjs": ["webpack", "sourcemap"],
    },
    proxies: {},
    webpackCopy: [],
  });

  config.plugins = config.plugins || [];
  config.plugins.push("kotlin-web-helpers/dist/karma-kotlin-reporter.js");

  config.loggers = [
    {
      type: "kotlin-web-helpers/dist/tc-log-appender.js",
      layout: { type: "pattern", pattern: "%[%d{DATETIME}:%p [%c]: %]%m" },
    },
  ];

  config.plugins = config.plugins || [];
  config.plugins.push("kotlin-web-helpers/dist/karma-webpack-output.js");

  // ---- reliability timeouts (from karma.config.d/ci.conf.js — empirically
  // required container values; see that file for the full rationale matrix) ----
  config.set({
    browserDisconnectTimeout: 30000,
    browserDisconnectTolerance: 50,
    browserNoActivityTimeout: 60000,
    browserSocketKeepTimeout: 20000,
    browserSocketTimeout: 60000,
    pingTimeout: 300000,
  });

  // webpack config (mirrors KGP's createWebpackConfig for wasm)
  function createWebpackConfig() {
    const webpackConfig = {
      mode: "development",
      resolve: {
        modules: ["node_modules"],
      },
      plugins: [],
      module: {
        rules: [],
      },
      resolveLoader: {
        modules: ["node_modules", process.env["KOTLIN_TOOLING_DIR"]],
      },
    };

    // source maps
    webpackConfig.module.rules.push({
      test: /\.m?js$/,
      use: ["source-map-loader"],
      enforce: "pre",
    });
    webpackConfig.devtool = false;
    webpackConfig.ignoreWarnings = [
      /Failed to parse source map/,
      /Accessing import\.meta directly is unsupported \(only property access or destructuring is supported\)/,
    ];

    // optimization
    webpackConfig.optimization = webpackConfig.optimization || {
      splitChunks: false,
    };

    // teamcity error reporting
    (function (webpackConfig) {
      const tcErrorPlugin = require("kotlin-web-helpers/dist/tc-log-error-webpack");
      webpackConfig.plugins.push(new tcErrorPlugin());
      webpackConfig.stats = webpackConfig.stats || {};
      Object.assign(webpackConfig.stats, webpackConfig.stats, {
        warnings: false,
        errors: false,
      });
    })(webpackConfig);

    webpackConfig.experiments = {
      topLevelAwait: true,
    };

    // wasm source-map patching (see KGP KotlinWebpackConfig.appendTo)
    (function (webpackConfig) {
      const webpack = require("webpack");

      // https://github.com/webpack/webpack/issues/12951
      const PatchSourceMapSource = require("kotlin-web-helpers/dist/webpack-5-debug");
      webpackConfig.plugins.push(new PatchSourceMapSource());

      webpackConfig.plugins.push(
        new webpack.SourceMapDevToolPlugin({
          moduleFilenameTemplate: "[absolute-resource-path]",
        })
      );
    })(webpackConfig);

    return webpackConfig;
  }

  config.set({ webpack: createWebpackConfig() });
};
