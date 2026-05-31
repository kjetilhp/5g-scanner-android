package no.politiet.pit

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
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
    private var pausedManually = false
    private var pausedUntil: Instant? = null
    private var showingSettings = false
    private var sampleCount = 0
    private var lastSampleAt: Instant? = null
    private var frequency = SamplingFrequency.Balanced
    private var gnssMode = GnssMode.Balanced

    private var statusText: TextView? = null
    private var sampleText: TextView? = null
    private var lastSampleText: TextView? = null
    private var mockLogText: TextView? = null
    private var pauseButton: Button? = null

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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(title("Ask"))

        root.addView(section("Scanner"))
        statusText = body("")
        sampleText = body("")
        lastSampleText = body("")
        root.addView(statusText)
        root.addView(sampleText)
        root.addView(lastSampleText)
        pauseButton = actionButton(getString(R.string.pause_15_minutes)) {
            if (!isPaused()) {
                showPauseDialog()
            } else {
                resumeScanning()
            }
        }
        root.addView(pauseButton)

        root.addView(section("Mock Telemetry"))
        mockLogText = body(getString(R.string.no_samples_yet))
        root.addView(mockLogText)
        root.addView(actionButton(getString(R.string.open_settings)) {
            showingSettings = true
            render()
        })

        return ScrollView(this).apply {
            addView(root)
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
        mockLogText = null
        pauseButton = null
    }

    private fun captureMockSample() {
        sampleCount += 1
        lastSampleAt = Instant.now()
        mockLogText?.text = buildString {
            appendLine("mock://pixel-9-emulator")
            appendLine("sample: $sampleCount")
            appendLine("radio: NR5G-NSA")
            appendLine("rsrp: -91 dBm, rsrq: -11 dB, sinr: 18 dB")
            appendLine("gnss: ${gnssMode.label}")
            append("captured: ${clockFormatter.format(lastSampleAt)}")
        }
        updateScannerUi()
    }

    private fun showPauseDialog() {
        val options = arrayOf(
            getString(R.string.pause_until_i_start),
            getString(R.string.pause_15_minutes),
            getString(R.string.pause_1_hour),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pause_scanning_title))
            .setItems(options) { _, which ->
        pausedUntil = when (which) {
                    0 -> null
                    1 -> Instant.now().plusSeconds(15 * 60)
                    else -> Instant.now().plusSeconds(60 * 60)
                }
                pausedManually = which == 0
                handler.removeCallbacks(sampler)
                updateScannerUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resumeScanning() {
        pausedManually = false
        pausedUntil = null
        ensureSamplerState()
        updateScannerUi()
    }

    private fun canSample(): Boolean {
        val pause = pausedUntil
        if (pause != null && Instant.now().isAfter(pause)) {
            pausedUntil = null
        }
        return consentGranted && !pausedManually && pausedUntil == null
    }

    private fun ensureSamplerState() {
        handler.removeCallbacks(sampler)
        if (canSample()) {
            handler.post(sampler)
        }
    }

    private fun updateScannerUi() {
        val effectiveStatus = currentScannerStatus()

        statusText?.text = getString(R.string.status_format, effectiveStatus)
        sampleText?.text = getString(R.string.samples_format, sampleCount)
        lastSampleText?.text = getString(
            R.string.last_sample_format,
            lastSampleAt?.let(clockFormatter::format) ?: getString(R.string.never),
        )
        pauseButton?.text = if (isPaused()) {
            getString(R.string.resume_scanning)
        } else {
            getString(R.string.pause_scanning)
        }
    }

    private fun currentScannerStatus(): String {
        val pause = pausedUntil
        return when {
            pausedManually -> getString(R.string.status_paused_manual)
            pause != null -> getString(R.string.status_paused_until, clockFormatter.format(pause))
            else -> getString(R.string.status_scanning)
        }
    }

    private fun isPaused(): Boolean = pausedManually || pausedUntil != null

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

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 20f
        setPadding(0, 32, 0, 8)
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, 4, 0, 4)
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
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(SETTINGS_TEXT)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun settingsSectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text.uppercase()
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
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
        val SETTINGS_BACKGROUND: Int = Color.rgb(246, 247, 249)
        val SETTINGS_TEXT: Int = Color.rgb(31, 41, 55)
        val SETTINGS_SUBTLE_TEXT: Int = Color.rgb(91, 103, 119)
        val SETTINGS_ACCENT: Int = Color.rgb(15, 118, 110)
        val SETTINGS_CHEVRON: Int = Color.rgb(148, 163, 184)
        val SETTINGS_DIVIDER: Int = Color.rgb(229, 232, 237)
        val SETTINGS_RIPPLE: Int = Color.argb(26, 15, 118, 110)
    }
}
