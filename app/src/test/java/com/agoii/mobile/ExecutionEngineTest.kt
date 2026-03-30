package com.agoii.mobile

import com.agoii.mobile.contractor.ContractorCapabilityVector
import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.VerificationStatus
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.execution.ContractorExecutionOutput
import com.agoii.mobile.execution.ExecutionStatus
import com.agoii.mobile.execution.ResultValidator
import com.agoii.mobile.execution.RetryDecision
import com.agoii.mobile.execution.RetryEngine
import com.agoii.mobile.execution.TaskLifecycleManager
import com.agoii.mobile.execution.TaskLifecycleState
import com.agoii.mobile.execution.ValidationVerdict
import com.agoii.mobile.tasks.Task
import com.agoii.mobile.tasks.TaskAssignmentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AGOII-EXECUTION-ENGINE-01.
 *
 * Covers:
 *  - TaskLifecycleManager  (valid transitions, terminal states)
 *  - ResultValidator       (VALIDATED / FAILED verdicts, rule evaluation)
 *  - RetryEngine           (RETRY → REASSIGN → ESCALATE chain)
 *
 * All tests run on the JVM without an Android device.
 *
 * GOVERNANCE: ExecutionAuthority is the ONLY execution entry point (AGOII-RCF-EXECUTION-AUTHORITY-LOCK-01).
 * ContractorExecutor and ExecutionOrchestrator MUST NOT be used directly in tests.
 */
class ExecutionEngineTest {

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

    private lateinit var registry: ContractorRegistry

    @Before
    fun setUp() {
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
    // ResultValidator
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `validator - VALIDATED when all rules pass`() {
        val task   = simpleTask(taskId = "task-1")
        val output = ContractorExecutionOutput(
            taskId         = "task-1",
            resultArtifact = mapOf("taskId" to "task-1"),
            status         = ExecutionStatus.SUCCESS
        )
        val result = ResultValidator().validate(task, output)
        assertEquals(ValidationVerdict.VALIDATED, result.verdict)
        assertTrue(result.failureReasons.isEmpty())
    }

    @Test
    fun `validator - FAILED when taskId mismatch`() {
        val task   = simpleTask(taskId = "task-1")
        val output = ContractorExecutionOutput(
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
        val output = ContractorExecutionOutput(
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
        val output = ContractorExecutionOutput(
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
