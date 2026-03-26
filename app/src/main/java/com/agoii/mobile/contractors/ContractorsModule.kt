// CONTRACT: CONTRACTORS_MODULE_V1
// CLASSIFICATION: Structural
// REVISION: CR-1
// GOVERNANCE TIER: GT-CORE
// CONFIDENCE INDEX TARGET: ≥ 14/15
//
// AUTHORITY SOURCE:
// → Agoii_Architecture.md (MASTER LAW)
// → AGOII_ENFORCED_STRICT (ISSUE)
//
// EXECUTION PERMISSION: READ-ONLY (registry + matching only; no writes)
// MUTATION PROHIBITION: ABSOLUTE
//
// PURPOSE:
// Provide deterministic, auditable, mathematically grounded resolution of:
//   Contract → Contractor(s)
//
// SCOPE:
// - Contractor Registry (authoritative, immutable at runtime)
// - Capability & Constraint modeling
// - Deterministic Matching Engine
// - Swarm Composition Engine
// - Full audit trace output
//
// NON-GOALS:
// - No EventLedger access
// - No ExecutionAuthority logic
// - No Governor logic
// - No contract derivation
// - No execution
//
// FAILURE MODE:
// IF no valid contractor OR no valid swarm:
// → RETURN ResolutionResult.Blocked(reason)
//
// ─────────────────────────────────────────────────────────────────────────────

package com.agoii.mobile.contractors

import kotlin.math.max

// ─────────────────────────────────────────────────────────────────────────────
// 1. CAPABILITY MODEL
// ─────────────────────────────────────────────────────────────────────────────

enum class Capability {
    CODE_GENERATION,
    SYSTEM_DESIGN,
    API_INTEGRATION,
    INFRASTRUCTURE,
    DATA_ANALYSIS,
    NATURAL_LANGUAGE,
    UI_GENERATION,
    TESTING,
    REFACTORING
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. CONTRACT REQUIREMENT (INPUT FROM CONTRACT SYSTEM)
// ─────────────────────────────────────────────────────────────────────────────

data class ContractRequirement(
    val contractId: String,
    val requiredCapabilities: Set<Capability>,
    val forbiddenCapabilities: Set<Capability> = emptySet(),
    val maxDriftTolerance: Double = 0.3,     // [0..1]
    val minReliability: Double = 0.5,        // [0..1]
    val allowSwarm: Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────
// 3. CONTRACTOR PROFILE
// ─────────────────────────────────────────────────────────────────────────────

data class ContractorProfile(
    val contractorId: String,
    val capabilities: Set<Capability>,
    val reliability: Double,   // success probability [0..1]
    val drift: Double,         // [0..1] lower is better
    val latency: Double        // relative cost proxy (not used in scoring v1)
) {
    init {
        require(reliability in 0.0..1.0) { "reliability must be in [0.0, 1.0]" }
        require(drift in 0.0..1.0) { "drift must be in [0.0, 1.0]" }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. REGISTRY (MANDATORY SOURCE OF TRUTH)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ContractorRegistry — authoritative, immutable-at-runtime store of contractors.
 *
 * Invariants:
 *  - The contractor list is fixed at construction time; no runtime mutations.
 *  - All resolution queries MUST go through this registry.
 *  - Returns empty/null rather than throwing on miss.
 */
class ContractorRegistry(
    private val contractors: List<ContractorProfile>
) {

    fun all(): List<ContractorProfile> = contractors

    fun findByCapability(capability: Capability): List<ContractorProfile> =
        contractors.filter { capability in it.capabilities }

    fun get(contractorId: String): ContractorProfile? =
        contractors.firstOrNull { it.contractorId == contractorId }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. MATCH RESULT STRUCTURES
// ─────────────────────────────────────────────────────────────────────────────

data class ContractorScore(
    val contractor: ContractorProfile,
    val capabilityScore: Double,
    val reliabilityScore: Double,
    val driftPenalty: Double,
    val finalScore: Double
)

data class RejectedContractor(
    val contractor: ContractorProfile,
    val reason: String
)

data class ResolutionTrace(
    val evaluated: List<ContractorScore>,
    val rejected: List<RejectedContractor>,
    val selectedId: String?,
    val swarmIds: List<String>?
)

// ─────────────────────────────────────────────────────────────────────────────
// 6. RESOLUTION RESULT
// ─────────────────────────────────────────────────────────────────────────────

sealed class ResolutionResult {

    /** A single contractor was selected deterministically. */
    data class Matched(
        val contractor: ContractorProfile,
        val score: ContractorScore,
        val trace: ResolutionTrace
    ) : ResolutionResult()

    /** A deterministic swarm of contractors collectively covers all requirements. */
    data class Swarm(
        val contractors: List<ContractorProfile>,
        val trace: ResolutionTrace
    ) : ResolutionResult()

    /** No valid assignment could be made; reason documents the blocking cause. */
    data class Blocked(
        val reason: String,
        val trace: ResolutionTrace? = null
    ) : ResolutionResult()
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. DETERMINISTIC MATCHING ENGINE
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DeterministicMatchingEngine — resolves a [ContractRequirement] to a single
 * [ContractorProfile] or a swarm, using the [ContractorRegistry] as the sole
 * source of truth.
 *
 * Invariants:
 *  - No EventLedger access.
 *  - No side effects — pure resolution function.
 *  - All selection is deterministic: equal inputs always produce equal outputs.
 *
 * Algorithm:
 *  Phase 1 — Feasibility: reject contractors that violate hard constraints
 *             (forbidden capabilities, minimum reliability, maximum drift).
 *  Phase 2 — Scoring: compute fitness score for each feasible contractor.
 *  Phase 3 — Selection: choose the highest-scored contractor with full capability
 *             coverage, or delegate to [SwarmCompositionEngine].
 */
class DeterministicMatchingEngine(
    private val registry: ContractorRegistry,
    private val swarmEngine: SwarmCompositionEngine = SwarmCompositionEngine()
) {

    companion object {
        // Scoring weights — must sum to 1.0
        private const val WEIGHT_CAPABILITY  = 0.5
        private const val WEIGHT_RELIABILITY = 0.3
        private const val WEIGHT_DRIFT       = 0.2
    }

    /**
     * Resolve [requirement] deterministically against the registry.
     *
     * @return [ResolutionResult.Matched] when one contractor covers all requirements,
     *         [ResolutionResult.Swarm] when a valid swarm is composed,
     *         [ResolutionResult.Blocked] when no valid assignment can be made.
     */
    fun resolve(requirement: ContractRequirement): ResolutionResult {
        val all = registry.all()
        if (all.isEmpty()) {
            return ResolutionResult.Blocked("Registry is empty — no contractors available")
        }

        // Phase 1 — Feasibility
        val rejected  = mutableListOf<RejectedContractor>()
        val feasible  = mutableListOf<ContractorProfile>()
        for (contractor in all) {
            val reason = feasibilityRejectionReason(contractor, requirement)
            if (reason != null) {
                rejected += RejectedContractor(contractor, reason)
            } else {
                feasible += contractor
            }
        }

        if (feasible.isEmpty()) {
            val trace = ResolutionTrace(
                evaluated  = emptyList(),
                rejected   = rejected,
                selectedId = null,
                swarmIds   = null
            )
            return ResolutionResult.Blocked("No contractor passed feasibility checks", trace)
        }

        // Phase 2 — Scoring (stable, deterministic order: score desc, then id asc)
        val scored = feasible
            .map { score(it, requirement) }
            .sortedWith(
                compareByDescending<ContractorScore> { it.finalScore }
                    .thenBy { it.contractor.contractorId }
            )

        // Phase 3 — Selection: prefer a single contractor with full capability coverage
        val fullCoverage = scored.filter { it.capabilityScore >= 1.0 }
        if (fullCoverage.isNotEmpty()) {
            val best  = fullCoverage.first()
            val trace = ResolutionTrace(
                evaluated  = scored,
                rejected   = rejected,
                selectedId = best.contractor.contractorId,
                swarmIds   = null
            )
            return ResolutionResult.Matched(best.contractor, best, trace)
        }

        // No single full-coverage contractor — attempt swarm if permitted
        if (!requirement.allowSwarm) {
            val trace = ResolutionTrace(
                evaluated  = scored,
                rejected   = rejected,
                selectedId = null,
                swarmIds   = null
            )
            return ResolutionResult.Blocked(
                "No single contractor covers all required capabilities and swarm is disabled",
                trace
            )
        }

        return swarmEngine.compose(requirement, feasible, scored, rejected)
    }

    // ─── Feasibility ──────────────────────────────────────────────────────────

    private fun feasibilityRejectionReason(
        contractor: ContractorProfile,
        requirement: ContractRequirement
    ): String? {
        val forbidden = contractor.capabilities.intersect(requirement.forbiddenCapabilities)
        if (forbidden.isNotEmpty()) {
            return "Has forbidden capabilities: $forbidden"
        }
        if (contractor.reliability < requirement.minReliability) {
            return "Reliability ${contractor.reliability} < required ${requirement.minReliability}"
        }
        if (contractor.drift > requirement.maxDriftTolerance) {
            return "Drift ${contractor.drift} > tolerance ${requirement.maxDriftTolerance}"
        }
        return null
    }

    // ─── Scoring ──────────────────────────────────────────────────────────────

    /**
     * Compute the fitness score for [contractor] against [requirement].
     *
     * - capabilityScore  = (required capabilities covered) / (total required)  [0..1]
     * - reliabilityScore = contractor.reliability                               [0..1]
     * - driftPenalty     = contractor.drift                                     [0..1]
     * - finalScore       = capScore×0.5 + relScore×0.3 + (1−drift)×0.2         [0..1]
     */
    private fun score(contractor: ContractorProfile, requirement: ContractRequirement): ContractorScore {
        val requiredCount = max(requirement.requiredCapabilities.size, 1)
        val matched       = contractor.capabilities.intersect(requirement.requiredCapabilities).size
        val capScore      = matched.toDouble() / requiredCount
        val relScore      = contractor.reliability
        val driftPenalty  = contractor.drift
        val finalScore    = capScore * WEIGHT_CAPABILITY +
                            relScore * WEIGHT_RELIABILITY +
                            (1.0 - driftPenalty) * WEIGHT_DRIFT
        return ContractorScore(
            contractor       = contractor,
            capabilityScore  = capScore,
            reliabilityScore = relScore,
            driftPenalty     = driftPenalty,
            finalScore       = finalScore
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 8. SWARM COMPOSITION ENGINE
// ─────────────────────────────────────────────────────────────────────────────

/**
 * SwarmCompositionEngine — constructs the minimal deterministic swarm of
 * contractors that collectively satisfies all [ContractRequirement.requiredCapabilities].
 *
 * Invariants:
 *  - No EventLedger access.
 *  - Pure function: no state, no side effects.
 *  - Selection is greedy but deterministic: at each step, the contractor covering
 *    the most uncovered capabilities is chosen; equal-coverage ties are broken by
 *    preferring the lexicographically earlier contractorId.
 *
 * Failure mode:
 *  - Returns [ResolutionResult.Blocked] when the feasible pool cannot collectively
 *    cover all required capabilities.
 */
class SwarmCompositionEngine {

    /**
     * Compose a minimal swarm from [feasible] contractors to cover all
     * [ContractRequirement.requiredCapabilities].
     *
     * @param requirement  The original contract requirement.
     * @param feasible     Contractors that passed feasibility checks.
     * @param scored       Pre-computed scores for all feasible contractors (for trace).
     * @param rejected     Contractors rejected during feasibility (for audit trace).
     * @return [ResolutionResult.Swarm] on success, [ResolutionResult.Blocked] on failure.
     */
    fun compose(
        requirement: ContractRequirement,
        feasible: List<ContractorProfile>,
        scored: List<ContractorScore>,
        rejected: List<RejectedContractor>
    ): ResolutionResult {
        val remaining = requirement.requiredCapabilities.toMutableSet()
        val swarm     = mutableListOf<ContractorProfile>()
        val pool      = feasible.toMutableList()

        // Greedy set cover: pick the contractor covering the most uncovered capabilities.
        // Tie-break: prefer the lexicographically earlier contractorId for determinism.
        // compareBy (ascending coverage) + maxWithOrNull selects the highest-coverage contractor.
        // thenByDescending (id) means the lower/earlier id is "greater" and wins the tie.
        while (remaining.isNotEmpty() && pool.isNotEmpty()) {
            val best = pool
                .maxWithOrNull(
                    compareBy<ContractorProfile> { it.capabilities.intersect(remaining).size }
                        .thenByDescending { it.contractorId }
                ) ?: break
            val covered = best.capabilities.intersect(remaining)
            if (covered.isEmpty()) break   // no remaining contractor can cover anything

            swarm     += best
            remaining -= covered
            pool      -= best
        }

        val swarmIds = swarm.map { it.contractorId }
        return if (remaining.isEmpty()) {
            val trace = ResolutionTrace(
                evaluated  = scored,
                rejected   = rejected,
                selectedId = null,
                swarmIds   = swarmIds
            )
            ResolutionResult.Swarm(swarm, trace)
        } else {
            val trace = ResolutionTrace(
                evaluated  = scored,
                rejected   = rejected,
                selectedId = null,
                swarmIds   = null
            )
            ResolutionResult.Blocked(
                "Swarm composition failed: capabilities $remaining cannot be covered by any registered contractor",
                trace
            )
        }
    }
}
