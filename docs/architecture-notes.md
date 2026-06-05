# Architecture Notes

## Main Idea

Share the output contract with the Node/TypeScript scanner, not the runtime.

The Android app should collect native Android telemetry and map it into Kotlin domain models that serialize to the same JSONL shape as the reference scanner.

The app should be designed around one effective scanner state derived from consent, Android permissions, user intent, and device state. Scanner work should not run when consent is missing, permissions are missing, or scanning is stopped.

## Proposed Areas

```text
app/        UI, permission flow, service wiring
core/       Domain models, JSONL encoding, contract tests
telemetry/  Android location, connectivity, and cellular collectors
storage/    Database persistence, reporting state, CSV export, retention
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
telemetry/  Radio/GNSS source interfaces, mock sources, Android sources, and sample assembly
storage/    App preferences, coverage database persistence, and CSV export
reporting/  Alarm receivers, reporting scheduler, transport, validation, and retry/recovery helpers
ScannerService.kt  Foreground active scanner loop and sample writer
```

`MainActivity` should keep shrinking toward lifecycle, navigation, and view rendering. Active scanning now lives in `ScannerService`: the Activity starts/stops it based on consent, runtime location/notification permissions, device feature guards, and the user scanning toggle, then renders the latest service state. Scanner state transitions, contract encoding, collectors, persistence, and reporting should live outside the Activity unless they are directly tied to Android view lifecycle.

Settings-style screens in `MainActivity` should stay on one RecyclerView-backed path. The current Settings, Recorded Coverage Data, and About screens render `SettingsListItem` rows through a shared adapter with stable IDs and `DiffUtil` content keys. Refreshes should replace the item list and let RecyclerView preserve viewport state; avoid custom scroll restoration, `notifyDataSetChanged`, or direct TextView field updates for individual settings rows.

The Android build compiles and targets Android 16/API 36. Android 10/API 29 is the minimum supported version so scanner work can assume the public 5G NR telephony classes are available.

## Android Collection Notes

Likely APIs:

- `TelephonyManager`
- `SubscriptionManager`
- `ConnectivityManager`
- Android location APIs or Google Play Services fused location, decided later
- Foreground service APIs for long-running scanning

Cellular data should be treated as partial and device-dependent. The app should not assume every phone exposes every LTE or NR metric.

Scanner sampling should keep GNSS and radio collection separate. Radio events or ticks should be paired with the latest acceptable GNSS fix by an assembler layer, then encoded to the shared JSONL contract. Radio callbacks are state updates, not direct sample writes: important radio changes may request a debounced near-term scanner tick, but the scanner tick remains the place where accepted samples are assembled and stored. The active emulator path uses `MockRadioTelemetrySource` and `MockGnssTelemetrySource`; physical-device radio collection now starts through `AndroidRadioTelemetrySource`.

Telemetry source selection is centralized in `TelemetrySourceFactory`. The app has a Developer setting named `Mock telemetry`, persisted in app preferences and shown under About. Mock telemetry defaults to enabled for emulator-friendly development. Emulators force mock telemetry regardless of the saved toggle. When disabled on a physical device, the scanner service routes through `AndroidRadioTelemetrySource` and `AndroidGnssTelemetrySource`. The Android radio source registers `TelephonyCallback` on API 31+ and falls back to `PhoneStateListener` plus `requestCellInfoUpdate` on API 29-30; it maps registered LTE and NR serving cells into the existing sample model. The Android GNSS source uses `LocationManager`, keeps latest reported/usable fixes, and adapts request tier from low power while stationary to high accuracy while driving. The main scanner UI shows a subtle `MOCK` badge in the serving-cell line whenever mock telemetry is active.

The About -> Developer telemetry diagnostics row should stay compact and practical. The row summarizes whether telemetry is mock or Android, whether sources are running or waiting, and either recent radio/GNSS/sample ages or the latest sample skip reason. Tapping the row opens a copyable diagnostics dialog with the fuller snapshot. Keep this as field-test feedback, not a general debug console.

The scanner loop is a foreground service. While scanning is active, `ScannerService` owns radio ticks, GNSS refreshes, sample assembly, Room/SQLite persistence, and continuous reporting triggers. CSV artifacts are generated from selected database rows for user-facing export. JSONL remains an internal reporting/compatibility contract, not a normal user export. The main UI observes an in-process state snapshot and receives service-state broadcasts while visible; the UI's own GNSS timer only updates the displayed `Ys ago` age and does not collect location. Stopping scanning stops the service and removes its foreground notification.

## Database Schema Versioning

Use SemVer for app releases:

- MAJOR: breaking compatibility after a release/shared baseline exists.
- MINOR: backward-compatible features or behavior.
- PATCH: fixes, docs, and polish.

Current development can remain in `0.x` while the app is prototype/private field-test software. The current app version is `0.1.0`; the current Room development baseline is schema version `1`.

Room migrations are a release boundary tool, not a dev-cycle tax. Until the first real release or any shared build with user data worth preserving, the local database schema is a disposable development baseline:

- Keep `CoverageDatabase.version` at `1`.
- Update entities, DAOs, and indices directly as the baseline changes.
- Use clean installs or app data clears after schema changes.
- Do not keep historical migrations for emulator-only/dev-only installs.

Every Room persistence/schema change should still be treated as a checkpoint. Before committing changes that touch entities, DAOs, indices, database version, database provider setup, or persisted settings keys/default semantics, call out the change and decide whether it remains a clean-install dev baseline change or needs a migration step.

After a release/shared build may exist outside the disposable dev environment, freeze that schema as the installed baseline. From then on, breaking database changes should increment the Room version and add an explicit migration. Non-breaking code-only changes should not bump the database version.

Before creating a real release, decide whether the current schema is the release baseline and add migration tests/schema export if we expect to preserve user data across updates.

Keep compatibility buckets separate:

- Room migration decision: local persisted data and settings compatibility.
- JSONL reporting contract decision: backend/reference scanner compatibility.
- CSV export contract decision: user/export consumer compatibility.

CSV or JSONL changes do not require a Room migration unless the local stored data shape also changes. They may still require a SemVer note, documentation update, backend coordination, or export-contract communication.

### Scanner Resilience Target

Treat the user's scanner toggle as desired state, not merely as "a service is currently alive". Once the user has consented and started scanning, the app should be as resilient as Android allows:

- Temporary errors such as flight mode, location disabled, or revoked runtime permissions should pause usable sample production, not erase the user's intent to scan.
- While in error, the scanner should keep enough app/service state to report why samples are not being produced.
- When the phone returns to a working condition, such as flight mode off, location enabled, or permissions restored, scanning should resume automatically when Android allows it.
- Samples should still be skipped rather than recorded when radio or GNSS data is missing, stale, or unusable.

The implementation path should separate three concepts:

```text
scannerDesired
  Persisted user intent: consent is granted and the user has not pressed Stop.

scannerError
  Current environment prevents useful samples while scanning is desired: missing permission, location off, flight mode, no cellular radio, or unusable GNSS.

scannerActive
  The foreground service is currently collecting and can attempt to produce samples.
```

The foreground service should not immediately stop just because a temporary error appears. Instead, it should pause collection/persistence, publish an error reason, keep the foreground notification visible with an error message, and listen or recheck for relevant guard changes. The foreground notification should use the scanner's running green when samples can be produced and a red warning accent when scanning is desired but in error. Use receivers/callbacks for airplane-mode changes, location-provider changes, permission rechecks on app resume, and telephony/connectivity changes where Android exposes them. When the error clears and `scannerDesired` is still true, the service can resume sampling.

Some states remain outside normal resilience: user force-stop, uninstall, app update/reinstall, process death without restart allowance, and reboot. Reboot auto-resume should be treated separately because modern Android may require background-location permission and stronger consent language.

The boot receiver currently restores reporting alarms from saved preferences. It does not restart active scanning after reboot. Auto-resuming scanner location collection from boot should be treated as a separate product/privacy decision because it may require background-location permission and stronger consent language on modern Android.

## Reporting Resilience

Reporting is local-first. Samples are written to Room first, then claimed into capped JSONL batches for scheduled, manual, or continuous reporting. Batch size is bounded by `AppConfig.Reporting.maxSamplesPerBatch` and `maxBatchBytes`; a single reporting trigger is also bounded by `maxBatchesPerDrain` so a stuck sender cannot monopolize the database executor.

The reporting state model uses sample upload statuses plus `reporting_batches`. Failed batches keep their samples queued for retry and use the configured backoff. If a process dies during an upload attempt, a later reporting trigger recovers stale `in_flight` batches whose next-attempt time has passed by marking the batch and samples failed with a recoverable interruption message, then the normal retry path owns the next send.

## GNSS Usability Strategy

GNSS/location updates should be treated as a stream of reports, not as a guarantee that the newest report is the best report. A real provider can report a newer but less useful fix after a good fix: for example a coarse network location, a fix with poor horizontal accuracy, a fix with weak geometry, or a stale cached provider result. Replacing the current location blindly with every new report can make the scanner discard a still-valid fix and skip samples unnecessarily.

Use two concepts internally:

```text
latestReportedGnssFix
  Newest location report observed from Android or a mock source, whether usable or not.

latestUsableGnssFix
  Newest report that passes the scanner's location usability gate.
```

When a new GNSS report arrives:

- Always keep it as the latest reported fix for diagnostics and future UI/debug details.
- Promote it to the latest usable fix only when it passes the usability gate.
- If it fails the gate, keep using the previous usable fix while that fix remains valid.
- Once the previous usable fix ages out, the scanner should expose the unusable/newest report so the UI can show why location is missing or not useful.

This preserves sample quality without being overly fragile during brief GNSS/provider regressions. The sample assembler should receive the latest usable fix, not merely the newest reported fix. The UI should primarily show the fix being used for scanner pairing. If the app later gains a detailed diagnostics view, it can separately show that a newer reported fix was rejected.

The current usability gate mirrors the sample-acceptance thresholds: horizontal accuracy must be at most 50 meters when provided, HDOP must be at most 4.0, and fix age is speed-aware. A fix can be held for up to 30 seconds while stationary/slow, 10 seconds at 2 m/s or faster, and 5 seconds at 10 m/s or faster. Unknown speed uses the 10 second slow-moving limit.

The assembler owns code-configured GNSS quality thresholds before sample persistence. Samples should be skipped when GNSS is missing, stale, or too imprecise. The current prototype defaults are max horizontal accuracy 50 meters, max HDOP 4.0, max GNSS snapshot age 30 seconds, and speed-aware fix age limits: 30 seconds while stationary/slow, 10 seconds at 2 m/s or faster, and 5 seconds at 10 m/s or faster. Unknown speed uses the 10 second slow-moving limit.

The main scanner UI visualizes the usable-fix state. GNSS quality is a continuous `0..1` usability score based on freshness, horizontal accuracy, and HDOP. The score decays smoothly as a fix ages. When no usable fix remains, the GNSS segment should read as missing/invalid location rather than merely low quality: the current prototype uses zero quality, explanatory text such as `Fix too old`, `Too imprecise`, or `Weak fix`, and a hatched GNSS panel.

The mock GNSS source uses separate latest-reported and latest-usable snapshots. It intentionally emits occasional degraded reports so emulator runs exercise the rejection path without immediately throwing away a still-valid previous fix.

## Early Milestones

1. Link the Node scanner as `external/node-scanner`.
2. Document the current output contract.
3. Document product, privacy/consent, and settings expectations.
4. Create the Android Studio project skeleton.
5. Add plain Kotlin models for the contract.
6. Add JSONL encoding tests using small reference fixtures.
7. Add live Android collectors and map their output into the contract.

## Deferred Decisions

- Production reporting destination, TLS policy, and server retention behavior
- Account identity, if any
- Data retention defaults
- Exact location provider choice
- Dual-SIM controls
