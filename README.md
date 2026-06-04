# 5G Scanner Android

Android/Kotlin app for building a real 5G/LTE coverage data set from Android phones.

5G Scanner is meant to be installed, granted consent, and then left to work quietly in the background. While scanning is running, it records mobile signal quality, location, time, and network context so coverage can be mapped over time in the areas where participants actually work and travel.

The app focuses on 5G coverage, while also collecting LTE data when that helps explain the real mobile experience. It only collects data; it is not a navigation app, speed test, or network optimization tool.

## Current Status

Planning and project setup plus a simulator-friendly Android scanner prototype.

The product direction is a consent-led background coverage collector. Most users should only need to grant consent, keep the app installed, and stop scanning when they are off duty or want to save battery. Engineers can inspect recorded samples and tune settings, but the app should stay simple for voluntary crowdsourced participation.

Storage is local-first, with reporting controlled by the app's reporting setting. Reporting currently builds capped JSONL batches from queued rows and posts them to the configured reporting endpoint.

## Terminology

Use these nouns consistently in user-facing text, code comments, docs, and agent work:

- Coverage sample: one accepted measurement record produced by the scanner.
- Recorded coverage data: the local collection of coverage samples stored on this device.
- Local database: the Room/SQLite store. This is the app's source of truth.
- CSV export: the user-facing export artifact generated from selected database rows.
- Reporting: sending queued coverage samples according to the reporting setting.
- JSONL contract: the internal compatibility shape shared with the Node reference scanner. JSONL is not a user-facing export format.
- Coordinate precision: latitude and longitude are rounded to five decimal places for UI display and CSV export. Stored samples keep their original coordinate values.
- Scanner states: stopped, running, and error.

Avoid obsolete storage terms such as log, log file, daily file, and daily JSONL when describing app persistence. The app no longer stores coverage data as files.

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
  core/                 Plain Kotlin domain models and internal sample encoding
  telemetry/            Android location/connectivity/cellular collectors
  storage/              Database persistence, reporting state, and CSV export
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

It uses a single native Android `Activity` and avoids Compose. AndroidX Room is included for local sample persistence; CSV is the user-facing export format. Android Studio can open the project and sync Gradle.

The app compiles and targets Android 16/API 36. The minimum supported Android version is Android 10/API 29, matching the first public Android API level with 5G NR telephony classes and the intended modern 5G device fleet.

The emulator build currently uses mock telemetry. It supports:

- Blocking first-run consent before scanner controls are shown
- Grant app-level consent
- Automatic mock scanning after consent through a foreground scanner service with mock radio and mock GNSS sources
- Accepted samples persisted in a local Room/SQLite database with upload bookkeeping fields and reporting batches
- Recorded coverage data inspector backed by the database, with CSV exports generated into temporary cache files
- Single stop/start control on the main scanner screen
- View sample count, last sample time, and mock radio output
- Separate settings screen for location mode, reporting, recorded coverage data, and About details
- Scanner state model: stopped, running, or error

The Gradle wrapper is committed so the project can be built consistently from Android Studio or the command line.

Useful verification commands:

```text
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

Before physical-device field testing, run through [docs/device-testing-checklist.md](docs/device-testing-checklist.md).

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

## Internal Reporting Contract

The first target format is append-only JSON Lines:

```text
one CoverageSample JSON object per line
```

The Android prototype stores accepted samples in Room/SQLite. JSONL is still the internal compatibility contract for reporting and tests, but it is not the user-facing export format.

See [docs/output-contract.md](docs/output-contract.md) for the current contract notes.

Accepted coverage samples are stored in a local Room/SQLite database. The app generates CSV from selected database rows for user-facing export through the Android Sharesheet. JSONL remains internal to reporting/debug workflows.

The prototype now routes mock collection through the same shape planned for real scanning:

```text
RadioTelemetrySource + GnssTelemetrySource
  -> CoverageSampleAssembler
  -> CoverageSampleJsonEncoder
  -> CoverageDatabase
```

Mock radio/GNSS sources are active for emulator development. Android radio/GNSS source classes exist as placeholders for the future public API collectors.

Runtime defaults and tuning values that are natural to adjust during development live in `app/src/main/java/no/politiet/pit/AppConfig.kt`. This includes settings defaults, scanner cadence, GNSS quality gates, enhanced privacy precision, reporting intervals/backoff/batch limits, the reporting endpoint and transport mode, and recorded coverage data display/export limits.

Reporting uses a small `ReportingTransport` boundary. The default prototype endpoint is `https://mock-api-ejkt.onrender.com/api/coverage-samples`, a hosted Render mock for emulator and device testing. For local backend testing, use the Developer `Reporting endpoint` setting in About to point the app at a backend URL reachable from the device, such as `http://10.0.2.2:8080/api/coverage-samples` from the Android emulator or the host PC's LAN IP from a physical phone. The setting verifies that the value is an HTTP(S) URL and that an `OPTIONS` request advertises `POST` support before saving; servers can advertise JSONL support with `Accept-Post: application/x-ndjson`. Cleartext HTTP is enabled until TLS and the real backend endpoint are decided. A mock reporting transport remains available through `AppConfig.Reporting.useMockTransport` for development.

Enhanced privacy is applied before a sample is stored. It snaps fix timestamps to UTC midnight, snaps coordinates to the configured grid-cell center, coarsens altitude, removes speed and heading, and replaces precise GNSS quality details with configured privacy-mode values.

Running scanning is owned by `ScannerService`, a foreground service with a visible notification. `MainActivity` starts or stops the service based on consent, location and notification permissions, flight-mode/location guards, and the user scanner toggle, then renders the latest in-process service state. This lets scanning continue after the UI leaves the foreground. Automatic scanner restart after phone reboot is intentionally not enabled yet because modern Android treats boot-started location services as a stronger background-location case.

The About screen includes a small Developer section with a `Mock telemetry` toggle. It defaults to enabled so emulator development keeps using simulator-friendly radio/GNSS samples. Emulators always force mock telemetry even if the saved toggle is off. Turning it off on a physical device routes the scanner through the Android source classes; those are still placeholders until the real public API collectors are implemented. The main scanner screen shows a subtle `MOCK` badge in the serving-cell line whenever mock telemetry is active.

## Location Mode and Quality

Location mode controls how aggressively the future Android GNSS/location source should request fixes. It does not lower the quality bar for recorded coverage samples.

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

The scanner keeps the newest reported GNSS fix separate from the newest usable GNSS fix. If Android reports a newer but worse location, the scanner can continue pairing radio samples with the previous usable fix until that fix ages out. The main UI shows the usable location state: `GNSS` displays accuracy and age while usable, and switches to reasons such as `Fix too old`, `Too imprecise`, or `Weak fix` when location is no longer useful for mapping.

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
- The normal experience is consent once, then let scanning run in the background
- Participation can pause by stopping scanning and can stop entirely by uninstalling the app
- The main UI should expose a clear scanning on/off control for off hours, battery saving, or user choice
- The prototype samples on a fixed internal cadence; settings should avoid scanner controls that do not change real collection behavior
- Settings should include location behavior, reporting, recorded coverage data, and About details
- The local database is the source of truth; CSV is the user-facing export format, while JSONL remains internal to reporting/debug workflows

## Notes

Android cellular APIs expose different fields depending on Android version, phone model, carrier, SIM state, and permissions. The app should tolerate missing signal metrics and partial cell identity data.
