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

### Building the container image

The deployment image is a copy-only runtime image: the fat JAR is built on the
host (reusing the `~/.gradle` cache across rebuilds) and then copied into a
slim JRE image. This avoids re-downloading Gradle dependencies and the Kotlin/JS
Node toolchain into fresh container layers on every rebuild.

**Host prerequisites**

- JDK 21 (the Gradle wrapper pins the build).
- On Debian/Ubuntu, `libatomic1` (required by Node.js, used by the Kotlin/JS and
  wasmJs webpack tasks):

  ```sh
  sudo apt-get install -y libatomic1
  ```

- Podman (preferred) or Docker.

**One-command flow**

```sh
scripts/build-image.sh
```

This runs `./gradlew :server:shadowJar --no-daemon`, builds the image tagged
`bankteller:latest`, and automatically prunes dangling (untagged) images so
repeated rebuilds cannot accumulate unbounded overlay storage. Then start the
app with:

```sh
docker compose up -d
```

`docker compose up` uses the image produced by the build script. If you prefer
to run the steps manually (e.g. on a shared daemon where you don't want the
automatic prune), build the jar first and then build the image:

```sh
./gradlew :server:shadowJar --no-daemon
docker compose build
```

To reclaim historical storage, run `podman system prune` (or `docker system
prune`) and prune volumes manually as needed.

Browser tests are unaffected: they still run via their dedicated container image
(`localhost/bankteller-browser-tests`).

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Server tests: `./gradlew :server:test`
- Web tests:
    - Wasm target: `./gradlew :app:shared:wasmJsTest`
    - JS target: `./gradlew :app:shared:jsTest`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack
channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).