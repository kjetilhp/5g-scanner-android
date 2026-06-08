package no.politiet.pit.fivegscanner.domain

enum class ReportingMode(val label: String, val summary: String) {
    Every15Minutes("Every 15 minutes", "Report saved coverage samples about every 15 minutes"),
    Hourly("Hourly", "Report saved coverage samples about once per hour"),
    Daily("Daily", "Report saved coverage samples about once per day"),
    Continuous("Continuous", "For live field testing. Uses more battery and network"),
    Manual("Manual", "Keep samples on this device until you send or export them");

    companion object {
        fun fromName(value: String?): ReportingMode =
            entries.firstOrNull { it.name == value } ?: Every15Minutes
    }
}
