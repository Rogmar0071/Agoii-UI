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
import com.agoii.mobile.execution.ExecutionOrchestrator
import com.agoii.mobile.execution.ExecutionResult
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
    // ExecutionOrchestrator — pure worker (no events, no lifecycle state)
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildOrchestrator(): ExecutionOrchestrator = ExecutionOrchestrator()

    private fun taskGraphWith(vararg tasks: Task) =
        TaskGraph(contractReference = "contract-1", tasks = tasks.toList())

    @Test
    fun `orchestrator - execute returns success for capable contractor`() {
        val contractor = verifiedProfile()
        val orch       = buildOrchestrator()
        val task       = simpleTask(taskId = "task-1")

        val result = orch.execute(task, contractor)

        assertTrue("Expected execution success", result.success)
        assertFalse("Expected non-empty artifact", result.artifact.isEmpty())
        assertEquals("Expected VALIDATED verdict",
            ValidationVerdict.VALIDATED, result.validationResult.verdict)
        assertNull("Expected no error on success", result.error)
    }

    @Test
    fun `orchestrator - execute returns failure for zero-capability contractor`() {
        val zeroCapContractor = ContractorProfile(
            id            = "zero-cap",
            capabilities  = ContractorCapabilityVector(0, 0, 0, 0, 0),
            status        = VerificationStatus.VERIFIED,
            source        = "test"
        )
        val orch   = buildOrchestrator()
        val task   = simpleTask(taskId = "task-zero")

        val result = orch.execute(task, zeroCapContractor)

        assertFalse("Expected execution failure", result.success)
        assertNotNull("Expected error message on failure", result.error)
    }

    @Test
    fun `orchestrator - execute is deterministic (same input same result)`() {
        val contractor = verifiedProfile()
        val orch       = buildOrchestrator()
        val task       = simpleTask(taskId = "task-det")

        val result1 = orch.execute(task, contractor)
        val result2 = orch.execute(task, contractor)

        assertEquals("Determinism violated: success differs",
            result1.success, result2.success)
        assertEquals("Determinism violated: artifact differs",
            result1.artifact, result2.artifact)
        assertEquals("Determinism violated: verdict differs",
            result1.validationResult.verdict, result2.validationResult.verdict)
    }

    @Test
    fun `orchestrator - execute produces artifact with task id`() {
        val contractor = verifiedProfile()
        val orch       = buildOrchestrator()
        val task       = simpleTask(taskId = "task-artifact")

        val result = orch.execute(task, contractor)

        assertTrue("Artifact must contain taskId", result.artifact.containsKey("taskId"))
        assertEquals("Artifact taskId must match task", "task-artifact", result.artifact["taskId"])
    }

    @Test
    fun `orchestrator - no events emitted during execute (pure worker)`() {
        val contractor = verifiedProfile()
        val orch       = buildOrchestrator()
        val task       = simpleTask(taskId = "task-silent")

        // Execute — no event store interaction expected
        orch.execute(task, contractor)

        // The shared store must remain empty since orchestrator does not emit events
        val events = store.loadEvents("proj")
        assertEquals("ExecutionOrchestrator must not emit any events", 0, events.size)
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
