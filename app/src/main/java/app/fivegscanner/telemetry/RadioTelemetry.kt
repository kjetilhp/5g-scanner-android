package app.fivegscanner.telemetry

import app.fivegscanner.domain.Cell
import java.time.Instant

data class RadioTelemetry(
    val receivedAt: Instant,
    val servingCell: Cell,
)
