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
