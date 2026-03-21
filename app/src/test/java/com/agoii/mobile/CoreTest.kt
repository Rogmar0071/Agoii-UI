package com.agoii.mobile

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.Governor
import com.agoii.mobile.core.LedgerAudit
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the core layer (no Android framework required).
 *
 * Tests use [Replay.deriveState] and the audit/replay classes directly
 * with an in-memory [EventRepository] so they run on the JVM without a device.
 *
 * Canonical contract lifecycle (per Agoii Master governance):
 *   execution_started
 *     → contract_started  (contract 1)
 *     → contract_completed (contract 1)
 *     → contract_started  (contract 2)
 *     → contract_completed (contract 2)
 *     → contract_started  (contract 3)
 *     → contract_completed (contract 3)
 *     → assembly_completed
 */
class CoreTest {

    // ── Replay tests ─────────────────────────────────────────────────────────

    @Test
    fun `replay on empty ledger returns idle phase`() {
        val state = Replay(store()).deriveState(emptyList())
        assertEquals("idle", state.phase)
        assertEquals(0, state.contractsCompleted)
        assertEquals(0, state.totalContracts)
        assertFalse(state.executionStarted)
        assertFalse(state.executionCompleted)
        assertNull(state.objective)
    }

    @Test
    fun `replay derives objective from intent_submitted`() {
        val events = listOf(Event("intent_submitted", mapOf("objective" to "Build the thing")))
        val state  = Replay(store()).deriveState(events)
        assertEquals("intent_submitted", state.phase)
        assertEquals("Build the thing", state.objective)
    }

    @Test
    fun `replay derives total_contracts from contracts_generated`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0))
        )
        val state = Replay(store()).deriveState(events)
        assertEquals("contracts_generated", state.phase)
        assertEquals(3, state.totalContracts)
        assertEquals(0, state.contractsCompleted)
    }

    @Test
    fun `replay counts only contract_completed events toward contractsCompleted`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0)),
            Event("contract_started",    mapOf("contract_index" to 0, "contract_id" to "contract_1")),
            Event("contract_completed",  mapOf("contract_index" to 0, "contract_id" to "contract_1")),
            Event("contract_started",    mapOf("contract_index" to 1, "contract_id" to "contract_2"))
        )
        val state = Replay(store()).deriveState(events)
        assertEquals(EventTypes.CONTRACT_STARTED, state.phase)
        // Only the one contract_completed event counts
        assertEquals(1, state.contractsCompleted)
        assertTrue(state.executionStarted)
        assertFalse(state.executionCompleted)
    }

    @Test
    fun `replay marks execution completed on assembly_completed`() {
        val state = Replay(store()).deriveState(buildFullLedger())
        assertEquals("assembly_completed", state.phase)
        assertEquals(3, state.contractsCompleted)
        assertEquals(3, state.totalContracts)
        assertTrue(state.executionStarted)
        assertTrue(state.executionCompleted)
    }

    // ── LedgerAudit tests ────────────────────────────────────────────────────

    @Test
    fun `audit passes for empty ledger`() {
        val result = LedgerAudit(store()).auditLedger("proj")
        assertTrue(result.valid)
        assertEquals(0, result.checkedEvents)
    }

    @Test
    fun `audit passes for complete valid ledger`() {
        val result = LedgerAudit(store(buildFullLedger())).auditLedger("proj")
        assertTrue("Unexpected errors: ${result.errors}", result.valid)
        // 1 intent + 1 generated + 1 ready + 1 approved + 1 started
        // + 3*(contract_started + contract_completed) + 1 assembly = 12
        assertEquals(12, result.checkedEvents)
    }

    @Test
    fun `audit fails when first event is not intent_submitted`() {
        val result = LedgerAudit(store(listOf(Event("contracts_generated", emptyMap())))).auditLedger("proj")
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("intent_submitted") })
    }

    @Test
    fun `audit fails on illegal transition`() {
        val events = listOf(
            Event("intent_submitted",   mapOf("objective" to "obj")),
            Event("assembly_completed", emptyMap())
        )
        val result = LedgerAudit(store(events)).auditLedger("proj")
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("Illegal transition") })
    }

    @Test
    fun `audit fails on unknown event type`() {
        val events = listOf(
            Event("intent_submitted", emptyMap()),
            Event("magic_event",      emptyMap())
        )
        val result = LedgerAudit(store(events)).auditLedger("proj")
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("Unknown event type") })
    }

    @Test
    fun `audit rejects contract_executed as unknown event type`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0)),
            Event("contract_executed",   mapOf("contract_index" to 0.0))
        )
        val result = LedgerAudit(store(events)).auditLedger("proj")
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("Unknown event type") && it.contains("contract_executed") })
    }

    @Test
    fun `audit enforces contract_started before contract_completed`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0)),
            // Skipping contract_started — illegal
            Event("contract_completed",  mapOf("contract_index" to 0))
        )
        val result = LedgerAudit(store(events)).auditLedger("proj")
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("Illegal transition") })
    }

    // ── ReplayTest (invariant) tests ─────────────────────────────────────────

    @Test
    fun `verify_replay is valid for complete ledger`() {
        val result = ReplayTest(store(buildFullLedger())).verifyReplay("proj")
        assertTrue("Errors: ${result.invariantErrors} | Audit: ${result.auditResult.errors}", result.valid)
    }

    @Test
    fun `verify_replay detects illegal transition when jumping to assembly early`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0)),
            Event("contract_started",    mapOf("contract_index" to 0, "contract_id" to "contract_1")),
            Event("contract_completed",  mapOf("contract_index" to 0, "contract_id" to "contract_1")),
            // Jump to assembly after only 1 of 3 contracts — illegal
            Event("assembly_completed",  mapOf("contracts_completed" to 3))
        )
        val result = ReplayTest(store(events)).verifyReplay("proj")
        assertFalse(result.valid)
    }

    // ── Governor tests ────────────────────────────────────────────────────────

    @Test
    fun `governor returns NO_EVENT on empty ledger`() {
        assertEquals(Governor.GovernorResult.NO_EVENT, Governor(store()).runGovernor("proj"))
    }

    @Test
    fun `governor advances from intent_submitted to contracts_generated`() {
        val s = store(listOf(Event("intent_submitted", mapOf("objective" to "obj"))))
        assertEquals(Governor.GovernorResult.ADVANCED, Governor(s).runGovernor("proj"))
        assertEquals("contracts_generated", s.loadEvents("proj").last().type)
    }

    @Test
    fun `governor waits for approval when last event is contracts_ready`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap())
        )
        val s = store(events)
        assertEquals(Governor.GovernorResult.WAITING_FOR_APPROVAL, Governor(s).runGovernor("proj"))
        // Ledger must NOT be modified
        assertEquals(3, s.loadEvents("proj").size)
    }

    @Test
    fun `governor starts first contract after execution_started`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0))
        )
        val s = store(events)
        assertEquals(Governor.GovernorResult.ADVANCED, Governor(s).runGovernor("proj"))
        assertEquals(EventTypes.CONTRACT_STARTED, s.loadEvents("proj").last().type)
    }

    @Test
    fun `governor completes open contract after contract_started`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0)),
            Event("contract_started",    mapOf("contract_index" to 0, "contract_id" to "contract_1"))
        )
        val s = store(events)
        assertEquals(Governor.GovernorResult.ADVANCED, Governor(s).runGovernor("proj"))
        assertEquals(EventTypes.CONTRACT_COMPLETED, s.loadEvents("proj").last().type)
    }

    @Test
    fun `governor executes all contracts step by step then assembles`() {
        val initial = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0))
        )
        val s   = store(initial)
        val gov = Governor(s)

        // 3 contracts × 2 steps (started + completed) = 6 ADVANCED, then 1 COMPLETED
        repeat(6) { step ->
            assertEquals(
                "Expected ADVANCED at step $step",
                Governor.GovernorResult.ADVANCED, gov.runGovernor("proj")
            )
        }
        assertEquals(Governor.GovernorResult.COMPLETED, gov.runGovernor("proj"))

        val events = s.loadEvents("proj")
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, events.last().type)
        assertEquals(3, events.count { it.type == EventTypes.CONTRACT_STARTED })
        assertEquals(3, events.count { it.type == EventTypes.CONTRACT_COMPLETED })
        // Must not contain the removed event type
        assertEquals(0, events.count { it.type == "contract_executed" })
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Canonical 12-event full ledger for a completed 3-contract project.
     *
     * Sequence:
     *   intent_submitted, contracts_generated, contracts_ready, contracts_approved,
     *   execution_started,
     *   contract_started (1), contract_completed (1),
     *   contract_started (2), contract_completed (2),
     *   contract_started (3), contract_completed (3),
     *   assembly_completed
     */
    private fun buildFullLedger(): List<Event> = listOf(
        Event("intent_submitted",    mapOf("objective" to "Build the core")),
        Event("contracts_generated", mapOf("total" to 3.0, "source_intent" to "Build the core")),
        Event("contracts_ready",     mapOf("total_contracts" to 3.0)),
        Event("contracts_approved",  emptyMap()),
        Event("execution_started",   mapOf("total_contracts" to 3.0)),
        Event("contract_started",    mapOf("contract_index" to 0, "contract_id" to "contract_1")),
        Event("contract_completed",  mapOf("contract_index" to 0, "contract_id" to "contract_1")),
        Event("contract_started",    mapOf("contract_index" to 1, "contract_id" to "contract_2")),
        Event("contract_completed",  mapOf("contract_index" to 1, "contract_id" to "contract_2")),
        Event("contract_started",    mapOf("contract_index" to 2, "contract_id" to "contract_3")),
        Event("contract_completed",  mapOf("contract_index" to 2, "contract_id" to "contract_3")),
        Event("assembly_completed",  mapOf("contracts_completed" to 3))
    )

    private fun store(initial: List<Event> = emptyList()): EventRepository =
        InMemoryEventRepository(initial)
}

private class InMemoryEventRepository(initial: List<Event>) : EventRepository {
    private val ledger = initial.toMutableList()
    override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        ledger.add(Event(type, payload))
    }
    override fun loadEvents(projectId: String): List<Event> = ledger.toList()
}
