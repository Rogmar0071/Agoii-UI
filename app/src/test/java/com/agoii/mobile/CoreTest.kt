package com.agoii.mobile

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.Governor
import com.agoii.mobile.core.LedgerAudit
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayTest
import com.agoii.mobile.irs.ConsensusRule
import com.agoii.mobile.irs.EvidenceRef
import com.agoii.mobile.irs.GapDetector
import com.agoii.mobile.irs.IntentField
import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.IrsOrchestrator
import com.agoii.mobile.irs.IrsStage
import com.agoii.mobile.irs.OrchestratorResult
import com.agoii.mobile.irs.PCCVValidator
import com.agoii.mobile.irs.ReconstructionEngine
import com.agoii.mobile.irs.ScoutOrchestrator
import com.agoii.mobile.irs.SimulationEngine
import com.agoii.mobile.irs.SwarmConfig
import com.agoii.mobile.irs.SwarmValidator
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
 *     → contract_started  (position=1, total=N)
 *     → contract_completed (position=1, total=N)
 *     → contract_started  (position=2, total=N)
 *     → contract_completed (position=2, total=N)
 *     …
 *     → contract_completed (position=N, total=N)
 *     → execution_completed
 *     → assembly_started
 *     → assembly_validated
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
        assertFalse(state.assemblyStarted)
        assertFalse(state.assemblyValidated)
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
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event("contract_started",    mapOf("contract_id" to "contract_2", "position" to 2, "total" to 3))
        )
        val state = Replay(store()).deriveState(events)
        assertEquals(EventTypes.CONTRACT_STARTED, state.phase)
        // Only the one contract_completed event counts; contract_started does not
        assertEquals(1, state.contractsCompleted)
        assertTrue(state.executionStarted)
        assertFalse(state.executionCompleted)
        assertFalse(state.assemblyStarted)
        assertFalse(state.assemblyValidated)
    }

    @Test
    fun `replay sets executionCompleted on execution_completed event`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 2.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 2.0)),
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 2)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 2)),
            Event("contract_started",    mapOf("contract_id" to "contract_2", "position" to 2, "total" to 2)),
            Event("contract_completed",  mapOf("contract_id" to "contract_2", "position" to 2, "total" to 2)),
            Event("execution_completed", mapOf("contracts_completed" to 2))
        )
        val state = Replay(store()).deriveState(events)
        assertEquals(EventTypes.EXECUTION_COMPLETED, state.phase)
        assertEquals(2, state.contractsCompleted)
        assertTrue(state.executionStarted)
        assertTrue(state.executionCompleted)
        assertFalse(state.assemblyStarted)
        assertFalse(state.assemblyValidated)
    }

    @Test
    fun `replay tracks full assembly pipeline flags`() {
        val state = Replay(store()).deriveState(buildFullLedger())
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, state.phase)
        assertEquals(3, state.contractsCompleted)
        assertEquals(3, state.totalContracts)
        assertTrue(state.executionStarted)
        assertTrue(state.executionCompleted)
        assertTrue(state.assemblyStarted)
        assertTrue(state.assemblyValidated)
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
        // 5 pre-execution + 3*(contract_started+contract_completed)
        // + execution_completed + assembly_started + assembly_validated + assembly_completed = 15
        assertEquals(15, result.checkedEvents)
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
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3))
        )
        val result = LedgerAudit(store(events)).auditLedger("proj")
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("Illegal transition") })
    }

    @Test
    fun `audit rejects direct jump from contract_completed to assembly_completed`() {
        // contract_completed must now go through execution_completed → assembly_started → ...
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 1.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 1.0)),
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            // Skipping execution_completed → assembly_started → assembly_validated — illegal
            Event("assembly_completed",  emptyMap())
        )
        val result = LedgerAudit(store(events)).auditLedger("proj")
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("Illegal transition") })
    }

    @Test
    fun `audit rejects skipping assembly_validated`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 1.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 1.0)),
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event("execution_completed", mapOf("contracts_completed" to 1)),
            Event("assembly_started",    emptyMap()),
            // Skipping assembly_validated — illegal
            Event("assembly_completed",  emptyMap())
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
        // Jumps from contract_completed (1 of 3) directly to assembly_completed — illegal.
        // The audit catches this because contract_completed → assembly_completed is not a
        // legal transition (must flow through execution_completed first).
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0)),
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event("assembly_completed",  emptyMap())
        )
        val result = ReplayTest(store(events)).verifyReplay("proj")
        assertFalse(result.valid)
    }

    @Test
    fun `verify_replay detects execution_completed before all contracts done`() {
        // Manually crafted ledger with execution_completed but only 1 of 3 contracts done.
        // ReplayTest invariant 3 catches this.
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0)),
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            // execution_completed with only 1 of 3 contracts done
            Event("execution_completed", mapOf("contracts_completed" to 1)),
            Event("assembly_started",    emptyMap()),
            Event("assembly_validated",  emptyMap()),
            Event("assembly_completed",  emptyMap())
        )
        val result = ReplayTest(store(events)).verifyReplay("proj")
        // Replay invariant: execution_completed only if all contracts completed
        assertFalse(result.valid)
        assertTrue(result.invariantErrors.any { it.contains("execution_completed") })
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
        val last = s.loadEvents("proj").last()
        assertEquals(EventTypes.CONTRACT_STARTED, last.type)
        assertEquals(1, last.payload["position"])
        assertEquals(3, last.payload["total"])
    }

    @Test
    fun `governor completes open contract after contract_started`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0)),
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3))
        )
        val s = store(events)
        assertEquals(Governor.GovernorResult.ADVANCED, Governor(s).runGovernor("proj"))
        val last = s.loadEvents("proj").last()
        assertEquals(EventTypes.CONTRACT_COMPLETED, last.type)
        assertEquals(1, last.payload["position"])
        assertEquals(3, last.payload["total"])
    }

    @Test
    fun `governor advances to next contract after contract_completed when more remain`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0)),
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3))
        )
        val s = store(events)
        assertEquals(Governor.GovernorResult.ADVANCED, Governor(s).runGovernor("proj"))
        val last = s.loadEvents("proj").last()
        assertEquals(EventTypes.CONTRACT_STARTED, last.type)
        assertEquals(2, last.payload["position"])
        assertEquals(3, last.payload["total"])
    }

    @Test
    fun `governor emits execution_completed after last contract_completed`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 2.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 2.0)),
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 2)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 2)),
            Event("contract_started",    mapOf("contract_id" to "contract_2", "position" to 2, "total" to 2)),
            Event("contract_completed",  mapOf("contract_id" to "contract_2", "position" to 2, "total" to 2))
        )
        val s = store(events)
        assertEquals(Governor.GovernorResult.ADVANCED, Governor(s).runGovernor("proj"))
        assertEquals(EventTypes.EXECUTION_COMPLETED, s.loadEvents("proj").last().type)
    }

    @Test
    fun `governor drives full assembly pipeline after execution_completed`() {
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 1.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 1.0)),
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event("execution_completed", mapOf("contracts_completed" to 1))
        )
        val s   = store(events)
        val gov = Governor(s)

        assertEquals(Governor.GovernorResult.ADVANCED,  gov.runGovernor("proj"))
        assertEquals(EventTypes.ASSEMBLY_STARTED,   s.loadEvents("proj").last().type)

        assertEquals(Governor.GovernorResult.ADVANCED,  gov.runGovernor("proj"))
        assertEquals(EventTypes.ASSEMBLY_VALIDATED, s.loadEvents("proj").last().type)

        assertEquals(Governor.GovernorResult.ADVANCED,  gov.runGovernor("proj"))
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, s.loadEvents("proj").last().type)

        // Terminal — no further events appended
        assertEquals(Governor.GovernorResult.COMPLETED, gov.runGovernor("proj"))
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, s.loadEvents("proj").last().type)
    }

    @Test
    fun `governor executes all contracts step by step then full assembly pipeline`() {
        val initial = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0))
        )
        val s   = store(initial)
        val gov = Governor(s)

        // 3 contracts × 2 steps each = 6 ADVANCED
        // + execution_completed, assembly_started, assembly_validated = 3 ADVANCED
        // + assembly_completed (emitted via VALID_TRANSITIONS) = 1 ADVANCED
        // + terminal detection (ASSEMBLY_COMPLETED already last) = 1 COMPLETED
        repeat(10) { step ->
            assertEquals(
                "Expected ADVANCED at step $step",
                Governor.GovernorResult.ADVANCED, gov.runGovernor("proj")
            )
        }
        assertEquals(Governor.GovernorResult.COMPLETED, gov.runGovernor("proj"))

        val events = s.loadEvents("proj")
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, events.last().type)
        assertEquals(3,  events.count { it.type == EventTypes.CONTRACT_STARTED })
        assertEquals(3,  events.count { it.type == EventTypes.CONTRACT_COMPLETED })
        assertEquals(1,  events.count { it.type == EventTypes.EXECUTION_COMPLETED })
        assertEquals(1,  events.count { it.type == EventTypes.ASSEMBLY_STARTED })
        assertEquals(1,  events.count { it.type == EventTypes.ASSEMBLY_VALIDATED })
        // Must not contain the removed event type
        assertEquals(0,  events.count { it.type == "contract_executed" })
    }

    // ── Governor Lock Certification tests ────────────────────────────────────

    /**
     * LOCK STEP 1 — EVENT MODEL LOCK
     * Verifies EventTypes.ALL is frozen: exactly the 11 defined event types, no more, no less.
     */
    @Test
    fun `lock - EventTypes ALL is frozen with exactly the 11 locked event types`() {
        val locked = setOf(
            "intent_submitted",
            "contracts_generated",
            "contracts_ready",
            "contracts_approved",
            "execution_started",
            "contract_started",
            "contract_completed",
            "execution_completed",
            "assembly_started",
            "assembly_validated",
            "assembly_completed"
        )
        assertEquals("EventTypes.ALL must contain exactly 11 locked event types", 11, EventTypes.ALL.size)
        assertEquals("EventTypes.ALL must match the locked set exactly", locked, EventTypes.ALL)
    }

    /**
     * LOCK STEP 2 — TRANSITION LOCK
     * Verifies Governor.VALID_TRANSITIONS is frozen: exactly the 6 defined governor transitions.
     */
    @Test
    fun `lock - Governor VALID_TRANSITIONS is frozen with exactly the 6 locked entries`() {
        val locked = mapOf(
            "intent_submitted"    to "contracts_generated",
            "contracts_generated" to "contracts_ready",
            "contracts_approved"  to "execution_started",
            "execution_completed" to "assembly_started",
            "assembly_started"    to "assembly_validated",
            "assembly_validated"  to "assembly_completed"
        )
        assertEquals("VALID_TRANSITIONS must contain exactly 6 locked entries", 6, Governor.VALID_TRANSITIONS.size)
        assertEquals("VALID_TRANSITIONS must match the locked map exactly", locked, Governor.VALID_TRANSITIONS)
    }

    /**
     * LOCK STEP 7 — CANONICAL 2-CONTRACT FULL FLOW
     * Validates the exact 13-event sequence specified in the lock certification:
     *   intent_submitted → contracts_generated → contracts_ready → contracts_approved
     *   → execution_started
     *   → contract_started (1) → contract_completed (1)
     *   → contract_started (2) → contract_completed (2)
     *   → execution_completed → assembly_started → assembly_validated → assembly_completed
     * ASSERTS: exact event count, exact order, no duplicates, replay = VALID, audit = VALID.
     */
    @Test
    fun `lock - canonical 2-contract full flow passes audit and replay`() {
        val expectedOrder = listOf(
            "intent_submitted",
            "contracts_generated",
            "contracts_ready",
            "contracts_approved",
            "execution_started",
            "contract_started",
            "contract_completed",
            "contract_started",
            "contract_completed",
            "execution_completed",
            "assembly_started",
            "assembly_validated",
            "assembly_completed"
        )
        val ledger = listOf(
            Event("intent_submitted",    mapOf("objective" to "Build the lock")),
            Event("contracts_generated", mapOf("total" to 2.0, "source_intent" to "Build the lock")),
            Event("contracts_ready",     mapOf("total_contracts" to 2.0)),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 2.0)),
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 2)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 2)),
            Event("contract_started",    mapOf("contract_id" to "contract_2", "position" to 2, "total" to 2)),
            Event("contract_completed",  mapOf("contract_id" to "contract_2", "position" to 2, "total" to 2)),
            Event("execution_completed", mapOf("contracts_completed" to 2)),
            Event("assembly_started",    emptyMap()),
            Event("assembly_validated",  emptyMap()),
            Event("assembly_completed",  emptyMap())
        )

        // Exact event count
        assertEquals("Canonical 2-contract flow must have exactly 13 events", 13, ledger.size)

        // Exact order — no duplicates, no skips
        ledger.forEachIndexed { i, event ->
            assertEquals("Event[$i] type mismatch", expectedOrder[i], event.type)
        }

        val s = store(ledger)

        // Audit must pass — all transitions legal
        val audit = LedgerAudit(s).auditLedger("proj")
        assertTrue("Audit must be VALID. Errors: ${audit.errors}", audit.valid)
        assertEquals(13, audit.checkedEvents)

        // Replay must reconstruct correct state with zero inference
        val verification = ReplayTest(s).verifyReplay("proj")
        assertTrue(
            "ReplayTest must be VALID. Invariant errors: ${verification.invariantErrors} " +
                    "| Audit errors: ${verification.auditResult.errors}",
            verification.valid
        )
        val state = verification.replayState
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, state.phase)
        assertEquals(2, state.contractsCompleted)
        assertEquals(2, state.totalContracts)
        assertTrue(state.executionStarted)
        assertTrue(state.executionCompleted)
        assertTrue(state.assemblyStarted)
        assertTrue(state.assemblyValidated)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Canonical 15-event full ledger for a completed 3-contract project.
     *
     * Sequence:
     *   intent_submitted, contracts_generated, contracts_ready, contracts_approved,
     *   execution_started,
     *   contract_started (1), contract_completed (1),
     *   contract_started (2), contract_completed (2),
     *   contract_started (3), contract_completed (3),
     *   execution_completed,
     *   assembly_started, assembly_validated, assembly_completed
     */
    private fun buildFullLedger(): List<Event> = listOf(
        Event("intent_submitted",    mapOf("objective" to "Build the core")),
        Event("contracts_generated", mapOf("total" to 3.0, "source_intent" to "Build the core")),
        Event("contracts_ready",     mapOf("total_contracts" to 3.0)),
        Event("contracts_approved",  emptyMap()),
        Event("execution_started",   mapOf("total_contracts" to 3.0)),
        Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
        Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
        Event("contract_started",    mapOf("contract_id" to "contract_2", "position" to 2, "total" to 3)),
        Event("contract_completed",  mapOf("contract_id" to "contract_2", "position" to 2, "total" to 3)),
        Event("contract_started",    mapOf("contract_id" to "contract_3", "position" to 3, "total" to 3)),
        Event("contract_completed",  mapOf("contract_id" to "contract_3", "position" to 3, "total" to 3)),
        Event("execution_completed", mapOf("contracts_completed" to 3)),
        Event("assembly_started",    emptyMap()),
        Event("assembly_validated",  emptyMap()),
        Event("assembly_completed",  emptyMap())
    )

    private fun store(initial: List<Event> = emptyList()): EventRepository =
        InMemoryEventRepository(initial)

    // ── IRS — shared helpers ──────────────────────────────────────────────────

    private val evidenceRef = EvidenceRef(id = "ev-001", source = "manual")

    private fun fullEvidence() = mapOf(
        "objective"   to listOf(evidenceRef),
        "constraints" to listOf(evidenceRef),
        "environment" to listOf(evidenceRef),
        "resources"   to listOf(evidenceRef)
    )

    private fun fullFields() = mapOf(
        "objective"   to "Build the system",
        "constraints" to "must be reliable",
        "environment" to "cloud",
        "resources"   to "team available"
    )

    private fun majorityConfig() = SwarmConfig(agentCount = 2, consensusRule = ConsensusRule.MAJORITY)

    // ── ReconstructionEngine tests ────────────────────────────────────────────

    @Test
    fun `ReconstructionEngine populates fields from raw input`() {
        val engine = ReconstructionEngine()
        val intent = engine.reconstruct(fullFields(), fullEvidence())
        assertEquals("Build the system", intent.objective.value)
        assertEquals(1, intent.objective.evidence.size)
        assertEquals("cloud", intent.environment.value)
    }

    @Test
    fun `ReconstructionEngine defaults missing fields to empty string`() {
        val engine = ReconstructionEngine()
        val intent = engine.reconstruct(emptyMap(), emptyMap())
        assertEquals("", intent.objective.value)
        assertTrue(intent.constraints.evidence.isEmpty())
    }

    // ── GapDetector tests ─────────────────────────────────────────────────────

    @Test
    fun `GapDetector finds no gaps when all fields have evidence`() {
        val detector = GapDetector()
        val intent   = ReconstructionEngine().reconstruct(fullFields(), fullEvidence())
        val result   = detector.detect(intent)
        assertFalse(result.hasGaps)
        assertTrue(result.gaps.isEmpty())
    }

    @Test
    fun `GapDetector reports gap for field with no evidence`() {
        val detector = GapDetector()
        val evidence = fullEvidence().toMutableMap().also { it.remove("objective") }
        val intent   = ReconstructionEngine().reconstruct(fullFields(), evidence)
        val result   = detector.detect(intent)
        assertTrue(result.hasGaps)
        assertTrue(result.gaps.contains("objective"))
    }

    @Test
    fun `GapDetector reports all four gaps when evidence is empty`() {
        val detector = GapDetector()
        val intent   = ReconstructionEngine().reconstruct(emptyMap(), emptyMap())
        val result   = detector.detect(intent)
        assertTrue(result.hasGaps)
        assertEquals(4, result.gaps.size)
    }

    // ── ScoutOrchestrator tests ───────────────────────────────────────────────

    @Test
    fun `ScoutOrchestrator fills gap when supplementary evidence is supplied`() {
        val scout  = ScoutOrchestrator()
        val intent = ReconstructionEngine().reconstruct(fullFields(), emptyMap())
        val result = scout.scout(intent, listOf("objective"), mapOf("objective" to listOf(evidenceRef)))
        assertTrue(result.enriched)
        assertTrue(result.updatedIntent.objective.evidence.isNotEmpty())
        assertTrue(result.scoutedFields.contains("objective"))
    }

    @Test
    fun `ScoutOrchestrator leaves intent unchanged when no supplementary evidence`() {
        val scout  = ScoutOrchestrator()
        val intent = ReconstructionEngine().reconstruct(fullFields(), emptyMap())
        val result = scout.scout(intent, listOf("objective"), emptyMap())
        assertFalse(result.enriched)
        assertTrue(result.updatedIntent.objective.evidence.isEmpty())
    }

    // ── SwarmValidator tests ──────────────────────────────────────────────────

    @Test
    fun `SwarmValidator is consistent when all fields are evidence-backed`() {
        val validator = SwarmValidator()
        val intent    = ReconstructionEngine().reconstruct(fullFields(), fullEvidence())
        val result    = validator.validate(intent, majorityConfig())
        assertTrue(result.consistent)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(2, result.agentOutputs.size)
    }

    @Test
    fun `SwarmValidator reports inconsistency when evidence is missing`() {
        val validator = SwarmValidator()
        val intent    = ReconstructionEngine().reconstruct(fullFields(), emptyMap())
        val result    = validator.validate(intent, SwarmConfig(4, ConsensusRule.UNANIMOUS))
        assertFalse(result.consistent)
        assertTrue(result.conflicts.isNotEmpty())
    }

    @Test
    fun `SwarmValidator enforces agentCount must be at least 2`() {
        val validator = SwarmValidator()
        val intent    = ReconstructionEngine().reconstruct(fullFields(), fullEvidence())
        try {
            validator.validate(intent, SwarmConfig(1, ConsensusRule.UNANIMOUS))
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("≥ 2") == true)
        }
    }

    @Test
    fun `SwarmValidator WEIGHTED consensus passes when conflicts are below threshold`() {
        val validator = SwarmValidator()
        val intent    = ReconstructionEngine().reconstruct(fullFields(), fullEvidence())
        val result    = validator.validate(intent, SwarmConfig(4, ConsensusRule.WEIGHTED))
        assertTrue(result.consistent)
    }

    // ── SimulationEngine tests ────────────────────────────────────────────────

    @Test
    fun `SimulationEngine is feasible when all fields have evidence and objective is set`() {
        val sim    = SimulationEngine()
        val intent = ReconstructionEngine().reconstruct(fullFields(), fullEvidence())
        val result = sim.simulate(intent)
        assertTrue(result.feasible)
        assertTrue(result.failurePoints.isEmpty())
    }

    @Test
    fun `SimulationEngine fails when any field has no evidence`() {
        val sim    = SimulationEngine()
        val intent = ReconstructionEngine().reconstruct(fullFields(), emptyMap())
        val result = sim.simulate(intent)
        assertFalse(result.feasible)
        assertEquals(4, result.failurePoints.size)
    }

    @Test
    fun `SimulationEngine detects resource-constraint contradiction`() {
        val sim = SimulationEngine()
        val fields = mapOf(
            "objective"   to "Build it",
            "constraints" to "must have resources",
            "environment" to "cloud",
            "resources"   to "unavailable"
        )
        val intent = ReconstructionEngine().reconstruct(fields, fullEvidence())
        val result = sim.simulate(intent)
        assertFalse(result.feasible)
        assertTrue(result.failurePoints.any { it.contains("unavailability") })
    }

    // ── PCCVValidator tests ───────────────────────────────────────────────────

    @Test
    fun `PCCVValidator passes when evidence coverage, swarm, and simulation are all OK`() {
        val pccv      = PCCVValidator()
        val intent    = ReconstructionEngine().reconstruct(fullFields(), fullEvidence())
        val swarm     = SwarmValidator().validate(intent, majorityConfig())
        val sim       = SimulationEngine().simulate(intent)
        val result    = pccv.validate(intent, swarm, sim)
        assertTrue(result.passed)
        assertTrue(result.evidenceCoverage)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `PCCVValidator fails when evidence coverage is missing`() {
        val pccv   = PCCVValidator()
        val intent = ReconstructionEngine().reconstruct(fullFields(), emptyMap())
        val swarm  = SwarmValidator().validate(intent, majorityConfig())
        val sim    = SimulationEngine().simulate(intent)
        val result = pccv.validate(intent, swarm, sim)
        assertFalse(result.passed)
        assertFalse(result.evidenceCoverage)
    }

    // ── IrsOrchestrator integration tests ─────────────────────────────────────

    @Test
    fun `IrsOrchestrator certifies a fully evidence-backed intent in deterministic steps`() {
        val orchestrator = IrsOrchestrator()
        orchestrator.createSession("s1", fullFields(), fullEvidence(), majorityConfig())

        var result = orchestrator.step("s1")
        assertFalse(result.terminal)
        assertEquals(IrsStage.GAP_DETECTION, result.executedStage)

        result = orchestrator.step("s1")
        assertFalse(result.terminal)
        assertEquals(IrsStage.SCOUTING, result.executedStage)

        result = orchestrator.step("s1")
        assertFalse(result.terminal)
        assertEquals(IrsStage.SWARM_VALIDATION, result.executedStage)

        result = orchestrator.step("s1")
        assertFalse(result.terminal)
        assertEquals(IrsStage.SIMULATION, result.executedStage)

        result = orchestrator.step("s1")
        assertFalse(result.terminal)
        assertEquals(IrsStage.PCCV, result.executedStage)

        result = orchestrator.step("s1")
        assertTrue(result.terminal)
        assertEquals(IrsStage.CERTIFICATION, result.executedStage)
        assertTrue(result.orchestratorResult is OrchestratorResult.Certified)
    }

    @Test
    fun `IrsOrchestrator halts with NeedsClarification when gaps exist and scouting cannot fill them`() {
        val orchestrator = IrsOrchestrator()
        orchestrator.createSession("s2", fullFields(), emptyMap(), majorityConfig())

        val gapStep = orchestrator.step("s2")
        assertTrue(gapStep.terminal)
        assertEquals(IrsStage.GAP_DETECTION, gapStep.executedStage)
        assertTrue(gapStep.orchestratorResult is OrchestratorResult.NeedsClarification)
        val clarification = gapStep.orchestratorResult as OrchestratorResult.NeedsClarification
        assertEquals(4, clarification.gaps.size)
    }

    @Test
    fun `IrsOrchestrator history is append-only and replays correctly`() {
        val orchestrator = IrsOrchestrator()
        orchestrator.createSession("s3", fullFields(), fullEvidence(), majorityConfig())

        repeat(6) { orchestrator.step("s3") }

        val history = orchestrator.replayHistory("s3")
        assertEquals(6, history.size)
        assertEquals(IrsStage.GAP_DETECTION, history[0].stage)
        assertEquals(IrsStage.CERTIFICATION, history[5].stage)
    }

    @Test
    fun `IrsOrchestrator returns terminal state on repeated step calls after completion`() {
        val orchestrator = IrsOrchestrator()
        orchestrator.createSession("s4", fullFields(), fullEvidence(), majorityConfig())

        repeat(6) { orchestrator.step("s4") }
        val extraCall = orchestrator.step("s4")
        assertTrue(extraCall.terminal)
        assertTrue(extraCall.orchestratorResult is OrchestratorResult.Certified)
    }
}

private class InMemoryEventRepository(initial: List<Event>) : EventRepository {
    private val ledger = initial.toMutableList()
    override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        ledger.add(Event(type, payload))
    }
    override fun loadEvents(projectId: String): List<Event> = ledger.toList()
}
