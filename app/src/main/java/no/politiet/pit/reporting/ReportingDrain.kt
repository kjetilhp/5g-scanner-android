package no.politiet.pit.reporting

import no.politiet.pit.AppConfig

object ReportingDrain {
    fun drain(
        maxBatches: Int = AppConfig.Reporting.maxBatchesPerDrain,
        sendNextBatch: () -> ReportingScheduler.ReportingResult,
    ): ReportingScheduler.ReportingResult {
        var sentSamples = 0
        var sentBytes = 0L
        var lastBatchId: String? = null
        var sentBatches = 0

        while (true) {
            val result = sendNextBatch()
            when (result.outcome) {
                ReportingScheduler.Outcome.Sent -> {
                    if (result.sampleCount <= 0 && result.payloadBytes <= 0) {
                        return ReportingScheduler.ReportingResult(
                            outcome = ReportingScheduler.Outcome.Failed,
                            batchId = result.batchId,
                            error = "Reporting made no progress.",
                        )
                    }
                    sentSamples += result.sampleCount
                    sentBytes += result.payloadBytes
                    lastBatchId = result.batchId
                    sentBatches += 1
                    if (sentBatches >= maxBatches) {
                        return ReportingScheduler.ReportingResult(
                            outcome = ReportingScheduler.Outcome.Failed,
                            sampleCount = sentSamples,
                            payloadBytes = sentBytes,
                            batchId = lastBatchId,
                            error = "Reporting paused after sending $sentBatches batches.",
                        )
                    }
                }
                ReportingScheduler.Outcome.NoSamples -> {
                    return if (sentSamples > 0) {
                        ReportingScheduler.ReportingResult(
                            outcome = ReportingScheduler.Outcome.Sent,
                            sampleCount = sentSamples,
                            payloadBytes = sentBytes,
                            batchId = lastBatchId,
                        )
                    } else {
                        result
                    }
                }
                ReportingScheduler.Outcome.Failed -> return result
            }
        }
    }
}
