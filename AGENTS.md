# AGENTS.md

## Project Intent

An Android/Kotlin 5G/LTE coverage scanner app. The app collects phone connectivity, cellular radio, and location data after user-granted consent and permissions, then records coverage samples compatible with the existing Node/TypeScript scanner contract.

The product purpose is to build a real coverage data set that evolves over time from the areas participants actually work and travel. The app only collects coverage data. It is not a speed test, navigation app, or network optimization tool.

The default user is a voluntary crowdsourcing participant, not a power user. Employees, engineers, and possibly public participants should be able to install the app, grant consent, and let it work quietly in the background. Normal users may pause scanning for off hours or battery conservation, but should rarely need settings. Engineer workflows can exist, but must not make the main product feel like an expert-only tool.

The reference scanner lives at:

- `external/node-scanner/`

Treat it as the source of truth for the current JSONL output shape, sample data, and TypeScript domain types.

## Architecture Direction

Prefer sharing the output contract, not the runtime.

Do not embed or port the Node.js scanner wholesale into Android unless explicitly requested. Android should implement native collectors around Android APIs, then map the results into Kotlin domain models matching the reference JSONL contract.

Keep Android API usage at the edges. Domain models, encoders, and validation should be plain Kotlin where possible.

The first Android app experience should be simple and consent-led: users voluntarily participate in crowdsourced coverage mapping and can pause scanning temporarily. Consent must be a blocking full-screen gate; scanner controls and settings should not be shown until app-level consent is granted. In the current prototype, stopping participation after consent is handled by stopping scanning or uninstalling the app rather than an in-app revoke control.

## Terminology

Use these terms consistently in user-facing text, code comments, docs, and implementation names:

- Coverage sample: one accepted measurement record produced by the scanner.
- Recorded coverage data: the local collection of coverage samples stored on this device.
- Local database: the Room/SQLite store and app source of truth.
- CSV export: the user-facing export artifact generated from selected database rows.
- Reporting: sending queued coverage samples according to the reporting setting.
- JSONL contract: the internal compatibility shape shared with the Node reference scanner. JSONL is not a user-facing export format.
- Coordinate precision: latitude and longitude are rounded to five decimal places for UI display and CSV export. Stored samples keep their original coordinate values.
- Scanner states: stopped, running, and error.

Avoid obsolete storage terms such as log, log file, daily file, and daily JSONL when describing app persistence. The app no longer stores coverage data as files.

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
- A foreground service for running scanning
- Runtime permission handling
- Room/SQLite local persistence with internal JSONL/reporting compatibility and user-facing CSV export

The initial app is intentionally minimal: one native Android Activity with no Compose and only dependencies that pull real weight for the scanner, consent flow, persistence, or app ergonomics.

The Android app should compile and target Android 16/API 36, with Android 10/API 29 as the minimum supported version for the intended modern 5G-capable device fleet.

Simulator-friendly work should use mock telemetry. Do not block UI/state progress on real cellular APIs while a physical device is unavailable.

Keep radio and GNSS collection separate. Implement mock and Android collectors behind source-facing interfaces, then pair radio telemetry with the latest acceptable GNSS fix in an assembler before JSONL encoding.

Cellular data availability varies by Android version, phone model, modem, carrier, SIM state, and granted permissions. Avoid assuming every metric is always present.

Treat the scanner stop/start control as user intent:

- App-level consent is granted
- Scanning is not stopped by the user

Temporary errors such as missing runtime permission, location disabled, flight mode, no cellular radio, or unusable GNSS should pause sample production and surface a clear reason rather than silently converting the scanner to stopped. When the error clears and the user intent is still running, the scanner should resume when Android allows it. Force-stop, uninstall, app update/reinstall, process death, and reboot are separate lifecycle cases.

Active scanning should run in a foreground service with a visible notification. The scanner state model is stopped, running, or error. Running means active sample production; error means scanning is still desired but samples cannot currently be produced. The UI should mirror the same state and keep the reason/action guard visible.

Initial versions should assume local-first sample persistence. Reporting behavior must stay consent-gated, clearly explained, and controlled by the reporting settings.

The current prototype uses a foreground `ScannerService` with mock radio/GNSS sources. Accepted samples are written to a local Room/SQLite database with upload bookkeeping fields and reporting batches. Reporting goes through a small `ReportingTransport` boundary; the current default posts JSONL batches to the configured HTTP endpoint, with a mock transport still available in `AppConfig.Reporting` for development. JSONL is internal to reporting/compatibility and must not be presented as the normal user export format. CSV is generated on demand for user-facing export. Settings includes reporting controls, a recorded coverage data view with CSV export actions, clearing already-reported local samples, deleting exported CSV cache files, and deleting all local coverage samples after confirmation.

The Android application id, manifest namespace, and Kotlin base package are all `no.politiet.pit.fivegscanner`.

Telemetry source selection goes through `TelemetrySourceFactory`. The About screen includes a Developer `Mock telemetry` toggle. Physical-device fresh installs default to Android telemetry. Emulators force mock telemetry regardless of the saved toggle so emulator development stays simulator-friendly. Android telemetry routes through collector classes behind the same `RadioTelemetrySource` and `GnssTelemetrySource` interfaces; the Android radio source uses TelephonyCallback on API 31+ and PhoneStateListener/requestCellInfoUpdate on API 29-30, while the Android GNSS source uses LocationManager with adaptive low/balanced/high request tiers based on movement speed. Radio callbacks update latest state and may request a debounced near-term scanner tick, but samples are still assembled by pairing latest radio with latest acceptable GNSS. The scanner UI should indicate active mock telemetry with a subtle `MOCK` badge.

The About -> Developer section also has a compact telemetry diagnostics row for field testing. Keep the row practical and scan-friendly: it should summarize whether telemetry is mock or Android, whether sources are running or waiting, and either recent radio/GNSS/sample ages or the latest sample skip reason. Tapping the row opens a copyable diagnostics dialog with source type, radio source count, radio/GNSS update ages, current GNSS tier where available, last sample attempt age, and latest sample skip reason. This is field-test feedback, not a general debug console.

Settings-style screens should stay on the shared RecyclerView path. Use stable row keys and data-driven content keys so RecyclerView preserves viewport state during label/sample refreshes. Avoid custom scroll restoration, direct TextView refresh fields, `notifyDataSetChanged`, or per-screen list refresh logic for Settings, Recorded Coverage Data, or About.

## Product Notes

Keep the first usable UI small:

- Consent/onboarding
- Main scanner status and telemetry
- A single stop/start control on the main scanner screen
- Separate settings screen for less frequent controls

Use clear, human language around what is collected, why it is collected, where it is stored, and how participation can pause or stop. The tone should support a normal participant who is helping build a coverage map by carrying their phone, not someone debugging radio metrics. Avoid dark patterns around consent, permissions, scanning state, deletion, or later reporting behavior.

When adding settings, keep the default path simple. Favor controls that support the crowdsourcing participant, such as pause/resume, pause until a date/time, battery-aware location behavior, reporting visibility, and local data deletion. Keep engineer-oriented controls discoverable but secondary.

Relevant planning docs:

- `docs/product-notes.md`
- `docs/privacy-consent.md`
- `docs/settings-model.md`

## Development Preferences

Prefer small, explicit models and tests around the output contract.

Agent work should focus on coding, testing, debugging, and documentation. Avoid spending tokens on routine Git operations unless explicitly requested.

Do not run `git add`, `git commit`, `git push`, create branches, or open pull requests unless the user explicitly asks for that Git action. Instead, describe the changes made, suggest a commit message when useful, and let the developer perform Git operations manually.

## Token-Saving Preferences

Keep routine progress updates brief. For small, clear tasks, implement directly without a separate plan.

Avoid explaining standard tools, framework concepts, command output, or unchanged code unless it affects the work. Summarize only the relevant part of long logs or test failures.

Inspect the files needed to make safe changes, but avoid broad repository tours unless the task requires architectural context.

Final responses should be concise: describe what changed, what verification ran, and any important remaining risk or follow-up.

Before making a commit, update `AGENTS.md` and `README.md` when the change affects project structure, setup steps, architecture direction, development workflow, or user-facing project status. Also check whether the change modified Room database persistence: entities, DAOs, indices, database version, database provider setup, or persisted settings keys/default semantics. If it did, explicitly tell the user before commit and ask whether this should stay a clean-install dev baseline change or become a migration step.

Release prep is an explicit exception to the normal token-saving posture. When the user asks `prepare release` or `prepare release x.y.z`, perform a broader release audit: inspect changed files, classify Room persistence changes, persisted settings changes, JSONL reporting contract changes, CSV export contract changes, user-facing behavior/text changes, docs, tests, and version metadata. Ask the user for SemVer and migration decisions when needed. Update version metadata, docs, `RELEASE_NOTES.md`, and release checklist items as appropriate. Treat repeated `prepare release` calls as idempotent for the current pending release: refresh the audit, release notes, docs, and verification, but do not bump to a new version unless the user explicitly supplies or approves a different SemVer. Do not run `git add`, `git commit`, `git push`, create branches, or open pull requests unless the user explicitly asks for that Git action. Prefer release preparation and committing as separate actions: first prepare and review, then commit only if the user explicitly says `commit` or otherwise asks for a Git commit.

Use `.\gradlew.bat assembleDebug` for the normal Android build verification and `.\gradlew.bat testDebugUnitTest` for local JVM unit tests.

When adding Android code, keep modules separated roughly as:

- `assets/icon/` for the canonical app icon SVG and generated favicon assets
- `app/` for UI and app wiring
- `core/` for domain models and JSONL encoding
- `telemetry/` for Android connectivity/location collectors
- `storage/` for database persistence, reporting state, and CSV export
- `docs/` for schema and architecture notes
- `samples/` for copied or minimized example coverage samples
- `scripts/` for maintenance scripts such as icon generation

App icons are based on `assets/icon/source.svg`. Android launcher icons use adaptive icon XML plus `app/src/main/res/drawable/ic_launcher_foreground.xml`; run `npm run icons` after changing the source to refresh generated favicon files.

Do not commit large generated datasets unless they are intentionally curated fixtures.

Runtime defaults and tuning values that are natural to adjust during development belong in `app/src/main/java/no/politiet/pit/fivegscanner/AppConfig.kt` rather than being redefined across Activity, service, storage, reporting, or telemetry classes. Reporting endpoint and transport-mode defaults belong there too; the current default is the hosted Render mock endpoint. Temporary field-test endpoint overrides can be set from About -> Developer -> Reporting endpoint; the override is saved only after URL validation and an `OPTIONS` response that advertises `POST` support.

Use SemVer for app releases. Before the first real release, `0.x` versions may make breaking prototype changes as long as they are called out. After a release/shared build may contain user data or downstream consumers, use: MAJOR for breaking compatibility changes, MINOR for backward-compatible features, and PATCH for fixes/docs/polish.

Maintain `RELEASE_NOTES.md` as the canonical release notes file. Every release prep must add or update exactly one `## x.y.z` entry for the release being prepared. If that entry already exists, update it in place as the pending release notes; do not create another entry or bump the version just because release prep was run again. The entry must be a concise bullet list of relevant changes since the previous release, including user-visible behavior, compatibility/migration decisions, data/reporting/export changes, and release-relevant known limits. Do not list every internal refactor. If there are no release-relevant changes, say so in the final response instead of padding the notes.

Separate compatibility buckets clearly. Room migrations are only for local persistence compatibility: Room entities/tables/columns, DAOs that assume stored data shape, indices when they affect the installed schema, database version/provider setup, and persisted settings keys/default semantics when old saved settings must survive. CSV export shape and JSONL reporting shape are contract/versioning concerns, not Room migration concerns, unless their change also requires changing persisted local data.

Until the first real release or external install base, Room schema changes may reset the development baseline at database version 1 and require clean installs/app data clears. Do not accumulate Room migrations for disposable emulator/dev cycles by default. Every Room persistence/schema change must still be called out to the user, who decides whether the change remains a clean-install dev baseline change or needs a migration. Once a release/shared build may contain user data worth preserving, treat that schema as a baseline: breaking database changes must bump the Room version and add a migration, while non-breaking code-only changes should not.
