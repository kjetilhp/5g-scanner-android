package no.politiet.pit.telemetry

import no.politiet.pit.domain.Cell
import java.time.Instant

data class RadioTelemetry(
    val receivedAt: Instant,
    val servingCell: Cell,
    val radioSourceIndex: Int = 0,
    val subscriptionCarrierName: String? = null,
    val isDefaultDataSubscription: Boolean = false,
)
