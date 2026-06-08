package no.politiet.pit.fivegscanner.domain

import no.politiet.pit.fivegscanner.AppConfig
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round

object CoverageSampleEnhancedPrivacyTransformer {
    fun reduce(sample: CoverageSample): CoverageSample {
        val reducedFix = sample.fix.reduced()
        return when (sample) {
            is ServingSample -> sample.copy(fix = reducedFix)
            is NeighbourSample -> sample.copy(fix = reducedFix)
            is ScanSample -> sample.copy(fix = reducedFix)
        }
    }

    private fun Fix.reduced(): Fix {
        val snappedTimestamp = timestamp.snappedToUtcMidnight()
        val gridCenter = LatLon(lat, lon).snappedToCellCenter(AppConfig.EnhancedPrivacy.gridCellSizeMeters)
        return copy(
            timestamp = snappedTimestamp,
            gpsTime = gpsTime?.snappedToUtcMidnight(),
            lat = gridCenter.lat,
            lon = gridCenter.lon,
            altitude = altitude.roundedTo(AppConfig.EnhancedPrivacy.altitudeResolutionMeters),
            speed = null,
            heading = null,
            hdop = hdop.coerceAtLeast(AppConfig.EnhancedPrivacy.reportedHdop),
            satellites = AppConfig.EnhancedPrivacy.reportedSatellites,
        )
    }

    private fun Double.roundedTo(resolution: Double): Double =
        round(this / resolution) * resolution

    private fun Instant.snappedToUtcMidnight(): Instant =
        atZone(ZoneOffset.UTC)
            .toLocalDate()
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)

    private data class LatLon(val lat: Double, val lon: Double) {
        fun snappedToCellCenter(cellSizeMeters: Double): LatLon {
            val latCellDegrees = cellSizeMeters / METERS_PER_DEGREE_LAT
            val latIndex = floor((lat.coerceIn(-90.0, 90.0) + 90.0) / latCellDegrees)
            val centerLat = latIndex * latCellDegrees - 90.0 + latCellDegrees / 2.0
            val metersPerDegreeLon = (METERS_PER_DEGREE_LAT * cos(Math.toRadians(centerLat)))
                .coerceAtLeast(MIN_METERS_PER_DEGREE_LON)
            val lonCellDegrees = cellSizeMeters / metersPerDegreeLon
            val lonIndex = floor((lon.coerceIn(-180.0, 180.0) + 180.0) / lonCellDegrees)
            val centerLon = lonIndex * lonCellDegrees - 180.0 + lonCellDegrees / 2.0
            return LatLon(lat = centerLat, lon = centerLon.coerceIn(-180.0, 180.0))
        }
    }

    private const val METERS_PER_DEGREE_LAT = 111_320.0
    private const val MIN_METERS_PER_DEGREE_LON = 0.001
}
