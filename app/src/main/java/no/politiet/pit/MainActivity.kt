package no.politiet.pit

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

    private var consentGranted = false
    private var stoppedManually = false
    private var stoppedUntil: Instant? = null
    private var showingSettings = false
    private var sampleCount = 0
    private var lastSampleAt: Instant? = null
    private var frequency = SamplingFrequency.Balanced
    private var gnssMode = GnssMode.Balanced

    private var statusText: TextView? = null
    private var sampleText: TextView? = null
    private var lastSampleText: TextView? = null
    private var telemetryBars: TelemetryBarsView? = null
    private var stopStartButton: ImageButton? = null
    private var scannerActivityRing: View? = null
    private var scannerActivityRingAnimator: ObjectAnimator? = null
    private var latestTelemetry = MockTelemetry.initial(gnssMode)

    private val sampler = object : Runnable {
        override fun run() {
            if (canSample()) {
                captureMockSample()
                handler.postDelayed(this, frequency.intervalMs)
            } else {
                render()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadState()
        render()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sampler)
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
            updateScannerUi()
        }
    }

    private fun createConsentView(): View {
        clearScannerViews()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }

        content.addView(title("Ask"))
        content.addView(body(getString(R.string.consent_intro)))
        content.addView(body(getString(R.string.consent_data_scope)))
        content.addView(body(getString(R.string.consent_local_only)))
        content.addView(body(getString(R.string.consent_reversible)))
        content.addView(actionButton(getString(R.string.grant_consent)) {
            consentGranted = true
            saveState()
            handler.postDelayed({ render() }, CONSENT_TRANSITION_DELAY_MS)
        })

        return ScrollView(this).apply {
            addView(content)
        }
    }

    private fun createScannerView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), statusBarHeight() + dp(28), dp(28), dp(152))
        }

        content.addView(scannerTitle())

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(28))
        }
        sampleText = scannerMetricText()
        lastSampleText = scannerMetricText()
        metrics.addView(sampleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        metrics.addView(lastSampleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(metrics)

        content.addView(scannerSection(getString(R.string.latest_measurement_title)))
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
            addView(settingsButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(24)
                bottomMargin = navigationBarHeight() + dp(40)
            })
        }
    }

    private fun createSettingsView(): View {
        clearScannerViews()

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
            settingsInfoRow(
                title = getString(R.string.setting_scanner_status_title),
                summary = currentScannerStatus(),
            ),
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
        sampleText = null
        lastSampleText = null
        telemetryBars = null
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
        handler.removeCallbacks(sampler)
        if (canSample()) {
            handler.post(sampler)
        }
    }

    private fun updateScannerUi() {
        val effectiveStatus = currentScannerStatus()

        statusText?.text = effectiveStatus
        sampleText?.text = getString(R.string.samples_format, sampleCount)
        lastSampleText?.text = getString(
            R.string.last_sample_format,
            lastSampleAt?.let(clockFormatter::format) ?: getString(R.string.never),
        )
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

    private fun isStopped(): Boolean = stoppedManually || stoppedUntil != null

    private fun loadState() {
        val preferences = getPreferences(MODE_PRIVATE)
        consentGranted = preferences.getBoolean(KEY_CONSENT_GRANTED, false)
        frequency = SamplingFrequency.fromName(
            preferences.getString(KEY_FREQUENCY, SamplingFrequency.Balanced.name),
        )
        gnssMode = GnssMode.fromName(
            preferences.getString(KEY_GNSS_MODE, GnssMode.Balanced.name),
        )
    }

    private fun saveState() {
        getPreferences(MODE_PRIVATE).edit()
            .putBoolean(KEY_CONSENT_GRANTED, consentGranted)
            .putString(KEY_FREQUENCY, frequency.name)
            .putString(KEY_GNSS_MODE, gnssMode.name)
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

    private fun scannerTitle(): TextView = TextView(this).apply {
        text = getString(R.string.app_name)
        textSize = 44f
        setTextColor(Color.WHITE)
        includeFontPadding = false
        setPadding(0, 0, 0, dp(18))
    }

    private fun scannerStatusText(): TextView = TextView(this).apply {
        textSize = 18f
        setTextColor(SCANNER_SOFT_TEXT)
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    private fun scannerMetricText(): TextView = TextView(this).apply {
        textSize = 15f
        setTextColor(SCANNER_SOFT_TEXT)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun scannerSection(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 13f
        setTextColor(SCANNER_SOFT_TEXT)
        includeFontPadding = false
        setPadding(0, dp(12), 0, dp(10))
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
                addView(TextView(this@MainActivity).apply {
                    text = ">"
                    textSize = 22f
                    setTextColor(SETTINGS_CHEVRON)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
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
        private var animatedQualities: List<Float> = emptyList()
        private var previousSampleQualities: List<Float> = emptyList()
        private var animatedPreviousQualities: List<Float> = emptyList()
        private var hasComparisonSample = false
        private var barAnimator: ValueAnimator? = null
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SCANNER_BAR_TRACK
            style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val previousPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1f
        }
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
        private val barBounds = RectF()

        init {
            background = roundedBackground(SCANNER_PANEL, dp(8))
            contentDescription = getString(R.string.no_samples_yet)
        }

        fun setMetrics(metrics: List<MetricQuality>, animate: Boolean) {
            val previousMetrics = this.metrics
            val previousQualities = animatedQualities.ifEmpty { previousMetrics.map { it.quality } }
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

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (metrics.isEmpty()) return

            val top = dp(22).toFloat()
            val bottom = height - dp(70).toFloat()
            val availableHeight = bottom - top
            val segmentWidth = width.toFloat() / metrics.size
            val barWidth = minOf(dp(34).toFloat(), segmentWidth * 0.42f)

            metrics.forEachIndexed { index, metric ->
                val centerX = segmentWidth * index + segmentWidth / 2f
                val left = centerX - barWidth / 2f
                val right = centerX + barWidth / 2f

                barBounds.set(left, top, right, bottom)
                canvas.drawRoundRect(barBounds, dp(14).toFloat(), dp(14).toFloat(), trackPaint)

                val animatedQuality = animatedQualities.getOrElse(index) { metric.quality }
                val fillTop = bottom - availableHeight * animatedQuality
                fillPaint.color = qualityColor(animatedQuality)
                barBounds.set(left, fillTop, right, bottom)
                canvas.drawRoundRect(barBounds, dp(14).toFloat(), dp(14).toFloat(), fillPaint)

                animatedPreviousQualities.getOrNull(index)?.let { previousQuality ->
                    val markerY = bottom - availableHeight * previousQuality.coerceIn(0f, 1f)
                    canvas.drawLine(left - dp(3), markerY, right + dp(3), markerY, previousPaint)
                }

                canvas.drawText(metric.label, centerX, height - dp(40).toFloat(), labelPaint)
                canvas.drawText(metric.valueText, centerX, height - dp(18).toFloat(), valuePaint)
            }
        }
    }

    private data class MetricQuality(
        val label: String,
        val valueText: String,
        val quality: Float,
    )

    private data class MockTelemetry(
        val rsrp: Int,
        val rsrq: Int,
        val sinr: Int,
        val hdop: Float,
        val fixAgeSeconds: Int,
        val gnssMode: GnssMode,
    ) {
        fun metrics(): List<MetricQuality> = listOf(
            MetricQuality("RSRP", "$rsrp", qualityFromRange(rsrp.toFloat(), -118f, -82f)),
            MetricQuality("RSRQ", "$rsrq", qualityFromRange(rsrq.toFloat(), -20f, -8f)),
            MetricQuality("SINR", "$sinr", qualityFromRange(sinr.toFloat(), 0f, 24f)),
            MetricQuality("GNSS", "H${formatHdop(hdop)} ${fixAgeSeconds}s", gnssQuality(hdop, fixAgeSeconds)),
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

    private companion object {
        const val CONSENT_TRANSITION_DELAY_MS = 250L
        const val KEY_CONSENT_GRANTED = "consentGranted"
        const val KEY_FREQUENCY = "frequency"
        const val KEY_GNSS_MODE = "gnssMode"
        val SCANNER_BACKGROUND: Int = Color.rgb(15, 118, 110)
        val SCANNER_PANEL: Int = Color.argb(43, 255, 255, 255)
        val SCANNER_SOFT_TEXT: Int = Color.argb(204, 255, 255, 255)
        val SCANNER_BUTTON_RIPPLE: Int = Color.argb(31, 15, 118, 110)
        val SCANNER_RING: Int = Color.argb(178, 255, 255, 255)
        val SCANNER_BAR_TRACK: Int = Color.argb(38, 255, 255, 255)
        val SCANNER_QUALITY_LOW: Int = Color.rgb(248, 113, 113)
        val SCANNER_QUALITY_MEDIUM: Int = Color.rgb(250, 204, 21)
        val SCANNER_QUALITY_HIGH: Int = Color.rgb(134, 239, 172)
        val SETTINGS_BACKGROUND: Int = Color.rgb(246, 247, 249)
        val SETTINGS_TEXT: Int = Color.rgb(31, 41, 55)
        val SETTINGS_SUBTLE_TEXT: Int = Color.rgb(91, 103, 119)
        val SETTINGS_ACCENT: Int = Color.rgb(15, 118, 110)
        val SETTINGS_CHEVRON: Int = Color.rgb(148, 163, 184)
        val SETTINGS_DIVIDER: Int = Color.rgb(229, 232, 237)
        val SETTINGS_RIPPLE: Int = Color.argb(26, 15, 118, 110)
    }
}
