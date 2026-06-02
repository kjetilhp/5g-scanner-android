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

The first Android app experience should be simple and consent-led: users voluntarily participate in crowdsourced coverage mapping and can stop scanning temporarily. Consent must be a blocking full-screen gate; scanner controls and settings should not be shown until app-level consent is granted. In the current prototype, stopping participation after consent is handled by stopping scanning or uninstalling the app rather than an in-app revoke control.

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
- JSONL file logging with user-facing CSV preview/share export

The initial app is intentionally minimal: one native Android Activity with no Compose, AndroidX, or third-party dependencies. Add dependencies only when they pull real weight for the scanner, consent flow, or app ergonomics.

Simulator-friendly work should use mock telemetry. Do not block UI/state progress on real cellular APIs while a physical device is unavailable.

Cellular data availability varies by Android version, phone model, modem, carrier, SIM state, and granted permissions. Avoid assuming every metric is always present.

Start scanner work automatically when the effective state allows it:

- App-level consent is granted
- Required Android permissions are granted
- Scanning is not stopped until a future date/time

Initial versions should assume local-first logging. Reporting/upload behavior must stay consent-gated, clearly explained, and controlled by the reporting settings.

The current prototype writes mock samples to daily JSONL coverage logs under `Documents/Ask/` on shared storage. Settings includes reporting controls, a coverage logs view for listing files, in-app CSV preview/share export, and deleting all local coverage logs after confirmation.

## Product Notes

Keep the first usable UI small:

- Consent/onboarding
- Main scanner status and telemetry
- A single stop/start control on the main scanner screen
- Separate settings screen for less frequent controls

Use clear language around what is collected, why it is collected, where it is stored, and how participation can stop. Avoid dark patterns around consent, permissions, scanning state, deletion, or later upload behavior.

Relevant planning docs:

- `docs/product-notes.md`
- `docs/privacy-consent.md`
- `docs/settings-model.md`

## Development Preferences

Prefer small, explicit models and tests around the output contract.

Before making a commit, update `AGENTS.md` and `README.md` when the change affects project structure, setup steps, architecture direction, development workflow, or user-facing project status.

When adding Android code, keep modules separated roughly as:

- `assets/icon/` for the canonical app icon SVG and generated favicon assets
- `app/` for UI and app wiring
- `core/` for domain models and JSONL encoding
- `telemetry/` for Android connectivity/location collectors
- `storage/` for log persistence/export
- `docs/` for schema and architecture notes
- `samples/` for copied or minimized example logs
- `scripts/` for maintenance scripts such as icon generation

App icons are based on `assets/icon/source.svg`. Android launcher icons use adaptive icon XML plus `app/src/main/res/drawable/ic_launcher_foreground.xml`; run `npm run icons` after changing the source to refresh generated favicon files.

Do not commit large generated datasets unless they are intentionally curated fixtures.
