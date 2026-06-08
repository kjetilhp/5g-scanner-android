# Device Testing Checklist

Use this before moving from emulator/mock testing into physical-device field testing. Copy `docs/field-test-note-template.md` into a short dated note when recording a physical-device test.

## Before Installing

- Confirm the backend is reachable from the target device.
- For emulator testing, use About -> Developer -> Reporting endpoint with `AppConfig.Reporting.emulatorHostEndpointUrl`.
- For physical-device testing, use About -> Developer -> Reporting endpoint with a URL the phone can reach, such as the host PC's LAN IP.
- Run `.\gradlew.bat testDebugUnitTest`.
- Run `.\gradlew.bat assembleDebug`.
- Install a fresh debug APK.

## First Launch

- Consent gate appears before scanner controls.
- Granting consent requests the required Android permissions with no dead end.
- After permissions are granted, scanning starts when the scanner toggle is running.
- Mock telemetry badge appears on emulator.
- On a physical device, mock telemetry is off by default and the main scanner screen does not show a mock badge.
- On a physical device, Android radio/GNSS telemetry starts without visiting Developer settings when permissions and device state allow it.
- Enabling mock telemetry from About -> Developer switches the scanner to mock sources for development.
- Scanner, consent, and settings screens use readable status/navigation bar icon contrast.
- Consent and settings screens follow the device light/dark mode.

## Scanning State

- Stop scanning from the main screen.
- Relaunch the app and confirm scanning remains stopped.
- Start scanning again and confirm sample count increases.
- On a physical device, confirm logcat shows Android radio telemetry registration and LTE/NR serving-cell updates when the device exposes them.
- On a physical device, confirm logcat shows Android GNSS request tier changes and location fixes when location is enabled.
- In About -> Developer, confirm the telemetry diagnostics row gives a compact status summary and opens a copyable details dialog with Android/mock telemetry, started/waiting state, radio source count, radio/GNSS update ages, GNSS tier, and any sample skip reason.
- Walk or drive briefly and confirm GNSS request tier moves toward balanced/high when speed increases, then back toward low when stationary.
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
- Continuous mode reports pending samples after sample writes and drains pending batches up to the configured per-trigger cap.
- If the app is killed during an upload, confirm a later reporting trigger recovers the stale in-flight batch and retries it.

## Recorded Coverage Data

- Recorded coverage data summary reads correctly with zero samples.
- Recorded coverage data summary reads correctly with samples.
- Clear reported samples leaves queued/unreported samples intact.
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
- Reboot the device; confirm active scanning does not auto-restart before the app is launched.
- Confirm active scanning uses a foreground notification.
- Confirm notification text changes when scanning needs attention.

## Known Prototype Limits

- Automatic scanner restart after reboot is not fully enabled yet for physical-device field use.
- Android radio collection has a first LTE/NR serving-cell implementation, but still needs real-device validation across phone models, carriers, SIM states, NSA/SA behavior, and screen-off conditions.
- Android GNSS/location collection has a first adaptive `LocationManager` implementation, but still needs real-device validation for provider behavior, speed-based tier changes, accuracy, screen-off behavior, and battery impact.
- Cleartext HTTP is enabled for the prototype endpoint and should be replaced by TLS before production use.
