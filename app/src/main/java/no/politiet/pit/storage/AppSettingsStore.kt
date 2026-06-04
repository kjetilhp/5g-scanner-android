package no.politiet.pit.storage

import android.content.Context
import no.politiet.pit.AppConfig
import no.politiet.pit.domain.GnssMode
import no.politiet.pit.domain.ReportingMode
import no.politiet.pit.reporting.ReportingScheduler
import java.time.Instant

class AppSettingsStore(private val context: Context) {
    data class Settings(
        val consentGranted: Boolean,
        val scannerStopped: Boolean,
        val gnssMode: GnssMode,
        val reportingMode: ReportingMode,
        val mockTelemetryEnabled: Boolean,
        val enhancedPrivacyEnabled: Boolean,
        val lastReportedAt: Instant?,
    )

    fun load(legacyConsentGranted: Boolean): Settings {
        val preferences = ReportingScheduler.appPreferences(context)
        return Settings(
            consentGranted = preferences.getBoolean(
                ReportingScheduler.KEY_CONSENT_GRANTED,
                legacyConsentGranted,
            ),
            scannerStopped = preferences.getBoolean(KEY_SCANNER_STOPPED, AppConfig.Defaults.scannerStopped),
            gnssMode = GnssMode.fromName(
                preferences.getString(KEY_GNSS_MODE, AppConfig.Defaults.gnssMode.name),
            ),
            reportingMode = ReportingMode.fromName(
                preferences.getString(ReportingScheduler.KEY_REPORTING_MODE, AppConfig.Defaults.reportingMode.name),
            ),
            mockTelemetryEnabled = preferences.getBoolean(KEY_MOCK_TELEMETRY_ENABLED, AppConfig.Defaults.mockTelemetryEnabled),
            enhancedPrivacyEnabled = preferences.getBoolean(KEY_ENHANCED_PRIVACY_ENABLED, AppConfig.Defaults.enhancedPrivacyEnabled),
            lastReportedAt = ReportingScheduler.lastReportedAt(context),
        )
    }

    fun save(
        consentGranted: Boolean,
        scannerStopped: Boolean,
        gnssMode: GnssMode,
        reportingMode: ReportingMode,
        mockTelemetryEnabled: Boolean,
        enhancedPrivacyEnabled: Boolean,
    ) {
        ReportingScheduler.appPreferences(context).edit()
            .putBoolean(ReportingScheduler.KEY_CONSENT_GRANTED, consentGranted)
            .putBoolean(KEY_SCANNER_STOPPED, scannerStopped)
            .putString(KEY_GNSS_MODE, gnssMode.name)
            .putString(ReportingScheduler.KEY_REPORTING_MODE, reportingMode.name)
            .putBoolean(KEY_MOCK_TELEMETRY_ENABLED, mockTelemetryEnabled)
            .putBoolean(KEY_ENHANCED_PRIVACY_ENABLED, enhancedPrivacyEnabled)
            .apply()
    }

    private companion object {
        const val KEY_SCANNER_STOPPED = "scannerStopped"
        const val KEY_GNSS_MODE = "gnssMode"
        const val KEY_MOCK_TELEMETRY_ENABLED = "mockTelemetryEnabled"
        const val KEY_ENHANCED_PRIVACY_ENABLED = "enhancedPrivacyEnabled"
    }
}
