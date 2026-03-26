package com.agoii.mobile.contractors

import java.util.concurrent.ConcurrentHashMap

// ─── Constants ────────────────────────────────────────────────────────────────

private const val MAX_CAPABILITY_LEVEL = 5

// ─── Capability ───────────────────────────────────────────────────────────────

data class Capability(
    val name: String,
    val level: Int
) {
    init {
        require(level in 0..MAX_CAPABILITY_LEVEL) { "level must be in [0, $MAX_CAPABILITY_LEVEL], got $level" }
    }
}

// ─── ContractRequirement ──────────────────────────────────────────────────────

data class ContractRequirement(
    val capability: String,
    val requiredLevel: Int,
    val weight: Double
)

// ─── ContractorProfile ────────────────────────────────────────────────────────

data class ContractorProfile(
    val contractorId: String,
    val capabilities: List<Capability>,
    val reliabilityScore: Double
)

// ─── ContractorRegistry ───────────────────────────────────────────────────────

class ContractorRegistry {
    private val profiles = ConcurrentHashMap<String, ContractorProfile>()

    fun register(profile: ContractorProfile) {
        profiles[profile.contractorId] = profile
    }

    fun lookup(contractorId: String): ContractorProfile? = profiles[contractorId]

    fun all(): List<ContractorProfile> = profiles.values.toList()

    fun remove(contractorId: String) {
        profiles.remove(contractorId)
    }
}

// ─── RejectedContractor ───────────────────────────────────────────────────────

data class RejectedContractor(
    val contractorId: String,
    val reason: String
)

// ─── ResolutionTrace ──────────────────────────────────────────────────────────

data class ResolutionTrace(
    val phase: String,
    val contractorId: String,
    val score: Double? = null,
    val note: String
)

// ─── ResolutionResult ─────────────────────────────────────────────────────────

sealed class ResolutionResult {
    data class Matched(
        val contractor: ContractorProfile,
        val score: Double,
        val trace: List<ResolutionTrace>
    ) : ResolutionResult()

    data class Swarm(
        val contractors: List<ContractorProfile>,
        val trace: List<ResolutionTrace>
    ) : ResolutionResult()

    data class Blocked(
        val rejected: List<RejectedContractor>,
        val trace: List<ResolutionTrace>
    ) : ResolutionResult()
}

// ─── DeterministicMatchingEngine ─────────────────────────────────────────────

class DeterministicMatchingEngine(private val registry: ContractorRegistry) {
    private val swarmEngine = SwarmCompositionEngine(registry)

    fun resolve(requirements: List<ContractRequirement>): ResolutionResult {
        val trace = mutableListOf<ResolutionTrace>()
        val candidates = registry.all()

        // Phase 1: Feasibility — each contractor must meet every requirement
        val feasible = mutableListOf<ContractorProfile>()
        val rejected = mutableListOf<RejectedContractor>()

        for (contractor in candidates) {
            val capMap = contractor.capabilities.associateBy { it.name }
            val failed = requirements.filter { req ->
                val cap = capMap[req.capability]
                cap == null || cap.level < req.requiredLevel
            }
            if (failed.isEmpty()) {
                feasible.add(contractor)
                trace.add(ResolutionTrace("FEASIBILITY", contractor.contractorId, note = "PASS"))
            } else {
                val reason = "Missing or insufficient: ${failed.joinToString { it.capability }}"
                rejected.add(RejectedContractor(contractor.contractorId, reason))
                trace.add(ResolutionTrace("FEASIBILITY", contractor.contractorId, note = "FAIL: $reason"))
            }
        }

        // Phase 2: Scoring — compute fitness for each feasible contractor
        val scored = feasible.map { contractor ->
            val score = computeFitness(contractor, requirements)
            trace.add(ResolutionTrace("SCORING", contractor.contractorId, score = score, note = "score=$score"))
            contractor to score
        }.sortedWith(
            compareByDescending<Pair<ContractorProfile, Double>> { it.second }
                .thenBy { it.first.contractorId }  // deterministic tiebreak
        )

        // Phase 3: Selection — highest score wins
        if (scored.isNotEmpty()) {
            val (winner, score) = scored.first()
            trace.add(ResolutionTrace("SELECTION", winner.contractorId, score = score, note = "SELECTED"))
            return ResolutionResult.Matched(winner, score, trace)
        }

        // No single contractor qualifies — attempt swarm composition
        return swarmEngine.compose(requirements, trace, rejected)
    }

    private fun computeFitness(contractor: ContractorProfile, requirements: List<ContractRequirement>): Double {
        if (requirements.isEmpty()) return contractor.reliabilityScore
        val capMap = contractor.capabilities.associateBy { it.name }
        var weightedSum = 0.0
        var totalWeight = 0.0
        for (req in requirements) {
            val cap = capMap[req.capability]
            val coverage = if (cap != null && cap.level >= req.requiredLevel) {
                cap.level.toDouble() / MAX_CAPABILITY_LEVEL.toDouble()
            } else 0.0
            weightedSum += coverage * req.weight
            totalWeight += req.weight
        }
        val capabilityScore = if (totalWeight > 0.0) weightedSum / totalWeight else 0.0
        return (capabilityScore + contractor.reliabilityScore) / 2.0
    }
}

// ─── SwarmCompositionEngine ───────────────────────────────────────────────────

class SwarmCompositionEngine(private val registry: ContractorRegistry) {

    fun compose(
        requirements: List<ContractRequirement>,
        trace: MutableList<ResolutionTrace>,
        rejected: List<RejectedContractor>
    ): ResolutionResult {
        val candidates = registry.all()
        val uncovered = requirements.toMutableSet()
        val swarm = mutableListOf<ContractorProfile>()
        val available = candidates.toMutableList()

        // Greedy set cover: each iteration selects the contractor that covers
        // the most uncovered requirements, with reliability as a tiebreak
        while (uncovered.isNotEmpty() && available.isNotEmpty()) {
            val best = available
                .map { contractor ->
                    val capMap = contractor.capabilities.associateBy { it.name }
                    val covered = uncovered.filter { req ->
                        val cap = capMap[req.capability]
                        cap != null && cap.level >= req.requiredLevel
                    }
                    Triple(contractor, covered, covered.size)
                }
                .filter { (_, covered, _) -> covered.isNotEmpty() }
                .sortedWith(
                    compareByDescending<Triple<ContractorProfile, List<ContractRequirement>, Int>> { it.third }
                        .thenByDescending { it.first.reliabilityScore }
                        .thenBy { it.first.contractorId }  // deterministic tiebreak
                )
                .firstOrNull() ?: break

            swarm.add(best.first)
            uncovered.removeAll(best.second.toSet())
            available.remove(best.first)
            trace.add(
                ResolutionTrace(
                    "SWARM",
                    best.first.contractorId,
                    note = "covers ${best.second.size} requirement(s)"
                )
            )
        }

        return if (uncovered.isEmpty()) {
            ResolutionResult.Swarm(swarm, trace)
        } else {
            trace.add(ResolutionTrace("SWARM", "none", note = "BLOCKED: ${uncovered.size} requirement(s) unresolvable"))
            ResolutionResult.Blocked(rejected, trace)
        }
    }
}
