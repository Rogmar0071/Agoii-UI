// AGOII CONTRACT — ASSEMBLY MODULE
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Deterministic reducer that consumes ALL CONTRACT_COMPLETED outputs for a given
// execution chain and produces a single, structured FINAL_ARTIFACT.
//
// TRIGGER CONDITIONS (enforced strictly — internal gates only, no caller reliance):
//   1. EXECUTION_COMPLETED exists in the ledger                            (spec 5.4)
//   2. COUNT(CONTRACT_COMPLETED) == totalContracts declared in             (spec 5.5)
//      CONTRACTS_GENERATED, AND all positions ∈ [1..N] with no gaps/dupes
//   3. NO ASSEMBLY_COMPLETED exists for the same report_reference
//   4. CONTRACTS_GENERATED event exists (source of metadata: RRID,
//      contractSetId, totalContracts)
//
// AUTHORITY RULES:
//   - Ordering and contract identity: CONTRACT_COMPLETED events             (spec 5.1)
//   - Artifact surface: TASK_EXECUTED.artifactStructure (frozen, ledger)   (spec 5.2)
//   - RRID lineage: every CONTRACT_COMPLETED.report_reference must match    (spec 5.3)
//   - Missing TASK_EXECUTED SUCCESS → BLOCK: INCOMPLETE_EXECUTION_SURFACE  (spec 5.6)
//   - RRID mismatch → BLOCK: RRID_VIOLATION                                (spec 5.3)
//
// EXECUTION AUTHORITY:
//   Assembly is invoked ONLY by ExecutionAuthority. Governor MUST NOT call assemble().
//
// LEDGER AUTHORITY:
//   ALL reads and writes operate exclusively through EventLedger.
//   No external memory; no inference; no external orchestrators.

package com.agoii.mobile.assembly

import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes

/**
 * Assembly Module — deterministic reducer over all CONTRACT_COMPLETED outputs.
 *
 * Single public entry point: [assemble].
 *
 * Pipeline (happy path):
 *  1. Verify EXECUTION_COMPLETED exists (internal gate — spec 5.4)
 *  2. Derive metadata (RRID, contractSetId, totalContracts) from CONTRACTS_GENERATED
 *  3. Idempotency guard — no duplicate ASSEMBLY_COMPLETED for same RRID
 *  4. Reconstruct ordered contracts from CONTRACT_COMPLETED events (spec 5.1)
 *  5. Enforce RRID lineage on every CONTRACT_COMPLETED (spec 5.3)
 *  6. Enforce position completeness: [1..N], no gaps, no duplicates (spec 5.5)
 *  7. Derive execution surface (artifactReference + artifactStructure) from
 *     TASK_EXECUTED(SUCCESS) events (spec 5.2 / spec 5.6)
 *  8. Pre-flight: verify every contract has a SUCCESS execution surface (spec 5.6)
 *  9. Append ASSEMBLY_STARTED to ledger
 * 10. Build [FinalArtifact] with traceMap (position 1 → N, spec 6.1/6.2)
 * 11. Append ASSEMBLY_VALIDATED to ledger
 * 12. Build [AssemblyContractReport] (AERP-1)
 * 13. Append ASSEMBLY_COMPLETED to ledger (spec 6.3)
 * 14. Return [AssemblyExecutionResult.Assembled]
 */
class AssemblyModule {

    /**
     * Execute the assembly pipeline.
     *
     * Reads the full ledger for [projectId], enforces all trigger conditions
     * internally (no reliance on caller), and — when conditions are met — appends
     * ASSEMBLY_STARTED, ASSEMBLY_VALIDATED, and ASSEMBLY_COMPLETED to [ledger],
     * then returns the [FinalArtifact].
     *
     * This method is a pure function of the ledger state: same ledger state always
     * produces the same result.  It has no implicit inputs and performs no inference.
     *
     * @param projectId  Project ledger identifier.
     * @param ledger     EventLedger — sole write authority.
     * @return [AssemblyExecutionResult] describing the pipeline outcome.
     */
    fun assemble(projectId: String, ledger: EventLedger): AssemblyExecutionResult {

        val events = ledger.loadEvents(projectId)

        // ── Step 1: Internal EXECUTION_COMPLETED gate (spec 5.4) ────────────
        val hasExecutionCompleted = events.any { it.type == EventTypes.EXECUTION_COMPLETED }
        if (!hasExecutionCompleted) return AssemblyExecutionResult.NotTriggered

        // ── Step 2: Derive metadata from CONTRACTS_GENERATED ────────────────
        val contractsGenEvent = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
            ?: return AssemblyExecutionResult.NotTriggered

        val reportReference = contractsGenEvent.payload["report_id"]?.toString()
            ?: contractsGenEvent.payload["report_reference"]?.toString()
            ?: ""

        if (reportReference.isBlank()) return AssemblyExecutionResult.NotTriggered

        val contractSetId = contractsGenEvent.payload["contractSetId"]?.toString() ?: ""
        if (contractSetId.isBlank()) return AssemblyExecutionResult.NotTriggered

        val contractsList = contractsGenEvent.payload["contracts"] as? List<*>
        val totalContracts: Int = when {
            !contractsList.isNullOrEmpty() -> contractsList.size
            else -> resolveInt(contractsGenEvent.payload["total"]) ?: 0
        }
        if (totalContracts < 1) return AssemblyExecutionResult.NotTriggered

        // ── Step 3: Idempotency guard ────────────────────────────────────────
        val alreadyCompleted = events.any { ev ->
            ev.type == EventTypes.ASSEMBLY_COMPLETED &&
            ev.payload["report_reference"]?.toString() == reportReference
        }
        if (alreadyCompleted) {
            return AssemblyExecutionResult.AlreadyCompleted(reportReference)
        }

        // ── Step 4: Reconstruct ordered contracts from CONTRACT_COMPLETED (spec 5.1) ──
        val completedEvents = events.filter { it.type == EventTypes.CONTRACT_COMPLETED }

        // Trigger condition: all contracts must be completed (spec 5.5 count check)
        if (completedEvents.size < totalContracts) {
            return AssemblyExecutionResult.NotTriggered
        }

        val assemblyContracts = completedEvents.mapNotNull { ev ->
            val contractId      = ev.payload["contractId"]?.toString()      ?: return@mapNotNull null
            val position        = resolveInt(ev.payload["position"])         ?: return@mapNotNull null
            val contractRrid    = ev.payload["report_reference"]?.toString() ?: ""
            AssemblyContract(
                contractId      = contractId,
                position        = position,
                reportReference = contractRrid
            )
        }

        // ── Step 5: RRID lineage enforcement (spec 5.3) ──────────────────────
        val rridViolations = assemblyContracts.filter { c ->
            c.reportReference.isNotBlank() && c.reportReference != reportReference
        }
        if (rridViolations.isNotEmpty()) {
            return AssemblyExecutionResult.Blocked(
                reason           = "RRID_VIOLATION: ${rridViolations.map { it.contractId }}",
                missingContracts = rridViolations.map { it.contractId }
            )
        }

        // ── Step 6: Position completeness — [1..N], no gaps, no duplicates (spec 5.5) ──
        val sortedContracts   = assemblyContracts.sortedBy { it.position }
        val observedPositions = sortedContracts.map { it.position }
        val expectedPositions = (1..totalContracts).toList()
        if (observedPositions != expectedPositions) {
            return AssemblyExecutionResult.Blocked(
                reason = "POSITION_VIOLATION: positions $observedPositions != expected $expectedPositions"
            )
        }

        // ── Step 7: Derive execution surface from TASK_EXECUTED(SUCCESS) (spec 5.2) ──
        val taskExecutionData = mutableMapOf<String, ContractExecutionData>()
        events.filter { ev ->
            ev.type == EventTypes.TASK_EXECUTED &&
            ev.payload["executionStatus"]?.toString() == "SUCCESS" &&
            (reportReference.isEmpty() ||
                ev.payload["report_reference"]?.toString() == reportReference)
        }.forEach { ev ->
            val contractId      = ev.payload["contractId"]?.toString()      ?: return@forEach
            val artifactRef     = ev.payload["artifactReference"]?.toString() ?: return@forEach
            @Suppress("UNCHECKED_CAST")
            val artifactStruct  = ev.payload["artifactStructure"] as? Map<String, Any> ?: return@forEach
            // Last SUCCESS wins (idempotent across retries)
            taskExecutionData[contractId] = ContractExecutionData(
                artifactReference = artifactRef,
                artifactStructure = artifactStruct
            )
        }

        // ── Step 8: Pre-flight — every contract must have SUCCESS execution surface (spec 5.6) ──
        val incompleteContracts = sortedContracts
            .filter { !taskExecutionData.containsKey(it.contractId) }
            .map { it.contractId }

        if (incompleteContracts.isNotEmpty()) {
            return AssemblyExecutionResult.Blocked(
                reason           = "INCOMPLETE_EXECUTION_SURFACE: $incompleteContracts",
                missingContracts = incompleteContracts
            )
        }

        // ── Step 9: Write ASSEMBLY_STARTED ───────────────────────────────────
        ledger.appendEvent(
            projectId,
            EventTypes.ASSEMBLY_STARTED,
            mapOf(
                "report_reference" to reportReference,
                "contractSetId"    to contractSetId,
                "totalContracts"   to totalContracts
            )
        )

        // ── Step 10: Build FinalArtifact with traceMap (spec 6.1/6.2) ────────
        val contractOutputs = sortedContracts.map { contract ->
            val execData = taskExecutionData.getValue(contract.contractId)
            ContractOutput(
                contractId        = contract.contractId,
                position          = contract.position,
                reportReference   = reportReference,
                artifactReference = execData.artifactReference,
                artifactStructure = execData.artifactStructure
            )
        }
        val traceMap: Map<String, String> = sortedContracts.associate { c ->
            c.contractId to reportReference
        }
        val finalArtifact = FinalArtifact(
            reportReference = reportReference,
            contractOutputs = contractOutputs,
            traceMap        = traceMap
        )

        // ── Step 11: Write ASSEMBLY_VALIDATED ────────────────────────────────
        ledger.appendEvent(projectId, EventTypes.ASSEMBLY_VALIDATED, emptyMap())

        // ── Step 12: Build AssemblyContractReport (AERP-1) ───────────────────
        val assemblyTaskId = "ASSEMBLY::$reportReference"
        val finalArtifactReference = buildFinalArtifactReference(reportReference)

        val assemblyReport = AssemblyContractReport(
            reportReference = reportReference,
            taskId          = assemblyTaskId,
            assemblyId      = assemblyTaskId,
            contractSetId   = contractSetId,
            totalContracts  = totalContracts,
            finalArtifact   = finalArtifact
        )

        // ── Step 13: Write ASSEMBLY_COMPLETED (spec 6.3) ─────────────────────
        ledger.appendEvent(
            projectId,
            EventTypes.ASSEMBLY_COMPLETED,
            mapOf(
                "report_reference"       to reportReference,
                "contractSetId"          to contractSetId,
                "totalContracts"         to totalContracts,
                "finalArtifactReference" to finalArtifactReference,
                "taskId"                 to assemblyTaskId,
                "assemblyId"             to assemblyTaskId,
                "traceMap"               to traceMap
            )
        )

        return AssemblyExecutionResult.Assembled(
            finalArtifact  = finalArtifact,
            assemblyReport = assemblyReport
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds the deterministic, referenceable final artifact reference.
     *
     * Format: `assembly:<reportReference>`
     */
    private fun buildFinalArtifactReference(reportReference: String): String =
        "assembly:$reportReference"

    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}

