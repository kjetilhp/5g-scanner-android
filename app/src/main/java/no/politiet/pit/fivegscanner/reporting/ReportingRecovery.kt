package no.politiet.pit.fivegscanner.reporting

import no.politiet.pit.fivegscanner.storage.ReportingBatchEntity

object ReportingRecovery {
    const val StaleInFlightError = "Recovered interrupted reporting send."

    fun isStaleInFlight(batch: ReportingBatchEntity, nowEpochMillis: Long): Boolean =
        batch.status == ReportingBatchEntity.StatusInFlight &&
            batch.nextAttemptAtEpochMillis != null &&
            batch.nextAttemptAtEpochMillis <= nowEpochMillis
}
