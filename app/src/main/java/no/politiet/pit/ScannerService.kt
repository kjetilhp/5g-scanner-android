package no.politiet.pit

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import no.politiet.pit.domain.CoverageSampleEnhancedPrivacyTransformer
import no.politiet.pit.domain.GnssMode
import no.politiet.pit.domain.ReportingMode
import no.politiet.pit.encoding.CoverageSampleJsonEncoder
import no.politiet.pit.reporting.ReportingScheduler
import no.politiet.pit.storage.AppSettingsStore
import no.politiet.pit.storage.CoverageDatabaseProvider
import no.politiet.pit.storage.CoverageSampleEntity
import no.politiet.pit.telemetry.CoverageSampleAssembler
import no.politiet.pit.telemetry.CoverageSampleAssembler.AssemblyResult
import no.politiet.pit.telemetry.GnssTelemetrySource
import no.politiet.pit.telemetry.RadioTelemetrySource
import no.politiet.pit.telemetry.ScannerTelemetrySnapshot
import no.politiet.pit.telemetry.TelemetrySourceFactory
import java.time.Instant

class ScannerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settingsStore: AppSettingsStore
    private lateinit var radioTelemetrySource: RadioTelemetrySource
    private lateinit var gnssTelemetrySource: GnssTelemetrySource
    private val gnssQualityThresholds = CoverageSampleAssembler.GnssQualityThresholds(
        maxStationaryFixAgeSeconds = AppConfig.Scanner.maxStationaryFixAgeSeconds,
        maxSlowFixAgeSeconds = AppConfig.Scanner.maxSlowFixAgeSeconds,
        maxFastFixAgeSeconds = AppConfig.Scanner.maxFastFixAgeSeconds,
        slowSpeedMetersPerSecond = AppConfig.Scanner.slowSpeedMetersPerSecond,
        fastSpeedMetersPerSecond = AppConfig.Scanner.fastSpeedMetersPerSecond,
        maxHorizontalAccuracyMeters = AppConfig.Scanner.maxHorizontalAccuracyMeters,
        maxHdop = AppConfig.Scanner.maxHdop,
        maxSnapshotAgeSeconds = AppConfig.Scanner.maxSnapshotAgeSeconds,
    )
    private var sampleCount = 0
    private var latestTelemetry = ScannerTelemetrySnapshot.initial(AppConfig.Defaults.gnssMode)
    private var sampleScheduled = false
    private var gnssRefreshScheduled = false
    private var telemetrySourcesStarted = false
    private var lastSampleAttemptAt: Instant? = null
    private var gnssMode = AppConfig.Defaults.gnssMode
    private var telemetryGnssMode = AppConfig.Defaults.gnssMode
    private var reportingMode = AppConfig.Defaults.reportingMode
    private var mockTelemetryEnabled = AppConfig.Defaults.mockTelemetryEnabled
    private var enhancedPrivacyEnabled = AppConfig.Defaults.enhancedPrivacyEnabled
    private var effectiveMockTelemetry = AppConfig.Defaults.mockTelemetryEnabled

    private val sampler = object : Runnable {
        override fun run() {
            sampleScheduled = false
            if (!scannerDesired()) {
                stopScannerService()
            } else if (scannerErrorReason() == null) {
                startTelemetrySources()
                captureSample()
                scheduleNextSample(AppConfig.Scanner.sampleIntervalMs)
            } else {
                publishState()
                scheduleNextSample(AppConfig.Scanner.errorRecheckIntervalMs)
            }
        }
    }

    private val gnssRefresh = object : Runnable {
        override fun run() {
            gnssRefreshScheduled = false
            if (!scannerDesired()) {
                stopScannerService()
            } else if (scannerErrorReason() == null) {
                refreshGnssTelemetry()
                scheduleGnssRefresh(gnssRefreshIntervalMs())
            } else {
                publishState()
                scheduleGnssRefresh(AppConfig.Scanner.errorRecheckIntervalMs)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = AppSettingsStore(this)
        configureTelemetrySources(useMockTelemetry = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopScannerService()
                return START_NOT_STICKY
            }
            else -> startScannerService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(sampler)
        handler.removeCallbacks(gnssRefresh)
        sampleScheduled = false
        gnssRefreshScheduled = false
        stopTelemetrySources()
        publishStoppedState()
        super.onDestroy()
    }

    private fun startScannerService() {
        loadSettings()
        if (!scannerDesired()) {
            stopScannerService()
            return
        }

        ensureNotificationChannel()
        val notification = scannerNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        publishState()
        if (scannerErrorReason() == null) {
            startTelemetrySources()
        }
        scheduleNextSample(0L)
        scheduleGnssRefresh(gnssRefreshIntervalMs())
        Log.i(TAG, "Scanner service started: gnssMode=${gnssMode.name}, reportingMode=${reportingMode.name}")
    }

    private fun stopScannerService() {
        handler.removeCallbacks(sampler)
        handler.removeCallbacks(gnssRefresh)
        sampleScheduled = false
        gnssRefreshScheduled = false
        stopTelemetrySources()
        publishStoppedState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun loadSettings() {
        val settings = settingsStore.load(legacyConsentGranted = false)
        gnssMode = settings.gnssMode
        reportingMode = settings.reportingMode
        val useMockTelemetry = settings.mockTelemetryEnabled || DeviceProfile.isLikelyEmulator()
        if (effectiveMockTelemetry != useMockTelemetry) {
            configureTelemetrySources(useMockTelemetry)
        }
        mockTelemetryEnabled = settings.mockTelemetryEnabled
        enhancedPrivacyEnabled = settings.enhancedPrivacyEnabled
        if (telemetryGnssMode != gnssMode) {
            latestTelemetry = ScannerTelemetrySnapshot.initial(gnssMode)
            telemetryGnssMode = gnssMode
        }
    }

    private fun configureTelemetrySources(useMockTelemetry: Boolean) {
        val shouldRestartSources = telemetrySourcesStarted
        if (shouldRestartSources) {
            stopTelemetrySources()
        }
        val sources = TelemetrySourceFactory.create(this, useMockTelemetry)
        radioTelemetrySource = sources.radio
        gnssTelemetrySource = sources.gnss
        effectiveMockTelemetry = useMockTelemetry
        if (shouldRestartSources) {
            startTelemetrySources()
        }
        Log.i(TAG, "Telemetry sources configured: ${if (useMockTelemetry) "mock" else "android"}")
    }

    private fun startTelemetrySources() {
        if (telemetrySourcesStarted) return
        radioTelemetrySource.start(::requestSampleSoonForRadioEvent)
        gnssTelemetrySource.start()
        telemetrySourcesStarted = true
    }

    private fun stopTelemetrySources() {
        if (!telemetrySourcesStarted) return
        radioTelemetrySource.stop()
        gnssTelemetrySource.stop()
        telemetrySourcesStarted = false
    }

    private fun scannerDesired(): Boolean {
        val settings = settingsStore.load(legacyConsentGranted = false)
        return settings.consentGranted && !settings.scannerStopped
    }

    private fun scannerErrorReason(): String? {
        if (!scannerDesired()) return null
        val hasLocationPermission =
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) return getString(R.string.scanner_error_missing_location_permission)
        if (!effectiveMockTelemetry &&
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
        ) {
            return getString(R.string.scanner_error_missing_phone_permission)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return getString(R.string.scanner_error_missing_notification_permission)
        }
        val locationManager = getSystemService(LocationManager::class.java)
        if (locationManager != null && !locationManager.isLocationEnabled) return getString(R.string.scanner_error_location_disabled)
        if (Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) return getString(R.string.scanner_error_airplane_mode)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return getString(R.string.scanner_error_telephony_unavailable)
        return null
    }

    private fun captureSample() {
        loadSettings()
        sampleCount += 1
        val capturedAt = Instant.now()
        lastSampleAttemptAt = capturedAt
        val gnssTelemetry = gnssTelemetrySource.latest(sampleCount, capturedAt, gnssMode)
        val radioTelemetry = radioTelemetrySource.latestAll(sampleCount, capturedAt)
        if (radioTelemetry.isEmpty() || gnssTelemetry == null) {
            Log.d(TAG, "Skipped sample: sampleNumber=$sampleCount, radioSnapshots=${radioTelemetry.size}, hasGnss=${gnssTelemetry != null}")
            publishState()
            return
        }

        latestTelemetry = ScannerTelemetrySnapshot(radioTelemetry.first(), gnssTelemetry)
        radioTelemetry.forEach { radio ->
            writeCoverageSample(sampleCount, capturedAt, ScannerTelemetrySnapshot(radio, gnssTelemetry))
        }
        triggerContinuousReporting()
        publishState()
    }

    private fun refreshGnssTelemetry() {
        if (!scannerDesired() || scannerErrorReason() != null) return
        loadSettings()
        val capturedAt = Instant.now()
        val gnssTelemetry = gnssTelemetrySource.latest(sampleCount, capturedAt, gnssMode) ?: return
        latestTelemetry = ScannerTelemetrySnapshot(
            radio = latestTelemetry.radio,
            gnss = gnssTelemetry,
        )
        publishState()
    }

    private fun writeCoverageSample(sampleNumber: Int, capturedAt: Instant, telemetry: ScannerTelemetrySnapshot) {
        runCatching {
            val assemblyResult = CoverageSampleAssembler.servingSample(
                radio = telemetry.radio,
                gnss = telemetry.gnss,
                gnssThresholds = gnssQualityThresholds,
            )
            val sample = when (assemblyResult) {
                is AssemblyResult.Accepted -> assemblyResult.sample
                is AssemblyResult.Rejected -> {
                    Log.d(TAG, "Skipped coverage sample: sampleNumber=$sampleNumber, reason=${assemblyResult.reason}")
                    return@runCatching
                }
            }
            val storedSample = if (enhancedPrivacyEnabled) {
                CoverageSampleEnhancedPrivacyTransformer.reduce(sample)
            } else {
                sample
            }
            val sampleJson = CoverageSampleJsonEncoder.encode(storedSample)
            persistCoverageSample(
                sampleJson = sampleJson,
                capturedAt = storedSample.fix.timestamp,
                privacyReduced = enhancedPrivacyEnabled,
            )
            Log.d(TAG, "Persisted coverage sample: sampleNumber=$sampleNumber, capturedAt=${storedSample.fix.timestamp}, privacyReduced=$enhancedPrivacyEnabled")
        }.onFailure { error ->
            Log.e(TAG, "Could not persist coverage sample", error)
        }
    }

    private fun persistCoverageSample(sampleJson: String, capturedAt: Instant, privacyReduced: Boolean) {
        val mockTelemetry = effectiveMockTelemetry
        CoverageDatabaseProvider.ioExecutor.execute {
            runCatching {
                val sampleId = CoverageDatabaseProvider.database(this).coverageSampleDao().insert(
                    CoverageSampleEntity.from(
                        sampleJson = sampleJson,
                        capturedAt = capturedAt,
                        mockTelemetry = mockTelemetry,
                        privacyReduced = privacyReduced,
                    ),
                )
                sendBroadcast(
                    Intent(ACTION_SAMPLE_RECORDED)
                        .setPackage(packageName)
                        .putExtra(EXTRA_SAMPLE_ID, sampleId),
                )
            }.onFailure { error ->
                Log.e(TAG, "Could not persist coverage sample", error)
            }
        }
    }

    private fun triggerContinuousReporting() {
        if (reportingMode == ReportingMode.Continuous) {
            ReportingScheduler.recordTrigger(this, reportingMode.name)
        }
    }

    private fun scheduleNextSample(delayMs: Long) {
        if (sampleScheduled) return
        sampleScheduled = true
        handler.postDelayed(sampler, delayMs)
    }

    private fun requestSampleSoonForRadioEvent() {
        if (!scannerDesired() || scannerErrorReason() != null) return
        val now = Instant.now()
        val delayMs = lastSampleAttemptAt
            ?.let { lastAttempt ->
                AppConfig.Scanner.radioEventSampleMinSpacingMs -
                    java.time.Duration.between(lastAttempt, now).toMillis()
            }
            ?.coerceAtLeast(0L)
            ?: 0L
        handler.removeCallbacks(sampler)
        sampleScheduled = true
        handler.postDelayed(sampler, delayMs)
        Log.d(TAG, "Radio event requested sample: delayMs=$delayMs")
    }

    private fun scheduleGnssRefresh(delayMs: Long) {
        if (gnssRefreshScheduled) return
        gnssRefreshScheduled = true
        handler.postDelayed(gnssRefresh, delayMs)
    }

    private fun gnssRefreshIntervalMs(): Long =
        when (gnssMode) {
            GnssMode.HighAccuracy -> AppConfig.Scanner.highAccuracyGnssRefreshMs
            GnssMode.Balanced -> AppConfig.Scanner.balancedGnssRefreshMs
            GnssMode.LowPower -> AppConfig.Scanner.lowPowerGnssRefreshMs
        }

    private fun publishState() {
        val errorReason = scannerErrorReason()
        currentState = State(
            status = if (errorReason == null) ScannerStatus.RUNNING else ScannerStatus.ERROR,
            errorReason = errorReason,
            mockTelemetryActive = effectiveMockTelemetry,
            sampleCount = sampleCount,
            latestTelemetry = latestTelemetry,
        )
        updateForegroundNotification(errorReason)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun publishStoppedState() {
        currentState = currentState.copy(status = ScannerStatus.STOPPED, errorReason = null)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun scannerNotification(): Notification {
        return scannerNotification(scannerErrorReason())
    }

    private fun updateForegroundNotification(errorReason: String?) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, scannerNotification(errorReason))
    }

    private fun scannerNotification(errorReason: String?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hasError = errorReason != null
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_warning_48)
            .setContentTitle(if (hasError) getString(R.string.scanner_notification_error_title) else getString(R.string.scanner_notification_running_title))
            .setContentText(if (hasError) getString(R.string.scanner_notification_error_text, errorReason) else getString(R.string.scanner_notification_running_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setColor(if (hasError) SCANNER_ERROR_COLOR else SCANNER_RUNNING_COLOR)
            .setBadgeIconType(Notification.BADGE_ICON_SMALL)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.scanner_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(true)
        manager.createNotificationChannel(channel)
    }

    enum class ScannerStatus {
        STOPPED,
        RUNNING,
        ERROR,
    }

    data class State(
        val status: ScannerStatus,
        val errorReason: String?,
        val mockTelemetryActive: Boolean,
        val sampleCount: Int,
        val latestTelemetry: ScannerTelemetrySnapshot,
    ) {
        val isRunning: Boolean
            get() = status != ScannerStatus.STOPPED
    }

    companion object {
        const val ACTION_START = "no.politiet.pit.ScannerService.START"
        const val ACTION_STOP = "no.politiet.pit.ScannerService.STOP"
        const val ACTION_STATE_CHANGED = "no.politiet.pit.ScannerService.STATE_CHANGED"
        const val ACTION_SAMPLE_RECORDED = "no.politiet.pit.ScannerService.SAMPLE_RECORDED"
        const val EXTRA_SAMPLE_ID = "sampleId"
        private const val TAG = "5GScanner"
        private const val NOTIFICATION_ID = 4201
        private const val NOTIFICATION_CHANNEL_ID = "scanner"
        private val SCANNER_RUNNING_COLOR = Color.rgb(0, 38, 62)
        private val SCANNER_ERROR_COLOR = Color.rgb(207, 69, 32)

        @Volatile
        var currentState: State = State(
            status = ScannerStatus.STOPPED,
            errorReason = null,
            mockTelemetryActive = true,
            sampleCount = 0,
            latestTelemetry = ScannerTelemetrySnapshot.initial(AppConfig.Defaults.gnssMode),
        )
            private set

        fun start(context: Context) {
            val intent = Intent(context, ScannerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScannerService::class.java).setAction(ACTION_STOP))
        }

    }
}
