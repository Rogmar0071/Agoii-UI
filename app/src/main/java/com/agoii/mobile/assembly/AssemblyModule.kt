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
// TRIGGER CONDITIONS (enforced strictly):
//   1. count(CONTRACT_COMPLETED) == totalContracts declared in CONTRACTS_GENERATED
//   2. NO ASSEMBLY_COMPLETED exists for the same report_reference
//   3. CONTRACTS_GENERATED event exists (source of totalContracts + RRID)
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
 *  1. Verify trigger conditions (pre-flight, no ledger writes on failure)
 *  2. Reconstruct [AssemblyInput] from ledger (RRIL-1, ledger-only)
 *  3. Validate artifact presence for every ordered contract
 *  4. Append ASSEMBLY_STARTED to ledger
 *  5. Build [FinalArtifact] (position 1 → N)
 *  6. Append ASSEMBLY_VALIDATED to ledger
 *  7. Build [AssemblyContractReport] (AERP-1)
 *  8. Append ASSEMBLY_COMPLETED to ledger (carries finalArtifactReference + RRID)
 *  9. Return [AssemblyExecutionResult.Assembled]
 */
class AssemblyModule {

    /**
     * Execute the assembly pipeline.
     *
     * Reads the full ledger for [projectId], enforces all trigger conditions,
     * and — when conditions are met — appends ASSEMBLY_STARTED, ASSEMBLY_VALIDATED,
     * and ASSEMBLY_COMPLETED to [ledger], then returns the [FinalArtifact].
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

        // ── Trigger condition 3: CONTRACTS_GENERATED must exist ─────────────
        val contractsGenEvent = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
            ?: return AssemblyExecutionResult.NotTriggered

        // ── Derive RRID + contractSetId + totalContracts from CONTRACTS_GENERATED ──
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

        // ── Trigger condition 2: idempotency guard ───────────────────────────
        val alreadyCompleted = events.any { ev ->
            ev.type == EventTypes.ASSEMBLY_COMPLETED &&
            ev.payload["report_reference"]?.toString() == reportReference
        }
        if (alreadyCompleted) {
            return AssemblyExecutionResult.AlreadyCompleted(reportReference)
        }

        // ── Trigger condition 1: all contracts must be completed ─────────────
        val completedContracts = events.count { it.type == EventTypes.CONTRACT_COMPLETED }
        if (completedContracts < totalContracts) {
            return AssemblyExecutionResult.NotTriggered
        }

        // ── Reconstruct AssemblyInput from ledger (RRIL-1) ───────────────────
        val assemblyInput = reconstructAssemblyInput(
            events          = events,
            reportReference = reportReference,
            contractSetId   = contractSetId,
            totalContracts  = totalContracts,
            contractsList   = contractsList
        )

        // ── Pre-flight: validate artifact presence (before any ledger write) ─
        val missingContracts = assemblyInput.orderedContracts
            .filter { !assemblyInput.taskArtifacts.containsKey(it.contractId) }
            .map { it.contractId }

        if (missingContracts.isNotEmpty()) {
            return AssemblyExecutionResult.Blocked(
                reason           = "MISSING_ARTIFACTS: $missingContracts",
                missingContracts = missingContracts
            )
        }

        // ── Step 4: Write ASSEMBLY_STARTED ───────────────────────────────────
        ledger.appendEvent(
            projectId,
            EventTypes.ASSEMBLY_STARTED,
            mapOf(
                "report_reference" to reportReference,
                "contractSetId"    to contractSetId,
                "totalContracts"   to totalContracts
            )
        )

        // ── Step 5: Build FinalArtifact (position 1 → N) ────────────────────
        val contractOutputs = assemblyInput.orderedContracts.map { contract ->
            val artifactRef = assemblyInput.taskArtifacts.getValue(contract.contractId)
            ContractOutput(
                contractId        = contract.contractId,
                position          = contract.position,
                artifactReference = artifactRef,
                artifactStructure = mapOf(
                    "contractId" to contract.contractId,
                    "position"   to contract.position
                )
            )
        }
        val finalArtifact = FinalArtifact(
            reportReference = reportReference,
            contractOutputs = contractOutputs
        )

        // ── Step 6: Write ASSEMBLY_VALIDATED ────────────────────────────────
        ledger.appendEvent(projectId, EventTypes.ASSEMBLY_VALIDATED, emptyMap())

        // ── Step 7: Build AssemblyContractReport (AERP-1) ────────────────────
        val assemblyTaskId = "ASSEMBLY::$reportReference"
        val finalArtifactReference = buildFinalArtifactReference(reportReference)

        val assemblyReport = AssemblyContractReport(
            reportReference = reportReference,
            taskId          = assemblyTaskId,
            contractSetId   = contractSetId,
            totalContracts  = totalContracts,
            finalArtifact   = finalArtifact
        )

        // ── Step 8: Write ASSEMBLY_COMPLETED ─────────────────────────────────
        ledger.appendEvent(
            projectId,
            EventTypes.ASSEMBLY_COMPLETED,
            mapOf(
                "report_reference"       to reportReference,
                "contractSetId"          to contractSetId,
                "totalContracts"         to totalContracts,
                "finalArtifactReference" to finalArtifactReference,
                "taskId"                 to assemblyTaskId
            )
        )

        return AssemblyExecutionResult.Assembled(
            finalArtifact  = finalArtifact,
            assemblyReport = assemblyReport
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Reconstruct [AssemblyInput] from the ledger exclusively.
     *
     * Ordered contracts are derived from the CONTRACTS_GENERATED payload.
     * Task artifacts are sourced from the most recent TASK_EXECUTED(SUCCESS) event
     * for each contract, filtered by [reportReference] (RRIL-1 lineage).
     *
     * NO external memory, NO inference — ledger is the single source of truth.
     */
    private fun reconstructAssemblyInput(
        events:          List<com.agoii.mobile.core.Event>,
        reportReference: String,
        contractSetId:   String,
        totalContracts:  Int,
        contractsList:   List<*>?
    ): AssemblyInput {

        // Derive ordered contracts from CONTRACTS_GENERATED payload
        val orderedContracts: List<AssemblyContract> = if (!contractsList.isNullOrEmpty()) {
            contractsList.filterIsInstance<Map<*, *>>()
                .mapNotNull { map ->
                    val contractId = map["contractId"]?.toString() ?: return@mapNotNull null
                    val position   = resolveInt(map["position"])    ?: return@mapNotNull null
                    AssemblyContract(contractId = contractId, position = position)
                }
                .sortedBy { it.position }
        } else {
            // Fallback: synthesise contract list from CONTRACT_STARTED events
            events.filter { it.type == EventTypes.CONTRACT_STARTED }
                .mapNotNull { ev ->
                    val contractId = ev.payload["contract_id"]?.toString() ?: return@mapNotNull null
                    val position   = resolveInt(ev.payload["position"])    ?: return@mapNotNull null
                    AssemblyContract(contractId = contractId, position = position)
                }
                .sortedBy { it.position }
        }

        // Derive task artifacts from successful TASK_EXECUTED events
        // For each contractId, prefer the latest SUCCESS entry (idempotent across retries)
        val taskArtifacts = mutableMapOf<String, String>()
        events.filter { ev ->
            ev.type == EventTypes.TASK_EXECUTED &&
            ev.payload["executionStatus"]?.toString() == "SUCCESS" &&
            (reportReference.isEmpty() ||
                ev.payload["report_reference"]?.toString() == reportReference)
        }.forEach { ev ->
            val contractId      = ev.payload["contractId"]?.toString()      ?: return@forEach
            val artifactReference = ev.payload["artifactReference"]?.toString() ?: return@forEach
            taskArtifacts[contractId] = artifactReference  // last SUCCESS wins
        }

        return AssemblyInput(
            reportReference  = reportReference,
            contractSetId    = contractSetId,
            totalContracts   = totalContracts,
            orderedContracts = orderedContracts,
            taskArtifacts    = taskArtifacts
        )
    }

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
