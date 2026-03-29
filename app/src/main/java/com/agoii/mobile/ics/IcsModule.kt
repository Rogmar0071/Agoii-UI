// AGOII CONTRACT — ICS MODULE (INFORMATION & COMMUNICATION SYSTEM)
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Transforms FinalArtifact into a deterministic, structured, externally consumable output.
//
// TRIGGER CONDITIONS (enforced strictly):
//   1. Last ledger event == ASSEMBLY_COMPLETED
//   2. ASSEMBLY_COMPLETED payload contains report_reference AND finalArtifactReference
//   3. NO ICS_COMPLETED exists for same report_reference
//
// EXECUTION AUTHORITY:
//   ICS is invoked ONLY by ExecutionAuthority. No other module may call process().
//
// LEDGER AUTHORITY:
//   ALL reads and writes operate exclusively through EventLedger.
//   No external memory; no inference; no external orchestrators.
//
// PROCESSING RULES:
//   - NO interpretation
//   - NO AI rewriting
//   - NO summarization outside defined structure
//   - NO mutation of original data

package com.agoii.mobile.ics

import com.agoii.mobile.assembly.ContractOutput
import com.agoii.mobile.assembly.FinalArtifact
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes

/**
 * ICS Module — deterministic transformer from [FinalArtifact] to [IcsOutput].
 *
 * Single public entry point: [process].
 *
 * Pipeline (happy path):
 *  1. Trigger check: last ledger event MUST be ASSEMBLY_COMPLETED
 *  2. Extract ICS input fields from ASSEMBLY_COMPLETED payload (RRIL-1, ledger-only)
 *  3. Idempotency guard: no ICS_COMPLETED for same report_reference
 *  4. Reconstruct [FinalArtifact] from ASSEMBLY_STARTED payload + TASK_EXECUTED events
 *  5. Write ICS_STARTED to ledger
 *  6. Structural normalization — remove execution-only metadata, standardize names
 *  7. Output classification — determine [IcsOutputType] per entry
 *  8. Structured packaging — group into deterministic sections
 *  9. Trace preservation — contractId + artifactReference on every entry
 * 10. Write ICS_COMPLETED to ledger
 * 11. Return [IcsExecutionResult.Processed]
 */
class IcsModule {

    /**
     * Execute the ICS processing pipeline.
     *
     * @param projectId  Project ledger identifier.
     * @param ledger     EventLedger — sole write authority.
     * @return [IcsExecutionResult] describing the pipeline outcome.
     */
    fun process(projectId: String, ledger: EventLedger): IcsExecutionResult {

        val events = ledger.loadEvents(projectId)

        // ── Trigger condition 1: last event MUST be ASSEMBLY_COMPLETED ────────
        val lastEvent = events.lastOrNull()
        if (lastEvent?.type != EventTypes.ASSEMBLY_COMPLETED) {
            return IcsExecutionResult.NotTriggered
        }

        // ── Trigger condition 2: required payload fields ───────────────────────
        val reportReference = lastEvent.payload["report_reference"]?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return IcsExecutionResult.NotTriggered

        val finalArtifactReference = lastEvent.payload["finalArtifactReference"]?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return IcsExecutionResult.NotTriggered

        val contractSetId = lastEvent.payload["contractSetId"]?.toString() ?: ""
        val totalContracts = resolveInt(lastEvent.payload["totalContracts"]) ?: 0

        // ── Trigger condition 3: idempotency guard ────────────────────────────
        val alreadyCompleted = events.any { ev ->
            ev.type == EventTypes.ICS_COMPLETED &&
            ev.payload["report_reference"]?.toString() == reportReference
        }
        if (alreadyCompleted) {
            return IcsExecutionResult.AlreadyCompleted(reportReference)
        }

        // ── Reconstruct FinalArtifact from ledger (RRIL-1) ────────────────────
        val finalArtifact = reconstructFinalArtifact(
            events               = events,
            reportReference      = reportReference,
            contractSetId        = contractSetId,
            totalContracts       = totalContracts,
            finalArtifactReference = finalArtifactReference
        ) ?: return IcsExecutionResult.Blocked(
            "FINAL_ARTIFACT_RECONSTRUCTION_FAILED: unable to derive FinalArtifact " +
            "for report_reference='$reportReference' from ledger"
        )

        // ── Construct IcsInput ────────────────────────────────────────────────
        val icsInput = IcsInput(
            reportReference        = reportReference,
            contractSetId          = contractSetId,
            totalContracts         = totalContracts,
            finalArtifactReference = finalArtifactReference,
            finalArtifact          = finalArtifact
        )

        val icsTaskId          = "ICS::$reportReference"
        val icsOutputReference = buildIcsOutputReference(reportReference)

        // ── Step 5: Write ICS_STARTED ────────────────────────────────────────
        ledger.appendEvent(
            projectId,
            EventTypes.ICS_STARTED,
            mapOf(
                "report_reference"       to reportReference,
                "finalArtifactReference" to finalArtifactReference,
                "taskId"                 to icsTaskId
            )
        )

        // ── Steps 6–9: Build IcsOutput (normalize, classify, package, trace) ─
        val icsOutput = buildIcsOutput(icsInput, icsTaskId, icsOutputReference)

        // ── Step 10: Write ICS_COMPLETED ─────────────────────────────────────
        ledger.appendEvent(
            projectId,
            EventTypes.ICS_COMPLETED,
            mapOf(
                "report_reference"  to reportReference,
                "taskId"            to icsTaskId,
                "icsOutputReference" to icsOutputReference
            )
        )

        return IcsExecutionResult.Processed(icsOutput)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Reconstruct the [FinalArtifact] from the ledger exclusively.
     *
     * Order of contract reconstruction:
     *  1. ASSEMBLY_STARTED payload (most authoritative — written by AssemblyModule)
     *  2. Fallback: CONTRACTS_GENERATED event's contracts list
     *  3. Fallback: CONTRACT_STARTED events
     *
     * Task artifacts are sourced from the most recent TASK_EXECUTED(SUCCESS) per contractId,
     * scoped to [reportReference] for RRIL-1 lineage.
     *
     * Returns null when the ledger lacks sufficient data to reconstruct the artifact.
     */
    private fun reconstructFinalArtifact(
        events:                List<com.agoii.mobile.core.Event>,
        reportReference:       String,
        contractSetId:         String,
        totalContracts:        Int,
        finalArtifactReference: String
    ): FinalArtifact? {

        // Derive ordered contracts — prefer CONTRACTS_GENERATED contracts list
        val contractsGenEvent = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
        val contractsList     = contractsGenEvent?.payload?.get("contracts") as? List<*>

        val orderedContracts: List<Pair<String, Int>> = when {
            !contractsList.isNullOrEmpty() ->
                contractsList.filterIsInstance<Map<*, *>>()
                    .mapNotNull { map ->
                        val cId = map["contractId"]?.toString() ?: return@mapNotNull null
                        val pos = resolveInt(map["position"])   ?: return@mapNotNull null
                        cId to pos
                    }
                    .sortedBy { it.second }

            else ->
                events.filter { it.type == EventTypes.CONTRACT_STARTED }
                    .mapNotNull { ev ->
                        val cId = ev.payload["contract_id"]?.toString() ?: return@mapNotNull null
                        val pos = resolveInt(ev.payload["position"])    ?: return@mapNotNull null
                        cId to pos
                    }
                    .sortedBy { it.second }
        }

        if (orderedContracts.isEmpty()) return null

        // Derive task artifacts from successful TASK_EXECUTED events (RRIL-1)
        val taskArtifacts = mutableMapOf<String, String>()
        val taskArtifactStructures = mutableMapOf<String, Map<String, Any>>()
        events.filter { ev ->
            ev.type == EventTypes.TASK_EXECUTED &&
            ev.payload["executionStatus"]?.toString() == "SUCCESS" &&
            ev.payload["report_reference"]?.toString() == reportReference
        }.forEach { ev ->
            val contractId   = ev.payload["contractId"]?.toString()      ?: return@forEach
            val artifactRef  = ev.payload["artifactReference"]?.toString() ?: return@forEach
            @Suppress("UNCHECKED_CAST")
            val artifactStruct = ev.payload["artifactStructure"] as? Map<String, Any>
            taskArtifacts[contractId] = artifactRef
            if (artifactStruct != null) taskArtifactStructures[contractId] = artifactStruct
        }

        val contractOutputs = orderedContracts.map { (contractId, position) ->
            val artifactRef = taskArtifacts[contractId]
                // Prefix "diagnostic:" signals a data gap: no TASK_EXECUTED(SUCCESS) found for this
                // contract. The IcsOutputType classifier will detect this as DIAGNOSTIC so callers
                // can surface the gap rather than treating it as valid output.
                ?: "diagnostic:$contractId:no_artifact"
            val artifactStruct = taskArtifactStructures[contractId]
                ?: mapOf("contractId" to contractId, "position" to position)
            com.agoii.mobile.assembly.ContractOutput(
                contractId        = contractId,
                position          = position,
                reportReference   = reportReference,
                artifactReference = artifactRef,
                artifactStructure = artifactStruct
            )
        }

        val traceMap: Map<String, String> = orderedContracts.associate { (contractId, _) ->
            contractId to reportReference
        }
        return FinalArtifact(
            reportReference = reportReference,
            contractOutputs = contractOutputs,
            traceMap        = traceMap
        )
    }

    /**
     * Transform [IcsInput] into a structured [IcsOutput].
     *
     * Processing steps:
     *  1. Structural normalization: remove execution-only keys ("contractId", "position"),
     *     keep only externally meaningful fields.
     *  2. Output classification: determine [IcsOutputType] per contract output.
     *  3. Structured packaging: build deterministic sections.
     *  4. Trace preservation: contractId + artifactReference retained on every entry.
     */
    private fun buildIcsOutput(
        input:              IcsInput,
        icsTaskId:          String,
        icsOutputReference: String
    ): IcsOutput {

        // ── Step 6 + 7 + 9: Normalize, classify, preserve trace ─────────────
        val entries: List<IcsOutputEntry> = input.finalArtifact.contractOutputs.map { contractOutput ->
            val normalizedFields = normalizeFields(contractOutput)
            val outputType       = classifyOutput(contractOutput, normalizedFields)

            IcsOutputEntry(
                contractId        = contractOutput.contractId,
                position          = contractOutput.position,
                artifactReference = contractOutput.artifactReference,
                outputType        = outputType,
                fields            = normalizedFields
            )
        }

        // ── Step 8: Structured packaging ─────────────────────────────────────
        val sections = buildSections(entries)

        return IcsOutput(
            reportReference    = input.reportReference,
            taskId             = icsTaskId,
            icsOutputReference = icsOutputReference,
            totalContracts     = input.totalContracts,
            entries            = entries,
            sections           = sections
        )
    }

    /**
     * Structural normalization:
     *  - Remove execution-only metadata keys ("contractId", "position").
     *  - Standardize key names to snake_case externally consumable form.
     *  - Retain all other fields verbatim (NO mutation, NO rewriting).
     */
    private fun normalizeFields(contractOutput: ContractOutput): Map<String, Any> {
        val executionOnlyKeys = setOf("contractId", "position")
        return contractOutput.artifactStructure
            .filterKeys { it !in executionOnlyKeys }
            .mapKeys { (key, _) -> toSnakeCase(key) }
    }

    /**
     * Output classification — deterministic, rule-based:
     *  - DIAGNOSTIC  if the artifact reference starts with "diagnostic:" or contains
     *                "NO_ARTIFACT" or "FAILURE" (covers gap-detected and explicit failure refs)
     *  - ACTIONABLE  if the normalized fields contain an "action", "next_step",
     *                or "action_required" key
     *  - INFORMATIONAL otherwise (default)
     *
     * No AI, no inference, no summarization.
     */
    private fun classifyOutput(
        contractOutput: ContractOutput,
        normalizedFields: Map<String, Any>
    ): IcsOutputType {
        val artifactRef = contractOutput.artifactReference.lowercase()
        if (artifactRef.startsWith("diagnostic:") ||
            artifactRef.contains("no_artifact") ||
            artifactRef.contains("failure")
        ) {
            return IcsOutputType.DIAGNOSTIC
        }
        val keys = normalizedFields.keys.map { it.lowercase() }
        if (keys.any { it == "action" || it == "next_step" || it == "action_required" }) {
            return IcsOutputType.ACTIONABLE
        }
        return IcsOutputType.INFORMATIONAL
    }

    /**
     * Build the deterministic [IcsOutputSection] list.
     *
     * Always present:
     *  - "summary"  — first INFORMATIONAL entry, or absent when all entries are non-informational
     *  - "details"  — all INFORMATIONAL entries (may be empty when all entries are DIAGNOSTIC/ACTIONABLE)
     *
     * Present only when non-empty:
     *  - "actions"     — all ACTIONABLE entries
     *  - "diagnostics" — all DIAGNOSTIC entries
     *
     * Note: when no INFORMATIONAL entries exist, the "summary" section is omitted entirely
     * rather than promoting a DIAGNOSTIC or ACTIONABLE entry, to avoid misrepresenting
     * the output type.
     */
    private fun buildSections(entries: List<IcsOutputEntry>): List<IcsOutputSection> {
        val informational = entries.filter { it.outputType == IcsOutputType.INFORMATIONAL }
        val actionable    = entries.filter { it.outputType == IcsOutputType.ACTIONABLE }
        val diagnostic    = entries.filter { it.outputType == IcsOutputType.DIAGNOSTIC }

        val sections = mutableListOf<IcsOutputSection>()

        // summary — first INFORMATIONAL entry only; omit when none are informational
        val summaryEntry = informational.firstOrNull()
        if (summaryEntry != null) {
            sections.add(IcsOutputSection(name = "summary", entries = listOf(summaryEntry)))
        }

        // details — all informational entries (may be empty)
        sections.add(IcsOutputSection(name = "details", entries = informational))

        // actions — only when non-empty
        if (actionable.isNotEmpty()) {
            sections.add(IcsOutputSection(name = "actions", entries = actionable))
        }

        // diagnostics — only when non-empty
        if (diagnostic.isNotEmpty()) {
            sections.add(IcsOutputSection(name = "diagnostics", entries = diagnostic))
        }

        return sections
    }

    /**
     * Convert camelCase or PascalCase key to snake_case.
     * Already-snake_case keys are returned unchanged.
     */
    private fun toSnakeCase(key: String): String = buildString {
        key.forEachIndexed { i, ch ->
            if (ch.isUpperCase() && i > 0) append('_')
            append(ch.lowercaseChar())
        }
    }

    /**
     * Build deterministic ICS output reference.
     *
     * Format: `ics:<reportReference>`
     */
    private fun buildIcsOutputReference(reportReference: String): String =
        "ics:$reportReference"

    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}
