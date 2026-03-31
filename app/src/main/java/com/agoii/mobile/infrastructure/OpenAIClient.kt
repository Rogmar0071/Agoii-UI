package com.agoii.mobile.infrastructure

import com.agoii.mobile.core.LedgerValidationException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// ─── OpenAIClient ─────────────────────────────────────────────────────────────

/**
 * OpenAIClient — sole infrastructure component responsible for HTTP communication.
 *
 * RESPONSIBILITY:
 *  - Perform HTTP POST request to the configured endpoint.
 *  - Apply required headers (Content-Type, Authorization).
 *  - Inject the API key from [OpenAIConfig].
 *  - Return the raw response body string.
 *
 * RULES:
 *  - NO business logic. Only transport.
 *  - Callers are responsible for request body construction and response parsing.
 *
 * CONTRACT: AGOII-RCF-EXTERNAL-COMMUNICATION-ISOLATION-01
 */
class OpenAIClient {

    /**
     * Send [requestBody] to the endpoint described by [config] and return the raw response.
     *
     * @param requestBody JSON string to POST.
     * @param config      Fully-populated [OpenAIConfig].
     * @return Raw HTTP response body.
     * @throws LedgerValidationException on connection failure, non-2xx status, or I/O error.
     */
    fun send(requestBody: String, config: OpenAIConfig): String {
        val connection = openConnection(config)

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
            }

            val code = connection.responseCode
            println("LLM STATUS: $code")

            if (code !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw LedgerValidationException(
                    "LLM_HTTP_ERROR:\nCODE=$code\nBODY=$errorBody"
                )
            }

            val raw = connection.inputStream.bufferedReader().readText()
            println("LLM RESPONSE RAW: $raw")
            return raw

        } catch (e: LedgerValidationException) {
            throw e
        } catch (e: Exception) {
            throw LedgerValidationException("ICS BLOCKED: LLM IO error:\n${e.stackTraceToString()}")
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(config: OpenAIConfig): HttpURLConnection {
        return try {
            (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod  = "POST"
                connectTimeout = config.timeoutMs.toInt()
                readTimeout    = config.timeoutMs.toInt()
                doOutput       = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        } catch (e: Exception) {
            throw LedgerValidationException(
                "ICS BLOCKED: LLM connection failed:\n${e.stackTraceToString()}"
            )
        }
    }
}
