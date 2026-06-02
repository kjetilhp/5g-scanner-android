package no.politiet.pit.telemetry

import no.politiet.pit.domain.GnssMode
import java.time.Instant

interface RadioTelemetrySource {
    fun latest(sampleNumber: Int, capturedAt: Instant): RadioTelemetry?
}

interface GnssTelemetrySource {
    fun latest(sampleNumber: Int, capturedAt: Instant, gnssMode: GnssMode): GnssTelemetry?
}
