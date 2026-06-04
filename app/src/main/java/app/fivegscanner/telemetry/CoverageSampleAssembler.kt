package app.fivegscanner.telemetry

import app.fivegscanner.domain.ServingSample
import java.time.Duration

object CoverageSampleAssembler {
    data class GnssQualityThresholds(
        val maxStationaryFixAgeSeconds: Int = 30,
        val maxSlowFixAgeSeconds: Int = 10,
        val maxFastFixAgeSeconds: Int = 5,
        val slowSpeedMetersPerSecond: Double = 2.0,
        val fastSpeedMetersPerSecond: Double = 10.0,
        val maxHorizontalAccuracyMeters: Float = 50f,
        val maxHdop: Double = 4.0,
        val maxSnapshotAgeSeconds: Long = 30,
    )

    sealed interface AssemblyResult {
        data class Accepted(val sample: ServingSample) : AssemblyResult
        data class Rejected(val reason: String) : AssemblyResult
    }

    fun servingSample(
        radio: RadioTelemetry,
        gnss: GnssTelemetry,
        gnssThresholds: GnssQualityThresholds = GnssQualityThresholds(),
    ): AssemblyResult {
        val rejectionReason = gnssRejectionReason(radio, gnss, gnssThresholds)
        if (rejectionReason != null) {
            return AssemblyResult.Rejected(rejectionReason)
        }

        return AssemblyResult.Accepted(ServingSample(
            fix = gnss.fix,
            cell = radio.servingCell,
        ))
    }

    private fun gnssRejectionReason(
        radio: RadioTelemetry,
        gnss: GnssTelemetry,
        thresholds: GnssQualityThresholds,
    ): String? {
        val accuracyMeters = gnss.horizontalAccuracyMeters
        if (accuracyMeters != null && accuracyMeters > thresholds.maxHorizontalAccuracyMeters) {
            return "gnss_accuracy_too_low accuracyMeters=$accuracyMeters maxAccuracyMeters=${thresholds.maxHorizontalAccuracyMeters}"
        }

        val maxFixAgeSeconds = thresholds.maxFixAgeSecondsFor(gnss.fix.speed)
        if (gnss.fixAgeSeconds > maxFixAgeSeconds) {
            return "gnss_fix_too_old ageSeconds=${gnss.fixAgeSeconds} maxAgeSeconds=$maxFixAgeSeconds speedMetersPerSecond=${gnss.fix.speed ?: "unknown"}"
        }

        if (gnss.fix.hdop > thresholds.maxHdop) {
            return "gnss_hdop_too_high hdop=${gnss.fix.hdop} maxHdop=${thresholds.maxHdop}"
        }

        val snapshotAgeSeconds = Duration
            .between(gnss.receivedAt, radio.receivedAt)
            .seconds
            .coerceAtLeast(0L)
        if (snapshotAgeSeconds > thresholds.maxSnapshotAgeSeconds) {
            return "gnss_snapshot_too_old snapshotAgeSeconds=$snapshotAgeSeconds maxSnapshotAgeSeconds=${thresholds.maxSnapshotAgeSeconds}"
        }

        return null
    }

    private fun GnssQualityThresholds.maxFixAgeSecondsFor(speedMetersPerSecond: Double?): Int =
        when {
            speedMetersPerSecond == null -> maxSlowFixAgeSeconds
            speedMetersPerSecond >= fastSpeedMetersPerSecond -> maxFastFixAgeSeconds
            speedMetersPerSecond >= slowSpeedMetersPerSecond -> maxSlowFixAgeSeconds
            else -> maxStationaryFixAgeSeconds
        }
}
