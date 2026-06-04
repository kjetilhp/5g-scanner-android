# Device Testing Checklist

Use this before moving from emulator/mock testing into physical-device field testing. Keep notes in `TODO.md` or a short dated test note when something behaves differently from expected.

## Before Installing

- Confirm the backend is reachable from the target device.
- For emulator testing, use `AppConfig.Reporting.emulatorHostEndpointUrl`.
- For physical-device testing, set `AppConfig.Reporting.physicalDeviceEndpointUrl` to a URL the phone can reach, then point `AppConfig.Reporting.endpointUrl` at it.
- Run `.\gradlew.bat testDebugUnitTest`.
- Run `.\gradlew.bat assembleDebug`.
- Install a fresh debug APK.

## First Launch

- Consent gate appears before scanner controls.
- Granting consent requests the required Android permissions with no dead end.
- After permissions are granted, scanning starts when the scanner toggle is running.
- Mock telemetry badge appears on emulator.
- On a physical device, mock telemetry can be disabled from About.

## Scanning State

- Stop scanning from the main screen.
- Relaunch the app and confirm scanning remains stopped.
- Start scanning again and confirm sample count increases.
- Turn location off and confirm the app shows an error state instead of silently stopping.
- Turn location back on and confirm scanning can resume.
- Enable airplane mode and confirm the app shows an error state.
- Disable airplane mode and confirm scanning can resume.

## Reporting

- With no queued samples, Send now says recorded coverage data is up to date and does not POST.
- With queued samples and backend running, Send now POSTs `application/x-ndjson` to the configured endpoint.
- A successful POST marks samples as reported.
- Recent samples show reported/upload status in details without raw JSON.
- Stop the backend and send again.
- Confirm the UI shows a short human-readable error.
- Confirm logcat includes HTTP request/response details for the failed request.
- Restart the backend and confirm a later manual or scheduled send succeeds.
- Continuous mode reports pending samples after sample writes and drains all pending batches.

## Recorded Coverage Data

- Recorded coverage data summary reads correctly with zero samples.
- Recorded coverage data summary reads correctly with samples.
- Delete reported samples leaves queued/unreported samples intact.
- Delete all clears samples, reporting batches, and exported CSV cache.
- Export CSV creates a shareable CSV file.
- Delete exported CSV files appears only when cached export files exist.

## Enhanced Privacy

- Enable Enhanced privacy.
- Record a new sample.
- Recent samples show the Enhanced privacy indicator.
- Sample details show date only, not precise time.
- Reported/exported sample has UTC-midnight timestamps.
- Reported/exported sample has less precise location details.
- Reported/exported sample omits speed and heading.
- Reported/exported sample uses privacy-mode GNSS detail values.
- Disable Enhanced privacy and confirm new samples are no longer marked as enhanced privacy.

## App Lifecycle

- Relaunch the app and confirm settings persist.
- Force-stop and reopen the app; confirm scanner intent/settings are preserved.
- Reboot the device; confirm reporting schedule is restored after launch/boot where Android allows it.
- Confirm active scanning uses a foreground notification.
- Confirm notification text changes when scanning needs attention.

## Known Prototype Limits

- Automatic scanner restart after reboot is not fully enabled yet for physical-device field use.
- Real Android radio/GNSS collectors are still placeholders until mock telemetry is disabled and collector work begins.
- Cleartext HTTP is enabled for the prototype endpoint and should be replaced by TLS before production use.
