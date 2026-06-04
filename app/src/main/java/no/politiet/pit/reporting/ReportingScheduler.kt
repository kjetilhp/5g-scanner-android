package no.politiet.pit.reporting

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object ReportingScheduler {
    const val PREFS_NAME = "scanner_settings"
    const val KEY_CONSENT_GRANTED = "consentGranted"
    const val KEY_REPORTING_MODE = "reportingMode"
    const val KEY_LAST_REPORTED_AT = "lastReportedAt"

    private const val TAG = "5GScanner"
    private const val ACTION_REPORTING_TRIGGER = "no.politiet.pit.REPORTING_TRIGGER"
    private const val REQUEST_REPORTING = 1001
    private const val MODE_DAILY = "Daily"
    private const val MODE_HOURLY = "Hourly"
    private const val MODE_CONTINUOUS = "Continuous"
    private const val MODE_MANUAL = "Manual"
    private const val HOURLY_INTERVAL_MS = 60 * 60 * 1_000L
    private const val DAILY_INTERVAL_MS = 24 * 60 * 60 * 1_000L
    private const val NOOP_URL = "https://www.google.com/generate_204"
    private const val NETWORK_TIMEOUT_MS = 5_000

    fun appPreferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun scheduleFromPreferences(context: Context) {
        val preferences = appPreferences(context)
        val consentGranted = preferences.getBoolean(KEY_CONSENT_GRANTED, false)
        val reportingMode = preferences.getString(KEY_REPORTING_MODE, MODE_HOURLY)
        schedule(context, consentGranted, reportingMode)
    }

    fun scheduleOnLaunch(context: Context) {
        val preferences = appPreferences(context)
        val consentGranted = preferences.getBoolean(KEY_CONSENT_GRANTED, false)
        val reportingMode = preferences.getString(KEY_REPORTING_MODE, MODE_HOURLY)
        schedule(context, consentGranted, reportingMode)
        triggerMissedReportIfDue(context, consentGranted, reportingMode, reason = "launch")
    }

    fun schedule(context: Context, consentGranted: Boolean, reportingMode: String?) {
        cancel(context)
        if (!consentGranted) {
            Log.i(TAG, "Reporting alarm not scheduled: consentGranted=false, mode=$reportingMode")
            return
        }

        val schedule = alarmSchedule(reportingMode)
        if (schedule == null) {
            val reason = when (reportingMode) {
                MODE_CONTINUOUS -> "continuous_reports_when_sample_is_received"
                MODE_MANUAL -> "manual_reporting_only"
                else -> "unsupported_mode"
            }
            Log.i(TAG, "Reporting alarm not scheduled: mode=$reportingMode, reason=$reason")
            return
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            schedule.firstTriggerAtMillis,
            schedule.intervalMs,
            reportingPendingIntent(context),
        )
        Log.i(
            TAG,
            "Scheduled reporting alarm: mode=$reportingMode, firstTriggerAt=${Instant.ofEpochMilli(schedule.firstTriggerAtMillis)}, intervalMs=${schedule.intervalMs}",
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(reportingPendingIntent(context))
        Log.d(TAG, "Canceled reporting alarm")
    }

    fun recordTrigger(
        context: Context,
        reportingMode: String,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        Thread {
            val success = hasNetworkConnection(appContext) && performNoopNetworkCall()
            if (success) {
                val reportedAt = Instant.now()
                appPreferences(appContext).edit()
                    .putString(KEY_LAST_REPORTED_AT, reportedAt.toString())
                    .apply()
                Log.i(TAG, "Reporting trigger: mode=$reportingMode, lastReportedAt=$reportedAt")
            } else {
                Log.i(TAG, "Reporting trigger skipped: mode=$reportingMode, noopNetworkCall=false")
            }
            onComplete?.let { callback ->
                Handler(Looper.getMainLooper()).post {
                    callback(success)
                }
            }
        }.start()
    }

    fun recordTriggerFromPreferences(
        context: Context,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        val reportingMode = appPreferences(context).getString(KEY_REPORTING_MODE, MODE_HOURLY) ?: MODE_HOURLY
        recordTrigger(context, reportingMode, onComplete)
    }

    fun lastReportedAt(context: Context): Instant? =
        appPreferences(context).getString(KEY_LAST_REPORTED_AT, null)?.let(Instant::parse)

    private fun triggerMissedReportIfDue(
        context: Context,
        consentGranted: Boolean,
        reportingMode: String?,
        reason: String,
    ) {
        if (!consentGranted) {
            Log.i(TAG, "Missed reporting check skipped: reason=$reason, consentGranted=false")
            return
        }
        if (reportingMode != MODE_HOURLY && reportingMode != MODE_DAILY) {
            Log.i(TAG, "Missed reporting check skipped: reason=$reason, mode=$reportingMode")
            return
        }

        val lastReportedAt = lastReportedAt(context)
        if (!isMissedReportDue(reportingMode, lastReportedAt, Instant.now())) {
            Log.i(TAG, "Missed reporting check complete: reason=$reason, mode=$reportingMode, due=false, lastReportedAt=$lastReportedAt")
            return
        }

        Log.i(TAG, "Triggering missed reporting: reason=$reason, mode=$reportingMode, lastReportedAt=$lastReportedAt")
        recordTrigger(context, reportingMode)
    }

    private fun alarmSchedule(reportingMode: String?): AlarmSchedule? =
        when (reportingMode) {
            MODE_DAILY -> AlarmSchedule(nextLocalMidnightMillis(), DAILY_INTERVAL_MS)
            MODE_HOURLY -> AlarmSchedule(System.currentTimeMillis() + HOURLY_INTERVAL_MS, HOURLY_INTERVAL_MS)
            else -> null
        }

    private fun isMissedReportDue(reportingMode: String?, lastReportedAt: Instant?, now: Instant): Boolean {
        if (lastReportedAt == null) return true
        return when (reportingMode) {
            MODE_HOURLY -> Duration.between(lastReportedAt, now).toMillis() >= HOURLY_INTERVAL_MS
            MODE_DAILY -> {
                val zone = ZoneId.systemDefault()
                lastReportedAt.atZone(zone).toLocalDate().isBefore(now.atZone(zone).toLocalDate())
            }
            else -> false
        }
    }

    private fun nextLocalMidnightMillis(): Long =
        ZonedDateTime.now()
            .plusDays(1)
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun hasNetworkConnection(context: Context): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun performNoopNetworkCall(): Boolean {
        val connection = URL(NOOP_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            connection.useCaches = false
            connection.responseCode in 200..399
        } catch (error: Exception) {
            Log.i(TAG, "No-op reporting network call failed", error)
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun reportingPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_REPORTING,
            Intent(context, ReportingAlarmReceiver::class.java).setAction(ACTION_REPORTING_TRIGGER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private data class AlarmSchedule(
        val firstTriggerAtMillis: Long,
        val intervalMs: Long,
    )
}

class ReportingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("5GScanner", "Reporting alarm received")
        val preferences = ReportingScheduler.appPreferences(context)
        if (!preferences.getBoolean(ReportingScheduler.KEY_CONSENT_GRANTED, false)) {
            ReportingScheduler.cancel(context)
            return
        }

        val pendingResult = goAsync()
        ReportingScheduler.recordTriggerFromPreferences(context) {
            pendingResult.finish()
        }
    }
}

class ReportingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("5GScanner", "Boot completed; scheduling reporting from preferences")
            ReportingScheduler.scheduleOnLaunch(context)
        }
    }
}
