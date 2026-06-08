package no.politiet.pit.fivegscanner.reporting

import no.politiet.pit.fivegscanner.storage.ReportingBatchEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportingRecoveryTest {
    @Test
    fun treatsInFlightBatchWithPastRetryTimeAsStale() {
        val batch = batch(
            status = ReportingBatchEntity.StatusInFlight,
            nextAttemptAtEpochMillis = 1_000L,
        )

        assertTrue(ReportingRecovery.isStaleInFlight(batch, nowEpochMillis = 1_000L))
        assertTrue(ReportingRecovery.isStaleInFlight(batch, nowEpochMillis = 1_001L))
    }

    @Test
    fun doesNotTreatActiveInFlightBatchAsStale() {
        val batch = batch(
            status = ReportingBatchEntity.StatusInFlight,
            nextAttemptAtEpochMillis = 2_000L,
        )

        assertFalse(ReportingRecovery.isStaleInFlight(batch, nowEpochMillis = 1_999L))
    }

    @Test
    fun doesNotTreatInFlightBatchWithoutRetryTimeAsStale() {
        val batch = batch(
            status = ReportingBatchEntity.StatusInFlight,
            nextAttemptAtEpochMillis = null,
        )

        assertFalse(ReportingRecovery.isStaleInFlight(batch, nowEpochMillis = 2_000L))
    }

    @Test
    fun ignoresNonInFlightBatches() {
        val failedBatch = batch(
            status = ReportingBatchEntity.StatusFailed,
            nextAttemptAtEpochMillis = 1_000L,
        )
        val uploadedBatch = batch(
            status = ReportingBatchEntity.StatusUploaded,
            nextAttemptAtEpochMillis = 1_000L,
        )

        assertFalse(ReportingRecovery.isStaleInFlight(failedBatch, nowEpochMillis = 2_000L))
        assertFalse(ReportingRecovery.isStaleInFlight(uploadedBatch, nowEpochMillis = 2_000L))
    }

    private fun batch(
        status: String,
        nextAttemptAtEpochMillis: Long?,
    ): ReportingBatchEntity =
        ReportingBatchEntity(
            id = "batch-1",
            status = status,
            createdAtEpochMillis = 100L,
            firstSampleId = 1L,
            lastSampleId = 1L,
            sampleCount = 1,
            payloadBytes = 64L,
            nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
        )
}
