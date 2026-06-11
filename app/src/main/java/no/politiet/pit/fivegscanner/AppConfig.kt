package no.politiet.pit.fivegscanner

import no.politiet.pit.fivegscanner.domain.GnssMode
import no.politiet.pit.fivegscanner.domain.ReportingMode
import java.time.Duration

object AppConfig {
    object Defaults {
        val gnssMode: GnssMode = GnssMode.Balanced
        val reportingMode: ReportingMode = ReportingMode.Every15Minutes
        const val mockTelemetryEnabled: Boolean = false
        const val enhancedPrivacyEnabled: Boolean = false
        const val scannerStopped: Boolean = false
    }

    object Scanner {
        const val sampleIntervalMs: Long = 5_000L
        const val radioEventSampleMinSpacingMs: Long = 2_000L
        const val errorRecheckIntervalMs: Long = 5_000L
        const val maxStationaryFixAgeSeconds: Int = 30
        const val maxSlowFixAgeSeconds: Int = 10
        const val maxFastFixAgeSeconds: Int = 5
        const val slowSpeedMetersPerSecond: Double = 2.0
        const val fastSpeedMetersPerSecond: Double = 10.0
        const val maxHorizontalAccuracyMeters: Float = 50f
        const val maxHdop: Double = 4.0
        const val maxSnapshotAgeSeconds: Long = 30L
        const val highAccuracyGnssRefreshMs: Long = 5_000L
        const val balancedGnssRefreshMs: Long = 11_000L
        const val lowPowerGnssRefreshMs: Long = 29_000L
        const val diagnosticsRefreshMs: Long = 1_000L
    }

    object EnhancedPrivacy {
        const val gridCellSizeMeters: Double = 50.0
        const val altitudeResolutionMeters: Double = 50.0
        const val reportedHdop: Double = 10.0
        const val reportedSatellites: Int = 0
    }

    object Reporting {
        val every15MinutesInterval: Duration = Duration.ofMinutes(15)
        val hourlyInterval: Duration = Duration.ofHours(1)
        val dailyInterval: Duration = Duration.ofDays(1)
        const val useMockTransport: Boolean = false
        const val endpointUrl: String = "https://deknings.info/v1/measurements?token=352da26732af23abd4b7ab397a5e8a5311d99bd0954f6654"
        const val connectTimeoutMs: Int = 10_000
        const val readTimeoutMs: Int = 20_000
        const val contentType: String = "application/x-ndjson"
        const val maxSamplesPerBatch: Int = 1_000
        const val maxBatchBytes: Int = 2 * 1024 * 1024
        const val maxBatchesPerDrain: Int = 100
        val retryBackoff: List<Duration> = listOf(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofMinutes(30),
            Duration.ofHours(1),
        )
    }

    object RecordedCoverageData {
        const val recentSampleInspectionLimit: Int = 25
        const val recentCsvExportLimit: Int = 1_000
    }
}
