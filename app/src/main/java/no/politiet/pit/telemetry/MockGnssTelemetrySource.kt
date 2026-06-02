package no.politiet.pit.telemetry

import no.politiet.pit.domain.Fix
import no.politiet.pit.domain.GnssMode
import java.time.Duration
import java.time.Instant

class MockGnssTelemetrySource : GnssTelemetrySource {
    private var latestFix: MockFixSnapshot? = null

    override fun latest(
        sampleNumber: Int,
        capturedAt: Instant,
        gnssMode: GnssMode,
    ): GnssTelemetry {
        val currentFix = latestFix
        if (currentFix == null || currentFix.mode != gnssMode || shouldRefreshFix(sampleNumber, gnssMode)) {
            latestFix = newFix(sampleNumber, capturedAt, gnssMode)
        }

        val snapshot = latestFix ?: newFix(sampleNumber, capturedAt, gnssMode).also { latestFix = it }
        val fixAgeSeconds = Duration
            .between(snapshot.fix.timestamp, capturedAt)
            .seconds
            .coerceAtLeast(0L)
            .toInt()

        return GnssTelemetry(
            receivedAt = snapshot.receivedAt,
            fixAgeSeconds = fixAgeSeconds,
            horizontalAccuracyMeters = snapshot.horizontalAccuracyMeters,
            fix = snapshot.fix,
        )
    }

    private fun newFix(sampleNumber: Int, capturedAt: Instant, gnssMode: GnssMode): MockFixSnapshot {
        val positionWave = sampleNumber % 36
        val qualityWave = sampleNumber % 6
        val profile = profileFor(gnssMode)
        val degraded = shouldEmitDegradedFix(sampleNumber, gnssMode)
        val hdop = if (degraded) profile.degradedHdop else profile.hdopValues[qualityWave]
        val horizontalAccuracy = if (degraded) profile.degradedAccuracyMeters else profile.accuracyValues[qualityWave]

        return MockFixSnapshot(
            mode = gnssMode,
            receivedAt = capturedAt,
            horizontalAccuracyMeters = horizontalAccuracy,
            fix = Fix(
                timestamp = capturedAt,
                gpsTime = null,
                lat = 59.9139 + positionWave * 0.00008,
                lon = 10.7522 + positionWave * 0.00011,
                altitude = 23.0 + (positionWave % 5),
                speed = profile.speedMetersPerSecond,
                heading = ((sampleNumber * 17) % 360).toDouble(),
                hdop = hdop.toDouble(),
                satellites = if (degraded) profile.degradedSatellites else profile.satellites,
            ),
        )
    }

    private fun shouldRefreshFix(sampleNumber: Int, gnssMode: GnssMode): Boolean {
        val interval = profileFor(gnssMode).refreshEverySamples
        return sampleNumber % interval == 0
    }

    private fun shouldEmitDegradedFix(sampleNumber: Int, gnssMode: GnssMode): Boolean {
        val degradedEvery = profileFor(gnssMode).degradedEverySamples
        return degradedEvery != null && sampleNumber > 0 && sampleNumber % degradedEvery == 0
    }

    private fun profileFor(gnssMode: GnssMode): MockGnssProfile =
        when (gnssMode) {
            GnssMode.HighAccuracy -> MockGnssProfile(
                refreshEverySamples = 1,
                speedMetersPerSecond = 3.2,
                satellites = 18,
                hdopValues = floatArrayOf(1.0f, 0.8f, 0.6f, 0.9f, 1.1f, 0.7f),
                accuracyValues = floatArrayOf(10f, 8f, 5f, 7f, 12f, 6f),
                degradedEverySamples = 12,
                degradedHdop = 2.4f,
                degradedAccuracyMeters = 26f,
                degradedSatellites = 11,
            )
            GnssMode.Balanced -> MockGnssProfile(
                refreshEverySamples = 2,
                speedMetersPerSecond = 1.4,
                satellites = 13,
                hdopValues = floatArrayOf(1.8f, 1.3f, 0.9f, 1.5f, 2.1f, 1.0f),
                accuracyValues = floatArrayOf(24f, 18f, 12f, 20f, 32f, 14f),
                degradedEverySamples = 10,
                degradedHdop = 4.8f,
                degradedAccuracyMeters = 72f,
                degradedSatellites = 7,
            )
            GnssMode.LowPower -> MockGnssProfile(
                refreshEverySamples = 4,
                speedMetersPerSecond = 2.8,
                satellites = 9,
                hdopValues = floatArrayOf(2.6f, 2.0f, 1.5f, 2.4f, 3.2f, 1.8f),
                accuracyValues = floatArrayOf(38f, 30f, 20f, 34f, 46f, 24f),
                degradedEverySamples = 8,
                degradedHdop = 5.6f,
                degradedAccuracyMeters = 120f,
                degradedSatellites = 5,
            )
        }

    private data class MockFixSnapshot(
        val mode: GnssMode,
        val receivedAt: Instant,
        val horizontalAccuracyMeters: Float,
        val fix: Fix,
    )

    private data class MockGnssProfile(
        val refreshEverySamples: Int,
        val speedMetersPerSecond: Double,
        val satellites: Int,
        val hdopValues: FloatArray,
        val accuracyValues: FloatArray,
        val degradedEverySamples: Int?,
        val degradedHdop: Float,
        val degradedAccuracyMeters: Float,
        val degradedSatellites: Int,
    )
}
