package com.agoii.mobile

import com.agoii.mobile.core.AssemblyStructuralState
import com.agoii.mobile.core.AuditView
import com.agoii.mobile.core.ContractStructuralState
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.ExecutionStructuralState
import com.agoii.mobile.core.ExecutionView
import com.agoii.mobile.core.GovernanceView
import com.agoii.mobile.core.IntentStructuralState
import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.ui.core.ActionGate
import com.agoii.mobile.ui.core.EventTimelineRenderer
import com.agoii.mobile.ui.core.LedgerViewEngine
import com.agoii.mobile.ui.core.StateProjection
import com.agoii.mobile.ui.modules.ContractModuleUI
import com.agoii.mobile.ui.modules.ExecutionModuleUI
import com.agoii.mobile.ui.modules.TaskModuleUI
import com.agoii.mobile.ui.orchestration.UIModuleRegistry
import com.agoii.mobile.ui.orchestration.UIViewOrchestrator
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the GUI system architecture (ui/core, ui/modules, ui/orchestration).
 *
 * All tests run on the JVM — no Android framework required.
 */
class UIArchitectureTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Builds an empty [GovernanceView] with all defaults for test states. */
    private fun emptyGovernanceView() = GovernanceView(
        lastEventType            = null,
        lastEventPayload         = emptyMap(),
        totalContracts           = 0,
        reportReference          = "",
        deltaContractRecoveryIds = emptySet(),
        taskAssignedTaskIds      = emptySet(),
        lastContractStartedId    = "",
        lastContractStartedPosition = null
    )

    /** Builds an empty [ExecutionView] with all flags false for test states. */
    private fun emptyExecutionView() = ExecutionView(
        taskStatus           = emptyMap(),
        icsStarted           = false,
        icsCompleted         = false,
        commitContractExists = false,
        commitExecuted       = false,
        commitAborted        = false
    )

    private fun idle() = ReplayStructuralState(
        governanceView = emptyGovernanceView(),
        executionView  = emptyExecutionView(),
        auditView      = AuditView(
            intent    = IntentStructuralState(structurallyComplete = false),
            contracts = ContractStructuralState(generated = false, valid = false),
            execution = ExecutionStructuralState(0, 0, 0, 0),
            assembly  = AssemblyStructuralState(false, false, false),
            icsStarted = false,
            icsCompleted = false,
            commitContractExists = false,
            commitExecuted = false,
            commitAborted = false
        )
    )

    private fun contractsValid() = ReplayStructuralState(
        governanceView = emptyGovernanceView().copy(totalContracts = 1),
        executionView  = emptyExecutionView(),
        auditView      = AuditView(
            intent    = IntentStructuralState(structurallyComplete = true),
            // totalContracts is intentionally set in both GovernanceView and ContractStructuralState
            // because each view serves a different authority.  In production both are derived from
            // the same CONTRACTS_GENERATED event so they always agree.
            contracts = ContractStructuralState(generated = true, valid = true, totalContracts = 1),
            execution = ExecutionStructuralState(0, 0, 0, 0),
            assembly  = AssemblyStructuralState(false, false, false),
            icsStarted = false,
            icsCompleted = false,
            commitContractExists = false,
            commitExecuted = false,
            commitAborted = false
        )
    )

    private fun executionStarted() = ReplayStructuralState(
        governanceView = emptyGovernanceView().copy(totalContracts = 3),
        executionView  = emptyExecutionView(),
        auditView      = AuditView(
            intent    = IntentStructuralState(structurallyComplete = true),
            contracts = ContractStructuralState(generated = true, valid = true, totalContracts = 3),
            execution = ExecutionStructuralState(3, 1, 0, 0),
            assembly  = AssemblyStructuralState(false, false, false),
            icsStarted = false,
            icsCompleted = false,
            commitContractExists = false,
            commitExecuted = false,
            commitAborted = false
        )
    )

    private fun executionCompleted() = ReplayStructuralState(
        governanceView = emptyGovernanceView().copy(totalContracts = 3),
        executionView  = emptyExecutionView(),
        auditView      = AuditView(
            intent    = IntentStructuralState(structurallyComplete = true),
            contracts = ContractStructuralState(generated = true, valid = true, totalContracts = 3),
            execution = ExecutionStructuralState(3, 3, 3, 3),
            assembly  = AssemblyStructuralState(false, false, false),
            icsStarted = false,
            icsCompleted = false,
            commitContractExists = false,
            commitExecuted = false,
            commitAborted = false
        )
    )

    private fun assemblyCompleted() = ReplayStructuralState(
        governanceView = emptyGovernanceView().copy(totalContracts = 3),
        executionView  = emptyExecutionView(),
        auditView      = AuditView(
            intent    = IntentStructuralState(structurallyComplete = true),
            contracts = ContractStructuralState(generated = true, valid = true, totalContracts = 3),
            execution = ExecutionStructuralState(3, 3, 3, 3),
            assembly  = AssemblyStructuralState(true, true, true),
            icsStarted = false,
            icsCompleted = false,
            commitContractExists = false,
            commitExecuted = false,
            commitAborted = false
        )
    )

    // ── StateProjection ───────────────────────────────────────────────────────

    @Test
    fun `StateProjection maps idle state correctly`() {
        val ui = StateProjection().project(idle())
        assertFalse(ui.isComplete)
        assertFalse(ui.assemblyStarted)
        assertFalse(ui.assemblyValidated)
        assertFalse(ui.assemblyCompleted)
    }

    @Test
    fun `StateProjection marks isComplete on assembly_completed`() {
        val ui = StateProjection().project(assemblyCompleted())
        assertTrue(ui.isComplete)
        assertTrue(ui.assemblyStarted)
        assertTrue(ui.assemblyValidated)
        assertTrue(ui.assemblyCompleted)
    }

    @Test
    fun `StateProjection assembly flags false for idle state`() {
        val ui = StateProjection().project(idle())
        assertFalse(ui.assemblyStarted)
        assertFalse(ui.assemblyValidated)
        assertFalse(ui.assemblyCompleted)
    }

    // ── LedgerViewEngine ──────────────────────────────────────────────────────

    @Test
    fun `LedgerViewEngine returns defaults before any render`() {
        val engine = LedgerViewEngine()
        assertFalse(engine.assemblyStarted)
        assertFalse(engine.assemblyValidated)
        assertFalse(engine.assemblyCompleted)
        assertNull(engine.currentUIState())
    }

    @Test
    fun `LedgerViewEngine render updates all exposed properties`() {
        val engine = LedgerViewEngine()
        engine.render(executionStarted())
        assertNotNull(engine.currentUIState())
    }

    @Test
    fun `LedgerViewEngine assembly properties reflect structural state after render`() {
        val engine = LedgerViewEngine()
        engine.render(assemblyCompleted())
        assertTrue(engine.assemblyStarted)
        assertTrue(engine.assemblyValidated)
        assertTrue(engine.assemblyCompleted)
    }

    // ── EventTimelineRenderer ─────────────────────────────────────────────────

    @Test
    fun `EventTimelineRenderer on empty list returns empty`() {
        val blocks = EventTimelineRenderer().render(emptyList())
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `EventTimelineRenderer assigns sequential positions`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
            Event(EventTypes.CONTRACTS_READY, emptyMap())
        )
        val blocks = EventTimelineRenderer().render(events)
        assertEquals(3, blocks.size)
        assertEquals(0, blocks[0].position)
        assertEquals(1, blocks[1].position)
        assertEquals(2, blocks[2].position)
    }

    @Test
    fun `EventTimelineRenderer preserves event type`() {
        val events = listOf(Event(EventTypes.EXECUTION_STARTED, emptyMap()))
        val block = EventTimelineRenderer().render(events).first()
        assertEquals(EventTypes.EXECUTION_STARTED, block.type)
        assertEquals("started", block.status)
    }

    @Test
    fun `EventTimelineRenderer uses payload status when present`() {
        val events = listOf(Event("contract_started", mapOf("status" to "custom_status")))
        val block = EventTimelineRenderer().render(events).first()
        assertEquals("custom_status", block.status)
    }

    @Test
    fun `EventTimelineRenderer labels unknown events as unknown`() {
        val block = EventTimelineRenderer().render(listOf(Event("some_new_event", emptyMap()))).first()
        assertEquals("unknown", block.status)
    }

    // ── ActionGate ────────────────────────────────────────────────────────────

    @Test
    fun `ActionGate all false on idle state`() {
        val avail = ActionGate().evaluate(idle())
        assertFalse(avail.canApproveContracts)
        assertFalse(avail.canStartExecution)
        assertFalse(avail.canRetry)
    }

    @Test
    fun `ActionGate canApproveContracts true when contracts valid and execution not started`() {
        assertTrue(ActionGate().evaluate(contractsValid()).canApproveContracts)
        assertFalse(ActionGate().evaluate(idle()).canApproveContracts)
    }

    @Test
    fun `ActionGate canRetry true when execution started but not completed`() {
        assertTrue(ActionGate().evaluate(executionStarted()).canRetry)
        assertFalse(ActionGate().evaluate(executionCompleted()).canRetry)
    }

    // ── ContractModuleUI ──────────────────────────────────────────────────────

    @Test
    fun `ContractModuleUI returns empty list`() {
        val ui = StateProjection().project(idle())
        val result = ContractModuleUI().present(ui)
        assertTrue(result.contracts.isEmpty())
    }

    // ── TaskModuleUI ──────────────────────────────────────────────────────────

    @Test
    fun `TaskModuleUI returns empty when no active task`() {
        val ui = StateProjection().project(idle())
        val result = TaskModuleUI().present(ui)
        assertTrue(result.tasks.isEmpty())
    }

    // ── ExecutionModuleUI ─────────────────────────────────────────────────────

    @Test
    fun `ExecutionModuleUI idle state shows no assembly activity`() {
        val ui = StateProjection().project(idle())
        val result = ExecutionModuleUI().present(ui)
        assertFalse(result.executionStarted)
        assertFalse(result.executionCompleted)
        assertFalse(result.assemblyStarted)
        assertFalse(result.assemblyValidated)
        assertFalse(result.assemblyCompleted)
    }

    @Test
    fun `ExecutionModuleUI assembly completed state shows assembly activity`() {
        val ui = StateProjection().project(assemblyCompleted())
        val result = ExecutionModuleUI().present(ui)
        assertTrue(result.executionStarted)
        assertTrue(result.executionCompleted)
        assertTrue(result.assemblyStarted)
        assertTrue(result.assemblyValidated)
        assertTrue(result.assemblyCompleted)
    }

    // ── UIModuleRegistry ──────────────────────────────────────────────────────

    @Test
    fun `UIModuleRegistry registers three default modules`() {
        val registry = UIModuleRegistry()
        val modules = registry.getModules()
        assertEquals(3, modules.size)
        val ids = modules.map { it.id }.toSet()
        assertTrue(ids.contains("contract"))
        assertTrue(ids.contains("task"))
        assertTrue(ids.contains("execution"))
    }

    @Test
    fun `UIModuleRegistry allows additional module registration`() {
        val registry = UIModuleRegistry()
        registry.register(object : com.agoii.mobile.ui.orchestration.UIModule {
            override val id = "custom"
            override fun render(state: com.agoii.mobile.ui.core.UIState) = "custom-output"
        })
        assertEquals(4, registry.getModules().size)
        assertTrue(registry.getModules().any { it.id == "custom" })
    }

    // ── UIViewOrchestrator ────────────────────────────────────────────────────

    @Test
    fun `UIViewOrchestrator produces CombinedViewState with core and module outputs`() {
        val combined = UIViewOrchestrator().orchestrate(idle())
        assertNotNull(combined.core)
        assertTrue(combined.modules.containsKey("contract"))
        assertTrue(combined.modules.containsKey("task"))
        assertTrue(combined.modules.containsKey("execution"))
    }

    @Test
    fun `UIViewOrchestrator core UIState matches StateProjection output`() {
        val state = executionStarted()
        val combined = UIViewOrchestrator().orchestrate(state)
        val expected = StateProjection().project(state)
        assertEquals(expected, combined.core)
    }

    @Test
    fun `UIViewOrchestrator is deterministic same input same output`() {
        val state = assemblyCompleted()
        val orchestrator = UIViewOrchestrator()
        val first = orchestrator.orchestrate(state)
        val second = orchestrator.orchestrate(state)
        assertEquals(first.core, second.core)
    }
}
