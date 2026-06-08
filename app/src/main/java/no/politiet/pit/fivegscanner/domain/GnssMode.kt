package no.politiet.pit.fivegscanner.domain

enum class GnssMode(val label: String, val summary: String) {
    Balanced("Balanced", "Saves battery when still or moving slowly, then asks for fresher fixes while driving"),
    HighAccuracy("High accuracy", "Best for active mapping and driving, with higher battery use"),
    LowPower("Low power", "Uses fewer location updates; some driving samples may be skipped");

    companion object {
        fun fromName(value: String?): GnssMode =
            entries.firstOrNull { it.name == value } ?: Balanced
    }
}
