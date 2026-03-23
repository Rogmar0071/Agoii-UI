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
 *  - Assembly is fully derived from the event ledger (no mutation, no hidden state).
 *  - Valid execution → assembly passes.
 *  - Missing contract → assembly fails.
 *  - Incomplete execution → assembly blocked.
 *  - Deterministic output from same ledger.
 *  - No mutation occurs inside AssemblyValidator.
 */
class AssemblyTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun store(events: List<Event> = emptyList()): EventRepository {
        val repo = EventRepository()
        events.forEach { repo.appendEvent("proj", it.type, it.payload) }
        return repo
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

    // ── AssemblyValidator: valid execution → assembly passes ──────────────────

    @Test
    fun `valid single-contract execution passes assembly validation`() {
        val result = AssemblyValidator().validate(singleContractLedger())
        assertTrue("Expected valid but got errors: ${result.errors}", result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `valid three-contract execution passes assembly validation`() {
        val result = AssemblyValidator().validate(threeContractLedger())
        assertTrue("Expected valid but got errors: ${result.errors}", result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    // ── AssemblyValidator: missing contract → assembly fails ──────────────────

    @Test
    fun `missing contract_completed causes assembly validation to fail`() {
        val events = threeContractLedger().filter {
            // Remove the last contract_completed (position 3)
            !(it.type == EventTypes.CONTRACT_COMPLETED && it.payload["position"] == 3)
        }
        val result = AssemblyValidator().validate(events)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("contract") })
    }

    @Test
    fun `no contract_completed events causes assembly validation to fail`() {
        val events = singleContractLedger().filter {
            it.type != EventTypes.CONTRACT_COMPLETED
        }
        val result = AssemblyValidator().validate(events)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "no contract_completed" in it })
    }

    // ── AssemblyValidator: incomplete execution → assembly blocked ────────────

    @Test
    fun `missing execution_completed blocks assembly validation`() {
        val events = singleContractLedger().filter {
            it.type != EventTypes.EXECUTION_COMPLETED
        }
        val result = AssemblyValidator().validate(events)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "execution_completed" in it })
    }

    @Test
    fun `assembly_started before execution_completed is an illegal transition`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "obj")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 1.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.ASSEMBLY_STARTED,    emptyMap()),   // illegal — no execution_completed
            Event(EventTypes.ASSEMBLY_VALIDATED,  emptyMap())
        )
        val result = AssemblyValidator().validate(events)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "execution_completed" in it })
    }

    @Test
    fun `contract position gap causes assembly validation to fail`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 3.0)),
            // positions 1 and 3 — gap at position 2
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("position" to 1, "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("position" to 3, "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,  mapOf("position" to 3, "total" to 3)),
            Event(EventTypes.EXECUTION_COMPLETED, emptyMap()),
            Event(EventTypes.ASSEMBLY_STARTED,    emptyMap()),
            Event(EventTypes.ASSEMBLY_VALIDATED,  emptyMap())
        )
        val result = AssemblyValidator().validate(events)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "position" in it || "gap" in it })
    }

    // ── AssemblyValidator: deterministic output ───────────────────────────────

    @Test
    fun `assembly validation is deterministic — same ledger same result`() {
        val events = threeContractLedger()
        val first  = AssemblyValidator().validate(events)
        val second = AssemblyValidator().validate(events)
        assertEquals(first.isValid, second.isValid)
        assertEquals(first.errors, second.errors)
    }

    @Test
    fun `assembly validation produces no side effects — ledger unchanged`() {
        val events = singleContractLedger().toMutableList()
        val sizeBefore = events.size
        AssemblyValidator().validate(events)
        assertEquals(sizeBefore, events.size)
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

    private fun replayStateAt(events: List<Event>): ReplayState =
        Replay(store()).deriveState(events)

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
