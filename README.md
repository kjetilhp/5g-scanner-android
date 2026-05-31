# 5G Scanner Android

Android/Kotlin companion app for the 5G Scanner project.

The goal is to turn an Android phone into a 5G/LTE coverage sensor: after the user grants permissions, the app will sample location, connectivity, and cellular radio data, then write logs compatible with the existing Node/TypeScript scanner.

## Current Status

Planning and project setup plus a simulator-friendly Android scanner prototype.

The Android app name is `Ask`, and the package/application id is `no.politiet.pit`.

The current product direction is a small, consent-led scanner app: the user voluntarily turns scanning on, can pause or stop it, and can revoke participation. Initial logging is local-only; upload/sync behavior is intentionally deferred.

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

The emulator build currently uses mock telemetry. It supports:

- Blocking first-run consent before scanner controls are shown
- Grant app-level consent
- Automatic mock scanning after consent
- Single pause/resume control on the main scanner screen
- View sample count, last sample time, and mock radio output
- Separate settings screen for sampling frequency and GNSS mode

The Gradle wrapper is committed so the project can be built consistently from Android Studio or the command line.

## Icon Workflow

The app icon and favicon files are based on a single square SVG source:

```text
assets/icon/source.svg
```

It uses a `1024x1024` canvas with grid-aligned geometry so small launcher and favicon outputs stay crisp. Android launcher icons use adaptive icon XML plus the vector foreground in `app/src/main/res/drawable/ic_launcher_foreground.xml`; no density-specific launcher PNGs are generated because the app targets Android 8+.

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

See [docs/output-contract.md](docs/output-contract.md) for the current contract notes.

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
- Consent can be revoked separately from OS permissions
- The main UI should expose a clear scanning on/off control
- Settings should include sampling frequency, pause controls, and location/GNSS behavior
- Local logging comes before any upload/sync feature

## Notes

Android cellular APIs expose different fields depending on Android version, phone model, carrier, SIM state, and permissions. The app should tolerate missing signal metrics and partial cell identity data.
