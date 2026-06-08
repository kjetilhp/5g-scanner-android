package no.politiet.pit.fivegscanner.reporting

import no.politiet.pit.fivegscanner.AppConfig
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException

object ReportingEndpointVerifier {
    fun verify(endpointUrl: String): VerificationResult {
        val normalized = endpointUrl.trim()
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: return VerificationResult.Failure("Enter a valid HTTP or HTTPS URL.")
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            return VerificationResult.Failure("Enter a valid HTTP or HTTPS URL.")
        }

        val connection = runCatching {
            (URL(normalized).openConnection() as HttpURLConnection).apply {
                requestMethod = "OPTIONS"
                connectTimeout = AppConfig.Reporting.connectTimeoutMs
                readTimeout = AppConfig.Reporting.readTimeoutMs
                setRequestProperty("Accept", AppConfig.Reporting.contentType)
            }
        }.getOrElse {
            return VerificationResult.Failure("Enter a valid HTTP or HTTPS URL.")
        }

        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                return VerificationResult.Failure("The endpoint did not respond as expected.")
            }

            val allowedMethods = headerTokens(listOfNotNull(
                connection.getHeaderField("Allow"),
                connection.getHeaderField("Access-Control-Allow-Methods"),
            ))
            if ("POST" !in allowedMethods) {
                return VerificationResult.Failure("The endpoint does not advertise POST support.")
            }

            val acceptedTypes = headerTokens(listOfNotNull(connection.getHeaderField("Accept-Post")))
            if (acceptedTypes.isNotEmpty() &&
                AppConfig.Reporting.contentType.uppercase() !in acceptedTypes &&
                "*/*" !in acceptedTypes
            ) {
                return VerificationResult.Failure("The endpoint does not advertise JSONL support.")
            }

            VerificationResult.Success
        } catch (error: IOException) {
            VerificationResult.Failure(userMessageForException(error))
        } finally {
            connection.disconnect()
        }
    }

    private fun headerTokens(values: List<String>): Set<String> =
        values
            .flatMap { it.split(",") }
            .map { it.trim().substringBefore(";").trim() }
            .filter { it.isNotBlank() }
            .map { it.uppercase() }
            .toSet()

    private fun userMessageForException(error: IOException): String =
        when (error) {
            is UnknownHostException,
            is ConnectException,
            is SocketTimeoutException -> "Could not reach the endpoint."
            else -> "Could not reach the endpoint."
        }

    sealed interface VerificationResult {
        data object Success : VerificationResult
        data class Failure(val reason: String) : VerificationResult
    }
}
