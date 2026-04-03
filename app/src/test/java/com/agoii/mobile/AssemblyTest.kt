package com.agoii.mobile

import com.agoii.mobile.assembly.AssemblyValidator
import com.agoii.mobile.core.AssemblyStructuralState
import com.agoii.mobile.core.AuditView
import com.agoii.mobile.core.ContractStructuralState
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.ExecutionStructuralState
import com.agoii.mobile.core.ExecutionView
import com.agoii.mobile.core.GovernanceView
import com.agoii.mobile.core.IntentStructuralState
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.ui.core.LedgerViewEngine
import com.agoii.mobile.ui.core.StateProjection
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the Assembly validation layer and its integration with Replay and UI.
 *
 * Rules verified:
 *  - Assembly accepts a ReplayStructuralState and returns an AssemblyResult (no mutation, no hidden state).
 *  - Valid execution → assembly passes.
 *  - Incomplete execution → assembly blocked.
 *  - Deterministic output from same state.
 *  - No mutation occurs inside AssemblyValidator.
 */
class AssemblyTest {

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
        commitAborted = false, commitPending = false
    )

    private fun store(): EventRepository = object : EventRepository {
        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {}
        override fun loadEvents(projectId: String): List<Event> = emptyList()
    }

    /** Builds a minimal but complete single-contract execution ledger. */
    private fun singleContractLedger(): List<Event> = listOf(
        Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "test")),
        Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
        Event(EventTypes.CONTRACTS_READY,     emptyMap()),
        Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
        Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 1.0)),
        Event(EventTypes.TASK_CREATED,        mapOf("task_id" to "task-1")),
        Event(EventTypes.TASK_ASSIGNED,       mapOf("task_id" to "task-1")),
        Event(EventTypes.TASK_COMPLETED,      mapOf("task_id" to "task-1")),
        Event(EventTypes.TASK_VALIDATED,      mapOf("task_id" to "task-1")),
        Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 1)),
        Event(EventTypes.ASSEMBLY_STARTED,    emptyMap()),
        Event(EventTypes.ASSEMBLY_VALIDATED,  emptyMap())
    )

    /** Builds a complete 3-task execution ledger. */
    private fun threeTaskLedger(): List<Event> = listOf(
        Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "test")),
        Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
        Event(EventTypes.CONTRACTS_READY,     emptyMap()),
        Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
        Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 3.0)),
        Event(EventTypes.TASK_CREATED,        mapOf("task_id" to "task-1")),
        Event(EventTypes.TASK_CREATED,        mapOf("task_id" to "task-2")),
        Event(EventTypes.TASK_CREATED,        mapOf("task_id" to "task-3")),
        Event(EventTypes.TASK_ASSIGNED,       mapOf("task_id" to "task-1")),
        Event(EventTypes.TASK_COMPLETED,      mapOf("task_id" to "task-1")),
        Event(EventTypes.TASK_VALIDATED,      mapOf("task_id" to "task-1")),
        Event(EventTypes.TASK_ASSIGNED,       mapOf("task_id" to "task-2")),
        Event(EventTypes.TASK_COMPLETED,      mapOf("task_id" to "task-2")),
        Event(EventTypes.TASK_VALIDATED,      mapOf("task_id" to "task-2")),
        Event(EventTypes.TASK_ASSIGNED,       mapOf("task_id" to "task-3")),
        Event(EventTypes.TASK_COMPLETED,      mapOf("task_id" to "task-3")),
        Event(EventTypes.TASK_VALIDATED,      mapOf("task_id" to "task-3")),
        Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 3)),
        Event(EventTypes.ASSEMBLY_STARTED,    emptyMap()),
        Event(EventTypes.ASSEMBLY_VALIDATED,  emptyMap())
    )

    private fun replayStateAt(events: List<Event>): ReplayStructuralState =
        Replay(store()).deriveStructuralState(events)

    // ── AssemblyValidator: valid execution → assembly passes ──────────────────

    @Test
    fun `valid single-task execution passes assembly validation`() {
        val result = AssemblyValidator().validate(replayStateAt(singleContractLedger()))
        val allIssues = result.missingElements + result.failedChecks
        assertTrue("Expected valid but got issues: $allIssues", result.isValid)
        assertTrue(result.missingElements.isEmpty())
        assertTrue(result.failedChecks.isEmpty())
    }

    @Test
    fun `valid three-task execution passes assembly validation`() {
        val result = AssemblyValidator().validate(replayStateAt(threeTaskLedger()))
        val allIssues = result.missingElements + result.failedChecks
        assertTrue("Expected valid but got issues: $allIssues", result.isValid)
        assertTrue(result.missingElements.isEmpty())
        assertTrue(result.failedChecks.isEmpty())
    }

    // ── AssemblyValidator: incomplete execution → assembly blocked ────────────

    @Test
    fun `missing execution_completed blocks assembly validation`() {
        val events = singleContractLedger().filter {
            it.type != EventTypes.EXECUTION_COMPLETED
        }
        val result = AssemblyValidator().validate(replayStateAt(events))
        assertFalse(result.isValid)
        assertTrue(result.failedChecks.any { "execution_completed" in it })
    }

    @Test
    fun `assembly_started before execution_completed is an illegal transition`() {
        val state = ReplayStructuralState(
            governanceView = emptyGovernanceView(),
            executionView  = emptyExecutionView(),
            auditView      = AuditView(
                intent    = IntentStructuralState(structurallyComplete = true),
                contracts = ContractStructuralState(generated = true, valid = true),
                execution = ExecutionStructuralState(1, 1, 0, 0, fullyExecuted = false),
                assembly  = AssemblyStructuralState(
                    assemblyStarted   = true,
                    assemblyValidated = false,
                    assemblyCompleted = false,
                    assemblyValid     = false
                ),
                executionValid = false,
                assemblyValid  = false,
                icsValid       = false,
                commitValid    = false
            )
        )
        val result = AssemblyValidator().validate(state)
        assertFalse(result.isValid)
        assertTrue(result.failedChecks.any { "execution_completed" in it })
    }

    // ── AssemblyValidator: deterministic output ───────────────────────────────

    @Test
    fun `assembly validation is deterministic — same state same result`() {
        val state  = replayStateAt(threeTaskLedger())
        val first  = AssemblyValidator().validate(state)
        val second = AssemblyValidator().validate(state)
        assertEquals(first.isValid,          second.isValid)
        assertEquals(first.completionStatus, second.completionStatus)
        assertEquals(first.missingElements,  second.missingElements)
        assertEquals(first.failedChecks,     second.failedChecks)
    }

    @Test
    fun `assembly validation produces no side effects — state unchanged`() {
        val state = replayStateAt(singleContractLedger())
        val executionBefore = state.auditView.execution.fullyExecuted
        AssemblyValidator().validate(state)
        assertEquals(executionBefore, state.auditView.execution.fullyExecuted)
    }

    // ── ReplayStructuralState: assemblyCompleted is derived from ledger ───────

    @Test
    fun `assemblyCompleted is false before assembly_completed event`() {
        val state = Replay(store()).deriveStructuralState(singleContractLedger())
        // ledger ends at assembly_validated — assemblyCompleted must be false
        assertFalse(state.auditView.assembly.assemblyCompleted)
        assertTrue(state.auditView.assembly.assemblyStarted)
        assertTrue(state.auditView.assembly.assemblyValidated)
    }

    @Test
    fun `assemblyCompleted is true after assembly_completed event`() {
        val events = singleContractLedger() + Event(EventTypes.ASSEMBLY_COMPLETED, emptyMap())
        val state = Replay(store()).deriveStructuralState(events)
        assertTrue(state.auditView.assembly.assemblyCompleted)
    }

    @Test
    fun `assembly flags all false on empty ledger`() {
        val state = Replay(store()).deriveStructuralState(emptyList())
        assertFalse(state.auditView.assembly.assemblyStarted)
        assertFalse(state.auditView.assembly.assemblyValidated)
        assertFalse(state.auditView.assembly.assemblyCompleted)
    }

    // ── UI integration: assembly visibility in StateProjection ────────────────

    @Test
    fun `StateProjection exposes assemblyStarted from structural state`() {
        val events = singleContractLedger()   // ends at assembly_validated
        val ui = StateProjection().project(replayStateAt(events))
        assertTrue(ui.assemblyStarted)
        assertTrue(ui.assemblyValidated)
        assertFalse(ui.assemblyCompleted)
    }

    @Test
    fun `StateProjection assemblyCompleted true when assembly_completed reached`() {
        val events = singleContractLedger() + Event(EventTypes.ASSEMBLY_COMPLETED, emptyMap())
        val ui = StateProjection().project(replayStateAt(events))
        assertTrue(ui.assemblyStarted)
        assertTrue(ui.assemblyValidated)
        assertTrue(ui.assemblyCompleted)
        assertTrue(ui.isComplete)
    }

    @Test
    fun `StateProjection assembly flags all false on idle state`() {
        val idleState = ReplayStructuralState(
            governanceView = emptyGovernanceView(),
            executionView  = emptyExecutionView(),
            auditView      = AuditView(
                intent    = IntentStructuralState(structurallyComplete = false),
                contracts = ContractStructuralState(generated = false, valid = false),
                execution = ExecutionStructuralState(0, 0, 0, 0, false),
                assembly  = AssemblyStructuralState(false, false, false, false),
                executionValid = false,
                assemblyValid  = false,
                icsValid       = false,
                commitValid    = false
            )
        )
        val ui = StateProjection().project(idleState)
        assertFalse(ui.assemblyStarted)
        assertFalse(ui.assemblyValidated)
        assertFalse(ui.assemblyCompleted)
    }

    // ── LedgerViewEngine: assembly visibility ─────────────────────────────────

    @Test
    fun `LedgerViewEngine assembly properties are false before any render`() {
        val engine = LedgerViewEngine()
        assertFalse(engine.assemblyStarted)
        assertFalse(engine.assemblyValidated)
        assertFalse(engine.assemblyCompleted)
    }

    @Test
    fun `LedgerViewEngine reflects assembly state after render`() {
        val engine = LedgerViewEngine()
        val state = replayStateAt(singleContractLedger())  // assembly_started + validated
        engine.render(state)
        assertTrue(engine.assemblyStarted)
        assertTrue(engine.assemblyValidated)
        assertFalse(engine.assemblyCompleted)
    }

    @Test
    fun `LedgerViewEngine assemblyCompleted true when full assembly reached`() {
        val engine = LedgerViewEngine()
        val events = singleContractLedger() + Event(EventTypes.ASSEMBLY_COMPLETED, emptyMap())
        engine.render(replayStateAt(events))
        assertTrue(engine.assemblyCompleted)
    }
}
