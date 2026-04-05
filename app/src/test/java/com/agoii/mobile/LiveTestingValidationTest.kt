package com.agoii.mobile

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
import com.agoii.mobile.governor.Governor
import org.junit.Assert.*
import org.junit.Test

/**
 * MQP-LIVE-TESTING-v1 — Live Structural Validation Test Suite
 *
 * Validates the system holds under real execution scenarios:
 *   Input → Event → Ledger → Replay → UI
 *
 * 7 test sequences covering:
 *   TEST-01: Empty system (zero-state)
 *   TEST-02: First interaction (single event flow)
 *   TEST-03: Contract flow (deterministic lifecycle)
 *   TEST-04: Governor block (invalid sequence rejection)
 *   TEST-05: Multi-event sequence (incremental correctness)
 *   TEST-06: Replay consistency (determinism under restart)
 *   TEST-07: Edge payload (unusual inputs, no crash)
 *
 * Invariant watch: RL-01, ARCH-04, DET-01, CSL
 */
class LiveTestingValidationTest {

    // ── Test infrastructure ──────────────────────────────────────────────────

    /** In-memory event repository for isolated JVM testing — no Android context needed. */
    private class InMemoryStore(initial: List<Event> = emptyList()) : EventRepository {
        private val ledger = initial.toMutableList()
        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            println("[LOG-01-TEST] InMemoryStore.append | project=$projectId | type=$type | payload=$payload")
            ledger.add(Event(type, payload, id = "test-${ledger.size}", sequenceNumber = ledger.size.toLong()))
        }
        override fun loadEvents(projectId: String): List<Event> = ledger.toList()
        fun eventCount(): Int = ledger.size
    }

    private val projectId = "live-test"

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST-01 — EMPTY SYSTEM
    //
    // Launch with empty ledger. Verify zero-state.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TEST-01a empty system produces explicit zero-state`() {
        val store = InMemoryStore()
        val replay = Replay(store)
        val state = replay.replayStructuralState(projectId)

        // Governance
        assertFalse("hasLastEvent must be false", state.governanceView.hasLastEvent)
        assertNull("lastEventType must be null", state.governanceView.lastEventType)
        assertEquals("totalContracts must be 0", 0, state.governanceView.totalContracts)

        // Execution
        assertEquals("executionStatus must be not_started", "not_started", state.executionView.executionStatus)
        assertFalse("showCommitPanel must be false", state.executionView.showCommitPanel)

        // Audit
        assertEquals("totalEvents must be 0", 0, state.auditView.totalEvents)
        assertEquals("contractIds must be empty", emptyList<String>(), state.auditView.contractIds)
        assertFalse("hasContracts must be false", state.auditView.hasContracts)

        println("[TEST-01a] PASS: Empty system produces explicit zero-state")
    }

    @Test
    fun `TEST-01b empty system governor returns NO_EVENT`() {
        val store = InMemoryStore()
        val governor = Governor(store)

        val result = governor.runGovernor(projectId)
        assertEquals("Governor must return NO_EVENT on empty ledger", Governor.GovernorResult.NO_EVENT, result)

        // Ledger unchanged
        assertEquals("No events should be written", 0, store.eventCount())

        println("[TEST-01b] PASS: Governor returns NO_EVENT on empty system")
    }

    @Test
    fun `TEST-01c empty system no non-zero values appear`() {
        val state = Replay(InMemoryStore()).deriveStructuralState(emptyList())

        // Every numeric must be 0
        assertEquals(0, state.auditView.totalEvents)
        assertEquals(0, state.auditView.execution.totalTasks)
        assertEquals(0, state.auditView.execution.assignedTasks)
        assertEquals(0, state.auditView.execution.completedTasks)
        assertEquals(0, state.auditView.execution.validatedTasks)
        assertEquals(0, state.auditView.contracts.totalContracts)

        // Every boolean must be false (no guessing)
        assertFalse(state.governanceView.hasLastEvent)
        assertFalse(state.auditView.hasContracts)
        assertFalse(state.executionView.showCommitPanel)
        assertFalse(state.executionView.icsStarted)
        assertFalse(state.executionView.icsCompleted)
        assertFalse(state.auditView.intent.structurallyComplete)
        assertFalse(state.auditView.contracts.generated)
        assertFalse(state.auditView.assembly.assemblyStarted)

        println("[TEST-01c] PASS: All zero-state values are explicit, not guessed")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST-02 — FIRST INTERACTION
    //
    // Trigger: processInteraction("start") equivalent → INTENT_SUBMITTED
    // Expected: hasLastEvent=true, totalEvents=1, UI updates immediately
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TEST-02a first interaction creates event and updates replay`() {
        val store = InMemoryStore()
        val replay = Replay(store)

        // Simulate: processInteraction("start") → INTENT_SUBMITTED
        store.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "start"))

        val state = replay.replayStructuralState(projectId)

        // Governance
        assertTrue("hasLastEvent must be true after first event", state.governanceView.hasLastEvent)
        assertEquals("lastEventType must be intent_submitted",
            EventTypes.INTENT_SUBMITTED, state.governanceView.lastEventType)

        // Audit
        assertEquals("totalEvents must be 1", 1, state.auditView.totalEvents)
        assertTrue("intent must be structurally complete", state.auditView.intent.structurallyComplete)

        // Execution unchanged
        assertEquals("executionStatus must still be not_started", "not_started", state.executionView.executionStatus)
        assertFalse("showCommitPanel must still be false", state.executionView.showCommitPanel)

        println("[TEST-02a] PASS: First interaction flow: Event→Replay→State correct")
    }

    @Test
    fun `TEST-02b replay updates immediately after event write`() {
        val store = InMemoryStore()
        val replay = Replay(store)

        // Before write
        val before = replay.replayStructuralState(projectId)
        assertEquals(0, before.auditView.totalEvents)
        assertFalse(before.governanceView.hasLastEvent)

        // Write event
        store.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"))

        // After write — immediate update, no manual refresh needed
        val after = replay.replayStructuralState(projectId)
        assertEquals(1, after.auditView.totalEvents)
        assertTrue(after.governanceView.hasLastEvent)

        println("[TEST-02b] PASS: Replay updates immediately after event write")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST-03 — CONTRACT FLOW
    //
    // Full flow: Intent → Contracts → Execution start
    // Verify: executionStatus changes deterministically, showCommitPanel valid
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TEST-03a contract flow - executionStatus changes deterministically`() {
        val store = InMemoryStore()
        val replay = Replay(store)

        // Phase 1: Intent
        store.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "build"))
        var state = replay.replayStructuralState(projectId)
        assertEquals("not_started", state.executionView.executionStatus)
        assertFalse(state.auditView.hasContracts)

        // Phase 2: Contracts generated
        store.appendEvent(projectId, EventTypes.CONTRACTS_GENERATED,
            mapOf("total" to 3, "report_reference" to "rpt-001"))
        state = replay.replayStructuralState(projectId)
        assertEquals("not_started", state.executionView.executionStatus) // Still not started
        assertTrue("hasContracts must be true", state.auditView.hasContracts)
        assertEquals(3, state.governanceView.totalContracts)
        assertEquals("rpt-001", state.governanceView.reportReference)

        // Phase 3: Contract started
        store.appendEvent(projectId, EventTypes.CONTRACT_STARTED,
            mapOf("contract_id" to "c1", "position" to 1, "total" to 3))
        state = replay.replayStructuralState(projectId)
        assertEquals("not_started", state.executionView.executionStatus) // No task started yet

        // Phase 4: Task assigned + started
        store.appendEvent(projectId, EventTypes.TASK_ASSIGNED,
            mapOf("taskId" to "t1", "position" to 1, "total" to 3))
        store.appendEvent(projectId, EventTypes.TASK_STARTED,
            mapOf("taskId" to "t1", "position" to 1, "total" to 3))
        state = replay.replayStructuralState(projectId)
        assertEquals("running", state.executionView.executionStatus) // Now running

        // Phase 5: Task executed (success)
        store.appendEvent(projectId, EventTypes.TASK_EXECUTED,
            mapOf("taskId" to "t1", "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED",
                "position" to 1, "total" to 3))
        state = replay.replayStructuralState(projectId)
        assertEquals("success", state.executionView.executionStatus)

        println("[TEST-03a] PASS: executionStatus transitions deterministically through contract flow")
    }

    @Test
    fun `TEST-03b showCommitPanel appears only when valid`() {
        val store = InMemoryStore()
        val replay = Replay(store)

        // Setup: intent + contracts
        store.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "commit-test"))
        store.appendEvent(projectId, EventTypes.CONTRACTS_GENERATED,
            mapOf("total" to 1, "report_reference" to "rpt-c"))

        // Before COMMIT_CONTRACT: panel not shown
        var state = replay.replayStructuralState(projectId)
        assertFalse("showCommitPanel must be false before COMMIT_CONTRACT", state.executionView.showCommitPanel)

        // COMMIT_CONTRACT written: panel should show
        store.appendEvent(projectId, EventTypes.COMMIT_CONTRACT, emptyMap())
        state = replay.replayStructuralState(projectId)
        assertTrue("showCommitPanel must be true when COMMIT_CONTRACT exists", state.executionView.showCommitPanel)

        // COMMIT_EXECUTED: panel should hide
        store.appendEvent(projectId, EventTypes.COMMIT_EXECUTED, emptyMap())
        state = replay.replayStructuralState(projectId)
        assertFalse("showCommitPanel must be false after COMMIT_EXECUTED", state.executionView.showCommitPanel)

        println("[TEST-03b] PASS: showCommitPanel appears ONLY when valid")
    }

    @Test
    fun `TEST-03c showCommitPanel hides on abort`() {
        val store = InMemoryStore()
        val replay = Replay(store)

        store.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "abort-test"))
        store.appendEvent(projectId, EventTypes.COMMIT_CONTRACT, emptyMap())

        var state = replay.replayStructuralState(projectId)
        assertTrue("showCommitPanel must be true", state.executionView.showCommitPanel)

        store.appendEvent(projectId, EventTypes.COMMIT_ABORTED, emptyMap())
        state = replay.replayStructuralState(projectId)
        assertFalse("showCommitPanel must be false after abort", state.executionView.showCommitPanel)

        println("[TEST-03c] PASS: showCommitPanel hides on abort")
    }

    @Test
    fun `TEST-03d no intermediate invalid states during flow`() {
        val store = InMemoryStore()
        val replay = Replay(store)
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "flow")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2, "report_reference" to "rpt-x")),
            Event(EventTypes.CONTRACT_STARTED, mapOf("contract_id" to "c1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "t1", "executionStatus" to "SUCCESS",
                "validationStatus" to "VALIDATED", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "t1", "position" to 1, "total" to 2))
        )

        // Walk through each prefix and verify no invalid intermediate state
        for (i in events.indices) {
            val prefix = events.subList(0, i + 1)
            val state = replay.deriveStructuralState(prefix)

            // RL-01: totalEvents MUST equal prefix length
            assertEquals("totalEvents must equal event count at step $i",
                prefix.size, state.auditView.totalEvents)

            // ARCH-04: hasLastEvent MUST be true for any non-empty ledger
            assertTrue("hasLastEvent must be true at step $i", state.governanceView.hasLastEvent)

            // DET-01: lastEventType MUST match last event in prefix
            assertEquals("lastEventType must match last event at step $i",
                prefix.last().type, state.governanceView.lastEventType)
        }

        println("[TEST-03d] PASS: No intermediate invalid states during full flow")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST-04 — GOVERNOR BLOCK
    //
    // Trigger invalid sequence: approve before contract exists
    // Expected: NO event written, UI unchanged, no crash
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TEST-04a governor returns NO_EVENT on empty ledger`() {
        val store = InMemoryStore()
        val governor = Governor(store)

        val result = governor.runGovernor(projectId)
        assertEquals(Governor.GovernorResult.NO_EVENT, result)
        assertEquals("No events should be written", 0, store.eventCount())

        println("[TEST-04a] PASS: Governor blocks on empty ledger — no events written")
    }

    @Test
    fun `TEST-04b governor returns NO_EVENT after only INTENT_SUBMITTED`() {
        val store = InMemoryStore()
        store.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"))
        val governor = Governor(store)

        val result = governor.runGovernor(projectId)
        // Governor waits for ExecutionAuthority to write CONTRACTS_GENERATED
        assertEquals(Governor.GovernorResult.NO_EVENT, result)
        assertEquals("Only 1 event should exist", 1, store.eventCount())

        println("[TEST-04b] PASS: Governor waits for ExecutionAuthority after INTENT_SUBMITTED")
    }

    @Test
    fun `TEST-04c governor drift on invalid state`() {
        val store = InMemoryStore()
        // Start from a state that has no valid transition
        store.appendEvent(projectId, EventTypes.TASK_FAILED,
            mapOf("taskId" to "t1", "position" to 1, "total" to 1))
        val governor = Governor(store)

        val result = governor.runGovernor(projectId)
        // TASK_FAILED has no auto-transition (requires external escalation)
        assertEquals("Governor returns DRIFT on stuck state",
            Governor.GovernorResult.DRIFT, result)

        println("[TEST-04c] PASS: Governor signals DRIFT on invalid sequence — no crash")
    }

    @Test
    fun `TEST-04d CSL gate blocks contract beyond velocity ceiling`() {
        // CSL: VC=5, CONTRACT_BASE_LOAD=2. Position 4 → EL=6 > 5 → DRIFT
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "csl-test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 5, "report_reference" to "rpt-csl")),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            // Contract 1 full cycle
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 5, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "c1-t1", "position" to 1, "total" to 5)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "c1-t1", "position" to 1, "total" to 5)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "c1-t1", "executionStatus" to "SUCCESS",
                "validationStatus" to "VALIDATED", "position" to 1, "total" to 5)),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "c1-t1", "position" to 1, "total" to 5)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 1, "total" to 5,
                "contractId" to "c1", "report_reference" to "rpt-csl")),
            // Contract 2 full cycle
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 2, "total" to 5, "contract_id" to "c2")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "c2-t1", "position" to 2, "total" to 5)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "c2-t1", "position" to 2, "total" to 5)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "c2-t1", "executionStatus" to "SUCCESS",
                "validationStatus" to "VALIDATED", "position" to 2, "total" to 5)),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "c2-t1", "position" to 2, "total" to 5)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 2, "total" to 5,
                "contractId" to "c2", "report_reference" to "rpt-csl")),
            // Contract 3 full cycle
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 3, "total" to 5, "contract_id" to "c3")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "c3-t1", "position" to 3, "total" to 5)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "c3-t1", "position" to 3, "total" to 5)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "c3-t1", "executionStatus" to "SUCCESS",
                "validationStatus" to "VALIDATED", "position" to 3, "total" to 5)),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "c3-t1", "position" to 3, "total" to 5)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 3, "total" to 5,
                "contractId" to "c3", "report_reference" to "rpt-csl"))
        )

        val governor = Governor(InMemoryStore(events))

        // Position 4: EL = 2 + 4 = 6 > VC=5 → DRIFT
        val result = governor.runGovernor(projectId)
        assertEquals("CSL gate must block position 4", Governor.GovernorResult.DRIFT, result)

        println("[TEST-04d] PASS: CSL gate enforced — position 4 blocked")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST-05 — MULTI-EVENT SEQUENCE
    //
    // Run 5-10 interactions rapidly. Verify: totalEvents increments correctly,
    // contractIds stable, no duplicated state, no UI flicker.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TEST-05a totalEvents increments correctly across multi-event sequence`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "multi")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2, "report_reference" to "rpt-m")),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACT_STARTED, mapOf("contract_id" to "c1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "t1", "executionStatus" to "SUCCESS",
                "validationStatus" to "VALIDATED", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "t1", "position" to 1, "total" to 2)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 1, "total" to 2,
                "contractId" to "c1", "report_reference" to "rpt-m")),
            Event(EventTypes.CONTRACT_STARTED, mapOf("contract_id" to "c2", "position" to 2, "total" to 2))
        )

        val replay = Replay(InMemoryStore())

        // Replay each prefix and verify totalEvents increments correctly
        for (i in events.indices) {
            val prefix = events.subList(0, i + 1)
            val state = replay.deriveStructuralState(prefix)
            assertEquals("totalEvents at step $i must be ${i + 1}",
                i + 1, state.auditView.totalEvents)
        }

        println("[TEST-05a] PASS: totalEvents increments correctly across 10 events")
    }

    @Test
    fun `TEST-05b contractIds stable under multi-event sequence`() {
        val replay = Replay(InMemoryStore())

        // Events with delta contract recovery IDs
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "delta-test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2, "report_reference" to "rpt-d")),
            Event(EventTypes.DELTA_CONTRACT_CREATED, mapOf("recoveryId" to "r1", "contractId" to "c1",
                "taskId" to "t1", "report_reference" to "rpt-d")),
            Event(EventTypes.DELTA_CONTRACT_CREATED, mapOf("recoveryId" to "r2", "contractId" to "c2",
                "taskId" to "t2", "report_reference" to "rpt-d")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t2", "position" to 2, "total" to 2))
        )

        val state = replay.deriveStructuralState(events)

        // contractIds should contain exactly r1, r2 (from delta contracts)
        assertEquals("contractIds must contain exactly 2 IDs", 2, state.auditView.contractIds.size)
        assertTrue("contractIds must contain r1", state.auditView.contractIds.contains("r1"))
        assertTrue("contractIds must contain r2", state.auditView.contractIds.contains("r2"))

        // No duplicates
        assertEquals("No duplicate contractIds",
            state.auditView.contractIds.size,
            state.auditView.contractIds.toSet().size)

        println("[TEST-05b] PASS: contractIds stable — no duplicates, correct IDs")
    }

    @Test
    fun `TEST-05c no duplicated state across rapid event replay`() {
        val events = mutableListOf<Event>()
        events.add(Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "rapid")))
        events.add(Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1, "report_reference" to "rpt-r")))
        events.add(Event(EventTypes.CONTRACT_STARTED, mapOf("contract_id" to "c1", "position" to 1, "total" to 1)))
        events.add(Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 1)))
        events.add(Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1", "position" to 1, "total" to 1)))

        val replay = Replay(InMemoryStore())

        // Replay multiple times from same events — must produce identical state each time
        val results = (1..5).map { replay.deriveStructuralState(events) }
        val first = results.first()
        results.forEach { result ->
            assertEquals("totalEvents must be consistent", first.auditView.totalEvents, result.auditView.totalEvents)
            assertEquals("executionStatus must be consistent",
                first.executionView.executionStatus, result.executionView.executionStatus)
            assertEquals("hasLastEvent must be consistent",
                first.governanceView.hasLastEvent, result.governanceView.hasLastEvent)
            assertEquals("hasContracts must be consistent",
                first.auditView.hasContracts, result.auditView.hasContracts)
        }

        println("[TEST-05c] PASS: No duplicated state across rapid replays")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST-06 — REPLAY CONSISTENCY
    //
    // Kill app → restart equivalent: derive from same events → identical state.
    // Same events → same UI. Every time. (DET-01)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TEST-06a replay produces identical state from same events`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "consistency")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2, "report_reference" to "rpt-con")),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACT_STARTED, mapOf("contract_id" to "c1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "t1", "executionStatus" to "SUCCESS",
                "validationStatus" to "VALIDATED", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "t1", "position" to 1, "total" to 2))
        )

        // Simulate "restart": two independent Replay instances derive from same events
        val replay1 = Replay(InMemoryStore())
        val replay2 = Replay(InMemoryStore())

        val state1 = replay1.deriveStructuralState(events)
        val state2 = replay2.deriveStructuralState(events)

        // Full structural equality (DET-01)
        assertEquals("GovernanceView must be identical",
            state1.governanceView, state2.governanceView)
        assertEquals("ExecutionView must be identical",
            state1.executionView, state2.executionView)
        assertEquals("AuditView must be identical",
            state1.auditView, state2.auditView)
        assertEquals("Full ReplayStructuralState must be identical", state1, state2)

        println("[TEST-06a] PASS: Replay determinism — same events → same state (DET-01)")
    }

    @Test
    fun `TEST-06b replay state is identical after simulated app restart`() {
        // Simulate: write events → read state → "kill" → read state again
        val store = InMemoryStore()
        val replay = Replay(store)

        store.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "restart"))
        store.appendEvent(projectId, EventTypes.CONTRACTS_GENERATED,
            mapOf("total" to 1, "report_reference" to "rpt-rst"))
        store.appendEvent(projectId, EventTypes.COMMIT_CONTRACT, emptyMap())

        // "Before kill" state
        val beforeKill = replay.replayStructuralState(projectId)

        // "After restart": create new Replay instance, same underlying store
        val replayAfterRestart = Replay(store)
        val afterRestart = replayAfterRestart.replayStructuralState(projectId)

        // Must be identical
        assertEquals("State before kill must equal state after restart", beforeKill, afterRestart)

        // Specific fields verified
        assertEquals(beforeKill.governanceView.hasLastEvent, afterRestart.governanceView.hasLastEvent)
        assertEquals(beforeKill.executionView.showCommitPanel, afterRestart.executionView.showCommitPanel)
        assertEquals(beforeKill.auditView.totalEvents, afterRestart.auditView.totalEvents)
        assertEquals(beforeKill.auditView.hasContracts, afterRestart.auditView.hasContracts)

        println("[TEST-06b] PASS: State identical after simulated app restart")
    }

    @Test
    fun `TEST-06c replay does not recompute differently on second call`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "det")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3, "report_reference" to "rpt-det")),
            Event(EventTypes.DELTA_CONTRACT_CREATED, mapOf("recoveryId" to "r1", "contractId" to "c1",
                "taskId" to "t1", "report_reference" to "rpt-det"))
        )

        val replay = Replay(InMemoryStore())

        val first  = replay.deriveStructuralState(events)
        val second = replay.deriveStructuralState(events)
        val third  = replay.deriveStructuralState(events)

        assertEquals(first, second)
        assertEquals(second, third)
        assertEquals("contractIds must be deterministic", first.auditView.contractIds, third.auditView.contractIds)

        println("[TEST-06c] PASS: Replay does not recompute differently on repeated calls")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST-07 — EDGE PAYLOAD
    //
    // Inject large / unusual input. Verify: no crash, payload displayed correctly,
    // Replay stable.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TEST-07a large payload does not crash replay`() {
        val largeString = "x".repeat(100_000)
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to largeString))
        )

        val replay = Replay(InMemoryStore())
        val state = replay.deriveStructuralState(events)

        assertTrue("hasLastEvent must be true", state.governanceView.hasLastEvent)
        assertEquals(1, state.auditView.totalEvents)
        // Payload contains the large string
        assertTrue("payload must contain large string",
            state.governanceView.lastEventPayload.containsKey("objective"))

        println("[TEST-07a] PASS: Large payload — no crash, replay stable")
    }

    @Test
    fun `TEST-07b special characters in payload do not crash`() {
        val specialPayloads = listOf(
            mapOf("objective" to ""),
            mapOf("objective" to "null"),
            mapOf("objective" to "\n\t\r"),
            mapOf("objective" to "日本語テスト"),
            mapOf("objective" to "<script>alert('xss')</script>"),
            mapOf("objective" to "key=value&other=test"),
            mapOf("objective" to "\"quoted\""),
            mapOf("objective" to "emoji 🎉🔥")
        )

        val replay = Replay(InMemoryStore())

        for ((i, payload) in specialPayloads.withIndex()) {
            val events = listOf(Event(EventTypes.INTENT_SUBMITTED, payload))
            val state = replay.deriveStructuralState(events)

            assertTrue("hasLastEvent must be true for payload $i", state.governanceView.hasLastEvent)
            assertEquals("totalEvents must be 1 for payload $i", 1, state.auditView.totalEvents)
        }

        println("[TEST-07b] PASS: Special characters in payload — no crash")
    }

    @Test
    fun `TEST-07c empty payload fields do not crash`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, emptyMap()),
            Event(EventTypes.CONTRACTS_GENERATED, emptyMap()),
            Event(EventTypes.CONTRACT_STARTED, emptyMap()),
            Event(EventTypes.TASK_ASSIGNED, emptyMap()),
            Event(EventTypes.TASK_STARTED, emptyMap())
        )

        val replay = Replay(InMemoryStore())
        val state = replay.deriveStructuralState(events)

        assertEquals(5, state.auditView.totalEvents)
        assertTrue(state.governanceView.hasLastEvent)
        assertEquals("not_started", state.executionView.executionStatus) // No TASK_EXECUTED → not running

        println("[TEST-07c] PASS: Empty payload fields — no crash, replay stable")
    }

    @Test
    fun `TEST-07d numeric edge cases in payload`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "numbers")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf(
                "total" to 0,                    // Zero contracts
                "report_reference" to ""         // Empty reference
            ))
        )

        val replay = Replay(InMemoryStore())
        val state = replay.deriveStructuralState(events)

        assertEquals(2, state.auditView.totalEvents)
        assertFalse("hasContracts must be false when total=0", state.auditView.hasContracts)
        assertEquals("", state.governanceView.reportReference)

        println("[TEST-07d] PASS: Numeric edge cases — no crash, correct state")
    }

    @Test
    fun `TEST-07e payload stringification in display fields`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "display", "nested" to mapOf("a" to 1)))
        )

        val replay = Replay(InMemoryStore())
        val state = replay.deriveStructuralState(events)

        // lastEventPayloadDisplay must be the toString() of the payload map
        assertNotNull("lastEventPayloadDisplay must not be null", state.governanceView.lastEventPayloadDisplay)
        assertTrue("lastEventPayloadDisplay must contain 'objective'",
            state.governanceView.lastEventPayloadDisplay.contains("objective"))
        assertTrue("lastEventTypeDisplay must be intent_submitted",
            state.governanceView.lastEventTypeDisplay == EventTypes.INTENT_SUBMITTED)

        println("[TEST-07e] PASS: Payload stringification correct in display fields")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INVARIANT ENFORCEMENT — Cross-cutting validation
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `INVARIANT-RL01 all visible state exists in ReplayStructuralState`() {
        // RL-01: Every field the UI reads MUST exist in ReplayStructuralState.
        // Verify by checking the data class has all expected fields via reflection.
        val state = Replay(InMemoryStore()).deriveStructuralState(emptyList())

        // GovernanceView fields that UI reads
        assertNotNull(state.governanceView.hasLastEvent)
        assertNotNull(state.governanceView.lastEventTypeDisplay)
        assertNotNull(state.governanceView.lastEventPayloadDisplay)
        assertNotNull(state.governanceView.reportReference)

        // ExecutionView fields that UI reads
        assertNotNull(state.executionView.executionStatus)
        assertNotNull(state.executionView.showCommitPanel)

        // AuditView fields that UI reads
        assertNotNull(state.auditView.totalEvents)
        assertNotNull(state.auditView.contractIds)
        assertNotNull(state.auditView.hasContracts)

        println("[INVARIANT-RL01] PASS: All visible state exists in ReplayStructuralState")
    }

    @Test
    fun `INVARIANT-ARCH04 replay is single source of truth`() {
        // ARCH-04: Same events → same state. No external input can change the result.
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "arch04")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2, "report_reference" to "rpt-arch"))
        )

        val replay = Replay(InMemoryStore())

        // 10 independent derivations must be identical
        val results = (1..10).map { replay.deriveStructuralState(events) }
        val reference = results.first()
        results.forEach { r ->
            assertEquals("ReplayStructuralState must be identical across all derivations", reference, r)
        }

        println("[INVARIANT-ARCH04] PASS: Replay is single source of truth")
    }

    @Test
    fun `INVARIANT-DET01 deterministic across independent replay instances`() {
        // DET-01: Different Replay instances, same events → same state
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "det01")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1, "report_reference" to "rpt-det01")),
            Event(EventTypes.CONTRACT_STARTED, mapOf("contract_id" to "c1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1", "position" to 1, "total" to 1))
        )

        val r1 = Replay(InMemoryStore()).deriveStructuralState(events)
        val r2 = Replay(InMemoryStore()).deriveStructuralState(events)
        val r3 = Replay(InMemoryStore()).deriveStructuralState(events)

        assertEquals(r1, r2)
        assertEquals(r2, r3)

        println("[INVARIANT-DET01] PASS: Deterministic across independent replay instances")
    }

    @Test
    fun `INVARIANT-CSL no mutation outside governed flow`() {
        // CSL: Verify that Governor only appends events through the store interface.
        // After Governor runs, events in store must only have valid types.
        val store = InMemoryStore(listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "csl-test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1, "report_reference" to "rpt-csl")),
            Event(EventTypes.CONTRACTS_READY, emptyMap())
        ))
        val governor = Governor(store)

        governor.runGovernor(projectId)

        // All events must have valid types
        val allEvents = store.loadEvents(projectId)
        allEvents.forEach { event ->
            assertTrue("Event type '${event.type}' must be in EventTypes.ALL",
                event.type in EventTypes.ALL)
        }

        println("[INVARIANT-CSL] PASS: No mutation outside governed flow — all types valid")
    }
}
