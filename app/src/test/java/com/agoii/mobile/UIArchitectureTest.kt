package com.agoii.mobile

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.ui.core.ActionGate
import com.agoii.mobile.ui.core.EventTimelineRenderer
import com.agoii.mobile.ui.core.LedgerViewEngine
import com.agoii.mobile.ui.core.StateProjection
import com.agoii.mobile.ui.modules.ContractModuleUI
import com.agoii.mobile.ui.modules.ExecutionModuleUI
import com.agoii.mobile.ui.modules.TaskLifecycleState
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

    private fun idle() = ReplayState(
        phase = "idle",
        contractsCompleted = 0,
        totalContracts = 0,
        executionStarted = false,
        executionCompleted = false,
        assemblyStarted = false,
        assemblyValidated = false,
        objective = null
    )

    private fun ready() = idle().copy(
        phase = EventTypes.CONTRACTS_READY,
        totalContracts = 3
    )

    private fun approved() = ready().copy(
        phase = EventTypes.CONTRACTS_APPROVED
    )

    private fun executionStarted() = approved().copy(
        phase = EventTypes.EXECUTION_STARTED,
        executionStarted = true
    )

    private fun contractStarted(completed: Int = 0) = executionStarted().copy(
        phase = EventTypes.CONTRACT_STARTED,
        contractsCompleted = completed
    )

    private fun contractCompleted(completed: Int = 1) = contractStarted(completed - 1).copy(
        phase = EventTypes.CONTRACT_COMPLETED,
        contractsCompleted = completed
    )

    private fun executionCompleted() = contractCompleted(3).copy(
        phase = EventTypes.EXECUTION_COMPLETED,
        executionCompleted = true
    )

    private fun assemblyCompleted() = executionCompleted().copy(
        phase = EventTypes.ASSEMBLY_COMPLETED,
        assemblyStarted = true,
        assemblyValidated = true
    )

    // ── StateProjection ───────────────────────────────────────────────────────

    @Test
    fun `StateProjection maps idle state correctly`() {
        val ui = StateProjection().project(idle())
        assertEquals("idle", ui.phase)
        assertNull(ui.activeContractId)
        assertNull(ui.activeTaskId)
        assertEquals(0f, ui.progress, 0.001f)
        assertFalse(ui.isComplete)
    }

    @Test
    fun `StateProjection progress is fraction of completed over total`() {
        val state = contractCompleted(1).copy(totalContracts = 3)
        val ui = StateProjection().project(state)
        assertEquals(1f / 3f, ui.progress, 0.001f)
        assertFalse(ui.isComplete)
    }

    @Test
    fun `StateProjection marks isComplete on assembly_completed`() {
        val ui = StateProjection().project(assemblyCompleted())
        assertTrue(ui.isComplete)
        assertEquals(1f, ui.progress, 0.001f)
    }

    @Test
    fun `StateProjection progress clamped to 0-1`() {
        val state = idle().copy(totalContracts = 0, contractsCompleted = 0)
        val ui = StateProjection().project(state)
        assertEquals(0f, ui.progress, 0.001f)
    }

    // ── LedgerViewEngine ──────────────────────────────────────────────────────

    @Test
    fun `LedgerViewEngine returns idle defaults before any render`() {
        val engine = LedgerViewEngine()
        assertEquals("idle", engine.currentPhase)
        assertNull(engine.activeContract)
        assertNull(engine.activeTask)
        assertEquals(0f, engine.executionProgress, 0.001f)
        assertNull(engine.currentUIState())
    }

    @Test
    fun `LedgerViewEngine render updates all exposed properties`() {
        val engine = LedgerViewEngine()
        engine.render(executionStarted())
        assertEquals(EventTypes.EXECUTION_STARTED, engine.currentPhase)
        assertNotNull(engine.currentUIState())
    }

    @Test
    fun `LedgerViewEngine executionProgress reflects StateProjection progress`() {
        val engine = LedgerViewEngine()
        val state = contractCompleted(2).copy(totalContracts = 3)
        engine.render(state)
        assertEquals(2f / 3f, engine.executionProgress, 0.001f)
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
    fun `ActionGate canApproveContracts true only when contracts_ready`() {
        assertTrue(ActionGate().evaluate(ready()).canApproveContracts)
        assertFalse(ActionGate().evaluate(approved()).canApproveContracts)
    }

    @Test
    fun `ActionGate canStartExecution true only when approved and not started`() {
        assertTrue(ActionGate().evaluate(approved()).canStartExecution)
        assertFalse(ActionGate().evaluate(executionStarted()).canStartExecution)
    }

    @Test
    fun `ActionGate canRetry true when execution started but not completed`() {
        assertTrue(ActionGate().evaluate(executionStarted()).canRetry)
        assertFalse(ActionGate().evaluate(executionCompleted()).canRetry)
    }

    // ── ContractModuleUI ──────────────────────────────────────────────────────

    @Test
    fun `ContractModuleUI returns empty list when no contracts`() {
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

    @Test
    fun `TaskModuleUI derives lifecycle state from phase`() {
        val uiState = StateProjection().project(idle()).copy(
            phase = "task_started",
            activeTaskId = "task-1"
        )
        val result = TaskModuleUI().present(uiState)
        assertEquals(1, result.tasks.size)
        assertEquals(TaskLifecycleState.STARTED, result.tasks[0].lifecycleState)
        assertEquals("assigned", result.tasks[0].assignmentStatus)
    }

    @Test
    fun `TaskModuleUI reports failed lifecycle state`() {
        val uiState = StateProjection().project(idle()).copy(
            phase = "task_failed",
            activeTaskId = "task-2"
        )
        val result = TaskModuleUI().present(uiState)
        assertEquals(TaskLifecycleState.FAILED, result.tasks[0].lifecycleState)
        assertEquals("failed", result.tasks[0].assignmentStatus)
    }

    // ── ExecutionModuleUI ─────────────────────────────────────────────────────

    @Test
    fun `ExecutionModuleUI idle state shows no task activity`() {
        val ui = StateProjection().project(idle())
        val result = ExecutionModuleUI().present(ui)
        assertFalse(result.taskStarted)
        assertFalse(result.taskCompleted)
        assertFalse(result.taskFailed)
        assertEquals(0, result.retryCount)
        assertEquals("pending", result.validationStatus)
    }

    @Test
    fun `ExecutionModuleUI task_started phase sets taskStarted`() {
        val uiState = StateProjection().project(idle()).copy(phase = "task_started")
        val result = ExecutionModuleUI().present(uiState)
        assertTrue(result.taskStarted)
        assertFalse(result.taskCompleted)
        assertFalse(result.taskFailed)
    }

    @Test
    fun `ExecutionModuleUI task_completed phase sets taskStarted and taskCompleted`() {
        val uiState = StateProjection().project(idle()).copy(phase = "task_completed")
        val result = ExecutionModuleUI().present(uiState)
        assertTrue(result.taskStarted)
        assertTrue(result.taskCompleted)
        assertFalse(result.taskFailed)
    }

    @Test
    fun `ExecutionModuleUI task_failed phase sets taskFailed`() {
        val uiState = StateProjection().project(idle()).copy(phase = "task_failed")
        val result = ExecutionModuleUI().present(uiState)
        assertTrue(result.taskFailed)
        assertEquals("failed", result.validationStatus)
    }

    @Test
    fun `ExecutionModuleUI task_validated shows validated status`() {
        val uiState = StateProjection().project(idle()).copy(phase = "task_validated")
        val result = ExecutionModuleUI().present(uiState)
        assertEquals("validated", result.validationStatus)
    }

    @Test
    fun `ExecutionModuleUI contractor_reassigned increments retry count`() {
        val uiState = StateProjection().project(idle()).copy(phase = "contractor_reassigned")
        val result = ExecutionModuleUI().present(uiState)
        assertEquals(1, result.retryCount)
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
        assertEquals("idle", combined.core.phase)
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
        val state = contractCompleted(2)
        val orchestrator = UIViewOrchestrator()
        val first = orchestrator.orchestrate(state)
        val second = orchestrator.orchestrate(state)
        assertEquals(first.core, second.core)
    }
}
