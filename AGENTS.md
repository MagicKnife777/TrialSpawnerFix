# Repository Guidelines

## Project Structure & Module Organization

Java sources live in `src/main/java/magicknife/trialspawnerfix/`. `TrialSpawnerFixPlugin` manages lifecycle and saved state; `SpawnerListener` handles chunk loads and cooldown repair. Plugin metadata and default configuration live in `src/main/resources/`. The `src/test/java/` and `src/test/resources/` directories are currently empty. Gradle configuration is in `build.gradle.kts`; `gradle/wrapper/` pins the build tooling. Local server files belong under ignored `run/`; build artifacts go to `build/libs/`.

## Build, Test, and Development Commands

Use JDK 25 and the bundled Gradle 9.8.0 wrapper. The compile-only dependency is Lophine API `26.3.build.961-stable`.

- `./gradlew.bat clean build --console=plain`: clean, compile, check, and package `build/libs/TrialSpawnerFix-1.0.0.jar`.
- `./gradlew.bat test --console=plain`: run the test task; currently no tests exist.
- `./gradlew.bat runServer --console=plain`: build and load the plugin on a local Lophine server. Place its JAR at `run/server/lophine.jar`, or pass `"-PlophineServerJar=E:/Servers/lophine.jar"`.

On Linux/macOS, replace `./gradlew.bat` with `sh ./gradlew`. Initial builds require dependency downloads; use `--offline` only after caching them. Server data lives in `run/lophine/`; review its generated EULA before accepting it manually.

## Coding Style & Naming Conventions

Follow `.editorconfig`: UTF-8, LF, four spaces for Java/Kotlin, two for YAML, and a final newline. The Windows wrapper uses CRLF. Use PascalCase class names, camelCase methods and variables, and lowercase packages. Keep comments in Chinese to match existing code. No formatter or linter is configured. Maintain plugin version in `build.gradle.kts`; resource processing expands it into `plugin.yml`.

## Testing Guidelines

No test framework or coverage threshold is configured. Add focused regression tests under `src/test/java/` when introducing a test setup; use names such as `SpawnerListenerTest`. For behavior changes, validate on the local server: cooldowns above 36,000 ticks reset on chunk load, while normal cooldowns remain unchanged. Record build results and manual checks.

## Commit & Pull Request Guidelines

Recent commits use `feat: <Chinese summary>`; keep messages imperative and scoped. Keep changes focused. PR descriptions should explain behavior, link relevant issues, and report validation and compatibility limitations. Include logs or reproduction steps for cooldown fixes.

## Configuration & Threading

Perform block mutations through the region scheduler and reacquire block state inside the callback. Preserve `folia-supported: true`. `last-shutdown-gametime` is automatically saved state currently unused by cooldown repair; avoid manual edits. Keep server JARs, worlds, logs, and caches out of commits.
