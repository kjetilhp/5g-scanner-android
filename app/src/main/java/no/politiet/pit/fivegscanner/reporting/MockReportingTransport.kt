package no.politiet.pit.fivegscanner.reporting

class MockReportingTransport : ReportingTransport {
    override fun post(payload: ReportingPayload): ReportingTransportResult =
        ReportingTransportResult.Success
}
