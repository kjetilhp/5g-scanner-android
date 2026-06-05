package no.politiet.pit.telemetry

import no.politiet.pit.domain.GnssMode
import java.time.Instant

interface RadioTelemetrySource {
    fun start(onImportantRadioChange: () -> Unit = {}) = Unit
    fun stop() = Unit
    fun latest(sampleNumber: Int, capturedAt: Instant): RadioTelemetry?
    fun latestAll(sampleNumber: Int, capturedAt: Instant): List<RadioTelemetry> =
        latest(sampleNumber, capturedAt)?.let(::listOf).orEmpty()
}

interface GnssTelemetrySource {
    fun start() = Unit
    fun stop() = Unit
    fun latest(sampleNumber: Int, capturedAt: Instant, gnssMode: GnssMode): GnssTelemetry?
}
