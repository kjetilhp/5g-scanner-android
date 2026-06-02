package no.politiet.pit.telemetry

import no.politiet.pit.domain.Fix
import java.time.Instant

data class GnssTelemetry(
    val receivedAt: Instant,
    val fix: Fix,
    val fixAgeSeconds: Int,
    val horizontalAccuracyMeters: Float?,
)
