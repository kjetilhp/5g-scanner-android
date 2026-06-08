package no.politiet.pit.fivegscanner.domain

import java.time.Instant

data class Fix(
    val timestamp: Instant,
    val gpsTime: Instant?,
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val speed: Double?,
    val heading: Double?,
    val hdop: Double,
    val satellites: Int,
)
