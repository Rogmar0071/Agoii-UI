package com.agoii.mobile.contractor.registry

import com.agoii.mobile.contractor.ContractorCapabilityVector
import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.VerificationStatus
import com.agoii.mobile.infrastructure.ConfigProvider
import com.agoii.mobile.infrastructure.OpenAIClient
import com.google.gson.Gson
import java.util.UUID

/**
 * HumanCommunicationContractor — governed LLM-based intent interpreter.
 *
 * Responsibilities:
 *  - Accept raw human-language input
 *  - Use LLM ONLY as a language interpreter to extract a structured intent
 *  - Return a strict structured object ready for ExecutionEntryPoint
 *
 * LLM role: interpreter ONLY — NOT executor.
 * Execution authority remains exclusively with NemoClaw.
 *
 * CONTRACT: INTERACTION_LAYER_V1
 */
object HumanCommunicationContractor {

    val PROFILE = ContractorProfile(
        id = "internal.human.communication",
        source = "human",
        status = VerificationStatus.VERIFIED,

        capabilities = ContractorCapabilityVector(
            constraintObedience = 3,
            structuralAccuracy  = 2,
            driftScore          = 2,
            complexityCapacity  = 2,
            reliability         = 3
        ),

        verificationCount = 1,
        successCount = 0,
        failureCount = 0,
        notes = listOf("Human interaction contractor")
    )

    private val gson   = Gson()
    private val client = OpenAIClient()

    private const val SYSTEM_PROMPT = """You are a strict intent parser for the Agoii execution system.
Convert the user's natural language input into a JSON object with EXACTLY these fields:
{
  "objective": "<clear, actionable, single-sentence goal derived from user input>",
  "intentId": "<a new UUID v4>",
  "interpretedMeaning": "<what the system believes the user wants, in one sentence>",
  "keyConstraints": ["<short constraint or risk>", "<short constraint or risk>"]
}
Rules:
- Respond with ONLY the JSON object. No explanation, no markdown, no code fences.
- The objective MUST be a single, clear, actionable sentence.
- The intentId MUST be a valid UUID v4.
- interpretedMeaning MUST be a faithful paraphrase of the request, not a new request.
- keyConstraints MUST be a JSON array of short strings. Use an empty array when there are no explicit constraints."""

    /**
     * Parse raw human-language input into a structured intent payload.
     *
     * Uses the LLM exclusively as a language interpreter — NOT as an executor.
     * The returned map is the ONLY sanctioned input format for ExecutionEntryPoint.
     *
     * SAFETY CONTRACT: this function MUST NEVER throw.  All LLM failures, network
     * errors, and JSON parse errors are fully contained and always produce a valid
     * fallback map.
     *
     * @param rawInput  Unstructured user text from the UI.
     * @return          Structured intent:
     *                  `{"objective": "...", "intentId": "...", "interpretedMeaning": "...", "keyConstraints": [...]}`.
     *                  Falls back to treating raw input as objective if LLM is
     *                  unavailable, times out, or returns an unparseable response.
     */
    fun parse(rawInput: String): Map<String, Any> {
        if (rawInput.isBlank()) return structuredFallback("unspecified")
        return try {
            val config = ConfigProvider.openAI()

            if (config.apiKey.isBlank() || config.endpoint.isBlank() || config.model.isBlank()) {
                return structuredFallback(rawInput)
            }

            val requestBody = gson.toJson(
                mapOf(
                    "model" to config.model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                        mapOf("role" to "user",   "content" to rawInput)
                    ),
                    "temperature" to 0
                )
            )

            val raw = client.send(config, requestBody)
            val content = extractContent(raw)

            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson(content, Map::class.java) as? Map<String, Any>
                ?: return structuredFallback(rawInput)

            val objective = (parsed["objective"] as? String)?.trim()
            if (objective.isNullOrBlank()) return structuredFallback(rawInput)

            val intentId = (parsed["intentId"] as? String)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()

            val interpretedMeaning = (parsed["interpretedMeaning"] as? String)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: objective

            val keyConstraints = resolveStringList(parsed["keyConstraints"])

            mapOf(
                "objective" to objective,
                "intentId"  to intentId,
                "interpretedMeaning" to interpretedMeaning,
                "keyConstraints" to keyConstraints
            )
        } catch (_: Throwable) {
            structuredFallback(rawInput)
        }
    }

    private fun structuredFallback(rawInput: String): Map<String, Any> =
        mapOf(
            "objective" to rawInput,
            "intentId"  to UUID.randomUUID().toString(),
            "interpretedMeaning" to rawInput,
            "keyConstraints" to emptyList<String>()
        )

    @Suppress("UNCHECKED_CAST")
    private fun extractContent(raw: String): String {
        return try {
            val json    = gson.fromJson(raw, Map::class.java) as? Map<String, Any> ?: return raw
            val choices = json["choices"] as? List<*> ?: return raw
            val first   = choices.firstOrNull() as? Map<*, *> ?: return raw
            val message = first["message"] as? Map<*, *> ?: return raw
            message["content"] as? String ?: raw
        } catch (_: Throwable) {
            raw
        }
    }

    private fun resolveStringList(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        is String -> value.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        else -> emptyList()
    }
}
