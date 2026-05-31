# Product Notes

## User Promise

This app lets a user voluntarily help map 5G/LTE coverage with their Android phone.

The app should only scan when all of these are true:

- The user has granted app-level consent
- The required Android permissions are granted
- The user has turned scanning on
- Scanning is not temporarily paused

The first version should log locally only. Automatic upload, account linking, server sync, or public map contribution are deferred until a later explicit design and consent decision.

## First Screens

### Consent and Onboarding

The first-run flow should explain in plain language:

- What is collected: location, timestamps, cellular/network metrics, and relevant device/network metadata
- Why it is collected: voluntary crowdsourced coverage mapping
- Where it goes initially: local device logs
- What is not active yet: automatic upload/sync
- How the user can stop scanning or revoke consent

Consent should be tracked by the app separately from Android OS permission grants.

### Scanner

The main screen should be quiet and practical:

- Large scanning on/off control
- Current state: idle, scanning, paused, missing permission, or consent required
- Last sample time
- Samples collected in the current session
- Local log status
- Optional current radio summary once available

The user should never need to understand radio engineering details to know whether the app is scanning.

### Settings

Settings should favor a small number of understandable controls:

- Pause/resume scanning
- Pause scanning until a date/time
- Location/GNSS behavior
- Consent and permission management
- Local data deletion/export later

Sampling frequency should not be user-configurable until changing it would reliably change real collection behavior across supported phones. The prototype should use a fixed internal cadence.

## Scanner State

Prefer one effective scanner state derived from consent, permissions, user toggle, and pause settings.

Example states:

```text
ConsentRequired
PermissionRequired
Idle
Scanning
PausedUntil(timestamp)
PausedManually
Error(message)
```

The UI should show the effective state, not several competing flags.

## Design Tone

The app should feel transparent and calm. The user is volunteering; the interface should make participation feel informed, reversible, and under their control.
