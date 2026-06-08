package no.politiet.pit.fivegscanner.reporting

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportingDrainTest {
    @Test
    fun returnsNoSamplesWithoutSendingWhenAlreadyUpToDate() {
        var calls = 0

        val result = ReportingDrain.drain {
            calls += 1
            ReportingScheduler.ReportingResult(ReportingScheduler.Outcome.NoSamples)
        }

        assertEquals(1, calls)
        assertEquals(ReportingScheduler.Outcome.NoSamples, result.outcome)
        assertEquals(0, result.sampleCount)
        assertEquals(0L, result.payloadBytes)
    }

    @Test
    fun drainsMultipleSuccessfulBatchesUntilUpToDate() {
        val results = ArrayDeque(
            listOf(
                ReportingScheduler.ReportingResult(
                    outcome = ReportingScheduler.Outcome.Sent,
                    sampleCount = 2,
                    payloadBytes = 120,
                    batchId = "batch-1",
                ),
                ReportingScheduler.ReportingResult(
                    outcome = ReportingScheduler.Outcome.Sent,
                    sampleCount = 3,
                    payloadBytes = 180,
                    batchId = "batch-2",
                ),
                ReportingScheduler.ReportingResult(ReportingScheduler.Outcome.NoSamples),
            ),
        )

        val result = ReportingDrain.drain { results.removeFirst() }

        assertEquals(ReportingScheduler.Outcome.Sent, result.outcome)
        assertEquals(5, result.sampleCount)
        assertEquals(300L, result.payloadBytes)
        assertEquals("batch-2", result.batchId)
        assertEquals(0, results.size)
    }

    @Test
    fun stopsOnFailure() {
        val results = ArrayDeque(
            listOf(
                ReportingScheduler.ReportingResult(
                    outcome = ReportingScheduler.Outcome.Sent,
                    sampleCount = 2,
                    payloadBytes = 120,
                    batchId = "batch-1",
                ),
                ReportingScheduler.ReportingResult(
                    outcome = ReportingScheduler.Outcome.Failed,
                    error = "Could not reach the server.",
                    batchId = "batch-2",
                ),
                ReportingScheduler.ReportingResult(
                    outcome = ReportingScheduler.Outcome.Sent,
                    sampleCount = 1,
                    payloadBytes = 60,
                    batchId = "batch-3",
                ),
            ),
        )

        val result = ReportingDrain.drain { results.removeFirst() }

        assertEquals(ReportingScheduler.Outcome.Failed, result.outcome)
        assertEquals("batch-2", result.batchId)
        assertEquals("Could not reach the server.", result.error)
        assertEquals(1, results.size)
    }

    @Test
    fun stopsWhenSentResultMakesNoProgress() {
        val result = ReportingDrain.drain {
            ReportingScheduler.ReportingResult(
                outcome = ReportingScheduler.Outcome.Sent,
                sampleCount = 0,
                payloadBytes = 0,
                batchId = "batch-stuck",
            )
        }

        assertEquals(ReportingScheduler.Outcome.Failed, result.outcome)
        assertEquals("batch-stuck", result.batchId)
        assertEquals("Reporting made no progress.", result.error)
    }

    @Test
    fun stopsAfterMaxBatchesInOneDrain() {
        var calls = 0

        val result = ReportingDrain.drain(maxBatches = 2) {
            calls += 1
            ReportingScheduler.ReportingResult(
                outcome = ReportingScheduler.Outcome.Sent,
                sampleCount = 1,
                payloadBytes = 50,
                batchId = "batch-$calls",
            )
        }

        assertEquals(2, calls)
        assertEquals(ReportingScheduler.Outcome.Failed, result.outcome)
        assertEquals(2, result.sampleCount)
        assertEquals(100L, result.payloadBytes)
        assertEquals("batch-2", result.batchId)
        assertEquals("Reporting paused after sending 2 batches.", result.error)
    }
}
