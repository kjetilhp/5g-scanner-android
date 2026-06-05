package no.politiet.pit.telemetry

import no.politiet.pit.domain.Fix
import no.politiet.pit.domain.GnssMode
import no.politiet.pit.AppConfig
import java.time.Duration
import java.time.Instant

class MockGnssTelemetrySource : GnssTelemetrySource {
    private var latestReportedFix: MockFixSnapshot? = null
    private var latestUsableFix: MockFixSnapshot? = null
    private var fixSequence = 0

    override fun latest(
        sampleNumber: Int,
        capturedAt: Instant,
        gnssMode: GnssMode,
    ): GnssTelemetry {
        val currentReport = latestReportedFix
        if (currentReport == null || currentReport.mode != gnssMode || shouldRefreshFix(currentReport, capturedAt, gnssMode)) {
            fixSequence += 1
            val reportedFix = newFix(capturedAt, gnssMode)
            latestReportedFix = reportedFix
            if (isUsableAt(reportedFix, capturedAt)) {
                latestUsableFix = reportedFix
            }
        }

        val snapshot = latestUsableFix
            ?.takeIf { it.mode == gnssMode && isUsableAt(it, capturedAt) }
            ?: latestReportedFix
            ?: newFix(capturedAt, gnssMode).also {
                latestReportedFix = it
                if (isUsableAt(it, capturedAt)) latestUsableFix = it
            }
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

    override fun diagnostics(): GnssSourceDiagnostics =
        GnssSourceDiagnostics(
            activeTier = "Mock",
            latestUpdateAt = latestReportedFix?.receivedAt,
        )

    private fun newFix(capturedAt: Instant, gnssMode: GnssMode): MockFixSnapshot {
        val positionWave = fixSequence % 36
        val qualityWave = fixSequence % 6
        val profile = profileFor(gnssMode)
        val degraded = shouldEmitDegradedFix(fixSequence, gnssMode)
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
                heading = ((fixSequence * 17) % 360).toDouble(),
                hdop = hdop.toDouble(),
                satellites = if (degraded) profile.degradedSatellites else profile.satellites,
            ),
        )
    }

    private fun shouldRefreshFix(currentFix: MockFixSnapshot, capturedAt: Instant, gnssMode: GnssMode): Boolean {
        val intervalSeconds = profileFor(gnssMode).refreshIntervalSeconds
        return Duration.between(currentFix.receivedAt, capturedAt).seconds >= intervalSeconds
    }

    private fun shouldEmitDegradedFix(fixSequence: Int, gnssMode: GnssMode): Boolean {
        val degradedEvery = profileFor(gnssMode).degradedEverySamples
        return degradedEvery != null && fixSequence > 0 && fixSequence % degradedEvery == 0
    }

    private fun isUsableAt(snapshot: MockFixSnapshot, capturedAt: Instant): Boolean {
        val ageSeconds = Duration.between(snapshot.fix.timestamp, capturedAt).seconds.coerceAtLeast(0L)
        return ageSeconds < maxUsableFixAgeSeconds(snapshot.fix.speed) &&
            snapshot.horizontalAccuracyMeters <= AppConfig.Scanner.maxHorizontalAccuracyMeters &&
            snapshot.fix.hdop <= AppConfig.Scanner.maxHdop
    }

    private fun maxUsableFixAgeSeconds(speedMetersPerSecond: Double?): Long =
        when {
            speedMetersPerSecond == null -> AppConfig.Scanner.maxSlowFixAgeSeconds.toLong()
            speedMetersPerSecond >= AppConfig.Scanner.fastSpeedMetersPerSecond -> AppConfig.Scanner.maxFastFixAgeSeconds.toLong()
            speedMetersPerSecond >= AppConfig.Scanner.slowSpeedMetersPerSecond -> AppConfig.Scanner.maxSlowFixAgeSeconds.toLong()
            else -> AppConfig.Scanner.maxStationaryFixAgeSeconds.toLong()
        }

    private fun profileFor(gnssMode: GnssMode): MockGnssProfile =
        when (gnssMode) {
            GnssMode.HighAccuracy -> MockGnssProfile(
                refreshIntervalSeconds = 5,
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
                refreshIntervalSeconds = 11,
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
                refreshIntervalSeconds = 29,
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
        val refreshIntervalSeconds: Long,
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
