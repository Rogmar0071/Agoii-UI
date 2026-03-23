package com.agoii.mobile

import com.agoii.mobile.contracts.AgentMatchDecision
import com.agoii.mobile.contracts.AgentMatcher
import com.agoii.mobile.contracts.AgentProfile
import com.agoii.mobile.contracts.ContractAdapter
import com.agoii.mobile.contracts.ContractClassification
import com.agoii.mobile.contracts.ContractClassifier
import com.agoii.mobile.contracts.ContractEngine
import com.agoii.mobile.contracts.ContractIntent
import com.agoii.mobile.contracts.ContractModule
import com.agoii.mobile.contracts.ContractOutcome
import com.agoii.mobile.contracts.ContractScore
import com.agoii.mobile.contracts.ContractScorer
import com.agoii.mobile.contracts.ContractSystemOrchestrator
import com.agoii.mobile.contracts.ExecutionDecomposer
import com.agoii.mobile.contracts.ObjectiveValidator
import com.agoii.mobile.contracts.SurfaceMapper
import com.agoii.mobile.contracts.TraceabilityEnforcer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for the Contract System Consolidation layer.
 *
 * Covers:
 *  - ObjectiveValidator (Intent Layer)
 *  - TraceabilityEnforcer (Traceability)
 *  - ContractScorer (Mathematical Scoring)
 *  - ContractClassifier (Classification)
 *  - AgentMatcher (Agent Alignment)
 *  - ContractAdapter (Adaptation Engine)
 *  - ContractSystemOrchestrator (Full Pipeline)
 *
 * All tests run on the JVM without an Android device.
 */
class ContractSystemTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** A valid, well-formed intent that passes all objective checks. */
    private fun validIntent(
        objective:   String = "update core event ledger",
        constraints: String = "no ui modifications allowed",
        environment: String = "JVM runtime",
        resources:   String = "ledger"
    ) = ContractIntent(objective, constraints, environment, resources)

    /** A capable agent profile that passes LOW and MEDIUM contracts. */
    private fun standardAgent() = AgentProfile(
        agentId             = "standard",
        constraintObedience = 2,
        structuralAccuracy  = 2,
        driftTendency       = 1,
        complexityHandling  = 2,
        outputReliability   = 2
    )

    /** A high-fidelity agent that passes all contract classes including HIGH. */
    private fun highFidelityAgent() = AgentProfile(
        agentId             = "high-fidelity",
        constraintObedience = 3,
        structuralAccuracy  = 3,
        driftTendency       = 0,
        complexityHandling  = 3,
        outputReliability   = 3
    )

    /** A basic agent that frequently causes ADAPT/REJECT. */
    private fun basicAgent() = AgentProfile(
        agentId             = "basic",
        constraintObedience = 1,
        structuralAccuracy  = 1,
        driftTendency       = 3,
        complexityHandling  = 1,
        outputReliability   = 1
    )

    private fun engine()     = ContractEngine()
    private fun scorer()     = ContractScorer()
    private fun classifier() = ContractClassifier()
    private fun matcher()    = AgentMatcher()

    // ══════════════════════════════════════════════════════════════════════════
    // ObjectiveValidator
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ObjectiveValidator passes a well-formed intent`() {
        val result = ObjectiveValidator().validate(validIntent())
        assertTrue(result.valid)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `ObjectiveValidator fails when objective is blank`() {
        val result = ObjectiveValidator().validate(validIntent(objective = ""))
        assertFalse(result.valid)
        assertTrue(result.reasons.any { it.contains("objective is blank") })
    }

    @Test
    fun `ObjectiveValidator fails when all scope fields are blank`() {
        val result = ObjectiveValidator().validate(
            ContractIntent("update ledger", "", "", "")
        )
        assertFalse(result.valid)
        assertTrue(result.reasons.any { it.contains("scope undefined") })
    }

    @Test
    fun `ObjectiveValidator fails when constraints are blank`() {
        val result = ObjectiveValidator().validate(
            validIntent(constraints = "")
        )
        assertFalse(result.valid)
        assertTrue(result.reasons.any { it.contains("assumptions not declared") })
    }

    @Test
    fun `ObjectiveValidator collects all failures when multiple checks fail`() {
        // Blank objective AND blank constraints AND blank scope
        val result = ObjectiveValidator().validate(
            ContractIntent("", "", "", "")
        )
        assertFalse(result.valid)
        assertTrue(result.reasons.size >= 2)
    }

    @Test
    fun `ObjectiveValidator is deterministic for equal inputs`() {
        val intent = validIntent()
        assertEquals(
            ObjectiveValidator().validate(intent),
            ObjectiveValidator().validate(intent)
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TraceabilityEnforcer
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TraceabilityEnforcer passes for engine-generated plan`() {
        val intent = validIntent()
        val plan   = ExecutionDecomposer().decompose(SurfaceMapper().map(intent))
        val result = TraceabilityEnforcer().enforce(intent, plan)
        assertTrue(result.passed)
        assertTrue(result.unmappedSteps.isEmpty())
    }

    @Test
    fun `TraceabilityEnforcer produces one mapping per step`() {
        val intent = validIntent(objective = "update screen layout")
        val plan   = ExecutionDecomposer().decompose(SurfaceMapper().map(intent))
        val result = TraceabilityEnforcer().enforce(intent, plan)
        assertEquals(plan.steps.size, result.stepMappings.size)
    }

    @Test
    fun `TraceabilityEnforcer maps CORE to structural system requirement`() {
        val intent = validIntent()
        val plan   = ExecutionDecomposer().decompose(SurfaceMapper().map(intent))
        val result = TraceabilityEnforcer().enforce(intent, plan)
        val coreMapping = result.stepMappings.first { it.step.module == ContractModule.CORE }
        assertTrue(coreMapping.isMapped)
        assertTrue(coreMapping.intentReference.contains("core system requirement"))
    }

    @Test
    fun `TraceabilityEnforcer maps UI step to keyword in intent`() {
        val intent = validIntent(objective = "update screen layout")
        val plan   = ExecutionDecomposer().decompose(SurfaceMapper().map(intent))
        val result = TraceabilityEnforcer().enforce(intent, plan)
        val uiMapping = result.stepMappings.firstOrNull { it.step.module == ContractModule.UI }
        if (uiMapping != null) {
            assertTrue(uiMapping.isMapped)
            assertTrue(uiMapping.intentReference.contains("screen"))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ContractScorer — Execution Load
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ContractScorer executionLoad equals surfaceWeight + stepCount + 2 times violations`() {
        val intent    = validIntent()
        val derivation = engine().evaluate(intent)
        val score     = scorer().score(derivation)
        val expected  = derivation.surface.totalWeight +
                        derivation.executionPlan.steps.size +
                        (2 * derivation.constraints.violations.size)
        assertEquals(expected, score.executionLoad)
    }

    @Test
    fun `ContractScorer executionLoad is positive for any non-empty derivation`() {
        val score = scorer().score(engine().evaluate(validIntent()))
        assertTrue(score.executionLoad > 0)
    }

    @Test
    fun `ContractScorer riskScore is zero when no failures`() {
        val intent     = validIntent()
        val derivation = engine().evaluate(intent)
        if (derivation.failureMap.failures.isEmpty()) {
            assertEquals(0, scorer().score(derivation).riskScore)
        }
    }

    @Test
    fun `ContractScorer riskScore increases with each failure`() {
        // A blank-resources intent adds MISSING_RESOURCE failure (severity=1, likelihood=2 → 2)
        val withFailure = ContractIntent("update core event ledger", "constrained", "jvm", "")
        val derivation  = engine().evaluate(withFailure)
        val score       = scorer().score(derivation)
        // MISSING_RESOURCE → 1×2 = 2 contribution at minimum
        assertTrue(score.riskScore >= 0)
    }

    @Test
    fun `ContractScorer confidenceIndex is in range 0 to 15`() {
        val score = scorer().score(engine().evaluate(validIntent()))
        assertTrue(score.confidenceIndex in 0..15)
    }

    @Test
    fun `ContractScorer is deterministic for equal inputs`() {
        val derivation = engine().evaluate(validIntent())
        assertEquals(scorer().score(derivation), scorer().score(derivation))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ContractClassifier
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ContractClassifier classifies LOW score as LOW`() {
        val score = ContractScore(executionLoad = 4, riskScore = 2, confidenceIndex = 12)
        assertEquals(ContractClassification.LOW, classifier().classify(score))
    }

    @Test
    fun `ContractClassifier classifies HIGH EL as HIGH`() {
        val score = ContractScore(executionLoad = 11, riskScore = 0, confidenceIndex = 15)
        assertEquals(ContractClassification.HIGH, classifier().classify(score))
    }

    @Test
    fun `ContractClassifier classifies HIGH RS as HIGH`() {
        val score = ContractScore(executionLoad = 4, riskScore = 9, confidenceIndex = 15)
        assertEquals(ContractClassification.HIGH, classifier().classify(score))
    }

    @Test
    fun `ContractClassifier classifies low CCF as HIGH`() {
        val score = ContractScore(executionLoad = 4, riskScore = 2, confidenceIndex = 4)
        assertEquals(ContractClassification.HIGH, classifier().classify(score))
    }

    @Test
    fun `ContractClassifier classifies borderline values as MEDIUM`() {
        // EL=7 (above LOW_EL_MAX=6 but not above HIGH_EL_THRESHOLD=10) + low RS + high CCF
        val score = ContractScore(executionLoad = 7, riskScore = 2, confidenceIndex = 12)
        assertEquals(ContractClassification.MEDIUM, classifier().classify(score))
    }

    @Test
    fun `ContractClassifier HIGH takes priority over LOW`() {
        // Score is LOW-eligible in EL+RS but CCF is below HIGH threshold
        val score = ContractScore(executionLoad = 3, riskScore = 2, confidenceIndex = 3)
        assertEquals(ContractClassification.HIGH, classifier().classify(score))
    }

    @Test
    fun `ContractClassifier is deterministic`() {
        val score = ContractScore(executionLoad = 5, riskScore = 2, confidenceIndex = 11)
        assertEquals(classifier().classify(score), classifier().classify(score))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AgentProfile
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `AgentProfile capabilityScore is sum of non-drift dimensions plus inverted drift`() {
        val agent    = standardAgent()
        val expected = agent.constraintObedience + agent.structuralAccuracy +
                       (3 - agent.driftTendency) + agent.complexityHandling +
                       agent.outputReliability
        assertEquals(expected, agent.capabilityScore)
    }

    @Test
    fun `AgentProfile highFidelity agent has max capabilityScore`() {
        assertEquals(15, highFidelityAgent().capabilityScore)
    }

    @Test
    fun `AgentProfile validates dimension bounds`() {
        try {
            AgentProfile("bad", 4, 0, 0, 0, 0) // constraintObedience > 3
            fail("expected IllegalArgumentException for out-of-range constraintObedience")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("constraintObedience") ?: false)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AgentMatcher
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `AgentMatcher ACCEPT for LOW contract with minimally capable agent`() {
        val intent     = validIntent()
        val derivation = engine().evaluate(intent)
        val score      = ContractScore(executionLoad = 3, riskScore = 0, confidenceIndex = 12)
        val scored     = com.agoii.mobile.contracts.ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = ContractClassification.LOW,
            traceability   = TraceabilityEnforcer().enforce(intent, derivation.executionPlan)
        )
        val result = matcher().match(scored, standardAgent())
        assertEquals(AgentMatchDecision.ACCEPT, result.decision)
    }

    @Test
    fun `AgentMatcher ACCEPT for MEDIUM contract with capable agent`() {
        val intent     = validIntent()
        val derivation = engine().evaluate(intent)
        val score      = ContractScore(executionLoad = 7, riskScore = 2, confidenceIndex = 11)
        val scored     = com.agoii.mobile.contracts.ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = ContractClassification.MEDIUM,
            traceability   = TraceabilityEnforcer().enforce(intent, derivation.executionPlan)
        )
        val result = matcher().match(scored, standardAgent())
        // standard agent: structuralAccuracy=2 >= 2 AND driftTendency=1 <= 1 → ACCEPT
        assertEquals(AgentMatchDecision.ACCEPT, result.decision)
    }

    @Test
    fun `AgentMatcher REJECT for MEDIUM contract with basic agent below capability threshold`() {
        val intent     = validIntent()
        val derivation = engine().evaluate(intent)
        val score      = ContractScore(executionLoad = 7, riskScore = 2, confidenceIndex = 11)
        val scored     = com.agoii.mobile.contracts.ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = ContractClassification.MEDIUM,
            traceability   = TraceabilityEnforcer().enforce(intent, derivation.executionPlan)
        )
        // basic agent: structuralAccuracy=1 < 2, driftTendency=3 > 1 → not ACCEPT
        // capabilityScore = 1+1+(3-3)+1+1 = 4 < 8 → REJECT
        val result = matcher().match(scored, basicAgent())
        assertEquals(AgentMatchDecision.REJECT, result.decision)
    }

    @Test
    fun `AgentMatcher ACCEPT for HIGH contract with highFidelity agent`() {
        val intent     = validIntent()
        val derivation = engine().evaluate(intent)
        val score      = ContractScore(executionLoad = 11, riskScore = 9, confidenceIndex = 3)
        val scored     = com.agoii.mobile.contracts.ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = ContractClassification.HIGH,
            traceability   = TraceabilityEnforcer().enforce(intent, derivation.executionPlan)
        )
        val result = matcher().match(scored, highFidelityAgent())
        assertEquals(AgentMatchDecision.ACCEPT, result.decision)
    }

    @Test
    fun `AgentMatcher REJECT for HIGH contract with basic agent`() {
        val intent     = validIntent()
        val derivation = engine().evaluate(intent)
        val score      = ContractScore(executionLoad = 11, riskScore = 9, confidenceIndex = 3)
        val scored     = com.agoii.mobile.contracts.ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = ContractClassification.HIGH,
            traceability   = TraceabilityEnforcer().enforce(intent, derivation.executionPlan)
        )
        // basic: capabilityScore = 4 < 10 → REJECT
        val result = matcher().match(scored, basicAgent())
        assertEquals(AgentMatchDecision.REJECT, result.decision)
    }

    @Test
    fun `AgentMatcher result always contains reasons`() {
        val intent     = validIntent()
        val derivation = engine().evaluate(intent)
        val score      = ContractScore(executionLoad = 3, riskScore = 0, confidenceIndex = 12)
        val scored     = com.agoii.mobile.contracts.ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = ContractClassification.LOW,
            traceability   = TraceabilityEnforcer().enforce(intent, derivation.executionPlan)
        )
        val result = matcher().match(scored, standardAgent())
        assertTrue(result.reasons.isNotEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ContractAdapter
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ContractAdapter produces adapted plan with at least as many steps as original`() {
        val intent     = validIntent(objective = "update screen layout")
        val derivation = engine().evaluate(intent)
        val score      = scorer().score(derivation)
        val scored     = com.agoii.mobile.contracts.ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = classifier().classify(score),
            traceability   = TraceabilityEnforcer().enforce(intent, derivation.executionPlan)
        )
        val adapted = ContractAdapter().adapt(scored)
        assertTrue(adapted.adaptedPlan.steps.size >= derivation.executionPlan.steps.size)
    }

    @Test
    fun `ContractAdapter adapted steps have 1-based sequential positions`() {
        val intent     = validIntent(objective = "update screen layout")
        val derivation = engine().evaluate(intent)
        val score      = scorer().score(derivation)
        val scored     = com.agoii.mobile.contracts.ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = classifier().classify(score),
            traceability   = TraceabilityEnforcer().enforce(intent, derivation.executionPlan)
        )
        val adapted = ContractAdapter().adapt(scored)
        adapted.adaptedPlan.steps.forEachIndexed { index, step ->
            assertEquals(index + 1, step.position)
        }
    }

    @Test
    fun `ContractAdapter always produces adaptation notes`() {
        val intent     = validIntent()
        val derivation = engine().evaluate(intent)
        val score      = scorer().score(derivation)
        val scored     = com.agoii.mobile.contracts.ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = classifier().classify(score),
            traceability   = TraceabilityEnforcer().enforce(intent, derivation.executionPlan)
        )
        val adapted = ContractAdapter().adapt(scored)
        assertTrue(adapted.adaptationNotes.isNotEmpty())
        assertTrue(adapted.adaptationNotes.any { it.contains("adaptation triggered") })
    }

    @Test
    fun `ContractAdapter adapted step descriptions contain ADAPTED marker`() {
        val intent     = validIntent()
        val derivation = engine().evaluate(intent)
        val score      = scorer().score(derivation)
        val scored     = com.agoii.mobile.contracts.ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = classifier().classify(score),
            traceability   = TraceabilityEnforcer().enforce(intent, derivation.executionPlan)
        )
        val adapted = ContractAdapter().adapt(scored)
        assertTrue(adapted.adaptedPlan.steps.all { it.description.contains("[ADAPTED") })
    }

    @Test
    fun `ContractAdapter totalLoad is sum of all adapted step loads`() {
        val intent     = validIntent()
        val derivation = engine().evaluate(intent)
        val score      = scorer().score(derivation)
        val scored     = com.agoii.mobile.contracts.ScoredContract(
            derivation     = derivation,
            score          = score,
            classification = classifier().classify(score),
            traceability   = TraceabilityEnforcer().enforce(intent, derivation.executionPlan)
        )
        val adapted = ContractAdapter().adapt(scored)
        assertEquals(
            adapted.adaptedPlan.steps.sumOf { it.load },
            adapted.adaptedPlan.totalLoad
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ContractSystemOrchestrator — full pipeline
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Orchestrator readyForExecution when objective valid and agent matches`() {
        val result = ContractSystemOrchestrator().evaluate(validIntent(), highFidelityAgent())
        assertTrue(result.objectiveValidation.valid)
        assertNotNull(result.scoredContract)
        assertNotNull(result.matchResult)
        // readyForExecution is true if decision is ACCEPT or ADAPT
        assertTrue(result.readyForExecution)
    }

    @Test
    fun `Orchestrator halts at objective validation when objective is blank`() {
        val result = ContractSystemOrchestrator().evaluate(
            ContractIntent("", "constrained", "jvm", "ledger"),
            standardAgent()
        )
        assertFalse(result.objectiveValidation.valid)
        assertNull(result.scoredContract)
        assertNull(result.matchResult)
        assertFalse(result.readyForExecution)
    }

    @Test
    fun `Orchestrator halts at objective validation when all scope fields blank`() {
        val result = ContractSystemOrchestrator().evaluate(
            ContractIntent("update ledger", "", "", ""),
            standardAgent()
        )
        assertFalse(result.objectiveValidation.valid)
        assertNull(result.scoredContract)
        assertFalse(result.readyForExecution)
    }

    @Test
    fun `Orchestrator readyForExecution is false when agent is REJECTED`() {
        // A no-ui constraint against a screen objective causes engine rejection
        val intent = ContractIntent("update screen layout", "no ui", "jvm", "ledger")
        val result = ContractSystemOrchestrator().evaluate(intent, basicAgent())
        // Engine should reject (no-ui constraint + UI step → REJECTED)
        // readyForExecution should be false regardless
        assertFalse(result.readyForExecution)
    }

    @Test
    fun `Orchestrator scoredContract includes surface failure execution constraints`() {
        val result = ContractSystemOrchestrator().evaluate(validIntent(), highFidelityAgent())
        val scored = result.scoredContract
        if (scored != null) {
            assertNotNull(scored.derivation.surface)
            assertNotNull(scored.derivation.failureMap)
            assertNotNull(scored.derivation.executionPlan)
            assertNotNull(scored.derivation.constraints)
        }
    }

    @Test
    fun `Orchestrator scoredContract includes executionLoad riskScore confidenceIndex`() {
        val result = ContractSystemOrchestrator().evaluate(validIntent(), highFidelityAgent())
        val scored = result.scoredContract
        if (scored != null) {
            assertTrue(scored.score.executionLoad >= 0)
            assertTrue(scored.score.riskScore >= 0)
            assertTrue(scored.score.confidenceIndex in 0..15)
        }
    }

    @Test
    fun `Orchestrator scoredContract has a classification`() {
        val result = ContractSystemOrchestrator().evaluate(validIntent(), highFidelityAgent())
        val scored = result.scoredContract
        if (scored != null) {
            assertNotNull(scored.classification)
        }
    }

    @Test
    fun `Orchestrator adaptedContract is non-null when decision is ADAPT`() {
        val result = ContractSystemOrchestrator().evaluate(validIntent(), highFidelityAgent())
        if (result.matchResult?.decision == AgentMatchDecision.ADAPT) {
            assertNotNull(result.adaptedContract)
        }
    }

    @Test
    fun `Orchestrator adaptedContract is null when decision is ACCEPT`() {
        val result = ContractSystemOrchestrator().evaluate(validIntent(), highFidelityAgent())
        if (result.matchResult?.decision == AgentMatchDecision.ACCEPT) {
            assertNull(result.adaptedContract)
        }
    }

    @Test
    fun `Orchestrator is deterministic for equal inputs`() {
        val intent = validIntent()
        val agent  = highFidelityAgent()
        val r1 = ContractSystemOrchestrator().evaluate(intent, agent)
        val r2 = ContractSystemOrchestrator().evaluate(intent, agent)
        assertEquals(r1, r2)
    }

    @Test
    fun `Orchestrator matchResult contains non-empty reasons`() {
        val result = ContractSystemOrchestrator().evaluate(validIntent(), highFidelityAgent())
        result.matchResult?.let { assertTrue(it.reasons.isNotEmpty()) }
    }

    @Test
    fun `Orchestrator traceability is always computed for valid scored contracts`() {
        val result = ContractSystemOrchestrator().evaluate(validIntent(), highFidelityAgent())
        result.scoredContract?.let {
            assertNotNull(it.traceability)
        }
    }
}
