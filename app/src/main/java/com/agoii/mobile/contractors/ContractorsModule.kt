package com.agoii.mobile.contractors

data class Capability(val name: String, val level: Int) {
init {
require(level in 0..5)
}
}

data class ContractRequirement(val capability: String, val requiredLevel: Int, val weight: Double) {
init {
require(requiredLevel in 0..5)
}
}

data class ContractorProfile(
val contractorId: String,
val capabilities: List<Capability>,
val reliabilityScore: Double,
val costScore: Double,
val availabilityScore: Double
) {
init {
require(reliabilityScore in 0.0..1.0)
require(costScore in 0.0..1.0)
require(availabilityScore in 0.0..1.0)
}
}

interface ContractorRegistry {
fun getAll(): List<ContractorProfile>
}

data class RejectedContractor(val contractorId: String, val reason: String)

data class ResolutionTrace(val evaluated: Int, val matched: Int, val rejected: List<RejectedContractor>)

sealed class ResolutionResult {
data class Matched(val contractor: ContractorProfile, val score: Double, val trace: ResolutionTrace) : ResolutionResult()
data class Swarm(val contractors: List<ContractorProfile>, val combinedScore: Double, val trace: ResolutionTrace) : ResolutionResult()
data class Blocked(val reason: String, val trace: ResolutionTrace) : ResolutionResult()
}

class DeterministicMatchingEngine {

fun resolve(requirements: List<ContractRequirement>, registry: ContractorRegistry): ResolutionResult {

    val contractors = registry.getAll()

    if (contractors.isEmpty()) {
        return ResolutionResult.Blocked("REGISTRY_EMPTY", ResolutionTrace(0, 0, emptyList()))
    }

    val rejected = mutableListOf<RejectedContractor>()
    val valid = mutableListOf<ContractorProfile>()

    for (c in contractors) {
        var ok = true
        for (r in requirements) {
            val cap = c.capabilities.find { it.name == r.capability }
            if (cap == null) {
                rejected.add(RejectedContractor(c.contractorId, "MISSING_CAPABILITY"))
                ok = false
                break
            }
            if (cap.level < r.requiredLevel) {
                rejected.add(RejectedContractor(c.contractorId, "INSUFFICIENT_LEVEL"))
                ok = false
                break
            }
        }
        if (ok) valid.add(c)
    }

    if (valid.isNotEmpty()) {

        var best: ContractorProfile? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (c in valid) {
            var s = 0.0
            for (r in requirements) {
                val cap = c.capabilities.first { it.name == r.capability }
                s += r.weight * cap.level
            }
            s *= c.reliabilityScore
            s *= c.availabilityScore
            s *= (1 - c.costScore)

            if (best == null || s > bestScore || (s == bestScore && c.contractorId < best!!.contractorId)) {
                best = c
                bestScore = s
            }
        }

        return ResolutionResult.Matched(best!!, bestScore, ResolutionTrace(contractors.size, 1, rejected))
    }

    val uncovered = requirements.toMutableSet()
    val selected = mutableListOf<ContractorProfile>()

    while (uncovered.isNotEmpty()) {
        val best = contractors.maxByOrNull { c ->
            uncovered.count { r ->
                c.capabilities.any { it.name == r.capability && it.level >= r.requiredLevel }
            }
        } ?: break

        val covered = uncovered.filter { r ->
            best.capabilities.any { it.name == r.capability && it.level >= r.requiredLevel }
        }

        if (covered.isEmpty()) break

        selected.add(best)
        uncovered.removeAll(covered)
    }

    if (uncovered.isEmpty()) {

        val scores = selected.map { c ->
            var s = 0.0
            for (r in requirements) {
                val cap = c.capabilities.find { it.name == r.capability }
                if (cap != null) s += r.weight * cap.level
            }
            s *= c.reliabilityScore
            s *= c.availabilityScore
            s *= (1 - c.costScore)
            s
        }

        return ResolutionResult.Swarm(selected, scores.average(), ResolutionTrace(contractors.size, selected.size, rejected))
    }

    return ResolutionResult.Blocked("NO_FEASIBLE_CONTRACTOR", ResolutionTrace(contractors.size, 0, rejected))
}

}
