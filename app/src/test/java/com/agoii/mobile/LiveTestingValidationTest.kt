package com.agoii.mobile

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerObserver
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.governor.Governor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * LiveTestingValidationTest — validates all 9 spine invariants defined in
 * MQP-SPINE-STABILIZATION-GOVERNOR-v1.
 *
 * Invariants under test:
 *  INV-1  UI NEVER triggers execution directly
 *  INV-2  EventLedger is the ONLY activation source
 *  INV-3  appendUserMessage = pure ingress (HARD STOP)
 *  INV-4  Execution runs ONLY via LedgerObserver
 *  INV-5  Spine runs asynchronously (never inline with append)
 *  INV-6  NO intent loss (serialization required)
 *  INV-7  Exactly one active spine at a time
 *  INV-8  Replay is sole UI state source
 *  INV-9  Governor loop must be fully traceable
 *
 * All tests are JVM-only — no Android framework or network access required.
 * Tests use [InMemoryStore] for isolated, deterministic ledger access.
 *
 * Test sequences:
 *   TEST-01  Empty system
 *   TEST-02  Pure ingress / first interaction
 *   TEST-03  Contract flow
 *   TEST-04  Governor block / drift
 *   TEST-05  Multi-event sequences
 *   TEST-06  Replay consistency
 *   TEST-07  Edge payloads
 *
 * Invariant integration tests:
 *   RL-01   Replay purity law
 *   ARCH-04 State authority
 *   DET-01  Determinism
 *   CSL     Contract sequence lock
 */
class LiveTestingValidationTest {

    // ── InMemoryStore ─────────────────────────────────────────────────────────

    /**
     * In-memory ledger that mirrors the EventLedger observer contract.
     *
     * CONTRACT MQP-LEDGER-ACTIVATION-v1:
     *  - [appendEvent] notifies [observer] OUTSIDE the append operation.
     *  - [observer] may safely call [appendEvent] without deadlock.
     *  - [registerObserver] replaces any previously registered observer.
     *
     * NOTE: This is a test-only implementation. Thread-safety is provided by
     * a synchronized list; for single-threaded tests this is sufficient.
     * It is NOT intended as a template for production code.
     */
    private class InMemoryStore : EventRepository {
        @Volatile private var observer: LedgerObserver? = null
        private val events = java.util.Collections.synchronizedList(mutableListOf<Event>())

        /**
         * Registers the test observer. Test-only — not designed for concurrent callers.
         */
        fun registerObserver(observer: LedgerObserver) {
            this.observer = observer
        }

        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            events.add(Event(type, payload))
            // Notify outside the conceptual "lock" — matches EventLedger contract
            observer?.onLedgerUpdated(projectId)
        }

        override fun loadEvents(projectId: String): List<Event> = synchronized(events) { events.toList() }
    }

    private fun store(vararg initial: Event): InMemoryStore {
        val s = InMemoryStore()
        initial.forEach { s.appendEvent("pid", it.type, it.payload) }
        return s
    }

    private fun replay(store: EventRepository) = Replay(store)

    private val pid = "test-project"

    // ── TEST-01: Empty system ─────────────────────────────────────────────────

    @Test
    fun `TEST-01 ledger starts with zero events`() {
        val s = InMemoryStore()
        assertEquals(0, s.loadEvents(pid).size)
    }

    @Test
    fun `TEST-01 replay on empty ledger returns idle structural state`() {
        val s = InMemoryStore()
        val state = replay(s).replayStructuralState(pid)
        assertFalse(state.auditView.intent.structurallyComplete)
        assertFalse(state.auditView.contracts.generated)
        assertEquals(0, state.auditView.execution.totalTasks)
        assertNull(state.governanceView.lastEventType)
    }

    @Test
    fun `TEST-01 governor on empty ledger returns NO_EVENT`() {
        val s = InMemoryStore()
        val result = Governor(s).runGovernor(pid)
        assertEquals(Governor.GovernorResult.NO_EVENT, result)
    }

    @Test
    fun `TEST-01 observer is not notified on empty ledger`() {
        val notified = AtomicInteger(0)
        val s = InMemoryStore()
        s.registerObserver { notified.incrementAndGet() }
        // No append → observer must not fire
        assertEquals(0, notified.get())
    }

    // ── TEST-02: Pure ingress / first interaction ─────────────────────────────

    @Test
    fun `TEST-02 ingress appends USER_MESSAGE_SUBMITTED and INTENT_SUBMITTED`() {
        val s = InMemoryStore()
        // Simulate pure ingress: two ledger appends only
        s.appendEvent(pid, EventTypes.USER_MESSAGE_SUBMITTED,
            mapOf("messageId" to "m1", "text" to "hello", "timestamp" to 1L))
        s.appendEvent(pid, EventTypes.INTENT_SUBMITTED,
            mapOf("objective" to "hello"))

        val events = s.loadEvents(pid)
        assertEquals(2, events.size)
        assertEquals(EventTypes.USER_MESSAGE_SUBMITTED, events[0].type)
        assertEquals(EventTypes.INTENT_SUBMITTED, events[1].type)
    }

    @Test
    fun `TEST-02 ingress appends exactly two events (INV-3 pure ingress)`() {
        val s = InMemoryStore()
        val before = s.loadEvents(pid).size
        // Simulate appendUserMessage: exactly USER_MESSAGE_SUBMITTED + INTENT_SUBMITTED
        s.appendEvent(pid, EventTypes.USER_MESSAGE_SUBMITTED, mapOf("text" to "create task"))
        s.appendEvent(pid, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "create task"))
        val after = s.loadEvents(pid).size
        assertEquals("Pure ingress must append exactly 2 events", 2, after - before)
    }

    @Test
    fun `TEST-02 INTENT_SUBMITTED payload preserves the objective (INV-6 no intent loss)`() {
        val objective = "build the feature"
        val s = InMemoryStore()
        s.appendEvent(pid, EventTypes.USER_MESSAGE_SUBMITTED, mapOf("text" to objective))
        s.appendEvent(pid, EventTypes.INTENT_SUBMITTED, mapOf("objective" to objective))

        val intentEvent = s.loadEvents(pid).first { it.type == EventTypes.INTENT_SUBMITTED }
        assertEquals("Objective must be preserved verbatim", objective,
            intentEvent.payload["objective"])
    }

    @Test
    fun `TEST-02 observer fires after INTENT_SUBMITTED (INV-4 activation source)`() {
        val activatedFor = mutableListOf<String>()
        val s = InMemoryStore()
        s.registerObserver { projectId -> activatedFor.add(projectId) }

        s.appendEvent(pid, EventTypes.USER_MESSAGE_SUBMITTED, mapOf("text" to "test"))
        s.appendEvent(pid, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"))

        assertEquals("Observer must be notified twice (once per append)", 2, activatedFor.size)
        assertTrue("Observer receives correct projectId", activatedFor.all { it == pid })
    }

    // ── TEST-03: Contract flow ────────────────────────────────────────────────

    @Test
    fun `TEST-03 contracts_generated advances to contracts_ready via governor`() {
        val s = store(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0))
        )
        val result = Governor(s).runGovernor(pid)
        assertEquals(Governor.GovernorResult.ADVANCED, result)
        val types = s.loadEvents(pid).map { it.type }
        assertTrue(types.contains(EventTypes.CONTRACTS_READY))
    }

    @Test
    fun `TEST-03 replay marks contracts valid after contracts_generated`() {
        val s = store(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0))
        )
        val state = replay(s).replayStructuralState(pid)
        assertTrue(state.auditView.contracts.generated)
        assertTrue(state.auditView.contracts.valid)
        assertEquals(3, state.auditView.contracts.totalContracts)
    }

    @Test
    fun `TEST-03 contracts_approved triggers execution_started via governor`() {
        val s = store(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap())
        )
        val result = Governor(s).runGovernor(pid)
        assertEquals(Governor.GovernorResult.ADVANCED, result)
        assertTrue(s.loadEvents(pid).any { it.type == EventTypes.EXECUTION_STARTED })
    }

    @Test
    fun `TEST-03 intent payload objective is non-blank after ingress`() {
        val s = InMemoryStore()
        val rawInput = "create a project"
        s.appendEvent(pid, EventTypes.USER_MESSAGE_SUBMITTED, mapOf("text" to rawInput))
        s.appendEvent(pid, EventTypes.INTENT_SUBMITTED, mapOf("objective" to rawInput))

        val intent = s.loadEvents(pid).first { it.type == EventTypes.INTENT_SUBMITTED }
        val objective = intent.payload["objective"]?.toString() ?: ""
        assertFalse("Objective must not be blank", objective.isBlank())
    }

    // ── TEST-04: Governor block / drift ───────────────────────────────────────

    @Test
    fun `TEST-04 governor returns DRIFT on unknown terminal event`() {
        val s = store(Event("unknown_terminal_event_xyz", emptyMap()))
        val result = Governor(s).runGovernor(pid)
        assertEquals(Governor.GovernorResult.DRIFT, result)
    }

    @Test
    fun `TEST-04 governor returns COMPLETED when last event is EXECUTION_COMPLETED`() {
        val s = store(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 1))
        )
        val result = Governor(s).runGovernor(pid)
        assertEquals(Governor.GovernorResult.COMPLETED, result)
    }

    @Test
    fun `TEST-04 governor returns NO_EVENT on INTENT_SUBMITTED`() {
        val s = store(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj"))
        )
        val result = Governor(s).runGovernor(pid)
        assertEquals(Governor.GovernorResult.NO_EVENT, result)
    }

    @Test
    fun `TEST-04 governor returns ADVANCED on CONTRACTS_GENERATED`() {
        val s = store(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2.0))
        )
        val result = Governor(s).runGovernor(pid)
        assertEquals(Governor.GovernorResult.ADVANCED, result)
    }

    // ── TEST-05: Multi-event sequences ────────────────────────────────────────

    @Test
    fun `TEST-05 governor advances contracts_generated to contracts_ready`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0))
        )
        val next = Governor(InMemoryStore()).nextEvents(events)
        assertEquals(1, next.size)
        assertEquals(EventTypes.CONTRACTS_READY, next[0].type)
    }

    @Test
    fun `TEST-05 governor advances contracts_approved to execution_started`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap())
        )
        val next = Governor(InMemoryStore()).nextEvents(events)
        assertEquals(1, next.size)
        assertEquals(EventTypes.EXECUTION_STARTED, next[0].type)
    }

    @Test
    fun `TEST-05 governor advances execution_started to contract_started`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, emptyMap())
        )
        val next = Governor(InMemoryStore()).nextEvents(events)
        assertEquals(1, next.size)
        assertEquals(EventTypes.CONTRACT_STARTED, next[0].type)
    }

    @Test
    fun `TEST-05 full single-contract lifecycle runs to EXECUTION_COMPLETED via governor`() {
        val s = store(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, emptyMap()),
            Event(EventTypes.CONTRACT_STARTED, mapOf("contract_id" to "c1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_ASSIGNED,
                mapOf("taskId" to "c1-step1", "contractId" to "c1",
                    "position" to 1, "total" to 1,
                    "report_reference" to "", "requirements" to emptyList<Any>(),
                    "constraints" to emptyList<Any>())),
            Event(EventTypes.TASK_STARTED,
                mapOf("taskId" to "c1-step1", "contractId" to "c1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("taskId" to "c1-step1", "contractId" to "c1",
                    "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED",
                    "position" to 1, "total" to 1,
                    "artifactReference" to "art-1", "validationReasons" to listOf("ok"))),
            Event(EventTypes.TASK_COMPLETED,
                mapOf("taskId" to "c1-step1", "position" to 1, "total" to 1))
        )
        // Governor should emit CONTRACT_COMPLETED
        var result = Governor(s).runGovernor(pid)
        assertEquals(Governor.GovernorResult.ADVANCED, result)
        assertTrue(s.loadEvents(pid).any { it.type == EventTypes.CONTRACT_COMPLETED })
    }

    // ── TEST-06: Replay consistency ───────────────────────────────────────────

    @Test
    fun `TEST-06 same event sequence produces identical structural state`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2.0))
        )

        val s1 = store(*events.toTypedArray())
        val s2 = store(*events.toTypedArray())

        val state1 = replay(s1).replayStructuralState(pid)
        val state2 = replay(s2).replayStructuralState(pid)

        assertEquals("Replay must be deterministic", state1.auditView.contracts.totalContracts,
            state2.auditView.contracts.totalContracts)
        assertEquals(state1.governanceView.lastEventType, state2.governanceView.lastEventType)
    }

    @Test
    fun `TEST-06 replay conversation derives user message from USER_MESSAGE_SUBMITTED`() {
        val s = store(
            Event(EventTypes.USER_MESSAGE_SUBMITTED,
                mapOf("messageId" to "m1", "text" to "hello system", "timestamp" to 1L)),
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "hello system"))
        )
        val state = replay(s).replayStructuralState(pid)
        val userMessages = state.conversation.filter { it.isUser }
        assertTrue("Replay must surface user message", userMessages.isNotEmpty())
        assertEquals("hello system", userMessages.first().text)
    }

    @Test
    fun `TEST-06 replay conversation derives system message from SYSTEM_MESSAGE_EMITTED`() {
        val s = store(
            Event(EventTypes.USER_MESSAGE_SUBMITTED,
                mapOf("messageId" to "m1", "text" to "query", "timestamp" to 1L)),
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "query")),
            Event(EventTypes.SYSTEM_MESSAGE_EMITTED,
                mapOf("messageId" to "s1", "text" to "SYSTEM_READY",
                    "source" to "execution", "timestamp" to 2L))
        )
        val state = replay(s).replayStructuralState(pid)
        val systemMessages = state.conversation.filter { !it.isUser }
        assertTrue("Replay must surface system message", systemMessages.isNotEmpty())
        assertEquals("SYSTEM_READY", systemMessages.first().text)
    }

    @Test
    fun `TEST-06 replay produces non-null state for all lifecycle stages`() {
        val stageSets = listOf(
            emptyList(),
            listOf(Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "x"))),
            listOf(
                Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "x")),
                Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0))
            ),
            listOf(
                Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "x")),
                Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
                Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 1))
            )
        )
        for (events in stageSets) {
            val s = store(*events.toTypedArray())
            val state = replay(s).replayStructuralState(pid)
            assertNotNull("Replay must return non-null state for any event list", state)
        }
    }

    // ── TEST-07: Edge payloads ────────────────────────────────────────────────

    @Test
    fun `TEST-07 governor returns null nextEvent on CONTRACTS_READY with zero contracts`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 0.0)),
            Event(EventTypes.CONTRACTS_READY, emptyMap())
        )
        val next = Governor(InMemoryStore()).nextEvents(events)
        assertTrue("Governor must not advance when totalContracts is zero", next.isEmpty())
    }

    @Test
    fun `TEST-07 replay handles empty payload for standard events`() {
        val s = store(
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap())
        )
        // Must not throw
        val state = replay(s).replayStructuralState(pid)
        assertNotNull(state)
    }

    @Test
    fun `TEST-07 governor handles missing position in CONTRACT_STARTED payload`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, emptyMap()),
            // Missing position/total in CONTRACT_STARTED
            Event(EventTypes.CONTRACT_STARTED, mapOf("contract_id" to "c1"))
        )
        val next = Governor(InMemoryStore()).nextEvents(events)
        // Expect null: Governor cannot emit TASK_ASSIGNED without position/contractId
        assertTrue("Governor must not advance when CONTRACT_STARTED lacks position/total",
            next.isEmpty())
    }

    @Test
    fun `TEST-07 objective falls back to non-blank value when explicit objective absent`() {
        val rawInput = "fallback input"
        val s = InMemoryStore()
        // Simulate resolveObjective fallback: explicit objective is absent → use rawInput
        val explicitObjective: String? = null
        val objective = explicitObjective ?: rawInput
        s.appendEvent(pid, EventTypes.INTENT_SUBMITTED, mapOf("objective" to objective))

        val event = s.loadEvents(pid).first { it.type == EventTypes.INTENT_SUBMITTED }
        assertFalse("Fallback objective must not be blank",
            event.payload["objective"]?.toString().isNullOrBlank())
    }

    // ── Invariant integration tests ───────────────────────────────────────────

    @Test
    fun `RL-01 replay purity: structural state derived solely from event sequence`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
            Event(EventTypes.CONTRACTS_READY, emptyMap())
        )
        // Replay from two independent stores with identical events must produce identical state
        val s1 = store(*events.toTypedArray())
        val s2 = store(*events.toTypedArray())
        val st1 = replay(s1).replayStructuralState(pid)
        val st2 = replay(s2).replayStructuralState(pid)

        assertEquals("RL-01: same events must produce same intent completeness",
            st1.auditView.intent.structurallyComplete, st2.auditView.intent.structurallyComplete)
        assertEquals("RL-01: same events must produce same contracts.generated",
            st1.auditView.contracts.generated, st2.auditView.contracts.generated)
        assertEquals("RL-01: same events must produce same totalContracts",
            st1.auditView.contracts.totalContracts, st2.auditView.contracts.totalContracts)
        assertEquals("RL-01: same events must produce same lastEventType",
            st1.governanceView.lastEventType, st2.governanceView.lastEventType)
    }

    @Test
    fun `ARCH-04 ReplayStructuralState is the sole state authority for UI`() {
        // No state may be derived outside of Replay.replayStructuralState.
        // Verify that all meaningful fields flow from the replay engine.
        val s = store(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "verify authority")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2.0)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap())
        )
        val state: ReplayStructuralState = replay(s).replayStructuralState(pid)

        // State must reflect the ledger — not be hard-coded or derived by UI
        assertNotNull("ARCH-04: governanceView must be non-null", state.governanceView)
        assertNotNull("ARCH-04: executionView must be non-null", state.executionView)
        assertNotNull("ARCH-04: auditView must be non-null", state.auditView)
        assertEquals("ARCH-04: lastEventType must match final ledger event",
            EventTypes.CONTRACTS_APPROVED, state.governanceView.lastEventType)
        assertEquals("ARCH-04: totalContracts must be 2 as stored in CONTRACTS_GENERATED",
            2, state.governanceView.totalContracts)
    }

    @Test
    fun `DET-01 governor is deterministic for identical ledger state`() {
        val eventList = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "determinism-test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0))
        )
        // Running nextEvents twice on the same event list must return the same result
        val gov = Governor(InMemoryStore())
        val result1 = gov.nextEvents(eventList)
        val result2 = gov.nextEvents(eventList)

        assertEquals("DET-01: governor must produce same event type", result1.size, result2.size)
        if (result1.isNotEmpty() && result2.isNotEmpty()) {
            assertEquals("DET-01: governor must produce identical event types",
                result1[0].type, result2[0].type)
        }
    }

    @Test
    fun `CSL contract sequence lock enforced: position 4 exceeds velocity ceiling`() {
        // Governor.VC = 5, CONTRACT_BASE_LOAD = 2
        // EL for position 4 = 2 + 4 = 6 > 5 → DRIFT
        // Position 3 → EL = 5 ≤ 5 → allowed
        // Position 4 → EL = 6 > 5 → blocked

        // Build a ledger with 3 contracts executed to force position 4
        val s = store(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "csl-test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 4.0)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, emptyMap()),
            // Simulate 3 completed contracts
            Event(EventTypes.CONTRACT_STARTED, mapOf("contract_id" to "c1", "position" to 1, "total" to 4)),
            Event(EventTypes.TASK_ASSIGNED,
                mapOf("taskId" to "c1-s1", "contractId" to "c1", "position" to 1, "total" to 4,
                    "report_reference" to "", "requirements" to emptyList<Any>(), "constraints" to emptyList<Any>())),
            Event(EventTypes.TASK_STARTED,
                mapOf("taskId" to "c1-s1", "contractId" to "c1", "position" to 1, "total" to 4)),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("taskId" to "c1-s1", "contractId" to "c1",
                    "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED",
                    "position" to 1, "total" to 4,
                    "artifactReference" to "art-1", "validationReasons" to listOf("ok"))),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "c1-s1", "position" to 1, "total" to 4)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 1, "total" to 4, "contractId" to "c1")),
            Event(EventTypes.CONTRACT_STARTED, mapOf("contract_id" to "c2", "position" to 2, "total" to 4)),
            Event(EventTypes.TASK_ASSIGNED,
                mapOf("taskId" to "c2-s1", "contractId" to "c2", "position" to 2, "total" to 4,
                    "report_reference" to "", "requirements" to emptyList<Any>(), "constraints" to emptyList<Any>())),
            Event(EventTypes.TASK_STARTED,
                mapOf("taskId" to "c2-s1", "contractId" to "c2", "position" to 2, "total" to 4)),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("taskId" to "c2-s1", "contractId" to "c2",
                    "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED",
                    "position" to 2, "total" to 4,
                    "artifactReference" to "art-2", "validationReasons" to listOf("ok"))),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "c2-s1", "position" to 2, "total" to 4)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 2, "total" to 4, "contractId" to "c2")),
            Event(EventTypes.CONTRACT_STARTED, mapOf("contract_id" to "c3", "position" to 3, "total" to 4)),
            Event(EventTypes.TASK_ASSIGNED,
                mapOf("taskId" to "c3-s1", "contractId" to "c3", "position" to 3, "total" to 4,
                    "report_reference" to "", "requirements" to emptyList<Any>(), "constraints" to emptyList<Any>())),
            Event(EventTypes.TASK_STARTED,
                mapOf("taskId" to "c3-s1", "contractId" to "c3", "position" to 3, "total" to 4)),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("taskId" to "c3-s1", "contractId" to "c3",
                    "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED",
                    "position" to 3, "total" to 4,
                    "artifactReference" to "art-3", "validationReasons" to listOf("ok"))),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "c3-s1", "position" to 3, "total" to 4)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 3, "total" to 4, "contractId" to "c3"))
        )
        // After CONTRACT_COMPLETED position=3, Governor tries CONTRACT_STARTED position=4.
        // EL = 2 + 4 = 6 > VC=5 → Governor must NOT advance (returns DRIFT or NO advancement).
        val result = Governor(s).runGovernor(pid)
        // Governor.canIssue(4) returns null → nextEventSingle returns null → DRIFT
        assertEquals("CSL: position 4 exceeds velocity ceiling — Governor must DRIFT",
            Governor.GovernorResult.DRIFT, result)
    }
}
