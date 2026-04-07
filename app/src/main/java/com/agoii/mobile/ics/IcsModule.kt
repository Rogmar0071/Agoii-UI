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

import android.util.Log
import com.agoii.mobile.assembly.ContractOutput
import com.agoii.mobile.assembly.FinalArtifact
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes

/**
 * ICS Module — deterministic transformer from [FinalArtifact] to [IcsOutput].
 *
 * Single public entry point: [process].
 *
 * Pipeline (happy path):
 *  1. Trigger check: last ledger event MUST be ICS_STARTED (written by Governor — AGOII-ALIGN-1)
 *  2. Extract ICS input fields from ICS_STARTED payload; read assembly metadata from ASSEMBLY_COMPLETED
 *  3. Idempotency guard: no ICS_COMPLETED for same report_reference
 *  4. Reconstruct [FinalArtifact] from ASSEMBLY_STARTED payload + TASK_EXECUTED events
 *  5. ICS_STARTED already written by Governor; no write here
 *  6. Structural normalization — remove execution-only metadata, standardize names
 *  7. Output classification — determine [IcsOutputType] per entry
 *  8. Structured packaging — group into deterministic sections
 *  9. Trace preservation — contractId + artifactReference on every entry
 * 10. Write ICS_COMPLETED (source=ICS_MODULE) to ledger
 * 11. Return [IcsExecutionResult.Processed]
 */
class IcsModule {

    // ── Intent Construction Step (MQP-INTENT-STEP-EXECUTION-v1) ──────────────

    /**
     * Run exactly one deterministic intent construction step for the given [contract].
     *
     * Emits exactly one INTENT_* event per invocation. No internal loop, no auto-approval,
     * no multi-event writes.
     *
     * Deterministic state machine:
     *   NONE               -> INTENT_PARTIAL_CREATED
     *   INTENT_PARTIAL_CREATED -> INTENT_IN_PROGRESS
     *   INTENT_IN_PROGRESS / INTENT_UPDATED:
     *       completeness < 1.0 -> INTENT_UPDATED or INTENT_IN_PROGRESS (LedgerAudit-safe)
     *       completeness >= 1.0 -> INTENT_COMPLETED when legal
     *   INTENT_COMPLETED   -> INTENT_APPROVAL_REQUESTED
     *   INTENT_APPROVAL_REQUESTED -> Blocked("AWAITING_APPROVAL")
     *   INTENT_APPROVED    -> AlreadyConstructed
     *   INTENT_REJECTED    -> Blocked("INTENT_REJECTED_REQUIRES_RESTART")
     *
     * Called exclusively by [ExecutionEntryPoint.executeIntent] before the intent
     * authority gate check.  No other module may call this method.
     *
     * @param projectId  Project ledger identifier.
     * @param contract   ICS contract describing the intent to construct.
     * @param ledger     [EventRepository] — sole write authority.
     * @return [IntentConstructionResult] — Constructed, AlreadyConstructed, or Blocked.
     */
    fun constructIntentStep(
        projectId: String,
        contract:  ICSContract,
        ledger:    EventRepository
    ): IntentConstructionResult {
        val events = ledger.loadEvents(projectId)
        val lastIntentEvent = events.lastOrNull { it.type in INTENT_AUTHORITY_TYPES }
        val lastType = lastIntentEvent?.type
        val completeness = resolveCompleteness(lastIntentEvent?.payload?.get("completeness"))

        val basePayload = mapOf(
            "intentId" to contract.intentId,
            "objective" to contract.userInput,
            "contractId" to contract.contractId
        ) + intentSummaryPayload(contract)

        return when (lastType) {
            null -> {
                appendIntentEvent(
                    projectId = projectId,
                    ledger = ledger,
                    type = EventTypes.INTENT_PARTIAL_CREATED,
                    payload = basePayload + mapOf("completeness" to 0.2)
                )
                IntentConstructionResult.Constructed(contract.intentId)
            }

            EventTypes.INTENT_PARTIAL_CREATED -> {
                appendIntentEvent(
                    projectId = projectId,
                    ledger = ledger,
                    type = EventTypes.INTENT_IN_PROGRESS,
                    payload = basePayload + mapOf("completeness" to 1.0)
                )
                IntentConstructionResult.Constructed(contract.intentId)
            }

            EventTypes.INTENT_IN_PROGRESS -> {
                if (completeness < 1.0) {
                    appendIntentEvent(
                        projectId = projectId,
                        ledger = ledger,
                        type = EventTypes.INTENT_UPDATED,
                        payload = basePayload + mapOf("completeness" to 1.0)
                    )
                } else {
                    appendIntentEvent(
                        projectId = projectId,
                        ledger = ledger,
                        type = EventTypes.INTENT_COMPLETED,
                        payload = basePayload + mapOf("completeness" to 1.0)
                    )
                }
                IntentConstructionResult.Constructed(contract.intentId)
            }

            EventTypes.INTENT_UPDATED -> {
                appendIntentEvent(
                    projectId = projectId,
                    ledger = ledger,
                    type = EventTypes.INTENT_IN_PROGRESS,
                    payload = basePayload + mapOf("completeness" to 1.0)
                )
                IntentConstructionResult.Constructed(contract.intentId)
            }

            EventTypes.INTENT_COMPLETED -> {
                appendIntentEvent(
                    projectId = projectId,
                    ledger = ledger,
                    type = EventTypes.INTENT_APPROVAL_REQUESTED,
                    payload = basePayload + mapOf("completeness" to 1.0)
                )
                IntentConstructionResult.Constructed(contract.intentId)
            }

            EventTypes.INTENT_APPROVAL_REQUESTED ->
                IntentConstructionResult.Blocked("AWAITING_APPROVAL")

            EventTypes.INTENT_APPROVED ->
                IntentConstructionResult.AlreadyConstructed(contract.intentId)

            EventTypes.INTENT_REJECTED ->
                IntentConstructionResult.Blocked("INTENT_REJECTED_REQUIRES_RESTART")

            else ->
                IntentConstructionResult.Blocked("UNSUPPORTED_INTENT_STATE:$lastType")
        }
    }

    private fun appendIntentEvent(
        projectId: String,
        ledger: EventRepository,
        type: String,
        payload: Map<String, Any>
    ) {
        Log.e("AGOII_TRACE", "[INTENT_CONSTRUCTION_STEP] type=$type intentId=${payload["intentId"]}")
        ledger.appendEvent(projectId, type, payload)
    }

    private fun resolveCompleteness(value: Any?): Double = when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun intentSummaryPayload(contract: ICSContract): Map<String, Any> {
        val interpretedMeaning = contract.contextSnapshot["interpretedMeaning"]?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: contract.userInput

        val keyConstraints = when (val raw = contract.contextSnapshot["keyConstraints"]) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            else -> emptyList()
        }
        val assumptions = when (val raw = contract.contextSnapshot["assumptions"]) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            else -> emptyList()
        }
        val uncertainties = when (val raw = contract.contextSnapshot["uncertainties"]) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            else -> emptyList()
        }
        val missingInformation = when (val raw = contract.contextSnapshot["missingInformation"]) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            else -> emptyList()
        }
        val failureRisks = when (val raw = contract.contextSnapshot["failureRisks"]) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            else -> emptyList()
        }

        return mapOf(
            "interpretedMeaning" to interpretedMeaning,
            "keyConstraints" to keyConstraints,
            "assumptions" to assumptions,
            "uncertainties" to uncertainties,
            "missingInformation" to missingInformation,
            "failureRisks" to failureRisks
        )
    }

    private companion object {
        val INTENT_AUTHORITY_TYPES = setOf(
            EventTypes.INTENT_PARTIAL_CREATED,
            EventTypes.INTENT_IN_PROGRESS,
            EventTypes.INTENT_UPDATED,
            EventTypes.INTENT_COMPLETED,
            EventTypes.INTENT_APPROVAL_REQUESTED,
            EventTypes.INTENT_APPROVED,
            EventTypes.INTENT_REJECTED
        )
    }

    // ── ICS Processing Pipeline ───────────────────────────────────────────────

    /**
     * Execute the ICS processing pipeline.
     *
     * @param projectId  Project ledger identifier.
     * @param ledger     [EventRepository] — sole write authority.
     * @return [IcsExecutionResult] describing the pipeline outcome.
     */
    fun process(projectId: String, ledger: EventRepository): IcsExecutionResult {

        val events = ledger.loadEvents(projectId)

        // ── Trigger condition 1: last event MUST be ICS_STARTED (AGOII-ALIGN-1) ──
        // Governor emits ICS_STARTED; IcsModule consumes it and emits ICS_COMPLETED.
        val lastEvent = events.lastOrNull()
        if (lastEvent?.type != EventTypes.ICS_STARTED) {
            return IcsExecutionResult.NotTriggered
        }

        // ── Trigger condition 2: required payload fields from ICS_STARTED ─────────
        val reportReference = lastEvent.payload["report_reference"]?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return IcsExecutionResult.NotTriggered

        val finalArtifactReference = lastEvent.payload["finalArtifactReference"]?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return IcsExecutionResult.NotTriggered

        val icsTaskId = lastEvent.payload["taskId"]?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: "ICS::$reportReference"

        // ── Resolve contractSetId and totalContracts from ASSEMBLY_COMPLETED ─────
        // ICS_STARTED carries only the ICS-specific fields; assembly metadata is read
        // from the ASSEMBLY_COMPLETED event in the ledger history (RRIL-1 traceability).
        val assemblyCompletedEvent = events.lastOrNull { it.type == EventTypes.ASSEMBLY_COMPLETED }
        val contractSetId   = assemblyCompletedEvent?.payload?.get("contractSetId")?.toString() ?: ""
        val totalContracts  = assemblyCompletedEvent?.payload?.get("totalContracts")
            ?.let { resolveInt(it) } ?: 0

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

        val icsOutputReference = buildIcsOutputReference(reportReference)

        // ── Steps 6–9: Build IcsOutput (normalize, classify, package, trace) ─
        // ICS_STARTED was already written by Governor (AGOII-ALIGN-1 RULE 3).
        val icsOutput = buildIcsOutput(icsInput, icsTaskId, icsOutputReference)

        // ── Step 10: Write ICS_COMPLETED (source=ICS_MODULE — AGOII-ALIGN-1) ─
        ledger.appendEvent(
            projectId,
            EventTypes.ICS_COMPLETED,
            mapOf(
                "report_reference"   to reportReference,
                "taskId"             to icsTaskId,
                "icsOutputReference" to icsOutputReference,
                "source"             to "ICS_MODULE"
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
        // artifactStructure is NOT persisted in the EventLedger (AERP-1 §3); only
        // artifactReference is legal to consume.
        val taskArtifacts = mutableMapOf<String, String>()
        events.filter { ev ->
            ev.type == EventTypes.TASK_EXECUTED &&
            ev.payload["executionStatus"]?.toString() == "SUCCESS" &&
            ev.payload["report_reference"]?.toString() == reportReference
        }.forEach { ev ->
            val contractId  = ev.payload["contractId"]?.toString()        ?: return@forEach
            val artifactRef = ev.payload["artifactReference"]?.toString() ?: return@forEach
            taskArtifacts[contractId] = artifactRef
        }

        val contractOutputs = orderedContracts.map { (contractId, position) ->
            val artifactRef = taskArtifacts[contractId]
                // Prefix "diagnostic:" signals a data gap: no TASK_EXECUTED(SUCCESS) found for this
                // contract. The IcsOutputType classifier will detect this as DIAGNOSTIC so callers
                // can surface the gap rather than treating it as valid output.
                ?: "diagnostic:$contractId:no_artifact"
            com.agoii.mobile.assembly.ContractOutput(
                contractId        = contractId,
                position          = position,
                reportReference   = reportReference,
                artifactReference = artifactRef
            )
        }

        val traceMap: Map<String, String> = orderedContracts.associate { (contractId, _) ->
            contractId to reportReference
        }
        return FinalArtifact(
            reportReference = reportReference,
            contractSetId   = contractSetId,
            totalContracts  = totalContracts,
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

        // ── Step 6 + 7 + 9: Classify and preserve trace ─────────────────────
        // No structural normalization: artifactStructure is not in the ledger (AERP-1 §3).
        val entries: List<IcsOutputEntry> = input.finalArtifact.contractOutputs.map { contractOutput ->
            val outputType = classifyOutput(contractOutput)

            IcsOutputEntry(
                contractId        = contractOutput.contractId,
                position          = contractOutput.position,
                artifactReference = contractOutput.artifactReference,
                outputType        = outputType,
                fields            = emptyMap()
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
     * Output classification — deterministic, rule-based:
     *  - DIAGNOSTIC  if the artifact reference starts with "diagnostic:" or contains
     *                "NO_ARTIFACT" or "FAILURE" (covers gap-detected and explicit failure refs)
     *  - INFORMATIONAL otherwise (default)
     *
     * ACTIONABLE classification is no longer possible: it relied on normalizedFields keys
     * derived from artifactStructure, which is not persisted in the EventLedger (AERP-1 §3).
     *
     * No AI, no inference, no summarization.
     */
    private fun classifyOutput(contractOutput: ContractOutput): IcsOutputType {
        val artifactRef = contractOutput.artifactReference.lowercase()
        if (artifactRef.startsWith("diagnostic:") ||
            artifactRef.contains("no_artifact") ||
            artifactRef.contains("failure")
        ) {
            return IcsOutputType.DIAGNOSTIC
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
