package com.agoii.mobile.execution

import com.agoii.mobile.core.LedgerValidationException
import com.agoii.mobile.infrastructure.ConfigProvider
import com.agoii.mobile.infrastructure.NemoClawClient
import com.google.gson.Gson

class NemoClawContractor(private val client: NemoClawClient) : ExecutionDriver {

    private val gson = Gson()

    override fun execute(input: ContractorExecutionInput): ContractorExecutionOutput {
        try {
            val config = ConfigProvider.nemoClaw()

            if (config.apiKey.isBlank() || config.endpoint.isBlank()) {
                throw LedgerValidationException("NEMOCLAW BLOCKED: Missing NemoClaw configuration")
            }

            val payload = input.toExecutionPayload()
            val requestBody = gson.toJson(payload)

            val raw = client.send(config, requestBody)
            val responseText = extract(raw)

            if (responseText.isBlank()) {
                throw LedgerValidationException("NEMOCLAW BLOCKED: Empty NemoClaw response")
            }

            return ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = mapOf("response" to responseText),
                status         = ExecutionStatus.SUCCESS
            )
        } catch (_: Exception) {
            return ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = emptyMap(),
                status         = ExecutionStatus.FAILURE,
                error          = "Execution failed"
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extract(raw: String): String {
        return try {
            val json = gson.fromJson(raw, Map::class.java) as Map<String, Any>
            (json["result"] as? String)
                ?: (json["response"] as? String)
                ?: raw
        } catch (_: Exception) {
            raw
        }
    }
}
