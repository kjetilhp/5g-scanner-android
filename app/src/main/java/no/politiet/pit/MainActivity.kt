package no.politiet.pit

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.animation.AnimatorSet
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.location.LocationManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.DashPathEffect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import no.politiet.pit.domain.Cell
import no.politiet.pit.domain.GnssMode
import no.politiet.pit.domain.ReportingMode
import no.politiet.pit.reporting.ReportingScheduler
import no.politiet.pit.storage.AppSettingsStore
import no.politiet.pit.storage.CoverageDataStore
import no.politiet.pit.telemetry.MetricKind
import no.politiet.pit.telemetry.MetricQuality
import no.politiet.pit.telemetry.ScannerTelemetrySnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    private var consentGranted = false
    private var waitingForInitialPermissions = false
    private var stoppedManually = false
    private var showingSettings = false
    private var settingsDestination = SettingsDestination.Main
    private var sampleCount = 0
    private var gnssMode = GnssMode.Balanced
    private var reportingMode = ReportingMode.Hourly
    private var mockTelemetryEnabled = true
    private var lastReportedAt: Instant? = null
    private val mobileOperatorLookup: MobileOperatorLookup by lazy {
        MobileOperatorLookup.fromRawResource(this, R.raw.mobile_operators)
    }

    private var telemetryBars: TelemetryBarsView? = null
    private var telemetryHelpButton: ImageButton? = null
    private var servingCellText: TextView? = null
    private var titleSignalIcon: SignalQualityIconView? = null
    private var rfSignalBackground: RfSignalBackgroundView? = null
    private var stopStartButton: ImageButton? = null
    private var startButtonPulseAnimator: AnimatorSet? = null
    private var startButtonPulseScheduled = false
    private var errorPulseScheduled = false
    private var scannerActivityRing: View? = null
    private var scannerActivityRingAnimator: ObjectAnimator? = null
    private var settingsBackCallback: OnBackInvokedCallback? = null
    private var latestTelemetry = ScannerTelemetrySnapshot.initial(gnssMode)
    private var gnssAgeTickScheduled = false
    private var settingsRefreshScheduled = false
    private var scannerStateReceiverRegistered = false
    private lateinit var coverageDataStore: CoverageDataStore
    private lateinit var settingsStore: AppSettingsStore

    private val gnssAgeTick = object : Runnable {
        override fun run() {
            gnssAgeTickScheduled = false
            updateGnssAgeDisplay()
            scheduleGnssAgeTick()
        }
    }

    private val startButtonPulse = object : Runnable {
        override fun run() {
            startButtonPulseScheduled = false
            val button = stopStartButton
            if (button == null || !isStopped() || showingSettings) {
                stopStartButtonPulse()
                return
            }

            startButtonPulseAnimator?.cancel()
            button.pivotX = button.width / 2f
            button.pivotY = button.height / 2f
            startButtonPulseAnimator = AnimatorSet().apply {
                duration = 860L
                interpolator = DecelerateInterpolator()
                playTogether(
                    ObjectAnimator.ofFloat(button, View.SCALE_X, 1f, 1.055f, 1f),
                    ObjectAnimator.ofFloat(button, View.SCALE_Y, 1f, 1.055f, 1f),
                    ObjectAnimator.ofFloat(button, "elevation", dp(8).toFloat(), dp(14).toFloat(), dp(8).toFloat()),
                )
                start()
            }
            scheduleStartButtonPulse()
        }
    }

    private val errorPulse = object : Runnable {
        override fun run() {
            errorPulseScheduled = false
            val panel = telemetryBars
            val hasActionableError = (scannerAvailability() as? ScannerAvailability.Error)
                ?.reason
                ?.settingsIntent() != null
            if (!hasActionableError || showingSettings || panel == null) {
                stopErrorPulse()
                return
            }

            panel.pulseErrorIcon()
            scheduleErrorPulse()
        }
    }

    private val settingsRefresh = object : Runnable {
        override fun run() {
            settingsRefreshScheduled = false
            if (showingSettings) {
                render()
            }
        }
    }

    private val scannerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScannerService.ACTION_STATE_CHANGED) return
            syncScannerStateFromService(animateBars = true)
            if (!showingSettings) {
                updateScannerUi()
                scheduleSettingsRefresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coverageDataStore = CoverageDataStore(this)
        settingsStore = AppSettingsStore(this)
        loadState()
        syncScannerStateFromService(animateBars = false)
        Log.d(TAG, "Activity created: consentGranted=$consentGranted, scannerStopped=$stoppedManually, gnssMode=${gnssMode.name}, reportingMode=${reportingMode.name}")
        logAppliedSettingsOnLaunch()
        ReportingScheduler.appPreferences(this).registerOnSharedPreferenceChangeListener(this)
        saveState()
        ReportingScheduler.scheduleOnLaunch(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        lastReportedAt = ReportingScheduler.lastReportedAt(this)
        if (waitingForInitialPermissions && requiredScannerPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            waitingForInitialPermissions = false
        }
        if (waitingForInitialPermissions) return
        registerScannerStateReceiver()
        if (showingSettings) {
            render()
        } else {
            ensureSamplerState()
            updateScannerUi()
        }
    }

    override fun onPause() {
        unregisterScannerStateReceiver()
        super.onPause()
    }

    override fun onDestroy() {
        updateSettingsBackCallback(enabled = false)
        unregisterScannerStateReceiver()
        ReportingScheduler.appPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacks(gnssAgeTick)
        handler.removeCallbacks(startButtonPulse)
        handler.removeCallbacks(errorPulse)
        handler.removeCallbacks(settingsRefresh)
        gnssAgeTickScheduled = false
        startButtonPulseScheduled = false
        errorPulseScheduled = false
        settingsRefreshScheduled = false
        startButtonPulseAnimator?.cancel()
        scannerActivityRingAnimator?.cancel()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (key != ReportingScheduler.KEY_LAST_REPORTED_AT) return
        handler.post {
            refreshLastReportedAt()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SCANNER_PERMISSION_REQUEST_CODE) return
        waitingForInitialPermissions = false
        Log.d(
            TAG,
            "Scanner permissions result: ${permissions.zip(grantResults.asIterable()).joinToString { (permission, result) -> "$permission=${result == PackageManager.PERMISSION_GRANTED}" }}",
        )
        registerScannerStateReceiver()
        ensureSamplerState()
        if (consentGranted) {
            render()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!handleBackNavigation()) {
            super.onBackPressed()
        }
    }

    private fun handleBackNavigation(): Boolean {
        return when {
            showingSettings && settingsDestination == SettingsDestination.CoverageData -> {
                Log.d(TAG, "Back pressed: recorded data -> settings")
                settingsDestination = SettingsDestination.Main
                render(ScreenTransition.Back)
                true
            }
            showingSettings && settingsDestination == SettingsDestination.About -> {
                Log.d(TAG, "Back pressed: about -> settings")
                settingsDestination = SettingsDestination.Main
                render(ScreenTransition.Back)
                true
            }
            showingSettings -> {
                Log.d(TAG, "Back pressed: settings -> scanner")
                showingSettings = false
                settingsDestination = SettingsDestination.Main
                render(ScreenTransition.Back)
                true
            }
            else -> false
        }
    }

    private fun updateSettingsBackCallback(enabled: Boolean = showingSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val callback = settingsBackCallback
        if (enabled && callback == null) {
            val newCallback = OnBackInvokedCallback { handleBackNavigation() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                newCallback,
            )
            settingsBackCallback = newCallback
        } else if (!enabled && callback != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
            settingsBackCallback = null
        }
    }

    private fun render(transition: ScreenTransition = ScreenTransition.None) {
        updateSettingsBackCallback()
        val nextView = when {
            !consentGranted -> createConsentView()
            showingSettings -> when (settingsDestination) {
                SettingsDestination.Main -> createSettingsView()
                SettingsDestination.CoverageData -> createCoverageDataView()
                SettingsDestination.About -> createAboutView()
            }
            else -> createScannerView()
        }
        setContentView(nextView)
        animateScreenTransition(nextView, transition)
        if (consentGranted) {
            ensureSamplerState()
            updateScannerUi()
        }
    }

    private fun animateScreenTransition(view: View, transition: ScreenTransition) {
        if (transition == ScreenTransition.None) return

        val direction = when (transition) {
            ScreenTransition.Forward -> 1f
            ScreenTransition.Back -> -1f
            ScreenTransition.None -> 0f
        }
        view.alpha = 0f
        view.translationX = resources.displayMetrics.widthPixels * 0.12f * direction
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .start()
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
                Log.d(TAG, "Consent granted")
                saveState()
                ensureReportingScheduler()
                if (!requestRequiredScannerPermissions()) {
                    handler.postDelayed({ render() }, CONSENT_TRANSITION_DELAY_MS)
                }
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

        telemetryBars = TelemetryBarsView().apply {
            setLiveMetricProvider { latestTelemetry.metrics() }
            setMetrics(latestTelemetry.metrics(), animate = false)
        }
        telemetryHelpButton = measurementHelpButton()
        val telemetryPanel = FrameLayout(this).apply {
            addView(telemetryBars, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(244),
            ).apply {
                topMargin = 0
            })
            addView(telemetryHelpButton, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = 0
            })
        }
        content.addView(telemetryPanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(292),
        ).apply {
            topMargin = dp(58)
        })
        servingCellText = servingCellSummaryText().apply {
            text = servingCellSummary(latestTelemetry.radio.servingCell)
        }
        content.addView(servingCellText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(8)
        })

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        rfSignalBackground = RfSignalBackgroundView()
        scrollView.setOnTouchListener { _, event ->
            rfSignalBackground?.handleScannerTouch(event)
            false
        }

        stopStartButton = scannerIconButton(R.drawable.ic_stop_32, getString(R.string.stop_scanning), dp(56)) {
            handleStopStartAction()
        }

        scannerActivityRing = ScannerActivityRing()

        return FrameLayout(this).apply {
            setBackgroundColor(SCANNER_BACKGROUND)
            addView(rfSignalBackground, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(scrollView)
            setOnTouchListener { _, event ->
                rfSignalBackground?.handleScannerTouch(event)
                false
            }
            val controlBottomMargin = navigationBarHeight() + dp(160)
            addView(scannerActivityRing, FrameLayout.LayoutParams(dp(144), dp(144), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = controlBottomMargin - dp(16)
            })
            addView(stopStartButton, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = controlBottomMargin
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
        reportingRows.add(settingsPreferenceRow(
            title = getString(R.string.setting_recorded_coverage_data_title),
            summary = coverageDataSummary(),
            value = "",
        ) {
            Log.d(TAG, "Opening recorded coverage data")
            settingsDestination = SettingsDestination.CoverageData
            render(ScreenTransition.Forward)
        })
        if (reportingMode != ReportingMode.Continuous) {
            reportingRows.add(settingsActionRow(
                title = getString(R.string.setting_send_now_title),
                summary = getString(R.string.setting_send_now_summary),
                titleColor = SETTINGS_ACCENT,
            ) {
                sendNow()
            })
        }
        content.addView(settingsSectionLabel(getString(R.string.settings_section_reporting)))
        content.addView(settingsGroup(*reportingRows.toTypedArray()))

        content.addView(settingsSectionLabel(getString(R.string.settings_section_about)))
        content.addView(settingsGroup(
            settingsActionRow(
                title = getString(R.string.setting_about_title),
                summary = getString(R.string.setting_about_summary),
            ) {
                Log.d(TAG, "Opening About")
                settingsDestination = SettingsDestination.About
                render(ScreenTransition.Forward)
            },
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

    private fun createCoverageDataView(): View {
        clearScannerViews()
        val stats = coverageDataStore.stats()
        val recentSamples = if (stats.sampleCount > 0) {
            coverageDataStore.recentInspectionSamples(INSPECTOR_SAMPLE_LIMIT)
        } else {
            emptyList()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        content.addView(settingsSectionLabel(getString(R.string.settings_section_coverage_data)))
        content.addView(settingsGroup(
            settingsInfoRow(
                title = if (stats.sampleCount == 0) {
                    getString(R.string.coverage_data_empty_title)
                } else {
                    getString(R.string.coverage_data_inspector_title)
                },
                summary = if (stats.sampleCount == 0) {
                    getString(R.string.coverage_data_empty_summary)
                } else {
                    getString(
                        R.string.coverage_data_inspector_stats,
                        stats.sampleCount,
                        stats.dayCount,
                        formatBytes(stats.estimatedBytes),
                    )
                },
            ),
            settingsInfoRow(
                title = getString(R.string.coverage_data_inspector_title),
                summary = getString(R.string.coverage_data_inspector_summary),
            ),
        ))
        if (stats.sampleCount > 0) {
            content.addView(settingsSectionLabel(getString(R.string.coverage_data_actions_title)))
            content.addView(settingsGroup(
                settingsActionRow(
                    title = getString(R.string.coverage_export_csv_title),
                    summary = getString(R.string.coverage_export_csv_summary),
                    titleColor = SETTINGS_ACCENT,
                ) {
                    showExportCsvActions()
                },
                settingsDestructiveActionRow(
                    title = getString(R.string.delete_all_coverage_data_title),
                    summary = getString(R.string.delete_all_coverage_data_summary),
                ) {
                    confirmDeleteAllSamples()
                },
            ))
        }
        if (recentSamples.isNotEmpty()) {
            content.addView(settingsSectionLabel(getString(R.string.coverage_data_recent_title)))
            content.addView(settingsGroup(*recentSamples.map { sample ->
                inspectionSampleRow(sample)
            }.toTypedArray()))
        }

        return settingsScreen(
            title = getString(R.string.setting_recorded_coverage_data_title),
            backDescription = getString(R.string.back_to_settings),
            onBack = {
                settingsDestination = SettingsDestination.Main
                render(ScreenTransition.Back)
            },
            body = content,
        )
    }

    private fun confirmDeleteAllSamples() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_all_coverage_data_title))
            .setMessage(getString(R.string.delete_all_coverage_data_confirm_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.delete_all_coverage_data_confirm_action)) { _, _ ->
                val deletedSamples = coverageDataStore.deleteAllSamples()
                Log.d(TAG, "Deleted local coverage samples: samples=$deletedSamples")
                render()
            }
            .show()
    }

    private fun inspectionSampleTitle(sample: CoverageDataStore.InspectionSample): String =
        buildString {
            append(dateTimeFormatter.format(Instant.ofEpochMilli(sample.capturedAtEpochMillis)))
            append(" - ")
            append(sample.rat.ifBlank { sample.kind })
            sample.band.takeIf { it.isNotBlank() }?.let { band ->
                append(" - Band ")
                append(band)
            }
            if (sample.mockTelemetry) append(" - MOCK")
        }

    private fun inspectionSampleRow(sample: CoverageDataStore.InspectionSample): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(86)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rippleBackground(Color.WHITE, dp(16))
            isFocusable = true
            setOnClickListener { showInspectionSampleDetails(sample) }

            addView(TextView(this@MainActivity).apply {
                text = inspectionSampleTitle(sample)
                textSize = 15f
                setTextColor(SETTINGS_TEXT)
                includeFontPadding = false
                maxLines = 1
            })
            addView(InspectionSampleBarsView(sample.inspectionMetricBars()), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42),
            ).apply {
                topMargin = dp(10)
            })
        }

    private fun CoverageDataStore.InspectionSample.inspectionMetricBars(): List<InspectionMetricBar> =
        listOf(
            InspectionMetricBar(
                label = "RSRP",
                valueText = rsrp.ifBlank { "-" },
                quality = rsrp.toFloatOrNull()?.let(::rsrpQuality) ?: 0f,
                hasValue = rsrp.isNotBlank(),
            ),
            InspectionMetricBar(
                label = "SINR",
                valueText = sinr.ifBlank { "-" },
                quality = sinr.toFloatOrNull()?.let(::sinrQuality) ?: 0f,
                hasValue = sinr.isNotBlank(),
            ),
            InspectionMetricBar(
                label = "LOC",
                valueText = if (lat.isNotBlank() && lon.isNotBlank()) "OK" else "-",
                quality = if (lat.isNotBlank() && lon.isNotBlank()) 0.78f else 0f,
                hasValue = lat.isNotBlank() && lon.isNotBlank(),
            ),
        )

    private fun rsrpQuality(value: Float): Float =
        ((value + 120f) / 40f).coerceIn(0f, 1f)

    private fun sinrQuality(value: Float): Float =
        ((value + 5f) / 30f).coerceIn(0f, 1f)

    private fun showInspectionSampleDetails(sample: CoverageDataStore.InspectionSample) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.coverage_sample_detail_title))
            .setMessage(inspectionSampleDetailText(sample))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun inspectionSampleDetailText(sample: CoverageDataStore.InspectionSample): String =
        buildString {
            appendLine(getString(R.string.coverage_sample_detail_summary, sample.id))
            appendLine("Captured: ${dateTimeFormatter.format(Instant.ofEpochMilli(sample.capturedAtEpochMillis))}")
            appendLine("Kind: ${sample.kind}")
            appendLine("RAT: ${sample.rat.ifBlank { "unknown" }}")
            appendLine("MCC/MNC: ${sample.mcc.ifBlank { "?" }}/${sample.mnc.ifBlank { "?" }}")
            appendLine("Cell ID: ${sample.cellId.ifBlank { "unknown" }}")
            appendLine("Band: ${sample.band.ifBlank { "unknown" }}")
            appendLine("RSRP: ${sample.rsrp.ifBlank { "unknown" }}")
            appendLine("SINR: ${sample.sinr.ifBlank { "unknown" }}")
            appendLine("Location: ${if (sample.lat.isNotBlank() && sample.lon.isNotBlank()) "${sample.lat}, ${sample.lon}" else "unknown"}")
            appendLine("Mock telemetry: ${if (sample.mockTelemetry) "yes" else "no"}")
            appendLine("Upload status: ${sample.uploadStatus}")
            appendLine()
            appendLine(sample.sampleJson)
        }

    private fun showExportCsvActions() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.coverage_export_csv_title))
            .setItems(
                arrayOf(
                    getString(R.string.coverage_export_recent_action),
                    getString(R.string.coverage_export_all_action),
                ),
            ) { _, which ->
                when (which) {
                    0 -> shareCoverageCsv(coverageDataStore.exportRecentCsv(RECENT_EXPORT_SAMPLE_LIMIT))
                    1 -> shareCoverageCsv(coverageDataStore.exportAllCsv())
                }
            }
            .show()
    }

    private fun shareCoverageCsv(csvFile: java.io.File) {
        val uri = coverageDataStore.exportUri(csvFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, csvFile.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Log.d(TAG, "Sharing coverage CSV: ${csvFile.name}, uri=$uri")
        startActivity(Intent.createChooser(intent, getString(R.string.coverage_export_csv_title)))
    }

    private fun createAboutView(): View {
        clearScannerViews()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        content.addView(aboutSection(
            title = getString(R.string.about_intro_title),
            body = getString(R.string.about_intro_body),
        ))
        content.addView(aboutSection(
            title = getString(R.string.about_data_title),
            body = getString(R.string.about_data_body),
        ))
        content.addView(aboutSection(
            title = getString(R.string.about_privacy_title),
            body = getString(R.string.about_privacy_body),
        ))
        content.addView(aboutSection(
            title = getString(R.string.about_storage_title),
            body = getString(R.string.about_storage_body),
        ))
        content.addView(aboutSection(
            title = getString(R.string.about_participation_title),
            body = getString(R.string.about_participation_body),
        ))
        content.addView(aboutSection(
            title = getString(R.string.about_app_title),
            body = getString(
                R.string.about_app_body,
                packageInfoVersionName(),
                packageName,
            ),
        ))
        content.addView(settingsSectionLabel(getString(R.string.settings_section_developer)))
        content.addView(settingsGroup(
            settingsToggleRow(
                title = getString(R.string.setting_mock_telemetry_title),
                summary = if (DeviceProfile.isLikelyEmulator()) {
                    getString(R.string.setting_mock_telemetry_emulator_summary)
                } else {
                    getString(R.string.setting_mock_telemetry_summary)
                },
                isChecked = mockTelemetryEnabled || DeviceProfile.isLikelyEmulator(),
                isEnabled = !DeviceProfile.isLikelyEmulator(),
            ) { enabled ->
                mockTelemetryEnabled = enabled
                Log.d(TAG, "Mock telemetry changed: enabled=$mockTelemetryEnabled")
                saveState()
                ensureSamplerState()
                render()
            },
        ))

        return settingsScreen(
            title = getString(R.string.setting_about_title),
            backDescription = getString(R.string.back_to_settings),
            onBack = {
                settingsDestination = SettingsDestination.Main
                render(ScreenTransition.Back)
            },
            body = content,
        )
    }

    private fun aboutSection(title: String, body: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(settingsSectionLabel(title))
            addView(settingsGroup(settingsParagraphRow(body)))
        }

    private fun packageInfoVersionName(): String =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        }.getOrNull().orEmpty().ifBlank { "unknown" }

    private fun clearScannerViews() {
        telemetryBars = null
        servingCellText = null
        titleSignalIcon = null
        telemetryHelpButton = null
        rfSignalBackground?.setScanning(false)
        rfSignalBackground = null
        stopStartButtonPulse()
        stopStartButton = null
        stopErrorPulse()
        scannerActivityRingAnimator?.cancel()
        scannerActivityRingAnimator = null
        scannerActivityRing = null
        stopGnssAgeTick()
    }

    private fun stopScanning() {
        stoppedManually = true
        Log.d(TAG, "Scanner stopped manually")
        saveState()
        ScannerService.stop(this)
        updateScannerUi()
    }

    private fun startScanning() {
        stoppedManually = false
        Log.d(TAG, "Scanner started manually")
        saveState()
        ensureSamplerState()
        updateScannerUi()
    }

    private fun canSample(): Boolean {
        return scannerDesired() &&
            scannerAvailability() is ScannerAvailability.Available &&
            ScannerService.currentState.errorReason == null
    }

    private fun scannerDesired(): Boolean = consentGranted && !stoppedManually

    private fun ensureSamplerState() {
        if (!scannerDesired()) {
            ScannerService.stop(this)
            stopGnssAgeTick()
            Log.d(TAG, "Scanner service disabled: consentGranted=$consentGranted, scannerStopped=$stoppedManually")
            return
        }
        if (canStartForegroundScannerService() && !ScannerService.currentState.isRunning) {
            ScannerService.start(this)
        }
        scheduleGnssAgeTick()
    }

    private fun canStartForegroundScannerService(): Boolean {
        val hasLocationPermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun scheduleSettingsRefresh() {
        if (!showingSettings || settingsRefreshScheduled) return
        settingsRefreshScheduled = true
        handler.post(settingsRefresh)
    }

    private fun ensureReportingScheduler() {
        Log.d(TAG, "Ensuring reporting scheduler: consentGranted=$consentGranted, reportingMode=${reportingMode.name}")
        ReportingScheduler.schedule(this, consentGranted, reportingMode.name)
    }

    private fun triggerReporting(onComplete: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "Triggering reporting: reportingMode=${reportingMode.name}")
        ReportingScheduler.recordTrigger(this, reportingMode.name) { success ->
            Log.d(TAG, "Reporting trigger completed: success=$success")
            if (success) {
                refreshLastReportedAt()
            }
            onComplete?.invoke(success)
        }
    }

    private fun refreshLastReportedAt() {
        lastReportedAt = ReportingScheduler.lastReportedAt(this)
        scheduleSettingsRefresh()
    }

    private fun updateScannerUi() {
        val availability = scannerAvailability()
        val error = availability as? ScannerAvailability.Error
        val scannerDesired = scannerDesired()
        val serviceState = ScannerService.currentState
        val errorMessage = if (scannerDesired) serviceState.errorReason ?: error?.reason?.message else null
        val errorSettingsIntent = if (scannerDesired) error?.reason?.settingsIntent() else null
        val canSample = scannerDesired && errorMessage == null
        titleSignalIcon?.setQuality(
            quality = if (canSample) latestTelemetry.overallQuality() else 0f,
            animate = sampleCount > 0,
        )
        rfSignalBackground?.setScanning(canSample, latestTelemetry.overallQuality())
        if (!canSample) {
            telemetryBars?.showNoData(
                message = errorMessage ?: getString(R.string.no_data),
                isError = errorMessage != null,
            )
        }
        telemetryBars?.apply {
            isClickable = errorSettingsIntent != null
            isFocusable = errorSettingsIntent != null
            setOnClickListener(errorSettingsIntent?.let { intent ->
                View.OnClickListener { openScannerErrorSettings(intent) }
            })
        }
        servingCellText?.apply {
            text = if (canSample) servingCellSummary(latestTelemetry.radio.servingCell) else getString(R.string.serving_cell_unavailable)
            alpha = if (canSample) 1f else 0.56f
        }
        telemetryHelpButton?.visibility = if (canSample) View.VISIBLE else View.GONE
        stopStartButton?.apply {
            isEnabled = true
            alpha = 1f
            if (isStopped()) {
                setImageResource(R.drawable.ic_play_arrow_32)
                contentDescription = getString(R.string.start_scanning)
                setOnClickListener { handleStopStartAction() }
            } else {
                setImageResource(R.drawable.ic_stop_32)
                contentDescription = getString(R.string.stop_scanning)
                setOnClickListener { handleStopStartAction() }
            }
        }
        updateStartButtonPulse()
        updateErrorPulse(scannerDesired && errorSettingsIntent != null)
        updateScannerActivityRing(canSample)
        if (canSample) {
            scheduleGnssAgeTick()
        } else {
            stopGnssAgeTick()
        }
    }

    private fun handleStopStartAction() {
        if (!isStopped()) {
            stopScanning()
        } else {
            startScanning()
        }
    }

    private fun openScannerErrorSettings(intent: Intent) {
        runCatching {
            startActivity(intent)
        }.onFailure { error ->
            Log.e(TAG, "Could not open scanner error settings", error)
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun ScannerErrorReason.settingsIntent(): Intent? =
        when (this) {
            is ScannerErrorReason.MissingLocationPermission,
            is ScannerErrorReason.MissingNotificationPermission -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            is ScannerErrorReason.LocationDisabled -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            is ScannerErrorReason.AirplaneModeEnabled -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            is ScannerErrorReason.TelephonyUnavailable -> null
        }

    private fun scannerAvailability(): ScannerAvailability {
        val locationPermissionGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!locationPermissionGranted) {
            return ScannerAvailability.Error(ScannerErrorReason.MissingLocationPermission(getString(R.string.scanner_error_missing_location_permission)))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return ScannerAvailability.Error(ScannerErrorReason.MissingNotificationPermission(getString(R.string.scanner_error_missing_notification_permission)))
        }

        val locationManager = getSystemService(LocationManager::class.java)
        if (locationManager != null && !locationManager.isLocationEnabled) {
            return ScannerAvailability.Error(ScannerErrorReason.LocationDisabled(getString(R.string.scanner_error_location_disabled)))
        }

        val airplaneModeEnabled = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        if (airplaneModeEnabled) {
            return ScannerAvailability.Error(ScannerErrorReason.AirplaneModeEnabled(getString(R.string.scanner_error_airplane_mode)))
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return ScannerAvailability.Error(ScannerErrorReason.TelephonyUnavailable(getString(R.string.scanner_error_telephony_unavailable)))
        }

        return ScannerAvailability.Available
    }

    private fun requestRequiredScannerPermissions(): Boolean {
        val missingPermissions = requiredScannerPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) return false

        Log.d(TAG, "Requesting scanner permissions: ${missingPermissions.joinToString()}")
        waitingForInitialPermissions = true
        requestPermissions(missingPermissions.toTypedArray(), SCANNER_PERMISSION_REQUEST_CODE)
        return true
    }

    private fun requiredScannerPermissions(): List<String> =
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

    private fun syncScannerStateFromService(animateBars: Boolean) {
        val state = ScannerService.currentState
        sampleCount = state.sampleCount
        latestTelemetry = state.latestTelemetry
        telemetryBars?.let { bars ->
            if (animateBars) {
                bars.setMetrics(latestTelemetry.metrics(), animate = true)
            } else {
                bars.updateMetrics(latestTelemetry.metrics(), showIfEmpty = true)
            }
        }
        if (state.isRunning) {
            scheduleGnssAgeTick()
        }
    }

    private fun registerScannerStateReceiver() {
        if (scannerStateReceiverRegistered) return
        val filter = IntentFilter(ScannerService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scannerStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(scannerStateReceiver, filter)
        }
        scannerStateReceiverRegistered = true
    }

    private fun unregisterScannerStateReceiver() {
        if (!scannerStateReceiverRegistered) return
        runCatching {
            unregisterReceiver(scannerStateReceiver)
        }
        scannerStateReceiverRegistered = false
    }

    private fun updateGnssAgeDisplay() {
        if (!canSample() || showingSettings) return
        telemetryBars?.updateMetrics(latestTelemetry.metrics(), showIfEmpty = false)
    }

    private fun scheduleGnssAgeTick() {
        if (!canSample() || showingSettings || telemetryBars == null || gnssAgeTickScheduled) return
        gnssAgeTickScheduled = true
        handler.postDelayed(gnssAgeTick, GNSS_AGE_TICK_MS)
    }

    private fun stopGnssAgeTick() {
        handler.removeCallbacks(gnssAgeTick)
        gnssAgeTickScheduled = false
    }

    private fun updateStartButtonPulse() {
        val hasActionableError = (scannerAvailability() as? ScannerAvailability.Error)
            ?.reason
            ?.settingsIntent() != null
        if (isStopped() && !hasActionableError && stopStartButton != null && !showingSettings) {
            scheduleStartButtonPulse()
        } else {
            stopStartButtonPulse()
        }
    }

    private fun updateErrorPulse(hasActionableError: Boolean) {
        if (hasActionableError && stopStartButton != null && telemetryBars != null && !showingSettings) {
            scheduleErrorPulse()
        } else {
            stopErrorPulse()
        }
    }

    private fun scheduleErrorPulse() {
        if (errorPulseScheduled) return
        errorPulseScheduled = true
        handler.postDelayed(errorPulse, Random.nextLong(3_200L, 6_400L))
    }

    private fun stopErrorPulse() {
        handler.removeCallbacks(errorPulse)
        errorPulseScheduled = false
        telemetryBars?.clearErrorIconPulse()
    }

    private fun scheduleStartButtonPulse() {
        if (startButtonPulseScheduled) return
        startButtonPulseScheduled = true
        handler.postDelayed(startButtonPulse, Random.nextLong(2_800L, 5_800L))
    }

    private fun stopStartButtonPulse() {
        handler.removeCallbacks(startButtonPulse)
        startButtonPulseScheduled = false
        startButtonPulseAnimator?.cancel()
        startButtonPulseAnimator = null
        stopStartButton?.apply {
            scaleX = 1f
            scaleY = 1f
            elevation = dp(8).toFloat()
        }
    }

    private fun reportingSummary(): String =
        getString(
            R.string.setting_reporting_mode_summary_with_last_sent,
            reportingMode.summary,
            lastReportedAt?.let(dateTimeFormatter::format) ?: getString(R.string.never),
        )

    private fun coverageDataSummary(): String {
        val stats = coverageDataStore.stats()
        return resources.getQuantityString(
            R.plurals.coverage_data_summary,
            stats.sampleCount,
            stats.sampleCount,
            stats.dayCount,
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
        val legacyConsentGranted = getPreferences(MODE_PRIVATE).getBoolean(
            ReportingScheduler.KEY_CONSENT_GRANTED,
            false,
        )
        val settings = settingsStore.load(legacyConsentGranted)
        consentGranted = settings.consentGranted
        stoppedManually = settings.scannerStopped
        gnssMode = settings.gnssMode
        reportingMode = settings.reportingMode
        mockTelemetryEnabled = settings.mockTelemetryEnabled
        lastReportedAt = settings.lastReportedAt
        latestTelemetry = ScannerTelemetrySnapshot.initial(gnssMode)
        Log.d(TAG, "Loaded state: consentGranted=$consentGranted, scannerStopped=$stoppedManually, gnssMode=${gnssMode.name}, reportingMode=${reportingMode.name}, mockTelemetryEnabled=$mockTelemetryEnabled, lastReportedAt=$lastReportedAt")
    }

    private fun saveState() {
        settingsStore.save(
            consentGranted = consentGranted,
            scannerStopped = stoppedManually,
            gnssMode = gnssMode,
            reportingMode = reportingMode,
            mockTelemetryEnabled = mockTelemetryEnabled,
        )
        Log.d(TAG, "Saved state: consentGranted=$consentGranted, scannerStopped=$stoppedManually, gnssMode=${gnssMode.name}, reportingMode=${reportingMode.name}, mockTelemetryEnabled=$mockTelemetryEnabled")
    }

    private fun logAppliedSettingsOnLaunch() {
        if (!consentGranted) return

        Log.i(
            TAG,
            "Applied settings on launch: consentGranted=$consentGranted, scannerStopped=$stoppedManually, scannerWillSample=${canSample()}, gnssMode=${gnssMode.name}, reportingMode=${reportingMode.name}, mockTelemetryEnabled=$mockTelemetryEnabled, lastReportDateTime=${lastReportedAt?.let(dateTimeFormatter::format) ?: "never"}, lastReportedAt=$lastReportedAt, coverageDataStore=${coverageDataStore.displayDirectory()}",
        )
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

            addView(FrameLayout(this@MainActivity).apply {
                addView(scannerTitleHeader(), FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ))
                addView(headerSettingsButton(), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
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
            addView(titleSignalIcon, LinearLayout.LayoutParams(dp(48), dp(48)))
        }

    private fun servingCellSummaryText(): TextView =
        TextView(this).apply {
            textSize = 13f
            setTextColor(SCANNER_SOFT_TEXT)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedBackground(SCANNER_PANEL, dp(8))
        }

    private fun servingCellSummary(cell: Cell): CharSequence {
        val operator = mobileOperatorLookup.displayNameFor(cell.mcc, cell.mnc)
        val summary = getString(R.string.serving_cell_summary, cell.rat, cell.band, RadioFrequencyFormatter.displayText(cell), operator)
        if (!ScannerService.currentState.mockTelemetryActive) return summary

        return SpannableString("MOCK  $summary").apply {
            setSpan(RelativeSizeSpan(0.72f), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.argb(150, 255, 255, 255)), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun headerSettingsButton(): ImageButton =
        ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings_24)
            setColorFilter(SCANNER_SOFT_TEXT)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = getString(R.string.open_settings)
            background = RippleDrawable(
                ColorStateList.valueOf(SCANNER_BUTTON_RIPPLE),
                roundedBackground(Color.TRANSPARENT, dp(24)),
                roundedBackground(Color.WHITE, dp(24)),
            )
            setOnClickListener {
                Log.d(TAG, "Opening settings")
                showingSettings = true
                settingsDestination = SettingsDestination.Main
                render(ScreenTransition.Forward)
            }
        }

    private fun measurementHelpButton(): ImageButton =
        ImageButton(this).apply {
            setImageResource(R.drawable.ic_help_24)
            setColorFilter(SCANNER_SOFT_TEXT)
            scaleType = ImageView.ScaleType.CENTER
            visibility = View.GONE
            contentDescription = getString(R.string.measurement_help_action)
            background = RippleDrawable(
                ColorStateList.valueOf(SCANNER_BUTTON_RIPPLE),
                roundedBackground(Color.TRANSPARENT, dp(20)),
                roundedBackground(Color.WHITE, dp(20)),
            )
            setOnClickListener { showMeasurementHelp() }
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
            render(ScreenTransition.Back)
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

    private fun settingsParagraphRow(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(SETTINGS_SUBTLE_TEXT)
            includeFontPadding = true
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

    private fun settingsActionRow(
        title: String,
        summary: String,
        titleColor: Int = SETTINGS_TEXT,
        onClick: () -> Unit,
    ): LinearLayout =
        settingsRow(title, summary, value = null, isClickable = true, titleColor = titleColor).apply {
            setOnClickListener { onClick() }
        }

    private fun settingsToggleRow(
        title: String,
        summary: String,
        isChecked: Boolean,
        isEnabled: Boolean = true,
        onCheckedChanged: (Boolean) -> Unit,
    ): LinearLayout =
        settingsRow(title, summary, value = null, isClickable = isEnabled).apply {
            this.isEnabled = isEnabled
            alpha = if (isEnabled) 1f else 0.5f
            val checkBox = CheckBox(this@MainActivity).apply {
                buttonTintList = ColorStateList.valueOf(SETTINGS_ACCENT)
                this.isChecked = isChecked
                this.isEnabled = isEnabled
                isClickable = false
                isFocusable = false
            }
            addView(checkBox, LinearLayout.LayoutParams(dp(48), dp(48)))
            setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                val next = !checkBox.isChecked
                checkBox.isChecked = next
                onCheckedChanged(next)
            }
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

    private fun showGnssModeDialog() {
        val modes = GnssMode.entries.toList()
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_gnss_mode_title))
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        modes.forEach { mode ->
            content.addView(locationModeChoiceRow(mode) {
                gnssMode = mode
                latestTelemetry = ScannerTelemetrySnapshot.initial(gnssMode)
                Log.d(TAG, "GNSS mode changed: gnssMode=${gnssMode.name}")
                saveState()
                ensureSamplerState()
                dialog.dismiss()
                render()
            })
        }

        dialog.setView(ScrollView(this).apply {
            addView(content)
        })
        dialog.show()
    }

    private fun locationModeChoiceRow(mode: GnssMode, onSelected: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rippleBackground(Color.TRANSPARENT, dp(6))
            setOnClickListener { onSelected() }

            addView(RadioButton(this@MainActivity).apply {
                isChecked = mode == gnssMode
                isClickable = false
                buttonTintList = ColorStateList.valueOf(SETTINGS_ACCENT)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(12)
            })

            val textColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            textColumn.addView(TextView(this@MainActivity).apply {
                text = mode.label
                textSize = 16f
                setTextColor(SETTINGS_TEXT)
                includeFontPadding = false
            })
            textColumn.addView(TextView(this@MainActivity).apply {
                text = mode.summary
                textSize = 14f
                setTextColor(SETTINGS_SUBTLE_TEXT)
                setPadding(0, dp(4), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            addView(textColumn, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ))
        }

    private fun showReportingModeDialog() {
        val labels = ReportingMode.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_reporting_mode_title))
            .setSingleChoiceItems(labels, ReportingMode.entries.indexOf(reportingMode)) { dialog, which ->
                reportingMode = ReportingMode.entries[which]
                Log.d(TAG, "Reporting mode changed: reportingMode=${reportingMode.name}")
                saveState()
                ensureReportingScheduler()
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendNow() {
        triggerReporting { success ->
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

    private inner class RfSignalBackgroundView : View(this@MainActivity) {
        private var scanning = false
        private var scanStartedAtMs = 0L
        private var lastTouchRippleAtMs = 0L
        private var intensity = latestTelemetry.overallQuality()
        private var random = Random(System.nanoTime())
        private var waves = newWaveSpecs()
        private var spectrumBars = newSpectrumBars()
        private var staticFlecks = newStaticFlecks()
        private var dormantFlecks = newDormantFlecks()
        private val ripples = mutableListOf<RfRipple>()
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val wavePath = Path()

        init {
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setScanning(isScanning: Boolean, signalQuality: Float = intensity) {
            intensity = signalQuality.coerceIn(0f, 1f)
            if (scanning == isScanning) {
                invalidate()
                return
            }

            scanning = isScanning
            if (scanning) {
                beginScanSession()
                postInvalidateOnAnimation()
            } else {
                dormantFlecks = newDormantFlecks()
                ripples.clear()
                invalidate()
            }
        }

        fun handleScannerTouch(event: MotionEvent) {
            if (!scanning) return
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> addRipple(event.x, event.y, touch = true)
                MotionEvent.ACTION_MOVE -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTouchRippleAtMs > 180L) {
                        lastTouchRippleAtMs = now
                        addRipple(event.x, event.y, touch = true)
                    }
                }
            }
        }

        override fun onDetachedFromWindow() {
            scanning = false
            ripples.clear()
            super.onDetachedFromWindow()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == VISIBLE && scanning) {
                postInvalidateOnAnimation()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (windowVisibility != VISIBLE || width <= 0 || height <= 0) return
            if (!scanning) {
                drawDormantAfterglow(canvas)
                return
            }

            val now = SystemClock.uptimeMillis()
            val seconds = (now - scanStartedAtMs) / 1000f
            val activity = 0.65f + intensity * 0.35f

            drawStaticNoise(canvas, seconds, activity)
            drawSpectrumBars(canvas, seconds, activity)
            drawSineWaves(canvas, seconds, activity)
            drawSweep(canvas, seconds)
            drawRipples(canvas, now)

            maybeAddAmbientRipple(now)
            postInvalidateOnAnimation()
        }

        private fun beginScanSession() {
            scanStartedAtMs = SystemClock.uptimeMillis()
            lastTouchRippleAtMs = 0L
            random = Random(System.nanoTime() xor sampleCount.toLong() xor width.toLong() shl 16)
            waves = newWaveSpecs()
            spectrumBars = newSpectrumBars()
            staticFlecks = newStaticFlecks()
            ripples.clear()
            addRipple(width * random.nextFloatIn(0.18f, 0.82f), height * random.nextFloatIn(0.18f, 0.72f), touch = false)
        }

        private fun drawDormantAfterglow(canvas: Canvas) {
            drawDormantAnalyzer(canvas)
            dormantFlecks.forEach { fleck ->
                fillPaint.alpha = (SCANNER_RF_DORMANT_ALPHA * fleck.alpha).toInt().coerceIn(0, SCANNER_RF_DORMANT_ALPHA)
                if (fleck.isDash) {
                    val x = width * fleck.x
                    val y = height * fleck.y
                    canvas.drawRoundRect(
                        x,
                        y,
                        x + dp(8).toFloat() + fleck.size * 5f,
                        y + fleck.size,
                        fleck.size / 2f,
                        fleck.size / 2f,
                        fillPaint,
                    )
                } else {
                    canvas.drawCircle(width * fleck.x, height * fleck.y, fleck.size, fillPaint)
                }
            }

            fillPaint.alpha = 255
        }

        private fun drawDormantAnalyzer(canvas: Canvas) {
            val baseY = height + dp(1).toFloat()
            val barWidth = maxOf(dp(2).toFloat(), width / 116f)
            val maxBarHeight = height * 0.13f
            fillPaint.alpha = SCANNER_RF_DORMANT_BAR_ALPHA
            repeat(42) { index ->
                val x = width * (index / 41f)
                val noise = hashStaticTick(index * 131 + 17)
                val clusterA = dormantClusterEnergy(index / 41f, 0.26f, 0.11f)
                val clusterB = dormantClusterEnergy(index / 41f, 0.68f, 0.08f)
                val heightFactor = (0.06f + noise * 0.14f + clusterA * 0.52f + clusterB * 0.34f).coerceIn(0.04f, 0.72f)
                canvas.drawRoundRect(
                    x,
                    baseY - maxBarHeight * heightFactor,
                    x + barWidth,
                    baseY,
                    barWidth / 2f,
                    barWidth / 2f,
                    fillPaint,
                )
            }

            linePaint.strokeWidth = dp(1).toFloat()
            linePaint.alpha = SCANNER_RF_DORMANT_TRACE_ALPHA
            wavePath.reset()
            val centerY = height * 0.34f
            val amplitude = height * 0.012f
            val step = maxOf(10f, width / 64f)
            var x = -step
            while (x <= width + step) {
                val phase = (x / width.coerceAtLeast(1).toFloat()) * Math.PI.toFloat() * 4.2f
                val y = centerY + kotlin.math.sin(phase + 0.85f) * amplitude
                if (x <= -step) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                x += step
            }
            canvas.drawPath(wavePath, linePaint)

            linePaint.alpha = 255
            fillPaint.alpha = 255
        }

        private fun dormantClusterEnergy(x: Float, center: Float, width: Float): Float {
            val distance = kotlin.math.abs(x - center)
            val falloff = (1f - distance / width).coerceIn(0f, 1f)
            return falloff * falloff
        }

        private fun drawSineWaves(canvas: Canvas, seconds: Float, activity: Float) {
            waves.forEachIndexed { index, wave ->
                wavePath.reset()
                val centerY = height * wave.y
                val amplitudePulse = 0.68f +
                    kotlin.math.sin((seconds * wave.amplitudeSpeed + wave.phase) * Math.PI.toFloat() * 2f) * 0.2f +
                    kotlin.math.sin((seconds * wave.amplitudeSpeed * 0.37f + wave.phase * 1.7f) * Math.PI.toFloat() * 2f) * 0.12f
                val amplitude = height * wave.amplitude * amplitudePulse.coerceIn(0.42f, 1.08f) * (0.82f + activity * 0.22f)
                val step = maxOf(8f, width / 72f)
                var x = -step
                while (x <= width + step) {
                    val normalizedX = x / width.coerceAtLeast(1).toFloat()
                    val phase = normalizedX * wave.frequency + seconds * wave.speed + wave.phase
                    val y = centerY + kotlin.math.sin(phase * Math.PI.toFloat() * 2f) * amplitude
                    if (x <= -step) {
                        wavePath.moveTo(x, y)
                    } else {
                        wavePath.lineTo(x, y)
                    }
                    x += step
                }
                linePaint.strokeWidth = dp(if (index == 0) 2 else 1).toFloat()
                linePaint.alpha = (SCANNER_RF_WAVE_ALPHA * wave.alpha * activity).toInt().coerceIn(0, 255)
                canvas.drawPath(wavePath, linePaint)
            }
            linePaint.alpha = 255
        }

        private fun drawSpectrumBars(canvas: Canvas, seconds: Float, activity: Float) {
            val baseY = height + dp(1).toFloat()
            val maxBarHeight = height * 0.24f
            val barWidth = maxOf(dp(2).toFloat(), width / 108f)
            val analyzerTick = kotlin.math.floor(seconds * 22f).toInt()
            val clusterTick = kotlin.math.floor(seconds * 1.35f).toInt()
            val clusters = spectrumClusters(clusterTick)
            spectrumBars.forEachIndexed { index, bar ->
                val x = width * bar.x
                val fastSample = hashStaticTick(analyzerTick * 97 + index * 53)
                val localNoise = hashStaticTick(analyzerTick * 29 + index * 191 + sampleCount * 7)
                var groupedEnergy = 0f
                clusters.forEach { cluster ->
                    val distance = kotlin.math.abs(bar.x - cluster.center)
                    val wrappedDistance = minOf(distance, 1f - distance)
                    val falloff = (1f - wrappedDistance / cluster.width).coerceIn(0f, 1f)
                    groupedEnergy += falloff * falloff * cluster.strength
                }
                val needle = if (groupedEnergy > 0.12f && fastSample > 0.72f) fastSample * 0.22f else 0f
                val noiseFloor = bar.base * (0.42f + localNoise * 0.28f)
                val heightFactor = (noiseFloor + groupedEnergy * (0.68f + fastSample * 0.28f) + needle).coerceIn(0.04f, 1f)
                val barHeight = maxBarHeight * heightFactor * (0.72f + activity * 0.28f)
                fillPaint.alpha = (SCANNER_RF_BAR_ALPHA * bar.alpha * activity).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(
                    x,
                    baseY - barHeight,
                    x + barWidth,
                    baseY,
                    barWidth / 2f,
                    barWidth / 2f,
                    fillPaint,
                )
            }
            fillPaint.alpha = 255
        }

        private fun spectrumClusters(tick: Int): List<RfSpectrumCluster> =
            List(3) { index ->
                val centerSample = hashStaticTick(tick * 83 + index * 197)
                val jumpSample = hashStaticTick(tick * 47 + index * 67)
                val center = ((centerSample * 0.86f + jumpSample * 0.14f) % 1f).coerceIn(0f, 1f)
                RfSpectrumCluster(
                    center = center,
                    width = 0.045f + hashStaticTick(tick * 29 + index * 113) * 0.085f,
                    strength = 0.42f + hashStaticTick(tick * 131 + index * 31) * 0.58f,
                )
            }

        private fun drawStaticNoise(canvas: Canvas, seconds: Float, activity: Float) {
            val tick = kotlin.math.floor(seconds * 18f).toInt()
            staticFlecks.forEachIndexed { index, fleck ->
                val flash = hashStaticTick(tick + index * 31)
                if (flash < fleck.threshold) return@forEachIndexed

                val shimmer = hashStaticTick(tick * 3 + index * 47)
                val x = width * ((fleck.x + shimmer * 0.018f) % 1f)
                val y = height * fleck.y
                fillPaint.alpha = (SCANNER_RF_STATIC_ALPHA * fleck.alpha * activity * flash).toInt().coerceIn(0, 255)
                if (fleck.isDash) {
                    val dashWidth = dp(3).toFloat() + dp(10).toFloat() * shimmer
                    canvas.drawRoundRect(
                        x,
                        y,
                        x + dashWidth,
                        y + fleck.size,
                        fleck.size / 2f,
                        fleck.size / 2f,
                        fillPaint,
                    )
                } else {
                    canvas.drawCircle(x, y, fleck.size, fillPaint)
                }
            }
            fillPaint.alpha = 255
        }

        private fun drawSweep(canvas: Canvas, seconds: Float) {
            val sweepAge = seconds % 7.5f
            if (sweepAge > 2.2f) return

            val progress = sweepAge / 2.2f
            val x = width * progress
            linePaint.strokeWidth = dp(1).toFloat()
            linePaint.alpha = (38 * (1f - progress)).toInt().coerceIn(0, 38)
            canvas.drawLine(x, height * 0.12f, x, height * 0.88f, linePaint)
            linePaint.alpha = (22 * (1f - progress)).toInt().coerceIn(0, 22)
            canvas.drawLine(x + dp(18), height * 0.2f, x + dp(18), height * 0.74f, linePaint)
            linePaint.alpha = 255
        }

        private fun drawRipples(canvas: Canvas, now: Long) {
            val iterator = ripples.iterator()
            while (iterator.hasNext()) {
                val ripple = iterator.next()
                val age = now - ripple.startedAtMs
                val progress = age.toFloat() / ripple.durationMs.toFloat()
                if (progress >= 1f) {
                    iterator.remove()
                    continue
                }

                val eased = 1f - (1f - progress) * (1f - progress)
                val radius = ripple.radius * eased
                linePaint.strokeWidth = ripple.strokeWidth
                linePaint.alpha = (ripple.alpha * (1f - progress)).toInt().coerceIn(0, 255)
                canvas.drawCircle(ripple.x, ripple.y, radius, linePaint)
                if (ripple.hasEcho) {
                    linePaint.alpha = (ripple.alpha * 0.42f * (1f - progress)).toInt().coerceIn(0, 255)
                    canvas.drawCircle(ripple.x, ripple.y, radius * 0.62f, linePaint)
                }
            }
            linePaint.alpha = 255
        }

        private fun maybeAddAmbientRipple(now: Long) {
            if (ripples.size > 5) return
            val elapsed = now - scanStartedAtMs
            if (elapsed < 900L) return
            val cadence = 1_050L + (1f - intensity) * 900L
            if (random.nextFloat() > 16f / cadence) return
            addRipple(
                x = width * random.nextFloatIn(0.08f, 0.92f),
                y = height * random.nextFloatIn(0.12f, 0.86f),
                touch = false,
            )
        }

        private fun addRipple(x: Float, y: Float, touch: Boolean) {
            val startedAt = SystemClock.uptimeMillis()
            val maxRadius = maxOf(width, height) * if (touch) 0.34f else random.nextFloatIn(0.18f, 0.32f)
            ripples.add(RfRipple(
                x = x,
                y = y,
                radius = maxRadius,
                durationMs = if (touch) 1_150L else random.nextLong(1_650L, 2_700L),
                alpha = if (touch) 72 else random.nextInt(22, 45),
                strokeWidth = dp(if (touch) 2 else 1).toFloat(),
                hasEcho = touch || random.nextFloat() > 0.42f,
                startedAtMs = startedAt,
            ))
            postInvalidateOnAnimation()
        }

        private fun newWaveSpecs(): List<RfWaveSpec> =
            List(3) { index ->
                RfWaveSpec(
                    y = random.nextFloatIn(0.16f + index * 0.17f, 0.26f + index * 0.2f).coerceIn(0.12f, 0.78f),
                    amplitude = random.nextFloatIn(0.012f, 0.032f),
                    frequency = random.nextFloatIn(1.4f, 3.6f),
                    speed = random.nextFloatIn(0.025f, 0.085f) * if (random.nextBoolean()) 1f else -1f,
                    amplitudeSpeed = random.nextFloatIn(0.08f, 0.22f),
                    phase = random.nextFloatIn(0f, 1f),
                    alpha = random.nextFloatIn(0.62f, 1f),
                )
            }

        private fun newSpectrumBars(): List<RfBarSpec> =
            List(44) { index ->
                RfBarSpec(
                    x = index / 43f,
                    base = random.nextFloatIn(0.08f, 0.42f),
                    jitter = random.nextFloatIn(0.5f, 1f),
                    speed = 0f,
                    phase = random.nextFloatIn(0f, 1f),
                    alpha = random.nextFloatIn(0.5f, 1f),
                )
            }

        private fun newStaticFlecks(): List<RfStaticFleck> =
            List(96) {
                RfStaticFleck(
                    x = random.nextFloatIn(0f, 1f),
                    y = random.nextFloatIn(0.58f, 0.98f),
                    size = random.nextFloatIn(0.6f, 1.7f) * resources.displayMetrics.density,
                    threshold = random.nextFloatIn(0.42f, 0.86f),
                    alpha = random.nextFloatIn(0.36f, 1f),
                    isDash = random.nextFloat() > 0.72f,
                )
            }

        private fun newDormantFlecks(): List<RfStaticFleck> =
            List(86) {
                RfStaticFleck(
                    x = random.nextFloatIn(0f, 1f),
                    y = random.nextFloatIn(0.06f, 0.96f),
                    size = random.nextFloatIn(0.55f, 1.45f) * resources.displayMetrics.density,
                    threshold = 0f,
                    alpha = random.nextFloatIn(0.32f, 1f),
                    isDash = random.nextFloat() > 0.84f,
                )
            }

        private fun hashStaticTick(value: Int): Float {
            var hash = value * 1103515245 + 12345
            hash = hash xor (hash ushr 16)
            return (hash and 0x7fffffff).toFloat() / Int.MAX_VALUE.toFloat()
        }
    }

    private data class RfWaveSpec(
        val y: Float,
        val amplitude: Float,
        val frequency: Float,
        val speed: Float,
        val amplitudeSpeed: Float,
        val phase: Float,
        val alpha: Float,
    )

    private data class RfBarSpec(
        val x: Float,
        val base: Float,
        val jitter: Float,
        val speed: Float,
        val phase: Float,
        val alpha: Float,
    )

    private data class RfStaticFleck(
        val x: Float,
        val y: Float,
        val size: Float,
        val threshold: Float,
        val alpha: Float,
        val isDash: Boolean,
    )

    private data class RfSpectrumCluster(
        val center: Float,
        val width: Float,
        val strength: Float,
    )

    private data class RfRipple(
        val x: Float,
        val y: Float,
        val radius: Float,
        val durationMs: Long,
        val alpha: Int,
        val strokeWidth: Float,
        val hasEcho: Boolean,
        val startedAtMs: Long,
    )

    private data class InspectionMetricBar(
        val label: String,
        val valueText: String,
        val quality: Float,
        val hasValue: Boolean,
    )

    private inner class TelemetryBarsView : View(this@MainActivity) {
        private var metrics: List<MetricQuality> = emptyList()
        private var showNoData = false
        private var noDataMessage = getString(R.string.no_data)
        private var noDataIsError = false
        private var errorPulseHighlight = 0f
        private var errorHighlightAnimator: ValueAnimator? = null
        private var noDataAlpha = 0f
        private var barsAlpha = 1f
        private var animatedQualities: List<Float> = emptyList()
        private var previousSampleQualities: List<Float> = emptyList()
        private var animatedPreviousQualities: List<Float> = emptyList()
        private var hasComparisonSample = false
        private var liveMetricProvider: (() -> List<MetricQuality>)? = null
        private var lastQualityFrameMs = 0L
        private var barAnimator: ValueAnimator? = null
        private var visibilityAnimator: ValueAnimator? = null
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SCANNER_BAR_TRACK
            style = Paint.Style.FILL
        }
        private val gnssPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SCANNER_GNSS_PANEL
            style = Paint.Style.FILL
        }
        private val unusableGnssHatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SCANNER_GNSS_UNUSABLE
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
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
        private val warningDrawable: Drawable? = getDrawable(R.drawable.ic_warning_48)?.mutate()?.apply {
            setTint(Color.WHITE)
            alpha = SCANNER_ERROR_ICON_ALPHA
        }
        private val barBounds = RectF()
        private val barClipPath = Path()
        private val gnssPanelPath = Path()

        init {
            background = roundedBackground(SCANNER_PANEL, dp(8))
            contentDescription = getString(R.string.no_data)
        }

        fun setLiveMetricProvider(provider: (() -> List<MetricQuality>)?) {
            liveMetricProvider = provider
        }

        fun setMetrics(metrics: List<MetricQuality>, animate: Boolean) {
            val previousQualities = displayedQualities()
            setNoDataVisible(false, animate)
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
                lastQualityFrameMs = 0L
                invalidate()
                return
            }

            val previousMarkerStart = previousSampleQualities
            val previousMarkerEnd = previousQualities
            previousSampleQualities = previousQualities
            hasComparisonSample = previousMarkerStart.isNotEmpty() || sampleCount >= 2
            animatedPreviousQualities = if (!hasComparisonSample) {
                emptyList()
            } else if (previousMarkerStart.size == previousMarkerEnd.size) {
                previousMarkerEnd
            } else {
                previousMarkerEnd
            }
            invalidate()
        }

        fun updateMetrics(metrics: List<MetricQuality>, showIfEmpty: Boolean) {
            if (metrics.isEmpty() && !showIfEmpty) return
            setNoDataVisible(false, animate = false)
            this.metrics = metrics
            contentDescription = metrics.joinToString(separator = ", ") {
                "${it.label} ${it.valueText}"
            }
            if (animatedQualities.size != metrics.size) {
                animatedQualities = metrics.map { it.quality }
                lastQualityFrameMs = 0L
            }
            invalidate()
        }

        fun showNoData(message: String = getString(R.string.no_data), isError: Boolean = false) {
            barAnimator?.cancel()
            setNoDataVisible(true, animate = true)
            noDataMessage = message
            noDataIsError = isError
            metrics = emptyList()
            animatedQualities = emptyList()
            previousSampleQualities = emptyList()
            animatedPreviousQualities = emptyList()
            hasComparisonSample = false
            contentDescription = message
            invalidate()
        }

        fun pulseErrorIcon() {
            errorHighlightAnimator?.cancel()
            errorPulseHighlight = 1f
            errorHighlightAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 1_250L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    errorPulseHighlight = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun clearErrorIconPulse() {
            errorHighlightAnimator?.cancel()
            errorHighlightAnimator = null
            errorPulseHighlight = 0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawErrorPulseBackground(canvas)
            refreshLiveMetricsForDraw()
            updateDisplayedQualitiesForFrame()
            if (showNoData || noDataAlpha > 0f) {
                val centerY = if (noDataIsError) {
                    errorReasonTextBaseline()
                } else {
                    height / 2f - (emptyPaint.descent() + emptyPaint.ascent()) / 2f
                }
                if (noDataIsError) {
                    drawErrorWarningIcon(canvas)
                }
                emptyPaint.alpha = (SCANNER_SOFT_TEXT_ALPHA * noDataAlpha).toInt().coerceIn(0, SCANNER_SOFT_TEXT_ALPHA)
                canvas.drawText(noDataMessage, width / 2f, centerY, emptyPaint)
                emptyPaint.alpha = SCANNER_SOFT_TEXT_ALPHA
            }
            if (metrics.isEmpty() || barsAlpha <= 0f) return

            val top = dp(22).toFloat()
            val bottom = height - dp(70).toFloat()
            val availableHeight = bottom - top
            val segmentWidth = width.toFloat() / metrics.size
            val barWidth = minOf(dp(34).toFloat(), segmentWidth * 0.42f)
            val hasGnss = metrics.any { it.kind == MetricKind.Gnss }
            if (hasGnss) {
                val dividerX = segmentWidth * 3f
                drawRightRoundedPanel(canvas, dividerX)
                if (metrics.any { it.kind == MetricKind.Gnss && !it.isUsable }) {
                    drawUnusableGnssHatch(canvas, dividerX)
                }
                dividerPaint.alpha = (SCANNER_GNSS_DIVIDER_ALPHA * barsAlpha).toInt().coerceIn(0, SCANNER_GNSS_DIVIDER_ALPHA)
                canvas.drawLine(dividerX, dp(14).toFloat(), dividerX, height - dp(14).toFloat(), dividerPaint)
                dividerPaint.alpha = SCANNER_GNSS_DIVIDER_ALPHA
            }

            metrics.forEachIndexed { index, metric ->
                val centerX = segmentWidth * index + segmentWidth / 2f
                val left = centerX - barWidth / 2f
                val right = centerX + barWidth / 2f

                barBounds.set(left, top, right, bottom)
                trackPaint.alpha = (SCANNER_BAR_TRACK_ALPHA * barsAlpha).toInt().coerceIn(0, SCANNER_BAR_TRACK_ALPHA)
                canvas.drawRoundRect(barBounds, dp(14).toFloat(), dp(14).toFloat(), trackPaint)

                val displayQuality = animatedQualities.getOrElse(index) { metric.quality }
                val fillTop = bottom - availableHeight * displayQuality
                fillPaint.color = qualityColor(displayQuality)
                fillPaint.alpha = (255 * barsAlpha).toInt().coerceIn(0, 255)
                drawMaskedBarFill(canvas, left, top, right, bottom, fillTop)

                if (metric.kind == MetricKind.Radio) {
                    animatedPreviousQualities.getOrNull(index)?.let { previousQuality ->
                        val markerY = bottom - availableHeight * previousQuality.coerceIn(0f, 1f)
                        previousPaint.alpha = (255 * barsAlpha).toInt().coerceIn(0, 255)
                        canvas.drawLine(left - dp(3), markerY, right + dp(3), markerY, previousPaint)
                        drawPreviousMarkerArrow(
                            canvas = canvas,
                            x = right + dp(8),
                            y = markerY,
                            previousQuality = previousQuality,
                            currentQuality = metric.quality,
                        )
                        previousPaint.alpha = 255
                    }
                }

                labelPaint.alpha = (255 * barsAlpha).toInt().coerceIn(0, 255)
                val previousValueColor = valuePaint.color
                valuePaint.color = if (metric.kind == MetricKind.Gnss && !metric.isUsable) SCANNER_GNSS_UNUSABLE_TEXT else SCANNER_SOFT_TEXT
                valuePaint.alpha = (SCANNER_SOFT_TEXT_ALPHA * barsAlpha).toInt().coerceIn(0, SCANNER_SOFT_TEXT_ALPHA)
                canvas.drawText(metric.label, centerX, height - dp(40).toFloat(), labelPaint)
                canvas.drawText(metric.valueText, centerX, height - dp(18).toFloat(), valuePaint)
                labelPaint.alpha = 255
                valuePaint.color = previousValueColor
                valuePaint.alpha = SCANNER_SOFT_TEXT_ALPHA
            }
            trackPaint.alpha = SCANNER_BAR_TRACK_ALPHA
            fillPaint.alpha = 255
            if (!showNoData && shouldKeepAnimatingBars()) {
                postInvalidateOnAnimation()
            }
        }

        private fun drawErrorPulseBackground(canvas: Canvas) {
            if (!showNoData || !noDataIsError || errorPulseHighlight <= 0f) return
            trackPaint.color = Color.WHITE
            trackPaint.alpha = (SCANNER_ERROR_BACKGROUND_PULSE_ALPHA * errorPulseHighlight * noDataAlpha).toInt()
                .coerceIn(0, SCANNER_ERROR_BACKGROUND_PULSE_ALPHA)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(8).toFloat(), dp(8).toFloat(), trackPaint)
            trackPaint.color = SCANNER_BAR_TRACK
            trackPaint.alpha = SCANNER_BAR_TRACK_ALPHA
        }

        private fun drawErrorWarningIcon(canvas: Canvas) {
            val icon = warningDrawable ?: return
            val baseSize = minOf(width * 0.14f, height * 0.18f).toInt().coerceIn(dp(44), dp(72))
            val size = baseSize
            val left = (width - size) / 2
            val top = errorIconTop(size)
            icon.alpha = (SCANNER_ERROR_ICON_ALPHA * noDataAlpha).toInt().coerceIn(0, SCANNER_ERROR_ICON_ALPHA)
            icon.setBounds(left, top, left + size, top + size)
            icon.draw(canvas)
            icon.alpha = SCANNER_ERROR_ICON_ALPHA
        }

        private fun errorReasonTextBaseline(): Float {
            val iconSize = errorBaseIconSize().toFloat()
            val gap = dp(14).toFloat()
            val textHeight = emptyPaint.descent() - emptyPaint.ascent()
            val blockHeight = iconSize + gap + textHeight
            val blockTop = height / 2f - blockHeight / 2f
            return blockTop + iconSize + gap - emptyPaint.ascent()
        }

        private fun errorIconTop(size: Int): Int {
            val iconSize = errorBaseIconSize().toFloat()
            val gap = dp(14).toFloat()
            val textHeight = emptyPaint.descent() - emptyPaint.ascent()
            val blockHeight = iconSize + gap + textHeight
            val blockTop = height / 2f - blockHeight / 2f
            val iconCenterXAlignedTop = blockTop + iconSize / 2f - size / 2f
            return iconCenterXAlignedTop.toInt()
        }

        private fun errorBaseIconSize(): Int =
            minOf(width * 0.14f, height * 0.18f).toInt().coerceIn(dp(44), dp(72))

        private fun refreshLiveMetricsForDraw() {
            if (showNoData) return
            val liveMetrics = liveMetricProvider?.invoke() ?: return
            if (liveMetrics.isEmpty()) return
            if (liveMetrics.size != metrics.size) {
                metrics = liveMetrics
                animatedQualities = liveMetrics.map { it.quality }
                lastQualityFrameMs = 0L
                return
            }
            metrics = liveMetrics
        }

        private fun updateDisplayedQualitiesForFrame() {
            if (metrics.isEmpty()) return
            val targets = metrics.map { it.quality }
            if (animatedQualities.size != targets.size) {
                animatedQualities = targets
                lastQualityFrameMs = SystemClock.uptimeMillis()
                return
            }

            val nowMs = SystemClock.uptimeMillis()
            val elapsedMs = if (lastQualityFrameMs == 0L) QUALITY_ANIMATION_FRAME_MS else (nowMs - lastQualityFrameMs).coerceAtLeast(0L)
            lastQualityFrameMs = nowMs
            val progress = (elapsedMs.toFloat() / QUALITY_ANIMATION_DURATION_MS).coerceIn(0f, 1f)
            if (progress <= 0f) return

            animatedQualities = animatedQualities.zip(targets) { displayed, target ->
                val next = displayed + (target - displayed) * progress
                if (kotlin.math.abs(next - target) < 0.002f) target else next
            }
        }

        private fun displayedQualities(): List<Float> =
            animatedQualities.ifEmpty { metrics.map { it.quality } }

        private fun shouldKeepAnimatingBars(): Boolean {
            val targets = metrics.map { it.quality }
            return animatedQualities.size != targets.size ||
                animatedQualities.zip(targets).any { (displayed, target) -> kotlin.math.abs(displayed - target) > 0.002f } ||
                metrics.any { it.kind == MetricKind.Gnss }
        }

        private fun setNoDataVisible(visible: Boolean, animate: Boolean) {
            if (showNoData == visible && noDataAlpha == if (visible) 1f else 0f) return
            showNoData = visible
            visibilityAnimator?.cancel()

            val targetNoDataAlpha = if (visible) 1f else 0f
            val targetBarsAlpha = if (visible) 0f else 1f
            if (!animate) {
                noDataAlpha = targetNoDataAlpha
                barsAlpha = targetBarsAlpha
                invalidate()
                return
            }

            val startNoDataAlpha = noDataAlpha
            val startBarsAlpha = barsAlpha
            visibilityAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 460L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    noDataAlpha = startNoDataAlpha + (targetNoDataAlpha - startNoDataAlpha) * progress
                    barsAlpha = startBarsAlpha + (targetBarsAlpha - startBarsAlpha) * progress
                    invalidate()
                }
                start()
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
            gnssPanelPaint.alpha = (SCANNER_GNSS_PANEL_ALPHA * barsAlpha).toInt().coerceIn(0, SCANNER_GNSS_PANEL_ALPHA)
            canvas.drawPath(gnssPanelPath, gnssPanelPaint)
            gnssPanelPaint.alpha = SCANNER_GNSS_PANEL_ALPHA
        }

        private fun drawUnusableGnssHatch(canvas: Canvas, left: Float) {
            val checkpoint = canvas.save()
            canvas.clipRect(left, 0f, width.toFloat(), height.toFloat())
            unusableGnssHatchPaint.alpha = (SCANNER_GNSS_UNUSABLE_ALPHA * barsAlpha).toInt().coerceIn(0, SCANNER_GNSS_UNUSABLE_ALPHA)
            val spacing = dp(12).toFloat()
            var x = left - height.toFloat()
            while (x < width + height) {
                canvas.drawLine(x, height.toFloat(), x + height.toFloat(), 0f, unusableGnssHatchPaint)
                x += spacing
            }
            unusableGnssHatchPaint.alpha = SCANNER_GNSS_UNUSABLE_ALPHA
            canvas.restoreToCount(checkpoint)
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

    private inner class InspectionSampleBarsView(
        private val bars: List<InspectionMetricBar>,
    ) : View(this@MainActivity) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(36, 91, 103, 119)
            style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SETTINGS_SUBTLE_TEXT
            textAlign = Paint.Align.LEFT
            textSize = dp(10).toFloat()
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SETTINGS_TEXT
            textAlign = Paint.Align.RIGHT
            textSize = dp(10).toFloat()
        }
        private val barBounds = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (bars.isEmpty()) return

            val gap = dp(10).toFloat()
            val segmentWidth = (width - gap * (bars.size - 1)) / bars.size.toFloat()
            val trackTop = dp(18).toFloat()
            val trackHeight = dp(7).toFloat()
            val radius = trackHeight / 2f

            bars.forEachIndexed { index, bar ->
                val left = index * (segmentWidth + gap)
                val right = left + segmentWidth
                val labelBaseline = dp(10).toFloat()
                labelPaint.alpha = if (bar.hasValue) 210 else 110
                valuePaint.alpha = if (bar.hasValue) 220 else 110
                canvas.drawText(bar.label, left, labelBaseline, labelPaint)
                canvas.drawText(bar.valueText, right, labelBaseline, valuePaint)

                barBounds.set(left, trackTop, right, trackTop + trackHeight)
                trackPaint.alpha = if (bar.hasValue) 46 else 26
                canvas.drawRoundRect(barBounds, radius, radius, trackPaint)

                val fillRight = left + segmentWidth * bar.quality.coerceIn(0f, 1f)
                fillPaint.color = if (bar.hasValue) qualityColor(bar.quality) else SETTINGS_DIVIDER
                fillPaint.alpha = if (bar.hasValue) 170 else 80
                barBounds.set(left, trackTop, fillRight, trackTop + trackHeight)
                canvas.drawRoundRect(barBounds, radius, radius, fillPaint)
            }

            labelPaint.alpha = 255
            valuePaint.alpha = 255
            trackPaint.alpha = 255
            fillPaint.alpha = 255
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

    private enum class SettingsDestination {
        Main,
        CoverageData,
        About,
    }

    private enum class ScreenTransition {
        None,
        Forward,
        Back,
    }

    private sealed interface ScannerAvailability {
        data object Available : ScannerAvailability
        data class Error(val reason: ScannerErrorReason) : ScannerAvailability
    }

    private sealed class ScannerErrorReason(val message: String) {
        class MissingLocationPermission(message: String) : ScannerErrorReason(message)
        class MissingNotificationPermission(message: String) : ScannerErrorReason(message)
        class LocationDisabled(message: String) : ScannerErrorReason(message)
        class AirplaneModeEnabled(message: String) : ScannerErrorReason(message)
        class TelephonyUnavailable(message: String) : ScannerErrorReason(message)
    }

    private companion object {
        const val TAG = "5GScanner"
        const val CONSENT_TRANSITION_DELAY_MS = 250L
        const val SCANNER_PERMISSION_REQUEST_CODE = 7001
        const val INSPECTOR_SAMPLE_LIMIT = 25
        const val RECENT_EXPORT_SAMPLE_LIMIT = 1_000
        const val GNSS_AGE_TICK_MS = 1_000L
        const val QUALITY_ANIMATION_DURATION_MS = 420L
        const val QUALITY_ANIMATION_FRAME_MS = 16L
        val SCANNER_BACKGROUND: Int = Color.rgb(15, 118, 110)
        val SCANNER_PANEL: Int = Color.argb(43, 255, 255, 255)
        val SCANNER_SOFT_TEXT: Int = Color.argb(204, 255, 255, 255)
        const val SCANNER_SOFT_TEXT_ALPHA: Int = 204
        val SCANNER_BUTTON_RIPPLE: Int = Color.argb(31, 15, 118, 110)
        val SCANNER_RING: Int = Color.argb(178, 255, 255, 255)
        const val SCANNER_ERROR_ICON_ALPHA: Int = 218
        const val SCANNER_ERROR_BACKGROUND_PULSE_ALPHA: Int = 46
        const val SCANNER_RF_WAVE_ALPHA: Int = 56
        const val SCANNER_RF_BAR_ALPHA: Int = 42
        const val SCANNER_RF_STATIC_ALPHA: Int = 62
        const val SCANNER_RF_DORMANT_ALPHA: Int = 38
        const val SCANNER_RF_DORMANT_BAR_ALPHA: Int = 34
        const val SCANNER_RF_DORMANT_TRACE_ALPHA: Int = 32
        val SCANNER_BAR_TRACK: Int = Color.argb(38, 255, 255, 255)
        const val SCANNER_BAR_TRACK_ALPHA: Int = 38
        val SCANNER_GNSS_PANEL: Int = Color.argb(34, 0, 0, 0)
        const val SCANNER_GNSS_PANEL_ALPHA: Int = 34
        val SCANNER_GNSS_DIVIDER: Int = Color.argb(138, 255, 255, 255)
        const val SCANNER_GNSS_DIVIDER_ALPHA: Int = 138
        val SCANNER_GNSS_UNUSABLE: Int = Color.rgb(254, 202, 202)
        const val SCANNER_GNSS_UNUSABLE_ALPHA: Int = 112
        val SCANNER_GNSS_UNUSABLE_TEXT: Int = Color.rgb(254, 226, 226)
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
