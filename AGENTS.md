# AGENTS.md

## Project Intent

This repo will contain an Android/Kotlin 5G/LTE coverage scanner app. The app should collect phone connectivity, cellular radio, and location data after user-granted permissions, then emit logs compatible with the existing Node/TypeScript scanner.

The Android app name is `Ask`, and the application id/package namespace is `no.politiet.pit`.

The reference scanner lives at:

- `external/node-scanner/`

Treat it as the source of truth for the current JSONL output shape, sample data, and TypeScript domain types.

## Architecture Direction

Prefer sharing the output contract, not the runtime.

Do not embed or port the Node.js scanner wholesale into Android unless explicitly requested. Android should implement native collectors around Android APIs, then map the results into Kotlin domain models matching the reference JSONL contract.

Keep Android API usage at the edges. Domain models, encoders, and validation should be plain Kotlin where possible.

The first Android app experience should be simple and consent-led: users voluntarily participate in crowdsourced coverage mapping, can clearly start or stop scanning, can pause scanning temporarily, and can revoke app-level consent separately from Android OS permissions.

## Reference Contract

The current reference output is append-only JSONL, one coverage sample per line.

Relevant reference files:

- `external/node-scanner/src/domain/sample.ts`
- `external/node-scanner/src/domain/cell.ts`
- `external/node-scanner/src/domain/fix.ts`
- `external/node-scanner/src/storage/jsonl.ts`
- `external/node-scanner/data/*.jsonl`

## Android Notes

Expect to use:

- Kotlin
- Android Gradle Plugin
- Android telephony APIs
- Location APIs
- A foreground service for active logging
- Runtime permission handling
- JSONL file export

The initial app skeleton is intentionally minimal: one native Android Activity with no Compose, AndroidX, or third-party dependencies. Add dependencies only when they pull real weight for the scanner, consent flow, or app ergonomics.

Cellular data availability varies by Android version, phone model, modem, carrier, SIM state, and granted permissions. Avoid assuming every metric is always present.

Do not start scanner work unless the effective state allows it:

- App-level consent is granted
- Required Android permissions are granted
- Scanning is enabled
- Scanning is not paused until a future date/time

Initial versions should assume local logging only. Upload/sync behavior is intentionally deferred and must require a later explicit product/privacy decision before implementation.

## Product Notes

Keep the first usable UI small:

- Consent/onboarding
- Main scanner status and on/off control
- Settings

Use clear language around what is collected, why it is collected, where it is stored, and how participation can stop. Avoid dark patterns around consent, permissions, scanning state, deletion, or later upload behavior.

Relevant planning docs:

- `docs/product-notes.md`
- `docs/privacy-consent.md`
- `docs/settings-model.md`

## Development Preferences

Prefer small, explicit models and tests around the output contract.

Before making a commit, update `AGENTS.md` and `README.md` when the change affects project structure, setup steps, architecture direction, development workflow, or user-facing project status.

When adding Android code, keep modules separated roughly as:

- `app/` for UI and app wiring
- `core/` for domain models and JSONL encoding
- `telemetry/` for Android connectivity/location collectors
- `storage/` for log persistence/export
- `docs/` for schema and architecture notes
- `samples/` for copied or minimized example logs

Do not commit large generated datasets unless they are intentionally curated fixtures.
