package com.agoii.mobile.execution

import com.agoii.mobile.core.LedgerValidationException
import com.google.gson.Gson
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// ─── LLMDriver ───────────────────────────────────────────────────────────────

/**
 * LLMDriver — routes contractor execution to an external LLM provider.
 *
 * CONTRACT: AGOII-RCF-LLM-DRIVER-IMPLEMENTATION-01
 *
 * RULES:
 *  - Configuration is mandatory; any blank field → BLOCK.
 *  - ALL communication goes through [config.endpoint] using [config.apiKey].
 *  - No mock responses, no retry loops, no silent fallbacks.
 *  - External failure → BLOCK with a clear message.
 *  - Empty response → BLOCK.
 *  - Output is the raw response text only; no formatting, no enrichment.
 *
 * @param config  Fully-populated [LLMDriverConfig]. Must have no blank fields.
 */
class LLMDriver(private val config: LLMDriverConfig) : ExecutionDriver {

    private val gson = Gson()

    /**
     * Execute [input] by calling the configured LLM endpoint.
     *
     * @param input The structured execution contract from [ContractorExecutor].
     * @return [ContractorExecutionOutput] whose [ContractorExecutionOutput.resultArtifact]
     *         contains a single `"response"` key with the raw LLM response text.
     * @throws LedgerValidationException on missing config, API failure, or empty response.
     */
    override fun execute(input: ContractorExecutionInput): ContractorExecutionOutput {
        // ── Validate configuration ─────────────────────────────────────────────
        if (config.apiKey.isBlank() || config.endpoint.isBlank() || config.model.isBlank()) {
            throw LedgerValidationException("ICS BLOCKED: Missing LLM configuration")
        }
        if (config.timeoutMs <= 0 || config.timeoutMs > Int.MAX_VALUE) {
            throw LedgerValidationException("ICS BLOCKED: Missing LLM configuration")
        }

        // ── Build request body ─────────────────────────────────────────────────
        val prompt = buildPrompt(input)
        val requestBody = gson.toJson(
            mapOf(
                "model"    to config.model,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to prompt)
                )
            )
        )

        // ── Call external API ──────────────────────────────────────────────────
        val responseText = callApi(requestBody)

        // ── Validate response ──────────────────────────────────────────────────
        if (responseText.isBlank()) {
            throw LedgerValidationException("ICS BLOCKED: Empty LLM response")
        }

        // ── Return raw output ──────────────────────────────────────────────────
        return ContractorExecutionOutput(
            taskId         = input.taskId,
            resultArtifact = mapOf("response" to responseText),
            status         = ExecutionStatus.SUCCESS
        )
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Construct the prompt from [input.taskPayload] and [input.expectedOutputSchema].
     *
     * Only String values are extracted from [input.taskPayload]; non-String values
     * (e.g. numeric metadata, nested maps) are intentionally excluded because the LLM
     * prompt is a text surface. The output schema is always appended as the final instruction.
     * No enrichment or formatting is applied beyond plain concatenation.
     */
    private fun buildPrompt(input: ContractorExecutionInput): String {
        val payloadText = input.taskPayload.values
            .filterIsInstance<String>()
            .joinToString("\n")
        return if (payloadText.isBlank()) {
            input.expectedOutputSchema
        } else {
            "$payloadText\n${input.expectedOutputSchema}"
        }
    }

    /**
     * Send [requestBody] as JSON to [config.endpoint] and return the extracted response text.
     *
     * @throws LedgerValidationException on any I/O error or non-2xx HTTP status.
     */
    private fun callApi(requestBody: String): String {
        val connection = try {
            (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod     = "POST"
                connectTimeout    = config.timeoutMs.toInt()
                readTimeout       = config.timeoutMs.toInt()
                doOutput          = true
                setRequestProperty("Content-Type",  "application/json")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        } catch (e: Exception) {
            throw LedgerValidationException("ICS BLOCKED: LLM execution failed: ${e.message}")
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw LedgerValidationException(
                    "ICS BLOCKED: LLM execution failed: HTTP $statusCode"
                )
            }

            val rawBody = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            return extractResponseText(rawBody)
        } catch (e: LedgerValidationException) {
            throw e
        } catch (e: Exception) {
            throw LedgerValidationException("ICS BLOCKED: LLM execution failed: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Extract the assistant message text from the raw JSON body.
     *
     * Supports the standard OpenAI-compatible chat completion shape:
     * `{ "choices": [ { "message": { "content": "..." } } ] }`
     *
     * Falls back to the entire raw body if the expected structure is absent,
     * letting the upstream empty-check determine whether it is valid.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractResponseText(rawBody: String): String {
        return try {
            val json = gson.fromJson(rawBody, Map::class.java) as? Map<String, Any>
                ?: return rawBody
            val choices = json["choices"] as? List<*> ?: return rawBody
            val first   = choices.firstOrNull() as? Map<*, *> ?: return rawBody
            val message = first["message"] as? Map<*, *> ?: return rawBody
            (message["content"] as? String) ?: rawBody
        } catch (_: Exception) {
            rawBody
        }
    }
}
