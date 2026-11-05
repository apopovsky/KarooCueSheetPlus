# Repository Guidelines

## Project Structure

- `app/` contains the Android extension, Karoo integration, UI, resources, and release manifest.
- `shared/` contains route parsing, cue calculations, rendering, and JVM unit tests under `src/test/`.
- `simulator/` contains the local simulator app and sample GPX; it is included only when the sibling `../KarooExtensionSimulator` project exists.
- `assets/` holds release assets; `.github/workflows/` defines CI and release automation.

## Build and Development

Use JDK 17 and an Android SDK. Configure `sdk.dir` in `local.properties` and GitHub Packages credentials as Gradle properties `gpr.user` and `gpr.key`, or environment variables `USERNAME` and `TOKEN`.

- `./gradlew :shared:testDebugUnitTest :app:assembleDebug` runs the CI unit tests and builds the debug APK.
- `./gradlew :simulator:assembleDebug` builds the simulator when its sibling dependency is available.
- `run_simulator.cmd` launches the local simulator on Windows.

On Windows, `gradlew.bat` can replace `./gradlew`.

## Style and Testing

Follow existing Kotlin and Android conventions: four-space indentation, `UpperCamelCase` for types, `lowerCamelCase` for functions and properties, and descriptive resource names such as `remote_cuesheet_full.xml`. Keep shared route and rendering logic in `shared/`; put device-specific integration in `app/`.

Use JUnit for unit tests, with coroutine-test where needed. Name test classes after the subject, for example `GpxRouteParserTest`, and add focused cases for changed behavior and edge conditions. Run the shared test command above before submitting changes. No coverage threshold is configured.

## Commits and Pull Requests

Recent history includes a scoped conventional-style subject (`ci: ...`) and an initial descriptive commit. Prefer short, imperative subjects with a suitable prefix where useful. Pull requests should describe the change, validation performed, and any linked issue; include screenshots for visible UI changes. Confirm CI passes.

## Security and Agent Instructions

Keep credentials, signing keys, and local configuration out of commits. Immediately before committing, run `sonar analyze secrets <path/to/file>` for each file to be committed. Do not scan during reading, investigation, editing, or compilation; do not commit if a scan fails or detects a secret.

When interacting with Cloudflare, use the `cf` CLI unless the project has a Wrangler configuration file.
