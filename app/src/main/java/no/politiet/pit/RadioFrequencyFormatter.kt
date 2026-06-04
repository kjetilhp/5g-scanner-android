package no.politiet.pit

import no.politiet.pit.domain.Cell
import no.politiet.pit.domain.LteCell
import no.politiet.pit.domain.Nr5gCell
import java.util.Locale

object RadioFrequencyFormatter {
    fun displayText(cell: Cell): String =
        when (cell) {
            is LteCell -> lteDisplayText(cell)
            is Nr5gCell -> nrDisplayText(cell)
        }

    private fun lteDisplayText(cell: LteCell): String {
        val frequency = LteDownlinkBand.find(cell.band, cell.earfcn)?.frequencyMhz(cell.earfcn)
        return if (frequency == null) {
            "EARFCN ${cell.earfcn}"
        } else {
            "${formatMhz(frequency)} / EARFCN ${cell.earfcn}"
        }
    }

    private fun nrDisplayText(cell: Nr5gCell): String {
        val frequency = nrFrequencyMhz(cell.arfcn)
        return if (frequency == null) {
            "NR-ARFCN ${cell.arfcn}"
        } else {
            "${formatMhz(frequency)} / NR-ARFCN ${cell.arfcn}"
        }
    }

    private fun nrFrequencyMhz(nrarfcn: Int): Double? =
        when (nrarfcn) {
            in 0..599_999 -> 0.005 * nrarfcn
            in 600_000..2_016_666 -> 3_000.0 + 0.015 * (nrarfcn - 600_000)
            in 2_016_667..3_279_165 -> 24_250.08 + 0.06 * (nrarfcn - 2_016_667)
            else -> null
        }

    private fun formatMhz(frequencyMhz: Double): String =
        String.format(Locale.US, "%.1f MHz", frequencyMhz)

    private data class LteDownlinkBand(
        val band: Int,
        val lowMhz: Double,
        val firstEarfcn: Int,
        val lastEarfcn: Int,
    ) {
        fun frequencyMhz(earfcn: Int): Double = lowMhz + 0.1 * (earfcn - firstEarfcn)

        companion object {
            fun find(band: Int, earfcn: Int): LteDownlinkBand? =
                entries.firstOrNull { it.band == band && earfcn in it.firstEarfcn..it.lastEarfcn }
                    ?: entries.firstOrNull { earfcn in it.firstEarfcn..it.lastEarfcn }

            private val entries = listOf(
                LteDownlinkBand(1, 2110.0, 0, 599),
                LteDownlinkBand(2, 1930.0, 600, 1199),
                LteDownlinkBand(3, 1805.0, 1200, 1949),
                LteDownlinkBand(4, 2110.0, 1950, 2399),
                LteDownlinkBand(5, 869.0, 2400, 2649),
                LteDownlinkBand(7, 2620.0, 2750, 3449),
                LteDownlinkBand(8, 925.0, 3450, 3799),
                LteDownlinkBand(12, 729.0, 5010, 5179),
                LteDownlinkBand(13, 746.0, 5180, 5279),
                LteDownlinkBand(14, 758.0, 5280, 5379),
                LteDownlinkBand(17, 734.0, 5730, 5849),
                LteDownlinkBand(18, 860.0, 5850, 5999),
                LteDownlinkBand(19, 875.0, 6000, 6149),
                LteDownlinkBand(20, 791.0, 6150, 6449),
                LteDownlinkBand(25, 1930.0, 8040, 8689),
                LteDownlinkBand(26, 859.0, 8690, 9039),
                LteDownlinkBand(28, 758.0, 9210, 9659),
                LteDownlinkBand(32, 1452.0, 9920, 10359),
                LteDownlinkBand(34, 2010.0, 36200, 36349),
                LteDownlinkBand(38, 2570.0, 37750, 38249),
                LteDownlinkBand(39, 1880.0, 38250, 38649),
                LteDownlinkBand(40, 2300.0, 38650, 39649),
                LteDownlinkBand(41, 2496.0, 39650, 41589),
                LteDownlinkBand(42, 3400.0, 41590, 43589),
                LteDownlinkBand(43, 3600.0, 43590, 45589),
                LteDownlinkBand(46, 5150.0, 46790, 54539),
                LteDownlinkBand(48, 3550.0, 55240, 56739),
                LteDownlinkBand(66, 2110.0, 66436, 67335),
                LteDownlinkBand(71, 617.0, 68586, 68935),
            )
        }
    }
}
