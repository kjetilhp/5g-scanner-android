package no.politiet.pit.fivegscanner.telemetry

import no.politiet.pit.fivegscanner.AppConfig
import no.politiet.pit.fivegscanner.domain.ServingSample
import java.time.Duration

object CoverageSampleAssembler {
    data class GnssQualityThresholds(
        val maxStationaryFixAgeSeconds: Int = AppConfig.Scanner.maxStationaryFixAgeSeconds,
        val maxSlowFixAgeSeconds: Int = AppConfig.Scanner.maxSlowFixAgeSeconds,
        val maxFastFixAgeSeconds: Int = AppConfig.Scanner.maxFastFixAgeSeconds,
        val slowSpeedMetersPerSecond: Double = AppConfig.Scanner.slowSpeedMetersPerSecond,
        val fastSpeedMetersPerSecond: Double = AppConfig.Scanner.fastSpeedMetersPerSecond,
        val maxHorizontalAccuracyMeters: Float = AppConfig.Scanner.maxHorizontalAccuracyMeters,
        val maxHdop: Double = AppConfig.Scanner.maxHdop,
        val maxSnapshotAgeSeconds: Long = AppConfig.Scanner.maxSnapshotAgeSeconds,
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

        val snapshotAgeSeconds = kotlin.math.abs(Duration
            .between(gnss.receivedAt, radio.receivedAt)
            .seconds
        )
        if (snapshotAgeSeconds > thresholds.maxSnapshotAgeSeconds) {
            return "telemetry_snapshots_too_far_apart snapshotAgeSeconds=$snapshotAgeSeconds maxSnapshotAgeSeconds=${thresholds.maxSnapshotAgeSeconds}"
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
