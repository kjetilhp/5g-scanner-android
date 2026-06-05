package no.politiet.pit.reporting

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import no.politiet.pit.AppConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class ReportingEndpointVerifierTest {
    private val servers = mutableListOf<HttpServer>()

    @After
    fun stopServers() {
        servers.forEach { it.stop(0) }
        servers.clear()
    }

    @Test
    fun rejectsBlankAndNonHttpUrlsBeforeNetworkCheck() {
        assertFailure("", "Enter a valid HTTP or HTTPS URL.")
        assertFailure("ftp://example.com/report", "Enter a valid HTTP or HTTPS URL.")
        assertFailure("https:///missing-host", "Enter a valid HTTP or HTTPS URL.")
        assertFailure("not a url", "Enter a valid HTTP or HTTPS URL.")
    }

    @Test
    fun succeedsWhenOptionsAdvertisesPostAndJsonl() {
        val endpoint = testEndpoint(
            status = 204,
            headers = mapOf(
                "Allow" to "GET, OPTIONS, POST",
                "Accept-Post" to "application/x-ndjson; charset=utf-8",
            ),
        )

        assertEquals(ReportingEndpointVerifier.VerificationResult.Success, ReportingEndpointVerifier.verify(endpoint))
    }

    @Test
    fun trimsEndpointBeforeVerifying() {
        val endpoint = testEndpoint(
            status = 204,
            headers = mapOf("Access-Control-Allow-Methods" to "options, post"),
        )

        assertEquals(ReportingEndpointVerifier.VerificationResult.Success, ReportingEndpointVerifier.verify("  $endpoint  "))
    }

    @Test
    fun sendsOptionsWithJsonlAcceptHeader() {
        var requestMethod = ""
        var acceptHeader = ""
        val endpoint = testEndpoint(
            status = 204,
            headers = mapOf("Allow" to "POST"),
        ) { exchange ->
            requestMethod = exchange.requestMethod
            acceptHeader = exchange.requestHeaders.getFirst("Accept").orEmpty()
        }

        assertEquals(ReportingEndpointVerifier.VerificationResult.Success, ReportingEndpointVerifier.verify(endpoint))
        assertEquals("OPTIONS", requestMethod)
        assertEquals(AppConfig.Reporting.contentType, acceptHeader)
    }

    @Test
    fun rejectsNonSuccessOptionsResponse() {
        val endpoint = testEndpoint(
            status = 404,
            headers = mapOf("Allow" to "POST"),
        )

        assertFailure(endpoint, "The endpoint did not respond as expected.")
    }

    @Test
    fun rejectsEndpointThatDoesNotAdvertisePost() {
        val endpoint = testEndpoint(
            status = 204,
            headers = mapOf("Allow" to "GET, OPTIONS"),
        )

        assertFailure(endpoint, "The endpoint does not advertise POST support.")
    }

    @Test
    fun allowsMissingAcceptPostHeader() {
        val endpoint = testEndpoint(
            status = 204,
            headers = mapOf("Allow" to "POST"),
        )

        assertEquals(ReportingEndpointVerifier.VerificationResult.Success, ReportingEndpointVerifier.verify(endpoint))
    }

    @Test
    fun allowsWildcardAcceptPostHeader() {
        val endpoint = testEndpoint(
            status = 204,
            headers = mapOf(
                "Allow" to "POST",
                "Accept-Post" to "*/*",
            ),
        )

        assertEquals(ReportingEndpointVerifier.VerificationResult.Success, ReportingEndpointVerifier.verify(endpoint))
    }

    @Test
    fun rejectsEndpointThatAdvertisesDifferentPostMediaType() {
        val endpoint = testEndpoint(
            status = 204,
            headers = mapOf(
                "Allow" to "POST",
                "Accept-Post" to "application/json",
            ),
        )

        assertFailure(endpoint, "The endpoint does not advertise JSONL support.")
    }

    private fun assertFailure(endpoint: String, expectedReason: String) {
        val result = ReportingEndpointVerifier.verify(endpoint)
        assertTrue(result is ReportingEndpointVerifier.VerificationResult.Failure)
        assertEquals(expectedReason, (result as ReportingEndpointVerifier.VerificationResult.Failure).reason)
    }

    private fun testEndpoint(
        status: Int,
        headers: Map<String, String>,
        inspect: (HttpExchange) -> Unit = {},
    ): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/api/coverage-samples") { exchange ->
            inspect(exchange)
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/api/coverage-samples"
    }
}
