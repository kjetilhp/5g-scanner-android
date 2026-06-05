package no.politiet.pit.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import no.politiet.pit.AppConfig
import no.politiet.pit.domain.Fix
import no.politiet.pit.domain.GnssMode
import java.time.Duration
import java.time.Instant

class AndroidGnssTelemetrySource(
    private val context: Context,
) : GnssTelemetrySource {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        override fun onProviderDisabled(provider: String) {
            currentTier = null
            Log.d(TAG, "GNSS provider disabled: provider=$provider")
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "GNSS provider enabled: provider=$provider")
        }

        @Deprecated("Deprecated by Android framework")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            latestSatelliteCount = status.satelliteCount
        }
    }

    @Volatile private var latestReportedFix: GnssTelemetry? = null
    @Volatile private var latestUsableFix: GnssTelemetry? = null
    @Volatile private var latestSatelliteCount: Int = 0
    private var started = false
    private var currentTier: GnssRequestTier? = null

    override fun start(gnssMode: GnssMode) {
        if (started) return
        registerGnssStatus()
        started = configureLocationUpdates(GnssRequestTier.forMode(gnssMode))
        if (!started) {
            unregisterGnssStatus()
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        currentTier = null
        runCatching {
            locationManager?.removeUpdates(locationListener)
        }.onFailure { error ->
            Log.w(TAG, "Could not stop Android GNSS location updates", error)
        }
        unregisterGnssStatus()
    }

    override fun latest(
        sampleNumber: Int,
        capturedAt: Instant,
        gnssMode: GnssMode,
    ): GnssTelemetry? {
        if (started && currentTier == null && !configureLocationUpdates(GnssRequestTier.forMode(gnssMode))) return null
        val latest = latestUsableFix?.takeIf { isUsableAt(it, capturedAt) } ?: return null
        return latest.copy(
            fixAgeSeconds = Duration
                .between(latest.fix.timestamp, capturedAt)
                .seconds
                .coerceAtLeast(0L)
                .toInt(),
        )
    }

    private fun handleLocation(location: Location) {
        val capturedAt = Instant.now()
        val telemetry = location.toTelemetry(capturedAt)
        latestReportedFix = telemetry
        if (isUsableAt(telemetry, capturedAt)) {
            latestUsableFix = telemetry
        }

        val nextTier = GnssRequestTier.forSpeed(location.speed.takeIf { location.hasSpeed() }?.toDouble())
        if (started && nextTier != currentTier) {
            configureLocationUpdates(nextTier)
        }
    }

    @SuppressLint("MissingPermission")
    private fun configureLocationUpdates(tier: GnssRequestTier): Boolean {
        val manager = locationManager ?: return false
        val provider = providerFor(tier)
        if (provider == null) {
            Log.w(TAG, "No enabled location provider for tier=${tier.name}")
            currentTier = null
            return false
        }

        return runCatching {
            manager.removeUpdates(locationListener)
            manager.requestLocationUpdates(
                provider,
                tier.minTimeMs,
                tier.minDistanceMeters,
                locationListener,
            )
            currentTier = tier
            Log.i(TAG, "Android GNSS updates configured: tier=${tier.name}, provider=$provider, minTimeMs=${tier.minTimeMs}, minDistanceMeters=${tier.minDistanceMeters}")
            true
        }.getOrElse { error ->
            Log.w(TAG, "Could not configure Android GNSS updates: tier=${tier.name}, provider=$provider", error)
            currentTier = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssStatus() {
        runCatching {
            locationManager?.registerGnssStatusCallback(gnssStatusCallback, null)
        }.onFailure { error ->
            Log.d(TAG, "Could not register GNSS status callback", error)
        }
    }

    private fun unregisterGnssStatus() {
        runCatching {
            locationManager?.unregisterGnssStatusCallback(gnssStatusCallback)
        }.onFailure { error ->
            Log.d(TAG, "Could not unregister GNSS status callback", error)
        }
    }

    private fun providerFor(tier: GnssRequestTier): String? {
        val manager = locationManager ?: return null
        val gpsEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val passiveEnabled = manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)
        return when (tier) {
            GnssRequestTier.High -> when {
                gpsEnabled -> LocationManager.GPS_PROVIDER
                networkEnabled -> LocationManager.NETWORK_PROVIDER
                passiveEnabled -> LocationManager.PASSIVE_PROVIDER
                else -> null
            }
            GnssRequestTier.Balanced -> when {
                gpsEnabled -> LocationManager.GPS_PROVIDER
                networkEnabled -> LocationManager.NETWORK_PROVIDER
                passiveEnabled -> LocationManager.PASSIVE_PROVIDER
                else -> null
            }
            GnssRequestTier.Low -> when {
                networkEnabled -> LocationManager.NETWORK_PROVIDER
                passiveEnabled -> LocationManager.PASSIVE_PROVIDER
                gpsEnabled -> LocationManager.GPS_PROVIDER
                else -> null
            }
        }
    }

    private fun Location.toTelemetry(receivedAt: Instant): GnssTelemetry {
        val fixTimestamp = if (time > 0L) {
            Instant.ofEpochMilli(time)
        } else {
            receivedAt
        }
        val horizontalAccuracy = if (hasAccuracy()) accuracy else null
        val hdop = estimatedHdop(horizontalAccuracy)
        val altitudeValue = if (hasAltitude()) altitude else 0.0
        return GnssTelemetry(
            receivedAt = receivedAt,
            fixAgeSeconds = Duration
                .between(fixTimestamp, receivedAt)
                .seconds
                .coerceAtLeast(0L)
                .toInt(),
            horizontalAccuracyMeters = horizontalAccuracy,
            fix = Fix(
                timestamp = fixTimestamp,
                gpsTime = fixTimestamp,
                lat = latitude,
                lon = longitude,
                altitude = altitudeValue,
                speed = speed.takeIf { hasSpeed() }?.toDouble(),
                heading = bearing.takeIf { hasBearing() }?.toDouble(),
                hdop = hdop,
                satellites = latestSatelliteCount,
            ),
        )
    }

    private fun estimatedHdop(horizontalAccuracyMeters: Float?): Double =
        horizontalAccuracyMeters
            ?.let { (it / ESTIMATED_HDOP_ACCURACY_DIVISOR).toDouble().coerceAtLeast(MIN_ESTIMATED_HDOP) }
            ?: UNKNOWN_HDOP

    private fun isUsableAt(telemetry: GnssTelemetry, capturedAt: Instant): Boolean {
        val ageSeconds = Duration.between(telemetry.fix.timestamp, capturedAt).seconds.coerceAtLeast(0L)
        val accuracy = telemetry.horizontalAccuracyMeters
        return ageSeconds < maxUsableFixAgeSeconds(telemetry.fix.speed) &&
            (accuracy == null || accuracy <= AppConfig.Scanner.maxHorizontalAccuracyMeters) &&
            telemetry.fix.hdop <= AppConfig.Scanner.maxHdop
    }

    private fun maxUsableFixAgeSeconds(speedMetersPerSecond: Double?): Long =
        when {
            speedMetersPerSecond == null -> AppConfig.Scanner.maxSlowFixAgeSeconds.toLong()
            speedMetersPerSecond >= AppConfig.Scanner.fastSpeedMetersPerSecond -> AppConfig.Scanner.maxFastFixAgeSeconds.toLong()
            speedMetersPerSecond >= AppConfig.Scanner.slowSpeedMetersPerSecond -> AppConfig.Scanner.maxSlowFixAgeSeconds.toLong()
            else -> AppConfig.Scanner.maxStationaryFixAgeSeconds.toLong()
        }

    private enum class GnssRequestTier(
        val minTimeMs: Long,
        val minDistanceMeters: Float,
    ) {
        Low(AppConfig.Scanner.lowPowerGnssRefreshMs, 25f),
        Balanced(AppConfig.Scanner.balancedGnssRefreshMs, 10f),
        High(AppConfig.Scanner.highAccuracyGnssRefreshMs, 0f);

        companion object {
            fun forMode(gnssMode: GnssMode): GnssRequestTier =
                when (gnssMode) {
                    GnssMode.HighAccuracy -> High
                    GnssMode.Balanced -> Balanced
                    GnssMode.LowPower -> Low
                }

            fun forSpeed(speedMetersPerSecond: Double?): GnssRequestTier =
                when {
                    speedMetersPerSecond == null -> Balanced
                    speedMetersPerSecond >= AppConfig.Scanner.fastSpeedMetersPerSecond -> High
                    speedMetersPerSecond >= AppConfig.Scanner.slowSpeedMetersPerSecond -> Balanced
                    else -> Low
                }
        }
    }

    private companion object {
        const val TAG = "5GScanner"
        const val ESTIMATED_HDOP_ACCURACY_DIVISOR = 8f
        const val MIN_ESTIMATED_HDOP = 0.8
        const val UNKNOWN_HDOP = 3.5
    }
}
