package com.agoii.mobile

import com.agoii.mobile.assembly.AssemblyValidator
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerAudit
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.core.ReplayTest
import com.agoii.mobile.governor.Governor
import org.junit.Assert.*
import org.junit.Test

/**
 * AGOII_PROOF_PHASE_EXECUTION_SCRIPT_V1
 *
 * Validates all 5 proof phase scenarios:
 *
 *   SCENARIO_1: HAPPY_PATH (FULL_SUCCESS)    → SYSTEM_COMPLETED
 *   SCENARIO_2: MISSING_CONTRACT (FAIL)      → SYSTEM_BLOCKED
 *   SCENARIO_3: PARTIAL_EXECUTION (FAIL)     → SYSTEM_BLOCKED
 *   SCENARIO_4: ILLEGAL_TRANSITION (FAIL)    → SYSTEM_REJECTED
 *   SCENARIO_5: REPLAY_INTEGRITY (CRITICAL)  → REPLAY_MATCHES_LEDGER
 *
 * GLOBAL_RULES:
 *  - Governor controls every transition (no direct state mutation).
 *  - Assembly triggered at assembly_started.
 *  - assembly_completed ONLY after assembly_validated.
 *  - Replay is the sole source of truth; same ledger → same state.
 */
class ProofPhaseTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun store(events: List<Event> = emptyList()): EventRepository =
        InMemoryProofEventRepository(events)

    /**
     * Canonical happy-path ledger (simplified — no task lifecycle) for N contracts.
     *   intent_submitted → contracts_generated → contracts_ready → contracts_approved
     *   → execution_started
     *   → contract_started (xN) → contract_completed (xN)
     *   → execution_completed
     *   → assembly_started → assembly_validated → assembly_completed
     */
    private fun happyPathLedger(totalContracts: Int = 3): List<Event> {
        val events = mutableListOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "Build a simple app")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to totalContracts.toDouble())),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to totalContracts.toDouble()))
        )
        for (i in 1..totalContracts) {
            events += Event(
                EventTypes.CONTRACT_STARTED,
                mapOf("contract_id" to "contract_$i", "position" to i, "total" to totalContracts)
            )
            events += Event(
                EventTypes.CONTRACT_COMPLETED,
                mapOf("contract_id" to "contract_$i", "position" to i, "total" to totalContracts)
            )
        }
        events += listOf(
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to totalContracts)),
            Event(EventTypes.ASSEMBLY_STARTED,    emptyMap()),
            Event(EventTypes.ASSEMBLY_VALIDATED,  emptyMap()),
            Event(EventTypes.ASSEMBLY_COMPLETED,  emptyMap())
        )
        return events
    }

    // ── SCENARIO_1: HAPPY_PATH (FULL_SUCCESS) ─────────────────────────────────

    /**
     * SCENARIO_1 — Part A
     *
     * Governor drives the full assembly pipeline after all contracts complete.
     * Validates:
     *  - assembly_started → assembly_validated → assembly_completed via Governor
     *  - assembly_completed is NOT emitted until assembly_validated is emitted
     *  - Terminal result is SYSTEM_COMPLETED
     */
    @Test
    fun `SCENARIO_1 governor drives full assembly pipeline to system completed`() {
        val executionLedger = listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "Build a simple app")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 3.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_2", "position" to 2, "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_2", "position" to 2, "total" to 3)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_3", "position" to 3, "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_3", "position" to 3, "total" to 3)),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 3))
        )
        val s = store(executionLedger)
        val gov = Governor(s)

        // execution_completed → assembly_started (Governor transition)
        assertEquals(Governor.GovernorResult.ADVANCED, gov.runGovernor("proj"))
        assertEquals(EventTypes.ASSEMBLY_STARTED, s.loadEvents("proj").last().type)

        // assembly_started → assembly_validated (Governor gate via AssemblyValidator)
        assertEquals(Governor.GovernorResult.ADVANCED, gov.runGovernor("proj"))
        assertEquals(EventTypes.ASSEMBLY_VALIDATED, s.loadEvents("proj").last().type)

        // assembly_validated → assembly_completed (Governor transition)
        assertEquals(Governor.GovernorResult.ADVANCED, gov.runGovernor("proj"))
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, s.loadEvents("proj").last().type)

        // SYSTEM_COMPLETED — terminal state, no further events
        assertEquals(Governor.GovernorResult.COMPLETED, gov.runGovernor("proj"))

        // Full ledger audit must pass
        val audit = LedgerAudit(s).auditLedger("proj")
        assertTrue("Audit must pass. Errors: ${audit.errors}", audit.valid)
    }

    /**
     * SCENARIO_1 — Part B
     *
     * assembly_completed is ONLY emitted after assembly_validated in the event stream.
     */
    @Test
    fun `SCENARIO_1 assembly_completed only emitted after assembly_validated`() {
        val ledger = happyPathLedger()
        val types = ledger.map { it.type }

        val validatedIdx = types.indexOf(EventTypes.ASSEMBLY_VALIDATED)
        val completedIdx = types.indexOf(EventTypes.ASSEMBLY_COMPLETED)

        assertTrue("assembly_validated must appear in ledger",  validatedIdx >= 0)
        assertTrue("assembly_completed must appear in ledger",  completedIdx >= 0)
        assertTrue(
            "assembly_validated must precede assembly_completed",
            validatedIdx < completedIdx
        )
    }

    /**
     * SCENARIO_1 — Part C
     *
     * Full happy-path ledger passes both LedgerAudit and Replay invariants.
     * Final ReplayState matches all expected SYSTEM_COMPLETED values.
     */
    @Test
    fun `SCENARIO_1 full happy-path ledger passes audit and replay invariants`() {
        val s = store(happyPathLedger())

        val audit = LedgerAudit(s).auditLedger("proj")
        assertTrue("Audit must pass. Errors: ${audit.errors}", audit.valid)

        val verification = ReplayTest(s).verifyReplay("proj")
        assertTrue(
            "ReplayTest invariants must pass. Errors: ${verification.invariantErrors} | " +
            "Audit: ${verification.auditResult.errors}",
            verification.valid
        )

        val state = verification.replayState
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, state.phase)
        assertEquals(3, state.contractsCompleted)
        assertEquals(3, state.totalContracts)
        assertTrue(state.executionStarted)
        assertTrue(state.executionCompleted)
        assertTrue(state.assemblyStarted)
        assertTrue(state.assemblyValidated)
        assertTrue(state.assemblyCompleted)
    }

    // ── SCENARIO_2: MISSING_CONTRACT (FAIL) ──────────────────────────────────

    /**
     * SCENARIO_2 — Part A
     *
     * contractsCompleted (2) < totalContracts (3) at assembly_started.
     * Governor must return NO_EVENT (SYSTEM_BLOCKED).
     * assembly_validated and assembly_completed must NOT be emitted.
     */
    @Test
    fun `SCENARIO_2 missing contract blocks assembly - governor returns NO_EVENT`() {
        val ledger = listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "Build a simple app")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 3.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 3)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_2", "position" to 2, "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_2", "position" to 2, "total" to 3)),
            // contract_3 is missing — contractsCompleted(2) < totalContracts(3)
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 2)),
            Event(EventTypes.ASSEMBLY_STARTED,    emptyMap())
        )
        val s = store(ledger)

        // SYSTEM_BLOCKED — Governor cannot emit assembly_validated
        assertEquals(Governor.GovernorResult.NO_EVENT, Governor(s).runGovernor("proj"))

        val types = s.loadEvents("proj").map { it.type }
        assertFalse("assembly_validated must NOT be emitted", EventTypes.ASSEMBLY_VALIDATED in types)
        assertFalse("assembly_completed must NOT be emitted", EventTypes.ASSEMBLY_COMPLETED in types)
    }

    /**
     * SCENARIO_2 — Part B
     *
     * AssemblyValidator directly confirms that a missing contract causes failure.
     */
    @Test
    fun `SCENARIO_2 AssemblyValidator confirms missing contract failure`() {
        val state = ReplayState(
            phase              = "assembly_started",
            contractsCompleted = 2,
            totalContracts     = 3,
            executionStarted   = true,
            executionCompleted = true,
            assemblyStarted    = true,
            assemblyValidated  = false,
            objective          = "Build a simple app"
        )
        val result = AssemblyValidator().validate(state)
        assertFalse("Assembly must fail when contract is missing", result.isValid)
        assertTrue(
            "failedChecks must mention contract count mismatch",
            result.failedChecks.any { "contract" in it }
        )
    }

    // ── SCENARIO_3: PARTIAL_EXECUTION (FAIL) ─────────────────────────────────

    /**
     * SCENARIO_3 — Part A
     *
     * executionStarted=true but executionCompleted=false when assembly_started is reached.
     * Governor must return NO_EVENT (SYSTEM_BLOCKED).
     */
    @Test
    fun `SCENARIO_3 partial execution blocks assembly - governor returns NO_EVENT`() {
        val ledger = listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "Build a simple app")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 1.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            // execution_completed is MISSING — execution not closed
            Event(EventTypes.ASSEMBLY_STARTED,    emptyMap())
        )
        val s = store(ledger)

        // SYSTEM_BLOCKED — AssemblyValidator blocks because executionCompleted=false
        assertEquals(Governor.GovernorResult.NO_EVENT, Governor(s).runGovernor("proj"))

        val types = s.loadEvents("proj").map { it.type }
        assertFalse("assembly_validated must NOT be emitted", EventTypes.ASSEMBLY_VALIDATED in types)
        assertFalse("assembly_completed must NOT be emitted", EventTypes.ASSEMBLY_COMPLETED in types)
    }

    /**
     * SCENARIO_3 — Part B
     *
     * AssemblyValidator directly confirms that incomplete execution causes failure.
     */
    @Test
    fun `SCENARIO_3 AssemblyValidator confirms partial execution failure`() {
        val state = ReplayState(
            phase              = "assembly_started",
            contractsCompleted = 1,
            totalContracts     = 1,
            executionStarted   = true,
            executionCompleted = false,   // execution not yet closed
            assemblyStarted    = true,
            assemblyValidated  = false,
            objective          = "Build a simple app"
        )
        val result = AssemblyValidator().validate(state)
        assertFalse("Assembly must fail with partial execution", result.isValid)
        assertTrue(
            "failedChecks must mention execution_completed",
            result.failedChecks.any { "execution_completed" in it }
        )
    }

    // ── SCENARIO_4: ILLEGAL_TRANSITION (FAIL) ────────────────────────────────

    /**
     * SCENARIO_4 — Part A
     *
     * Illegal transition: assembly_started → assembly_completed (assembly_validated skipped).
     * LedgerAudit must reject this as an illegal transition.
     * State must be SYSTEM_REJECTED.
     */
    @Test
    fun `SCENARIO_4 illegal transition - skipping assembly_validated is rejected by ledger audit`() {
        val ledger = listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "Build a simple app")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 1.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 1)),
            Event(EventTypes.ASSEMBLY_STARTED,    emptyMap()),
            // ILLEGAL: assembly_validated skipped — jumps directly to assembly_completed
            Event(EventTypes.ASSEMBLY_COMPLETED,  emptyMap())
        )
        val audit = LedgerAudit(store(ledger)).auditLedger("proj")
        assertFalse("LedgerAudit must FAIL for illegal transition", audit.valid)
        assertTrue(
            "Errors must mention Illegal transition",
            audit.errors.any { "Illegal transition" in it }
        )
    }

    /**
     * SCENARIO_4 — Part B
     *
     * Illegal transition: execution_completed → assembly_completed
     * (both assembly_started and assembly_validated are skipped).
     * LedgerAudit must reject this.
     */
    @Test
    fun `SCENARIO_4 illegal transition - jumping from execution_completed to assembly_completed is rejected`() {
        val ledger = listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "Build a simple app")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 1.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 1)),
            // ILLEGAL: assembly_started and assembly_validated both skipped
            Event(EventTypes.ASSEMBLY_COMPLETED,  emptyMap())
        )
        val audit = LedgerAudit(store(ledger)).auditLedger("proj")
        assertFalse("LedgerAudit must FAIL for illegal skip", audit.valid)
        assertTrue(
            "Errors must mention Illegal transition",
            audit.errors.any { "Illegal transition" in it }
        )
    }

    /**
     * SCENARIO_4 — Part C
     *
     * AssemblyValidator also rejects a state where assembly_validated appeared
     * before assembly_started (structural integrity failure).
     */
    @Test
    fun `SCENARIO_4 assembly_validated before assembly_started is rejected by AssemblyValidator`() {
        val state = ReplayState(
            phase              = "assembly_validated",
            contractsCompleted = 1,
            totalContracts     = 1,
            executionStarted   = true,
            executionCompleted = true,
            assemblyStarted    = false,  // missing assembly_started
            assemblyValidated  = true,   // illegal — cannot be true before assemblyStarted
            objective          = "Build a simple app"
        )
        val result = AssemblyValidator().validate(state)
        assertFalse("AssemblyValidator must FAIL for illegal structural state", result.isValid)
        assertTrue(
            "failedChecks must mention assembly_validated or assembly_started",
            result.failedChecks.any { "assembly_validated" in it || "assembly_started" in it }
        )
    }

    // ── SCENARIO_5: REPLAY_INTEGRITY (CRITICAL) ───────────────────────────────

    /**
     * SCENARIO_5 — Part A
     *
     * After running the happy path, clear the Replay instance (simulate UI state reset)
     * and rebuild state ONLY from the ledger. The reconstructed ReplayState must
     * exactly match every tracked field.
     */
    @Test
    fun `SCENARIO_5 replay state rebuilt from ledger exactly matches original state`() {
        val ledger = happyPathLedger(totalContracts = 3)
        val s = store(ledger)

        // Step 1: Derive initial state (simulates live system)
        val initialState = Replay(s).replay("proj")

        // Step 2: Simulate "clear UI state" — create a fresh Replay instance
        val freshReplay = Replay(s)

        // Step 3: Rebuild state ONLY from the same immutable ledger
        val replayedState = freshReplay.replay("proj")

        // Step 4: Every tracked field must match exactly (REPLAY_MATCHES_LEDGER)
        assertEquals("phase mismatch",              initialState.phase,               replayedState.phase)
        assertEquals("contractsCompleted mismatch", initialState.contractsCompleted,  replayedState.contractsCompleted)
        assertEquals("totalContracts mismatch",     initialState.totalContracts,      replayedState.totalContracts)
        assertEquals("executionStarted mismatch",   initialState.executionStarted,    replayedState.executionStarted)
        assertEquals("executionCompleted mismatch", initialState.executionCompleted,  replayedState.executionCompleted)
        assertEquals("assemblyStarted mismatch",    initialState.assemblyStarted,     replayedState.assemblyStarted)
        assertEquals("assemblyValidated mismatch",  initialState.assemblyValidated,   replayedState.assemblyValidated)
        assertEquals("assemblyCompleted mismatch",  initialState.assemblyCompleted,   replayedState.assemblyCompleted)

        // Verify the expected final values (SYSTEM_COMPLETED)
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, replayedState.phase)
        assertEquals(3, replayedState.contractsCompleted)
        assertEquals(3, replayedState.totalContracts)
        assertTrue(replayedState.executionStarted)
        assertTrue(replayedState.executionCompleted)
        assertTrue(replayedState.assemblyStarted)
        assertTrue(replayedState.assemblyValidated)
        assertTrue(replayedState.assemblyCompleted)
    }

    /**
     * SCENARIO_5 — Part B
     *
     * Replay is deterministic: same ledger always produces exactly the same ReplayState.
     */
    @Test
    fun `SCENARIO_5 replay is deterministic - same ledger always produces same state`() {
        val s = store(happyPathLedger())

        val state1 = Replay(s).replay("proj")
        val state2 = Replay(s).replay("proj")
        val state3 = Replay(s).replay("proj")

        assertEquals("First and second replay must match",  state1, state2)
        assertEquals("Second and third replay must match",  state2, state3)
    }

    /**
     * SCENARIO_5 — Part C
     *
     * ReplayTest cross-validates the ledger audit and replay invariants.
     * Both must pass for a valid completed system.
     */
    @Test
    fun `SCENARIO_5 replay test invariants pass for completed system`() {
        val s = store(happyPathLedger())
        val verification = ReplayTest(s).verifyReplay("proj")
        assertTrue(
            "ReplayTest invariants must pass. Errors: ${verification.invariantErrors}",
            verification.valid
        )
        assertTrue(
            "LedgerAudit must pass. Errors: ${verification.auditResult.errors}",
            verification.auditResult.valid
        )
        assertEquals(0, verification.invariantErrors.size)
        assertEquals(0, verification.auditResult.errors.size)
    }
}

private class InMemoryProofEventRepository(initial: List<Event> = emptyList()) : EventRepository {
    private val ledger = initial.toMutableList()
    override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        ledger.add(Event(type, payload))
    }
    override fun loadEvents(projectId: String): List<Event> = ledger.toList()
}
