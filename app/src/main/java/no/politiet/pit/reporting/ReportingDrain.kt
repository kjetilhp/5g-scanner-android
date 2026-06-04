package no.politiet.pit.reporting

object ReportingDrain {
    fun drain(sendNextBatch: () -> ReportingScheduler.ReportingResult): ReportingScheduler.ReportingResult {
        var sentSamples = 0
        var sentBytes = 0L
        var lastBatchId: String? = null

        while (true) {
            val result = sendNextBatch()
            when (result.outcome) {
                ReportingScheduler.Outcome.Sent -> {
                    sentSamples += result.sampleCount
                    sentBytes += result.payloadBytes
                    lastBatchId = result.batchId
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
