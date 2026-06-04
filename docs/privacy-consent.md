# Privacy and Consent

## Principles

The app should be built around informed, reversible participation. The user is helping build a coverage map by carrying their phone; consent text should be plain, concrete, and free of power-user language.

- Consent must be explicit before scanning begins
- Android permissions must be requested only with context
- Revoking consent must stop scanning
- Permission revocation must be handled gracefully
- Reporting/upload behavior must not be added or changed silently
- Local coverage samples should be deletable by the user

This document is product guidance, not legal advice.

## Consent vs Permissions

Track app-level consent separately from Android OS permission grants.

```text
App consent:
  The user agrees to participate in coverage data collection.

Android permissions:
  The operating system grants access to location, phone/network state, notifications, and any other required APIs.
```

The app can scan only when both consent and required permissions are present.

## Suggested Consent State

```text
NotAsked
Granted(version, timestamp)
Revoked(timestamp)
```

Keep a consent text or privacy notice version from the beginning, even if the first version is just `1`. If collection, retention, or upload behavior changes later, users can be asked to review and re-consent.

## Data Collection Scope

The first planned scope is:

- Location fix data
- Timestamp data
- Cellular radio information
- Connectivity/network state
- Carrier information
- Signal metrics where available
- Device make, OS version, and other device/network metadata needed to interpret samples

Avoid collecting personal account data or contact/user identifiers unless a later feature explicitly requires it and the consent model is updated.

User-facing copy should state that coverage data is intended for coverage mapping and is not intended to identify the participant or be traced back to them.

User-facing copy should also make the purpose clear: the app measures 5G/LTE signal quality over time in real places where participants work and travel.

## Local-First Reporting

Initial behavior:

- Store coverage samples locally on device first
- Report according to the user's reporting setting
- Allow scanning to be stopped
- Allow local data deletion
- Allow user-facing CSV export/share

Material reporting/upload changes should require:

- Updated privacy documentation
- Explicit user-facing consent
- Clear destination and purpose
- Clear retention/deletion story

## Revocation Behavior

When consent is revoked:

- Stop active scanning
- Prevent scheduled/background scanner work
- Keep Android permissions unchanged unless the user chooses to revoke them in OS settings
- Offer local data deletion when that feature exists

When Android permissions are revoked:

- Stop or block scanner work that needs those permissions
- Show a clear missing-permission state
- Keep app-level consent unchanged unless the user revokes it

## Background Scanning

If background scanning is supported, it should use Android foreground service patterns with a visible notification while active. The user should be able to return to the app and stop scanning from the main screen.

Background scanning is the intended normal experience after consent. Stopping or future pause-until controls should be presented as ordinary ways to pause participation for off hours, battery conservation, or user choice.
