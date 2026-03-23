package com.agoii.mobile

import com.agoii.mobile.interaction.InteractionContract
import com.agoii.mobile.interaction.InteractionEngine
import com.agoii.mobile.interaction.InteractionMapper
import com.agoii.mobile.interaction.InteractionScope
import com.agoii.mobile.interaction.OutputType
import com.agoii.mobile.interaction.SimulationInteractionBridge
import com.agoii.mobile.simulation.SimulationMode
import com.agoii.mobile.simulation.SimulationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Simulation → Interaction integration
 * (SIMULATION_INTERACTION_INTEGRATION_V1).
 *
 * All tests run on the JVM — no Android framework or network access required.
 *
 * Verified invariants:
 *  1. SimulationInteractionBridge — all SimulationResult fields are preserved verbatim.
 *  2. SimulationInteractionBridge — output is deterministic and immutable.
 *  3. InteractionMapper.extractFromSimulation — phase encodes simulation mode.
 *  4. InteractionMapper.extractFromSimulation — objective encodes feasibility + confidence.
 *  5. InteractionMapper.extractFromSimulation — numeric/boolean fields follow mapping rules.
 *  6. InteractionMapper.extractFromSimulation — references list covers all simulation fields.
 *  7. InteractionEngine.executeSimulation — contractId echoed verbatim.
 *  8. InteractionEngine.executeSimulation — all OutputType values produce non-blank content.
 *  9. InteractionEngine.executeSimulation — output is deterministic.
 * 10. InteractionEngine.execute — existing ledger-based path is unaffected.
 * 11. InteractionContract — simulationId optional field; ledger contracts default to null.
 * 12. InteractionEngine.executeSimulation — feasible/infeasible results produce different output.
 */
class SimulationInteractionTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun feasibleResult(
        contractId: String = "sim-1",
        mode: SimulationMode = SimulationMode.FEASIBILITY,
        findings: List<String> = listOf("ready to execute", "dependencies met"),
        failurePoints: List<String> = emptyList(),
        scenarios: List<String> = emptyList(),
        confidence: Double = 0.9
    ) = SimulationResult(
        contractId    = contractId,
        mode          = mode,
        feasible      = true,
        confidence    = confidence,
        findings      = findings,
        failurePoints = failurePoints,
        scenarios     = scenarios
    )

    private fun infeasibleResult(
        contractId: String = "sim-2",
        mode: SimulationMode = SimulationMode.FEASIBILITY,
        failurePoints: List<String> = listOf("missing dependency", "resource unavailable"),
        confidence: Double = 0.6
    ) = SimulationResult(
        contractId    = contractId,
        mode          = mode,
        feasible      = false,
        confidence    = confidence,
        findings      = listOf("execution blocked"),
        failurePoints = failurePoints,
        scenarios     = emptyList()
    )

    private val bridge  = SimulationInteractionBridge()
    private val mapper  = InteractionMapper()
    private val engine  = InteractionEngine()

    // ── 1. Bridge — field preservation ───────────────────────────────────────

    @Test
    fun `bridge preserves contractId`() {
        val result = feasibleResult(contractId = "my-contract-id")
        val mapped = bridge.map(result)
        assertEquals("my-contract-id", mapped["contractId"])
    }

    @Test
    fun `bridge preserves mode name`() {
        val result = feasibleResult(mode = SimulationMode.UNDERSTAND)
        val mapped = bridge.map(result)
        assertEquals("UNDERSTAND", mapped["mode"])
    }

    @Test
    fun `bridge preserves feasible as string`() {
        val feasible   = bridge.map(feasibleResult())
        val infeasible = bridge.map(infeasibleResult())
        assertEquals("true",  feasible["feasible"])
        assertEquals("false", infeasible["feasible"])
    }

    @Test
    fun `bridge preserves confidence as string`() {
        val result = feasibleResult(confidence = 0.75)
        val mapped = bridge.map(result)
        assertEquals("0.75", mapped["confidence"])
    }

    @Test
    fun `bridge joins findings with semicolon separator`() {
        val result = feasibleResult(findings = listOf("finding A", "finding B"))
        val mapped = bridge.map(result)
        assertEquals("finding A; finding B", mapped["findings"])
    }

    @Test
    fun `bridge produces empty string for empty findings`() {
        val result = feasibleResult(findings = emptyList())
        val mapped = bridge.map(result)
        assertEquals("", mapped["findings"])
    }

    @Test
    fun `bridge joins failurePoints with semicolon separator`() {
        val result = infeasibleResult(failurePoints = listOf("fp1", "fp2"))
        val mapped = bridge.map(result)
        assertEquals("fp1; fp2", mapped["failurePoints"])
    }

    @Test
    fun `bridge produces empty string for empty failurePoints`() {
        val result = feasibleResult(failurePoints = emptyList())
        val mapped = bridge.map(result)
        assertEquals("", mapped["failurePoints"])
    }

    @Test
    fun `bridge map contains all seven expected keys`() {
        val mapped = bridge.map(feasibleResult())
        val expected = setOf("contractId", "mode", "feasible", "confidence",
                             "findings", "failurePoints", "scenarios")
        assertEquals(expected, mapped.keys)
    }

    // ── 2. Bridge — determinism and immutability ──────────────────────────────

    @Test
    fun `bridge produces identical maps for identical inputs`() {
        val result = feasibleResult()
        assertEquals(bridge.map(result), bridge.map(result))
    }

    @Test
    fun `bridge map is read-only (mapOf returns unmodifiable map)`() {
        val mapped = bridge.map(feasibleResult())
        // mapOf() in Kotlin always returns an unmodifiable LinkedHashMap-backed map;
        // verify this by checking that the exact key set matches expectations and
        // that calling put throws as per the Kotlin collections contract.
        assertEquals(
            setOf("contractId", "mode", "feasible", "confidence",
                  "findings", "failurePoints", "scenarios"),
            mapped.keys
        )
        try {
            (mapped as MutableMap<String, String>)["contractId"] = "mutated"
            // If no exception was thrown the map is mutable — fail the test
            org.junit.Assert.fail("Bridge map must not allow mutation")
        } catch (_: UnsupportedOperationException) {
            // expected: mapOf() result is unmodifiable
        }
    }

    // ── 3. Mapper — phase encodes simulation mode ─────────────────────────────

    @Test
    fun `extractFromSimulation encodes FEASIBILITY mode in phase`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult(mode = SimulationMode.FEASIBILITY))
        assertTrue("Phase must contain 'simulation_feasibility'",
                   slice.phase.contains("simulation_feasibility"))
    }

    @Test
    fun `extractFromSimulation encodes UNDERSTAND mode in phase`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult(mode = SimulationMode.UNDERSTAND))
        assertTrue(slice.phase.contains("simulation_understand"))
    }

    @Test
    fun `extractFromSimulation encodes SCENARIO mode in phase`() {
        val result = SimulationResult(
            contractId = "s", mode = SimulationMode.SCENARIO, feasible = true,
            confidence = 0.8, findings = listOf("alt path"), failurePoints = emptyList(),
            scenarios = listOf("scenario A")
        )
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, result)
        assertTrue(slice.phase.contains("simulation_scenario"))
    }

    // ── 4. Mapper — objective encodes feasibility + confidence ────────────────

    @Test
    fun `extractFromSimulation includes feasible in objective`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult())
        assertNotNull(slice.objective)
        assertTrue(slice.objective!!.contains("feasible=true"))
    }

    @Test
    fun `extractFromSimulation includes confidence in objective`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult(confidence = 0.85))
        assertTrue(slice.objective!!.contains("confidence=0.85"))
    }

    @Test
    fun `extractFromSimulation includes findings in objective when non-empty`() {
        val result = feasibleResult(findings = listOf("dep A ok", "env ready"))
        val slice  = mapper.extractFromSimulation(InteractionScope.SIMULATION, result)
        assertTrue(slice.objective!!.contains("dep A ok"))
        assertTrue(slice.objective!!.contains("env ready"))
    }

    @Test
    fun `extractFromSimulation omits findings section in objective when empty`() {
        val result = feasibleResult(findings = emptyList())
        val slice  = mapper.extractFromSimulation(InteractionScope.SIMULATION, result)
        assertFalse(slice.objective!!.contains("findings"))
    }

    // ── 5. Mapper — numeric and boolean field mapping rules ───────────────────

    @Test
    fun `extractFromSimulation sets contractsCompleted to 0`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult())
        assertEquals(0, slice.contractsCompleted)
    }

    @Test
    fun `extractFromSimulation sets totalContracts to 0`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult())
        assertEquals(0, slice.totalContracts)
    }

    @Test
    fun `extractFromSimulation sets executionStarted to result feasible`() {
        val feasibleSlice   = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult())
        val infeasibleSlice = mapper.extractFromSimulation(InteractionScope.SIMULATION, infeasibleResult())
        assertTrue(feasibleSlice.executionStarted)
        assertFalse(infeasibleSlice.executionStarted)
    }

    @Test
    fun `extractFromSimulation sets executionCompleted to true when no failure points`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult(failurePoints = emptyList()))
        assertTrue(slice.executionCompleted)
    }

    @Test
    fun `extractFromSimulation sets executionCompleted to false when failure points exist`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, infeasibleResult())
        assertFalse(slice.executionCompleted)
    }

    @Test
    fun `extractFromSimulation sets assemblyStarted to false`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult())
        assertFalse(slice.assemblyStarted)
    }

    @Test
    fun `extractFromSimulation sets assemblyValidated to false`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult())
        assertFalse(slice.assemblyValidated)
    }

    // ── 6. Mapper — references cover all simulation fields ────────────────────

    @Test
    fun `extractFromSimulation references list is non-empty`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult())
        assertTrue(slice.references.isNotEmpty())
    }

    @Test
    fun `extractFromSimulation references include key simulation fields`() {
        val slice = mapper.extractFromSimulation(InteractionScope.SIMULATION, feasibleResult())
        val refs  = slice.references
        assertTrue(refs.contains("contractId"))
        assertTrue(refs.contains("mode"))
        assertTrue(refs.contains("feasible"))
        assertTrue(refs.contains("confidence"))
    }

    // ── 7. Engine — contractId echoed verbatim ────────────────────────────────

    @Test
    fun `executeSimulation echoes contractId from InteractionContract`() {
        val contract = InteractionContract("my-id", "query", InteractionScope.SIMULATION, OutputType.SUMMARY, simulationId = "sim-1")
        val result   = engine.executeSimulation(contract, feasibleResult())
        assertEquals("my-id", result.contractId)
    }

    // ── 8. Engine — all OutputType values produce non-blank content ───────────

    @Test
    fun `executeSimulation produces non-blank content for all OutputType values`() {
        for (type in OutputType.values()) {
            val contract = InteractionContract("c", "q", InteractionScope.SIMULATION, type)
            val result   = engine.executeSimulation(contract, feasibleResult())
            assertTrue("Content must not be blank for $type", result.content.isNotBlank())
        }
    }

    // ── 9. Engine — determinism ───────────────────────────────────────────────

    @Test
    fun `executeSimulation is deterministic for identical inputs`() {
        val contract = InteractionContract("det-1", "q", InteractionScope.SIMULATION, OutputType.SUMMARY)
        val simResult = feasibleResult()
        val r1 = engine.executeSimulation(contract, simResult)
        val r2 = engine.executeSimulation(contract, simResult)
        assertEquals("Output must be deterministic", r1, r2)
    }

    // ── 10. Engine — existing ledger path unaffected ──────────────────────────

    @Test
    fun `execute ledger path still works after extension`() {
        val state = com.agoii.mobile.core.ReplayState(
            phase              = "idle",
            contractsCompleted = 0,
            totalContracts     = 3,
            executionStarted   = false,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            objective          = null
        )
        val contract = InteractionContract("ledger-id", "query", InteractionScope.FULL_SYSTEM, OutputType.STATUS)
        val result   = engine.execute(contract, state)
        assertEquals("ledger-id", result.contractId)
        assertTrue(result.content.contains("idle"))
    }

    // ── 11. InteractionContract — simulationId field ──────────────────────────

    @Test
    fun `InteractionContract simulationId defaults to null for ledger contracts`() {
        val contract = InteractionContract("id", "q", InteractionScope.FULL_SYSTEM, OutputType.SUMMARY)
        assertEquals(null, contract.simulationId)
    }

    @Test
    fun `InteractionContract simulationId can be set for simulation contracts`() {
        val contract = InteractionContract("id", "q", InteractionScope.SIMULATION, OutputType.SUMMARY, simulationId = "sim-99")
        assertEquals("sim-99", contract.simulationId)
    }

    // ── 12. Feasible vs infeasible produce different output ───────────────────

    @Test
    fun `executeSimulation produces different output for feasible vs infeasible results`() {
        val contract = InteractionContract("c", "q", InteractionScope.SIMULATION, OutputType.SUMMARY)
        val r1 = engine.executeSimulation(contract, feasibleResult())
        val r2 = engine.executeSimulation(contract, infeasibleResult())
        assertNotEquals("Feasible and infeasible results must produce different output",
                        r1.content, r2.content)
    }
}
