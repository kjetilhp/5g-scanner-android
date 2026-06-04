package app.fivegscanner.storage

import android.content.Context
import app.fivegscanner.domain.GnssMode
import app.fivegscanner.domain.ReportingMode
import app.fivegscanner.reporting.ReportingScheduler
import java.time.Instant

class AppSettingsStore(private val context: Context) {
    data class Settings(
        val consentGranted: Boolean,
        val scannerStopped: Boolean,
        val gnssMode: GnssMode,
        val reportingMode: ReportingMode,
        val mockTelemetryEnabled: Boolean,
        val lastReportedAt: Instant?,
    )

    fun load(legacyConsentGranted: Boolean): Settings {
        val preferences = ReportingScheduler.appPreferences(context)
        return Settings(
            consentGranted = preferences.getBoolean(
                ReportingScheduler.KEY_CONSENT_GRANTED,
                legacyConsentGranted,
            ),
            scannerStopped = preferences.getBoolean(KEY_SCANNER_STOPPED, false),
            gnssMode = GnssMode.fromName(
                preferences.getString(KEY_GNSS_MODE, GnssMode.Balanced.name),
            ),
            reportingMode = ReportingMode.fromName(
                preferences.getString(ReportingScheduler.KEY_REPORTING_MODE, ReportingMode.Hourly.name),
            ),
            mockTelemetryEnabled = preferences.getBoolean(KEY_MOCK_TELEMETRY_ENABLED, true),
            lastReportedAt = ReportingScheduler.lastReportedAt(context),
        )
    }

    fun save(
        consentGranted: Boolean,
        scannerStopped: Boolean,
        gnssMode: GnssMode,
        reportingMode: ReportingMode,
        mockTelemetryEnabled: Boolean,
    ) {
        ReportingScheduler.appPreferences(context).edit()
            .putBoolean(ReportingScheduler.KEY_CONSENT_GRANTED, consentGranted)
            .putBoolean(KEY_SCANNER_STOPPED, scannerStopped)
            .putString(KEY_GNSS_MODE, gnssMode.name)
            .putString(ReportingScheduler.KEY_REPORTING_MODE, reportingMode.name)
            .putBoolean(KEY_MOCK_TELEMETRY_ENABLED, mockTelemetryEnabled)
            .apply()
    }

    private companion object {
        const val KEY_SCANNER_STOPPED = "scannerStopped"
        const val KEY_GNSS_MODE = "gnssMode"
        const val KEY_MOCK_TELEMETRY_ENABLED = "mockTelemetryEnabled"
    }
}
