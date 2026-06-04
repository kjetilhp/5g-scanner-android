package no.politiet.pit.reporting

data class ReportingPayload(
    val batchId: String,
    val sampleCount: Int,
    val payloadBytes: Long,
    val jsonl: String,
)

interface ReportingTransport {
    fun post(payload: ReportingPayload): ReportingTransportResult
}

sealed interface ReportingTransportResult {
    data object Success : ReportingTransportResult
    data class Failure(
        val reason: String,
        val retryable: Boolean = true,
    ) : ReportingTransportResult
}
