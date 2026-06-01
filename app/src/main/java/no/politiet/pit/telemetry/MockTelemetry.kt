package no.politiet.pit.telemetry

import no.politiet.pit.domain.GnssMode

data class MockTelemetry(
    val rsrp: Int,
    val rsrq: Int,
    val sinr: Int,
    val hdop: Float,
    val fixAgeSeconds: Int,
    val gnssMode: GnssMode,
) {
    fun metrics(): List<MetricQuality> = listOf(
        MetricQuality("RSRP", "$rsrp dBm", qualityFromRange(rsrp.toFloat(), -118f, -82f), MetricKind.Radio),
        MetricQuality("RSRQ", "$rsrq dB", qualityFromRange(rsrq.toFloat(), -20f, -8f), MetricKind.Radio),
        MetricQuality("SINR", "$sinr dB", qualityFromRange(sinr.toFloat(), 0f, 24f), MetricKind.Radio),
        MetricQuality("GNSS", "HDOP ${formatHdop(hdop)} / ${fixAgeSeconds}s", gnssQuality(hdop, fixAgeSeconds), MetricKind.Gnss),
    )

    fun overallQuality(): Float =
        radioQuality().average().toFloat().coerceIn(0f, 1f)

    private fun radioQuality(): List<Float> = listOf(
        qualityFromRange(rsrp.toFloat(), -118f, -82f),
        qualityFromRange(rsrq.toFloat(), -20f, -8f),
        qualityFromRange(sinr.toFloat(), 0f, 24f),
    )

    companion object {
        fun initial(gnssMode: GnssMode): MockTelemetry =
            MockTelemetry(-98, -13, 12, if (gnssMode == GnssMode.HighAccuracy) 0.8f else 1.4f, 9, gnssMode)

        fun fromSample(sample: Int, gnssMode: GnssMode): MockTelemetry {
            val wave = sample % 6
            val rsrpValues = intArrayOf(-113, -100, -88, -96, -109, -84)
            val rsrqValues = intArrayOf(-19, -15, -9, -12, -18, -8)
            val sinrValues = intArrayOf(3, 10, 22, 15, 6, 24)
            val baseHdop = if (gnssMode == GnssMode.HighAccuracy) 0.7f else 1.3f
            val hdopOffsets = floatArrayOf(2.2f, 1.1f, 0.1f, 0.8f, 2.7f, 0.0f)
            val ageValues = if (gnssMode == GnssMode.HighAccuracy) {
                intArrayOf(14, 8, 2, 5, 18, 3)
            } else {
                intArrayOf(24, 15, 5, 10, 30, 6)
            }
            return MockTelemetry(
                rsrpValues[wave],
                rsrqValues[wave],
                sinrValues[wave],
                baseHdop + hdopOffsets[wave],
                ageValues[wave],
                gnssMode,
            )
        }

        private fun qualityFromRange(value: Float, poor: Float, excellent: Float): Float =
            ((value - poor) / (excellent - poor)).coerceIn(0f, 1f)

        private fun gnssQuality(hdop: Float, ageSeconds: Int): Float {
            val precision = (1f - ((hdop - 0.7f) / 3.3f)).coerceIn(0f, 1f)
            val freshness = (1f - ((ageSeconds - 2f) / 28f)).coerceIn(0f, 1f)
            return (precision * 0.7f + freshness * 0.3f).coerceIn(0f, 1f)
        }

        private fun formatHdop(hdop: Float): String =
            String.format("%.1f", hdop)
    }
}
