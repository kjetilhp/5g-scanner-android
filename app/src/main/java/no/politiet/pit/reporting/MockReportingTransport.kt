package no.politiet.pit.reporting

class MockReportingTransport : ReportingTransport {
    override fun post(payload: ReportingPayload): ReportingTransportResult =
        ReportingTransportResult.Success
}
