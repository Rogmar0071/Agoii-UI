package com.agoii.mobile

import com.agoii.mobile.assembly.AssemblyValidator
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.ui.core.LedgerViewEngine
import com.agoii.mobile.ui.core.StateProjection
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the Assembly validation layer and its integration with Replay and UI.
 *
 * Rules verified:
 *  - Assembly accepts a ReplayState and returns an AssemblyResult (no mutation, no hidden state).
 *  - Valid execution → assembly passes.
 *  - Missing contract → assembly fails.
 *  - Incomplete execution → assembly blocked.
 *  - Deterministic output from same state.
 *  - No mutation occurs inside AssemblyValidator.
 */
class AssemblyTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun store(): EventRepository =
        object : EventRepository {
            private val ledger = mutableListOf<Event>()
            override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
                ledger.add(Event(type, payload))
            }
            override fun loadEvents(projectId: String): List<Event> = ledger.toList()
        }

    /** Builds a minimal but complete single-contract execution ledger. */
    private fun singleContractLedger(): List<Event> = listOf(
        Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "test")),
        Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
        Event(EventTypes.CONTRACTS_READY,     emptyMap()),
        Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
        Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 1.0)),
        Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
        Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
        Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 1)),
        Event(EventTypes.ASSEMBLY_STARTED,    emptyMap()),
        Event(EventTypes.ASSEMBLY_VALIDATED,  emptyMap())
    )

    /** Builds a complete 3-contract execution ledger. */
    private fun threeContractLedger(): List<Event> = listOf(
        Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "test")),
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
        Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 3)),
        Event(EventTypes.ASSEMBLY_STARTED,    emptyMap()),
        Event(EventTypes.ASSEMBLY_VALIDATED,  emptyMap())
    )

    private fun replayStateAt(events: List<Event>): ReplayState =
        Replay(store()).deriveState(events)

    // ── AssemblyValidator: valid execution → assembly passes ──────────────────

    @Test
    fun `valid single-contract execution passes assembly validation`() {
        val result = AssemblyValidator().validate(replayStateAt(singleContractLedger()))
        val allIssues = result.missingElements + result.failedChecks
        assertTrue("Expected valid but got issues: $allIssues", result.isValid)
        assertTrue(result.missingElements.isEmpty())
        assertTrue(result.failedChecks.isEmpty())
    }

    @Test
    fun `valid three-contract execution passes assembly validation`() {
        val result = AssemblyValidator().validate(replayStateAt(threeContractLedger()))
        val allIssues = result.missingElements + result.failedChecks
        assertTrue("Expected valid but got issues: $allIssues", result.isValid)
        assertTrue(result.missingElements.isEmpty())
        assertTrue(result.failedChecks.isEmpty())
    }

    // ── AssemblyValidator: missing contract → assembly fails ──────────────────

    @Test
    fun `missing contract_completed causes assembly validation to fail`() {
        val events = threeContractLedger().filter {
            // Remove the last contract_completed (position 3)
            !(it.type == EventTypes.CONTRACT_COMPLETED && it.payload["position"] == 3)
        }
        val result = AssemblyValidator().validate(replayStateAt(events))
        assertFalse(result.isValid)
        val allIssues = result.missingElements + result.failedChecks
        assertTrue(allIssues.any { it.contains("contract") })
    }

    @Test
    fun `no contract_completed events causes assembly validation to fail`() {
        val events = singleContractLedger().filter {
            it.type != EventTypes.CONTRACT_COMPLETED
        }
        val result = AssemblyValidator().validate(replayStateAt(events))
        assertFalse(result.isValid)
        assertTrue(result.missingElements.any { "contract_completed" in it })
    }

    // ── AssemblyValidator: incomplete execution → assembly blocked ────────────

    @Test
    fun `missing execution_completed blocks assembly validation`() {
        val events = singleContractLedger().filter {
            it.type != EventTypes.EXECUTION_COMPLETED
        }
        val result = AssemblyValidator().validate(replayStateAt(events))
        assertFalse(result.isValid)
        assertTrue(result.failedChecks.any { "execution_completed" in it })
    }

    @Test
    fun `assembly_started before execution_completed is an illegal transition`() {
        val state = ReplayState(
            phase              = "assembly_started",
            contractsCompleted = 1,
            totalContracts     = 1,
            executionStarted   = true,
            executionCompleted = false,  // no execution_completed
            assemblyStarted    = true,   // illegal — before execution_completed
            assemblyValidated  = false,
            objective          = "obj"
        )
        val result = AssemblyValidator().validate(state)
        assertFalse(result.isValid)
        assertTrue(result.failedChecks.any { "execution_completed" in it })
    }

    @Test
    fun `partial contract count causes assembly validation to fail`() {
        val state = ReplayState(
            phase              = "contract_completed",
            contractsCompleted = 2,
            totalContracts     = 3,
            executionStarted   = true,
            executionCompleted = true,
            assemblyStarted    = false,
            assemblyValidated  = false,
            objective          = "test"
        )
        val result = AssemblyValidator().validate(state)
        assertFalse(result.isValid)
        assertTrue(result.failedChecks.any { "contract" in it })
    }

    // ── AssemblyValidator: deterministic output ───────────────────────────────

    @Test
    fun `assembly validation is deterministic — same state same result`() {
        val state  = replayStateAt(threeContractLedger())
        val first  = AssemblyValidator().validate(state)
        val second = AssemblyValidator().validate(state)
        assertEquals(first.isValid,          second.isValid)
        assertEquals(first.completionStatus, second.completionStatus)
        assertEquals(first.missingElements,  second.missingElements)
        assertEquals(first.failedChecks,     second.failedChecks)
    }

    @Test
    fun `assembly validation produces no side effects — state unchanged`() {
        val state = replayStateAt(singleContractLedger())
        val phaseBefore = state.phase
        AssemblyValidator().validate(state)
        assertEquals(phaseBefore, state.phase)
    }

    // ── ReplayState: assemblyCompleted is derived from ledger ─────────────────

    @Test
    fun `ReplayState assemblyCompleted is false before assembly_completed event`() {
        val state = Replay(store()).deriveState(singleContractLedger())
        // ledger ends at assembly_validated — assemblyCompleted must be false
        assertFalse(state.assemblyCompleted)
        assertTrue(state.assemblyStarted)
        assertTrue(state.assemblyValidated)
    }

    @Test
    fun `ReplayState assemblyCompleted is true after assembly_completed event`() {
        val events = singleContractLedger() + Event(EventTypes.ASSEMBLY_COMPLETED, emptyMap())
        val state = Replay(store()).deriveState(events)
        assertTrue(state.assemblyCompleted)
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, state.phase)
    }

    @Test
    fun `ReplayState assembly flags all false on empty ledger`() {
        val state = Replay(store()).deriveState(emptyList())
        assertFalse(state.assemblyStarted)
        assertFalse(state.assemblyValidated)
        assertFalse(state.assemblyCompleted)
    }

    // ── UI integration: assembly visibility in StateProjection ────────────────

    @Test
    fun `StateProjection exposes assemblyStarted from ReplayState`() {
        val events = singleContractLedger()   // ends at assembly_validated
        val ui = StateProjection().project(replayStateAt(events))
        assertTrue(ui.assemblyStarted)
        assertTrue(ui.assemblyValidated)
        assertFalse(ui.assemblyCompleted)
    }

    @Test
    fun `StateProjection assemblyCompleted true when assembly_completed reached`() {
        val events = singleContractLedger() + Event(EventTypes.ASSEMBLY_COMPLETED, emptyMap())
        val ui = StateProjection().project(replayStateAt(events))
        assertTrue(ui.assemblyStarted)
        assertTrue(ui.assemblyValidated)
        assertTrue(ui.assemblyCompleted)
        assertTrue(ui.isComplete)
    }

    @Test
    fun `StateProjection assembly flags all false on idle state`() {
        val idleState = ReplayState(
            phase = "idle",
            contractsCompleted = 0,
            totalContracts = 0,
            executionStarted = false,
            executionCompleted = false,
            assemblyStarted = false,
            assemblyValidated = false,
            objective = null,
            assemblyCompleted = false
        )
        val ui = StateProjection().project(idleState)
        assertFalse(ui.assemblyStarted)
        assertFalse(ui.assemblyValidated)
        assertFalse(ui.assemblyCompleted)
    }

    // ── LedgerViewEngine: assembly visibility ─────────────────────────────────

    @Test
    fun `LedgerViewEngine assembly properties are false before any render`() {
        val engine = LedgerViewEngine()
        assertFalse(engine.assemblyStarted)
        assertFalse(engine.assemblyValidated)
        assertFalse(engine.assemblyCompleted)
    }

    @Test
    fun `LedgerViewEngine reflects assembly state after render`() {
        val engine = LedgerViewEngine()
        val state = replayStateAt(singleContractLedger())  // assembly_started + validated
        engine.render(state)
        assertTrue(engine.assemblyStarted)
        assertTrue(engine.assemblyValidated)
        assertFalse(engine.assemblyCompleted)
    }

    @Test
    fun `LedgerViewEngine assemblyCompleted true when full assembly reached`() {
        val engine = LedgerViewEngine()
        val events = singleContractLedger() + Event(EventTypes.ASSEMBLY_COMPLETED, emptyMap())
        engine.render(replayStateAt(events))
        assertTrue(engine.assemblyCompleted)
    }
}
