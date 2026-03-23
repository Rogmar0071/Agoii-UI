package com.agoii.mobile

import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.interaction.InteractionContract
import com.agoii.mobile.interaction.InteractionEngine
import com.agoii.mobile.interaction.InteractionFormatter
import com.agoii.mobile.interaction.InteractionInput
import com.agoii.mobile.interaction.InteractionMapper
import com.agoii.mobile.interaction.InteractionScope
import com.agoii.mobile.interaction.OutputType
import com.agoii.mobile.interaction.SourceType
import com.agoii.mobile.interaction.StateSlice
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the Interaction Contract System.
 *
 * All tests run on the JVM — no Android framework or network access required.
 *
 * Verified invariants:
 *  1. No state mutation — ReplayState is never modified.
 *  2. Correct scope extraction — each [InteractionScope] exposes only its fields.
 *  3. Deterministic output — identical input always produces identical output.
 *  4. No dependency on external systems — engine uses only the supplied state.
 *  5. Correct formatting per [OutputType].
 */
class InteractionContractTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun state(
        phase: String = "idle",
        contractsCompleted: Int = 0,
        totalContracts: Int = 3,
        executionStarted: Boolean = false,
        executionCompleted: Boolean = false,
        assemblyStarted: Boolean = false,
        assemblyValidated: Boolean = false,
        objective: String? = null
    ) = ReplayState(
        phase              = phase,
        contractsCompleted = contractsCompleted,
        totalContracts     = totalContracts,
        executionStarted   = executionStarted,
        executionCompleted = executionCompleted,
        assemblyStarted    = assemblyStarted,
        assemblyValidated  = assemblyValidated,
        objective          = objective
    )

    private val engine    = InteractionEngine()
    private val mapper    = InteractionMapper()
    private val formatter = InteractionFormatter()

    // ── 1. No state mutation ──────────────────────────────────────────────────

    @Test
    fun `execute does not mutate ReplayState`() {
        val original = state(phase = "execution_started", executionStarted = true)
        val snapshot = original.copy()
        engine.execute(
            InteractionContract("id-1", "query", InteractionScope.FULL_SYSTEM, OutputType.SUMMARY, SourceType.LEDGER),
            InteractionInput.LedgerInput(original)
        )
        assertEquals("ReplayState must not be mutated by execute()", snapshot, original)
    }

    @Test
    fun `execute multiple times does not change the state`() {
        val s = state(phase = "contracts_ready", totalContracts = 3)
        val contract = InteractionContract("id-x", "q", InteractionScope.CONTRACT, OutputType.STATUS, SourceType.LEDGER)
        repeat(5) { engine.execute(contract, InteractionInput.LedgerInput(s)) }
        assertEquals("contracts_ready", s.phase)
        assertEquals(3, s.totalContracts)
    }

    // ── 2. Correct scope extraction ───────────────────────────────────────────

    @Test
    fun `FULL_SYSTEM scope exposes all ReplayState fields`() {
        val s = state(
            phase = "execution_started",
            contractsCompleted = 1,
            totalContracts = 3,
            executionStarted = true,
            executionCompleted = false,
            assemblyStarted = false,
            assemblyValidated = false,
            objective = "build the thing"
        )
        val slice = mapper.extract(InteractionScope.FULL_SYSTEM, s)

        assertEquals(s.phase,              slice.phase)
        assertEquals(s.objective,          slice.objective)
        assertEquals(s.contractsCompleted, slice.contractsCompleted)
        assertEquals(s.totalContracts,     slice.totalContracts)
        assertEquals(s.executionStarted,   slice.executionStarted)
        assertEquals(s.executionCompleted, slice.executionCompleted)
        assertEquals(s.assemblyStarted,    slice.assemblyStarted)
        assertEquals(s.assemblyValidated,  slice.assemblyValidated)
    }

    @Test
    fun `CONTRACT scope omits execution and assembly flags`() {
        val s = state(
            executionStarted  = true,
            executionCompleted = true,
            assemblyStarted   = true,
            assemblyValidated = true
        )
        val slice = mapper.extract(InteractionScope.CONTRACT, s)

        assertFalse("CONTRACT scope must not expose executionStarted",   slice.executionStarted)
        assertFalse("CONTRACT scope must not expose executionCompleted", slice.executionCompleted)
        assertFalse("CONTRACT scope must not expose assemblyStarted",    slice.assemblyStarted)
        assertFalse("CONTRACT scope must not expose assemblyValidated",  slice.assemblyValidated)
    }

    @Test
    fun `TASK scope omits objective and assembly fields`() {
        val s = state(objective = "hidden", assemblyStarted = true, assemblyValidated = true,
                      executionCompleted = true)
        val slice = mapper.extract(InteractionScope.TASK, s)

        assertNull("TASK scope must not expose objective",              slice.objective)
        assertFalse("TASK scope must not expose assemblyStarted",       slice.assemblyStarted)
        assertFalse("TASK scope must not expose assemblyValidated",     slice.assemblyValidated)
        assertFalse("TASK scope must not expose executionCompleted",    slice.executionCompleted)
    }

    @Test
    fun `EXECUTION scope omits objective and assembly fields`() {
        val s = state(objective = "hidden", assemblyStarted = true, assemblyValidated = true)
        val slice = mapper.extract(InteractionScope.EXECUTION, s)

        assertNull("EXECUTION scope must not expose objective",          slice.objective)
        assertFalse("EXECUTION scope must not expose assemblyStarted",   slice.assemblyStarted)
        assertFalse("EXECUTION scope must not expose assemblyValidated", slice.assemblyValidated)
    }

    @Test
    fun `SIMULATION scope exposes only phase and objective`() {
        val s = state(
            contractsCompleted = 2,
            totalContracts     = 5,
            executionStarted   = true,
            executionCompleted = true,
            assemblyStarted    = true,
            assemblyValidated  = true,
            objective          = "simulate me"
        )
        val slice = mapper.extract(InteractionScope.SIMULATION, s)

        assertEquals(0,             slice.contractsCompleted)
        assertEquals(0,             slice.totalContracts)
        assertFalse(slice.executionStarted)
        assertFalse(slice.executionCompleted)
        assertFalse(slice.assemblyStarted)
        assertFalse(slice.assemblyValidated)
        assertEquals("simulate me", slice.objective)
    }

    @Test
    fun `references list is non-empty for every scope`() {
        val s = state()
        for (scope in InteractionScope.values()) {
            val slice = mapper.extract(scope, s)
            assertTrue("References must be non-empty for scope $scope", slice.references.isNotEmpty())
        }
    }

    // ── 3. Deterministic output ───────────────────────────────────────────────

    @Test
    fun `same contract and state always produce the same result`() {
        val s = state(phase = "contract_started", contractsCompleted = 1, totalContracts = 3,
                      executionStarted = true)
        val contract = InteractionContract("proj-1", "status?", InteractionScope.FULL_SYSTEM,
                                          OutputType.SUMMARY, SourceType.LEDGER)
        val r1 = engine.execute(contract, InteractionInput.LedgerInput(s))
        val r2 = engine.execute(contract, InteractionInput.LedgerInput(s))
        assertEquals("Output must be deterministic", r1, r2)
    }

    @Test
    fun `different states produce different SUMMARY outputs`() {
        val contract = InteractionContract("c", "q", InteractionScope.FULL_SYSTEM, OutputType.SUMMARY, SourceType.LEDGER)
        val r1 = engine.execute(contract, InteractionInput.LedgerInput(state(phase = "idle")))
        val r2 = engine.execute(contract, InteractionInput.LedgerInput(state(phase = "execution_started")))
        assertNotEquals(r1.content, r2.content)
    }

    // ── 4. No dependency on external systems ──────────────────────────────────

    @Test
    fun `InteractionEngine works with no external dependencies`() {
        val localEngine = InteractionEngine(InteractionMapper(), InteractionFormatter())
        val result = localEngine.execute(
            InteractionContract("self-contained", "test", InteractionScope.FULL_SYSTEM, OutputType.STATUS, SourceType.LEDGER),
            InteractionInput.LedgerInput(state(phase = "idle"))
        )
        assertNotNull(result)
        assertEquals("self-contained", result.contractId)
    }

    @Test
    fun `contractId is echoed verbatim in InteractionResult`() {
        val id = "unique-contract-99"
        val result = engine.execute(
            InteractionContract(id, "q", InteractionScope.FULL_SYSTEM, OutputType.STATUS, SourceType.LEDGER),
            InteractionInput.LedgerInput(state())
        )
        assertEquals(id, result.contractId)
    }

    // ── 5. Correct formatting per OutputType ─────────────────────────────────

    @Test
    fun `SUMMARY output contains phase and contract counts`() {
        val slice = sliceWith(phase = "contract_started", contractsCompleted = 2, totalContracts = 5)
        val output = formatter.format(OutputType.SUMMARY, slice)
        assertTrue("SUMMARY must include phase",    output.contains("contract_started"))
        assertTrue("SUMMARY must include completed", output.contains("2"))
        assertTrue("SUMMARY must include total",     output.contains("5"))
    }

    @Test
    fun `SUMMARY output includes objective when present`() {
        val slice = sliceWith(objective = "do the work")
        val output = formatter.format(OutputType.SUMMARY, slice)
        assertTrue(output.contains("do the work"))
    }

    @Test
    fun `SUMMARY output omits objective label when objective is null`() {
        val slice = sliceWith(objective = null)
        val output = formatter.format(OutputType.SUMMARY, slice)
        assertFalse(output.contains("objective"))
    }

    @Test
    fun `DETAILED output contains all fields`() {
        val slice = sliceWith(
            phase              = "execution_started",
            contractsCompleted = 1,
            totalContracts     = 3,
            executionStarted   = true,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            objective          = "ship it"
        )
        val output = formatter.format(OutputType.DETAILED, slice)
        assertTrue(output.contains("execution_started"))
        assertTrue(output.contains("ship it"))
        assertTrue(output.contains("1"))
        assertTrue(output.contains("3"))
        assertTrue(output.contains("true"))   // executionStarted
        assertTrue(output.contains("false"))  // executionCompleted
    }

    @Test
    fun `EXPLANATION describes execution in progress correctly`() {
        val slice = sliceWith(executionStarted = true, contractsCompleted = 1, totalContracts = 3)
        val output = formatter.format(OutputType.EXPLANATION, slice)
        assertTrue("EXPLANATION must mention in progress", output.contains("in progress"))
        assertTrue("EXPLANATION must mention completed count", output.contains("1"))
        assertTrue("EXPLANATION must mention total", output.contains("3"))
    }

    @Test
    fun `EXPLANATION describes completed execution correctly`() {
        val slice = sliceWith(executionCompleted = true, totalContracts = 3, assemblyValidated = true)
        val output = formatter.format(OutputType.EXPLANATION, slice)
        assertTrue("EXPLANATION must mention completed", output.contains("completed"))
        assertTrue("EXPLANATION must mention assembly validated", output.contains("validated"))
    }

    @Test
    fun `EXPLANATION describes idle state correctly`() {
        val slice = sliceWith(phase = "idle")
        val output = formatter.format(OutputType.EXPLANATION, slice)
        assertTrue("EXPLANATION must include phase name", output.contains("idle"))
    }

    @Test
    fun `STATUS output is minimal and contains phase`() {
        val slice = sliceWith(phase = "contracts_ready")
        val output = formatter.format(OutputType.STATUS, slice)
        assertTrue("STATUS must include phase", output.contains("contracts_ready"))
        assertTrue("STATUS must be short", output.length < 50)
    }

    @Test
    fun `all OutputType values produce non-blank content`() {
        val slice = sliceWith()
        for (type in OutputType.values()) {
            val output = formatter.format(type, slice)
            assertTrue("Output must not be blank for $type", output.isNotBlank())
        }
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private fun sliceWith(
        phase: String = "idle",
        objective: String? = null,
        contractsCompleted: Int = 0,
        totalContracts: Int = 3,
        executionStarted: Boolean = false,
        executionCompleted: Boolean = false,
        assemblyStarted: Boolean = false,
        assemblyValidated: Boolean = false
    ) = StateSlice(
        phase              = phase,
        objective          = objective,
        contractsCompleted = contractsCompleted,
        totalContracts     = totalContracts,
        executionStarted   = executionStarted,
        executionCompleted = executionCompleted,
        assemblyStarted    = assemblyStarted,
        assemblyValidated  = assemblyValidated,
        references         = listOf("phase")
    )
}
