package no.politiet.pit.domain

enum class ReportingMode(val label: String, val summary: String) {
    Hourly("Hourly", "Report saved coverage logs about once per hour"),
    Daily("Daily", "Report saved coverage logs about once per day"),
    Continuous("Continuous", "For live field testing. Uses more battery and network"),
    Manual("Manual", "Keep logs on this device until you send or export them");

    companion object {
        fun fromName(value: String?): ReportingMode =
            entries.firstOrNull { it.name == value } ?: Hourly
    }
}
