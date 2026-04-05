package com.agoii.mobile

import com.agoii.mobile.core.AssemblyStructuralState
import com.agoii.mobile.core.AuditView
import com.agoii.mobile.core.ContractStructuralState
import com.agoii.mobile.core.ExecutionStructuralState
import com.agoii.mobile.core.ExecutionView
import com.agoii.mobile.core.GovernanceView
import com.agoii.mobile.core.IntentStructuralState
import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.interaction.InteractionContract
import com.agoii.mobile.interaction.InteractionEngine
import com.agoii.mobile.interaction.InteractionFormatter
import com.agoii.mobile.interaction.InteractionInput
import com.agoii.mobile.interaction.InteractionMapper
import com.agoii.mobile.interaction.OutputType
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
 *  2. Correct extraction — [InteractionMapper] maps all structural fields directly.
 *  3. Deterministic output — identical input always produces identical output.
 *  4. No dependency on external systems — engine uses only the supplied state.
 *  5. Correct formatting per [OutputType].
 */
class InteractionContractTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun emptyGovernanceView() = GovernanceView(
        lastEventType = null, lastEventPayload = emptyMap(),
        totalContracts = 0, reportReference = "",
        deltaContractRecoveryIds = emptySet(), taskAssignedTaskIds = emptySet(),
        lastContractStartedId = "", lastContractStartedPosition = null
    )

    private fun emptyExecutionView() = ExecutionView(
        taskStatus = emptyMap(), icsStarted = false, icsCompleted = false,
        commitContractExists = false, commitExecuted = false,
        commitAborted = false
    )

    private fun state(
        assignedTasks: Int = 0,
        fullyExecuted: Boolean = false,
        assemblyStarted: Boolean = false,
        assemblyValidated: Boolean = false,
        assemblyCompleted: Boolean = false,
        contractsValid: Boolean = false
    ) = ReplayStructuralState(
        governanceView = emptyGovernanceView(),
        executionView  = emptyExecutionView(),
        auditView      = AuditView(
            intent    = IntentStructuralState(structurallyComplete = assignedTasks > 0 || contractsValid),
            contracts = ContractStructuralState(generated = contractsValid, valid = contractsValid),
            execution = ExecutionStructuralState(
                totalTasks     = if (fullyExecuted) 3 else assignedTasks,
                assignedTasks  = assignedTasks,
                completedTasks = if (fullyExecuted) 3 else 0,
                validatedTasks = if (fullyExecuted) 3 else 0
            ),
            assembly  = AssemblyStructuralState(
                assemblyStarted   = assemblyStarted,
                assemblyValidated = assemblyValidated,
                assemblyCompleted = assemblyCompleted
            ),
            icsStarted = false,
            icsCompleted = false,
            commitContractExists = false,
            commitExecuted = false,
            commitAborted = false
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
            InteractionContract("id-1", "query", OutputType.SUMMARY),
            InteractionInput(original)
        )
        assertEquals("ReplayStructuralState must not be mutated by execute()", snapshot, original)
    }

    @Test
    fun `execute multiple times does not change the state`() {
        val s = state(contractsValid = true)
        val contract = InteractionContract("id-x", "q", OutputType.STATUS)
        repeat(5) { engine.execute(contract, InteractionInput(s)) }
        assertTrue(s.auditView.contracts.valid)
    }

    // ── 2. Correct extraction ─────────────────────────────────────────────────

    @Test
    fun `extract maps execution and assembly fields directly from state`() {
        val s = state(assignedTasks = 1, assemblyStarted = true, assemblyValidated = true)
        val slice = mapper.extract(s)

        assertTrue("executionStarted must reflect assignedTasks > 0", slice.executionStarted)
        assertFalse("executionCompleted must be false when not fully executed", slice.executionCompleted)
        assertTrue("assemblyStarted must reflect state", slice.assemblyStarted)
        assertTrue("assemblyValidated must reflect state", slice.assemblyValidated)
    }

    @Test
    fun `extract marks execution completed when all tasks validated`() {
        val s = state(fullyExecuted = true)
        val slice = mapper.extract(s)

        assertTrue("executionCompleted must be true when all tasks validated", slice.executionCompleted)
    }

    @Test
    fun `extract returns all-false slice for empty state`() {
        val slice = mapper.extract(state())

        assertFalse(slice.executionStarted)
        assertFalse(slice.executionCompleted)
        assertFalse(slice.assemblyStarted)
        assertFalse(slice.assemblyValidated)
    }

    // ── 3. Deterministic output ───────────────────────────────────────────────

    @Test
    fun `same contract and state always produce the same result`() {
        val s = state(assignedTasks = 1)
        val contract = InteractionContract("proj-1", "status?", OutputType.SUMMARY)
        val r1 = engine.execute(contract, InteractionInput(s))
        val r2 = engine.execute(contract, InteractionInput(s))
        assertEquals("Output must be deterministic", r1, r2)
    }

    @Test
    fun `different states produce different SUMMARY outputs`() {
        val contract = InteractionContract("c", "q", OutputType.SUMMARY)
        val r1 = engine.execute(contract, InteractionInput(state()))
        val r2 = engine.execute(contract, InteractionInput(state(assignedTasks = 1)))
        assertNotEquals(r1.content, r2.content)
    }

    // ── 4. No dependency on external systems ──────────────────────────────────

    @Test
    fun `InteractionEngine works with no external dependencies`() {
        val localEngine = InteractionEngine(InteractionMapper(), InteractionFormatter())
        val result = localEngine.execute(
            InteractionContract("self-contained", "test", OutputType.STATUS),
            InteractionInput(state())
        )
        assertNotNull(result)
        assertEquals("self-contained", result.contractId)
    }

    @Test
    fun `contractId is echoed verbatim in InteractionResult`() {
        val id = "unique-contract-99"
        val result = engine.execute(
            InteractionContract(id, "q", OutputType.STATUS),
            InteractionInput(state())
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
        assertTrue("EXPLANATION must mention idle", output.contains("idle"))
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
        assemblyValidated  = assemblyValidated
    )
}
