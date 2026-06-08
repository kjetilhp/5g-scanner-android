package no.politiet.pit.fivegscanner.domain

import java.util.Locale
import kotlin.math.round

object CoordinatePrecision {
    private const val DECIMAL_PLACES = 5
    private const val SCALE = 100_000.0

    fun rounded(value: Double): Double =
        round(value * SCALE) / SCALE

    fun display(value: Double): String =
        String.format(Locale.US, "%.${DECIMAL_PLACES}f", rounded(value))

    fun display(value: String): String =
        value.toDoubleOrNull()?.let(::display) ?: value
}
