package no.politiet.pit.fivegscanner.telemetry

import no.politiet.pit.fivegscanner.domain.Fix
import java.time.Instant

data class GnssTelemetry(
    val receivedAt: Instant,
    val fix: Fix,
    val fixAgeSeconds: Int,
    val horizontalAccuracyMeters: Float?,
)
