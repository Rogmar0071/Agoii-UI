package com.agoii.mobile

import com.agoii.mobile.assembly.AssemblyValidator
import com.agoii.mobile.contractor.ContractorCapabilityVector
import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.VerificationStatus
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerAudit
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.core.ReplayTest
import com.agoii.mobile.governor.Governor
import com.agoii.mobile.ui.core.StateProjection
import com.agoii.mobile.ui.orchestration.UIViewOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGOII — Real Proof Execution Scenarios (V1)
 *
 * These tests validate the seven proof scenarios from the problem statement using
 * the REAL system components (Governor, Replay, LedgerAudit, AssemblyValidator, UI).
 * All tests run on the JVM with an in-memory [EventRepository] — no Android device required.
 *
 * Global rules enforced throughout:
 *  1. ALL flows run through Governor (no direct event injection into the ledger).
 *  2. Each step = ONE Governor.runGovernor() call.
 *  3. After each step, the Ledger and ReplayState are inspected.
 *  4. Unexpected behavior stops the test immediately (assertion failure).
 */
class ProofExecutionScenariosTest {

    // ── In-memory event store ─────────────────────────────────────────────────

    /** Plain in-memory store used for read-only or governor-driven scenarios. */
    private fun newStore(initial: List<Event> = emptyList()): EventRepository {
        val ledger = initial.toMutableList()
        return object : EventRepository {
            override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
                ledger.add(Event(type, payload))
            }
            override fun loadEvents(projectId: String): List<Event> = ledger.toList()
        }
    }

    /**
     * Tracking store that counts every [appendEvent] call.
     * Used by Scenario 6 to verify mutation authority enforcement.
     */
    private class TrackingEventRepository(
        initial: List<Event> = emptyList()
    ) : EventRepository {
        private val ledger = initial.toMutableList()
        var appendCallCount = 0
            private set

        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            ledger.add(Event(type, payload))
            appendCallCount++
        }
        override fun loadEvents(projectId: String): List<Event> = ledger.toList()
    }

    // ── Contractor fixture ────────────────────────────────────────────────────

    /**
     * Returns a registry pre-populated with one capable, verified contractor.
     * Capability scores are high enough to guarantee success in ExecutionOrchestrator.
     */
    private fun registryWithContractor(): ContractorRegistry {
        val cap = ContractorCapabilityVector(
            constraintObedience = 3,
            structuralAccuracy  = 3,
            driftScore          = 0,
            complexityCapacity  = 3,
            reliability         = 3
        )
        val profile = ContractorProfile(
            id                = "proof-contractor",
            capabilities      = cap,
            verificationCount = 1,
            successCount      = 9,
            failureCount      = 1,
            status            = VerificationStatus.VERIFIED,
            source            = "proof"
        )
        return ContractorRegistry().also { it.registerVerified(profile) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 1 — FULL VALID FLOW (BASELINE)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Purpose: Verify the system can complete a full lifecycle when everything is correct.
     *
     * Flow (1-contract project):
     *   submitIntent → [13 × ADVANCED] → WAITING_FOR_APPROVAL → approveContracts
     *   → [more ADVANCED] → COMPLETED
     *
     * Validated at every step:
     *   - Governor returns expected result.
     *   - Ledger last event matches expected type.
     *   - ReplayState reflects the new phase.
     *
     * Final assertions:
     *   - AssemblyResult.isValid == true
     *   - auditLedger().valid == true
     *   - assembly_completed only reachable after assembly_validated
     *   - ReplayState: executionCompleted=true, contractsCompleted==totalContracts, assemblyCompleted=true
     */
    @Test
    fun `scenario 1 - full valid flow baseline`() {
        val pid      = "proof-s1"
        val store    = newStore()
        val registry = registryWithContractor()
        val governor = Governor(store, registry)
        val replay   = Replay(store)
        val audit    = LedgerAudit(store)

        // ── Intent submission (UI action via Governor) ────────────────────────
        governor.submitIntent(pid, "Prove the system works")
        assertEquals(EventTypes.INTENT_SUBMITTED, store.loadEvents(pid).last().type)

        // Step 1: intent_submitted → contracts_generated
        assertEquals(Governor.GovernorResult.ADVANCED, governor.runGovernor(pid))
        assertEquals(EventTypes.CONTRACTS_GENERATED, store.loadEvents(pid).last().type)

        // Step 2: contracts_generated → contracts_ready
        assertEquals(Governor.GovernorResult.ADVANCED, governor.runGovernor(pid))
        assertEquals(EventTypes.CONTRACTS_READY, store.loadEvents(pid).last().type)

        // Approval gate — Governor must pause, ledger must NOT change
        assertEquals(Governor.GovernorResult.WAITING_FOR_APPROVAL, governor.runGovernor(pid))
        assertEquals(EventTypes.CONTRACTS_READY, store.loadEvents(pid).last().type)

        // ── Contract approval (UI action via Governor) ────────────────────────
        governor.approveContracts(pid)
        assertEquals(EventTypes.CONTRACTS_APPROVED, store.loadEvents(pid).last().type)

        // Step 3: contracts_approved → execution_started
        assertEquals(Governor.GovernorResult.ADVANCED, governor.runGovernor(pid))
        assertEquals(EventTypes.EXECUTION_STARTED, store.loadEvents(pid).last().type)
        var state = replay.replay(pid)
        assertTrue("executionStarted must be true", state.executionStarted)
        assertFalse("executionCompleted must still be false", state.executionCompleted)
        assertFalse("assemblyStarted must still be false", state.assemblyStarted)

        // Steps 4–9: full contract lifecycle for 1 contract
        val contractLifecycle = listOf(
            EventTypes.CONTRACT_STARTED,
            EventTypes.TASK_ASSIGNED,
            EventTypes.TASK_STARTED,
            EventTypes.TASK_COMPLETED,
            EventTypes.TASK_VALIDATED,
            EventTypes.CONTRACT_COMPLETED
        )
        for (expectedType in contractLifecycle) {
            assertEquals(Governor.GovernorResult.ADVANCED, governor.runGovernor(pid))
            assertEquals(
                "Expected event type $expectedType",
                expectedType,
                store.loadEvents(pid).last().type
            )
        }

        // Step 10: contract_completed (pos==total) → execution_completed
        assertEquals(Governor.GovernorResult.ADVANCED, governor.runGovernor(pid))
        assertEquals(EventTypes.EXECUTION_COMPLETED, store.loadEvents(pid).last().type)
        state = replay.replay(pid)
        assertTrue("executionCompleted must be true", state.executionCompleted)
        assertEquals("contractsCompleted must equal totalContracts", state.totalContracts, state.contractsCompleted)
        assertFalse("assemblyStarted must still be false", state.assemblyStarted)

        // Step 11: execution_completed → assembly_started
        assertEquals(Governor.GovernorResult.ADVANCED, governor.runGovernor(pid))
        assertEquals(EventTypes.ASSEMBLY_STARTED, store.loadEvents(pid).last().type)
        state = replay.replay(pid)
        assertTrue("assemblyStarted must be true", state.assemblyStarted)
        assertFalse("assemblyValidated must still be false", state.assemblyValidated)

        // Step 12: assembly_started → assembly_validated (AssemblyValidator gate)
        assertEquals(Governor.GovernorResult.ADVANCED, governor.runGovernor(pid))
        assertEquals(EventTypes.ASSEMBLY_VALIDATED, store.loadEvents(pid).last().type)
        state = replay.replay(pid)
        assertTrue("assemblyValidated must be true", state.assemblyValidated)
        assertFalse("assemblyCompleted must still be false", state.assemblyCompleted)

        // Step 13: assembly_validated → assembly_completed
        assertEquals(Governor.GovernorResult.ADVANCED, governor.runGovernor(pid))
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, store.loadEvents(pid).last().type)

        // Terminal: Governor must return COMPLETED with no further mutations
        assertEquals(Governor.GovernorResult.COMPLETED, governor.runGovernor(pid))
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, store.loadEvents(pid).last().type)

        // ── Final ReplayState validation ──────────────────────────────────────
        state = replay.replay(pid)
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, state.phase)
        assertTrue("executionCompleted must be true", state.executionCompleted)
        assertEquals("contractsCompleted must equal totalContracts",
            state.totalContracts, state.contractsCompleted)
        assertTrue("assemblyStarted must be true", state.assemblyStarted)
        assertTrue("assemblyValidated must be true", state.assemblyValidated)
        assertTrue("assemblyCompleted must be true", state.assemblyCompleted)

        // ── AssemblyResult validation ─────────────────────────────────────────
        val assemblyResult = AssemblyValidator().validate(state)
        val issues = assemblyResult.missingElements + assemblyResult.failedChecks
        assertTrue("AssemblyResult.isValid must be true. Issues: $issues", assemblyResult.isValid)

        // ── Ledger audit ──────────────────────────────────────────────────────
        val auditResult = audit.auditLedger(pid)
        assertTrue("auditLedger must pass. Errors: ${auditResult.errors}", auditResult.valid)

        // ── assembly_completed only reachable AFTER assembly_validated ────────
        val events = store.loadEvents(pid)
        val validatedIdx  = events.indexOfFirst { it.type == EventTypes.ASSEMBLY_VALIDATED }
        val completedIdx  = events.indexOfFirst { it.type == EventTypes.ASSEMBLY_COMPLETED }
        assertTrue("assembly_completed index ($completedIdx) must be after assembly_validated ($validatedIdx)",
            validatedIdx < completedIdx)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 2 — MISSING CONTRACT COMPLETION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Purpose: Verify Assembly blocks incomplete contract execution.
     *
     * A ledger is seeded with 3 total contracts but only 2 completed,
     * plus a premature execution_completed + assembly_started.
     * Governor must return NO_EVENT when it tries to advance past assembly_started,
     * because AssemblyValidator rejects the incomplete state.
     *
     * Validations:
     *   - Governor returns NO_EVENT (blocked by AssemblyValidator)
     *   - assembly_validated NOT emitted
     *   - assembly_completed NOT emitted
     *   - ReplayState: contractsCompleted (2) < totalContracts (3), assemblyCompleted=false
     */
    @Test
    fun `scenario 2 - missing contract completion - assembly blocks incomplete execution`() {
        val pid = "proof-s2"
        // Seed: 3 total contracts, only 2 completed, then premature execution_completed + assembly_started
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "proof-s2")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 3.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_2", "position" to 2, "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_2", "position" to 2, "total" to 3)),
            // Only 2 of 3 contracts done — execution_completed is premature
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 2)),
            Event(EventTypes.ASSEMBLY_STARTED,    emptyMap())
        )
        val store    = newStore(events)
        val governor = Governor(store)
        val replay   = Replay(store)

        // Verify ReplayState before Governor call
        val stateBefore = replay.replay(pid)
        assertEquals("contractsCompleted must be 2", 2, stateBefore.contractsCompleted)
        assertEquals("totalContracts must be 3", 3, stateBefore.totalContracts)
        assertTrue("executionCompleted must be true", stateBefore.executionCompleted)
        assertTrue("assemblyStarted must be true", stateBefore.assemblyStarted)
        assertFalse("assemblyValidated must be false", stateBefore.assemblyValidated)
        assertFalse("assemblyCompleted must be false", stateBefore.assemblyCompleted)

        // Governor tries to advance from assembly_started → AssemblyValidator FAILS
        val result = governor.runGovernor(pid)
        assertEquals(
            "Governor must return NO_EVENT when assembly validation fails",
            Governor.GovernorResult.NO_EVENT,
            result
        )

        // Ledger must not have advanced past assembly_started
        val finalEvents = store.loadEvents(pid)
        assertFalse("assembly_validated must NOT be emitted",
            finalEvents.any { it.type == EventTypes.ASSEMBLY_VALIDATED })
        assertFalse("assembly_completed must NOT be emitted",
            finalEvents.any { it.type == EventTypes.ASSEMBLY_COMPLETED })

        // ReplayState must still show incomplete assembly
        val stateAfter = replay.replay(pid)
        assertEquals("contractsCompleted must still be 2", 2, stateAfter.contractsCompleted)
        assertFalse("assemblyCompleted must be false", stateAfter.assemblyCompleted)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 3 — PARTIAL EXECUTION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Purpose: Verify the system never reaches assembly_started when execution is not complete.
     *
     * A ledger is seeded only up to execution_started. The Governor is called multiple times.
     * Each call must advance the task lifecycle, but assembly events must never appear.
     *
     * Validations:
     *   - Governor refuses to emit assembly_started (it's stuck in task lifecycle)
     *   - executionCompleted = false
     *   - No assembly events in ledger
     */
    @Test
    fun `scenario 3 - partial execution - assembly_started never reached without execution_completed`() {
        val pid = "proof-s3"
        // Seed: ledger stopped at execution_started (no contract work done yet)
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "proof-s3")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 3.0))
        )
        val store    = newStore(events)
        val registry = registryWithContractor()
        val governor = Governor(store, registry)
        val replay   = Replay(store)

        // Verify initial state: execution started, nothing completed
        val initialState = replay.replay(pid)
        assertTrue("executionStarted must be true", initialState.executionStarted)
        assertFalse("executionCompleted must be false", initialState.executionCompleted)
        assertFalse("assemblyStarted must be false", initialState.assemblyStarted)

        // Advance the Governor a few steps — it must progress through task lifecycle,
        // NOT jump to assembly
        repeat(4) { step ->
            val result = governor.runGovernor(pid)
            assertTrue(
                "Step $step: Governor must return ADVANCED (still in task lifecycle), got $result",
                result == Governor.GovernorResult.ADVANCED
            )
        }

        // Assembly events must never appear in the ledger at this stage
        val currentEvents = store.loadEvents(pid)
        assertFalse("assembly_started must NOT appear during partial execution",
            currentEvents.any { it.type == EventTypes.ASSEMBLY_STARTED })
        assertFalse("assembly_validated must NOT appear during partial execution",
            currentEvents.any { it.type == EventTypes.ASSEMBLY_VALIDATED })
        assertFalse("assembly_completed must NOT appear during partial execution",
            currentEvents.any { it.type == EventTypes.ASSEMBLY_COMPLETED })

        // ReplayState confirms execution is not complete
        val midState = replay.replay(pid)
        assertFalse("executionCompleted must be false during partial execution", midState.executionCompleted)
        assertFalse("assemblyStarted must be false during partial execution", midState.assemblyStarted)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 4 — ILLEGAL TRANSITION ATTEMPT
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Purpose: Verify the system rejects invalid event sequences.
     *
     * A ledger is built with the illegal jump:
     *   execution_completed → assembly_completed  (skipping assembly_started + assembly_validated)
     *
     * Validations:
     *   - auditLedger().isValid == false
     *   - Illegal transition is detected in audit errors
     *   - ReplayTest.verifyReplay() also fails
     */
    @Test
    fun `scenario 4 - illegal transition - execution_completed to assembly_completed skips validation`() {
        val pid = "proof-s4"
        // Attempt illegal sequence: execution_completed → assembly_completed (skipping validated)
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "proof-s4")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 1.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 1)),
            // ILLEGAL: jumps directly to assembly_completed, skipping assembly_started and assembly_validated
            Event(EventTypes.ASSEMBLY_COMPLETED,  emptyMap())
        )
        val store = newStore(events)

        // LedgerAudit must reject this sequence
        val auditResult = LedgerAudit(store).auditLedger(pid)
        assertFalse("auditLedger must return isValid=false for illegal transition", auditResult.valid)
        assertTrue("Illegal transition must be reported in audit errors",
            auditResult.errors.any { "Illegal transition" in it })

        // ReplayTest invariants must also catch the inconsistency
        val verification = ReplayTest(store).verifyReplay(pid)
        assertFalse("ReplayTest.verifyReplay must return valid=false for illegal ledger",
            verification.valid)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 5 — REPLAY DETERMINISM
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Purpose: Verify replay is fully deterministic.
     *
     * A complete ledger is derived from a Governor-driven 3-contract flow.
     * The same ledger is then replayed twice to produce ReplayState A and B.
     * Every field of A and B must be identical.
     */
    @Test
    fun `scenario 5 - replay determinism - same events always produce identical ReplayState`() {
        val pid      = "proof-s5"
        val store    = newStore()
        val registry = registryWithContractor()
        val governor = Governor(store, registry)

        // Build a complete ledger via the real Governor (1-contract flow)
        governor.submitIntent(pid, "Determinism proof")
        repeat(2) { governor.runGovernor(pid) }                 // → contracts_ready
        governor.approveContracts(pid)
        // 1 ADVANCED (execution_started) + 6 (contract lifecycle) + 4 (assembly) = 11
        repeat(11) { governor.runGovernor(pid) }

        // Confirm the flow reached assembly_completed
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, store.loadEvents(pid).last().type)

        val replay = Replay(store)

        // Capture ReplayState A
        val stateA = replay.replay(pid)

        // Produce ReplayState B from the same store
        val stateB = replay.replay(pid)

        // Every field must be identical
        assertEquals("phase must match",              stateA.phase,              stateB.phase)
        assertEquals("objective must match",          stateA.objective,          stateB.objective)
        assertEquals("contractsCompleted must match", stateA.contractsCompleted, stateB.contractsCompleted)
        assertEquals("totalContracts must match",     stateA.totalContracts,     stateB.totalContracts)
        assertEquals("executionStarted must match",   stateA.executionStarted,   stateB.executionStarted)
        assertEquals("executionCompleted must match", stateA.executionCompleted, stateB.executionCompleted)
        assertEquals("assemblyStarted must match",    stateA.assemblyStarted,    stateB.assemblyStarted)
        assertEquals("assemblyValidated must match",  stateA.assemblyValidated,  stateB.assemblyValidated)
        assertEquals("assemblyCompleted must match",  stateA.assemblyCompleted,  stateB.assemblyCompleted)

        // Full structural equality
        assertEquals("ReplayState A must equal ReplayState B (full determinism)", stateA, stateB)

        // Sanity: the state must reflect the completed flow
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, stateA.phase)
        assertTrue(stateA.assemblyCompleted)
        assertTrue(stateA.assemblyValidated)
        assertTrue(stateA.executionCompleted)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 6 — MUTATION AUTHORITY ENFORCEMENT
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Purpose: Verify ONLY Governor mutates the ledger.
     *
     * Three read-only modules are called with a tracking store:
     *   AssemblyValidator, Replay, LedgerAudit, ReplayTest
     * None of them must call appendEvent.
     *
     * Governor.submitIntent() and Governor.runGovernor() are then called with a fresh
     * tracking store and each must produce exactly one appendEvent call.
     */
    @Test
    fun `scenario 6 - mutation authority - only governor calls appendEvent`() {
        val pid = "proof-s6"

        // A complete, valid ledger for read-only module testing
        val completeLedger = listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "proof-s6")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 1.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 1)),
            Event(EventTypes.ASSEMBLY_STARTED,    emptyMap()),
            Event(EventTypes.ASSEMBLY_VALIDATED,  emptyMap()),
            Event(EventTypes.ASSEMBLY_COMPLETED,  emptyMap())
        )

        // ── Test: read-only modules must NOT call appendEvent ─────────────────
        val readStore = TrackingEventRepository(completeLedger)
        val snapshotBefore = readStore.appendCallCount

        // AssemblyValidator — pure state validator
        val replayForValidation = Replay(readStore).replay(pid)
        AssemblyValidator().validate(replayForValidation)
        assertEquals("AssemblyValidator must NOT call appendEvent",
            snapshotBefore, readStore.appendCallCount)

        // Replay — pure reader
        Replay(readStore).replay(pid)
        assertEquals("Replay must NOT call appendEvent",
            snapshotBefore, readStore.appendCallCount)

        // LedgerAudit — pure audit
        LedgerAudit(readStore).auditLedger(pid)
        assertEquals("LedgerAudit must NOT call appendEvent",
            snapshotBefore, readStore.appendCallCount)

        // ReplayTest — combined check
        ReplayTest(readStore).verifyReplay(pid)
        assertEquals("ReplayTest must NOT call appendEvent",
            snapshotBefore, readStore.appendCallCount)

        // ── Test: Governor DOES call appendEvent (exactly once per advancement) ─
        val writeStore = TrackingEventRepository()
        val governor   = Governor(writeStore, registryWithContractor())

        val beforeSubmit = writeStore.appendCallCount
        governor.submitIntent(pid, "mutation authority test")
        assertEquals("submitIntent must call appendEvent exactly once",
            beforeSubmit + 1, writeStore.appendCallCount)

        val beforeAdvance = writeStore.appendCallCount
        val advanceResult = governor.runGovernor(pid)
        assertEquals("runGovernor (ADVANCED) must call appendEvent exactly once",
            beforeAdvance + 1, writeStore.appendCallCount)
        assertEquals("runGovernor must return ADVANCED for intent_submitted",
            Governor.GovernorResult.ADVANCED, advanceResult)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 7 — UI PASSIVITY CHECK
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Purpose: Verify the UI layer does not influence system logic.
     *
     * Validations:
     *   - UIViewOrchestrator output is determined solely by the input ReplayState.
     *   - Same input produces same output (deterministic, no hidden state).
     *   - After receiving a completed state the orchestrator resets correctly for idle input.
     *   - StateProjection does not mutate the ReplayState it receives.
     *   - UI does not compute or validate system logic.
     */
    @Test
    fun `scenario 7 - ui passivity - ui reads ReplayState without mutating or computing logic`() {
        val idleState = ReplayState(
            phase              = "idle",
            contractsCompleted = 0,
            totalContracts     = 0,
            executionStarted   = false,
            executionCompleted = false,
            assemblyStarted    = false,
            assemblyValidated  = false,
            objective          = null,
            assemblyCompleted  = false
        )
        val completedState = ReplayState(
            phase              = EventTypes.ASSEMBLY_COMPLETED,
            contractsCompleted = 3,
            totalContracts     = 3,
            executionStarted   = true,
            executionCompleted = true,
            assemblyStarted    = true,
            assemblyValidated  = true,
            objective          = "proof-s7-objective",
            assemblyCompleted  = true
        )

        val orchestrator = UIViewOrchestrator()

        // ── Determinism: same input → same output ─────────────────────────────
        val idle1 = orchestrator.orchestrate(idleState)
        val idle2 = orchestrator.orchestrate(idleState)
        assertEquals("UIViewOrchestrator must be deterministic for idle state",
            idle1.core, idle2.core)

        // ── UI reads state, not computes it ───────────────────────────────────
        val completedUI = orchestrator.orchestrate(completedState)
        assertTrue("UI must report isComplete when assembly_completed",
            completedUI.core.isComplete)
        assertEquals("UI phase must reflect ReplayState phase",
            EventTypes.ASSEMBLY_COMPLETED, completedUI.core.phase)
        assertEquals("UI progress must be 1.0 when all contracts complete",
            1.0f, completedUI.core.progress, 0.001f)

        // ── No hidden internal state: orchestrator resets for idle input ───────
        val idleAfterCompleted = orchestrator.orchestrate(idleState)
        assertFalse("UI must NOT carry completed state into subsequent idle call",
            idleAfterCompleted.core.isComplete)
        assertEquals("UI phase must revert to idle",
            "idle", idleAfterCompleted.core.phase)

        // ── StateProjection does NOT mutate the ReplayState ───────────────────
        val stateCopy = completedState.copy()
        StateProjection().project(completedState)
        assertEquals("ReplayState must not be mutated by StateProjection",
            stateCopy, completedState)

        // ── UI modules are all present (read-only delegates) ──────────────────
        assertTrue("contract module must be present in UI output",
            completedUI.modules.containsKey("contract"))
        assertTrue("task module must be present in UI output",
            completedUI.modules.containsKey("task"))
        assertTrue("execution module must be present in UI output",
            completedUI.modules.containsKey("execution"))
    }
}
