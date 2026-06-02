package no.politiet.pit.telemetry

import no.politiet.pit.domain.GnssMode
import no.politiet.pit.domain.LteCell
import no.politiet.pit.domain.Signal
import java.time.Duration
import java.time.Instant

data class ScannerTelemetrySnapshot(
    val radio: RadioTelemetry,
    val gnss: GnssTelemetry,
) {
    fun metrics(now: Instant = Instant.now()): List<MetricQuality> {
        val signal = radio.servingCell.signal
        val gnssAgeSeconds = currentGnssFixAgeSeconds(now)
        val gnssAgeSecondsFloat = currentGnssFixAgeSecondsFloat(now)
        val gnssUsability = gnssUsability(gnss, gnssAgeSecondsFloat)
        return listOf(
            MetricQuality("RSRP", "${signal.rsrp} dBm", qualityFromRange(signal.rsrp.toFloat(), -118f, -82f), MetricKind.Radio),
            MetricQuality("RSRQ", "${signal.rsrq} dB", qualityFromRange(signal.rsrq.toFloat(), -20f, -8f), MetricKind.Radio),
            MetricQuality("SINR", "${signal.sinr} dB", qualityFromRange(signal.sinr.toFloat(), 0f, 24f), MetricKind.Radio),
            MetricQuality("GNSS", gnssValueText(gnss, gnssAgeSeconds, gnssUsability), gnssUsability.quality, MetricKind.Gnss, gnssUsability.isUsable),
        )
    }

    fun overallQuality(): Float =
        radioQuality(radio.servingCell.signal).average().toFloat().coerceIn(0f, 1f)

    private fun radioQuality(signal: Signal): List<Float> = listOf(
        qualityFromRange(signal.rsrp.toFloat(), -118f, -82f),
        qualityFromRange(signal.rsrq.toFloat(), -20f, -8f),
        qualityFromRange(signal.sinr.toFloat(), 0f, 24f),
    )

    private fun currentGnssFixAgeSeconds(now: Instant): Int =
        currentGnssFixAgeSecondsFloat(now).toInt()

    private fun currentGnssFixAgeSecondsFloat(now: Instant): Float {
        val elapsedSinceSnapshotMillis = Duration
            .between(gnss.receivedAt, now)
            .toMillis()
            .coerceAtLeast(0L)
        return (gnss.fixAgeSeconds + elapsedSinceSnapshotMillis / 1_000f).coerceAtLeast(0f)
    }

    companion object {
        fun initial(gnssMode: GnssMode): ScannerTelemetrySnapshot {
            val capturedAt = Instant.now()
            val hdop = if (gnssMode == GnssMode.HighAccuracy) 0.8 else 1.4
            return ScannerTelemetrySnapshot(
                radio = RadioTelemetry(
                    receivedAt = capturedAt,
                    servingCell = LteCell(
                        mcc = 242,
                        mnc = 1,
                        cellId = "mock-100000",
                        tac = 4100,
                        pci = 120,
                        earfcn = 6300,
                        band = 20,
                        signal = Signal(-98, -13, 12, -68),
                    ),
                ),
                gnss = GnssTelemetry(
                    receivedAt = capturedAt,
                    fixAgeSeconds = 9,
                    horizontalAccuracyMeters = if (gnssMode == GnssMode.HighAccuracy) 8f else 18f,
                    fix = no.politiet.pit.domain.Fix(
                        timestamp = capturedAt,
                        gpsTime = null,
                        lat = 59.9139,
                        lon = 10.7522,
                        altitude = 23.0,
                        speed = if (gnssMode == GnssMode.HighAccuracy) 1.6 else 0.8,
                        heading = 0.0,
                        hdop = hdop,
                        satellites = if (gnssMode == GnssMode.HighAccuracy) 18 else 11,
                    ),
                ),
            )
        }

        private fun qualityFromRange(value: Float, poor: Float, excellent: Float): Float =
            ((value - poor) / (excellent - poor)).coerceIn(0f, 1f)

        private fun gnssUsability(gnss: GnssTelemetry, ageSeconds: Float): GnssUsability {
            val maxAgeSeconds = maxUsableFixAgeSeconds(gnss.fix.speed)
            val freshness = (1f - (ageSeconds / maxAgeSeconds)).coerceIn(0f, 1f)
            val accuracy = gnss.horizontalAccuracyMeters?.let { accuracyMeters ->
                (1f - ((accuracyMeters - EXCELLENT_ACCURACY_METERS) / (MAX_USABLE_ACCURACY_METERS - EXCELLENT_ACCURACY_METERS))).coerceIn(0f, 1f)
            } ?: 0.55f
            val hdop = (1f - ((gnss.fix.hdop.toFloat() - EXCELLENT_HDOP) / (MAX_USABLE_HDOP - EXCELLENT_HDOP))).coerceIn(0f, 1f)
            val precision = (accuracy * 0.82f + hdop * 0.18f).coerceIn(0f, 1f)
            val isUsable = ageSeconds < maxAgeSeconds &&
                (gnss.horizontalAccuracyMeters == null || gnss.horizontalAccuracyMeters <= MAX_USABLE_ACCURACY_METERS) &&
                gnss.fix.hdop <= MAX_USABLE_HDOP
            return GnssUsability(
                quality = if (isUsable) (freshness * precision).coerceIn(0f, 1f) else 0f,
                isUsable = isUsable,
                reason = when {
                    ageSeconds >= maxAgeSeconds -> "Fix too old"
                    gnss.horizontalAccuracyMeters != null && gnss.horizontalAccuracyMeters > MAX_USABLE_ACCURACY_METERS -> "Too imprecise"
                    gnss.fix.hdop > MAX_USABLE_HDOP -> "Weak fix"
                    else -> null
                },
            )
        }

        private fun gnssValueText(gnss: GnssTelemetry, ageSeconds: Int, usability: GnssUsability): String =
            usability.reason ?: "${formatHorizontalAccuracy(gnss.horizontalAccuracyMeters)} ${ageSeconds}s ago"

        private fun maxUsableFixAgeSeconds(speedMetersPerSecond: Double?): Float =
            when {
                speedMetersPerSecond == null -> 10f
                speedMetersPerSecond >= 10.0 -> 5f
                speedMetersPerSecond >= 2.0 -> 10f
                else -> 30f
            }

        private const val EXCELLENT_ACCURACY_METERS = 5f
        private const val MAX_USABLE_ACCURACY_METERS = 50f
        private const val EXCELLENT_HDOP = 0.7f
        private const val MAX_USABLE_HDOP = 4.0f

        private fun formatHorizontalAccuracy(horizontalAccuracyMeters: Float?): String =
            horizontalAccuracyMeters?.let { "±${it.toInt()}m" } ?: "±?m"

        private data class GnssUsability(
            val quality: Float,
            val isUsable: Boolean,
            val reason: String?,
        )
    }
}
