# Architecture Notes

## Main Idea

Share the output contract with the Node/TypeScript scanner, not the runtime.

The Android app should collect native Android telemetry and map it into Kotlin domain models that serialize to the same JSONL shape as the reference scanner.

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
3. Create the Android Studio project skeleton.
4. Add plain Kotlin models for the contract.
5. Add JSONL encoding tests using small reference fixtures.
6. Add live Android collectors and map their output into the contract.
