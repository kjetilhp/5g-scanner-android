package app.fivegscanner.telemetry

import app.fivegscanner.domain.Fix
import java.time.Instant

data class GnssTelemetry(
    val receivedAt: Instant,
    val fix: Fix,
    val fixAgeSeconds: Int,
    val horizontalAccuracyMeters: Float?,
)
