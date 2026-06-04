package app.fivegscanner.telemetry

import app.fivegscanner.domain.GnssMode
import java.time.Instant

interface RadioTelemetrySource {
    fun latest(sampleNumber: Int, capturedAt: Instant): RadioTelemetry?
}

interface GnssTelemetrySource {
    fun latest(sampleNumber: Int, capturedAt: Instant, gnssMode: GnssMode): GnssTelemetry?
}
