package com.agoii.mobile

import com.agoii.mobile.core.AssemblyStructuralState
import com.agoii.mobile.core.ContractStructuralState
import com.agoii.mobile.core.ExecutionStructuralState
import com.agoii.mobile.core.IntentStructuralState
import com.agoii.mobile.core.ReplayStructuralState
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
 *  1. No state mutation — ReplayStructuralState is never modified.
 *  2. Correct scope extraction — each [InteractionScope] exposes only structural fields.
 *  3. Deterministic output — identical input always produces identical output.
 *  4. No dependency on external systems — engine uses only the supplied state.
 *  5. Correct formatting per [OutputType].
 */
class InteractionContractTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun state(
        assignedTasks: Int = 0,
        fullyExecuted: Boolean = false,
        assemblyStarted: Boolean = false,
        assemblyValidated: Boolean = false,
        assemblyCompleted: Boolean = false,
        contractsValid: Boolean = false
    ) = ReplayStructuralState(
        intent    = IntentStructuralState(structurallyComplete = assignedTasks > 0 || contractsValid),
        contracts = ContractStructuralState(generated = contractsValid, valid = contractsValid),
        execution = ExecutionStructuralState(
            totalTasks     = if (fullyExecuted) 3 else assignedTasks,
            assignedTasks  = assignedTasks,
            completedTasks = if (fullyExecuted) 3 else 0,
            validatedTasks = if (fullyExecuted) 3 else 0,
            fullyExecuted  = fullyExecuted
        ),
        assembly  = AssemblyStructuralState(
            assemblyStarted   = assemblyStarted,
            assemblyValidated = assemblyValidated,
            assemblyCompleted = assemblyCompleted,
            assemblyValid     = assemblyStarted && assemblyValidated && assemblyCompleted && fullyExecuted
        )
    )

    private val engine    = InteractionEngine()
    private val mapper    = InteractionMapper()
    private val formatter = InteractionFormatter()

    // ── 1. No state mutation ──────────────────────────────────────────────────

    @Test
    fun `execute does not mutate ReplayStructuralState`() {
        val original = state(assignedTasks = 1)
        val snapshot = original.copy()
        engine.execute(
            InteractionContract("id-1", "query", InteractionScope.FULL_SYSTEM, OutputType.SUMMARY, SourceType.LEDGER),
            InteractionInput.LedgerInput(original)
        )
        assertEquals("ReplayStructuralState must not be mutated by execute()", snapshot, original)
    }

    @Test
    fun `execute multiple times does not change the state`() {
        val s = state(contractsValid = true)
        val contract = InteractionContract("id-x", "q", InteractionScope.CONTRACT, OutputType.STATUS, SourceType.LEDGER)
        repeat(5) { engine.execute(contract, InteractionInput.LedgerInput(s)) }
        assertTrue(s.contracts.valid)
    }

    // ── 2. Correct scope extraction ───────────────────────────────────────────

    @Test
    fun `FULL_SYSTEM scope exposes execution and assembly fields`() {
        val s = state(
            assignedTasks     = 1,
            fullyExecuted     = false,
            assemblyStarted   = false,
            assemblyValidated = false
        )
        val slice = mapper.extract(InteractionScope.FULL_SYSTEM, s)

        assertTrue(slice.executionStarted)
        assertFalse(slice.executionCompleted)
        assertFalse(slice.assemblyStarted)
        assertFalse(slice.assemblyValidated)
    }

    @Test
    fun `CONTRACT scope omits execution and assembly flags`() {
        val s = state(
            assignedTasks     = 1,
            fullyExecuted     = true,
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
    fun `TASK scope omits assembly fields`() {
        val s = state(assemblyStarted = true, assemblyValidated = true, fullyExecuted = true)
        val slice = mapper.extract(InteractionScope.TASK, s)

        assertFalse("TASK scope must not expose assemblyStarted",    slice.assemblyStarted)
        assertFalse("TASK scope must not expose assemblyValidated",  slice.assemblyValidated)
        assertFalse("TASK scope must not expose executionCompleted", slice.executionCompleted)
    }

    @Test
    fun `EXECUTION scope omits assembly fields`() {
        val s = state(assemblyStarted = true, assemblyValidated = true)
        val slice = mapper.extract(InteractionScope.EXECUTION, s)

        assertFalse("EXECUTION scope must not expose assemblyStarted",   slice.assemblyStarted)
        assertFalse("EXECUTION scope must not expose assemblyValidated", slice.assemblyValidated)
    }

    @Test
    fun `SIMULATION scope returns empty structural slice`() {
        val s = state(
            assignedTasks     = 1,
            fullyExecuted     = true,
            assemblyStarted   = true,
            assemblyValidated = true
        )
        val slice = mapper.extract(InteractionScope.SIMULATION, s)

        assertFalse(slice.executionStarted)
        assertFalse(slice.executionCompleted)
        assertFalse(slice.assemblyStarted)
        assertFalse(slice.assemblyValidated)
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
        val s = state(assignedTasks = 1)
        val contract = InteractionContract("proj-1", "status?", InteractionScope.FULL_SYSTEM,
                                          OutputType.SUMMARY, SourceType.LEDGER)
        val r1 = engine.execute(contract, InteractionInput.LedgerInput(s))
        val r2 = engine.execute(contract, InteractionInput.LedgerInput(s))
        assertEquals("Output must be deterministic", r1, r2)
    }

    @Test
    fun `different states produce different SUMMARY outputs`() {
        val contract = InteractionContract("c", "q", InteractionScope.FULL_SYSTEM, OutputType.SUMMARY, SourceType.LEDGER)
        val r1 = engine.execute(contract, InteractionInput.LedgerInput(state()))
        val r2 = engine.execute(contract, InteractionInput.LedgerInput(state(assignedTasks = 1)))
        assertNotEquals(r1.content, r2.content)
    }

    // ── 4. No dependency on external systems ──────────────────────────────────

    @Test
    fun `InteractionEngine works with no external dependencies`() {
        val localEngine = InteractionEngine(InteractionMapper(), InteractionFormatter())
        val result = localEngine.execute(
            InteractionContract("self-contained", "test", InteractionScope.FULL_SYSTEM, OutputType.STATUS, SourceType.LEDGER),
            InteractionInput.LedgerInput(state())
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
    fun `SUMMARY output contains execution and assembly status`() {
        val slice = sliceWith(executionStarted = true)
        val output = formatter.format(OutputType.SUMMARY, slice)
        assertTrue("SUMMARY must include execution status", output.contains("execution"))
        assertTrue("SUMMARY must include assembly status",  output.contains("assembly"))
    }

    @Test
    fun `DETAILED output contains execution and assembly flags`() {
        val slice = sliceWith(
            executionStarted   = true,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false
        )
        val output = formatter.format(OutputType.DETAILED, slice)
        assertTrue(output.contains("true"))    // executionStarted
        assertTrue(output.contains("false"))   // executionCompleted
    }

    @Test
    fun `EXPLANATION describes execution in progress correctly`() {
        val slice = sliceWith(executionStarted = true)
        val output = formatter.format(OutputType.EXPLANATION, slice)
        assertTrue("EXPLANATION must mention in progress", output.contains("in progress"))
    }

    @Test
    fun `EXPLANATION describes completed execution correctly`() {
        val slice = sliceWith(executionCompleted = true, assemblyValidated = true)
        val output = formatter.format(OutputType.EXPLANATION, slice)
        assertTrue("EXPLANATION must mention completed", output.contains("complete"))
        assertTrue("EXPLANATION must mention assembly validated", output.contains("validated"))
    }

    @Test
    fun `EXPLANATION describes idle state correctly`() {
        val slice = sliceWith()
        val output = formatter.format(OutputType.EXPLANATION, slice)
        assertTrue("EXPLANATION must mention awaiting", output.contains("awaiting"))
    }

    @Test
    fun `STATUS output is minimal`() {
        val slice = sliceWith()
        val output = formatter.format(OutputType.STATUS, slice)
        assertTrue("STATUS must include status", output.contains("status"))
        assertTrue("STATUS must be short", output.length < 80)
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
        executionStarted: Boolean = false,
        executionCompleted: Boolean = false,
        assemblyStarted: Boolean = false,
        assemblyValidated: Boolean = false
    ) = StateSlice(
        executionStarted   = executionStarted,
        executionCompleted = executionCompleted,
        assemblyStarted    = assemblyStarted,
        assemblyValidated  = assemblyValidated,
        references         = listOf("executionStarted")
    )
}
