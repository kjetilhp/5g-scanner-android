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
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    private var consentGranted = false
    private var stoppedManually = false
    private var stoppedUntil: Instant? = null
    private var showingSettings = false
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
    private var scannerActivityRing: View? = null
    private var scannerActivityRingAnimator: ObjectAnimator? = null
    private var latestTelemetry = MockTelemetry.initial(gnssMode)
    private var samplerScheduled = false

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

    private fun render() {
        setContentView(when {
            !consentGranted -> createConsentView()
            showingSettings -> createSettingsView()
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
                showStopDialog()
            } else {
                startScanning()
            }
        }

        statusText = scannerStatusText()
        sessionSampleText = scannerSessionSampleText()
        scannerActivityRing = ScannerActivityRing()
        val settingsButton = scannerIconButton(R.drawable.ic_settings_24, getString(R.string.open_settings), dp(28)) {
            showingSettings = true
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

        content.addView(settingsSectionLabel(getString(R.string.settings_section_privacy)))
        content.addView(settingsGroup(
            settingsInfoRow(
                title = getString(R.string.setting_storage_title),
                summary = getString(R.string.setting_storage_summary),
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

    private fun clearScannerViews() {
        statusText = null
        sessionSampleText = null
        telemetryBars = null
        titleSignalIcon = null
        stopStartButton = null
        scannerActivityRingAnimator?.cancel()
        scannerActivityRingAnimator = null
        scannerActivityRing = null
    }

    private fun captureMockSample() {
        sampleCount += 1
        lastSampleAt = Instant.now()
        latestTelemetry = MockTelemetry.fromSample(sampleCount, gnssMode)
        telemetryBars?.setMetrics(latestTelemetry.metrics(), animate = true)
        triggerContinuousReporting()
        updateScannerUi()
    }

    private fun showStopDialog() {
        val options = arrayOf(
            getString(R.string.stop_until_i_start),
            getString(R.string.stop_15_minutes),
            getString(R.string.stop_1_hour),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.stop_scanning_title))
            .setItems(options) { _, which ->
                stoppedUntil = when (which) {
                    0 -> null
                    1 -> Instant.now().plusSeconds(15 * 60)
                    else -> Instant.now().plusSeconds(60 * 60)
                }
                stoppedManually = which == 0
                handler.removeCallbacks(sampler)
                samplerScheduled = false
                updateScannerUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startScanning() {
        stoppedManually = false
        stoppedUntil = null
        ensureSamplerState()
        updateScannerUi()
    }

    private fun canSample(): Boolean {
        val stop = stoppedUntil
        if (stop != null && Instant.now().isAfter(stop)) {
            stoppedUntil = null
        }
        return consentGranted && !stoppedManually && stoppedUntil == null
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
        titleSignalIcon?.setQuality(latestTelemetry.overallQuality(), animate = sampleCount > 0)
        if (!canSample()) {
            telemetryBars?.showNoData()
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
        val stop = stoppedUntil
        return when {
            stoppedManually -> getString(R.string.status_stopped_manual)
            stop != null -> getString(R.string.status_stopped_until, clockFormatter.format(stop))
            else -> getString(R.string.status_scanning)
        }
    }

    private fun reportingSummary(): String =
        getString(
            R.string.setting_reporting_mode_summary_with_last_sent,
            reportingMode.summary,
            lastReportedAt?.let(dateTimeFormatter::format) ?: getString(R.string.never),
        )

    private fun isStopped(): Boolean = stoppedManually || stoppedUntil != null

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

    private fun settingsToolbar(): LinearLayout =
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
                contentDescription = getString(R.string.back_to_scanner)
                background = rippleBackground(Color.TRANSPARENT, dp(24))
                setOnClickListener {
                    showingSettings = false
                    render()
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))

            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.settings_title)
                textSize = 20f
                setTextColor(SETTINGS_TEXT)
                includeFontPadding = false
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

    private fun settingsRow(
        title: String,
        summary: String,
        value: String?,
        isClickable: Boolean,
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
                setTextColor(SETTINGS_TEXT)
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
            contentDescription = getString(R.string.no_samples_yet)
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

    private companion object {
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
        val SETTINGS_CHEVRON: Int = Color.rgb(148, 163, 184)
        val SETTINGS_DIVIDER: Int = Color.rgb(229, 232, 237)
        val SETTINGS_RIPPLE: Int = Color.argb(26, 15, 118, 110)
    }
}
