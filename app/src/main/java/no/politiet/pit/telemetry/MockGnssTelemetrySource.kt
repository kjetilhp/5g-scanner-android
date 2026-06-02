package no.politiet.pit.telemetry

import no.politiet.pit.domain.Fix
import no.politiet.pit.domain.GnssMode
import java.time.Instant

class MockGnssTelemetrySource : GnssTelemetrySource {
    override fun latest(
        sampleNumber: Int,
        capturedAt: Instant,
        gnssMode: GnssMode,
    ): GnssTelemetry {
        val wave = sampleNumber % 24
        val fixAgeSeconds = fixAgeSeconds(sampleNumber, gnssMode)
        val hdop = hdop(sampleNumber, gnssMode)

        return GnssTelemetry(
            receivedAt = capturedAt,
            fixAgeSeconds = fixAgeSeconds,
            horizontalAccuracyMeters = horizontalAccuracyMeters(sampleNumber, gnssMode),
            fix = Fix(
                timestamp = capturedAt,
                gpsTime = null,
                lat = 59.9139 + wave * 0.00008,
                lon = 10.7522 + wave * 0.00011,
                altitude = 23.0 + (wave % 5),
                speed = if (gnssMode == GnssMode.HighAccuracy) 1.6 else 0.8,
                heading = ((sampleNumber * 17) % 360).toDouble(),
                hdop = hdop.toDouble(),
                satellites = if (gnssMode == GnssMode.HighAccuracy) 18 else 11,
            ),
        )
    }

    private fun hdop(sampleNumber: Int, gnssMode: GnssMode): Float {
        if (shouldEmitRejectedFix(sampleNumber)) return 5.6f

        val wave = sampleNumber % 6
        val baseHdop = if (gnssMode == GnssMode.HighAccuracy) 0.7f else 1.3f
        val hdopOffsets = floatArrayOf(2.2f, 1.1f, 0.1f, 0.8f, 2.7f, 0.0f)
        return baseHdop + hdopOffsets[wave]
    }

    private fun fixAgeSeconds(sampleNumber: Int, gnssMode: GnssMode): Int {
        if (shouldEmitRejectedFix(sampleNumber)) return 42

        val wave = sampleNumber % 6
        val ageValues = if (gnssMode == GnssMode.HighAccuracy) {
            intArrayOf(14, 8, 2, 5, 18, 3)
        } else {
            intArrayOf(24, 15, 5, 10, 30, 6)
        }
        return ageValues[wave]
    }

    private fun horizontalAccuracyMeters(sampleNumber: Int, gnssMode: GnssMode): Float {
        if (shouldEmitRejectedFix(sampleNumber)) return 120f

        val wave = sampleNumber % 6
        val accuracyValues = if (gnssMode == GnssMode.HighAccuracy) {
            floatArrayOf(18f, 12f, 7f, 10f, 24f, 6f)
        } else {
            floatArrayOf(34f, 26f, 14f, 22f, 48f, 12f)
        }
        return accuracyValues[wave]
    }

    private fun shouldEmitRejectedFix(sampleNumber: Int): Boolean =
        sampleNumber > 0 && sampleNumber % 6 == 0
}
