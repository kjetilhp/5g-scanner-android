package no.politiet.pit.telemetry

import android.content.Context

object TelemetrySourceFactory {
    data class Sources(
        val radio: RadioTelemetrySource,
        val gnss: GnssTelemetrySource,
    )

    fun create(context: Context, useMockTelemetry: Boolean): Sources =
        if (useMockTelemetry) {
            Sources(
                radio = MockRadioTelemetrySource(),
                gnss = MockGnssTelemetrySource(),
            )
        } else {
            Sources(
                radio = AndroidRadioTelemetrySource(context.applicationContext),
                gnss = AndroidGnssTelemetrySource(context.applicationContext),
            )
        }
}
