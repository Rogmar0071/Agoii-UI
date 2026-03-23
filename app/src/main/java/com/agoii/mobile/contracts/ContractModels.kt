package com.agoii.mobile.contracts

// ─── L1 — Contract Engine Foundation Models ───────────────────────────────────

// ─── Module Enumeration ───────────────────────────────────────────────────────

/**
 * All system modules that may appear in a contract's mutation footprint.
 *
 * Each module carries a structural weight used in execution-load (EL) calculation:
 *   EL = sum of [ContractModule.weight] across all modules in the surface.
 */
enum class ContractModule(val weight: Int) {
    UI(1),
    CORE(3),
    GOVERNANCE(2),
    BRIDGE(2),
    ORCHESTRATION(2),
    IRS(3)
}

// ─── Intent ───────────────────────────────────────────────────────────────────

/**
 * Raw intent supplied by the caller before contract evaluation.
 *
 * @property objective    What the intent aims to achieve.
 * @property constraints  Boundary conditions that execution must respect.
 * @property environment  Runtime environment context.
 * @property resources    Resources required by the intent.
 */
data class ContractIntent(
    val objective:   String,
    val constraints: String,
    val environment: String,
    val resources:   String
)

// ─── Surface ──────────────────────────────────────────────────────────────────

/**
 * A single module entry in the mutation footprint, with the reason it was included.
 *
 * @property module Module this entry represents.
 * @property reason Human-readable justification for including this module.
 */
data class MappedModule(
    val module: ContractModule,
    val reason: String
)

/**
 * Exact mutation footprint derived from a [ContractIntent] by [SurfaceMapper].
 *
 * @property modules     Ordered list of mapped modules (sorted by [ContractModule] ordinal).
 * @property totalWeight Sum of [ContractModule.weight] across all mapped modules.
 */
data class ContractSurface(
    val modules:     List<MappedModule>,
    val totalWeight: Int
) {
    companion object {
        fun from(modules: List<MappedModule>) = ContractSurface(
            modules     = modules,
            totalWeight = modules.sumOf { it.module.weight }
        )
    }
}

// ─── Failure Mapping ──────────────────────────────────────────────────────────

/** Classifies the category of a contract failure mode. */
enum class ContractFailureType {
    LOAD_EXCEEDED,
    MISSING_RESOURCE,
    CONSTRAINT_VIOLATED,
    DEPENDENCY_MISSING
}

/**
 * A potential failure mode attached to a surface module.
 *
 * @property module      The module this failure mode is associated with.
 * @property type        Category of failure.
 * @property description Human-readable explanation of how this failure could occur.
 * @property critical    true when this failure causes hard rejection (REJECTED outcome).
 */
data class ContractFailure(
    val module:      ContractModule,
    val type:        ContractFailureType,
    val description: String,
    val critical:    Boolean
)

/**
 * Complete failure landscape derived from a [ContractSurface] by [FailureMapper].
 *
 * @property failures    All identified failure modes, ordered by module.
 * @property hasCritical true when at least one [ContractFailure.critical] entry is present.
 */
data class FailureMap(
    val failures:    List<ContractFailure>,
    val hasCritical: Boolean
)

// ─── Execution Decomposition ──────────────────────────────────────────────────

/**
 * A single atomic execution step in a contract's execution plan.
 *
 * @property position    1-based position in the ordered plan.
 * @property description What this step accomplishes.
 * @property module      Which module this step belongs to.
 * @property load        Execution load contributed by this step (equals [ContractModule.weight]).
 */
data class ExecutionStep(
    val position:    Int,
    val description: String,
    val module:      ContractModule,
    val load:        Int
)

/**
 * Ordered decomposition of a contract into atomic execution steps, produced by
 * [ExecutionDecomposer].
 *
 * @property steps     Steps in execution order (1-based positions).
 * @property totalLoad Sum of [ExecutionStep.load] across all steps.
 */
data class ExecutionPlan(
    val steps:     List<ExecutionStep>,
    val totalLoad: Int
) {
    companion object {
        fun from(steps: List<ExecutionStep>) = ExecutionPlan(
            steps     = steps,
            totalLoad = steps.sumOf { it.load }
        )
    }
}

// ─── Constraint Enforcement ───────────────────────────────────────────────────

/**
 * A constraint violation found during constraint enforcement.
 *
 * @property step       The execution step that violated a constraint.
 * @property constraint The constraint that was violated (human-readable label).
 * @property reason     Explanation of how the violation occurred.
 */
data class ConstraintViolation(
    val step:       ExecutionStep,
    val constraint: String,
    val reason:     String
)

/**
 * Output of the [ConstraintEnforcer] pass.
 *
 * @property passed     true only when no violations were found.
 * @property violations All detected violations (empty when [passed] = true).
 */
data class ConstraintResult(
    val passed:     Boolean,
    val violations: List<ConstraintViolation>
)

// ─── Deterministic Derivation ─────────────────────────────────────────────────

/** Terminal outcome of a contract evaluation. */
enum class ContractOutcome { APPROVED, REJECTED }

/**
 * Fully deterministic derivation result for a [ContractIntent], produced by
 * [DeterministicDeriver].
 *
 * All intermediate artifacts are preserved for complete traceability.
 *
 * @property outcome       APPROVED or REJECTED.
 * @property surface       Mutation footprint from Step 1.
 * @property failureMap    Failure landscape from Step 2.
 * @property executionPlan Decomposed steps from Step 3.
 * @property constraints   Constraint enforcement result from Step 4.
 * @property trace         Ordered list of decision reasons covering every step.
 */
data class ContractDerivation(
    val outcome:       ContractOutcome,
    val surface:       ContractSurface,
    val failureMap:    FailureMap,
    val executionPlan: ExecutionPlan,
    val constraints:   ConstraintResult,
    val trace:         List<String>
)
