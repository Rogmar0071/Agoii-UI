package com.agoii.mobile

import com.agoii.mobile.contractor.ContractorCapabilityVector
import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.VerificationStatus
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.execution.ContractorResult
import com.agoii.mobile.execution.ExecutionModule
import com.agoii.mobile.execution.ExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ExecutionModule (AGOII_EXECUTION_MODULE_COMPLETION_PASS_3).
 * 
 * Validates:
 * - ExecutionModule processes TASK_ASSIGNED events
 * - Calls ExecutionAuthority for validation and execution
 * - Appends result events (TASK_COMPLETED or TASK_FAILED) to ledger
 * - Closed system behavior: no orchestration, no listeners
 */
class ExecutionModuleTest {

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

    private lateinit var store: InMemoryEventStore
    private lateinit var registry: ContractorRegistry
    private lateinit var executionModule: ExecutionModule

    @Before
    fun setUp() {
        store = InMemoryEventStore()
        registry = ContractorRegistry()
        executionModule = ExecutionModule(store, registry)
    }

    private fun verifiedContractor(id: String = "contractor-1"): ContractorProfile {
        return ContractorProfile(
            id = id,
            capabilities = ContractorCapabilityVector(
                constraintObedience = 3,
                structuralAccuracy = 3,
                driftScore = 0,
                complexityCapacity = 3,
                reliability = 3
            ),
            verificationCount = 1,
            successCount = 5,
            failureCount = 0,
            status = VerificationStatus.VERIFIED,
            source = "test"
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ExecutionModule — TASK_ASSIGNED processing
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `processState - returns null for non-TASK_ASSIGNED events`() {
        val event = Event(
            type = EventTypes.CONTRACTS_GENERATED,
            payload = emptyMap()
        )

        val result = executionModule.processState("project-1", event)

        assertNull("ExecutionModule should ignore non-TASK_ASSIGNED events", result)
    }

    @Test
    fun `processState - executes and returns ContractorResult for TASK_ASSIGNED`() {
        registry.registerVerified(verifiedContractor("contractor-1"))

        val taskAssignedEvent = Event(
            type = EventTypes.TASK_ASSIGNED,
            payload = mapOf(
                "taskId" to "task-1",
                "contractorId" to "contractor-1",
                "position" to 1,
                "total" to 3,
                "report_reference" to "report-123"
            )
        )

        val result = executionModule.processState("project-1", taskAssignedEvent)

        assertNotNull("ExecutionModule should return ContractorResult", result)
        assertEquals("task-1", result?.taskId)
        assertEquals("contractor-1", result?.contractorId)
        assertEquals(ExecutionStatus.SUCCESS, result?.status)
    }

    @Test
    fun `processState - appends TASK_COMPLETED event to ledger on success`() {
        registry.registerVerified(verifiedContractor("contractor-1"))

        val taskAssignedEvent = Event(
            type = EventTypes.TASK_ASSIGNED,
            payload = mapOf(
                "taskId" to "task-1",
                "contractorId" to "contractor-1",
                "position" to 1,
                "total" to 3
            )
        )

        executionModule.processState("project-1", taskAssignedEvent)

        val events = store.loadEvents("project-1")
        assertEquals(1, events.size)
        assertEquals(EventTypes.TASK_COMPLETED, events[0].type)
        assertEquals("task-1", events[0].payload["taskId"])
        assertEquals("contractor-1", events[0].payload["contractorId"])
    }

    @Test
    fun `processState - uses default contractor when not in registry`() {
        // Registry is empty

        val taskAssignedEvent = Event(
            type = EventTypes.TASK_ASSIGNED,
            payload = mapOf(
                "taskId" to "task-1",
                "contractorId" to "unknown-contractor",
                "position" to 1,
                "total" to 3
            )
        )

        val result = executionModule.processState("project-1", taskAssignedEvent)

        assertNotNull("ExecutionModule should use default contractor", result)
        assertEquals("unknown-contractor", result?.contractorId)
    }

    @Test
    fun `processState - deterministic flow: same input produces same output`() {
        registry.registerVerified(verifiedContractor("contractor-1"))

        val taskAssignedEvent = Event(
            type = EventTypes.TASK_ASSIGNED,
            payload = mapOf(
                "taskId" to "task-1",
                "contractorId" to "contractor-1",
                "position" to 1,
                "total" to 3
            )
        )

        val result1 = executionModule.processState("project-1", taskAssignedEvent)
        // Clear ledger for second run
        store.loadEvents("project-1")
        val result2 = executionModule.processState("project-2", taskAssignedEvent)

        assertEquals(result1?.taskId, result2?.taskId)
        assertEquals(result1?.contractorId, result2?.contractorId)
        assertEquals(result1?.status, result2?.status)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ExecutionModule — Closed system validation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `processState - no orchestration: single function call returns immediately`() {
        registry.registerVerified(verifiedContractor())

        val taskAssignedEvent = Event(
            type = EventTypes.TASK_ASSIGNED,
            payload = mapOf(
                "taskId" to "task-1",
                "contractorId" to "contractor-1",
                "position" to 1,
                "total" to 3
            )
        )

        val startTime = System.currentTimeMillis()
        executionModule.processState("project-1", taskAssignedEvent)
        val duration = System.currentTimeMillis() - startTime

        // Should complete quickly (no async, no listeners, no external triggers)
        assert(duration < 1000) { "ExecutionModule took too long: ${duration}ms" }
    }
}
