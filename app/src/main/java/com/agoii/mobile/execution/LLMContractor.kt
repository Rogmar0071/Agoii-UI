package com.agoii.mobile.execution

import com.agoii.mobile.core.LedgerValidationException
import com.agoii.mobile.infrastructure.ConfigProvider
import com.agoii.mobile.infrastructure.OpenAIClient
import com.google.gson.Gson

class LLMContractor(private val client: OpenAIClient) : ExecutionDriver {

    private val gson = Gson()

    override fun execute(input: ContractorExecutionInput): ContractorExecutionOutput {
        try {
            val config = ConfigProvider.openAI()

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

            val raw = client.send(config, requestBody)
            val responseText = extract(raw)

            if (responseText.isBlank()) {
                throw LedgerValidationException("ICS BLOCKED: Empty LLM response")
            }

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
            // Parsing failed — return the raw response so the caller still has something to work with
            raw
        }
    }
}
