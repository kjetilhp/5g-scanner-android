# Field Test Note Template

Copy this into a dated note when testing on a physical device.

## Test Setup

- Date/time:
- Tester:
- Area/route:
- Phone make/model:
- Android version/API:
- App version/build:
- Carrier/SIM setup:
- Battery mode:
- Screen behavior tested: screen on / screen off / both
- Reporting endpoint:
- Mock telemetry: on / off
- GNSS mode:
- Reporting mode:
- Enhanced privacy: on / off

## Before Starting

- Fresh install or existing install:
- Consent flow result:
- Permissions granted:
- Backend reachable from device:
- About -> Developer telemetry diagnostics row before scanning:

```text
paste row summary here
```

- About -> Developer telemetry diagnostics details before scanning:

```text
paste diagnostics here
```

## Scanning Observations

- Scanner state after start:
- First usable sample time:
- Samples recorded:
- Recent sample details checked:
- LTE fields observed:
- NR fields observed:
- Radio source count:
- Carrier/SIM labels seen:
- GNSS tier changes observed:
- Stationary behavior:
- Walking behavior:
- Driving behavior:
- Screen-off behavior:
- Location off/on behavior:
- Airplane mode off/on behavior:
- Foreground notification behavior:
- Battery/heat notes:

## Reporting Observations

- Queued sample count before send:
- Manual Send now result:
- Scheduled/continuous reporting result:
- Backend received POST:
- Reported sample count:
- Failure behavior tested:
- Logcat request/response notes:

## Diagnostics Snapshot

- About -> Developer telemetry diagnostics row after the main test:

```text
paste row summary here
```

- About -> Developer telemetry diagnostics details after the main test:

```text
paste diagnostics here
```

## Issues

- Unexpected behavior:
- Reproduction steps:
- Relevant logcat lines:
- Sample IDs/timestamps:
- Follow-up needed:

## Verdict

- Ready for longer test: yes / no
- Main blocker:
- Next test focus:
