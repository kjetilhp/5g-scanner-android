package no.politiet.pit.domain

enum class GnssMode(val label: String, val summary: String) {
    Balanced("Balanced", "Use location with moderate power impact"),
    HighAccuracy("High accuracy", "Prefer the most precise available location");

    companion object {
        fun fromName(value: String?): GnssMode =
            entries.firstOrNull { it.name == value } ?: Balanced
    }
}
