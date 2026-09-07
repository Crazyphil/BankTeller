This is a Kotlin Multiplatform project targeting Web, Server.

* [/app/shared](./app/shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
    - [commonMain](./app/shared/src/commonMain/kotlin) is for code that’s common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
      For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
      the [iosMain](./app/shared/src/iosMain/kotlin) folder would be the right place for such calls.
      Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./app/shared/src/jvmMain/kotlin)
      folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and
options:

- Server: `./gradlew :server:run`
- Web app:
    - Wasm target (faster, modern browsers): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
    - JS target (slower, supports older browsers): `./gradlew :app:webApp:jsBrowserDevelopmentRun`

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Server tests: `./gradlew :server:test`
- Web tests:
    - Wasm target: `./gradlew :app:shared:wasmJsTest`
    - JS target: `./gradlew :app:shared:jsTest`

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

Browser tests are unaffected: they still run in their dedicated container image
(`scripts/build-browser-tests-image.sh`, `./gradlew :app:web:containerJsBrowserTest`).

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack
channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).