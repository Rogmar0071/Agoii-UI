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

            println("LLM REQUEST: $requestBody")

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
            e.printStackTrace()
            throw e
        }
    }

    private fun callApi(requestBody: String): String {
        val connection = try {
            (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod  = "POST"
                connectTimeout = config.timeoutMs.toInt()
                readTimeout    = config.timeoutMs.toInt()
                doOutput       = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        } catch (e: Exception) {
            throw LedgerValidationException("ICS BLOCKED: LLM execution failed: ${e.message}")
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                it.write(requestBody)
            }

            val code = connection.responseCode
            println("LLM STATUS: $code")
            if (code !in 200..299) {
                throw LedgerValidationException("ICS BLOCKED: LLM execution failed: HTTP $code")
            }

            val raw = connection.inputStream.bufferedReader().readText()
            println("LLM RESPONSE RAW: $raw")

            return extract(raw)

        } catch (e: LedgerValidationException) {
            throw e
        } catch (e: Exception) {
            throw LedgerValidationException("ICS BLOCKED: LLM execution failed: ${e.message}")
        } finally {
            connection.disconnect()
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
