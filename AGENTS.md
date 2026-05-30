# AGENTS.md

## Project Intent

This repo will contain an Android/Kotlin 5G/LTE coverage scanner app. The app should collect phone connectivity, cellular radio, and location data after user-granted permissions, then emit logs compatible with the existing Node/TypeScript scanner.

The reference scanner lives at:

- `external/node-scanner/`

Treat it as the source of truth for the current JSONL output shape, sample data, and TypeScript domain types.

## Architecture Direction

Prefer sharing the output contract, not the runtime.

Do not embed or port the Node.js scanner wholesale into Android unless explicitly requested. Android should implement native collectors around Android APIs, then map the results into Kotlin domain models matching the reference JSONL contract.

Keep Android API usage at the edges. Domain models, encoders, and validation should be plain Kotlin where possible.

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
- Android telephony APIs
- Location APIs
- A foreground service for active logging
- Runtime permission handling
- JSONL file export

Cellular data availability varies by Android version, phone model, modem, carrier, SIM state, and granted permissions. Avoid assuming every metric is always present.

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
