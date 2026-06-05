# Product Notes

## User Promise

This app lets a user voluntarily help build a real 5G/LTE coverage data set with their Android phone.

The normal experience is simple: install the app, grant consent, and let it work quietly in the background. The app should collect useful signal/location samples over time in the places participants actually work and travel.

The app only collects coverage data. It is not a speed test, navigation app, or network optimization tool.

The app should only scan when all of these are true:

- The user has granted app-level consent
- The required Android permissions are granted
- Scanning is not stopped by the user

The first version should store samples locally first. Reporting is controlled by a small reporting-mode setting and must remain consent-gated, transparent, and easy to explain.

Most users should rarely change settings. Engineers may inspect recorded samples or adjust behavior more often, but engineer workflows should remain secondary to the crowdsourcing participant experience.

## First Screens

### Consent and Onboarding

The first-run flow should explain in plain language:

- What is collected: location, timestamps, cellular/network metrics, and relevant device/network metadata
- Why it is collected: voluntary crowdsourced coverage mapping
- Where it goes: local device storage first, with periodic reporting according to the user's reporting setting
- How reporting is controlled: Every 15 minutes, Hourly, Daily, Continuous, Manual, and Send now
- How the user can stop scanning for off hours or battery conservation, and how participation can stop entirely

Consent should be tracked by the app separately from Android OS permission grants.

### Scanner

The main screen should be quiet and practical:

- Large start/stop scanning control
- Current state: stopped, running, error, or consent required
- Last sample time
- Samples collected in the current session
- Local recorded-data status
- Optional current radio summary once available

The user should never need to understand radio engineering details to know whether the app is scanning.

### Settings

Settings should favor a small number of understandable controls:

- Start/stop scanning, with future pause-until support if it proves useful
- Location/GNSS behavior
- Reporting mode and last sent status
- Recorded coverage data inspector with CSV export
- Consent and permission status, with in-app revocation deferred until the consent model is expanded
- Local data deletion

Sampling frequency should not be user-configurable until changing it would reliably change real collection behavior across supported phones. The prototype should use a fixed internal cadence.

## Scanner State

Prefer one effective scanner state derived from consent, permissions, user intent, and environment guards.

Example states:

```text
ConsentRequired
PermissionRequired
Stopped
Running
Error(message)
```

The UI should show the effective state, not several competing flags.

## Design Tone

The app should feel transparent and calm. The user is volunteering; the interface should make participation feel informed, reversible, and under their control.
