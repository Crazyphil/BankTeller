This is a Kotlin Multiplatform project targeting Web, Server.

* [/app/web](./app/web/src) is the Compose Multiplatform web frontend (wasmJs only).
  It contains several subfolders:
    - [commonMain](./app/web/src/commonMain/kotlin) is for code that’s common to all targets.
    - [wasmJsMain](./app/web/src/wasmJsMain/kotlin) is for wasmJs platform specifics.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and
options:

- Server: `./gradlew :server:run`
- Web app: `./gradlew :app:web:wasmJsBrowserDevelopmentRun`

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Server tests: `./gradlew :server:test`
- Web tests (containerized): `./gradlew :app:web:containerWasmJsBrowserTest`

### Building the container image

Host prerequisites:

- JDK 21
- On Debian/Ubuntu: `libatomic1` (required by the Kotlin/JS Node toolchain during the build):
  `sudo apt-get install libatomic1`

Build the deployment image with a single command:

```bash
scripts/build-image.sh
```

This builds the fat JAR on the host (`./gradlew :server:shadowJar`), builds the
`bankteller:latest` container image (Podman preferred, Docker fallback), and
automatically prunes dangling (untagged) images after each successful build, so
repeated rebuilds do not accumulate stale container layers.

To reclaim historical storage beyond dangling images, prune manually:

```bash
podman system prune        # or: docker system prune
podman volume prune        # or: docker volume prune
```

The image sets `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"` so the JVM heap
sizes relative to the container's memory limit. Override it via the environment
(e.g. in `docker-compose.yml` or `docker run -e JAVA_TOOL_OPTIONS=...`).

### Production source maps

Default production builds are fast and lean — the wasmJs production webpack task
emits no source maps. Pass `-PprodDebug=true` to build a debuggable,
source-mapped production bundle. Local development tasks are unaffected.

The served frontend is wasmJs-only and requires a WasmGC-capable browser:
Chrome/Edge 119+, Firefox 120+, Safari 18.2+.

Browser tests run in their dedicated container image
(`scripts/build-browser-tests-image.sh`, `./gradlew :app:web:containerWasmJsBrowserTest`).

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack
channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).