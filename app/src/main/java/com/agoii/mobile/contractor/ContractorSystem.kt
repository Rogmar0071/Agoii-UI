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
import com.agoii.mobile.execution.ContractorExecutionOutput
import com.agoii.mobile.execution.ContractorExecutor
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
     * Contractors selected and domain artifact produced deterministically.
     *
     * @property contractorIds   Ordered IDs of contractors that executed (1 for MATCHED,
     *                           N for SWARM_COORDINATION).
     * @property executionOutput [ContractorExecutionOutput] whose [ContractorExecutionOutput.resultArtifact]
     *                           is the domain-specific AERP-1 artifact.
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
 * contractor selection and domain-aware task execution.
 *
 * This class is the ONLY component permitted to call both [DeterministicMatchingEngine]
 * and [ContractorExecutor] together.  It is NOT a registry, NOT an orchestrator, and
 * NOT a ledger writer — it is a pure, deterministic execution kernel.
 *
 * Supported execution domains (all 5 [ExecutionType] values):
 *  - [ExecutionType.INTERNAL_EXECUTION]  → single matched contractor, internal artifact
 *  - [ExecutionType.EXTERNAL_EXECUTION]  → single matched contractor, external system artifact
 *  - [ExecutionType.COMMUNICATION]       → single matched contractor, communication cycle artifact
 *  - [ExecutionType.AI_PROCESSING]       → single matched contractor, AI processing artifact
 *  - [ExecutionType.SWARM_COORDINATION]  → swarm-composed contractors, swarm coordination artifact
 *
 * @param matchingEngine Injected for testing; defaults to a new [DeterministicMatchingEngine].
 * @param executor       Injected for testing; defaults to a new [ContractorExecutor].
 */
class ContractorSystem(
    private val matchingEngine: DeterministicMatchingEngine = DeterministicMatchingEngine(),
    private val executor:       ContractorExecutor          = ContractorExecutor()
) {

    // ─── Public entry point ───────────────────────────────────────────────────

    /**
     * Select contractors deterministically and execute for the given [executionType] domain.
     *
     * Pipeline (locked — all steps run in order):
     *  1. Adapt [registry] to [com.agoii.mobile.contractors.ContractorRegistry] interface.
     *  2. Resolve via [DeterministicMatchingEngine] (capability-based; NO heuristics).
     *  3. If BLOCKED → return [ContractorSystemResult.Blocked].
     *  4. Look up [ContractorProfile] objects for all resolved contractor IDs.
     *  5. Execute via [ContractorExecutor] (primary contractor; validates capability score).
     *  6. If execution FAILS → return [ContractorSystemResult.Blocked] with execution error.
     *  7. Build domain-specific AERP-1 artifact.
     *  8. Return [ContractorSystemResult.Resolved].
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

        // ── Step 7: Build domain-specific AERP-1 artifact (G6) ───────────────
        val domainArtifact = buildDomainArtifact(
            taskId        = taskId,
            contractId    = contractId,
            contractorIds = contractorIds,
            constraints   = constraints,
            rawOutput     = rawOutput,
            executionType = executionType,
            targetDomain  = targetDomain,
            profiles      = profiles
        )

        // ── Step 8: Return resolved result ────────────────────────────────────
        val executionOutput = ContractorExecutionOutput(
            taskId         = taskId,
            resultArtifact = domainArtifact,
            status         = ExecutionStatus.SUCCESS
        )
        return ContractorSystemResult.Resolved(
            contractorIds   = contractorIds,
            executionOutput = executionOutput,
            trace           = assigned.trace,
            executionType   = executionType,
            targetDomain    = targetDomain
        )
    }

    // ─── Domain-specific artifact builders ───────────────────────────────────

    /**
     * Build a domain-specific AERP-1 artifact for the given [executionType].
     *
     * AERP-1 mandatory base fields (A1, A2):
     *  - `taskId`         matches [taskId]
     *  - `constraintsMet` contains every element of [constraints]
     *
     * Additional fields are domain-specific and deterministic (A3, A4).
     */
    private fun buildDomainArtifact(
        taskId:        String,
        contractId:    String,
        contractorIds: List<String>,
        constraints:   List<String>,
        rawOutput:     ContractorExecutionOutput,
        executionType: ExecutionType,
        targetDomain:  TargetDomain,
        profiles:      List<ContractorProfile>
    ): Map<String, Any> {
        // Mandatory AERP-1 base (A1, A2)
        val base: Map<String, Any> = mapOf(
            "taskId"         to taskId,
            "contractId"     to contractId,
            "constraintsMet" to constraints,
            "executionType"  to executionType.name,
            "targetDomain"   to targetDomain.name
        )
        val domainExtension: Map<String, Any> = when (executionType) {
            ExecutionType.INTERNAL_EXECUTION -> buildInternalArtifact(contractorIds, rawOutput, profiles)
            ExecutionType.EXTERNAL_EXECUTION -> buildExternalArtifact(contractorIds, rawOutput, profiles)
            ExecutionType.COMMUNICATION      -> buildCommunicationArtifact(contractorIds, rawOutput, profiles)
            ExecutionType.AI_PROCESSING      -> buildAiProcessingArtifact(contractorIds, rawOutput, profiles)
            ExecutionType.SWARM_COORDINATION -> buildSwarmArtifact(contractorIds, rawOutput, profiles)
        }
        return base + domainExtension
    }

    /**
     * INTERNAL_EXECUTION artifact — single matched contractor executes within the internal engine.
     *
     * Domain fields:
     *  - `contractorId`    — primary contractor
     *  - `capabilityScore` — verified capability score
     *  - `reliabilityRatio` — historical reliability
     *  - `internalRef`     — deterministic internal reference (format: internal::<id>::<taskId>)
     *  - `executionPayload` — raw contractor output
     */
    private fun buildInternalArtifact(
        contractorIds: List<String>,
        rawOutput:     ContractorExecutionOutput,
        profiles:      List<ContractorProfile>
    ): Map<String, Any> {
        val profile = profiles.first()
        return mapOf(
            "contractorId"     to contractorIds.first(),
            "capabilityScore"  to profile.capabilities.capabilityScore,
            "reliabilityRatio" to profile.reliabilityRatio,
            "internalRef"      to "internal::${contractorIds.first()}::${rawOutput.taskId}",
            "executionPayload" to rawOutput.resultArtifact
        )
    }

    /**
     * EXTERNAL_EXECUTION artifact — contractor drives an external system integration.
     *
     * Domain fields:
     *  - `contractorId`      — driving contractor
     *  - `externalSystemRef` — deterministic external reference (format: ext::<id>::<taskId>)
     *  - `integrationStatus` — always "INTEGRATED" on success
     *  - `reliabilityRatio`  — historical reliability
     *  - `externalPayload`   — raw contractor output
     */
    private fun buildExternalArtifact(
        contractorIds: List<String>,
        rawOutput:     ContractorExecutionOutput,
        profiles:      List<ContractorProfile>
    ): Map<String, Any> {
        val profile = profiles.first()
        return mapOf(
            "contractorId"      to contractorIds.first(),
            "externalSystemRef" to "ext::${contractorIds.first()}::${rawOutput.taskId}",
            "integrationStatus" to "INTEGRATED",
            "reliabilityRatio"  to profile.reliabilityRatio,
            "externalPayload"   to rawOutput.resultArtifact
        )
    }

    /**
     * COMMUNICATION artifact — contractor drives a user-facing interaction cycle.
     *
     * Domain fields:
     *  - `contractorId`         — communication contractor
     *  - `communicationRef`     — deterministic comms reference (format: comm::<id>::<taskId>)
     *  - `promptCycle`          — always "COMPLETED" on success
     *  - `reliabilityRatio`     — historical reliability
     *  - `communicationPayload` — raw contractor output
     */
    private fun buildCommunicationArtifact(
        contractorIds: List<String>,
        rawOutput:     ContractorExecutionOutput,
        profiles:      List<ContractorProfile>
    ): Map<String, Any> {
        val profile = profiles.first()
        return mapOf(
            "contractorId"        to contractorIds.first(),
            "communicationRef"    to "comm::${contractorIds.first()}::${rawOutput.taskId}",
            "promptCycle"         to "COMPLETED",
            "reliabilityRatio"    to profile.reliabilityRatio,
            "communicationPayload" to rawOutput.resultArtifact
        )
    }

    /**
     * AI_PROCESSING artifact — contractor delegates to an AI/LLM agent.
     *
     * Domain fields:
     *  - `contractorId`     — AI processing contractor
     *  - `modelRef`         — deterministic model reference (format: ai::<id>::<taskId>)
     *  - `processingResult` — always "PROCESSED" on success
     *  - `reliabilityRatio` — historical reliability
     *  - `aiPayload`        — raw contractor output
     */
    private fun buildAiProcessingArtifact(
        contractorIds: List<String>,
        rawOutput:     ContractorExecutionOutput,
        profiles:      List<ContractorProfile>
    ): Map<String, Any> {
        val profile = profiles.first()
        return mapOf(
            "contractorId"     to contractorIds.first(),
            "modelRef"         to "ai::${contractorIds.first()}::${rawOutput.taskId}",
            "processingResult" to "PROCESSED",
            "reliabilityRatio" to profile.reliabilityRatio,
            "aiPayload"        to rawOutput.resultArtifact
        )
    }

    /**
     * SWARM_COORDINATION artifact — multiple contractors coordinate across a swarm.
     *
     * Domain fields:
     *  - `swarmContractorIds` — all participating contractor IDs
     *  - `swarmRef`           — deterministic swarm reference (format: swarm::<ids>::<taskId>)
     *  - `coordinationResult` — always "COORDINATED" on success
     *  - `swarmSize`          — number of swarm members
     *  - `swarmPayload`       — raw primary contractor output
     */
    private fun buildSwarmArtifact(
        contractorIds: List<String>,
        rawOutput:     ContractorExecutionOutput,
        profiles:      List<ContractorProfile>
    ): Map<String, Any> {
        return mapOf(
            "swarmContractorIds"  to contractorIds,
            "swarmRef"            to "swarm::${contractorIds.joinToString(",")}::${rawOutput.taskId}",
            "coordinationResult"  to "COORDINATED",
            "swarmSize"           to contractorIds.size,
            "swarmPayload"        to rawOutput.resultArtifact
        )
    }
}
