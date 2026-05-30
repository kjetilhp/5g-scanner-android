# 5G Scanner Android

Android/Kotlin companion app for the 5G Scanner project.

The goal is to turn an Android phone into a 5G/LTE coverage sensor: after the user grants permissions, the app will sample location, connectivity, and cellular radio data, then write logs compatible with the existing Node/TypeScript scanner.

## Current Status

Planning and project setup only. The Android app skeleton will be generated later with Android Studio.

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
  app/                  Android application module, created later
  core/                 Plain Kotlin domain models and JSONL encoding
  telemetry/            Android location/connectivity/cellular collectors
  storage/              Log persistence and export
  docs/                 Architecture notes and output contract
  external/
    node-scanner/       Reference Node/TypeScript scanner submodule
```

The exact Gradle layout can change once Android Studio creates the project.

## Output Format

The first target format is append-only JSON Lines:

```text
one CoverageSample JSON object per line
```

See [docs/output-contract.md](docs/output-contract.md) for the current contract notes.

## Notes

Android cellular APIs expose different fields depending on Android version, phone model, carrier, SIM state, and permissions. The app should tolerate missing signal metrics and partial cell identity data.
