# 5G Scanner Android

Android/Kotlin companion app for the 5G Scanner project.

The goal is to turn an Android phone into a 5G/LTE coverage sensor: after the user grants permissions, the app will sample location, connectivity, and cellular radio data, then write logs compatible with the existing Node/TypeScript scanner.

## Current Status

Planning and project setup plus a simulator-friendly Android scanner prototype.

The Android app name is `Ask`, and the package/application id is `no.politiet.pit`.

The current product direction is a small, consent-led scanner app: the user voluntarily participates in scanning, can stop and start it, and can stop participating by uninstalling the app. Logging is local-first, with reporting controlled by the app's reporting setting.

## Reference Project

The existing Node/TypeScript scanner should be linked as a Git submodule at:

```text
external/node-scanner/
```

That project currently defines the JSONL output shape and has real sample data. Android should mirror the output contract rather than embed the Node runtime.

Useful reference files:

```text
external/node-scanner/src/domain/sample.ts
external/node-scanner/src/domain/cell.ts
external/node-scanner/src/domain/fix.ts
external/node-scanner/src/storage/jsonl.ts
external/node-scanner/data/*.jsonl
```

## Intended Layout

```text
5g-scanner-android/
  assets/icon/          Canonical app icon SVG and generated favicon assets
  app/                  Android application module
  core/                 Plain Kotlin domain models and JSONL encoding
  telemetry/            Android location/connectivity/cellular collectors
  storage/              Log persistence and export
  scripts/              Maintenance scripts, including icon generation
  docs/                 Architecture notes and output contract
  external/
    node-scanner/       Reference Node/TypeScript scanner submodule
```

Only `app/` exists right now. The other modules can be added when the scanner logic starts to grow.

## Android Prototype

The current Android app is intentionally tiny:

```text
settings.gradle.kts
build.gradle.kts
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/no/politiet/pit/MainActivity.kt
```

It uses a single native Android `Activity` and avoids Compose, AndroidX, and third-party dependencies for now. Android Studio can open the project and sync Gradle.

The app compiles and targets Android 16/API 36. The minimum supported Android version is Android 10/API 29, matching the first public Android API level with 5G NR telephony classes and the intended modern 5G device fleet.

The emulator build currently uses mock telemetry. It supports:

- Blocking first-run consent before scanner controls are shown
- Grant app-level consent
- Automatic mock scanning after consent through mock radio and mock GNSS sources
- Daily JSONL log files written under `Documents/Ask/` on shared storage
- Single stop/start control on the main scanner screen
- View sample count, last sample time, and mock radio output
- Separate settings screen for location mode, reporting, coverage logs, and About details
- Coverage log CSV preview and Android Sharesheet export

The Gradle wrapper is committed so the project can be built consistently from Android Studio or the command line.

## Icon Workflow

The app icon and favicon files are based on a single square SVG source:

```text
assets/icon/source.svg
```

It uses a `1024x1024` canvas with grid-aligned geometry so small launcher and favicon outputs stay crisp. Android launcher icons use adaptive icon XML plus the vector foreground in `app/src/main/res/drawable/ic_launcher_foreground.xml`; no density-specific launcher PNGs are generated because the app targets Android 10+.

Install the small dev toolchain once, then regenerate favicon files with:

```text
npm install
npm run icons
```

The script uses the pinned `@resvg/resvg-js` dev dependency for deterministic SVG rasterization. Generated favicon assets are written to `assets/icon/generated/`.

## Output Format

The first target format is append-only JSON Lines:

```text
one CoverageSample JSON object per line
```

The Android prototype currently writes mock samples to one file per UTC day:

```text
Documents/Ask/coverage-YYYY-MM-DD.jsonl
```

See [docs/output-contract.md](docs/output-contract.md) for the current contract notes.

Coverage logs remain stored internally as JSONL. The app generates temporary CSV exports for on-device preview and sharing with other Android apps.

The prototype now routes mock collection through the same shape planned for real scanning:

```text
RadioTelemetrySource + GnssTelemetrySource
  -> CoverageSampleAssembler
  -> CoverageSampleJsonEncoder
  -> CoverageLogStore
```

Mock radio/GNSS sources are active for emulator development. Android radio/GNSS source classes exist as placeholders for the future public API collectors.

## Location Mode and Quality

Location mode controls how aggressively the future Android GNSS/location source should request fixes. It does not lower the quality bar for coverage logs.

```text
Balanced
  Default. Saves battery when still or moving slowly, then asks for fresher fixes while driving.

High accuracy
  Best for active mapping and driving. Uses more battery.

Low power
  Uses fewer location updates. Some driving samples may be skipped.
```

Samples are written only when the paired location is good enough. The current code-configured quality gate is:

```text
horizontal accuracy <= 50 m, when Android provides accuracy
HDOP <= 4.0
GNSS snapshot age <= 30 s
fix age <= 30 s when speed is under 2 m/s
fix age <= 10 s when speed is at least 2 m/s, or speed is unknown
fix age <= 5 s when speed is at least 10 m/s
```

This means the app should save battery by collecting fewer usable samples, not by accepting stale or imprecise locations. The emulator mock intentionally emits a bad location every sixth sample so this rejection path can be exercised without a physical phone.

## Product and Privacy Notes

Planning docs:

```text
docs/product-notes.md
docs/privacy-consent.md
docs/settings-model.md
docs/architecture-notes.md
```

Core first-version assumptions:

- Scanning requires app-level consent and Android permission grants
- Participation can stop by stopping scanning or uninstalling the app
- The main UI should expose a clear scanning on/off control
- The prototype samples on a fixed internal cadence; settings should avoid scanner controls that do not change real collection behavior
- Settings should include location behavior, reporting, local logs, and About details
- Local logging is the source of truth; user-facing exports are generated as CSV when needed

## Notes

Android cellular APIs expose different fields depending on Android version, phone model, carrier, SIM state, and permissions. The app should tolerate missing signal metrics and partial cell identity data.
