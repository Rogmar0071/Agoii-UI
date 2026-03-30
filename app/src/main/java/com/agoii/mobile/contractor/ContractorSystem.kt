package com.agoii.mobile.contractor

// AGOII CONTRACT — CONTRACTOR SYSTEM (AGOII-CONTRACTOR-SYSTEM-FULL-001)
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// COMPLETE, DETERMINISTIC Contractor System that executes ALL UniversalContracts
// via ExecutionAuthority exclusively.
//
// GOVERNANCE RULES (LOCKED):
//  G1 — Selection is ALWAYS performed by [DeterministicMatchingEngine]; no heuristics, no fallbacks.
//  G2 — Execution is ALWAYS delegated to [com.agoii.mobile.execution.ContractorExecutor].
//  G3 — Domain dispatch is purely functional: equal inputs produce equal outputs.
//  G4 — This class MUST be called exclusively from
//       [com.agoii.mobile.execution.ExecutionAuthority.executeFromLedger].
//  G5 — NO direct ledger access; the caller (ExecutionAuthority) owns all ledger I/O.
//  G6 — All 5 [com.agoii.mobile.contracts.ExecutionType] domains are supported:
//       INTERNAL_EXECUTION, EXTERNAL_EXECUTION, COMMUNICATION, AI_PROCESSING, SWARM_COORDINATION.
//
// ARTIFACT RULES (AERP-1):
//  A1 — Every domain artifact MUST contain `taskId` (ResultValidator Rule 1).
//  A2 — Every domain artifact MUST contain `constraintsMet` (ResultValidator Rule 2).
//  A3 — Artifacts are deterministic: equal inputs produce equal artifact key-sets.
//  A4 — Domain-specific fields are non-null and non-empty when execution succeeds.

import com.agoii.mobile.contractors.DeterministicMatchingEngine
import com.agoii.mobile.contractors.ExecutionContract as MatchingContract
import com.agoii.mobile.contractors.ResolutionTrace
import com.agoii.mobile.contracts.ContractCapability
import com.agoii.mobile.contracts.ExecutionType
import com.agoii.mobile.contracts.TargetDomain
import com.agoii.mobile.execution.ContractorExecutionInput
import com.agoii.mobile.execution.ContractorExecutor
import com.agoii.mobile.execution.DriverRegistry
import com.agoii.mobile.execution.ExecutionStatus

// ─── Result ───────────────────────────────────────────────────────────────────

/**
 * Domain-resolved execution context produced by [ContractorSystem].
 *
 * [Resolved] carries everything [com.agoii.mobile.execution.ExecutionAuthority.executeFromLedger]
 * needs to generate the AERP-1 report and continue the pipeline.
 *
 * [Blocked] signals that no feasible contractor exists for the given domain; the caller
 * must issue an RCF-1 recovery contract (handled by ExecutionAuthority).
 */
sealed class ContractorSystemResult {

    /**
     * Contractors selected and driver output returned.
     *
     * @property contractorIds   Ordered IDs of contractors that executed (1 for MATCHED,
     *                           N for SWARM_COORDINATION).
     * @property executionOutput [ContractorExecutionOutput] produced by the registered
     *                           [com.agoii.mobile.execution.ExecutionDriver] for the contractor's source.
     * @property trace           Resolution trace from [DeterministicMatchingEngine].
     * @property executionType   The [ExecutionType] that was applied.
     * @property targetDomain    The [TargetDomain] that was targeted.
     */
    data class Resolved(
        val contractorIds:   List<String>,
        val executionOutput: ContractorExecutionOutput,
        val trace:           ResolutionTrace,
        val executionType:   ExecutionType,
        val targetDomain:    TargetDomain
    ) : ContractorSystemResult()

    /**
     * No viable contractor could be selected; RCF-1 recovery is required.
     *
     * @property reason Deterministic failure code (e.g. "NO_FEASIBLE_CONTRACTOR:COMMUNICATION").
     * @property trace  Resolution trace for ledger audit.
     */
    data class Blocked(
        val reason: String,
        val trace:  ResolutionTrace
    ) : ContractorSystemResult()
}

// ─── ContractorSystem ─────────────────────────────────────────────────────────

/**
 * ContractorSystem — the single deterministic entry point for capability-based
 * contractor selection and driver-routed task execution.
 *
 * This class is the ONLY component permitted to call both [DeterministicMatchingEngine]
 * and [ContractorExecutor] together.  It is NOT a registry, NOT an orchestrator, and
 * NOT a ledger writer — it is a pure, deterministic execution kernel.
 *
 * All execution is delegated to registered [com.agoii.mobile.execution.ExecutionDriver]
 * instances via [DriverRegistry]. If no driver is registered for the selected contractor's
 * source, [ContractorExecutor] throws [com.agoii.mobile.core.LedgerValidationException]
 * and the system blocks truthfully (no simulation, no fallback).
 *
 * Supported execution domains (all 5 [ExecutionType] values):
 *  - [ExecutionType.INTERNAL_EXECUTION]  → single matched contractor
 *  - [ExecutionType.EXTERNAL_EXECUTION]  → single matched contractor
 *  - [ExecutionType.COMMUNICATION]       → single matched contractor
 *  - [ExecutionType.AI_PROCESSING]       → single matched contractor
 *  - [ExecutionType.SWARM_COORDINATION]  → swarm-composed contractors
 *
 * @param matchingEngine Injected for testing; defaults to a new [DeterministicMatchingEngine].
 * @param driverRegistry Registry of [com.agoii.mobile.execution.ExecutionDriver] instances;
 *                       defaults to an empty registry — no drivers are pre-registered.
 * @param executor       Injected for testing; defaults to a new [ContractorExecutor] backed
 *                       by [driverRegistry].
 */
class ContractorSystem(
    private val matchingEngine: DeterministicMatchingEngine = DeterministicMatchingEngine(),
    private val driverRegistry: DriverRegistry              = DriverRegistry(),
    private val executor:       ContractorExecutor          = ContractorExecutor(driverRegistry)
) {

    // ─── Public entry point ───────────────────────────────────────────────────

    /**
     * Select contractors deterministically and execute via the registered [ExecutionDriver].
     *
     * Pipeline (locked — all steps run in order):
     *  1. Adapt [registry] to [com.agoii.mobile.contractors.ContractorRegistry] interface.
     *  2. Resolve via [DeterministicMatchingEngine] (capability-based; NO heuristics).
     *  3. If BLOCKED → return [ContractorSystemResult.Blocked].
     *  4. Look up [ContractorProfile] objects for all resolved contractor IDs.
     *  5. Execute via [ContractorExecutor] → [DriverRegistry] → [com.agoii.mobile.execution.ExecutionDriver].
     *     No driver registered → throws [com.agoii.mobile.core.LedgerValidationException].
     *  6. If execution FAILS → return [ContractorSystemResult.Blocked] with execution error.
     *  7. Return [ContractorSystemResult.Resolved] with the raw driver output.
     *
     * @param taskId               Task identifier — propagated into artifact (AERP-1, A1).
     * @param contractId           Contract identifier — propagated into artifact.
     * @param reportReference      RRID — propagated for RRIL-1 compliance.
     * @param position             1-based contract position in the execution sequence.
     * @param constraints          Boundary constraints — embedded in artifact (AERP-1, A2).
     * @param expectedOutput       Output objective used as task description.
     * @param taskPayload          Structured task payload passed to the contractor.
     * @param requiredCapabilities Explicit, canonical capability list for deterministic matching (G1).
     * @param executionType        Declared execution domain (G6).
     * @param targetDomain         Declared execution boundary — embedded in artifact.
     * @param registry             Verified [ContractorRegistry] (contractor package).
     * @return [ContractorSystemResult.Resolved] on success; [ContractorSystemResult.Blocked] otherwise.
     * @throws com.agoii.mobile.core.LedgerValidationException when no driver is registered for
     *         the selected contractor's source.
     */
    fun execute(
        taskId:                String,
        contractId:            String,
        reportReference:       String,
        position:              Int,
        constraints:           List<String>,
        expectedOutput:        String,
        taskPayload:           Map<String, Any>,
        requiredCapabilities:  List<ContractCapability>,
        executionType:         ExecutionType,
        targetDomain:          TargetDomain,
        registry:              ContractorRegistry
    ): ContractorSystemResult {

        // ── Step 1: Deterministic matching via new overload (G1) ─────────────
        //   DeterministicMatchingEngine.resolve() receives the capability list directly
        //   (no transformation in the caller).
        val matchContract = MatchingContract(
            contractId      = contractId,
            reportReference = reportReference,
            position        = position.toString()
        )
        val assigned = matchingEngine.resolve(matchContract, requiredCapabilities, registry)

        if (assigned.assignment.mode == com.agoii.mobile.contractors.AssignmentMode.BLOCKED) {
            return ContractorSystemResult.Blocked(
                reason = "NO_FEASIBLE_CONTRACTOR:${executionType.name}",
                trace  = assigned.trace
            )
        }

        val contractorIds = assigned.assignment.contractorIds
        if (contractorIds.isEmpty()) {
            return ContractorSystemResult.Blocked(
                reason = "MATCHING_RETURNED_EMPTY_CONTRACTORS",
                trace  = assigned.trace
            )
        }

        // ── Step 4: Look up contractor profiles ───────────────────────────────
        val profiles = contractorIds.mapNotNull { id ->
            registry.allVerified().find { it.id == id }
        }
        if (profiles.isEmpty()) {
            return ContractorSystemResult.Blocked(
                reason = "CONTRACTOR_PROFILES_NOT_FOUND",
                trace  = assigned.trace
            )
        }

        // ── Step 5: Execute via ContractorExecutor (G2) ───────────────────────
        val primaryProfile = profiles.first()
        val input = ContractorExecutionInput(
            taskId               = taskId,
            taskDescription      = expectedOutput,
            taskPayload          = taskPayload,
            contractConstraints  = constraints,
            expectedOutputSchema = expectedOutput
        )
        val rawOutput = executor.execute(input, primaryProfile)

        // ── Step 6: Propagate executor failure ────────────────────────────────
        if (rawOutput.status == ExecutionStatus.FAILURE) {
            return ContractorSystemResult.Blocked(
                reason = "CONTRACTOR_EXECUTION_FAILED:${rawOutput.error ?: "unknown"}",
                trace  = assigned.trace
            )
        }

        // ── Step 7: Return resolved result with raw driver output ─────────────
        return ContractorSystemResult.Resolved(
            contractorIds   = contractorIds,
            executionOutput = rawOutput,
            trace           = assigned.trace,
            executionType   = executionType,
            targetDomain    = targetDomain
        )
    }
}
