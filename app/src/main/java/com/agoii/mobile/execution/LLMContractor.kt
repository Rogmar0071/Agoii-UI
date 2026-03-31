package com.agoii.mobile.execution

import com.agoii.mobile.core.LedgerValidationException
import com.agoii.mobile.infrastructure.OpenAIClient
import com.agoii.mobile.infrastructure.OpenAIConfig
import com.google.gson.Gson

// ─── LLMContractor ───────────────────────────────────────────────────────────

/**
 * LLMContractor — pure intent adapter between the contractor layer and the communication layer.
 *
 * RESPONSIBILITY:
 *  - Accept structured [ContractorExecutionInput].
 *  - Translate into a generic LLM request body.
 *  - Delegate HTTP communication entirely to [OpenAIClient].
 *  - Translate the raw response into [ContractorExecutionOutput].
 *
 * RULES:
 *  - NO HttpURLConnection.
 *  - NO endpoints.
 *  - NO API keys.
 *  - NO BuildConfig.
 *  - NO provider-specific constants.
 *
 * CONTRACT: AGOII-RCF-EXTERNAL-COMMUNICATION-ISOLATION-01
 */
class LLMContractor(
    private val client: OpenAIClient,
    private val config: OpenAIConfig
) : ExecutionDriver {

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
                            "role"    to "user",
                            "content" to gson.toJson(payload)
                        )
                    )
                )
            )

            println("LLM REQUEST: $requestBody")

            val rawResponse = client.send(requestBody, config)

            if (rawResponse.isBlank()) {
                throw LedgerValidationException("ICS BLOCKED: Empty LLM response")
            }

            val responseText = extract(rawResponse)

            return ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = mapOf("response" to responseText),
                status         = ExecutionStatus.SUCCESS
            )
        } catch (e: Exception) {
            return ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = mapOf("response" to "LLM_ERROR:\n${e.stackTraceToString()}"),
                status         = ExecutionStatus.FAILURE,
                error          = e.stackTraceToString()
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extract(raw: String): String {
        return try {
            val json    = gson.fromJson(raw, Map::class.java) as Map<String, Any>
            val choices = json["choices"] as? List<*> ?: return raw
            val first   = choices.firstOrNull() as? Map<*, *> ?: return raw
            val message = first["message"] as? Map<*, *> ?: return raw
            message["content"] as? String ?: raw
        } catch (_: Exception) {
            raw
        }
    }
}
