# Settings Model

## Goals

Settings should be simple, understandable, and hard to misconfigure.

Prefer a small set of choices over free-form expert controls in the first version.

## Suggested Settings

### Scanning Toggle

```text
scannerEnabled: Boolean
```

The main screen owns this control. Turning it off should stop active scanner work.

### Pause Mode

Use one pause model instead of separate temporary-disable concepts.

```text
PauseMode:
  None
  Manual
  Until(timestamp)
```

Examples:

- No pause: scanner may run if all other requirements are met
- Manual pause: disabled until the user resumes
- Pause until: disabled until a chosen date/time

### Sampling Frequency

Start with presets:

```text
Low impact: 60 seconds
Balanced: 15 seconds
High detail: 5 seconds
Debug: 1 second
```

The stored value can be milliseconds, but the UI should use human labels. Android may not deliver fresh telephony or location data exactly at the selected interval, so samples must always include actual timestamps.

### GNSS / Location Mode

Start with simple intent-based modes:

```text
Balanced
HighAccuracy
```

Optional later modes:

```text
DeviceOnly
Passive
```

The implementation can decide later whether to use Android platform location APIs, Google Play Services fused location, or both.

### Reporting Mode

Reporting controls how locally stored coverage logs leave the device.

```text
ReportingMode:
  Hourly
  Daily
  Continuous
  Manual
```

Examples:

- Hourly: upload saved logs about once per hour for normal crowdsourcing while early reporting is being validated
- Daily: upload saved logs about once per day for lower-impact crowdsourcing
- Continuous: upload new samples soon after they are collected for developers, engineers, and live field testing
- Manual: keep logs on the device until the user exports them, shares them, or uses a manual send action

The scanner should still write locally first. Upload failures must leave data queued locally for later retry or manual export.

Hourly is the initial default so early server-side reporting can be validated with reasonably fresh data while keeping ordinary crowdsourcing impact controlled. Daily may become the default later if collected data shows that a lower upload frequency is sufficient. Continuous is primarily for live field testing and should not be presented as the normal user path.

Settings should always show Last sent as supporting text inside the reporting mode row. Last sent is app-wide reporting state and updates only after a successful network upload or prototype no-op upload succeeds.

When Manual, Daily, or Hourly is selected, settings should also show a Send now action below the reporting mode row. It is a one-time manual upload action and should not change the automatic reporting mode. Continuous does not show Send now because it reports when new measurements are collected.

When Daily or Hourly is selected, the app should schedule reporting from the current setting with Android's native scheduler. Continuous should not use the periodic scheduler; it should report when new signal measurements are collected. Until the first successful reporting trigger, Last sent should read `never`. The prototype reporting path makes a lightweight no-op network call and updates Last sent only when that call succeeds. Reporting is gated by app-level consent and network availability.

### Consent and Permissions

Settings should include status and actions for:

- App-level consent
- Required Android permissions
- Link to OS permission settings when needed
- Revoke consent

Consent revocation should stop scanning even if Android permissions remain granted.

## Effective Scanner Eligibility

Scanner work can run only if:

```text
consent == Granted
requiredPermissions == Granted
scannerEnabled == true
pauseMode == None or pause-until time has passed
```

The app should derive one effective state from these inputs and show that state in the UI.

## Future Settings

Defer these until the app has basic local logging:

- Upload/sync destination
- Wi-Fi-only upload
- Data retention period
- Manual export format
- Debug verbosity
- Dual-SIM selection
