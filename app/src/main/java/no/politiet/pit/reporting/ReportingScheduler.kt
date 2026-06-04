package no.politiet.pit.reporting

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import no.politiet.pit.AppConfig
import no.politiet.pit.storage.CoverageDatabaseProvider
import no.politiet.pit.storage.CoverageDatabase
import no.politiet.pit.storage.CoverageSampleEntity
import no.politiet.pit.storage.ReportingBatchEntity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.Callable

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
    private const val MODE_EVERY_15_MINUTES = "Every15Minutes"
    private const val MODE_CONTINUOUS = "Continuous"
    private const val MODE_MANUAL = "Manual"

    data class ReportingResult(
        val outcome: Outcome,
        val sampleCount: Int = 0,
        val payloadBytes: Long = 0,
        val batchId: String? = null,
        val error: String? = null,
    ) {
        val success: Boolean
            get() = outcome == Outcome.Sent || outcome == Outcome.NoSamples
    }

    enum class Outcome {
        Sent,
        NoSamples,
        Failed,
    }

    enum class TriggerReason {
        Scheduled,
        Manual,
    }

    data class ReportingStatus(
        val queuedSampleCount: Int,
        val failedBatchCount: Int,
        val latestError: String?,
    )

    fun appPreferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun scheduleFromPreferences(context: Context) {
        val preferences = appPreferences(context)
        val consentGranted = preferences.getBoolean(KEY_CONSENT_GRANTED, false)
        val reportingMode = preferences.getString(KEY_REPORTING_MODE, AppConfig.Defaults.reportingMode.name)
        schedule(context, consentGranted, reportingMode)
    }

    fun scheduleOnLaunch(context: Context) {
        val preferences = appPreferences(context)
        val consentGranted = preferences.getBoolean(KEY_CONSENT_GRANTED, false)
        val reportingMode = preferences.getString(KEY_REPORTING_MODE, AppConfig.Defaults.reportingMode.name)
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
        triggerReason: TriggerReason = TriggerReason.Scheduled,
        onComplete: ((ReportingResult) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        CoverageDatabaseProvider.ioExecutor.execute {
            val result = runReportingTrigger(appContext, reportingMode, triggerReason)
            onComplete?.let { callback ->
                Handler(Looper.getMainLooper()).post {
                    callback(result)
                }
            }
        }
    }

    fun recordTriggerFromPreferences(
        context: Context,
        onComplete: ((ReportingResult) -> Unit)? = null,
    ) {
        val reportingMode = appPreferences(context).getString(KEY_REPORTING_MODE, AppConfig.Defaults.reportingMode.name)
            ?: AppConfig.Defaults.reportingMode.name
        recordTrigger(context, reportingMode, TriggerReason.Scheduled, onComplete)
    }

    fun lastReportedAt(context: Context): Instant? =
        appPreferences(context).getString(KEY_LAST_REPORTED_AT, null)?.let(Instant::parse)

    fun status(context: Context): ReportingStatus =
        CoverageDatabaseProvider.ioExecutor.submit(Callable {
            val database = CoverageDatabaseProvider.database(context)
            val batchDao = database.reportingBatchDao()
            ReportingStatus(
                queuedSampleCount = database.coverageSampleDao().queuedUploadCount(),
                failedBatchCount = batchDao.countByStatus(ReportingBatchEntity.StatusFailed),
                latestError = batchDao.latestErrorForStatus(ReportingBatchEntity.StatusFailed),
            )
        }).get()

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
        if (reportingMode != MODE_EVERY_15_MINUTES && reportingMode != MODE_HOURLY && reportingMode != MODE_DAILY) {
            Log.i(TAG, "Missed reporting check skipped: reason=$reason, mode=$reportingMode")
            return
        }

        val lastReportedAt = lastReportedAt(context)
        if (!isMissedReportDue(reportingMode, lastReportedAt, Instant.now())) {
            Log.i(TAG, "Missed reporting check complete: reason=$reason, mode=$reportingMode, due=false, lastReportedAt=$lastReportedAt")
            return
        }

        Log.i(TAG, "Triggering missed reporting: reason=$reason, mode=$reportingMode, lastReportedAt=$lastReportedAt")
        recordTrigger(context, reportingMode, TriggerReason.Scheduled)
    }

    private fun alarmSchedule(reportingMode: String?): AlarmSchedule? =
        when (reportingMode) {
            MODE_DAILY -> AlarmSchedule(nextLocalMidnightMillis(), AppConfig.Reporting.dailyInterval.toMillis())
            MODE_EVERY_15_MINUTES -> intervalSchedule(AppConfig.Reporting.every15MinutesInterval)
            MODE_HOURLY -> intervalSchedule(AppConfig.Reporting.hourlyInterval)
            else -> null
        }

    private fun isMissedReportDue(reportingMode: String?, lastReportedAt: Instant?, now: Instant): Boolean {
        if (lastReportedAt == null) return true
        return when (reportingMode) {
            MODE_EVERY_15_MINUTES -> Duration.between(lastReportedAt, now) >= AppConfig.Reporting.every15MinutesInterval
            MODE_HOURLY -> Duration.between(lastReportedAt, now) >= AppConfig.Reporting.hourlyInterval
            MODE_DAILY -> {
                val zone = ZoneId.systemDefault()
                lastReportedAt.atZone(zone).toLocalDate().isBefore(now.atZone(zone).toLocalDate())
            }
            else -> false
        }
    }

    private fun intervalSchedule(interval: Duration): AlarmSchedule {
        val intervalMs = interval.toMillis()
        return AlarmSchedule(System.currentTimeMillis() + intervalMs, intervalMs)
    }

    private fun nextLocalMidnightMillis(): Long =
        ZonedDateTime.now()
            .plusDays(1)
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun runReportingTrigger(
        context: Context,
        reportingMode: String,
        triggerReason: TriggerReason,
    ): ReportingResult {
        var sentSamples = 0
        var sentBytes = 0L
        var lastBatchId: String? = null

        while (true) {
            val result = runReportingTriggerOnce(context, reportingMode, triggerReason)
            when (result.outcome) {
                Outcome.Sent -> {
                    sentSamples += result.sampleCount
                    sentBytes += result.payloadBytes
                    lastBatchId = result.batchId
                }
                Outcome.NoSamples -> {
                    return if (sentSamples > 0) {
                        Log.i(
                            TAG,
                            "Reporting trigger complete: mode=$reportingMode, result=sent_all, samples=$sentSamples, payloadBytes=$sentBytes",
                        )
                        ReportingResult(
                            outcome = Outcome.Sent,
                            sampleCount = sentSamples,
                            payloadBytes = sentBytes,
                            batchId = lastBatchId,
                        )
                    } else {
                        result
                    }
                }
                Outcome.Failed -> {
                    if (sentSamples > 0) {
                        Log.i(
                            TAG,
                            "Reporting trigger stopped after partial send: mode=$reportingMode, sentSamples=$sentSamples, sentBytes=$sentBytes, error=${result.error}",
                        )
                    }
                    return result
                }
            }
        }
    }

    private fun runReportingTriggerOnce(
        context: Context,
        reportingMode: String,
        triggerReason: TriggerReason,
    ): ReportingResult {
        val db = CoverageDatabaseProvider.database(context)
        val sampleDao = db.coverageSampleDao()
        val batchDao = db.reportingBatchDao()
        val now = Instant.now()
        val nowEpochMillis = now.toEpochMilli()
        val unresolvedStatuses = listOf(ReportingBatchEntity.StatusFailed, ReportingBatchEntity.StatusInFlight)
        val unresolvedBatch = batchDao.oldestBatch(unresolvedStatuses)
        val batchAndSamples = if (unresolvedBatch != null) {
            val nextAttemptAt = unresolvedBatch.nextAttemptAtEpochMillis
            val isBackingOff = unresolvedBatch.status == ReportingBatchEntity.StatusFailed &&
                nextAttemptAt != null &&
                nextAttemptAt > nowEpochMillis
            val isActiveInFlight = unresolvedBatch.status == ReportingBatchEntity.StatusInFlight &&
                nextAttemptAt != null &&
                nextAttemptAt > nowEpochMillis
            if (isActiveInFlight) {
                val error = "Send already in progress"
                Log.i(TAG, "Reporting trigger deferred: mode=$reportingMode, batchId=${unresolvedBatch.id}, reason=$error")
                return ReportingResult(Outcome.Failed, batchId = unresolvedBatch.id, error = error)
            }
            if (isBackingOff && triggerReason != TriggerReason.Manual) {
                val error = "Next retry scheduled at ${Instant.ofEpochMilli(nextAttemptAt)}"
                Log.i(TAG, "Reporting trigger deferred: mode=$reportingMode, batchId=${unresolvedBatch.id}, reason=$error")
                return ReportingResult(Outcome.Failed, batchId = unresolvedBatch.id, error = error)
            }
            unresolvedBatch to sampleDao.samplesForUploadBatch(unresolvedBatch.id)
        } else {
            claimPendingBatch(db, nowEpochMillis)
        } ?: run {
            Log.i(TAG, "Reporting trigger complete: mode=$reportingMode, result=no_samples, reason=up_to_date")
            return ReportingResult(Outcome.NoSamples)
        }

        var (batch, samples) = batchAndSamples
        if (samples.isEmpty()) {
            batchDao.deleteById(batch.id)
            Log.i(TAG, "Removed empty reporting batch: batchId=${batch.id}")
            val pendingBatchAndSamples = claimPendingBatch(db, nowEpochMillis)
            if (pendingBatchAndSamples == null) {
                Log.i(TAG, "Reporting trigger complete: mode=$reportingMode, result=no_samples, reason=up_to_date")
                return ReportingResult(Outcome.NoSamples)
            }
            batch = pendingBatchAndSamples.first
            samples = pendingBatchAndSamples.second
        }

        val nextAttemptAt = nowEpochMillis + backoffMillis(batch.attemptCount + 1)
        val expectedStatuses = when (batch.status) {
            ReportingBatchEntity.StatusFailed -> listOf(ReportingBatchEntity.StatusFailed)
            ReportingBatchEntity.StatusInFlight -> listOf(ReportingBatchEntity.StatusInFlight)
            else -> emptyList()
        }
        if (expectedStatuses.isEmpty()) {
            val error = "Batch is not retryable"
            Log.i(TAG, "Reporting trigger deferred: mode=$reportingMode, batchId=${batch.id}, status=${batch.status}, reason=$error")
            return ReportingResult(Outcome.Failed, batchId = batch.id, error = error)
        }
        val started = batchDao.markAttemptStartedIfStatus(
            batchId = batch.id,
            expectedStatuses = expectedStatuses,
            status = ReportingBatchEntity.StatusInFlight,
            attemptedAtEpochMillis = nowEpochMillis,
            nextAttemptAtEpochMillis = nextAttemptAt,
        )
        if (started == 0) {
            val error = "Send already in progress"
            Log.i(TAG, "Reporting trigger deferred: mode=$reportingMode, batchId=${batch.id}, reason=$error")
            return ReportingResult(Outcome.Failed, batchId = batch.id, error = error)
        }
        sampleDao.markBatchAttemptStarted(batch.id, CoverageSampleEntity.UploadStatusInFlight)

        val jsonl = buildJsonl(samples)
        val payload = ReportingPayload(
            batchId = batch.id,
            sampleCount = samples.size,
            payloadBytes = jsonl.toByteArray(Charsets.UTF_8).size.toLong(),
            jsonl = jsonl,
        )
        val transport = reportingTransport()
        when (val transportResult = transport.post(payload)) {
            ReportingTransportResult.Success -> {
                val uploadedAt = Instant.now()
                val uploadedAtEpochMillis = uploadedAt.toEpochMilli()
                batchDao.markUploaded(batch.id, ReportingBatchEntity.StatusUploaded, uploadedAtEpochMillis)
                sampleDao.markBatchUploaded(batch.id, CoverageSampleEntity.UploadStatusUploaded, uploadedAtEpochMillis)
                appPreferences(context).edit()
                    .putString(KEY_LAST_REPORTED_AT, uploadedAt.toString())
                    .apply()
                Log.i(
                    TAG,
                    "Reporting trigger complete: mode=$reportingMode, batchId=${batch.id}, samples=${samples.size}, payloadBytes=${payload.payloadBytes}, result=sent",
                )
                return ReportingResult(
                    outcome = Outcome.Sent,
                    sampleCount = samples.size,
                    payloadBytes = payload.payloadBytes,
                    batchId = batch.id,
                )
            }

            is ReportingTransportResult.Failure -> {
                val error = transportResult.reason
                batchDao.markFailed(batch.id, ReportingBatchEntity.StatusFailed, nextAttemptAt, error)
                sampleDao.markBatchFailed(batch.id, CoverageSampleEntity.UploadStatusFailed, error)
                Log.i(
                    TAG,
                    "Reporting trigger failed: mode=$reportingMode, batchId=${batch.id}, samples=${samples.size}, payloadBytes=${payload.payloadBytes}, nextAttemptAt=${Instant.ofEpochMilli(nextAttemptAt)}, retryable=${transportResult.retryable}, reason=$error",
                )
                return ReportingResult(
                    outcome = Outcome.Failed,
                    sampleCount = samples.size,
                    payloadBytes = payload.payloadBytes,
                    batchId = batch.id,
                    error = error,
                )
            }
        }
    }

    private fun claimPendingBatch(db: CoverageDatabase, nowEpochMillis: Long): Pair<ReportingBatchEntity, List<CoverageSampleEntity>>? {
        var claimed: Pair<ReportingBatchEntity, List<CoverageSampleEntity>>? = null
        db.runInTransaction {
            val sampleDao = db.coverageSampleDao()
            val batchDao = db.reportingBatchDao()
            val candidates = sampleDao.oldestSamplesByUploadStatus(
                status = CoverageSampleEntity.UploadStatusPending,
                limit = AppConfig.Reporting.maxSamplesPerBatch,
            )
            val selected = selectBatchSamples(candidates)
            if (selected.isEmpty()) return@runInTransaction

            val batchId = UUID.randomUUID().toString()
            val payloadBytes = buildJsonl(selected).toByteArray(Charsets.UTF_8).size.toLong()
            val batch = ReportingBatchEntity(
                id = batchId,
                status = ReportingBatchEntity.StatusInFlight,
                createdAtEpochMillis = nowEpochMillis,
                firstSampleId = selected.first().id,
                lastSampleId = selected.last().id,
                sampleCount = selected.size,
                payloadBytes = payloadBytes,
                nextAttemptAtEpochMillis = nowEpochMillis + backoffMillis(1),
            )
            batchDao.insert(batch)
            sampleDao.markClaimed(
                sampleIds = selected.map { it.id },
                batchId = batchId,
                status = CoverageSampleEntity.UploadStatusInFlight,
            )
            claimed = batch to selected
        }
        return claimed
    }

    private fun selectBatchSamples(samples: List<CoverageSampleEntity>): List<CoverageSampleEntity> {
        val selected = mutableListOf<CoverageSampleEntity>()
        var bytes = 0
        for (sample in samples) {
            val lineBytes = jsonlLineBytes(sample)
            if (selected.isNotEmpty() && bytes + lineBytes > AppConfig.Reporting.maxBatchBytes) break
            selected.add(sample)
            bytes += lineBytes
        }
        return selected
    }

    private fun buildJsonl(samples: List<CoverageSampleEntity>): String =
        buildString {
            samples.forEach { sample ->
                append(sample.sampleJson)
                append('\n')
            }
        }

    private fun jsonlLineBytes(sample: CoverageSampleEntity): Int =
        sample.sampleJson.toByteArray(Charsets.UTF_8).size + 1

    private fun backoffMillis(nextAttemptNumber: Int): Long =
        AppConfig.Reporting.retryBackoff[
            (nextAttemptNumber.coerceAtLeast(1) - 1).coerceAtMost(AppConfig.Reporting.retryBackoff.lastIndex)
        ].toMillis()

    private fun reportingTransport(): ReportingTransport =
        if (AppConfig.Reporting.useMockTransport) {
            Log.i(TAG, "Reporting transport: mock")
            MockReportingTransport()
        } else {
            Log.i(TAG, "Reporting transport: http endpoint=${AppConfig.Reporting.endpointUrl}")
            HttpReportingTransport()
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
