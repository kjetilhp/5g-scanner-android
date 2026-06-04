package no.politiet.pit.telemetry

import android.content.Context
import no.politiet.pit.domain.GnssMode
import java.time.Instant

class AndroidGnssTelemetrySource(
    @Suppress("unused") private val context: Context,
) : GnssTelemetrySource {
    override fun latest(
        sampleNumber: Int,
        capturedAt: Instant,
        gnssMode: GnssMode,
    ): GnssTelemetry? {
        // Real location permission/provider wiring will land behind this source.
        return null
    }
}
