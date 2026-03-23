package com.agoii.mobile

import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.simulation.SimulationContract
import com.agoii.mobile.simulation.SimulationEngine
import com.agoii.mobile.simulation.SimulationMode
import com.agoii.mobile.simulation.SimulationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Simulation System (SIMULATION_SYSTEM_V1).
 *
 * All tests run on the JVM — no Android framework or network access required.
 *
 * Verified invariants:
 *  1. SimulationMode  — all enum values exist with their expected ordinals.
 *  2. SimulationContract — immutability; optional referenceId defaults; parameters round-trip.
 *  3. SimulationResult   — confidence range validation; immutability; field access.
 *  4. SimulationEngine / UNDERSTAND mode — produces findings; always feasible; no side effects.
 *  5. SimulationEngine / FEASIBILITY mode — feasible when execution can proceed.
 *  6. SimulationEngine / FEASIBILITY mode — infeasible when lifecycle is closed.
 *  7. SimulationEngine / SCENARIO mode   — generates scenarios for each lifecycle stage.
 *  8. Read-only guarantee — ReplayState is never mutated.
 *  9. Determinism — identical inputs always produce identical results.
 */
class SimulationTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun idleState(objective: String? = null) = ReplayState(
        phase              = "idle",
        contractsCompleted = 0,
        totalContracts     = 0,
        executionStarted   = false,
        executionCompleted = false,
        assemblyStarted    = false,
        assemblyValidated  = false,
        objective          = objective,
        assemblyCompleted  = false
    )

    private fun executionCompleteState() = ReplayState(
        phase              = "execution_completed",
        contractsCompleted = 3,
        totalContracts     = 3,
        executionStarted   = true,
        executionCompleted = true,
        assemblyStarted    = false,
        assemblyValidated  = false,
        objective          = "deploy service alpha",
        assemblyCompleted  = false
    )

    private fun assemblyCompleteState() = ReplayState(
        phase              = "assembly_completed",
        contractsCompleted = 3,
        totalContracts     = 3,
        executionStarted   = true,
        executionCompleted = true,
        assemblyStarted    = true,
        assemblyValidated  = true,
        objective          = "deploy service alpha",
        assemblyCompleted  = true
    )

    private fun partialExecutionState() = ReplayState(
        phase              = "contract_started",
        contractsCompleted = 1,
        totalContracts     = 3,
        executionStarted   = true,
        executionCompleted = false,
        assemblyStarted    = false,
        assemblyValidated  = false,
        objective          = "deploy service alpha",
        assemblyCompleted  = false
    )

    private fun minimalContract(
        contractId:  String                 = "sim-001",
        mode:        SimulationMode         = SimulationMode.FEASIBILITY,
        referenceId: String?                = null,
        parameters:  Map<String, String>    = emptyMap()
    ) = SimulationContract(
        contractId  = contractId,
        mode        = mode,
        referenceId = referenceId,
        parameters  = parameters
    )

    private val engine = SimulationEngine()

    // ── 1. SimulationMode enum ────────────────────────────────────────────────

    @Test
    fun `SimulationMode has exactly UNDERSTAND, FEASIBILITY, SCENARIO`() {
        val values = SimulationMode.values().map { it.name }
        assertEquals(listOf("UNDERSTAND", "FEASIBILITY", "SCENARIO"), values)
    }

    @Test
    fun `SimulationMode ordinals are stable`() {
        assertEquals(0, SimulationMode.UNDERSTAND.ordinal)
        assertEquals(1, SimulationMode.FEASIBILITY.ordinal)
        assertEquals(2, SimulationMode.SCENARIO.ordinal)
    }

    // ── 2. SimulationContract ─────────────────────────────────────────────────

    @Test
    fun `two SimulationContracts with identical fields are equal`() {
        val c1 = minimalContract()
        val c2 = minimalContract()
        assertEquals("Identical contracts must be value-equal", c1, c2)
    }

    @Test
    fun `SimulationContract copy preserves all fields`() {
        val original = minimalContract(contractId = "x-99", mode = SimulationMode.UNDERSTAND)
        val copy     = original.copy()
        assertEquals(original, copy)
    }

    @Test
    fun `SimulationContract referenceId defaults to null`() {
        val contract = minimalContract(referenceId = null)
        assertNull("referenceId should be null when not provided", contract.referenceId)
    }

    @Test
    fun `SimulationContract referenceId binds correctly`() {
        val contract = minimalContract(referenceId = "contract-42")
        assertEquals("contract-42", contract.referenceId)
    }

    @Test
    fun `SimulationContract parameters round-trip correctly`() {
        val params   = mapOf("depth" to "full", "scope" to "global")
        val contract = minimalContract(parameters = params)
        assertEquals("full",   contract.parameters["depth"])
        assertEquals("global", contract.parameters["scope"])
    }

    @Test
    fun `SimulationContract accepts empty parameters`() {
        val contract = minimalContract(parameters = emptyMap())
        assertTrue(contract.parameters.isEmpty())
    }

    // ── 3. SimulationResult invariants ────────────────────────────────────────

    @Test
    fun `SimulationResult accepts confidence at boundary 0 point 0`() {
        val result = SimulationResult(
            contractId    = "r-001",
            mode          = SimulationMode.FEASIBILITY,
            feasible      = false,
            confidence    = 0.0,
            findings      = emptyList(),
            failurePoints = listOf("test failure"),
            scenarios     = emptyList()
        )
        assertEquals(0.0, result.confidence, 0.0)
    }

    @Test
    fun `SimulationResult accepts confidence at boundary 1 point 0`() {
        val result = SimulationResult(
            contractId    = "r-002",
            mode          = SimulationMode.UNDERSTAND,
            feasible      = true,
            confidence    = 1.0,
            findings      = listOf("all good"),
            failurePoints = emptyList(),
            scenarios     = emptyList()
        )
        assertEquals(1.0, result.confidence, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SimulationResult rejects confidence below 0 point 0`() {
        SimulationResult(
            contractId    = "r-003",
            mode          = SimulationMode.FEASIBILITY,
            feasible      = false,
            confidence    = -0.1,
            findings      = emptyList(),
            failurePoints = emptyList(),
            scenarios     = emptyList()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SimulationResult rejects confidence above 1 point 0`() {
        SimulationResult(
            contractId    = "r-004",
            mode          = SimulationMode.FEASIBILITY,
            feasible      = true,
            confidence    = 1.1,
            findings      = emptyList(),
            failurePoints = emptyList(),
            scenarios     = emptyList()
        )
    }

    @Test
    fun `SimulationResult value equality holds`() {
        val r1 = SimulationResult(
            contractId    = "r-005",
            mode          = SimulationMode.SCENARIO,
            feasible      = true,
            confidence    = 0.75,
            findings      = listOf("f1"),
            failurePoints = emptyList(),
            scenarios     = listOf("s1")
        )
        val r2 = r1.copy()
        assertEquals(r1, r2)
    }

    // ── 4. UNDERSTAND mode ────────────────────────────────────────────────────

    @Test
    fun `UNDERSTAND mode always returns feasible true`() {
        val result = engine.simulate(
            idleState(objective = null),
            minimalContract(mode = SimulationMode.UNDERSTAND)
        )
        assertTrue("UNDERSTAND must always be feasible", result.feasible)
    }

    @Test
    fun `UNDERSTAND mode populates findings with phase`() {
        val result = engine.simulate(
            idleState(objective = "build product alpha"),
            minimalContract(mode = SimulationMode.UNDERSTAND)
        )
        assertTrue(result.findings.any { it.contains("idle") })
    }

    @Test
    fun `UNDERSTAND mode includes objective in findings when present`() {
        val result = engine.simulate(
            idleState(objective = "build product alpha"),
            minimalContract(mode = SimulationMode.UNDERSTAND)
        )
        assertTrue(result.findings.any { it.contains("build product alpha") })
    }

    @Test
    fun `UNDERSTAND mode notes missing objective`() {
        val result = engine.simulate(
            idleState(objective = null),
            minimalContract(mode = SimulationMode.UNDERSTAND)
        )
        assertTrue(result.findings.any { it.contains("no objective") })
    }

    @Test
    fun `UNDERSTAND mode produces no failure points`() {
        val result = engine.simulate(
            assemblyCompleteState(),
            minimalContract(mode = SimulationMode.UNDERSTAND)
        )
        assertTrue(result.failurePoints.isEmpty())
    }

    @Test
    fun `UNDERSTAND mode produces no scenarios`() {
        val result = engine.simulate(
            idleState(),
            minimalContract(mode = SimulationMode.UNDERSTAND)
        )
        assertTrue(result.scenarios.isEmpty())
    }

    @Test
    fun `UNDERSTAND confidence is higher when objective is present`() {
        val withObjective    = engine.simulate(
            idleState(objective = "goal"),
            minimalContract(mode = SimulationMode.UNDERSTAND)
        )
        val withoutObjective = engine.simulate(
            idleState(objective = null),
            minimalContract(mode = SimulationMode.UNDERSTAND)
        )
        assertTrue(withObjective.confidence > withoutObjective.confidence)
    }

    // ── 5. FEASIBILITY mode — feasible paths ──────────────────────────────────

    @Test
    fun `FEASIBILITY is feasible when objective set and execution not started`() {
        val result = engine.simulate(
            idleState(objective = "deploy service"),
            minimalContract(mode = SimulationMode.FEASIBILITY)
        )
        assertTrue("Should be feasible with a defined objective", result.feasible)
        assertTrue(result.failurePoints.isEmpty())
    }

    @Test
    fun `FEASIBILITY confidence is highest when all contracts are complete`() {
        val result = engine.simulate(
            executionCompleteState(),
            minimalContract(mode = SimulationMode.FEASIBILITY)
        )
        // execution completed → infeasible, but let's check partial-completion path separately
        // Here we use a state where execution completed is FALSE but contracts are fully done
        val partialState = ReplayState(
            phase              = "contract_completed",
            contractsCompleted = 3,
            totalContracts     = 3,
            executionStarted   = true,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            objective          = "deploy service",
            assemblyCompleted  = false
        )
        val r2 = engine.simulate(partialState, minimalContract(mode = SimulationMode.FEASIBILITY))
        assertTrue(r2.feasible)
        assertTrue(r2.confidence >= 0.9)
    }

    @Test
    fun `FEASIBILITY notes partial contract progress in findings`() {
        val result = engine.simulate(
            partialExecutionState(),
            minimalContract(mode = SimulationMode.FEASIBILITY)
        )
        assertTrue(result.findings.any { it.contains("partial execution") || it.contains("progress") })
    }

    // ── 6. FEASIBILITY mode — infeasible paths ────────────────────────────────

    @Test
    fun `FEASIBILITY is infeasible when no objective defined`() {
        val result = engine.simulate(
            idleState(objective = null),
            minimalContract(mode = SimulationMode.FEASIBILITY)
        )
        assertFalse(result.feasible)
        assertTrue(result.failurePoints.any { it.contains("no objective") })
    }

    @Test
    fun `FEASIBILITY is infeasible when execution already completed`() {
        val result = engine.simulate(
            executionCompleteState(),
            minimalContract(mode = SimulationMode.FEASIBILITY)
        )
        assertFalse(result.feasible)
        assertTrue(result.failurePoints.any { it.contains("execution already completed") })
    }

    @Test
    fun `FEASIBILITY is infeasible when assembly already completed`() {
        val result = engine.simulate(
            assemblyCompleteState(),
            minimalContract(mode = SimulationMode.FEASIBILITY)
        )
        assertFalse(result.feasible)
        assertTrue(result.failurePoints.any { it.contains("assembly already completed") })
    }

    @Test
    fun `FEASIBILITY infeasible result has low confidence`() {
        val result = engine.simulate(
            idleState(objective = null),
            minimalContract(mode = SimulationMode.FEASIBILITY)
        )
        assertTrue("Infeasible result confidence must be low", result.confidence <= 0.3)
    }

    // ── 7. SCENARIO mode ──────────────────────────────────────────────────────

    @Test
    fun `SCENARIO generates pre-execution scenarios when execution not started`() {
        val result = engine.simulate(
            idleState(objective = "build service"),
            minimalContract(mode = SimulationMode.SCENARIO)
        )
        assertTrue(result.scenarios.isNotEmpty())
        assertTrue(result.scenarios.any { it.contains("fast-track") || it.contains("validation") })
    }

    @Test
    fun `SCENARIO generates mid-execution scenarios when execution is in progress`() {
        val result = engine.simulate(
            partialExecutionState(),
            minimalContract(mode = SimulationMode.SCENARIO)
        )
        assertTrue(result.scenarios.any { it.contains("execution") || it.contains("abort") })
    }

    @Test
    fun `SCENARIO generates post-execution scenarios when execution complete but assembly not started`() {
        val state = ReplayState(
            phase              = "execution_completed",
            contractsCompleted = 3,
            totalContracts     = 3,
            executionStarted   = true,
            executionCompleted = true,
            assemblyStarted    = false,
            assemblyValidated  = false,
            objective          = "deploy service alpha",
            assemblyCompleted  = false
        )
        val result = engine.simulate(state, minimalContract(mode = SimulationMode.SCENARIO))
        assertTrue(result.scenarios.any { it.contains("assembly") })
    }

    @Test
    fun `SCENARIO generates assembly scenarios when assembly in progress`() {
        val state = ReplayState(
            phase              = "assembly_started",
            contractsCompleted = 3,
            totalContracts     = 3,
            executionStarted   = true,
            executionCompleted = true,
            assemblyStarted    = true,
            assemblyValidated  = false,
            objective          = "deploy service alpha",
            assemblyCompleted  = false
        )
        val result = engine.simulate(state, minimalContract(mode = SimulationMode.SCENARIO))
        assertTrue(result.scenarios.any { it.contains("validation") || it.contains("assembly") })
    }

    @Test
    fun `SCENARIO is feasible when objective is set`() {
        val result = engine.simulate(
            idleState(objective = "some goal"),
            minimalContract(mode = SimulationMode.SCENARIO)
        )
        assertTrue(result.feasible)
    }

    @Test
    fun `SCENARIO is infeasible when no objective`() {
        val result = engine.simulate(
            idleState(objective = null),
            minimalContract(mode = SimulationMode.SCENARIO)
        )
        assertFalse(result.feasible)
        assertTrue(result.failurePoints.any { it.contains("no objective") })
    }

    @Test
    fun `SCENARIO result contractId mirrors contract`() {
        val result = engine.simulate(
            idleState(objective = "goal"),
            minimalContract(contractId = "scenario-42", mode = SimulationMode.SCENARIO)
        )
        assertEquals("scenario-42", result.contractId)
    }

    // ── 8. Read-only guarantee ────────────────────────────────────────────────

    @Test
    fun `simulate does not mutate the input ReplayState`() {
        val state = idleState(objective = "initial objective")

        val phaseBefore    = state.phase
        val objectiveBefore = state.objective
        val completedBefore = state.contractsCompleted

        engine.simulate(state, minimalContract(mode = SimulationMode.FEASIBILITY))
        engine.simulate(state, minimalContract(mode = SimulationMode.UNDERSTAND))
        engine.simulate(state, minimalContract(mode = SimulationMode.SCENARIO))

        assertEquals("phase must be unchanged",              phaseBefore,     state.phase)
        assertEquals("objective must be unchanged",          objectiveBefore, state.objective)
        assertEquals("contractsCompleted must be unchanged", completedBefore, state.contractsCompleted)
    }

    // ── 9. Determinism ────────────────────────────────────────────────────────

    @Test
    fun `same inputs always produce identical SimulationResult`() {
        val state    = idleState(objective = "build service alpha")
        val contract = minimalContract(mode = SimulationMode.FEASIBILITY)

        val r1 = engine.simulate(state, contract)
        val r2 = engine.simulate(state, contract)

        assertEquals("Results must be deterministic", r1, r2)
    }

    @Test
    fun `result contractId always mirrors SimulationContract contractId`() {
        listOf(SimulationMode.UNDERSTAND, SimulationMode.FEASIBILITY, SimulationMode.SCENARIO)
            .forEach { mode ->
                val contract = minimalContract(contractId = "trace-id-$mode", mode = mode)
                val result   = engine.simulate(idleState(objective = "goal"), contract)
                assertEquals(
                    "contractId must mirror the contract for mode $mode",
                    contract.contractId,
                    result.contractId
                )
            }
    }

    @Test
    fun `result mode always mirrors SimulationContract mode`() {
        SimulationMode.values().forEach { mode ->
            val result = engine.simulate(
                idleState(objective = "goal"),
                minimalContract(mode = mode)
            )
            assertEquals("mode must mirror the contract mode", mode, result.mode)
        }
    }

    @Test
    fun `result confidence is always in valid range`() {
        val states = listOf(
            idleState(),
            idleState(objective = "goal"),
            partialExecutionState(),
            executionCompleteState(),
            assemblyCompleteState()
        )
        SimulationMode.values().forEach { mode ->
            states.forEach { state ->
                val result = engine.simulate(state, minimalContract(mode = mode))
                assertTrue(
                    "confidence must be ≥ 0.0 for mode $mode",
                    result.confidence >= 0.0
                )
                assertTrue(
                    "confidence must be ≤ 1.0 for mode $mode",
                    result.confidence <= 1.0
                )
            }
        }
    }
}
