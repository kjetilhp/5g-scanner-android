# Product Notes

## User Promise

This app lets a user voluntarily help map 5G/LTE coverage with their Android phone.

The app should only scan when all of these are true:

- The user has granted app-level consent
- The required Android permissions are granted
- Scanning is not stopped

The first version should log locally first. Reporting is controlled by a small reporting-mode setting and must remain consent-gated, transparent, and easy to explain.

## First Screens

### Consent and Onboarding

The first-run flow should explain in plain language:

- What is collected: location, timestamps, cellular/network metrics, and relevant device/network metadata
- Why it is collected: voluntary crowdsourced coverage mapping
- Where it goes: local device storage first, with periodic reporting according to the user's reporting setting
- How reporting is controlled: Hourly, Daily, Continuous, Manual, and Send now
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
- Location/GNSS behavior
- Reporting mode and last sent status
- Coverage log CSV preview/share
- Consent and permission management
- Local data deletion

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
