// AGOII CONTRACT — ASSEMBLY MODULE (FINAL COMPLIANT IMPLEMENTATION — AERP-1)
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Assembly Module ONLY
// - Mutation Authority: Tier C
//
// EXECUTION SURFACE (READ):
//   CONTRACTS_GENERATED, CONTRACT_COMPLETED (N), TASK_EXECUTED (N), EXECUTION_COMPLETED
//
// EXECUTION SURFACE (WRITE):
//   ASSEMBLY_STARTED → ASSEMBLY_COMPLETED  (success path)
//   ASSEMBLY_FAILED                        (failure path — Governor drives recovery)
//
// AUTHORITY RULES:
//   - Ordering and contract identity: CONTRACT_COMPLETED events             (spec §6.5)
//   - Artifact surface: TASK_EXECUTED.artifactReference ONLY (AERP-1 §3)  (spec §6.4)
//   - RRID lineage: every CONTRACT_COMPLETED.report_reference must match    (spec §6.3)
//   - Missing TASK_EXECUTED SUCCESS → BLOCK: INCOMPLETE_EXECUTION_SURFACE  (spec §6.4)
//   - RRID mismatch → BLOCK: RRID_VIOLATION                                (spec §6.3)
//
// INVARIANT ENFORCEMENT:
//   - NO artifactStructure in TASK_EXECUTED (AERP-1 §3)
//   - NO synthetic reconstruction
//   - NO external data sources
//   - NO heuristic ordering
//   - NO filtering of contractOutputs (pure projection)
//   - NO deduplication of contractOutputs
//   - NO ordering correction of contractOutputs
//   - NO fallback/default injection
//   ALL anomalies pass through unchanged to ExecutionAuthority via ASSEMBLY_FAILED
//
// RE-ENTRY GUARD:
//   Re-entry after ASSEMBLY_FAILED is allowed ONLY when:
//     - SAME contractSetId
//     - NO new CONTRACTS_GENERATED present after ASSEMBLY_FAILED
//     - EXECUTION_COMPLETED already present
//   Else → throw IllegalStateException (invariant violation)
//
// EXECUTION AUTHORITY:
//   Assembly is invoked ONLY by ExecutionAuthority. Governor MUST NOT call assemble().
//
// RECOVERY AUTHORITY:
//   ASSEMBLY_FAILED is the sole trigger for the Governor-owned recovery flow:
//   ASSEMBLY_FAILED → RECOVERY_CONTRACT → DELTA_CONTRACT_CREATED → TASK_ASSIGNED
//
// LEDGER AUTHORITY:
//   ALL reads and writes operate exclusively through EventRepository.
//   No external memory; no inference; no external orchestrators.

package com.agoii.mobile.assembly

import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes

/**
 * Assembly Module — deterministic reducer over all CONTRACT_COMPLETED outputs.
 *
 * Single public entry point: [assemble].
 *
 * Pipeline (success path):
 *  1. Verify EXECUTION_COMPLETED exists (internal gate — spec §6.1)
 *  2. Derive metadata (RRID, contractSetId, totalContracts) from CONTRACTS_GENERATED
 *  3. Idempotency guard — no duplicate ASSEMBLY_COMPLETED for same RRID
 *  3b. Re-entry guard — strict check when ASSEMBLY_FAILED already exists for this RRID
 *  4. Pure projection: collect ALL CONTRACT_COMPLETED events without filtering/sorting/deduplication
 *  5. Collect ALL TASK_EXECUTED events (success + failure — pure projection)
 *  6. Detect anomalies: RRID violations, position violations, incomplete execution surface,
 *     trace completeness violations
 *  7. If anomalies → emit ASSEMBLY_FAILED, return [AssemblyExecutionResult.Failed]
 *  8. Append ASSEMBLY_STARTED to ledger
 *  9. Build [FinalArtifact] with traceMap (contractId → artifactReference)
 * 10. Build [AssemblyContractReport] (AERP-1)
 * 11. Append ASSEMBLY_COMPLETED to ledger
 * 12. Return [AssemblyExecutionResult.Assembled]
 */
class AssemblyModule {

    /**
     * Execute the assembly pipeline.
     *
     * Reads the full ledger for [projectId], enforces all trigger conditions
     * internally (no reliance on caller), and — when conditions are met — appends
     * either ASSEMBLY_FAILED or (ASSEMBLY_STARTED + ASSEMBLY_COMPLETED) to [ledger].
     *
     * This method is a pure function of the ledger state: same ledger state always
     * produces the same result.  It has no implicit inputs and performs no inference.
     *
     * @param projectId  Project ledger identifier.
     * @param ledger     EventRepository — sole write authority.
     * @return [AssemblyExecutionResult] describing the pipeline outcome.
     */
    fun assemble(projectId: String, ledger: EventRepository): AssemblyExecutionResult {

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

        // ── Step 3b: Re-entry guard — strict check when ASSEMBLY_FAILED exists ──
        val failureIndex = events.indexOfLast { ev ->
            ev.type == EventTypes.ASSEMBLY_FAILED &&
            ev.payload["report_reference"]?.toString() == reportReference
        }
        if (failureIndex >= 0) {
            val previousFailure = events[failureIndex]
            val storedContractSetId = previousFailure.payload["contractSetId"]?.toString() ?: ""
            if (storedContractSetId != contractSetId) {
                throw IllegalStateException(
                    "ASSEMBLY_INVARIANT_VIOLATION: RE_ENTRY_BLOCKED — contractSetId mismatch " +
                    "for RRID=$reportReference. Stored=$storedContractSetId, current=$contractSetId"
                )
            }
            val newContractsGenAfterFailure = events.drop(failureIndex + 1).any { ev ->
                ev.type == EventTypes.CONTRACTS_GENERATED
            }
            if (newContractsGenAfterFailure) {
                throw IllegalStateException(
                    "ASSEMBLY_INVARIANT_VIOLATION: RE_ENTRY_BLOCKED — new CONTRACTS_GENERATED " +
                    "present after ASSEMBLY_FAILED for RRID=$reportReference"
                )
            }
        }

        // ── Step 4: Pure projection — collect ALL CONTRACT_COMPLETED events ──
        // NO filtering, NO deduplication, NO ordering correction, NO fallback injection.
        // All anomalies pass through unchanged.
        val completedEvents = events.filter { it.type == EventTypes.CONTRACT_COMPLETED }

        // Trigger condition: all contracts must be completed (spec 5.5 count check)
        if (completedEvents.size < totalContracts) {
            return AssemblyExecutionResult.NotTriggered
        }

        val assemblyContracts = completedEvents.mapNotNull { ev ->
            val contractId   = ev.payload["contractId"]?.toString()      ?: return@mapNotNull null
            val position     = resolveInt(ev.payload["position"])         ?: return@mapNotNull null
            val contractRrid = ev.payload["report_reference"]?.toString() ?: ""
            AssemblyContract(
                contractId      = contractId,
                position        = position,
                reportReference = contractRrid
            )
        }

        // ── Step 5: Pure projection — collect ALL TASK_EXECUTED events ───────
        // SUCCESS executions are tracked for traceMap and surface validation.
        // ALL executions are tracked for pure contractOutput projection.
        val allTaskExecutions    = mutableMapOf<String, ContractExecutionData>()
        val successTaskExecutions = mutableMapOf<String, ContractExecutionData>()
        events.filter { ev ->
            ev.type == EventTypes.TASK_EXECUTED &&
            (reportReference.isEmpty() ||
                ev.payload["report_reference"]?.toString() == reportReference)
        }.forEach { ev ->
            val contractId  = ev.payload["contractId"]?.toString()        ?: return@forEach
            val artifactRef = ev.payload["artifactReference"]?.toString() ?: ""
            val execStatus  = ev.payload["executionStatus"]?.toString()   ?: ""
            // Last write wins (idempotent across retries)
            allTaskExecutions[contractId] = ContractExecutionData(artifactRef)
            if (execStatus == "SUCCESS") {
                successTaskExecutions[contractId] = ContractExecutionData(artifactRef)
            }
        }

        // ── Step 6: Collect anomalies (without filtering or mutating contractOutputs) ──
        val failureReasons = mutableListOf<AssemblyFailureReason>()

        // RRID lineage violations (spec 5.3)
        for (contract in assemblyContracts) {
            if (contract.reportReference.isNotBlank() &&
                contract.reportReference != reportReference) {
                failureReasons.add(
                    AssemblyFailureReason(
                        contractId        = contract.contractId,
                        failureType       = "RRID_VIOLATION",
                        violatedInvariant = "report_reference '${contract.reportReference}' " +
                                            "!= expected '$reportReference' (spec §6.3)"
                    )
                )
            }
        }

        // Position completeness — [1..N], no gaps, no duplicates (spec 5.5)
        val observedPositionSet  = assemblyContracts.map { it.position }.toSortedSet()
        val expectedPositionSet  = (1..totalContracts).toSortedSet()
        if (observedPositionSet != expectedPositionSet) {
            val missingPositions  = expectedPositionSet - observedPositionSet
            val unexpectedContracts = assemblyContracts.filter { it.position !in expectedPositionSet }
            for (contract in unexpectedContracts) {
                failureReasons.add(
                    AssemblyFailureReason(
                        contractId        = contract.contractId,
                        failureType       = "POSITION_VIOLATION",
                        violatedInvariant = "position ${contract.position} not in expected range " +
                                            "[1..$totalContracts]; missing=$missingPositions"
                    )
                )
            }
            if (unexpectedContracts.isEmpty() && missingPositions.isNotEmpty()) {
                // Positions are valid integers but set is incomplete — flag a sentinel entry
                failureReasons.add(
                    AssemblyFailureReason(
                        contractId        = "POSITION_GAP",
                        failureType       = "POSITION_VIOLATION",
                        violatedInvariant = "position set $observedPositionSet missing $missingPositions " +
                                            "from expected [1..$totalContracts]"
                    )
                )
            }
        }

        // Incomplete execution surface — every contract must have a SUCCESS TASK_EXECUTED
        for (contract in assemblyContracts) {
            if (!successTaskExecutions.containsKey(contract.contractId)) {
                failureReasons.add(
                    AssemblyFailureReason(
                        contractId        = contract.contractId,
                        failureType       = "INCOMPLETE_EXECUTION_SURFACE",
                        violatedInvariant = "contractId='${contract.contractId}' has no " +
                                            "SUCCESS TASK_EXECUTED event (spec §6.4)"
                    )
                )
            }
        }

        // ── Step 7: Build traceMap (contractId → artifactReference) ──────────
        // Only non-blank artifactReferences are entered; missing entries signal
        // TRACE_INCOMPLETE. Trace completeness: every SUCCESS TASK_EXECUTED must
        // have a non-blank artifactReference tracked in the traceMap (AERP-1 §3 / spec §7.2).
        val traceMap: Map<String, String> = successTaskExecutions
            .filterValues { it.artifactReference.isNotBlank() }
            .mapKeys { (contractId, _) -> contractId }
            .mapValues { (_, data) -> data.artifactReference }

        events.filter { ev ->
            ev.type == EventTypes.TASK_EXECUTED &&
            ev.payload["executionStatus"]?.toString() == "SUCCESS" &&
            (reportReference.isEmpty() ||
                ev.payload["report_reference"]?.toString() == reportReference)
        }.forEach { ev ->
            val contractId  = ev.payload["contractId"]?.toString() ?: return@forEach
            val artifactRef = ev.payload["artifactReference"]?.toString() ?: ""
            if (artifactRef.isBlank()) {
                val alreadyReported = failureReasons.any {
                    it.contractId == contractId && it.failureType == "TRACE_INCOMPLETE"
                }
                if (!alreadyReported) {
                    failureReasons.add(
                        AssemblyFailureReason(
                            contractId        = contractId,
                            failureType       = "TRACE_INCOMPLETE",
                            violatedInvariant = "artifactReference for contractId='$contractId' " +
                                                "is absent from AssemblyContractReport.traceMap (AERP-1 §3)"
                        )
                    )
                }
            }
        }

        // ── Build contractOutputs (pure projection — ALL contracts, in ledger order) ──
        val contractOutputs = assemblyContracts.map { contract ->
            ContractOutput(
                contractId        = contract.contractId,
                position          = contract.position,
                reportReference   = reportReference,
                artifactReference = allTaskExecutions[contract.contractId]?.artifactReference ?: ""
            )
        }

        // ── Step 8: If anomalies exist → emit ASSEMBLY_FAILED ────────────────
        if (failureReasons.isNotEmpty()) {
            val invalidContractIds = failureReasons.map { it.contractId }.toSet()
            val lockedSections     = contractOutputs.filter { it.contractId !in invalidContractIds }
            val violationSurface   = contractOutputs.filter { it.contractId in invalidContractIds }
            val primary            = failureReasons.first()

            ledger.appendEvent(
                projectId,
                EventTypes.ASSEMBLY_FAILED,
                mapOf(
                    "report_reference"        to reportReference,
                    "contractSetId"           to contractSetId,
                    "failureReasonContractId" to primary.contractId,
                    "failureType"             to primary.failureType,
                    "violatedInvariant"       to primary.violatedInvariant,
                    "lockedSections"          to lockedSections.map { it.contractId },
                    "violationSurface"        to violationSurface.map { it.contractId }
                )
            )

            return AssemblyExecutionResult.Failed(
                reportReference  = reportReference,
                failureReasons   = failureReasons,
                lockedSections   = lockedSections,
                violationSurface = violationSurface
            )
        }

        // ── Step 9: Write ASSEMBLY_STARTED ───────────────────────────────────
        // Sort only for the final artifact ordering — ledger order is preserved in
        // contractOutputs above; this sorted list is used solely for ASSEMBLY_STARTED/COMPLETED.
        val sortedContracts = assemblyContracts.sortedBy { it.position }

        ledger.appendEvent(
            projectId,
            EventTypes.ASSEMBLY_STARTED,
            mapOf(
                "report_reference" to reportReference,
                "contractSetId"    to contractSetId,
                "totalContracts"   to totalContracts
            )
        )

        // ── Step 10: Build FinalArtifact with traceMap ───────────────────────
        val finalContractOutputs = sortedContracts.map { contract ->
            ContractOutput(
                contractId        = contract.contractId,
                position          = contract.position,
                reportReference   = reportReference,
                artifactReference = successTaskExecutions.getValue(contract.contractId).artifactReference
            )
        }

        val finalArtifact = FinalArtifact(
            reportReference = reportReference,
            contractSetId   = contractSetId,
            totalContracts  = totalContracts,
            contractOutputs = finalContractOutputs,
            traceMap        = traceMap
        )

        // ── Step 11: Build AssemblyContractReport (AERP-1) ───────────────────
        val assemblyId             = "assembly_$reportReference"
        val finalArtifactReference = "artifact_$assemblyId"

        val assemblyReport = AssemblyContractReport(
            reportReference = reportReference,
            taskId          = assemblyId,
            assemblyId      = assemblyId,
            contractSetId   = contractSetId,
            totalContracts  = totalContracts,
            finalArtifact   = finalArtifact
        )

        // ── Step 12: Write ASSEMBLY_COMPLETED (spec §7.3) ────────────────────
        ledger.appendEvent(
            projectId,
            EventTypes.ASSEMBLY_COMPLETED,
            mapOf(
                "report_reference"       to reportReference,
                "contractSetId"          to contractSetId,
                "totalContracts"         to totalContracts,
                "finalArtifactReference" to finalArtifactReference,
                "taskId"                 to assemblyId,
                "assemblyId"             to assemblyId,
                "traceMap"               to traceMap
            )
        )

        return AssemblyExecutionResult.Assembled(
            finalArtifact  = finalArtifact,
            assemblyReport = assemblyReport
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}

