package com.agoii.mobile.irs.admission

import com.agoii.mobile.irs.AdmissionDecision
import com.agoii.mobile.irs.AdmissionResult
import com.agoii.mobile.irs.ExecutionGraph
import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.RealityValidationResult
import com.agoii.mobile.irs.SwarmResult
import com.agoii.mobile.irs.ValidationCapacityComponents

/**
 * ElvcAdmissionLayer — the constitutional gatekeeper of execution.
 *
 * ELVC Contract (AGOII-ELVC-ADMISSION-LAYER):
 *  - Sits between IRS (certification) and the execution engine.
 *  - Enforces the invariant: EL ≤ VC before any contract may execute.
 *  - All calculations are integer-based, rule-based, and fully reproducible.
 *  - No weights, probabilities, ML scoring, or confidence thresholds are used.
 *
 * EL Formula (deterministic):
 *   EL = N + E + (C × 2)
 *   where N = nodes, E = edges, C = cross-links  (see [ExecutionGraph])
 *
 * VC Formula (deterministic):
 *   VC = EC + RC + SC + CC
 *   where:
 *     EC = fields with ≥ 2 distinct evidence sources
 *     RC = SimulationRuleResult evaluation count (IRS-05C)
 *     SC = same as RC; tracked separately for domain isolation
 *     CC = swarm agent validations that passed (= agentOutputs.size − conflicts.size)
 *
 * Admission Decision Table:
 *   EL < VC                          → ALLOW
 *   EL == VC                         → ALLOW_WITH_BOUNDARY
 *   EL > VC AND graph is decomposable → SPLIT  (subgraphs must satisfy EL ≤ VC independently)
 *   EL > VC AND NOT decomposable      → REJECT
 */
class ElvcAdmissionLayer {

    /**
     * Evaluate the admission condition for [intentData] using IRS outputs.
     *
     * @param intentData      The certified intent (post PCCV, post Certification).
     * @param realityResult   The [RealityValidationResult] produced during step 5.
     * @param swarmResult     The [SwarmResult] produced during step 6.
     * @param executionGraph  Pre-built [ExecutionGraph] derived from [intentData].
     * @return [AdmissionResult] with the deterministic admission decision and full calculation trace.
     */
    fun evaluate(
        intentData:     IntentData,
        realityResult:  RealityValidationResult,
        swarmResult:    SwarmResult,
        executionGraph: ExecutionGraph
    ): AdmissionResult {
        val el = executionGraph.executionLoad

        val vcComponents = computeVc(intentData, realityResult, swarmResult)
        val vc = vcComponents.validationCapacity

        val safetyCondition = el <= vc

        val decision = computeDecision(el, vc, executionGraph)

        val subGraphs = if (decision == AdmissionDecision.SPLIT) {
            decompose(executionGraph)
        } else {
            emptyList()
        }

        val rejectionReasons = if (decision == AdmissionDecision.REJECT) {
            listOf(
                "admission: EL ($el) > VC ($vc) and execution graph is not decomposable into valid subgraphs"
            )
        } else {
            emptyList()
        }

        return AdmissionResult(
            executionLoad       = el,
            validationCapacity  = vc,
            safetyCondition     = safetyCondition,
            decision            = decision,
            elTrace             = executionGraph,
            vcTrace             = vcComponents,
            rejectionReasons    = rejectionReasons,
            subGraphs           = subGraphs
        )
    }

    // ─── Private computation helpers ──────────────────────────────────────────

    /**
     * Compute Validation Capacity components from IRS outputs.
     *
     * EC: Evidence Coverage — count of mandatory fields that have ≥ 2 distinct evidence sources.
     * RC: Rule Coverage     — number of [SimulationRuleResult] evaluations in the reality stage.
     * SC: Simulation Coverage — identical count to RC; separate counter for domain isolation.
     * CC: Consensus Coverage — passing swarm agent count = agentOutputs.size − conflicts.size.
     */
    private fun computeVc(
        intentData:    IntentData,
        realityResult: RealityValidationResult,
        swarmResult:   SwarmResult
    ): ValidationCapacityComponents {
        val fields = listOf(
            intentData.objective,
            intentData.constraints,
            intentData.environment,
            intentData.resources
        )

        val ec = fields.count { f -> f.evidence.map { it.source }.toSet().size >= 2 }
        val rc = realityResult.simulationResult.evaluations.size
        val sc = rc
        val cc = (swarmResult.agentOutputs.size - swarmResult.conflicts.size).coerceAtLeast(0)

        return ValidationCapacityComponents(ec = ec, rc = rc, sc = sc, cc = cc)
    }

    /**
     * Determine the admission decision from EL, VC, and graph structure.
     *
     * CASE 1 — EL < VC              → ALLOW
     * CASE 2 — EL == VC             → ALLOW_WITH_BOUNDARY
     * CASE 3 — EL > VC, decomposable → SPLIT
     * CASE 4 — EL > VC, not decomposable → REJECT
     */
    private fun computeDecision(
        el:    Int,
        vc:    Int,
        graph: ExecutionGraph
    ): AdmissionDecision = when {
        el < vc  -> AdmissionDecision.ALLOW
        el == vc -> AdmissionDecision.ALLOW_WITH_BOUNDARY
        isDecomposable(graph) -> AdmissionDecision.SPLIT
        else     -> AdmissionDecision.REJECT
    }

    /**
     * A graph is decomposable when it contains ≥ 2 nodes and can be partitioned into subgraphs
     * that each reduce N, E, or C relative to the original.
     */
    private fun isDecomposable(graph: ExecutionGraph): Boolean = graph.nodes >= 2

    /**
     * Decompose [graph] into the smallest equal-sized subgraphs that each reduce N, E, and C.
     *
     * Each partition receives:
     *  - N_p = floor(N / 2) or ceil(N / 2) for balanced split.
     *  - E_p = max(0, N_p − 1) — internal sequential edges within the partition.
     *  - C_p = 0 — cross-links are eliminated by partitioning.
     */
    private fun decompose(graph: ExecutionGraph): List<ExecutionGraph> {
        if (graph.nodes < 2) return listOf(graph)
        val half = graph.nodes / 2
        val remainder = graph.nodes - half
        return listOf(
            ExecutionGraph(nodes = half,      edges = if (half > 1) half - 1 else 0,      crossLinks = 0),
            ExecutionGraph(nodes = remainder, edges = if (remainder > 1) remainder - 1 else 0, crossLinks = 0)
        )
    }
}
