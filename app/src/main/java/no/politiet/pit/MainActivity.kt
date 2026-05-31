package no.politiet.pit

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.DashPathEffect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    private var consentGranted = false
    private var stoppedManually = false
    private var showingSettings = false
    private var settingsDestination = SettingsDestination.Main
    private var selectedLogFile: CoverageLogStore.LogFile? = null
    private var sampleCount = 0
    private var lastSampleAt: Instant? = null
    private var frequency = SamplingFrequency.Balanced
    private var gnssMode = GnssMode.Balanced
    private var reportingMode = ReportingMode.Hourly
    private var lastReportedAt: Instant? = null

    private var statusText: TextView? = null
    private var sessionSampleText: TextView? = null
    private var telemetryBars: TelemetryBarsView? = null
    private var titleSignalIcon: SignalQualityIconView? = null
    private var stopStartButton: ImageButton? = null
    private var sleepIndicator: SleepIndicatorView? = null
    private var scannerActivityRing: View? = null
    private var scannerActivityRingAnimator: ObjectAnimator? = null
    private var latestTelemetry = MockTelemetry.initial(gnssMode)
    private var samplerScheduled = false
    private lateinit var coverageLogStore: CoverageLogStore

    private val sampler = object : Runnable {
        override fun run() {
            samplerScheduled = false
            if (canSample()) {
                captureMockSample()
                scheduleNextSample(frequency.intervalMs)
            } else {
                render()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coverageLogStore = CoverageLogStore(this)
        loadState()
        saveState()
        render()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sampler)
        samplerScheduled = false
        scannerActivityRingAnimator?.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            showingSettings && settingsDestination == SettingsDestination.LogFile -> {
                settingsDestination = SettingsDestination.LogList
                selectedLogFile = null
                render()
            }
            showingSettings && settingsDestination == SettingsDestination.LogList -> {
                settingsDestination = SettingsDestination.Main
                render()
            }
            showingSettings -> {
                showingSettings = false
                settingsDestination = SettingsDestination.Main
                selectedLogFile = null
                render()
            }
            else -> super.onBackPressed()
        }
    }

    private fun render() {
        setContentView(when {
            !consentGranted -> createConsentView()
            showingSettings -> when (settingsDestination) {
                SettingsDestination.Main -> createSettingsView()
                SettingsDestination.LogList -> createLogListView()
                SettingsDestination.LogFile -> createLogFileView()
            }
            else -> createScannerView()
        })
        if (consentGranted) {
            ensureSamplerState()
            ensureReportingScheduler()
            updateScannerUi()
        }
    }

    private fun createConsentView(): View {
        clearScannerViews()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), statusBarHeight() + dp(32), dp(24), dp(24))
        }

        content.addView(consentTitle(getString(R.string.app_name)))
        content.addView(consentIntro(getString(R.string.consent_intro)))
        content.addView(consentSection(
            title = getString(R.string.consent_data_scope_title),
            body = getString(R.string.consent_data_scope),
        ))
        content.addView(consentSection(
            title = getString(R.string.consent_storage_title),
            body = getString(R.string.consent_local_only),
        ))
        content.addView(consentSection(
            title = getString(R.string.consent_control_title),
            body = getString(R.string.consent_reversible),
        ))
        content.addView(Button(this).apply {
            text = getString(R.string.grant_consent)
            setOnClickListener {
                consentGranted = true
                saveState()
                handler.postDelayed({ render() }, CONSENT_TRANSITION_DELAY_MS)
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(24)
        })

        return ScrollView(this).apply {
            setBackgroundColor(SETTINGS_BACKGROUND)
            isFillViewport = true
            addView(content)
        }
    }

    private fun createScannerView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), statusBarHeight() + dp(28), dp(28), dp(152))
        }

        content.addView(scannerTitleBlock())

        content.addView(scannerSectionHeader(getString(R.string.latest_measurement_title)))
        telemetryBars = TelemetryBarsView().apply {
            setMetrics(latestTelemetry.metrics(), animate = false)
        }
        content.addView(telemetryBars, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(244),
        ))

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }

        stopStartButton = scannerIconButton(R.drawable.ic_stop_32, getString(R.string.stop_scanning), dp(56)) {
            if (!isStopped()) {
                stopScanning()
            } else {
                startScanning()
            }
        }

        statusText = scannerStatusText()
        sessionSampleText = scannerSessionSampleText()
        scannerActivityRing = ScannerActivityRing()
        sleepIndicator = SleepIndicatorView()
        val settingsButton = scannerIconButton(R.drawable.ic_settings_24, getString(R.string.open_settings), dp(28)) {
            showingSettings = true
            settingsDestination = SettingsDestination.Main
            selectedLogFile = null
            render()
        }

        return FrameLayout(this).apply {
            setBackgroundColor(SCANNER_BACKGROUND)
            addView(scrollView)
            val controlBottomMargin = navigationBarHeight() + dp(160)
            addView(statusText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                leftMargin = dp(28)
                rightMargin = dp(28)
                bottomMargin = controlBottomMargin + dp(136)
            })
            addView(scannerActivityRing, FrameLayout.LayoutParams(dp(144), dp(144), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = controlBottomMargin - dp(16)
            })
            addView(stopStartButton, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = controlBottomMargin
            })
            addView(sleepIndicator, FrameLayout.LayoutParams(dp(188), dp(188), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = controlBottomMargin - dp(38)
            })
            addView(sessionSampleText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                leftMargin = dp(28)
                rightMargin = dp(28)
                bottomMargin = controlBottomMargin - dp(38)
            })
            addView(settingsButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(24)
                bottomMargin = navigationBarHeight() + dp(40)
            })
        }
    }

    private fun createSettingsView(): View {
        clearScannerViews()
        lastReportedAt = ReportingScheduler.lastReportedAt(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        content.addView(settingsSectionLabel(getString(R.string.settings_section_scanning)))
        content.addView(settingsGroup(
            settingsPreferenceRow(
                title = getString(R.string.setting_sampling_frequency_title),
                summary = frequency.summary,
                value = frequency.label,
            ) {
                showSamplingFrequencyDialog()
            },
        ))

        content.addView(settingsSectionLabel(getString(R.string.settings_section_location)))
        content.addView(settingsGroup(
            settingsPreferenceRow(
                title = getString(R.string.setting_gnss_mode_title),
                summary = gnssMode.summary,
                value = gnssMode.label,
            ) {
                showGnssModeDialog()
            },
        ))

        val reportingRows = mutableListOf<View>(
            settingsPreferenceRow(
                title = getString(R.string.setting_reporting_mode_title),
                summary = reportingSummary(),
                value = reportingMode.label,
            ) {
                showReportingModeDialog()
            },
        )
        if (reportingMode != ReportingMode.Continuous) {
            reportingRows.add(settingsActionButtonRow(
                title = getString(R.string.setting_send_now_title),
                summary = getString(R.string.setting_send_now_summary),
            ) {
                sendNow()
            })
        }
        content.addView(settingsSectionLabel(getString(R.string.settings_section_reporting)))
        content.addView(settingsGroup(*reportingRows.toTypedArray()))

        content.addView(settingsSectionLabel(getString(R.string.settings_section_logs)))
        content.addView(settingsGroup(
            settingsPreferenceRow(
                title = getString(R.string.setting_view_logs_title),
                summary = logStatsSummary(),
                value = "",
            ) {
                settingsDestination = SettingsDestination.LogList
                render()
            },
        ))

        content.addView(settingsSectionLabel(getString(R.string.settings_section_privacy)))
        content.addView(settingsGroup(
            settingsInfoRow(
                title = getString(R.string.setting_storage_title),
                summary = getString(
                    R.string.setting_storage_summary,
                    coverageLogStore.displayDirectory(),
                ),
            ),
            settingsInfoRow(
                title = getString(R.string.setting_participation_title),
                summary = getString(R.string.setting_participation_summary),
            ),
        ))

        val scroller = ScrollView(this).apply {
            setBackgroundColor(SETTINGS_BACKGROUND)
            addView(content)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SETTINGS_BACKGROUND)
            addView(settingsToolbar())
            addView(scroller, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }

        updateScannerUi()

        return root
    }

    private fun createLogListView(): View {
        clearScannerViews()
        val logs = coverageLogStore.listLogs()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        content.addView(settingsSectionLabel(getString(R.string.settings_section_logs)))
        if (logs.isEmpty()) {
            content.addView(settingsGroup(
                settingsInfoRow(
                    title = getString(R.string.log_files_empty_title),
                    summary = getString(R.string.log_files_empty_summary, coverageLogStore.displayDirectory()),
                ),
            ))
        } else {
            val rows = logs.map { logFile ->
                settingsActionRow(
                    title = logFile.name,
                    summary = getString(
                        R.string.log_file_summary,
                        formatBytes(logFile.sizeBytes),
                        dateTimeFormatter.format(Instant.ofEpochMilli(logFile.modifiedAtMillis)),
                    ),
                ) {
                    selectedLogFile = logFile
                    settingsDestination = SettingsDestination.LogFile
                    render()
                }
            }
            content.addView(settingsGroup(*rows.toTypedArray()))
            content.addView(settingsSectionLabel(getString(R.string.coverage_logs_actions_section)))
            content.addView(settingsGroup(
                settingsDestructiveActionRow(
                    title = getString(R.string.delete_all_logs_title),
                    summary = getString(R.string.delete_all_logs_summary),
                ) {
                    confirmDeleteAllLogs()
                },
            ))
        }

        return settingsScreen(
            title = getString(R.string.setting_view_logs_title),
            backDescription = getString(R.string.back_to_settings),
            onBack = {
                settingsDestination = SettingsDestination.Main
                render()
            },
            body = content,
        )
    }

    private fun confirmDeleteAllLogs() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_all_logs_title))
            .setMessage(getString(R.string.delete_all_logs_confirm_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.delete_all_logs_confirm_action)) { _, _ ->
                coverageLogStore.deleteAllLogs()
                selectedLogFile = null
                settingsDestination = SettingsDestination.LogList
                render()
            }
            .show()
    }

    private fun createLogFileView(): View {
        clearScannerViews()
        val logFile = selectedLogFile
        if (logFile == null) {
            settingsDestination = SettingsDestination.LogList
            return createLogListView()
        }

        val rawText = coverageLogStore.read(logFile)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.log_file_raw_summary, formatBytes(logFile.sizeBytes))
            textSize = 14f
            setTextColor(SETTINGS_SUBTLE_TEXT)
            includeFontPadding = false
            setPadding(0, 0, 0, dp(12))
        })

        val rawTextView = TextView(this).apply {
            text = rawText.ifBlank { getString(R.string.log_file_empty) }
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(SETTINGS_TEXT)
            setTextIsSelectable(true)
            includeFontPadding = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val horizontalScroller = HorizontalScrollView(this).apply {
            addView(rawTextView)
        }
        content.addView(horizontalScroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        return settingsScreen(
            title = logFile.name,
            backDescription = getString(R.string.back_to_logs),
            onBack = {
                settingsDestination = SettingsDestination.LogList
                selectedLogFile = null
                render()
            },
            body = content,
        )
    }

    private fun clearScannerViews() {
        statusText = null
        sessionSampleText = null
        telemetryBars = null
        titleSignalIcon = null
        stopStartButton = null
        sleepIndicator?.stop()
        sleepIndicator = null
        scannerActivityRingAnimator?.cancel()
        scannerActivityRingAnimator = null
        scannerActivityRing = null
    }

    private fun captureMockSample() {
        sampleCount += 1
        val capturedAt = Instant.now()
        lastSampleAt = capturedAt
        latestTelemetry = MockTelemetry.fromSample(sampleCount, gnssMode)
        writeMockSample(sampleCount, capturedAt, latestTelemetry)
        telemetryBars?.setMetrics(latestTelemetry.metrics(), animate = true)
        triggerContinuousReporting()
        updateScannerUi()
    }

    private fun writeMockSample(sampleNumber: Int, capturedAt: Instant, telemetry: MockTelemetry) {
        runCatching {
            coverageLogStore.append(
                sampleJson = mockCoverageSampleJson(sampleNumber, capturedAt, telemetry),
                capturedAt = capturedAt,
            )
        }.onFailure { error ->
            Log.e(TAG, "Could not append coverage sample", error)
        }
    }

    private fun mockCoverageSampleJson(
        sampleNumber: Int,
        capturedAt: Instant,
        telemetry: MockTelemetry,
    ): String {
        val wave = sampleNumber % 24
        val fix = JSONObject()
            .put("timestamp", capturedAt.toString())
            .put("gpsTime", JSONObject.NULL)
            .put("lat", 59.9139 + wave * 0.00008)
            .put("lon", 10.7522 + wave * 0.00011)
            .put("altitude", 23.0 + (wave % 5))
            .put("speed", if (telemetry.gnssMode == GnssMode.HighAccuracy) 1.6 else 0.8)
            .put("heading", (sampleNumber * 17) % 360)
            .put("hdop", telemetry.hdop.toDouble())
            .put("satellites", if (telemetry.gnssMode == GnssMode.HighAccuracy) 18 else 11)

        val signal = JSONObject()
            .put("rsrp", telemetry.rsrp)
            .put("rsrq", telemetry.rsrq)
            .put("sinr", telemetry.sinr)
            .put("rssi", telemetry.rsrp + 30)

        val cell = JSONObject()
            .put("rat", "LTE")
            .put("mcc", 242)
            .put("mnc", 1)
            .put("cellId", "mock-${100000 + sampleNumber}")
            .put("tac", 4100 + (sampleNumber % 12))
            .put("pci", 120 + (sampleNumber % 48))
            .put("earfcn", 6300 + (sampleNumber % 20))
            .put("band", 20)
            .put("signal", signal)

        return JSONObject()
            .put("kind", "serving")
            .put("fix", fix)
            .put("cell", cell)
            .toString()
    }

    private fun stopScanning() {
        stoppedManually = true
        handler.removeCallbacks(sampler)
        samplerScheduled = false
        updateScannerUi()
    }

    private fun startScanning() {
        stoppedManually = false
        ensureSamplerState()
        updateScannerUi()
    }

    private fun canSample(): Boolean {
        return consentGranted && !stoppedManually
    }

    private fun ensureSamplerState() {
        if (!canSample()) {
            handler.removeCallbacks(sampler)
            samplerScheduled = false
            return
        }
        scheduleNextSample(0L)
    }

    private fun scheduleNextSample(delayMs: Long) {
        if (samplerScheduled) return
        samplerScheduled = true
        handler.postDelayed(sampler, delayMs)
    }

    private fun ensureReportingScheduler() {
        ReportingScheduler.schedule(this, consentGranted, reportingMode.name)
    }

    private fun triggerContinuousReporting() {
        if (consentGranted && reportingMode == ReportingMode.Continuous) {
            ReportingScheduler.recordTrigger(this, reportingMode.name) { success ->
                if (success) {
                    lastReportedAt = ReportingScheduler.lastReportedAt(this)
                    if (showingSettings) {
                        render()
                    }
                }
            }
        }
    }

    private fun updateScannerUi() {
        val effectiveStatus = currentScannerStatus()

        statusText?.text = effectiveStatus
        sessionSampleText?.text = resources.getQuantityString(
            R.plurals.samples_this_session,
            sampleCount,
            sampleCount,
        )
        sessionSampleText?.visibility = if (canSample()) View.VISIBLE else View.INVISIBLE
        titleSignalIcon?.setQuality(
            quality = if (canSample()) latestTelemetry.overallQuality() else 0f,
            animate = sampleCount > 0,
        )
        if (!canSample()) {
            telemetryBars?.showNoData()
            sleepIndicator?.start()
        } else {
            sleepIndicator?.stop()
        }
        stopStartButton?.apply {
            if (isStopped()) {
                setImageResource(R.drawable.ic_play_arrow_32)
                contentDescription = getString(R.string.start_scanning)
            } else {
                setImageResource(R.drawable.ic_stop_32)
                contentDescription = getString(R.string.stop_scanning)
            }
        }
        updateScannerActivityRing(canSample())
    }

    private fun currentScannerStatus(): String {
        return when {
            stoppedManually -> getString(R.string.status_stopped_manual)
            else -> getString(R.string.status_scanning)
        }
    }

    private fun reportingSummary(): String =
        getString(
            R.string.setting_reporting_mode_summary_with_last_sent,
            reportingMode.summary,
            lastReportedAt?.let(dateTimeFormatter::format) ?: getString(R.string.never),
        )

    private fun logStatsSummary(): String {
        val stats = coverageLogStore.stats()
        return resources.getQuantityString(
            R.plurals.log_files_summary,
            stats.fileCount,
            stats.fileCount,
            formatBytes(stats.totalBytes),
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return String.format("%.1f %s", value, units[unitIndex])
    }

    private fun isStopped(): Boolean = stoppedManually

    private fun loadState() {
        val preferences = ReportingScheduler.appPreferences(this)
        val legacyPreferences = getPreferences(MODE_PRIVATE)
        consentGranted = preferences.getBoolean(
            ReportingScheduler.KEY_CONSENT_GRANTED,
            legacyPreferences.getBoolean(ReportingScheduler.KEY_CONSENT_GRANTED, false),
        )
        frequency = SamplingFrequency.fromName(
            preferences.getString(KEY_FREQUENCY, SamplingFrequency.Balanced.name),
        )
        gnssMode = GnssMode.fromName(
            preferences.getString(KEY_GNSS_MODE, GnssMode.Balanced.name),
        )
        reportingMode = ReportingMode.fromName(
            preferences.getString(ReportingScheduler.KEY_REPORTING_MODE, ReportingMode.Hourly.name),
        )
        lastReportedAt = ReportingScheduler.lastReportedAt(this)
    }

    private fun saveState() {
        ReportingScheduler.appPreferences(this).edit()
            .putBoolean(ReportingScheduler.KEY_CONSENT_GRANTED, consentGranted)
            .putString(KEY_FREQUENCY, frequency.name)
            .putString(KEY_GNSS_MODE, gnssMode.name)
            .putString(ReportingScheduler.KEY_REPORTING_MODE, reportingMode.name)
            .apply()
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 32f
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, 12)
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, 4, 0, 4)
    }

    private fun consentTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 28f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(SETTINGS_TEXT)
        includeFontPadding = false
        setPadding(0, 0, 0, dp(12))
    }

    private fun consentIntro(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(SETTINGS_SUBTLE_TEXT)
        setPadding(0, 0, 0, dp(20))
    }

    private fun consentSection(title: String, body: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))

            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(SETTINGS_TEXT)
                includeFontPadding = false
            })
            addView(TextView(this@MainActivity).apply {
                text = body
                textSize = 14f
                setTextColor(SETTINGS_SUBTLE_TEXT)
                setPadding(0, dp(6), 0, 0)
            })
        }

    private fun scannerTitleBlock(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(18))

            addView(scannerTitleHeader())
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_subtitle)
                textSize = 14f
                setTextColor(SCANNER_SOFT_TEXT)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun scannerTitleHeader(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            titleSignalIcon = SignalQualityIconView().apply {
                setQuality(latestTelemetry.overallQuality(), animate = false)
            }
            addView(titleSignalIcon, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(12)
            })

            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 44f
                setTextColor(Color.WHITE)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

    private fun scannerStatusText(): TextView = TextView(this).apply {
        textSize = 18f
        setTextColor(SCANNER_SOFT_TEXT)
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    private fun scannerSessionSampleText(): TextView = TextView(this).apply {
        textSize = 12f
        setTextColor(SCANNER_SOFT_TEXT)
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    private fun scannerSection(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 13f
        setTextColor(SCANNER_SOFT_TEXT)
        includeFontPadding = false
        setPadding(0, dp(12), 0, dp(10))
    }

    private fun scannerSectionHeader(text: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(10))

            addView(TextView(this@MainActivity).apply {
                this.text = text.uppercase()
                textSize = 13f
                setTextColor(SCANNER_SOFT_TEXT)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_help_24)
                setColorFilter(SCANNER_SOFT_TEXT)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = getString(R.string.measurement_help_action)
                background = RippleDrawable(
                    ColorStateList.valueOf(SCANNER_BUTTON_RIPPLE),
                    roundedBackground(Color.TRANSPARENT, dp(18)),
                    roundedBackground(Color.WHITE, dp(18)),
                )
                setOnClickListener { showMeasurementHelp() }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
        }

    private fun showMeasurementHelp() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.measurement_help_title))
            .setMessage(getString(R.string.measurement_help_body))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun scannerIconButton(icon: Int, description: String, radius: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            setColorFilter(SCANNER_BACKGROUND)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            background = RippleDrawable(
                ColorStateList.valueOf(SCANNER_BUTTON_RIPPLE),
                roundedBackground(Color.WHITE, radius),
                roundedBackground(Color.WHITE, radius),
            )
            elevation = dp(8).toFloat()
            setOnClickListener { onClick() }
        }

    private fun updateScannerActivityRing(isActive: Boolean) {
        val ring = scannerActivityRing ?: return
        if (!isActive) {
            scannerActivityRingAnimator?.cancel()
            scannerActivityRingAnimator = null
            ring.visibility = View.INVISIBLE
            ring.rotation = 0f
            return
        }

        ring.visibility = View.VISIBLE
        if (scannerActivityRingAnimator?.isStarted == true) return
        scannerActivityRingAnimator = ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
            duration = 1600L
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun settingsScreen(
        title: String,
        backDescription: String,
        onBack: () -> Unit,
        body: View,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SETTINGS_BACKGROUND)
            addView(settingsToolbar(title, backDescription, onBack))
            addView(ScrollView(this@MainActivity).apply {
                setBackgroundColor(SETTINGS_BACKGROUND)
                addView(body)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }

    private fun settingsToolbar(
        title: String = getString(R.string.settings_title),
        backDescription: String = getString(R.string.back_to_scanner),
        onBack: () -> Unit = {
            showingSettings = false
            settingsDestination = SettingsDestination.Main
            selectedLogFile = null
            render()
        },
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), statusBarHeight() + dp(8), dp(20), dp(8))
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()

            addView(ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_arrow_back_24)
                setColorFilter(SETTINGS_TEXT)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = backDescription
                background = rippleBackground(Color.TRANSPARENT, dp(24))
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))

            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 20f
                setTextColor(SETTINGS_TEXT)
                includeFontPadding = false
                maxLines = 1
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun settingsSectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text.uppercase()
            textSize = 13f
            setTextColor(SETTINGS_ACCENT)
            includeFontPadding = false
            setPadding(dp(16), dp(24), dp(16), dp(8))
        }

    private fun settingsGroup(vararg rows: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, dp(8))
            clipToOutline = true
            rows.forEachIndexed { index, row ->
                addView(row)
                if (index < rows.lastIndex) {
                    addView(settingsDivider())
                }
            }
        }

    private fun settingsPreferenceRow(
        title: String,
        summary: String,
        value: String,
        onClick: () -> Unit,
    ): LinearLayout =
        settingsRow(title, summary, value, isClickable = true).apply {
            setOnClickListener { onClick() }
        }

    private fun settingsInfoRow(title: String, summary: String): LinearLayout =
        settingsRow(title, summary, value = null, isClickable = false)

    private fun settingsActionRow(title: String, summary: String, onClick: () -> Unit): LinearLayout =
        settingsRow(title, summary, value = null, isClickable = true).apply {
            setOnClickListener { onClick() }
        }

    private fun settingsActionButtonRow(title: String, summary: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rippleBackground(Color.WHITE, dp(8))
            isFocusable = true
            setOnClickListener { onClick() }

            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_send_24)
                setColorFilter(SETTINGS_ACCENT)
                scaleType = ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(16)
            })

            val textColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            textColumn.addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(SETTINGS_ACCENT)
                includeFontPadding = false
            })
            textColumn.addView(TextView(this@MainActivity).apply {
                text = summary
                textSize = 14f
                setTextColor(SETTINGS_SUBTLE_TEXT)
                setPadding(0, dp(4), 0, 0)
            })
            addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun settingsDestructiveActionRow(title: String, summary: String, onClick: () -> Unit): LinearLayout =
        settingsRow(
            title = title,
            summary = summary,
            value = null,
            isClickable = true,
            titleColor = SETTINGS_DESTRUCTIVE,
        ).apply {
            setOnClickListener { onClick() }
        }

    private fun settingsRow(
        title: String,
        summary: String,
        value: String?,
        isClickable: Boolean,
        titleColor: Int = SETTINGS_TEXT,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            if (isClickable) {
                background = rippleBackground(Color.WHITE, dp(16))
                isFocusable = true
            }

            val textColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            textColumn.addView(TextView(this@MainActivity).apply {
                this.text = title
                textSize = 16f
                setTextColor(titleColor)
                includeFontPadding = false
            })
            textColumn.addView(TextView(this@MainActivity).apply {
                this.text = summary
                textSize = 14f
                setTextColor(SETTINGS_SUBTLE_TEXT)
                setPadding(0, dp(4), 0, 0)
            })
            addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (value != null) {
                addView(TextView(this@MainActivity).apply {
                    text = value
                    textSize = 14f
                    setTextColor(SETTINGS_SUBTLE_TEXT)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    maxLines = 2
                    setPadding(dp(16), 0, dp(8), 0)
                }, LinearLayout.LayoutParams(dp(116), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_chevron_right_24)
                    setColorFilter(SETTINGS_CHEVRON)
                    scaleType = ImageView.ScaleType.CENTER
                }, LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }

    private fun settingsDivider(): View =
        View(this).apply {
            setBackgroundColor(SETTINGS_DIVIDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply {
                marginStart = dp(16)
            }
        }

    private fun showSamplingFrequencyDialog() {
        val labels = SamplingFrequency.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_sampling_frequency_title))
            .setSingleChoiceItems(labels, SamplingFrequency.entries.indexOf(frequency)) { dialog, which ->
                frequency = SamplingFrequency.entries[which]
                saveState()
                ensureSamplerState()
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGnssModeDialog() {
        val labels = GnssMode.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_gnss_mode_title))
            .setSingleChoiceItems(labels, GnssMode.entries.indexOf(gnssMode)) { dialog, which ->
                gnssMode = GnssMode.entries[which]
                saveState()
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showReportingModeDialog() {
        val labels = ReportingMode.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_reporting_mode_title))
            .setSingleChoiceItems(labels, ReportingMode.entries.indexOf(reportingMode)) { dialog, which ->
                reportingMode = ReportingMode.entries[which]
                saveState()
                ensureReportingScheduler()
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendNow() {
        ReportingScheduler.recordTrigger(this, reportingMode.name) { success ->
            lastReportedAt = ReportingScheduler.lastReportedAt(this)
            render()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.setting_send_now_title))
                .setMessage(
                    if (success) {
                        getString(R.string.setting_send_now_success_message)
                    } else {
                        getString(R.string.setting_send_now_failed_message)
                    },
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun rippleBackground(color: Int, radius: Int): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(SETTINGS_RIPPLE),
            if (color == Color.TRANSPARENT) ColorDrawable(Color.TRANSPARENT) else roundedBackground(color, radius),
            roundedBackground(Color.WHITE, radius),
        )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun navigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private inner class ScannerActivityRing : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SCANNER_RING
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(3).toFloat()
        }
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = paint.strokeWidth / 2f + dp(4)
            bounds.set(inset, inset, width - inset, height - inset)
            canvas.drawArc(bounds, -90f, 82f, false, paint)
            canvas.drawArc(bounds, 62f, 34f, false, paint)
        }
    }

    private inner class SleepIndicatorView : View(this@MainActivity) {
        private var animator: ValueAnimator? = null
        private val random = Random(System.nanoTime())
        private val specs = MutableList(5) { randomSleepSpec() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        init {
            visibility = View.INVISIBLE
        }

        fun start() {
            visibility = View.VISIBLE
            if (animator?.isStarted == true) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 16_000L
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    invalidate()
                }
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
            visibility = View.INVISIBLE
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = System.currentTimeMillis()
            specs.forEachIndexed { index, spec ->
                val age = now - spec.startedAtMs
                val phase = age.toFloat() / spec.durationMs.toFloat()
                if (phase >= 1f) {
                    specs[index] = randomSleepSpec(now)
                    return@forEachIndexed
                }

                val alpha = (255 * (1f - phase)).toInt().coerceIn(0, 255)
                val size = dp(spec.size).toFloat()
                val x = width * (spec.x + spec.driftX * phase)
                val y = height * (spec.y + spec.driftY * phase)
                paint.alpha = alpha
                paint.textSize = size
                canvas.drawText("z", x, y, paint)
            }
            paint.alpha = 255
        }

        private fun randomSleepSpec(startedAtMs: Long = System.currentTimeMillis()): SleepSpec {
            val angle = randomSleepAngle()
            val radius = random.nextFloatIn(0.36f, 0.44f)
            val x = 0.5f + kotlin.math.cos(angle) * radius
            val y = 0.5f + kotlin.math.sin(angle) * radius
            val outwardX = kotlin.math.cos(angle) * random.nextFloatIn(0.03f, 0.08f)
            val upwardBias = -random.nextFloatIn(0.03f, 0.09f)
            return SleepSpec(
                x = x.coerceIn(0.08f, 0.92f),
                y = y.coerceIn(0.08f, 0.92f),
                driftX = outwardX,
                driftY = upwardBias + kotlin.math.sin(angle) * random.nextFloatIn(0.01f, 0.04f),
                size = random.nextInt(19, 27),
                durationMs = random.nextLong(1_700L, 2_900L),
                startedAtMs = startedAtMs - random.nextLong(0L, 1_300L),
            )
        }

        private fun randomSleepAngle(): Float =
            if (random.nextBoolean()) {
                random.nextFloatIn(0f, (Math.PI * 7f / 6f).toFloat())
            } else {
                random.nextFloatIn((Math.PI * 11f / 6f).toFloat(), (Math.PI * 2f).toFloat())
            }
    }

    private data class SleepSpec(
        val x: Float,
        val y: Float,
        val driftX: Float,
        val driftY: Float,
        val size: Int,
        val durationMs: Long,
        val startedAtMs: Long,
    )

    private inner class TelemetryBarsView : View(this@MainActivity) {
        private var metrics: List<MetricQuality> = emptyList()
        private var showNoData = false
        private var animatedQualities: List<Float> = emptyList()
        private var previousSampleQualities: List<Float> = emptyList()
        private var animatedPreviousQualities: List<Float> = emptyList()
        private var hasComparisonSample = false
        private var barAnimator: ValueAnimator? = null
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SCANNER_BAR_TRACK
            style = Paint.Style.FILL
        }
        private val gnssPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SCANNER_GNSS_PANEL
            style = Paint.Style.FILL
        }
        private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SCANNER_GNSS_DIVIDER
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            pathEffect = DashPathEffect(floatArrayOf(dp(2).toFloat(), dp(4).toFloat()), 0f)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val previousPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1f
        }
        private val previousArrowPath = Path()
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = dp(13).toFloat()
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SCANNER_SOFT_TEXT
            textAlign = Paint.Align.CENTER
            textSize = dp(11).toFloat()
        }
        private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SCANNER_SOFT_TEXT
            textAlign = Paint.Align.CENTER
            textSize = dp(16).toFloat()
        }
        private val barBounds = RectF()
        private val barClipPath = Path()
        private val gnssPanelPath = Path()

        init {
            background = roundedBackground(SCANNER_PANEL, dp(8))
            contentDescription = getString(R.string.no_data)
        }

        fun setMetrics(metrics: List<MetricQuality>, animate: Boolean) {
            val previousMetrics = this.metrics
            val previousQualities = animatedQualities.ifEmpty { previousMetrics.map { it.quality } }
            showNoData = false
            this.metrics = metrics
            contentDescription = metrics.joinToString(separator = ", ") {
                "${it.label} ${it.valueText}"
            }

            barAnimator?.cancel()
            if (!animate || previousQualities.size != metrics.size) {
                animatedQualities = metrics.map { it.quality }
                previousSampleQualities = emptyList()
                animatedPreviousQualities = emptyList()
                hasComparisonSample = false
                invalidate()
                return
            }

            val targetQualities = metrics.map { it.quality }
            val previousMarkerStart = previousSampleQualities
            val previousMarkerEnd = previousQualities
            previousSampleQualities = previousQualities
            hasComparisonSample = previousMarkerStart.isNotEmpty() || sampleCount >= 2
            barAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    animatedQualities = previousQualities.zip(targetQualities) { start, end ->
                        start + (end - start) * progress
                    }
                    animatedPreviousQualities = if (!hasComparisonSample) {
                        emptyList()
                    } else if (previousMarkerStart.size == previousMarkerEnd.size) {
                        previousMarkerStart.zip(previousMarkerEnd) { start, end ->
                            start + (end - start) * progress
                        }
                    } else {
                        previousMarkerEnd
                    }
                    invalidate()
                }
                start()
            }
        }

        fun showNoData() {
            barAnimator?.cancel()
            showNoData = true
            metrics = emptyList()
            animatedQualities = emptyList()
            previousSampleQualities = emptyList()
            animatedPreviousQualities = emptyList()
            hasComparisonSample = false
            contentDescription = getString(R.string.no_data)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (showNoData) {
                val centerY = height / 2f - (emptyPaint.descent() + emptyPaint.ascent()) / 2f
                canvas.drawText(getString(R.string.no_data), width / 2f, centerY, emptyPaint)
                return
            }
            if (metrics.isEmpty()) return

            val top = dp(22).toFloat()
            val bottom = height - dp(70).toFloat()
            val availableHeight = bottom - top
            val segmentWidth = width.toFloat() / metrics.size
            val barWidth = minOf(dp(34).toFloat(), segmentWidth * 0.42f)
            val hasGnss = metrics.any { it.kind == MetricKind.Gnss }
            if (hasGnss) {
                val dividerX = segmentWidth * 3f
                drawRightRoundedPanel(canvas, dividerX)
                canvas.drawLine(dividerX, dp(14).toFloat(), dividerX, height - dp(14).toFloat(), dividerPaint)
            }

            metrics.forEachIndexed { index, metric ->
                val centerX = segmentWidth * index + segmentWidth / 2f
                val left = centerX - barWidth / 2f
                val right = centerX + barWidth / 2f

                barBounds.set(left, top, right, bottom)
                canvas.drawRoundRect(barBounds, dp(14).toFloat(), dp(14).toFloat(), trackPaint)

                val animatedQuality = animatedQualities.getOrElse(index) { metric.quality }
                val fillTop = bottom - availableHeight * animatedQuality
                fillPaint.color = qualityColor(animatedQuality)
                drawMaskedBarFill(canvas, left, top, right, bottom, fillTop)

                animatedPreviousQualities.getOrNull(index)?.let { previousQuality ->
                    val markerY = bottom - availableHeight * previousQuality.coerceIn(0f, 1f)
                    canvas.drawLine(left - dp(3), markerY, right + dp(3), markerY, previousPaint)
                    drawPreviousMarkerArrow(
                        canvas = canvas,
                        x = right + dp(8),
                        y = markerY,
                        previousQuality = previousQuality,
                        currentQuality = metric.quality,
                    )
                }

                canvas.drawText(metric.label, centerX, height - dp(40).toFloat(), labelPaint)
                canvas.drawText(metric.valueText, centerX, height - dp(18).toFloat(), valuePaint)
            }
        }

        private fun drawRightRoundedPanel(canvas: Canvas, left: Float) {
            val radius = dp(8).toFloat()
            val right = width.toFloat()
            val bottom = height.toFloat()

            gnssPanelPath.reset()
            gnssPanelPath.moveTo(left, 0f)
            gnssPanelPath.lineTo(right - radius, 0f)
            gnssPanelPath.quadTo(right, 0f, right, radius)
            gnssPanelPath.lineTo(right, bottom - radius)
            gnssPanelPath.quadTo(right, bottom, right - radius, bottom)
            gnssPanelPath.lineTo(left, bottom)
            gnssPanelPath.close()
            canvas.drawPath(gnssPanelPath, gnssPanelPaint)
        }

        private fun drawMaskedBarFill(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            fillTop: Float,
        ) {
            val checkpoint = canvas.save()
            barBounds.set(left, top, right, bottom)
            barClipPath.reset()
            barClipPath.addRoundRect(barBounds, dp(14).toFloat(), dp(14).toFloat(), Path.Direction.CW)
            canvas.clipPath(barClipPath)
            canvas.clipRect(left, fillTop, right, bottom)
            canvas.drawRect(left, fillTop, right, bottom, fillPaint)
            canvas.restoreToCount(checkpoint)
        }

        private fun drawPreviousMarkerArrow(
            canvas: Canvas,
            x: Float,
            y: Float,
            previousQuality: Float,
            currentQuality: Float,
        ) {
            val delta = currentQuality - previousQuality
            if (kotlin.math.abs(delta) < 0.03f) return

            val size = dp(4).toFloat()
            previousArrowPath.reset()
            if (delta > 0f) {
                previousArrowPath.moveTo(x, y - size)
                previousArrowPath.lineTo(x - size, y + size)
                previousArrowPath.lineTo(x + size, y + size)
            } else {
                previousArrowPath.moveTo(x, y + size)
                previousArrowPath.lineTo(x - size, y - size)
                previousArrowPath.lineTo(x + size, y - size)
            }
            previousArrowPath.close()
            canvas.drawPath(previousArrowPath, previousPaint)
        }
    }

    private inner class SignalQualityIconView : View(this@MainActivity) {
        private var displayedQuality = 0f
        private var qualityAnimator: ValueAnimator? = null
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val barBounds = RectF()

        fun setQuality(quality: Float, animate: Boolean) {
            val target = quality.coerceIn(0f, 1f)
            qualityAnimator?.cancel()
            if (!animate) {
                displayedQuality = target
                invalidate()
                return
            }

            val start = displayedQuality
            qualityAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    displayedQuality = start + (target - start) * progress
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val gap = width * 0.08f
            val barWidth = (width - gap * 5f) / 4f
            val maxHeight = height * 0.82f
            val bottom = signalBaseline()
            val litBars = kotlin.math.ceil(displayedQuality * 4f).toInt().coerceIn(0, 4)

            repeat(4) { index ->
                val left = gap + index * (barWidth + gap)
                val fullBarHeight = maxHeight * (0.35f + index * 0.18f)
                val top = bottom - fullBarHeight
                val isLit = index < litBars

                barPaint.color = Color.argb(58, 255, 255, 255)
                barBounds.set(left, top, left + barWidth, bottom)
                canvas.drawRoundRect(barBounds, dp(3).toFloat(), dp(3).toFloat(), barPaint)

                if (isLit) {
                    barPaint.color = SCANNER_TITLE_SIGNAL
                    barBounds.set(left, top, left + barWidth, bottom)
                    canvas.drawRoundRect(barBounds, dp(3).toFloat(), dp(3).toFloat(), barPaint)
                }
            }
        }

        private fun signalBaseline(): Float =
            height * 0.84f
    }

    private data class MetricQuality(
        val label: String,
        val valueText: String,
        val quality: Float,
        val kind: MetricKind,
    )

    private enum class MetricKind {
        Radio,
        Gnss,
    }

    private data class MockTelemetry(
        val rsrp: Int,
        val rsrq: Int,
        val sinr: Int,
        val hdop: Float,
        val fixAgeSeconds: Int,
        val gnssMode: GnssMode,
    ) {
        fun metrics(): List<MetricQuality> = listOf(
            MetricQuality("RSRP", "$rsrp dBm", qualityFromRange(rsrp.toFloat(), -118f, -82f), MetricKind.Radio),
            MetricQuality("RSRQ", "$rsrq dB", qualityFromRange(rsrq.toFloat(), -20f, -8f), MetricKind.Radio),
            MetricQuality("SINR", "$sinr dB", qualityFromRange(sinr.toFloat(), 0f, 24f), MetricKind.Radio),
            MetricQuality("GNSS", "HDOP ${formatHdop(hdop)} / ${fixAgeSeconds}s", gnssQuality(hdop, fixAgeSeconds), MetricKind.Gnss),
        )

        fun overallQuality(): Float =
            radioQuality().average().toFloat().coerceIn(0f, 1f)

        private fun radioQuality(): List<Float> = listOf(
            qualityFromRange(rsrp.toFloat(), -118f, -82f),
            qualityFromRange(rsrq.toFloat(), -20f, -8f),
            qualityFromRange(sinr.toFloat(), 0f, 24f),
        )

        companion object {
            fun initial(gnssMode: GnssMode): MockTelemetry =
                MockTelemetry(-98, -13, 12, if (gnssMode == GnssMode.HighAccuracy) 0.8f else 1.4f, 9, gnssMode)

            fun fromSample(sample: Int, gnssMode: GnssMode): MockTelemetry {
                val wave = sample % 6
                val rsrpValues = intArrayOf(-113, -100, -88, -96, -109, -84)
                val rsrqValues = intArrayOf(-19, -15, -9, -12, -18, -8)
                val sinrValues = intArrayOf(3, 10, 22, 15, 6, 24)
                val baseHdop = if (gnssMode == GnssMode.HighAccuracy) 0.7f else 1.3f
                val hdopOffsets = floatArrayOf(2.2f, 1.1f, 0.1f, 0.8f, 2.7f, 0.0f)
                val ageValues = if (gnssMode == GnssMode.HighAccuracy) {
                    intArrayOf(14, 8, 2, 5, 18, 3)
                } else {
                    intArrayOf(24, 15, 5, 10, 30, 6)
                }
                return MockTelemetry(
                    rsrpValues[wave],
                    rsrqValues[wave],
                    sinrValues[wave],
                    baseHdop + hdopOffsets[wave],
                    ageValues[wave],
                    gnssMode,
                )
            }

            private fun qualityFromRange(value: Float, poor: Float, excellent: Float): Float =
                ((value - poor) / (excellent - poor)).coerceIn(0f, 1f)

            private fun gnssQuality(hdop: Float, ageSeconds: Int): Float {
                val precision = (1f - ((hdop - 0.7f) / 3.3f)).coerceIn(0f, 1f)
                val freshness = (1f - ((ageSeconds - 2f) / 28f)).coerceIn(0f, 1f)
                return (precision * 0.7f + freshness * 0.3f).coerceIn(0f, 1f)
            }

            private fun formatHdop(hdop: Float): String =
                String.format("%.1f", hdop)
        }
    }

    private fun qualityColor(quality: Float): Int {
        val clamped = quality.coerceIn(0f, 1f)
        val start: Int
        val end: Int
        val fraction: Float
        if (clamped < 0.5f) {
            start = SCANNER_QUALITY_LOW
            end = SCANNER_QUALITY_MEDIUM
            fraction = clamped / 0.5f
        } else {
            start = SCANNER_QUALITY_MEDIUM
            end = SCANNER_QUALITY_HIGH
            fraction = (clamped - 0.5f) / 0.5f
        }
        return blendColor(start, end, fraction)
    }

    private fun blendColor(start: Int, end: Int, fraction: Float): Int {
        val inverse = 1f - fraction
        return Color.rgb(
            (Color.red(start) * inverse + Color.red(end) * fraction).toInt(),
            (Color.green(start) * inverse + Color.green(end) * fraction).toInt(),
            (Color.blue(start) * inverse + Color.blue(end) * fraction).toInt(),
        )
    }

    private fun Random.nextFloatIn(start: Float, end: Float): Float =
        start + nextFloat() * (end - start)

    private enum class SamplingFrequency(val label: String, val intervalMs: Long) {
        LowImpact("Low impact", 60_000),
        Balanced("Balanced", 15_000),
        HighDetail("High detail", 5_000),
        Debug("Debug", 1_000);

        val summary: String
            get() = when (this) {
                LowImpact -> "Every 60 seconds"
                Balanced -> "Every 15 seconds"
                HighDetail -> "Every 5 seconds"
                Debug -> "Every second"
            }

        companion object {
            fun fromName(value: String?): SamplingFrequency =
                entries.firstOrNull { it.name == value } ?: Balanced
        }

    }

    private enum class GnssMode(val label: String, val summary: String) {
        Balanced("Balanced", "Use location with moderate power impact"),
        HighAccuracy("High accuracy", "Prefer the most precise available location");

        companion object {
            fun fromName(value: String?): GnssMode =
                entries.firstOrNull { it.name == value } ?: Balanced
        }
    }

    private enum class ReportingMode(val label: String, val summary: String) {
        Hourly("Hourly", "Report saved coverage logs about once per hour"),
        Daily("Daily", "Report saved coverage logs about once per day"),
        Continuous("Continuous", "For live field testing. Uses more battery and network"),
        Manual("Manual", "Keep logs on this device until you send or export them");

        companion object {
            fun fromName(value: String?): ReportingMode =
                entries.firstOrNull { it.name == value } ?: Hourly
        }
    }

    private enum class SettingsDestination {
        Main,
        LogList,
        LogFile,
    }

    private companion object {
        const val TAG = "AskScanner"
        const val CONSENT_TRANSITION_DELAY_MS = 250L
        const val KEY_FREQUENCY = "frequency"
        const val KEY_GNSS_MODE = "gnssMode"
        val SCANNER_BACKGROUND: Int = Color.rgb(15, 118, 110)
        val SCANNER_PANEL: Int = Color.argb(43, 255, 255, 255)
        val SCANNER_SOFT_TEXT: Int = Color.argb(204, 255, 255, 255)
        val SCANNER_BUTTON_RIPPLE: Int = Color.argb(31, 15, 118, 110)
        val SCANNER_RING: Int = Color.argb(178, 255, 255, 255)
        val SCANNER_BAR_TRACK: Int = Color.argb(38, 255, 255, 255)
        val SCANNER_GNSS_PANEL: Int = Color.argb(34, 0, 0, 0)
        val SCANNER_GNSS_DIVIDER: Int = Color.argb(138, 255, 255, 255)
        val SCANNER_QUALITY_LOW: Int = Color.rgb(248, 113, 113)
        val SCANNER_QUALITY_MEDIUM: Int = Color.rgb(250, 204, 21)
        val SCANNER_QUALITY_HIGH: Int = Color.rgb(134, 239, 172)
        val SCANNER_TITLE_SIGNAL: Int = Color.rgb(134, 239, 172)
        val SETTINGS_BACKGROUND: Int = Color.rgb(246, 247, 249)
        val SETTINGS_TEXT: Int = Color.rgb(31, 41, 55)
        val SETTINGS_SUBTLE_TEXT: Int = Color.rgb(91, 103, 119)
        val SETTINGS_ACCENT: Int = Color.rgb(15, 118, 110)
        val SETTINGS_DESTRUCTIVE: Int = Color.rgb(185, 28, 28)
        val SETTINGS_CHEVRON: Int = Color.rgb(148, 163, 184)
        val SETTINGS_DIVIDER: Int = Color.rgb(229, 232, 237)
        val SETTINGS_RIPPLE: Int = Color.argb(26, 15, 118, 110)
    }
}
