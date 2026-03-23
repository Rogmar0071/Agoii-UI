package com.agoii.mobile

import com.agoii.mobile.interaction.InteractionContract
import com.agoii.mobile.interaction.InteractionEngine
import com.agoii.mobile.interaction.InteractionFormatter
import com.agoii.mobile.interaction.InteractionInput
import com.agoii.mobile.interaction.InteractionMapper
import com.agoii.mobile.interaction.InteractionScope
import com.agoii.mobile.interaction.OutputType
import com.agoii.mobile.interaction.SourceType
import com.agoii.mobile.simulation.SimulationView
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies the Simulation → Interaction alignment invariants (Phase 2 — Final Wiring).
 *
 * Verified invariants:
 *  1. [SimulationView] flows through [InteractionEngine] without touching [ReplayState].
 *  2. Output is human-readable (non-blank, contains mode and summary text).
 *  3. Same [SimulationView] input always produces the same [InteractionResult] (deterministic).
 *  4. Interaction does NOT interpret simulation meaning — fields are copied verbatim.
 *  5. No dependency on [ReplayState] for the simulation flow.
 *  6. [InteractionFormatter] is reused — no duplicated formatting logic.
 */
class SimulationInteractionAlignmentTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private val engine    = InteractionEngine()
    private val mapper    = InteractionMapper()
    private val formatter = InteractionFormatter()

    private fun view(
        summary:    String       = "system is understood",
        details:    List<String> = listOf("phase: idle", "objective: build service"),
        confidence: Double       = 0.8,
        mode:       String       = "UNDERSTAND"
    ) = SimulationView(
        summary    = summary,
        details    = details,
        confidence = confidence,
        mode       = mode
    )

    private fun contract(
        id:         String      = "sim-contract-1",
        outputType: OutputType  = OutputType.SUMMARY,
        scope:      InteractionScope = InteractionScope.SIMULATION
    ) = InteractionContract(
        contractId = id,
        query      = "what does the simulation say?",
        scope      = scope,
        outputType = outputType,
        sourceType = SourceType.SIMULATION
    )

    // ── 1. SimulationView flows through InteractionEngine ─────────────────────

    @Test
    fun `SimulationView flows through InteractionEngine and produces a result`() {
        val result = engine.execute(contract(), InteractionInput.SimulationInput(view()))
        assertNotNull(result)
        assertEquals("sim-contract-1", result.contractId)
    }

    @Test
    fun `SimulationInput produces a non-null non-blank content`() {
        val result = engine.execute(contract(), InteractionInput.SimulationInput(view()))
        assertNotNull(result.content)
        assertTrue("Content must not be blank", result.content.isNotBlank())
    }

    // ── 2. Output is human-readable ───────────────────────────────────────────

    @Test
    fun `SUMMARY output contains simulation mode in the phase field`() {
        val v = view(mode = "FEASIBILITY")
        val result = engine.execute(
            contract(outputType = OutputType.SUMMARY),
            InteractionInput.SimulationInput(v)
        )
        assertTrue(
            "SUMMARY must include simulation mode in content",
            result.content.contains("FEASIBILITY")
        )
    }

    @Test
    fun `SUMMARY output contains the summary text from SimulationView`() {
        val v = view(summary = "execution is feasible")
        val result = engine.execute(
            contract(outputType = OutputType.SUMMARY),
            InteractionInput.SimulationInput(v)
        )
        assertTrue(
            "SUMMARY must include SimulationView summary",
            result.content.contains("execution is feasible")
        )
    }

    @Test
    fun `DETAILED output is human-readable and contains mode`() {
        val v = view(mode = "SCENARIO", summary = "two scenarios found")
        val result = engine.execute(
            contract(outputType = OutputType.DETAILED),
            InteractionInput.SimulationInput(v)
        )
        assertTrue(
            "DETAILED must include the simulation phase",
            result.content.contains("SCENARIO")
        )
        assertTrue(
            "DETAILED must include the simulation summary",
            result.content.contains("two scenarios found")
        )
    }

    @Test
    fun `STATUS output contains the simulation mode`() {
        val v = view(mode = "UNDERSTAND")
        val result = engine.execute(
            contract(outputType = OutputType.STATUS),
            InteractionInput.SimulationInput(v)
        )
        assertTrue(
            "STATUS must include the simulation mode",
            result.content.contains("UNDERSTAND")
        )
    }

    @Test
    fun `all OutputType values produce non-blank content for SimulationInput`() {
        val v = view()
        for (outputType in OutputType.values()) {
            val result = engine.execute(
                contract(outputType = outputType),
                InteractionInput.SimulationInput(v)
            )
            assertTrue(
                "Content must not be blank for OutputType.$outputType",
                result.content.isNotBlank()
            )
        }
    }

    // ── 3. Deterministic output ───────────────────────────────────────────────

    @Test
    fun `same SimulationView always produces the same InteractionResult`() {
        val v = view(summary = "determinism check", mode = "UNDERSTAND")
        val c = contract()
        val r1 = engine.execute(c, InteractionInput.SimulationInput(v))
        val r2 = engine.execute(c, InteractionInput.SimulationInput(v))
        assertEquals("Results must be deterministic for identical SimulationView input", r1, r2)
    }

    @Test
    fun `different SimulationView inputs produce different content`() {
        val c = contract()
        val r1 = engine.execute(c, InteractionInput.SimulationInput(view(summary = "alpha")))
        val r2 = engine.execute(c, InteractionInput.SimulationInput(view(summary = "beta")))
        assertNotEquals(
            "Different SimulationView summaries must produce different content",
            r1.content, r2.content
        )
    }

    // ── 4. Interaction does NOT interpret simulation meaning ──────────────────

    @Test
    fun `mapper copies SimulationView fields verbatim without interpretation`() {
        val v = view(summary = "raw summary", details = listOf("detail-a", "detail-b"), mode = "SCENARIO")
        val slice = mapper.extractFromSimulationView(v)

        assertEquals("phase must be simulation_SCENARIO", "simulation_SCENARIO", slice.phase)
        assertEquals("objective must equal SimulationView.summary verbatim", v.summary, slice.objective)
        assertEquals("references must equal SimulationView.details verbatim", v.details, slice.references)
        assertEquals("contractsCompleted must be 0", 0, slice.contractsCompleted)
        assertEquals("totalContracts must be 0", 0, slice.totalContracts)
        assertFalse("executionStarted must be false", slice.executionStarted)
        assertFalse("executionCompleted must be false", slice.executionCompleted)
        assertFalse("assemblyStarted must be false", slice.assemblyStarted)
        assertFalse("assemblyValidated must be false", slice.assemblyValidated)
    }

    @Test
    fun `extractFromSimulationView does not add derived meaning to the slice`() {
        // High-confidence view should produce identical structural output to low-confidence view
        val highConf = view(confidence = 0.99, summary = "check", mode = "UNDERSTAND")
        val lowConf  = view(confidence = 0.01, summary = "check", mode = "UNDERSTAND")

        val sliceHigh = mapper.extractFromSimulationView(highConf)
        val sliceLow  = mapper.extractFromSimulationView(lowConf)

        assertEquals(
            "Confidence must not alter the slice — no derived meaning allowed",
            sliceHigh, sliceLow
        )
    }

    // ── 5. No dependency on ReplayState for simulation flow ───────────────────

    @Test
    fun `SimulationInput executes without any ReplayState`() {
        // This test proves no ReplayState is constructed or required
        val v = view()
        val result = engine.execute(contract(), InteractionInput.SimulationInput(v))
        assertNotNull("Result must be produced without ReplayState", result)
        assertTrue(result.content.isNotBlank())
    }

    @Test
    fun `references in result are sourced from SimulationView details, not ledger fields`() {
        val details = listOf("sim-detail-1", "sim-detail-2", "sim-detail-3")
        val v = view(details = details)
        val result = engine.execute(contract(), InteractionInput.SimulationInput(v))

        assertEquals(
            "References must equal SimulationView.details — not ledger field names",
            details, result.references
        )
        // Ledger field names must not appear in references
        val ledgerFields = listOf("phase", "objective", "contractsCompleted", "executionStarted")
        for (field in ledgerFields) {
            assertFalse(
                "Ledger field '$field' must not appear in simulation references",
                result.references.contains(field)
            )
        }
    }

    // ── 6. Formatter is reused — no duplication ───────────────────────────────

    @Test
    fun `engine and standalone formatter produce identical output for the same slice`() {
        val v = view(summary = "formatter reuse check", mode = "UNDERSTAND")
        val slice = mapper.extractFromSimulationView(v)

        val engineResult    = engine.execute(contract(), InteractionInput.SimulationInput(v))
        val standaloneOutput = formatter.format(OutputType.SUMMARY, slice)

        assertEquals(
            "Engine must delegate to the same formatter — output must be identical",
            standaloneOutput, engineResult.content
        )
    }

    @Test
    fun `contractId is echoed verbatim for SimulationInput`() {
        val id = "unique-sim-contract-42"
        val result = engine.execute(
            contract(id = id),
            InteractionInput.SimulationInput(view())
        )
        assertEquals(id, result.contractId)
    }
}
