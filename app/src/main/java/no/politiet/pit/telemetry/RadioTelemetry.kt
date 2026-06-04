package no.politiet.pit.telemetry

import no.politiet.pit.domain.Cell
import java.time.Instant

data class RadioTelemetry(
    val receivedAt: Instant,
    val servingCell: Cell,
)
