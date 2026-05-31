package no.politiet.pit

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
import java.time.Instant

object ReportingScheduler {
    const val PREFS_NAME = "ask_settings"
    const val KEY_CONSENT_GRANTED = "consentGranted"
    const val KEY_REPORTING_MODE = "reportingMode"
    const val KEY_LAST_REPORTED_AT = "lastReportedAt"

    private const val TAG = "AskScanner"
    private const val ACTION_REPORTING_TRIGGER = "no.politiet.pit.REPORTING_TRIGGER"
    private const val REQUEST_REPORTING = 1001
    private const val MODE_DAILY = "Daily"
    private const val MODE_HOURLY = "Hourly"
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

    fun schedule(context: Context, consentGranted: Boolean, reportingMode: String?) {
        cancel(context)
        val intervalMs = scheduledIntervalMs(reportingMode)
        if (!consentGranted || intervalMs == null) return

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val firstTriggerAt = System.currentTimeMillis() + intervalMs
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            firstTriggerAt,
            intervalMs,
            reportingPendingIntent(context),
        )
        Log.i(TAG, "Scheduled reporting: mode=$reportingMode, intervalMs=$intervalMs")
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(reportingPendingIntent(context))
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

    fun lastReportedAt(context: Context): Instant? =
        appPreferences(context).getString(KEY_LAST_REPORTED_AT, null)?.let(Instant::parse)

    private fun scheduledIntervalMs(reportingMode: String?): Long? =
        when (reportingMode) {
            MODE_DAILY -> 24 * 60 * 60 * 1_000L
            MODE_HOURLY -> 60 * 60 * 1_000L
            else -> null
        }

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
}

class ReportingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = ReportingScheduler.appPreferences(context)
        if (!preferences.getBoolean(ReportingScheduler.KEY_CONSENT_GRANTED, false)) {
            ReportingScheduler.cancel(context)
            return
        }

        val reportingMode = preferences.getString(ReportingScheduler.KEY_REPORTING_MODE, "Hourly") ?: "Hourly"
        val pendingResult = goAsync()
        ReportingScheduler.recordTrigger(context, reportingMode) {
            pendingResult.finish()
        }
    }
}

class ReportingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReportingScheduler.scheduleFromPreferences(context)
        }
    }
}
