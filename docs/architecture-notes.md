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
telemetry/  Radio/GNSS source interfaces, mock sources, Android source stubs, and sample assembly
storage/    App preferences and coverage log persistence
reporting/  Alarm receivers and reporting scheduler
```

`MainActivity` should keep shrinking toward lifecycle, navigation, and view rendering. Scanner state transitions, contract encoding, collectors, persistence, and reporting should live outside the Activity unless they are directly tied to Android view lifecycle.

The Android build compiles and targets Android 16/API 36. Android 10/API 29 is the minimum supported version so scanner work can assume the public 5G NR telephony classes are available.

## Android Collection Notes

Likely APIs:

- `TelephonyManager`
- `SubscriptionManager`
- `ConnectivityManager`
- Android location APIs or Google Play Services fused location, decided later
- Foreground service APIs for long-running active logging

Cellular data should be treated as partial and device-dependent. The app should not assume every phone exposes every LTE or NR metric.

Scanner sampling should keep GNSS and radio collection separate. Radio events or ticks should be paired with the latest acceptable GNSS fix by an assembler layer, then encoded to the shared JSONL contract. The active emulator path uses `MockRadioTelemetrySource` and `MockGnssTelemetrySource`; future physical-device work should implement the same interfaces with Android public APIs.

The assembler owns code-configured GNSS quality thresholds before log emission. Samples should be skipped when GNSS is missing, stale, or too imprecise. The current prototype defaults are max horizontal accuracy 50 meters, max HDOP 4.0, max GNSS snapshot age 30 seconds, and speed-aware fix age limits: 30 seconds while stationary/slow, 10 seconds at 2 m/s or faster, and 5 seconds at 10 m/s or faster. Unknown speed uses the 10 second slow-moving limit.

The mock GNSS source intentionally emits a stale/imprecise fix every sixth sample so emulator runs exercise the rejection path.

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
