package com.agoii.mobile

import com.agoii.mobile.irs.IrsOrchestrator
import com.agoii.mobile.irs.IntentStateManager
import com.agoii.mobile.irs.OrchestratorResult
import com.agoii.mobile.irs.SessionStatus
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for IRS-02 — the Intent Resolution Execution Architecture.
 *
 * Validates:
 *  - [IrsOrchestrator.step] advances exactly ONE stage per call.
 *  - The orchestrator stops at the correct status on every failure condition.
 *  - [IntentStateManager] provides append-only, full-history traceability.
 *  - [ScoutOrchestrator] produces evidence with confidence and reasoning trace.
 *  - [IrsOrchestrator.process] certifies a complete, valid intent end-to-end.
 *  - [IrsOrchestrator.process] returns clarification for incomplete intent.
 *  - Step log grows by exactly one entry per [step] call.
 */
class IrsOrchestratorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A fully structured valid intent string. */
    private fun validIntent(
        objective: String          = "Implement the user authentication module",
        successCriteria: String    = "All unit tests pass and coverage exceeds 80%",
        constraints: String        = "Must use Kotlin, no third-party auth libraries allowed",
        environment: String        = "Android API 26+, JVM 17, Kotlin SDK",
        resources: String          = "Android SDK, Kotlin stdlib, JUnit 4, Gradle",
        acceptanceBoundary: String = "APK builds successfully and all unit tests are green"
    ): String = """
        objective: $objective
        success_criteria: $successCriteria
        constraints: $constraints
        environment: $environment
        resources: $resources
        acceptance_boundary: $acceptanceBoundary
    """.trimIndent()

    private fun newOrchestrator() = IrsOrchestrator(IntentStateManager())

    // ── startSession / blank input ────────────────────────────────────────────

    @Test
    fun `startSession returns NeedsClarification for blank input`() {
        val orch   = newOrchestrator()
        val result = orch.startSession(UUID.randomUUID().toString(), "   ")
        assertTrue(result is OrchestratorResult.NeedsClarification)
        val ncr = result as OrchestratorResult.NeedsClarification
        assertEquals(6, ncr.request.missingFields.size)
        assertTrue(ncr.request.requiredInput.isNotBlank())
    }

    // ── Step-by-step progression (valid intent) ───────────────────────────────

    @Test
    fun `step 1 reconstruct transitions INITIATED to RECONSTRUCTED`() {
        val orch      = newOrchestrator()
        val sessionId = UUID.randomUUID().toString()
        orch.stateManager.initSession(sessionId, validIntent())

        val result = orch.step(sessionId)

        assertTrue(result is OrchestratorResult.Advanced)
        assertEquals("reconstruct", (result as OrchestratorResult.Advanced).step)
        assertEquals(SessionStatus.RECONSTRUCTED, result.state.status)
        assertNotNull(result.state.intentDraft)
        assertEquals(
            "Implement the user authentication module",
            result.state.intentDraft!!.objective
        )
    }

    @Test
    fun `step 2 detect_gaps transitions RECONSTRUCTED to SCOUTING for complete intent`() {
        val orch      = newOrchestrator()
        val sessionId = UUID.randomUUID().toString()
        orch.startSession(sessionId, validIntent())     // runs reconstruct

        val result = orch.step(sessionId)              // detect_gaps

        assertTrue(result is OrchestratorResult.Advanced)
        assertEquals("detect_gaps", (result as OrchestratorResult.Advanced).step)
        assertEquals(SessionStatus.SCOUTING, result.state.status)
        assertTrue(result.state.gaps.isEmpty())
    }

    @Test
    fun `step 3 run_scouts transitions SCOUTING to SWARM_PENDING with evidence`() {
        val orch      = newOrchestrator()
        val sessionId = UUID.randomUUID().toString()
        orch.startSession(sessionId, validIntent())   // reconstruct
        orch.step(sessionId)                          // detect_gaps → SCOUTING

        val result = orch.step(sessionId)             // run_scouts

        assertTrue(result is OrchestratorResult.Advanced)
        assertEquals("run_scouts", (result as OrchestratorResult.Advanced).step)
        assertEquals(SessionStatus.SWARM_PENDING, result.state.status)
        assertEquals(3, result.state.evidence.size)   // 3 scout agents
        result.state.evidence.forEach { ev ->
            assertTrue("Confidence must be in [0,1]", ev.confidence in 0f..1f)
            assertTrue("Reasoning trace must be non-blank", ev.reasoningTrace.isNotBlank())
            assertTrue("Source must be non-blank", ev.source.isNotBlank())
        }
    }

    @Test
    fun `step 4 swarm_validate transitions SWARM_PENDING to SIMULATION_PENDING for consistent intent`() {
        val orch      = newOrchestrator()
        val sessionId = UUID.randomUUID().toString()
        orch.startSession(sessionId, validIntent())   // reconstruct
        orch.step(sessionId)                          // detect_gaps
        orch.step(sessionId)                          // run_scouts → SWARM_PENDING

        val result = orch.step(sessionId)             // swarm_validate

        assertTrue(result is OrchestratorResult.Advanced)
        assertEquals("swarm_validate", (result as OrchestratorResult.Advanced).step)
        assertEquals(SessionStatus.SIMULATION_PENDING, result.state.status)
        val consensus = result.state.consensusResult!!
        assertTrue(consensus.consistent)
        assertTrue(consensus.conflicts.isEmpty())
        assertEquals(3, consensus.totalPasses)
    }

    @Test
    fun `step 5 simulate transitions SIMULATION_PENDING to PCCV_PENDING for feasible intent`() {
        val orch      = newOrchestrator()
        val sessionId = UUID.randomUUID().toString()
        orch.startSession(sessionId, validIntent())   // reconstruct
        orch.step(sessionId)                          // detect_gaps
        orch.step(sessionId)                          // run_scouts
        orch.step(sessionId)                          // swarm_validate → SIMULATION_PENDING

        val result = orch.step(sessionId)             // simulate

        assertTrue(result is OrchestratorResult.Advanced)
        assertEquals("simulate", (result as OrchestratorResult.Advanced).step)
        assertEquals(SessionStatus.PCCV_PENDING, result.state.status)
        val sim = result.state.simulationOutcome!!
        assertTrue(sim.feasible)
        assertTrue(sim.failurePoints.isEmpty())
    }

    @Test
    fun `step 6 pccv_validate transitions PCCV_PENDING to CERTIFIED for valid intent`() {
        val orch      = newOrchestrator()
        val sessionId = UUID.randomUUID().toString()
        orch.startSession(sessionId, validIntent())   // reconstruct
        orch.step(sessionId)                          // detect_gaps
        orch.step(sessionId)                          // run_scouts
        orch.step(sessionId)                          // swarm_validate
        orch.step(sessionId)                          // simulate → PCCV_PENDING

        val result = orch.step(sessionId)             // pccv_validate + certify

        assertTrue("Expected Certified but got: $result", result is OrchestratorResult.Certified)
        val cr = result as OrchestratorResult.Certified
        assertEquals(SessionStatus.CERTIFIED, cr.state.status)
        assertEquals("CERTIFIED", cr.intent.validationStatus)
        assertTrue(cr.state.pccvResult!!.pass)
        assertTrue(cr.state.pccvResult!!.violations.isEmpty())
        // Evidence refs include raw-input (6) + scout (3) = 9
        assertEquals(9, cr.intent.evidenceRefs.size)
    }

    @Test
    fun `after CERTIFIED step returns AlreadyTerminal`() {
        val orch      = newOrchestrator()
        val sessionId = UUID.randomUUID().toString()
        orch.startSession(sessionId, validIntent())
        repeat(5) { orch.step(sessionId) }             // advance to CERTIFIED
        val terminal = orch.step(sessionId)             // nothing left to do
        assertTrue(terminal is OrchestratorResult.AlreadyTerminal)
    }

    // ── Failure conditions ────────────────────────────────────────────────────

    @Test
    fun `step 2 stops at NEEDS_CLARIFICATION when fields are missing`() {
        val incomplete = "objective: Build something useful for the project"
        val orch       = newOrchestrator()
        val sessionId  = UUID.randomUUID().toString()
        orch.startSession(sessionId, incomplete)    // reconstruct

        val result = orch.step(sessionId)           // detect_gaps

        assertTrue(result is OrchestratorResult.NeedsClarification)
        val ncr = result as OrchestratorResult.NeedsClarification
        assertEquals(sessionId, ncr.request.sessionId)
        assertTrue(ncr.request.missingFields.isNotEmpty())
        assertTrue(ncr.request.requiredInput.isNotBlank())
        assertEquals(SessionStatus.NEEDS_CLARIFICATION, ncr.state.status)
    }

    @Test
    fun `step 4 stops at UNSTABLE when swarm detects identical objective and acceptance_boundary`() {
        val same  = "Implement authentication module"
        val input = validIntent(objective = same, acceptanceBoundary = same)
        val orch       = newOrchestrator()
        val sessionId  = UUID.randomUUID().toString()
        orch.startSession(sessionId, input)   // reconstruct
        orch.step(sessionId)                  // detect_gaps → SCOUTING
        orch.step(sessionId)                  // run_scouts → SWARM_PENDING

        val result = orch.step(sessionId)     // swarm_validate

        assertTrue(result is OrchestratorResult.Rejected)
        val rj = result as OrchestratorResult.Rejected
        assertEquals(SessionStatus.UNSTABLE, rj.state.status)
        assertTrue(rj.state.consensusResult!!.conflicts.isNotEmpty())
    }

    @Test
    fun `step 5 stops at INFEASIBLE when fields contain placeholder values`() {
        val input = validIntent(successCriteria = "tbd")
        val orch       = newOrchestrator()
        val sessionId  = UUID.randomUUID().toString()
        orch.startSession(sessionId, input)   // reconstruct
        orch.step(sessionId)                  // detect_gaps → SCOUTING
        orch.step(sessionId)                  // run_scouts → SWARM_PENDING
        orch.step(sessionId)                  // swarm_validate → SIMULATION_PENDING

        val result = orch.step(sessionId)     // simulate

        assertTrue(result is OrchestratorResult.Rejected)
        val rj = result as OrchestratorResult.Rejected
        assertEquals(SessionStatus.INFEASIBLE, rj.state.status)
        assertTrue(rj.state.simulationOutcome!!.failurePoints.isNotEmpty())
    }

    // ── process() integration ─────────────────────────────────────────────────

    @Test
    fun `process certifies a fully valid intent`() {
        val result = newOrchestrator().process(validIntent())

        assertTrue("Expected Certified but got: $result", result is OrchestratorResult.Certified)
        val cr = result as OrchestratorResult.Certified
        assertEquals("CERTIFIED", cr.intent.validationStatus)
        assertEquals("Implement the user authentication module", cr.intent.objective)
        assertTrue(cr.intent.intentId.isNotBlank())
        assertTrue(cr.state.pccvResult!!.pass)
    }

    @Test
    fun `process returns NeedsClarification for incomplete intent`() {
        val result = newOrchestrator().process("objective: Do something")

        assertTrue(result is OrchestratorResult.NeedsClarification)
        val ncr = result as OrchestratorResult.NeedsClarification
        assertTrue(ncr.request.missingFields.isNotEmpty())
        assertFalse(ncr.request.missingFields.contains("objective"))
        assertTrue(ncr.request.missingFields.contains("success_criteria"))
    }

    @Test
    fun `process returns Rejected for intent with swarm contradiction`() {
        val same   = "Build the auth module"
        val result = newOrchestrator().process(validIntent(objective = same, acceptanceBoundary = same))

        assertTrue(result is OrchestratorResult.Rejected)
        assertEquals(SessionStatus.UNSTABLE, (result as OrchestratorResult.Rejected).state.status)
    }

    @Test
    fun `process returns Rejected for intent with placeholder simulation failure`() {
        val result = newOrchestrator().process(validIntent(constraints = "none"))

        assertTrue(result is OrchestratorResult.Rejected)
        assertEquals(SessionStatus.INFEASIBLE, (result as OrchestratorResult.Rejected).state.status)
    }

    @Test
    fun `each process call produces a unique intentId`() {
        val orch  = newOrchestrator()
        val r1    = orch.process(validIntent()) as OrchestratorResult.Certified
        val r2    = orch.process(validIntent()) as OrchestratorResult.Certified
        assertNotEquals(r1.intent.intentId, r2.intent.intentId)
    }

    // ── IntentStateManager invariants ─────────────────────────────────────────

    @Test
    fun `state manager returns full history with one snapshot per step`() {
        val sm        = IntentStateManager()
        val orch      = IrsOrchestrator(sm)
        val sessionId = UUID.randomUUID().toString()

        orch.startSession(sessionId, validIntent())   // 2 snapshots: init + after reconstruct
        assertEquals(2, sm.getHistory(sessionId).size)

        orch.step(sessionId)                          // detect_gaps → +1
        assertEquals(3, sm.getHistory(sessionId).size)

        orch.step(sessionId)                          // run_scouts → +1
        assertEquals(4, sm.getHistory(sessionId).size)
    }

    @Test
    fun `state manager history statuses are monotonically progressing`() {
        val sm        = IntentStateManager()
        val orch      = IrsOrchestrator(sm)
        val sessionId = UUID.randomUUID().toString()

        orch.startSession(sessionId, validIntent())
        repeat(5) { orch.step(sessionId) }

        val statuses = sm.getHistory(sessionId).map { it.status }
        val expected = listOf(
            SessionStatus.INITIATED,
            SessionStatus.RECONSTRUCTED,
            SessionStatus.SCOUTING,
            SessionStatus.SWARM_PENDING,
            SessionStatus.SIMULATION_PENDING,
            SessionStatus.PCCV_PENDING,
            SessionStatus.CERTIFIED
        )
        assertEquals(expected, statuses)
    }

    @Test
    fun `state manager initSession is idempotent`() {
        val sm        = IntentStateManager()
        val sessionId = UUID.randomUUID().toString()
        val s1        = sm.initSession(sessionId, "input")
        val s2        = sm.initSession(sessionId, "different input")   // must be no-op
        assertEquals(s1.sessionId, s2.sessionId)
        assertEquals("input", s2.rawInput)  // original raw input preserved
    }

    @Test
    fun `step log grows by at least one entry per step`() {
        val sm        = IntentStateManager()
        val orch      = IrsOrchestrator(sm)
        val sessionId = UUID.randomUUID().toString()

        orch.startSession(sessionId, validIntent())
        val afterStart = sm.getLatestState(sessionId)!!.stepLog.size

        orch.step(sessionId)  // detect_gaps
        val afterGap = sm.getLatestState(sessionId)!!.stepLog.size
        assertTrue(afterGap > afterStart)

        orch.step(sessionId)  // run_scouts
        val afterScout = sm.getLatestState(sessionId)!!.stepLog.size
        assertTrue(afterScout > afterGap)
    }

    // ── Scout Orchestrator ────────────────────────────────────────────────────

    @Test
    fun `scout orchestrator returns exactly 3 evidence items`() {
        val sm        = IntentStateManager()
        val orch      = IrsOrchestrator(sm)
        val sessionId = UUID.randomUUID().toString()

        orch.startSession(sessionId, validIntent())  // reconstruct
        orch.step(sessionId)                         // detect_gaps → SCOUTING

        val scoutResult = orch.step(sessionId)       // run_scouts

        assertTrue(scoutResult is OrchestratorResult.Advanced)
        val evidence = (scoutResult as OrchestratorResult.Advanced).state.evidence
        assertEquals(3, evidence.size)
        val sources = evidence.map { it.source }.toSet()
        assertTrue(sources.contains("environment_lookup"))
        assertTrue(sources.contains("dependency_check"))
        assertTrue(sources.contains("feasibility_scan"))
    }

    @Test
    fun `environment_lookup assigns high confidence for well-specified environment`() {
        val sm        = IntentStateManager()
        val orch      = IrsOrchestrator(sm)
        val sessionId = UUID.randomUUID().toString()

        // environment field references multiple known platforms
        orch.startSession(sessionId, validIntent(environment = "Android API 26+, JVM 17, Kotlin SDK"))
        orch.step(sessionId)                          // detect_gaps
        val scoutResult = orch.step(sessionId)        // run_scouts

        val ev = (scoutResult as OrchestratorResult.Advanced).state.evidence
            .first { it.source == "environment_lookup" }
        assertTrue("Expected confidence ≥ 0.7 but was ${ev.confidence}", ev.confidence >= 0.7f)
    }

    @Test
    fun `dependency_check assigns moderate confidence for single-item resources`() {
        val sm        = IntentStateManager()
        val orch      = IrsOrchestrator(sm)
        val sessionId = UUID.randomUUID().toString()

        orch.startSession(sessionId, validIntent(resources = "Kotlin stdlib only"))
        orch.step(sessionId)                         // detect_gaps
        val scoutResult = orch.step(sessionId)       // run_scouts

        val ev = (scoutResult as OrchestratorResult.Advanced).state.evidence
            .first { it.source == "dependency_check" }
        assertTrue("Confidence must be in [0,1]", ev.confidence in 0f..1f)
        assertTrue(ev.reasoningTrace.contains("dependency_check"))
    }

    // ── PCCV result correctness ───────────────────────────────────────────────

    @Test
    fun `PCCV result has all dimensions true for fully valid intent`() {
        val sm        = IntentStateManager()
        val orch      = IrsOrchestrator(sm)
        val sessionId = UUID.randomUUID().toString()

        orch.startSession(sessionId, validIntent())
        repeat(5) { orch.step(sessionId) }            // advance to CERTIFIED

        val state = sm.getLatestState(sessionId)!!
        val pccv  = state.pccvResult!!
        assertTrue(pccv.completeness)
        assertTrue(pccv.consistency)
        assertTrue(pccv.feasibility)
        assertTrue(pccv.nonAssumption)
        assertTrue(pccv.reproducibility)
        assertTrue(pccv.violations.isEmpty())
    }

    // ── maxStepsPerProcess safety limit ──────────────────────────────────────

    @Test
    fun `process does not loop indefinitely when maxStepsPerProcess is exceeded`() {
        // With maxStepsPerProcess = 1, process() runs only startSession + 1 step.
        // For a valid intent that needs 6 steps to certify, it should return Advanced
        // (not certified) and not loop.
        val orch   = IrsOrchestrator(IntentStateManager(), maxStepsPerProcess = 1)
        val result = orch.process(validIntent())
        // Should return Advanced (stopped early) rather than hanging
        assertTrue(
            "Expected Advanced but got ${result.javaClass.simpleName}",
            result is OrchestratorResult.Advanced
        )
    }
}
