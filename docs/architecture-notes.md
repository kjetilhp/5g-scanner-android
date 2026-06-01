# Architecture Notes

## Main Idea

Share the output contract with the Node/TypeScript scanner, not the runtime.

The Android app should collect native Android telemetry and map it into Kotlin domain models that serialize to the same JSONL shape as the reference scanner.

The app should be designed around one effective scanner state derived from consent, Android permissions, the user scanning toggle, and pause settings. Scanner work should not run when consent is missing, permissions are missing, scanning is disabled, or scanning is paused.

## Proposed Areas

```text
app/        UI, permission flow, service wiring
core/       Domain models, JSONL encoding, contract tests
telemetry/  Android location, connectivity, and cellular collectors
storage/    Log files, export, retention
docs/       Contract and design notes
samples/    Small curated fixtures
external/   Reference projects such as node-scanner
```

The first checked-in Android skeleton contains only `app/` and a simple native `MainActivity`. Add `core/`, `telemetry/`, and `storage/` when there is enough real scanner code to justify separating modules.

## Current Android Package Shape

The prototype still uses a single Gradle `app` module, but code inside `no.politiet.pit` is split by responsibility:

```text
domain/     Plain Kotlin modes and future scanner contract models
encoding/   JSONL/sample encoders that can be tested away from Android UI
telemetry/  Mock telemetry and future Android collector-facing models
storage/    App preferences and coverage log persistence
reporting/  Alarm receivers and reporting scheduler
```

`MainActivity` should keep shrinking toward lifecycle, navigation, and view rendering. Scanner state transitions, contract encoding, collectors, persistence, and reporting should live outside the Activity unless they are directly tied to Android view lifecycle.

## Android Collection Notes

Likely APIs:

- `TelephonyManager`
- `SubscriptionManager`
- `ConnectivityManager`
- Android location APIs or Google Play Services fused location, decided later
- Foreground service APIs for long-running active logging

Cellular data should be treated as partial and device-dependent. The app should not assume every phone exposes every LTE or NR metric.

## Early Milestones

1. Link the Node scanner as `external/node-scanner`.
2. Document the current output contract.
3. Document product, privacy/consent, and settings expectations.
4. Create the Android Studio project skeleton.
5. Add plain Kotlin models for the contract.
6. Add JSONL encoding tests using small reference fixtures.
7. Add live Android collectors and map their output into the contract.

## Deferred Decisions

- Upload/sync behavior
- Account identity, if any
- Data retention defaults
- Exact location provider choice
- Dual-SIM controls
