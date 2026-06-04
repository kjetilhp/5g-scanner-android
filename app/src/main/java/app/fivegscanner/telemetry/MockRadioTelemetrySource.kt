package app.fivegscanner.telemetry

import app.fivegscanner.domain.LteCell
import app.fivegscanner.domain.Signal
import java.time.Instant

class MockRadioTelemetrySource : RadioTelemetrySource {
    override fun latest(sampleNumber: Int, capturedAt: Instant): RadioTelemetry {
        val wave = sampleNumber % 6
        val rsrpValues = intArrayOf(-113, -100, -88, -96, -109, -84)
        val rsrqValues = intArrayOf(-19, -15, -9, -12, -18, -8)
        val sinrValues = intArrayOf(3, 10, 22, 15, 6, 24)
        val signal = Signal(
            rsrp = rsrpValues[wave],
            rsrq = rsrqValues[wave],
            sinr = sinrValues[wave],
            rssi = rsrpValues[wave] + 30,
        )

        return RadioTelemetry(
            receivedAt = capturedAt,
            servingCell = LteCell(
                mcc = 242,
                mnc = 1,
                cellId = "mock-${100000 + sampleNumber}",
                tac = 4100 + (sampleNumber % 12),
                pci = 120 + (sampleNumber % 48),
                earfcn = 6300 + (sampleNumber % 20),
                band = 20,
                signal = signal,
            ),
        )
    }
}
