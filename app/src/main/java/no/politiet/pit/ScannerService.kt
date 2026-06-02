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
import no.politiet.pit.domain.GnssMode
import no.politiet.pit.domain.ReportingMode
import no.politiet.pit.encoding.CoverageSampleJsonEncoder
import no.politiet.pit.reporting.ReportingScheduler
import no.politiet.pit.storage.AppSettingsStore
import no.politiet.pit.storage.CoverageLogStore
import no.politiet.pit.telemetry.CoverageSampleAssembler
import no.politiet.pit.telemetry.CoverageSampleAssembler.AssemblyResult
import no.politiet.pit.telemetry.GnssTelemetrySource
import no.politiet.pit.telemetry.RadioTelemetrySource
import no.politiet.pit.telemetry.ScannerTelemetrySnapshot
import no.politiet.pit.telemetry.TelemetrySourceFactory
import java.time.Instant

class ScannerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var coverageLogStore: CoverageLogStore
    private lateinit var settingsStore: AppSettingsStore
    private lateinit var radioTelemetrySource: RadioTelemetrySource
    private lateinit var gnssTelemetrySource: GnssTelemetrySource
    private val gnssQualityThresholds = CoverageSampleAssembler.GnssQualityThresholds(
        maxStationaryFixAgeSeconds = 30,
        maxSlowFixAgeSeconds = 10,
        maxFastFixAgeSeconds = 5,
        maxHorizontalAccuracyMeters = 50f,
        maxHdop = 4.0,
        maxSnapshotAgeSeconds = 30,
    )
    private var sampleCount = 0
    private var latestTelemetry = ScannerTelemetrySnapshot.initial(GnssMode.Balanced)
    private var sampleScheduled = false
    private var gnssRefreshScheduled = false
    private var gnssMode = GnssMode.Balanced
    private var telemetryGnssMode = GnssMode.Balanced
    private var reportingMode = ReportingMode.Hourly
    private var mockTelemetryEnabled = true
    private var effectiveMockTelemetry = true

    private val sampler = object : Runnable {
        override fun run() {
            sampleScheduled = false
            if (!scannerDesired()) {
                stopScannerService()
            } else if (scannerBlockReason() == null) {
                captureSample()
                scheduleNextSample(SAMPLE_INTERVAL_MS)
            } else {
                publishState()
                scheduleNextSample(BLOCKED_RECHECK_INTERVAL_MS)
            }
        }
    }

    private val gnssRefresh = object : Runnable {
        override fun run() {
            gnssRefreshScheduled = false
            if (!scannerDesired()) {
                stopScannerService()
            } else if (scannerBlockReason() == null) {
                refreshGnssTelemetry()
                scheduleGnssRefresh(gnssRefreshIntervalMs())
            } else {
                publishState()
                scheduleGnssRefresh(BLOCKED_RECHECK_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        coverageLogStore = CoverageLogStore(this)
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
        scheduleNextSample(0L)
        scheduleGnssRefresh(gnssRefreshIntervalMs())
        Log.i(TAG, "Scanner service started: gnssMode=${gnssMode.name}, reportingMode=${reportingMode.name}")
    }

    private fun stopScannerService() {
        handler.removeCallbacks(sampler)
        handler.removeCallbacks(gnssRefresh)
        sampleScheduled = false
        gnssRefreshScheduled = false
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
        if (telemetryGnssMode != gnssMode) {
            latestTelemetry = ScannerTelemetrySnapshot.initial(gnssMode)
            telemetryGnssMode = gnssMode
        }
    }

    private fun configureTelemetrySources(useMockTelemetry: Boolean) {
        val sources = TelemetrySourceFactory.create(this, useMockTelemetry)
        radioTelemetrySource = sources.radio
        gnssTelemetrySource = sources.gnss
        effectiveMockTelemetry = useMockTelemetry
        Log.i(TAG, "Telemetry sources configured: ${if (useMockTelemetry) "mock" else "android"}")
    }

    private fun scannerDesired(): Boolean {
        val settings = settingsStore.load(legacyConsentGranted = false)
        return settings.consentGranted && !settings.scannerStopped
    }

    private fun scannerBlockReason(): String? {
        if (!scannerDesired()) return null
        val hasLocationPermission =
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) return getString(R.string.scanner_block_missing_location_permission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return getString(R.string.scanner_block_missing_notification_permission)
        }
        val locationManager = getSystemService(LocationManager::class.java)
        if (locationManager != null && !locationManager.isLocationEnabled) return getString(R.string.scanner_block_location_disabled)
        if (Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) return getString(R.string.scanner_block_airplane_mode)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return getString(R.string.scanner_block_telephony_unavailable)
        return null
    }

    private fun captureSample() {
        loadSettings()
        sampleCount += 1
        val capturedAt = Instant.now()
        val radioTelemetry = radioTelemetrySource.latest(sampleCount, capturedAt)
        val gnssTelemetry = gnssTelemetrySource.latest(sampleCount, capturedAt, gnssMode)
        if (radioTelemetry == null || gnssTelemetry == null) {
            Log.d(TAG, "Skipped sample: sampleNumber=$sampleCount, hasRadio=${radioTelemetry != null}, hasGnss=${gnssTelemetry != null}")
            publishState()
            return
        }

        latestTelemetry = ScannerTelemetrySnapshot(radioTelemetry, gnssTelemetry)
        writeCoverageSample(sampleCount, capturedAt, latestTelemetry)
        triggerContinuousReporting()
        publishState()
    }

    private fun refreshGnssTelemetry() {
        if (!scannerDesired() || scannerBlockReason() != null) return
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
            coverageLogStore.append(
                sampleJson = CoverageSampleJsonEncoder.encode(sample),
                capturedAt = capturedAt,
            )
            Log.d(TAG, "Appended coverage sample: sampleNumber=$sampleNumber, capturedAt=$capturedAt")
        }.onFailure { error ->
            Log.e(TAG, "Could not append coverage sample", error)
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

    private fun scheduleGnssRefresh(delayMs: Long) {
        if (gnssRefreshScheduled) return
        gnssRefreshScheduled = true
        handler.postDelayed(gnssRefresh, delayMs)
    }

    private fun gnssRefreshIntervalMs(): Long =
        when (gnssMode) {
            GnssMode.HighAccuracy -> 5_000L
            GnssMode.Balanced -> 11_000L
            GnssMode.LowPower -> 29_000L
        }

    private fun publishState() {
        val blockReason = scannerBlockReason()
        currentState = State(
            isRunning = true,
            blockedReason = blockReason,
            mockTelemetryActive = effectiveMockTelemetry,
            sampleCount = sampleCount,
            latestTelemetry = latestTelemetry,
        )
        updateForegroundNotification(blockReason)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun publishStoppedState() {
        currentState = currentState.copy(isRunning = false, blockedReason = null)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun scannerNotification(): Notification {
        return scannerNotification(scannerBlockReason())
    }

    private fun updateForegroundNotification(blockReason: String?) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, scannerNotification(blockReason))
    }

    private fun scannerNotification(blockReason: String?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val blocked = blockReason != null
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_warning_48)
            .setContentTitle(if (blocked) getString(R.string.scanner_notification_blocked_title) else getString(R.string.scanner_notification_title))
            .setContentText(if (blocked) getString(R.string.scanner_notification_blocked_text, blockReason) else getString(R.string.scanner_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setColor(if (blocked) SCANNER_BLOCKED_COLOR else SCANNER_ACTIVE_COLOR)
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
        manager.createNotificationChannel(channel)
    }

    data class State(
        val isRunning: Boolean,
        val blockedReason: String?,
        val mockTelemetryActive: Boolean,
        val sampleCount: Int,
        val latestTelemetry: ScannerTelemetrySnapshot,
    )

    companion object {
        const val ACTION_START = "no.politiet.pit.ScannerService.START"
        const val ACTION_STOP = "no.politiet.pit.ScannerService.STOP"
        const val ACTION_STATE_CHANGED = "no.politiet.pit.ScannerService.STATE_CHANGED"
        private const val TAG = "AskScanner"
        private const val NOTIFICATION_ID = 4201
        private const val NOTIFICATION_CHANNEL_ID = "scanner"
        private const val SAMPLE_INTERVAL_MS = 15_000L
        private const val BLOCKED_RECHECK_INTERVAL_MS = 5_000L
        private val SCANNER_ACTIVE_COLOR = Color.rgb(15, 118, 110)
        private val SCANNER_BLOCKED_COLOR = Color.rgb(220, 38, 38)

        @Volatile
        var currentState: State = State(
            isRunning = false,
            blockedReason = null,
            mockTelemetryActive = true,
            sampleCount = 0,
            latestTelemetry = ScannerTelemetrySnapshot.initial(GnssMode.Balanced),
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
