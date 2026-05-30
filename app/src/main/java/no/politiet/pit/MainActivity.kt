package no.politiet.pit

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(title(getString(R.string.settings_title)))
        root.addView(actionButton(getString(R.string.back_to_scanner)) {
            showingSettings = false
            render()
        })

        root.addView(section("Sampling"))
        root.addView(label("Sampling frequency"))
        root.addView(spinner(
            SamplingFrequency.entries.map { it.label },
            SamplingFrequency.entries.indexOf(frequency),
        ) { index ->
            frequency = SamplingFrequency.entries[index]
            saveState()
            ensureSamplerState()
            updateScannerUi()
        })

        root.addView(section("Location"))
        root.addView(label("GNSS mode"))
        root.addView(spinner(
            GnssMode.entries.map { it.label },
            GnssMode.entries.indexOf(gnssMode),
        ) { index ->
            gnssMode = GnssMode.entries[index]
            saveState()
            updateScannerUi()
        })

        updateScannerUi()

        return ScrollView(this).apply {
            addView(root)
        }
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
        val pause = pausedUntil
        val effectiveStatus = when {
            pausedManually -> getString(R.string.status_paused_manual)
            pause != null -> getString(R.string.status_paused_until, clockFormatter.format(pause))
            else -> getString(R.string.status_scanning)
        }

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

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 12, 0, 4)
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

    private fun buttonRow(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (button in buttons) {
                addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun spinner(labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels,
            )
            setSelection(selectedIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onSelected(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

    private enum class SamplingFrequency(val label: String, val intervalMs: Long) {
        LowImpact("Low impact - 60s", 60_000),
        Balanced("Balanced - 15s", 15_000),
        HighDetail("High detail - 5s", 5_000),
        Debug("Debug - 1s", 1_000);

        companion object {
            fun fromName(value: String?): SamplingFrequency =
                entries.firstOrNull { it.name == value } ?: Balanced
        }
    }

    private enum class GnssMode(val label: String) {
        Balanced("Balanced"),
        HighAccuracy("High accuracy");

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
    }
}
