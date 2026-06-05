package no.politiet.pit.telemetry

import no.politiet.pit.domain.GnssMode
import java.time.Instant

interface RadioTelemetrySource {
    fun start(onImportantRadioChange: () -> Unit = {}) = Unit
    fun stop() = Unit
    fun latest(sampleNumber: Int, capturedAt: Instant): RadioTelemetry?
    fun latestAll(sampleNumber: Int, capturedAt: Instant): List<RadioTelemetry> =
        latest(sampleNumber, capturedAt)?.let(::listOf).orEmpty()
    fun diagnostics(): RadioSourceDiagnostics = RadioSourceDiagnostics()
}

interface GnssTelemetrySource {
    fun start(gnssMode: GnssMode = GnssMode.Balanced) = Unit
    fun stop() = Unit
    fun latest(sampleNumber: Int, capturedAt: Instant, gnssMode: GnssMode): GnssTelemetry?
    fun diagnostics(): GnssSourceDiagnostics = GnssSourceDiagnostics()
}

data class RadioSourceDiagnostics(
    val sourceCount: Int = 0,
    val latestUpdateAt: Instant? = null,
)

data class GnssSourceDiagnostics(
    val activeTier: String? = null,
    val latestUpdateAt: Instant? = null,
)
