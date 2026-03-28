package com.agoii.mobile.contracts

import com.agoii.mobile.execution.ExecutionContract
import com.agoii.mobile.execution.ExecutionTask

// AGOII CONTRACT — UNIVERSAL CONTRACT INTEROP (UCS-1)
// SURFACE 7: CONTRACT INTEROPERABILITY
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Bridges [UniversalContract] to and from the execution system's internal models
// ([ExecutionContract], [ExecutionTask]).
//
// INTEROP RULES:
//   I1 — [UniversalContract] is the SSOT; existing models derive FROM it.
//   I2 — Field mapping is deterministic: equal input always produces equal output.
//   I3 — No fields are dropped: the full surface of the target model is populated.
//   I4 — reportReference is propagated without modification (RRIL-1).
//   I5 — Backward-compatibility bridge (fromExecutionContract) applies canonical
//        defaults for fields absent from the legacy model.

/**
 * UniversalContractInterop — deterministic bridge between [UniversalContract] and the
 * execution system's internal models ([ExecutionContract], [ExecutionTask]).
 *
 * All methods are pure functions: no I/O, no state, no side effects.
 *
 * INTEROP DIRECTION RULE (I1):
 *   [UniversalContract] → [ExecutionContract]  via [toExecutionContract]
 *   [UniversalContract] → [ExecutionTask]       via [toExecutionTask]
 *   [ExecutionContract] → [UniversalContract]   via [fromExecutionContract]
 */
object UniversalContractInterop {

    // ── UniversalContract → ExecutionContract (Phase 1 model) ─────────────────

    /**
     * Map [contract] to the Phase 1 [ExecutionContract] model.
     *
     * Used to feed [com.agoii.mobile.execution.ExecutionAuthority.evaluate] with a
     * [UniversalContract] without modifying the Phase 1 pipeline.
     *
     * Mapping:
     *  contractId      → contractId
     *  outputDefinition.expectedType → name
     *  position        → position
     *  reportReference → reportReference (RRIL-1, I4)
     *
     * @param contract The [UniversalContract] to bridge.
     * @return [ExecutionContract] derived from [contract].
     */
    fun toExecutionContract(contract: UniversalContract): ExecutionContract =
        ExecutionContract(
            contractId      = contract.contractId,
            name            = contract.outputDefinition.expectedType,
            position        = contract.position,
            reportReference = contract.reportReference
        )

    // ── UniversalContract → ExecutionTask (Phase 2 model) ─────────────────────

    /**
     * Map [contract] to the Phase 2 [ExecutionTask] model.
     *
     * Used to inject a [UniversalContract] into the contractor execution pipeline
     * without requiring a TASK_ASSIGNED ledger event as the source.
     *
     * Mapping:
     *  contractId                      → contractId
     *  position / total                → position / total
     *  requirements                    → requirements (opaque list, preserved as-is)
     *  constraints (String items only) → constraints
     *  outputDefinition.expectedType   → expectedOutput
     *  reportReference                 → reportReference (RRIL-1, I4)
     *
     * @param contract The [UniversalContract] to bridge.
     * @param taskId   Deterministic task identifier; must be supplied by the caller
     *                 (typically built as "<contractId>-step1" or via [buildTaskId]).
     * @return [ExecutionTask] derived from [contract].
     */
    fun toExecutionTask(contract: UniversalContract, taskId: String): ExecutionTask =
        ExecutionTask(
            taskId          = taskId,
            contractId      = contract.contractId,
            position        = contract.position,
            total           = contract.total,
            requirements    = contract.requirements,
            constraints     = contract.constraints.filterIsInstance<String>(),
            expectedOutput  = contract.outputDefinition.expectedType,
            reportReference = contract.reportReference
        )

    // ── ExecutionContract → UniversalContract (backward compatibility) ─────────

    /**
     * Map a Phase 1 [ExecutionContract] to a [UniversalContract] for backward
     * compatibility with ledger-driven pipelines that pre-date UCS-1.
     *
     * Canonical defaults are applied for all fields not carried by [ExecutionContract]:
     *  - contractClass  → [ContractClass.OPERATIONAL]
     *  - executionType  → [ExecutionType.INTERNAL_EXECUTION]
     *  - targetDomain   → [TargetDomain.CONTRACTOR]
     *  - requirements   → empty list
     *  - constraints    → empty list
     *  - outputDefinition → inferred from [ExecutionContract.name]
     *
     * @param contract      The legacy [ExecutionContract] to upgrade.
     * @param intentId      The intentId to assign (must be supplied by the caller).
     * @param total         The total contract count in the execution sequence.
     * @param contractClass Optional class override; defaults to [ContractClass.OPERATIONAL].
     * @param executionType Optional type override; defaults to [ExecutionType.INTERNAL_EXECUTION].
     * @param targetDomain  Optional domain override; defaults to [TargetDomain.CONTRACTOR].
     * @return [UniversalContract] derived from [contract].
     */
    fun fromExecutionContract(
        contract:      ExecutionContract,
        intentId:      String,
        total:         Int,
        contractClass: ContractClass = ContractClass.OPERATIONAL,
        executionType: ExecutionType = ExecutionType.INTERNAL_EXECUTION,
        targetDomain:  TargetDomain  = TargetDomain.CONTRACTOR
    ): UniversalContract = UniversalContract(
        contractId       = contract.contractId,
        intentId         = intentId,
        reportReference  = contract.reportReference,
        contractClass    = contractClass,
        executionType    = executionType,
        targetDomain     = targetDomain,
        position         = contract.position,
        total            = total,
        requirements     = emptyList(),
        constraints      = emptyList(),
        outputDefinition = OutputDefinition(
            expectedType   = contract.name.ifBlank { contract.contractId },
            expectedSchema = mapOf("output" to "Any")
        )
    )

    /**
     * Build a deterministic task identifier from [reportReference] and [contractId].
     *
     * Format: `<contractId>-step1` (mirrors the Governor convention).
     *
     * @param reportReference The RRID from the originating [UniversalContract].
     * @param contractId      The contract identifier.
     * @return Deterministic task identifier string.
     */
    fun buildTaskId(reportReference: String, contractId: String): String =
        "$contractId-step1"
}
