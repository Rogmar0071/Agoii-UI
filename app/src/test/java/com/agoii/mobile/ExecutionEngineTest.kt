package com.agoii.mobile

import com.agoii.mobile.contractor.ContractorCapabilityVector
import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.VerificationStatus
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.execution.ContractorExecutionInput
import com.agoii.mobile.execution.ContractorExecutor
import com.agoii.mobile.execution.ExecutionEventEmitter
import com.agoii.mobile.execution.ExecutionOrchestrator
import com.agoii.mobile.execution.ExecutionStatus
import com.agoii.mobile.execution.ResultValidator
import com.agoii.mobile.execution.RetryDecision
import com.agoii.mobile.execution.RetryEngine
import com.agoii.mobile.execution.TaskLifecycleManager
import com.agoii.mobile.execution.TaskLifecycleState
import com.agoii.mobile.execution.ValidationVerdict
import com.agoii.mobile.tasks.Task
import com.agoii.mobile.tasks.TaskAssignmentStatus
import com.agoii.mobile.tasks.TaskGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AGOII-EXECUTION-ENGINE-01.
 *
 * Covers:
 *  - TaskLifecycleManager  (valid transitions, terminal states)
 *  - ContractorExecutor    (success / failure outputs)
 *  - ResultValidator       (VALIDATED / FAILED verdicts, rule evaluation)
 *  - RetryEngine           (RETRY → REASSIGN → ESCALATE chain)
 *  - ExecutionEventEmitter (correct event types appended to store)
 *  - ExecutionOrchestrator (full step-by-step lifecycle, failure handling)
 *
 * All tests run on the JVM without an Android device.
 */
class ExecutionEngineTest {

    // ── In-memory EventRepository ─────────────────────────────────────────────

    private class InMemoryEventStore : EventRepository {
        private val ledger: MutableMap<String, MutableList<Event>> = mutableMapOf()

        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            ledger.getOrPut(projectId) { mutableListOf() }
                .add(Event(type, payload))
        }

        override fun loadEvents(projectId: String): List<Event> =
            ledger[projectId] ?: emptyList()
    }

    // ── Test fixtures ─────────────────────────────────────────────────────────

    private fun verifiedProfile(
        id:           String = "contractor-a",
        capScore:     Int    = 12,
        reliability:  Double = 0.9
    ): ContractorProfile {
        // High-score profile (all dimensions at 3 except driftScore at 0 = minimum drift)
        val cap = ContractorCapabilityVector(
            constraintObedience = 3,
            structuralAccuracy  = 3,
            driftScore          = 0,
            complexityCapacity  = 3,
            reliability         = 3
        )
        return ContractorProfile(
            id                = id,
            capabilities      = cap,
            verificationCount = 1,
            successCount      = 9,
            failureCount      = 1,
            status            = VerificationStatus.VERIFIED,
            source            = "test"
        )
    }

    private fun simpleTask(
        taskId:      String = "contract-1-step1",
        contractRef: String = "contract-1",
        constraints: List<String> = emptyList()
    ) = Task(
        taskId               = taskId,
        contractReference    = contractRef,
        stepReference        = 1,
        module               = "CORE",
        description          = "Test task",
        requiredCapabilities = mapOf("reliability" to 2),
        constraints          = constraints,
        expectedOutput       = "Test output",
        validationRules      = listOf("output must be non-empty"),
        assignedContractorId = null,
        assignmentStatus     = TaskAssignmentStatus.BLOCKED
    )

    private lateinit var store: InMemoryEventStore
    private lateinit var registry: ContractorRegistry

    @Before
    fun setUp() {
        store    = InMemoryEventStore()
        registry = ContractorRegistry()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TaskLifecycleManager
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `lifecycle - TASK_READY can transition to TASK_ASSIGNED only`() {
        val mgr = TaskLifecycleManager()
        assertTrue(mgr.canTransition(TaskLifecycleState.TASK_READY, TaskLifecycleState.TASK_ASSIGNED))
        assertFalse(mgr.canTransition(TaskLifecycleState.TASK_READY, TaskLifecycleState.TASK_STARTED))
        assertFalse(mgr.canTransition(TaskLifecycleState.TASK_READY, TaskLifecycleState.TASK_VALIDATED))
    }

    @Test
    fun `lifecycle - TASK_COMPLETED can transition to VALIDATED or FAILED`() {
        val mgr = TaskLifecycleManager()
        assertTrue(mgr.canTransition(TaskLifecycleState.TASK_COMPLETED, TaskLifecycleState.TASK_VALIDATED))
        assertTrue(mgr.canTransition(TaskLifecycleState.TASK_COMPLETED, TaskLifecycleState.TASK_FAILED))
        assertFalse(mgr.canTransition(TaskLifecycleState.TASK_COMPLETED, TaskLifecycleState.TASK_READY))
    }

    @Test
    fun `lifecycle - TASK_VALIDATED is terminal`() {
        val mgr = TaskLifecycleManager()
        assertTrue(mgr.isTerminal(TaskLifecycleState.TASK_VALIDATED))
    }

    @Test
    fun `lifecycle - TASK_FAILED is terminal`() {
        val mgr = TaskLifecycleManager()
        assertTrue(mgr.isTerminal(TaskLifecycleState.TASK_FAILED))
    }

    @Test
    fun `lifecycle - TASK_READY is not terminal`() {
        val mgr = TaskLifecycleManager()
        assertFalse(mgr.isTerminal(TaskLifecycleState.TASK_READY))
    }

    @Test(expected = IllegalStateException::class)
    fun `lifecycle - invalid transition throws`() {
        TaskLifecycleManager().transition(
            TaskLifecycleState.TASK_VALIDATED,
            TaskLifecycleState.TASK_READY
        )
    }

    @Test
    fun `lifecycle - valid transition returns new state`() {
        val state = TaskLifecycleManager().transition(
            TaskLifecycleState.TASK_READY,
            TaskLifecycleState.TASK_ASSIGNED
        )
        assertEquals(TaskLifecycleState.TASK_ASSIGNED, state)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ContractorExecutor
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `executor - success produces non-empty artifact with taskId`() {
        val ex = ContractorExecutor()
        val input = ContractorExecutionInput(
            taskId               = "task-1",
            taskDescription      = "Do something",
            taskPayload          = mapOf("module" to "CORE"),
            contractConstraints  = listOf("no-mutation"),
            expectedOutputSchema = "structured artifact"
        )
        val output = ex.execute(input, verifiedProfile())
        assertEquals(ExecutionStatus.SUCCESS, output.status)
        assertNull(output.error)
        assertEquals("task-1", output.resultArtifact["taskId"])
    }

    @Test
    fun `executor - zero capability score produces failure`() {
        val ex = ContractorExecutor()
        val zeroCap = ContractorProfile(
            id           = "zero",
            capabilities = ContractorCapabilityVector(
                constraintObedience = 0,
                structuralAccuracy  = 0,
                driftScore          = 3,
                complexityCapacity  = 0,
                reliability         = 0
            ),
            status = VerificationStatus.VERIFIED
        )
        val input = ContractorExecutionInput(
            taskId               = "task-z",
            taskDescription      = "test",
            taskPayload          = emptyMap(),
            contractConstraints  = emptyList(),
            expectedOutputSchema = "any"
        )
        val output = ex.execute(input, zeroCap)
        assertEquals(ExecutionStatus.FAILURE, output.status)
        assertNotNull(output.error)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ResultValidator
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `validator - VALIDATED when all rules pass`() {
        val task = simpleTask(taskId = "task-1")
        val ex   = ContractorExecutor()
        val input = ContractorExecutionInput(
            taskId               = "task-1",
            taskDescription      = task.description,
            taskPayload          = mapOf("module" to task.module),
            contractConstraints  = task.constraints,
            expectedOutputSchema = task.expectedOutput
        )
        val output = ex.execute(input, verifiedProfile())
        val result = ResultValidator().validate(task, output)
        assertEquals(ValidationVerdict.VALIDATED, result.verdict)
        assertTrue(result.failureReasons.isEmpty())
    }

    @Test
    fun `validator - FAILED when taskId mismatch`() {
        val task   = simpleTask(taskId = "task-1")
        val output = com.agoii.mobile.execution.ContractorExecutionOutput(
            taskId         = "task-WRONG",
            resultArtifact = mapOf("taskId" to "task-WRONG"),
            status         = ExecutionStatus.SUCCESS
        )
        val result = ResultValidator().validate(task, output)
        assertEquals(ValidationVerdict.FAILED, result.verdict)
        assertFalse(result.failureReasons.isEmpty())
    }

    @Test
    fun `validator - FAILED when execution status is FAILURE`() {
        val task   = simpleTask(taskId = "task-1")
        val output = com.agoii.mobile.execution.ContractorExecutionOutput(
            taskId         = "task-1",
            resultArtifact = mapOf("taskId" to "task-1"),
            status         = ExecutionStatus.FAILURE,
            error          = "crashed"
        )
        val result = ResultValidator().validate(task, output)
        assertEquals(ValidationVerdict.FAILED, result.verdict)
    }

    @Test
    fun `validator - FAILED when constraint missing from artifact`() {
        val task   = simpleTask(taskId = "task-1", constraints = listOf("no-mutation"))
        val output = com.agoii.mobile.execution.ContractorExecutionOutput(
            taskId         = "task-1",
            resultArtifact = mapOf(
                "taskId"         to "task-1",
                "constraintsMet" to listOf<String>()   // empty — no-mutation not present
            ),
            status         = ExecutionStatus.SUCCESS
        )
        val result = ResultValidator().validate(task, output)
        assertEquals(ValidationVerdict.FAILED, result.verdict)
        assertTrue(result.failureReasons.any { it.contains("no-mutation") })
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RetryEngine
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `retry - first failure returns RETRY with same contractor`() {
        val engine     = RetryEngine(registry)
        val contractor = verifiedProfile()
        val task       = simpleTask()
        val outcome    = engine.evaluate(task, contractor, attemptCount = 1)
        assertEquals(RetryDecision.RETRY, outcome.decision)
        assertEquals(contractor.id, outcome.assignedContractor?.id)
    }

    @Test
    fun `retry - at maxRetries with no replacement returns ESCALATE`() {
        val engine     = RetryEngine(registry, maxRetries = 3)
        val contractor = verifiedProfile()
        val task       = simpleTask()
        // attemptCount == maxRetries means we've hit the limit; registry is empty
        val outcome    = engine.evaluate(task, contractor, attemptCount = 3)
        assertEquals(RetryDecision.ESCALATE, outcome.decision)
        assertNull(outcome.assignedContractor)
    }

    @Test
    fun `retry - REASSIGN when replacement contractor exists in registry`() {
        val primary     = verifiedProfile(id = "primary")
        val replacement = verifiedProfile(id = "replacement")
        registry.registerVerified(primary)
        registry.registerVerified(replacement)

        val engine  = RetryEngine(registry, maxRetries = 3)
        val task    = simpleTask()
        // Attempt at maxRetries triggers reassignment lookup
        val outcome = engine.evaluate(task, primary, attemptCount = 3)
        assertEquals(RetryDecision.REASSIGN, outcome.decision)
        assertEquals("replacement", outcome.assignedContractor?.id)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ExecutionEventEmitter
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `emitter - taskAssigned appends correct event type`() {
        val emitter = ExecutionEventEmitter(store)
        emitter.taskAssigned("proj-1", "task-1", "contractor-a")
        val events = store.loadEvents("proj-1")
        assertEquals(1, events.size)
        assertEquals(EventTypes.TASK_ASSIGNED, events[0].type)
        assertEquals("task-1", events[0].payload["taskId"])
        assertEquals("contractor-a", events[0].payload["contractorId"])
    }

    @Test
    fun `emitter - taskValidated appends correct event type`() {
        val emitter = ExecutionEventEmitter(store)
        emitter.taskValidated("proj-1", "task-1")
        val events = store.loadEvents("proj-1")
        assertEquals(EventTypes.TASK_VALIDATED, events[0].type)
    }

    @Test
    fun `emitter - taskFailed appends correct event type with reason`() {
        val emitter = ExecutionEventEmitter(store)
        emitter.taskFailed("proj-1", "task-1", "contractor-a", "something broke")
        val events = store.loadEvents("proj-1")
        assertEquals(EventTypes.TASK_FAILED, events[0].type)
        assertEquals("something broke", events[0].payload["reason"])
    }

    @Test
    fun `emitter - contractorReassigned appends correct event with both contractor ids`() {
        val emitter = ExecutionEventEmitter(store)
        emitter.contractorReassigned("proj-1", "task-1", "old-c", "new-c")
        val events = store.loadEvents("proj-1")
        assertEquals(EventTypes.CONTRACTOR_REASSIGNED, events[0].type)
        assertEquals("old-c", events[0].payload["previousContractorId"])
        assertEquals("new-c", events[0].payload["newContractorId"])
    }

    @Test
    fun `emitter - contractFailed appends correct event type`() {
        val emitter = ExecutionEventEmitter(store)
        emitter.contractFailed("proj-1", "task-1", "all retries exhausted")
        val events = store.loadEvents("proj-1")
        assertEquals(EventTypes.CONTRACT_FAILED, events[0].type)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ExecutionOrchestrator — happy-path lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildOrchestrator(): ExecutionOrchestrator {
        val emitter = ExecutionEventEmitter(store)
        return ExecutionOrchestrator(
            eventStore  = store,
            registry    = registry,
            emitter     = emitter
        )
    }

    private fun taskGraphWith(vararg tasks: Task) =
        TaskGraph(contractReference = "contract-1", tasks = tasks.toList())

    @Test
    fun `orchestrator - NO_TASKS when graph is empty`() {
        val orch = buildOrchestrator()
        val result = orch.step("proj", taskGraphWith())
        assertEquals(com.agoii.mobile.execution.ExecutionStepResult.NO_TASKS, result)
    }

    @Test
    fun `orchestrator - WAITING when no contractor available`() {
        val orch = buildOrchestrator()
        val task = simpleTask(taskId = "task-1")
        val result = orch.step("proj", taskGraphWith(task))
        assertEquals(com.agoii.mobile.execution.ExecutionStepResult.WAITING, result)
    }

    @Test
    fun `orchestrator - full happy-path lifecycle produces validated terminal state`() {
        val contractor = verifiedProfile()
        registry.registerVerified(contractor)

        val orch = buildOrchestrator()
        val task = simpleTask(taskId = "task-1")
        val graph = taskGraphWith(task)

        // Step 1: TASK_READY → TASK_ASSIGNED
        var result = orch.step("proj", graph)
        assertEquals(com.agoii.mobile.execution.ExecutionStepResult.ADVANCED, result)

        // Step 2: TASK_ASSIGNED → TASK_STARTED
        result = orch.step("proj", graph)
        assertEquals(com.agoii.mobile.execution.ExecutionStepResult.ADVANCED, result)

        // Step 3: TASK_STARTED → TASK_COMPLETED
        result = orch.step("proj", graph)
        assertEquals(com.agoii.mobile.execution.ExecutionStepResult.ADVANCED, result)

        // Step 4: TASK_COMPLETED → TASK_VALIDATED (terminal)
        result = orch.step("proj", graph)
        assertEquals(com.agoii.mobile.execution.ExecutionStepResult.COMPLETED, result)

        // Verify event sequence in the ledger.
        val events = store.loadEvents("proj").filter { it.payload["taskId"] == "task-1" }
        val types  = events.map { it.type }
        assertTrue(types.contains(EventTypes.TASK_ASSIGNED))
        assertTrue(types.contains(EventTypes.TASK_STARTED))
        assertTrue(types.contains(EventTypes.TASK_COMPLETED))
        assertTrue(types.contains(EventTypes.TASK_VALIDATED))
    }

    @Test
    fun `orchestrator - emits events for each step (no silent transitions)`() {
        val contractor = verifiedProfile()
        registry.registerVerified(contractor)

        val orch  = buildOrchestrator()
        val task  = simpleTask(taskId = "task-2")
        val graph = taskGraphWith(task)

        // Drive until completed.
        repeat(10) { orch.step("proj2", graph) }

        val events = store.loadEvents("proj2")
        // Every meaningful transition must emit at least one event.
        assertTrue("Expected at least 4 events", events.size >= 4)
    }

    @Test
    fun `orchestrator - deterministic same event stream same outcome`() {
        val contractor = verifiedProfile()

        // Run once.
        val store1    = InMemoryEventStore()
        val registry1 = ContractorRegistry().also { it.registerVerified(contractor) }
        val orch1     = ExecutionOrchestrator(
            eventStore = store1, registry = registry1,
            emitter    = ExecutionEventEmitter(store1)
        )
        val graph = taskGraphWith(simpleTask(taskId = "task-det"))
        repeat(10) { orch1.step("proj-det", graph) }

        // Run again with fresh store and registry.
        val store2    = InMemoryEventStore()
        val registry2 = ContractorRegistry().also { it.registerVerified(contractor) }
        val orch2     = ExecutionOrchestrator(
            eventStore = store2, registry = registry2,
            emitter    = ExecutionEventEmitter(store2)
        )
        repeat(10) { orch2.step("proj-det", graph) }

        val types1 = store1.loadEvents("proj-det").map { it.type }
        val types2 = store2.loadEvents("proj-det").map { it.type }
        assertEquals("Determinism violated: event sequences differ", types1, types2)
    }

    @Test
    fun `orchestrator - second step when already completed returns COMPLETED immediately`() {
        val contractor = verifiedProfile()
        registry.registerVerified(contractor)

        val orch  = buildOrchestrator()
        val task  = simpleTask(taskId = "task-done")
        val graph = taskGraphWith(task)

        // Run to completion.
        repeat(10) { orch.step("proj-done", graph) }

        // One more step — should still return COMPLETED, not advance.
        val result = orch.step("proj-done", graph)
        assertEquals(com.agoii.mobile.execution.ExecutionStepResult.COMPLETED, result)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EventTypes — new constants present
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `EventTypes ALL includes all execution lifecycle constants`() {
        assertTrue(EventTypes.ALL.contains(EventTypes.TASK_ASSIGNED))
        assertTrue(EventTypes.ALL.contains(EventTypes.TASK_STARTED))
        assertTrue(EventTypes.ALL.contains(EventTypes.TASK_COMPLETED))
        assertTrue(EventTypes.ALL.contains(EventTypes.TASK_VALIDATED))
        assertTrue(EventTypes.ALL.contains(EventTypes.TASK_FAILED))
        assertTrue(EventTypes.ALL.contains(EventTypes.CONTRACTOR_REASSIGNED))
        assertTrue(EventTypes.ALL.contains(EventTypes.CONTRACT_FAILED))
    }
}
