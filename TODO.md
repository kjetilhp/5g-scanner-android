# Developer list of things to do, verify or consider

Keep this file practical: near-term tasks, open product questions, and parked ideas.

---

- Run the device testing checklist before starting real collector work: `docs/device-testing-checklist.md`

- Decide whether a "5G only / LTE + 5G" collection setting is useful. LTE is still valuable context for real 5G coverage gaps, so avoid adding this unless field tests show a clear need.

- Decide the product/privacy path for true long-running background collection across reboots. The current prototype restores reporting alarms after boot, but does not restart active scanning from boot.

- Add a participation schedule if field testing shows users need work-hours/weekdays scanning. Model it as scanner intent scheduling, not as a second scanner state.

- Consider a one-off reminder notification if scanning has been manually stopped for a long time. Keep it respectful and easy to dismiss.

- Confirm local database, settings, and exported CSV cache behavior on uninstall/reinstall during device testing.

- Add retention/storage controls if field tests show local database growth is a real issue. Prefer clear user-facing language such as "recorded coverage data limit" over internal cleanup terms.

- Before a release/shared build where updates must preserve user data, add Room migration tests/schema export and freeze that installed schema as the migration baseline.

- Improve scanner state visibility outside the app: foreground notification color/text, stopped/error clarity, and whether the launcher/app notification accent can match the product colors.

- Two or more SIMs: sampler should collect all usable active subscriptions; later UI should show which SIM/subscription is displayed and possibly allow swipe left/right between them.

- Keep settings screens on the RecyclerView path. Avoid custom scroll restoration, direct TextView refresh fields, or per-screen list refresh logic.

- Field-test the About -> Developer telemetry diagnostics row. It should stay compact in the row and put copyable details in the dialog; add fields only when they answer "is telemetry alive, and where is it stuck?"

- Keep screen awake option?

- Balanced GPS is not working as expected. Nor hogh accuracy. Powersaving on device kicking in? What should we do?

- Light phone statusbar. Black on blue is not great.

- App settings should follow device dark/light mode.

- Got a missing phone permissions on device. Is this required? If so ask on launch. "Also add a tap fo go to settings" or gear icon to make it clear the user can launch device settings from here.
