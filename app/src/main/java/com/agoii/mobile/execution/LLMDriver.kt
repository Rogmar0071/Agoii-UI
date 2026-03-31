package com.agoii.mobile.execution

import com.agoii.mobile.core.LedgerValidationException
import com.google.gson.Gson
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class LLMDriver(private val config: LLMDriverConfig) : ExecutionDriver {

    private val gson = Gson()

    override fun execute(input: ContractorExecutionInput): ContractorExecutionOutput {
        try {
            if (config.apiKey.isBlank() || config.endpoint.isBlank() || config.model.isBlank()) {
                throw LedgerValidationException("ICS BLOCKED: Missing LLM configuration")
            }

            val payload = input.toExecutionPayload()

            val requestBody = gson.toJson(
                mapOf(
                    "model" to config.model,
                    "messages" to listOf(
                        mapOf(
                            "role" to "user",
                            "content" to gson.toJson(payload)
                        )
                    )
                )
            )

            println("LLM URL: ${config.endpoint}")
            println("LLM REQUEST BODY:\n$requestBody")
            println(testNetwork())

            val responseText = callApi(requestBody)

            if (responseText.isBlank()) {
                throw LedgerValidationException("ICS BLOCKED: Empty LLM response")
            }

            return ContractorExecutionOutput(
                taskId = input.taskId,
                resultArtifact = mapOf("response" to responseText),
                status = ExecutionStatus.SUCCESS
            )
        } catch (e: Exception) {
            return ContractorExecutionOutput(
                taskId = input.taskId,
                resultArtifact = mapOf("response" to "LLM_ERROR:\n${e.stackTraceToString()}"),
                status = ExecutionStatus.FAILURE,
                error = e.stackTraceToString()
            )
        }
    }

    private fun callApi(requestBody: String): String {
        val connection = try {
            println("LLM CONNECTING...")
            (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod  = "POST"
                connectTimeout = config.timeoutMs.toInt()
                readTimeout    = config.timeoutMs.toInt()
                doOutput       = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        } catch (e: Exception) {
            throw LedgerValidationException("ICS BLOCKED: LLM connection failed:\n${e.stackTraceToString()}")
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                it.write(requestBody)
            }

            val code = connection.responseCode
            println("LLM STATUS: $code")

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: "NO_BODY"
            println("LLM RESPONSE RAW:\n$raw")

            if (code !in 200..299) {
                throw LedgerValidationException(
                    "LLM_HTTP_ERROR:\nCODE=$code\nBODY=$raw"
                )
            }

            return extract(raw)

        } catch (e: LedgerValidationException) {
            throw e
        } catch (e: Exception) {
            throw LedgerValidationException("ICS BLOCKED: LLM IO error:\n${e.stackTraceToString()}")
        } finally {
            connection.disconnect()
        }
    }

    private fun testNetwork(): String {
        return try {
            val url = URL("https://www.google.com")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            val code = connection.responseCode
            "NETWORK_OK: $code"
        } catch (e: Exception) {
            "NETWORK_FAIL:\n${e.stackTraceToString()}"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extract(raw: String): String {
        return try {
            val json = gson.fromJson(raw, Map::class.java) as Map<String, Any>
            val choices = json["choices"] as? List<*> ?: return raw
            val first = choices.firstOrNull() as? Map<*, *> ?: return raw
            val message = first["message"] as? Map<*, *> ?: return raw
            message["content"] as? String ?: raw
        } catch (_: Exception) {
            raw
        }
    }
}
