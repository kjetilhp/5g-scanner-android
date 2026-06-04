package app.fivegscanner.telemetry

import android.content.Context
import java.time.Instant

class AndroidRadioTelemetrySource(
    @Suppress("unused") private val context: Context,
) : RadioTelemetrySource {
    override fun latest(sampleNumber: Int, capturedAt: Instant): RadioTelemetry? {
        // Real TelephonyCallback/requestCellInfoUpdate wiring will land behind this source.
        return null
    }
}
