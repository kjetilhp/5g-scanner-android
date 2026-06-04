package no.politiet.pit.reporting

import android.util.Log
import no.politiet.pit.AppConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class HttpReportingTransport(
    private val endpointUrl: String = AppConfig.Reporting.endpointUrl,
    private val connectTimeoutMs: Int = AppConfig.Reporting.connectTimeoutMs,
    private val readTimeoutMs: Int = AppConfig.Reporting.readTimeoutMs,
) : ReportingTransport {
    override fun post(payload: ReportingPayload): ReportingTransportResult {
        val connection = runCatching {
            (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", AppConfig.Reporting.contentType)
                setRequestProperty("X-Scanner-Batch-Id", payload.batchId)
                setRequestProperty("X-Scanner-Sample-Count", payload.sampleCount.toString())
                setRequestProperty("X-Scanner-Payload-Bytes", payload.payloadBytes.toString())
            }
        }.getOrElse { error ->
            Log.w(TAG, "Reporting endpoint configuration failed", error)
            return ReportingTransportResult.Failure("Reporting is not configured correctly.")
        }

        return try {
            val requestHeaders = connection.requestProperties
            connection.outputStream.use { output ->
                output.write(payload.jsonl.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val responseText = responseBody(connection).take(MAX_LOG_BODY_CHARS)
            if (status in 200..299) {
                Log.i(TAG, "Reporting POST $endpointUrl successful. ${payload.sampleCount} samples reported.")
                ReportingTransportResult.Success
            } else {
                val retryable = status == 408 || status == 429 || status >= 500
                Log.w(
                    TAG,
                    buildHttpFailureDebugMessage(
                        payload = payload,
                        requestHeaders = requestHeaders,
                        status = status,
                        responseText = responseText,
                        responseHeaders = connection.headerFields,
                    ),
                )
                ReportingTransportResult.Failure(
                    reason = userMessageForStatus(status),
                    retryable = retryable,
                )
            }
        } catch (error: IOException) {
            Log.w(TAG, "Reporting request failed", error)
            ReportingTransportResult.Failure(userMessageForException(error))
        } finally {
            connection.disconnect()
        }
    }

    private fun userMessageForStatus(status: Int): String =
        when (status) {
            400, 422 -> "The app sent coverage data the server did not accept."
            401, 403 -> "The server denied the request."
            else -> "The server responded in an unexpected way."
        }

    private fun userMessageForException(error: IOException): String =
        when (error) {
            is UnknownHostException,
            is ConnectException,
            is SocketTimeoutException -> "Could not reach the server."
            else -> "Could not reach the server."
        }

    private fun responseBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun buildHttpFailureDebugMessage(
        payload: ReportingPayload,
        requestHeaders: Map<String, List<String>>,
        status: Int,
        responseText: String,
        responseHeaders: Map<String?, List<String>>,
    ): String = buildString {
        appendLine("Reporting HTTP request failed")
        appendLine("POST $endpointUrl")
        appendLine("Payload: batchId=${payload.batchId}, samples=${payload.sampleCount}, bytes=${payload.payloadBytes}")
        appendLine("Request headers:")
        appendHeaders(requestHeaders)
        appendLine("Status: $status")
        appendLine("Response headers:")
        appendHeaders(responseHeaders)
        appendLine("Response body:")
        if (responseText.isNotBlank()) {
            append(responseText)
        } else {
            append("(empty)")
        }
    }

    private fun StringBuilder.appendHeaders(headers: Map<*, List<String>>) {
        val headerLines = headers
            .filter { (_, values) -> values.isNotEmpty() }
            .map { (name, values) ->
                val headerName = name?.toString() ?: "Status"
                "$headerName: ${values.joinToString(", ")}"
            }
        if (headerLines.isEmpty()) {
            appendLine("(none)")
        } else {
            headerLines.forEach(::appendLine)
        }
    }

    private companion object {
        const val TAG = "5GScanner"
        const val MAX_LOG_BODY_CHARS = 4_000
    }
}
