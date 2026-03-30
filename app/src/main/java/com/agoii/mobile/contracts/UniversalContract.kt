package com.agoii.mobile.contracts

// AGOII CONTRACT — UNIVERSAL CONTRACT SYSTEM (UCS-1)
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// CORE PRINCIPLE (LOCKED):
// THE SYSTEM DOES NOT ADAPT TO CONTRACT TYPES
// THE CONTRACT DECLARES HOW IT MUST BE EXECUTED

// ─── Contract Class ───────────────────────────────────────────────────────────

/**
 * Structural classification of a [UniversalContract].
 *
 * Determines the category of change or operation the contract represents
 * within the system's lifecycle.
 *
 *  STRUCTURAL  — Adds, removes, or reorganises system components.
 *  STATE       — Drives a transition between well-defined lifecycle states.
 *  OPERATIONAL — Performs a bounded, reversible unit of work.
 *  GOVERNANCE  — Enforces rules, constraints, or authority decisions.
 */
enum class ContractClass {
    STRUCTURAL,
    STATE,
    OPERATIONAL,
    GOVERNANCE
}

// ─── Execution Type ───────────────────────────────────────────────────────────

/**
 * Execution semantics encoded by the contract itself.
 *
 * The [UniversalContract] declares HOW it must be executed; the system
 * reads this field and routes accordingly — it does NOT infer execution
 * mode from contract structure or caller context.
 *
 *  INTERNAL_EXECUTION   — Executed by an internal engine component (e.g. contractor).
 *  EXTERNAL_EXECUTION   — Executed by an external system integration.
 *  COMMUNICATION        — Drives a user-facing interaction or prompt cycle.
 *  AI_PROCESSING        — Delegated to an AI/LLM agent.
 *  SWARM_COORDINATION   — Distributed across a swarm of coordinated agents.
 */
enum class ExecutionType {
    INTERNAL_EXECUTION,
    EXTERNAL_EXECUTION,
    COMMUNICATION,
    AI_PROCESSING,
    SWARM_COORDINATION
}

// ─── Target Domain ────────────────────────────────────────────────────────────

/**
 * The execution boundary this contract targets.
 *
 * Combined with [ExecutionType], [TargetDomain] enables fully deterministic
 * routing via [com.agoii.mobile.execution.ExecutionAuthority] without any
 * specialised contract-type handling in the system.
 *
 *  INTERNAL_ENGINE — Targets a core engine module (Governor, Assembly, ICS, etc.).
 *  CONTRACTOR      — Targets a matched or swarm-composed contractor.
 *  EXTERNAL_SYSTEM — Targets an integration outside the execution engine.
 *  USER            — Targets a human actor via the interaction layer.
 *  MULTI_AGENT     — Targets a coordinated multi-agent surface.
 */
enum class TargetDomain {
    INTERNAL_ENGINE,
    CONTRACTOR,
    EXTERNAL_SYSTEM,
    USER,
    MULTI_AGENT
}

// ─── Contract Capability ──────────────────────────────────────────────────────

/**
 * Canonical capability dimension required by a [UniversalContract].
 *
 * Each value corresponds to one dimension of [com.agoii.mobile.contractor.ContractorCapabilityVector]
 * and is matched directly against the contractor registry during deterministic selection.
 * NO string parsing; NO level inference — the enum value IS the capability declaration.
 *
 * [dimensionName]   maps to the field name on
 *                   [com.agoii.mobile.contractor.ContractorCapabilityVector] and to the
 *                   [com.agoii.mobile.contractors.Capability.name] used by
 *                   [com.agoii.mobile.contractors.DeterministicMatchingEngine].
 * [requiredLevel]   minimum score (inclusive) a contractor must have in this dimension.
 *                   Scores are in the range [0, 3] for contractor profiles.
 */
enum class ContractCapability(val dimensionName: String, val requiredLevel: Int) {
    /** Contractor must reliably follow execution constraints. */
    CONSTRAINT_OBEDIENCE("constraintObedience", 1),
    /** Contractor must follow structural output specifications. */
    STRUCTURAL_ACCURACY("structuralAccuracy", 1),
    /** Contractor must handle complex, multi-step work. */
    COMPLEXITY_CAPACITY("complexityCapacity", 1),
    /** Contractor must produce consistent, deterministic output. */
    RELIABILITY("reliability", 1)
}

// ─── Output Definition ────────────────────────────────────────────────────────

/**
 * Declarative description of the output this contract is expected to produce.
 *
 * All output validation operates against this definition; no implicit output
 * shapes are permitted.
 *
 * @property expectedType   Human-readable type label (e.g. "ArtifactMap", "ReportReference").
 * @property expectedSchema Key/value schema describing the structure of the expected output.
 *                          Values describe the type or constraint for each key.
 */
data class OutputDefinition(
    val expectedType:   String,
    val expectedSchema: Map<String, Any>
)

// ─── Universal Contract ───────────────────────────────────────────────────────

/**
 * Universal Contract — the single model capable of expressing ALL contract types
 * across the system (UCS-1).
 *
 * Execution semantics are encoded within the contract via [executionType] and
 * [targetDomain].  The system NEVER adapts to contract types; it reads the contract
 * and routes deterministically via [com.agoii.mobile.execution.ExecutionAuthority].
 *
 * Compliance:
 *  - AERP-1 — [reportReference] is the RRID anchor for every execution report.
 *  - RCF-1  — [requiredCapabilities] and [constraints] feed the recovery contract on failure.
 *  - RRIL-1 — [reportReference] propagated in every downstream ledger event.
 *
 * @property contractId            Unique identifier for this contract instance.
 * @property intentId              Identifier of the originating intent.
 * @property reportReference       Report Reference ID (RRID) — externally supplied; never generated here.
 * @property contractClass         Structural classification of this contract.
 * @property executionType         How this contract must be executed (declared by the contract).
 * @property targetDomain          The execution boundary this contract targets.
 * @property position              1-based position of this contract in its execution sequence.
 * @property total                 Total number of contracts in the same sequence.
 * @property requiredCapabilities  Explicit, canonical capability list that MUST be satisfied
 *                                 by the matched contractor.  Non-nullable, non-empty.
 * @property constraints           Boundary conditions that execution must respect.
 * @property outputDefinition      Declarative description of the expected output.
 */
data class UniversalContract(
    val contractId:            String,
    val intentId:              String,
    val reportReference:       String,
    val contractClass:         ContractClass,
    val executionType:         ExecutionType,
    val targetDomain:          TargetDomain,
    val position:              Int,
    val total:                 Int,
    val requiredCapabilities:  List<ContractCapability>,
    val constraints:           List<Any>,
    val outputDefinition:      OutputDefinition
) {
    init {
        require(contractId.isNotBlank())               { "contractId must not be blank" }
        require(intentId.isNotBlank())                 { "intentId must not be blank" }
        require(reportReference.isNotBlank())          { "reportReference must not be blank" }
        require(position >= 1)                         { "position must be ≥ 1" }
        require(total >= position)                     { "total must be ≥ position" }
        require(requiredCapabilities.isNotEmpty())     { "requiredCapabilities must not be empty" }
    }
}
