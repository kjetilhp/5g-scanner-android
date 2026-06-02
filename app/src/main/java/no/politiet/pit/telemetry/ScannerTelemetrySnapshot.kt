package no.politiet.pit.telemetry

import no.politiet.pit.domain.GnssMode
import no.politiet.pit.domain.LteCell
import no.politiet.pit.domain.Signal
import java.time.Instant

data class ScannerTelemetrySnapshot(
    val radio: RadioTelemetry,
    val gnss: GnssTelemetry,
) {
    fun metrics(): List<MetricQuality> {
        val signal = radio.servingCell.signal
        return listOf(
            MetricQuality("RSRP", "${signal.rsrp} dBm", qualityFromRange(signal.rsrp.toFloat(), -118f, -82f), MetricKind.Radio),
            MetricQuality("RSRQ", "${signal.rsrq} dB", qualityFromRange(signal.rsrq.toFloat(), -20f, -8f), MetricKind.Radio),
            MetricQuality("SINR", "${signal.sinr} dB", qualityFromRange(signal.sinr.toFloat(), 0f, 24f), MetricKind.Radio),
            MetricQuality("GNSS", "HDOP ${formatHdop(gnss.fix.hdop)} / ${gnss.fixAgeSeconds}s", gnssQuality(gnss.fix.hdop.toFloat(), gnss.fixAgeSeconds), MetricKind.Gnss),
        )
    }

    fun overallQuality(): Float =
        radioQuality(radio.servingCell.signal).average().toFloat().coerceIn(0f, 1f)

    private fun radioQuality(signal: Signal): List<Float> = listOf(
        qualityFromRange(signal.rsrp.toFloat(), -118f, -82f),
        qualityFromRange(signal.rsrq.toFloat(), -20f, -8f),
        qualityFromRange(signal.sinr.toFloat(), 0f, 24f),
    )

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

        private fun gnssQuality(hdop: Float, ageSeconds: Int): Float {
            val precision = (1f - ((hdop - 0.7f) / 3.3f)).coerceIn(0f, 1f)
            val freshness = (1f - ((ageSeconds - 2f) / 28f)).coerceIn(0f, 1f)
            return (precision * 0.7f + freshness * 0.3f).coerceIn(0f, 1f)
        }

        private fun formatHdop(hdop: Double): String =
            String.format("%.1f", hdop)
    }
}
