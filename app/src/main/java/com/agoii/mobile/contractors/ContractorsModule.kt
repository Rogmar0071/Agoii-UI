package com.agoii.mobile.contractors

// ─── Capability ───────────────────────────────────────────────────────────────

/**
 * A named capability with a proficiency level.
 *
 * @property name  Unique capability identifier (e.g. "data_analysis").
 * @property level Proficiency level in [0, 5]. 0 = none, 5 = expert.
 */
data class Capability(
    val name:  String,
    val level: Int
) {
    init {
        require(level in 0..5) { "level must be in [0..5], got $level" }
    }
}

// ─── ContractRequirement ──────────────────────────────────────────────────────

/**
 * A single capability requirement that a contract imposes on its executor.
 *
 * @property capability    Name of the required capability.
 * @property requiredLevel Minimum proficiency level the contractor must possess [0, 5].
 * @property weight        Relative importance of this requirement for fitness scoring (> 0).
 */
data class ContractRequirement(
    val capability:    String,
    val requiredLevel: Int,
    val weight:        Double
) {
    init {
        require(requiredLevel in 0..5) { "requiredLevel must be in [0..5], got $requiredLevel" }
        require(weight > 0.0)          { "weight must be positive, got $weight" }
    }
}

// ─── ContractorProfile ────────────────────────────────────────────────────────

/**
 * Profile of a contractor registered in the [ContractorRegistry].
 *
 * @property contractorId      Unique identifier for this contractor.
 * @property capabilities      Declared capability set.
 * @property reliabilityScore  Historical reliability in [0.0, 1.0]. Defaults to 1.0.
 */
data class ContractorProfile(
    val contractorId:     String,
    val capabilities:     List<Capability>,
    val reliabilityScore: Double = 1.0
) {
    init {
        require(contractorId.isNotBlank())          { "contractorId must not be blank" }
        require(reliabilityScore in 0.0..1.0)       { "reliabilityScore must be in [0.0, 1.0]" }
    }

    /** Returns the declared level for [name], or 0 when the capability is absent. */
    fun capabilityLevel(name: String): Int =
        capabilities.firstOrNull { it.name == name }?.level ?: 0
}

// ─── ContractorRegistry ───────────────────────────────────────────────────────

/**
 * Authoritative, in-memory pool of registered contractors.
 *
 * Rules:
 *  - Only contractors present in the registry are eligible for selection.
 *  - [register] is idempotent: re-registering a contractor by ID replaces the
 *    previous entry.
 *  - The registry is read-only from the perspective of the matching engine;
 *    writes occur only through [register].
 */
class ContractorRegistry {

    private val store: MutableMap<String, ContractorProfile> = mutableMapOf()

    /** Register (or replace) [profile] in the pool. */
    fun register(profile: ContractorProfile) {
        store[profile.contractorId] = profile
    }

    /** Return the profile for [contractorId], or null when not found. */
    fun findById(contractorId: String): ContractorProfile? = store[contractorId]

    /** Return all registered profiles as an immutable snapshot. */
    fun all(): List<ContractorProfile> = store.values.toList()

    /** Number of registered contractors. */
    fun size(): Int = store.size
}

// ─── RejectedContractor ───────────────────────────────────────────────────────

/**
 * Records why a candidate was eliminated during the feasibility phase.
 *
 * @property contractorId Identifier of the eliminated contractor.
 * @property reasons      Ordered list of human-readable rejection reasons.
 * @property score        Fitness score at the point of rejection (0.0 when infeasible).
 */
data class RejectedContractor(
    val contractorId: String,
    val reasons:      List<String>,
    val score:        Double
)

// ─── ResolutionTrace ──────────────────────────────────────────────────────────

/**
 * Audit trail produced by [DeterministicMatchingEngine.resolve].
 *
 * @property requirements        The original requirements supplied to the engine.
 * @property candidatesEvaluated Total number of contractors evaluated (registry size).
 * @property feasibleCount       Number of contractors that passed the feasibility check.
 * @property rejected            Contractors eliminated with their reasons and scores.
 */
data class ResolutionTrace(
    val requirements:        List<ContractRequirement>,
    val candidatesEvaluated: Int,
    val feasibleCount:       Int,
    val rejected:            List<RejectedContractor>
)

// ─── ResolutionResult ─────────────────────────────────────────────────────────

/**
 * Deterministic outcome of contract-to-contractor resolution.
 *
 * - [Matched]  A single contractor satisfies all requirements.
 * - [Swarm]    A set of contractors collectively satisfies all requirements.
 * - [Blocked]  No contractor or swarm can cover all requirements.
 */
sealed class ResolutionResult {

    /** A single contractor was selected. */
    data class Matched(
        val contractor: ContractorProfile,
        val score:      Double,
        val trace:      ResolutionTrace
    ) : ResolutionResult()

    /** Multiple contractors together satisfy the contract. */
    data class Swarm(
        val contractors: List<ContractorProfile>,
        val trace:       ResolutionTrace
    ) : ResolutionResult()

    /** No viable contractor or swarm could be composed. */
    data class Blocked(
        val reasons: List<String>,
        val trace:   ResolutionTrace
    ) : ResolutionResult()
}

// ─── DeterministicMatchingEngine ─────────────────────────────────────────────

/**
 * Resolves a contract's [ContractRequirement] list to the best matching contractor
 * using a deterministic three-phase algorithm.
 *
 * Phase 1 — Feasibility check
 *   Binary filter: a contractor must meet or exceed every required level.
 *   Contractors that fail are recorded as [RejectedContractor] entries in the trace.
 *
 * Phase 2 — Scoring
 *   Fitness(contractor, requirements) ∈ [0.0, 1.0]:
 *     Σ (actualLevel / 5.0 × weight) / Σ weight  ×  reliabilityScore
 *
 * Phase 3 — Selection
 *   The feasible contractor with the highest score is selected.
 *   If no feasible contractor exists, [SwarmCompositionEngine] attempts a swarm.
 *   Same input always produces the same output (deterministic tie-breaking by id).
 *
 * @property registry    Authoritative contractor pool.
 * @property swarmEngine Engine used when single-contractor resolution fails.
 */
class DeterministicMatchingEngine(
    private val registry:    ContractorRegistry,
    private val swarmEngine: SwarmCompositionEngine = SwarmCompositionEngine()
) {

    /**
     * Resolve [requirements] to a [ResolutionResult].
     *
     * @param requirements Non-empty list of capability requirements.
     * @return [ResolutionResult.Matched], [ResolutionResult.Swarm], or [ResolutionResult.Blocked].
     */
    fun resolve(requirements: List<ContractRequirement>): ResolutionResult {
        val pool = registry.all().sortedBy { it.contractorId }   // deterministic order

        // ── Phase 1: Feasibility ─────────────────────────────────────────────
        val rejected   = mutableListOf<RejectedContractor>()
        val feasible   = mutableListOf<ContractorProfile>()

        for (profile in pool) {
            val gaps = requirements.filter { req ->
                profile.capabilityLevel(req.capability) < req.requiredLevel
            }
            if (gaps.isEmpty()) {
                feasible += profile
            } else {
                rejected += RejectedContractor(
                    contractorId = profile.contractorId,
                    reasons      = gaps.map { req ->
                        "Capability '${req.capability}': " +
                        "required ${req.requiredLevel}, " +
                        "has ${profile.capabilityLevel(req.capability)}"
                    },
                    score        = 0.0
                )
            }
        }

        val trace = ResolutionTrace(
            requirements        = requirements,
            candidatesEvaluated = pool.size,
            feasibleCount       = feasible.size,
            rejected            = rejected
        )

        // ── Phase 2: Scoring ─────────────────────────────────────────────────
        val scored = feasible.map { profile -> profile to computeScore(profile, requirements) }

        // ── Phase 3: Selection ───────────────────────────────────────────────
        val best = scored.maxWithOrNull(
            compareByDescending<Pair<ContractorProfile, Double>> { it.second }
                .thenBy { it.first.contractorId }   // deterministic tie-break (ascending id)
        )

        return if (best != null) {
            ResolutionResult.Matched(
                contractor = best.first,
                score      = best.second,
                trace      = trace
            )
        } else {
            swarmEngine.compose(requirements, pool, trace)
        }
    }

    // ── Internal: Fitness scoring ────────────────────────────────────────────

    private fun computeScore(
        profile:      ContractorProfile,
        requirements: List<ContractRequirement>
    ): Double {
        val totalWeight = requirements.sumOf { it.weight }
        if (totalWeight == 0.0) return 0.0

        val weightedCoverage = requirements.sumOf { req ->
            val level    = profile.capabilityLevel(req.capability).toDouble()
            val coverage = level / 5.0          // normalize to [0, 1]; max level = 5
            coverage * req.weight
        }

        return (weightedCoverage / totalWeight) * profile.reliabilityScore
    }
}

// ─── SwarmCompositionEngine ───────────────────────────────────────────────────

/**
 * Constructs a minimal swarm of contractors that collectively satisfies all
 * requirements using a greedy set-cover algorithm.
 *
 * Algorithm:
 *   While there are unmet requirements and eligible candidates remain:
 *     1. Pick the contractor (sorted by id for determinism) that covers the
 *        greatest number of currently unmet requirements.
 *     2. Remove those requirements from the unmet set.
 *     3. Remove the selected contractor from the candidate pool.
 *   If all requirements are covered → [ResolutionResult.Swarm].
 *   Otherwise                       → [ResolutionResult.Blocked].
 */
class SwarmCompositionEngine {

    /**
     * Attempt to compose a swarm covering all [requirements] from [pool].
     *
     * @param requirements Full list of requirements the swarm must satisfy.
     * @param pool         Ordered list of all registered contractors (sorted by id).
     * @param trace        Trace produced by the calling matching engine.
     * @return [ResolutionResult.Swarm] on success; [ResolutionResult.Blocked] on failure.
     */
    fun compose(
        requirements: List<ContractRequirement>,
        pool:         List<ContractorProfile>,
        trace:        ResolutionTrace
    ): ResolutionResult {
        val unmet     = requirements.toMutableList()
        val selected  = mutableListOf<ContractorProfile>()
        val remaining = pool.toMutableList()

        while (unmet.isNotEmpty() && remaining.isNotEmpty()) {
            // Greedy: pick the contractor covering the most unmet requirements.
            // Sort by id as secondary key to guarantee determinism across ties.
            val best = remaining.maxWithOrNull(
                compareByDescending<ContractorProfile> { candidate ->
                    unmet.count { req -> candidate.capabilityLevel(req.capability) >= req.requiredLevel }
                }.thenBy { it.contractorId }
            ) ?: break

            val covered = unmet.filter { req ->
                best.capabilityLevel(req.capability) >= req.requiredLevel
            }

            if (covered.isEmpty()) break    // no further progress is possible

            selected  += best
            remaining -= best
            unmet     -= covered
        }

        return if (unmet.isEmpty()) {
            ResolutionResult.Swarm(contractors = selected, trace = trace)
        } else {
            ResolutionResult.Blocked(
                reasons = unmet.map { req ->
                    "No contractor available for capability '${req.capability}' " +
                    "at level ${req.requiredLevel}"
                },
                trace = trace
            )
        }
    }
}
