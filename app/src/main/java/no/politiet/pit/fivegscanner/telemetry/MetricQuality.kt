package no.politiet.pit.fivegscanner.telemetry

data class MetricQuality(
    val label: String,
    val valueText: String,
    val quality: Float,
    val kind: MetricKind,
    val isUsable: Boolean = true,
)
