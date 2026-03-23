package com.agoii.mobile

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.Governor
import com.agoii.mobile.core.LedgerAudit
import com.agoii.mobile.core.Replay
import com.agoii.mobile.core.ReplayTest
import com.agoii.mobile.core.SystemVerificationContract
import com.agoii.mobile.contractor.ContractorCapabilityVector
import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.VerificationStatus
import com.agoii.mobile.irs.ConsensusRule
import com.agoii.mobile.irs.EvidenceRef
import com.agoii.mobile.irs.EvidenceValidator
import com.agoii.mobile.irs.FailureType
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
import com.agoii.mobile.irs.RiskLevel
import com.agoii.mobile.irs.SimulationRuleResult
import com.agoii.mobile.irs.CcfScore
import com.agoii.mobile.irs.IrsAuditReport
import com.agoii.mobile.irs.reality.ContradictionEngine
import com.agoii.mobile.irs.reality.EvidenceScoringEngine
import com.agoii.mobile.irs.reality.RealityKnowledgeGateway
import com.agoii.mobile.irs.reality.RealitySimulationEngine
import com.agoii.mobile.irs.reality.RealityValidator
import com.agoii.mobile.irs.scout.ConstraintScout
import com.agoii.mobile.irs.scout.DependencyScout
import com.agoii.mobile.irs.scout.EnvironmentScout
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
        // Governor needs a contractor in the registry to proceed through task lifecycle
        val reg = registryWithContractor()
        assertEquals(Governor.GovernorResult.ADVANCED, Governor(s, reg).runGovernor("proj"))
        val last = s.loadEvents("proj").last()
        // contract_started now triggers task_assigned (not contract_completed directly)
        assertEquals(EventTypes.TASK_ASSIGNED, last.type)
        assertEquals("contract_1-step1", last.payload["taskId"])
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
        val reg = registryWithContractor()
        val gov = Governor(s, reg)

        // Per contract: contract_started, task_assigned, task_started,
        //               task_completed, task_validated, contract_completed = 6 steps
        // 3 contracts × 6 steps = 18 ADVANCED
        // + execution_completed, assembly_started, assembly_validated = 3 ADVANCED
        // + assembly_completed (emitted via VALID_TRANSITIONS) = 1 ADVANCED
        // Total = 22 ADVANCED steps, then COMPLETED
        repeat(22) { step ->
            assertEquals(
                "Expected ADVANCED at step $step",
                Governor.GovernorResult.ADVANCED, gov.runGovernor("proj")
            )
        }
        assertEquals(Governor.GovernorResult.COMPLETED, gov.runGovernor("proj"))

        val events = s.loadEvents("proj")
        assertEquals(EventTypes.ASSEMBLY_COMPLETED, events.last().type)
        assertEquals(3, events.count { it.type == EventTypes.CONTRACT_STARTED })
        assertEquals(3, events.count { it.type == EventTypes.TASK_ASSIGNED })
        assertEquals(3, events.count { it.type == EventTypes.TASK_STARTED })
        assertEquals(3, events.count { it.type == EventTypes.TASK_COMPLETED })
        assertEquals(3, events.count { it.type == EventTypes.TASK_VALIDATED })
        assertEquals(3, events.count { it.type == EventTypes.CONTRACT_COMPLETED })
        assertEquals(1, events.count { it.type == EventTypes.EXECUTION_COMPLETED })
        assertEquals(1, events.count { it.type == EventTypes.ASSEMBLY_STARTED })
        assertEquals(1, events.count { it.type == EventTypes.ASSEMBLY_VALIDATED })
        // Must not contain the removed event type
        assertEquals(0, events.count { it.type == "contract_executed" })
    }

    // ── Governor Lock Certification tests ────────────────────────────────────

    /**
     * GOVERNOR TIGHTEN — G1: VC is fixed at 5 (deterministic baseline).
     */
    @Test
    fun `governor VC is fixed at 5`() {
        assertEquals("Governor.VC must be deterministic constant 5", 5, Governor.VC)
    }

    /**
     * GOVERNOR TIGHTEN — G3/G5: CSL blocks issuance at position 4 (EL=6 > VC=5).
     * Verifies the natural enforcement boundary: positions 1-3 allowed, position 4 blocked.
     */
    @Test
    fun `governor returns DRIFT when CSL blocks contract at position 4`() {
        // Build ledger up to the point where position 4 would be issued
        // 3 contracts complete → execution_completed is issued (not DRIFT, since position 4 is never started)
        // To trigger the position-4 gate we need total > 3 and position=3 completed
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 4.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 4.0)),
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 4)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 4)),
            Event("contract_started",    mapOf("contract_id" to "contract_2", "position" to 2, "total" to 4)),
            Event("contract_completed",  mapOf("contract_id" to "contract_2", "position" to 2, "total" to 4)),
            Event("contract_started",    mapOf("contract_id" to "contract_3", "position" to 3, "total" to 4)),
            Event("contract_completed",  mapOf("contract_id" to "contract_3", "position" to 3, "total" to 4))
        )
        val s = store(events)
        // EL (Execution Load) = surface.weight + executionCount + 2*conditionCount
        // Position 4: EL = 2 (LG weight) + 4 (position) + 0 (conditionCount=0) = 6 > VC=5 → CSL rejects → DRIFT
        assertEquals(Governor.GovernorResult.DRIFT, Governor(s).runGovernor("proj"))
        // Ledger must NOT be extended
        assertEquals(11, s.loadEvents("proj").size)
    }

    /**
     * GOVERNOR TIGHTEN — G2: SSM gates execution at execution_started for position 1 (allowed).
     * Ensures positions 1-3 pass CSL (EL ≤ VC=5).
     */
    @Test
    fun `governor allows contracts at positions 1 through 3 within VC`() {
        // Position 1: EL=3, Position 2: EL=4, Position 3: EL=5 — all ≤ VC=5
        val events = listOf(
            Event("intent_submitted",    mapOf("objective" to "obj")),
            Event("contracts_generated", mapOf("total" to 3.0)),
            Event("contracts_ready",     emptyMap()),
            Event("contracts_approved",  emptyMap()),
            Event("execution_started",   mapOf("total_contracts" to 3.0))
        )
        val s   = store(events)
        val reg = registryWithContractor()
        val gov = Governor(s, reg)

        // 3 contracts × 6 steps + execution_completed + assembly pipeline (3) = 22 ADVANCED total
        repeat(22) { step ->
            assertEquals("Expected ADVANCED at step $step", Governor.GovernorResult.ADVANCED, gov.runGovernor("proj"))
        }
        assertEquals(Governor.GovernorResult.COMPLETED, gov.runGovernor("proj"))
    }

    /**
     * LOCK STEP 1 — EVENT MODEL LOCK
     * Verifies EventTypes.ALL is frozen: exactly the 18 defined event types
     * (11 core pipeline + 7 execution engine lifecycle types), no more, no less.
     */
    @Test
    fun `lock - EventTypes ALL is frozen with exactly the 18 locked event types`() {
        val locked = setOf(
            // Core pipeline (original 11)
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
            "assembly_completed",
            // Execution engine lifecycle (added by AGOII-EXECUTION-ENGINE-01)
            "task_assigned",
            "task_started",
            "task_completed",
            "task_validated",
            "task_failed",
            "contractor_reassigned",
            "contract_failed"
        )
        assertEquals("EventTypes.ALL must contain exactly 18 locked event types", 18, EventTypes.ALL.size)
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
     * Validates the new canonical event sequence (with task lifecycle) for a 2-contract flow:
     *   intent_submitted → contracts_generated → contracts_ready → contracts_approved
     *   → execution_started
     *   → contract_started (1)
     *     → task_assigned → task_started → task_completed → task_validated
     *   → contract_completed (1)
     *   → contract_started (2)
     *     → task_assigned → task_started → task_completed → task_validated
     *   → contract_completed (2)
     *   → execution_completed → assembly_started → assembly_validated → assembly_completed
     * ASSERTS: exact event count, exact order, audit = VALID, replay = VALID.
     */
    @Test
    fun `lock - canonical 2-contract full flow passes audit and replay`() {
        val contractorId = "test-contractor"
        val expectedOrder = listOf(
            "intent_submitted",
            "contracts_generated",
            "contracts_ready",
            "contracts_approved",
            "execution_started",
            // contract 1
            "contract_started",
            "task_assigned",
            "task_started",
            "task_completed",
            "task_validated",
            "contract_completed",
            // contract 2
            "contract_started",
            "task_assigned",
            "task_started",
            "task_completed",
            "task_validated",
            "contract_completed",
            // assembly
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
            // contract 1
            Event("contract_started",    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 2)),
            Event("task_assigned",       mapOf("taskId" to "contract_1-step1", "contractorId" to contractorId, "contract_id" to "contract_1", "position" to 1, "total" to 2)),
            Event("task_started",        mapOf("taskId" to "contract_1-step1", "contractorId" to contractorId, "contract_id" to "contract_1", "position" to 1, "total" to 2)),
            Event("task_completed",      mapOf("taskId" to "contract_1-step1", "contractorId" to contractorId, "contract_id" to "contract_1", "position" to 1, "total" to 2)),
            Event("task_validated",      mapOf("taskId" to "contract_1-step1", "contract_id" to "contract_1", "position" to 1, "total" to 2)),
            Event("contract_completed",  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 2)),
            // contract 2
            Event("contract_started",    mapOf("contract_id" to "contract_2", "position" to 2, "total" to 2)),
            Event("task_assigned",       mapOf("taskId" to "contract_2-step1", "contractorId" to contractorId, "contract_id" to "contract_2", "position" to 2, "total" to 2)),
            Event("task_started",        mapOf("taskId" to "contract_2-step1", "contractorId" to contractorId, "contract_id" to "contract_2", "position" to 2, "total" to 2)),
            Event("task_completed",      mapOf("taskId" to "contract_2-step1", "contractorId" to contractorId, "contract_id" to "contract_2", "position" to 2, "total" to 2)),
            Event("task_validated",      mapOf("taskId" to "contract_2-step1", "contract_id" to "contract_2", "position" to 2, "total" to 2)),
            Event("contract_completed",  mapOf("contract_id" to "contract_2", "position" to 2, "total" to 2)),
            // assembly
            Event("execution_completed", mapOf("contracts_completed" to 2)),
            Event("assembly_started",    emptyMap()),
            Event("assembly_validated",  emptyMap()),
            Event("assembly_completed",  emptyMap())
        )

        // Exact event count
        assertEquals("Canonical 2-contract flow must have exactly 21 events", 21, ledger.size)

        // Exact order — no duplicates, no skips
        ledger.forEachIndexed { i, event ->
            assertEquals("Event[$i] type mismatch", expectedOrder[i], event.type)
        }

        val s = store(ledger)

        // Audit must pass — all transitions legal
        val audit = LedgerAudit(s).auditLedger("proj")
        assertTrue("Audit must be VALID. Errors: ${audit.errors}", audit.valid)
        assertEquals(21, audit.checkedEvents)

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
     * Create a [ContractorRegistry] pre-populated with one verified contractor.
     * Used by Governor tests that need to execute the task lifecycle.
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
            id                = "test-contractor",
            capabilities      = cap,
            verificationCount = 1,
            successCount      = 9,
            failureCount      = 1,
            status            = VerificationStatus.VERIFIED,
            source            = "test"
        )
        return ContractorRegistry().also { it.registerVerified(profile) }
    }
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

    /**
     * Generic evidence for tests that don't exercise EvidenceValidator.
     * Uses a source name ("manual") that will not pass the relevance check.
     */
    private fun fullEvidence() = mapOf(
        "objective"   to listOf(evidenceRef),
        "constraints" to listOf(evidenceRef),
        "environment" to listOf(evidenceRef),
        "resources"   to listOf(evidenceRef)
    )

    /**
     * Domain-relevant evidence for orchestrator integration tests that go through
     * EvidenceValidator. Each source name contains a keyword recognised by the validator.
     */
    private fun relevantEvidence() = mapOf(
        "objective"   to listOf(EvidenceRef(id = "ev-obj-01",  source = "objective-spec")),
        "constraints" to listOf(EvidenceRef(id = "ev-con-01",  source = "constraint-review")),
        "environment" to listOf(EvidenceRef(id = "ev-env-01",  source = "environment-audit")),
        "resources"   to listOf(EvidenceRef(id = "ev-res-01",  source = "resource-inventory"))
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
        orchestrator.createSession("s1", fullFields(), relevantEvidence(), majorityConfig())

        var result = orchestrator.step("s1")
        assertFalse(result.terminal)
        assertEquals(IrsStage.GAP_DETECTION, result.executedStage)

        result = orchestrator.step("s1")
        assertFalse(result.terminal)
        assertEquals(IrsStage.SCOUTING, result.executedStage)

        result = orchestrator.step("s1")
        assertFalse(result.terminal)
        assertEquals(IrsStage.EVIDENCE_VALIDATION, result.executedStage)

        result = orchestrator.step("s1")
        assertFalse(result.terminal)
        assertEquals(IrsStage.REALITY_VALIDATION, result.executedStage)

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
        orchestrator.createSession("s3", fullFields(), relevantEvidence(), majorityConfig())

        repeat(8) { orchestrator.step("s3") }

        val history = orchestrator.replayHistory("s3")
        assertEquals(8, history.size)
        assertEquals(IrsStage.GAP_DETECTION, history[0].stage)
        assertEquals(IrsStage.CERTIFICATION, history[7].stage)
    }

    @Test
    fun `IrsOrchestrator returns terminal state on repeated step calls after completion`() {
        val orchestrator = IrsOrchestrator()
        orchestrator.createSession("s4", fullFields(), relevantEvidence(), majorityConfig())

        repeat(8) { orchestrator.step("s4") }
        val extraCall = orchestrator.step("s4")
        assertTrue(extraCall.terminal)
        assertTrue(extraCall.orchestratorResult is OrchestratorResult.Certified)
    }

    // ── IRS-04: EnvironmentScout tests ────────────────────────────────────────

    @Test
    fun `EnvironmentScout returns HIGH confidence for known platform with evidence`() {
        val scout  = EnvironmentScout()
        val intent = ReconstructionEngine().reconstruct(fullFields(), fullEvidence())
        val result = scout.scout(intent)
        assertTrue(result.confidence > 0.7)
        assertTrue(result.findings.any { it.severity == "INFO" })
        assertTrue(result.sourceTrace.any { it.contains("cloud") })
    }

    @Test
    fun `EnvironmentScout returns LOW confidence for blank environment`() {
        val scout  = EnvironmentScout()
        val intent = ReconstructionEngine().reconstruct(
            mapOf("objective" to "x", "constraints" to "y", "environment" to "", "resources" to "z"),
            fullEvidence()
        )
        val result = scout.scout(intent)
        assertTrue(result.confidence <= 0.4)
        assertTrue(result.findings.any { it.severity == "ERROR" })
    }

    @Test
    fun `EnvironmentScout marks unknown platform as MEDIUM confidence when evidence present`() {
        val scout  = EnvironmentScout()
        val intent = ReconstructionEngine().reconstruct(
            mapOf("objective" to "x", "constraints" to "y", "environment" to "mainframe", "resources" to "z"),
            fullEvidence()
        )
        val result = scout.scout(intent)
        assertTrue(result.confidence in 0.4..0.7)
    }

    // ── IRS-04: DependencyScout tests ─────────────────────────────────────────

    @Test
    fun `DependencyScout returns HIGH confidence for known tool with evidence`() {
        val scout  = DependencyScout()
        val intent = ReconstructionEngine().reconstruct(
            mapOf("objective" to "Build", "constraints" to "use gradle", "environment" to "cloud", "resources" to "team"),
            fullEvidence()
        )
        val result = scout.scout(intent)
        assertTrue(result.confidence > 0.7)
        assertTrue(result.findings.any { it.description.contains("gradle") })
    }

    @Test
    fun `DependencyScout returns LOW confidence when no tools or evidence present`() {
        val scout  = DependencyScout()
        val intent = ReconstructionEngine().reconstruct(
            mapOf("objective" to "Build", "constraints" to "it must work", "environment" to "cloud", "resources" to "team"),
            emptyMap()
        )
        val result = scout.scout(intent)
        assertTrue(result.confidence <= 0.4)
    }

    // ── IRS-04: ConstraintScout tests ─────────────────────────────────────────

    @Test
    fun `ConstraintScout returns HIGH confidence when constraints are realistic and evidence-backed`() {
        val scout  = ConstraintScout()
        val intent = ReconstructionEngine().reconstruct(fullFields(), fullEvidence())
        val result = scout.scout(intent)
        assertTrue(result.confidence > 0.7)
        assertTrue(result.findings.none { it.severity == "ERROR" })
    }

    @Test
    fun `ConstraintScout detects real-time vs serverless contradiction`() {
        val scout  = ConstraintScout()
        val intent = ReconstructionEngine().reconstruct(
            mapOf("objective" to "Build", "constraints" to "real-time processing", "environment" to "serverless", "resources" to "team"),
            fullEvidence()
        )
        val result = scout.scout(intent)
        assertTrue(result.confidence <= 0.4)
        assertTrue(result.findings.any { it.severity == "ERROR" && it.description.contains("real-time") })
    }

    @Test
    fun `ConstraintScout detects offline vs cloud contradiction`() {
        val scout  = ConstraintScout()
        val intent = ReconstructionEngine().reconstruct(
            mapOf("objective" to "Build", "constraints" to "must work offline", "environment" to "cloud", "resources" to "team"),
            fullEvidence()
        )
        val result = scout.scout(intent)
        assertTrue(result.confidence <= 0.4)
        assertTrue(result.findings.any { it.severity == "ERROR" && it.description.contains("offline") })
    }

    @Test
    fun `ConstraintScout returns LOW confidence for blank constraints`() {
        val scout  = ConstraintScout()
        val intent = ReconstructionEngine().reconstruct(
            mapOf("objective" to "Build", "constraints" to "", "environment" to "cloud", "resources" to "team"),
            fullEvidence()
        )
        val result = scout.scout(intent)
        assertTrue(result.confidence <= 0.4)
        assertTrue(result.findings.any { it.severity == "ERROR" })
    }

    // ── IRS-04: EvidenceValidator tests ───────────────────────────────────────

    @Test
    fun `EvidenceValidator passes when all four dimensions are satisfied`() {
        val validator = EvidenceValidator()
        val intent    = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val result    = validator.validate(intent)
        assertTrue(result.valid)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `EvidenceValidator fails presence check when a field has no evidence`() {
        val validator = EvidenceValidator()
        val intent    = ReconstructionEngine().reconstruct(fullFields(), emptyMap())
        val result    = validator.validate(intent)
        assertFalse(result.valid)
        assertTrue(result.reasons.any { it.contains("presence") })
    }

    @Test
    fun `EvidenceValidator fails relevance check when source does not match field domain`() {
        val validator = EvidenceValidator()
        // "manual" source doesn't match any domain keyword for any field
        val intent    = ReconstructionEngine().reconstruct(fullFields(), fullEvidence())
        val result    = validator.validate(intent)
        assertFalse(result.valid)
        assertTrue(result.reasons.any { it.contains("relevance") })
    }

    @Test
    fun `EvidenceValidator fails consistency check when duplicate evidence ids exist`() {
        val validator = EvidenceValidator()
        val dupEvidence = mapOf(
            "objective"   to listOf(
                EvidenceRef("dup-id", "objective-spec"),
                EvidenceRef("dup-id", "objective-review")
            ),
            "constraints" to listOf(EvidenceRef("ev-con", "constraint-review")),
            "environment" to listOf(EvidenceRef("ev-env", "environment-audit")),
            "resources"   to listOf(EvidenceRef("ev-res", "resource-inventory"))
        )
        val intent = ReconstructionEngine().reconstruct(fullFields(), dupEvidence)
        val result = validator.validate(intent)
        assertFalse(result.valid)
        assertTrue(result.reasons.any { it.contains("consistency") })
    }

    @Test
    fun `EvidenceValidator fails coverage check when substantial field has only one source`() {
        val validator = EvidenceValidator()
        // "Build an enterprise-grade monitoring system" = 6 words → triggers coverage check
        val fields = fullFields().toMutableMap().also {
            it["objective"] = "Build an enterprise-grade monitoring system"
        }
        val evidence = mapOf(
            "objective"   to listOf(EvidenceRef("ev-obj", "objective-spec")), // only 1 source
            "constraints" to listOf(EvidenceRef("ev-con", "constraint-review")),
            "environment" to listOf(EvidenceRef("ev-env", "environment-audit")),
            "resources"   to listOf(EvidenceRef("ev-res", "resource-inventory"))
        )
        val intent = ReconstructionEngine().reconstruct(fields, evidence)
        val result = validator.validate(intent)
        assertFalse(result.valid)
        assertTrue(result.reasons.any { it.contains("coverage") })
    }

    @Test
    fun `IrsOrchestrator halts at EVIDENCE_VALIDATION with EVIDENCE_INVALID for irrelevant evidence`() {
        val orchestrator = IrsOrchestrator()
        // fullEvidence() uses "manual" sources — will fail relevance check
        orchestrator.createSession("s-ev-fail", fullFields(), fullEvidence(), majorityConfig())

        orchestrator.step("s-ev-fail")  // GAP_DETECTION
        orchestrator.step("s-ev-fail")  // SCOUTING

        val evStep = orchestrator.step("s-ev-fail")  // EVIDENCE_VALIDATION
        assertTrue(evStep.terminal)
        assertEquals(IrsStage.EVIDENCE_VALIDATION, evStep.executedStage)
        val rejected = evStep.orchestratorResult as? OrchestratorResult.Rejected
        assertNotNull(rejected)
        assertEquals(FailureType.EVIDENCE_INVALID.name, rejected!!.reason)
    }

    // ── IRS-05: RealityKnowledgeGateway tests ─────────────────────────────────

    @Test
    fun `RealityKnowledgeGateway returns facts for known domain`() {
        val gateway = RealityKnowledgeGateway()
        val intent  = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val facts   = gateway.query("environment", intent)
        assertTrue(facts.isNotEmpty())
        assertTrue(facts.all { it.domain == "environment" })
        assertTrue(facts.all { it.credibilityScore in 0.0..1.0 })
        assertTrue(facts.all { it.source.isNotBlank() })
    }

    @Test
    fun `RealityKnowledgeGateway returns empty list for unknown domain`() {
        val gateway = RealityKnowledgeGateway()
        val intent  = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val facts   = gateway.query("unknown-domain-xyz", intent)
        assertTrue(facts.isEmpty())
    }

    @Test
    fun `RealityKnowledgeGateway queryAll returns facts for all configured domains`() {
        val gateway  = RealityKnowledgeGateway()
        val intent   = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val allFacts = gateway.queryAll(intent)
        assertTrue(allFacts.isNotEmpty())
        assertTrue(allFacts.containsKey("environment"))
        assertTrue(allFacts.containsKey("dependency"))
        assertTrue(allFacts.containsKey("constraint"))
    }

    // ── IRS-05: EvidenceScoringEngine tests ──────────────────────────────────

    @Test
    fun `EvidenceScoringEngine returns acceptable score for well-evidenced intent`() {
        val scorer = EvidenceScoringEngine()
        val intent = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val report = scorer.score(intent)
        assertTrue(report.overallScore >= 0.5)
        assertTrue(report.isAcceptable)
        assertTrue(report.fieldScores.containsKey("objective"))
        assertTrue(report.fieldScores.containsKey("environment"))
    }

    @Test
    fun `EvidenceScoringEngine penalises fields with unavailability markers`() {
        val scorer = EvidenceScoringEngine()
        val fields = fullFields().toMutableMap().also { it["resources"] = "unavailable" }
        val intent = ReconstructionEngine().reconstruct(fields, relevantEvidence())
        val report = scorer.score(intent)
        val resourceScore = report.fieldScores["resources"] ?: 1.0
        assertTrue(resourceScore < 0.7)
    }

    @Test
    fun `EvidenceScoringEngine reports low-credibility fields when score is below threshold`() {
        val scorer = EvidenceScoringEngine()
        val fields = mapOf(
            "objective"   to "x",
            "constraints" to "y",
            "environment" to "z",
            "resources"   to "unavailable"
        )
        val intent = ReconstructionEngine().reconstruct(fields, emptyMap())
        val report = scorer.score(intent)
        assertTrue(report.lowCredibilityFields.contains("resources"))
    }

    // ── IRS-05: ContradictionEngine tests ────────────────────────────────────

    @Test
    fun `ContradictionEngine detects no contradictions for clean intent`() {
        val detector = ContradictionEngine()
        val intent   = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val report   = detector.detect(intent)
        assertFalse(report.hasContradictions)
        assertTrue(report.contradictions.isEmpty())
    }

    @Test
    fun `ContradictionEngine detects offline vs cloud semantic contradiction`() {
        val detector = ContradictionEngine()
        val fields   = mapOf(
            "objective"   to "Build app",
            "constraints" to "must work offline",
            "environment" to "cloud platform",
            "resources"   to "team available"
        )
        val intent = ReconstructionEngine().reconstruct(fields, relevantEvidence())
        val report = detector.detect(intent)
        assertTrue(report.hasContradictions)
        assertTrue(report.contradictions.any { it.description.contains("offline") })
    }

    @Test
    fun `ContradictionEngine detects real-time vs serverless semantic contradiction`() {
        val detector = ContradictionEngine()
        val fields   = mapOf(
            "objective"   to "Build app",
            "constraints" to "real-time processing required",
            "environment" to "serverless",
            "resources"   to "team available"
        )
        val intent = ReconstructionEngine().reconstruct(fields, relevantEvidence())
        val report = detector.detect(intent)
        assertTrue(report.hasContradictions)
        assertTrue(report.contradictions.any { it.description.contains("real-time") })
    }

    // ── IRS-05C: RealityValidator tests (pure rule-table, no implicit logic) ──

    @Test
    fun `RealityValidator passes with LOW risk for well-formed intent`() {
        val validator = RealityValidator()
        val intent    = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val result    = validator.validate(intent)
        assertTrue(result.valid)
        assertTrue(result.reasons.isEmpty())
        assertEquals(RiskLevel.LOW, result.riskLevel)
        assertTrue(result.confidence in 0.0..1.0)
        assertTrue(result.credibilityReport.isAcceptable)
        assertFalse(result.contradictionReport.hasContradictions)
        assertTrue(result.simulationResult.feasible)
    }

    @Test
    fun `RealityValidator confidence equals credibilityReport overallScore directly`() {
        val validator = RealityValidator()
        // Clean intent
        val clean = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val cleanResult = validator.validate(clean)
        assertEquals(cleanResult.credibilityReport.overallScore, cleanResult.confidence, 0.0)
        // Contradictory intent — confidence must still equal overallScore (no multiplier)
        val badFields = mapOf(
            "objective"   to "Build app",
            "constraints" to "must work offline",
            "environment" to "cloud",
            "resources"   to "team available"
        )
        val bad = ReconstructionEngine().reconstruct(badFields, relevantEvidence())
        val badResult = validator.validate(bad)
        assertEquals(badResult.credibilityReport.overallScore, badResult.confidence, 0.0)
    }

    @Test
    fun `RealityValidator fails with HIGH risk when contradictions are detected`() {
        val validator = RealityValidator()
        val fields    = mapOf(
            "objective"   to "Build app",
            "constraints" to "must work offline",
            "environment" to "cloud",
            "resources"   to "team available"
        )
        val intent = ReconstructionEngine().reconstruct(fields, relevantEvidence())
        val result = validator.validate(intent)
        assertFalse(result.valid)
        assertEquals(RiskLevel.HIGH, result.riskLevel)
        assertTrue(result.reasons.any { it.contains("contradiction") || it.contains("sim:") })
        assertTrue(result.contradictionReport.hasContradictions)
    }

    @Test
    fun `RealityValidator risk mapping uses only boolean outcomes — no score thresholds`() {
        val validator = RealityValidator()
        val intent    = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val result    = validator.validate(intent)
        // LOW must not require score >= 0.7 — any acceptable credibility + no contradictions + feasible = LOW
        if (result.credibilityReport.isAcceptable &&
            !result.contradictionReport.hasContradictions &&
            result.simulationResult.feasible) {
            assertEquals(RiskLevel.LOW, result.riskLevel)
        }
    }

    @Test
    fun `RealityValidator reasons come directly from sub-module outputs`() {
        val validator = RealityValidator()
        val fields    = mapOf(
            "objective"   to "Build app",
            "constraints" to "must work offline",
            "environment" to "cloud",
            "resources"   to "team available"
        )
        val intent = ReconstructionEngine().reconstruct(fields, relevantEvidence())
        val result = validator.validate(intent)
        assertTrue(result.reasons.any { it.startsWith("contradiction:") || it.startsWith("sim:") })
        val expected = result.credibilityReport.reasons +
                       result.contradictionReport.reasons +
                       result.simulationResult.failurePoints
        assertEquals(expected, result.reasons)
    }

    @Test
    fun `RealityValidator simulationResult evaluations included for full traceability`() {
        val validator = RealityValidator()
        val intent    = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val result    = validator.validate(intent)
        assertTrue(result.simulationResult.constraintsChecked > 0)
        assertTrue(result.simulationResult.evaluations.isNotEmpty())
        assertEquals(result.simulationResult.constraintsChecked, result.simulationResult.evaluations.size)
        assertTrue(result.simulationResult.evaluations.all { it.ruleId.isNotEmpty() })
    }

    @Test
    fun `IrsOrchestrator halts at REALITY_VALIDATION with REALITY_UNVERIFIABLE for contradictory intent`() {
        val orchestrator = IrsOrchestrator()
        val fields = mapOf(
            "objective"   to "Build app",
            "constraints" to "must work offline",
            "environment" to "cloud",
            "resources"   to "team available"
        )
        orchestrator.createSession("s-rv-fail", fields, relevantEvidence(), majorityConfig())

        orchestrator.step("s-rv-fail")  // GAP_DETECTION
        orchestrator.step("s-rv-fail")  // SCOUTING
        orchestrator.step("s-rv-fail")  // EVIDENCE_VALIDATION

        val rvStep = orchestrator.step("s-rv-fail")  // REALITY_VALIDATION
        assertTrue(rvStep.terminal)
        assertEquals(IrsStage.REALITY_VALIDATION, rvStep.executedStage)
        val rejected = rvStep.orchestratorResult as? OrchestratorResult.Rejected
        assertNotNull(rejected)
        assertEquals(FailureType.REALITY_UNVERIFIABLE.name, rejected!!.reason)
        assertTrue(rejected.details.isNotEmpty())
    }

    @Test
    fun `IrsOrchestrator REALITY_VALIDATION stage is included in full pipeline history`() {
        val orchestrator = IrsOrchestrator()
        orchestrator.createSession("s-rv-hist", fullFields(), relevantEvidence(), majorityConfig())

        repeat(8) { orchestrator.step("s-rv-hist") }

        val history = orchestrator.replayHistory("s-rv-hist")
        val stages  = history.map { it.stage }
        assertTrue(stages.contains(IrsStage.REALITY_VALIDATION))
        val rvIndex = stages.indexOf(IrsStage.REALITY_VALIDATION)
        val evIndex = stages.indexOf(IrsStage.EVIDENCE_VALIDATION)
        val swIndex = stages.indexOf(IrsStage.SWARM_VALIDATION)
        assertTrue(rvIndex > evIndex)
        assertTrue(rvIndex < swIndex)
    }

    // ── IRS-05C: RealitySimulationEngine tests (typed evaluations) ────────────

    @Test
    fun `RealitySimulationEngine returns feasible for clean intent`() {
        val engine = RealitySimulationEngine()
        val intent = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val result = engine.simulate(intent)
        assertTrue(result.feasible)
        assertTrue(result.failurePoints.isEmpty())
        assertTrue(result.constraintsChecked > 0)
    }

    @Test
    fun `RealitySimulationEngine evaluations cover all rules with typed SimulationRuleResult`() {
        val engine = RealitySimulationEngine()
        val intent = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val result = engine.simulate(intent)
        assertEquals(result.constraintsChecked, result.evaluations.size)
        // Each evaluation is a typed record with ruleId and description
        assertTrue(result.evaluations.all { it.ruleId.isNotEmpty() })
        assertTrue(result.evaluations.all { it.description.isNotEmpty() })
        // Clean intent — all rules must NOT be triggered
        assertTrue(result.evaluations.all { !it.triggered })
        assertTrue(result.evaluations.all { it.message == null })
    }

    @Test
    fun `RealitySimulationEngine evaluations mark triggered rules with non-null message`() {
        val engine = RealitySimulationEngine()
        val fields = mapOf(
            "objective"   to "Build app",
            "constraints" to "must work offline",
            "environment" to "cloud",
            "resources"   to "team available"
        )
        val intent = ReconstructionEngine().reconstruct(fields, relevantEvidence())
        val result = engine.simulate(intent)
        assertFalse(result.feasible)
        val triggered = result.evaluations.filter { it.triggered }
        assertTrue(triggered.isNotEmpty())
        assertTrue(triggered.all { it.message != null })
        // Non-triggered evaluations must have null message
        val passing = result.evaluations.filter { !it.triggered }
        assertTrue(passing.all { it.message == null })
        // failurePoints must match triggered messages exactly
        assertEquals(triggered.mapNotNull { it.message }, result.failurePoints)
    }

    @Test
    fun `RealitySimulationEngine evaluations count equals constraintsChecked`() {
        val engine = RealitySimulationEngine()
        val intent = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val result = engine.simulate(intent)
        assertEquals(result.constraintsChecked, result.evaluations.size)
    }

    @Test
    fun `RealitySimulationEngine detects unavailable resources as infeasible`() {
        val engine = RealitySimulationEngine()
        val fields = fullFields().toMutableMap().also { it["resources"] = "unavailable" }
        val intent = ReconstructionEngine().reconstruct(fields, relevantEvidence())
        val result = engine.simulate(intent)
        assertFalse(result.feasible)
        assertTrue(result.failurePoints.any { it.contains("resources") })
        assertTrue(result.evaluations.any { it.ruleId == "RES-01" && it.triggered })
    }

    @Test
    fun `RealitySimulationEngine detects offline vs cloud as infeasible`() {
        val engine = RealitySimulationEngine()
        val fields = mapOf(
            "objective"   to "Build app",
            "constraints" to "must work offline",
            "environment" to "cloud",
            "resources"   to "team available"
        )
        val intent = ReconstructionEngine().reconstruct(fields, relevantEvidence())
        val result = engine.simulate(intent)
        assertFalse(result.feasible)
        assertTrue(result.failurePoints.any { it.contains("offline") || it.contains("cloud") })
        assertTrue(result.evaluations.any { it.ruleId == "ENV-01" && it.triggered })
    }

    @Test
    fun `RealitySimulationEngine detects real-time vs serverless as infeasible`() {
        val engine = RealitySimulationEngine()
        val fields = mapOf(
            "objective"   to "Build app",
            "constraints" to "real-time processing required",
            "environment" to "serverless",
            "resources"   to "team available"
        )
        val intent = ReconstructionEngine().reconstruct(fields, relevantEvidence())
        val result = engine.simulate(intent)
        assertFalse(result.feasible)
        assertTrue(result.failurePoints.any { it.contains("real-time") || it.contains("serverless") })
        assertTrue(result.evaluations.any { it.ruleId == "ENV-02" && it.triggered })
    }

    @Test
    fun `RealitySimulationEngine reports constraintsChecked count`() {
        val engine = RealitySimulationEngine()
        val intent = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val result = engine.simulate(intent)
        assertTrue(result.constraintsChecked >= 4)
    }

    // ── IRS-05C-AUDIT-CLOSURE: FailureType taxonomy tests ────────────────────

    @Test
    fun `FailureType enum covers all five IRS rejection paths`() {
        val names = FailureType.values().map { it.name }.toSet()
        assertTrue(names.contains("EVIDENCE_INVALID"))
        assertTrue(names.contains("REALITY_UNVERIFIABLE"))
        assertTrue(names.contains("UNSTABLE"))
        assertTrue(names.contains("INFEASIBLE"))
        assertTrue(names.contains("PCCV_FAIL"))
        assertEquals(5, FailureType.values().size)
    }

    @Test
    fun `FailureType EVIDENCE_INVALID name matches orchestrator rejection reason`() {
        val orchestrator = IrsOrchestrator()
        orchestrator.createSession("s-ft-ev", fullFields(), fullEvidence(), majorityConfig())
        orchestrator.step("s-ft-ev")  // GAP_DETECTION
        orchestrator.step("s-ft-ev")  // SCOUTING
        val evStep = orchestrator.step("s-ft-ev")  // EVIDENCE_VALIDATION
        val rejected = evStep.orchestratorResult as? OrchestratorResult.Rejected
        assertNotNull(rejected)
        assertEquals(FailureType.EVIDENCE_INVALID.name, rejected!!.reason)
    }

    @Test
    fun `FailureType REALITY_UNVERIFIABLE name matches orchestrator rejection reason`() {
        val orchestrator = IrsOrchestrator()
        val fields = mapOf(
            "objective"   to "Build app",
            "constraints" to "must work offline",
            "environment" to "cloud",
            "resources"   to "team available"
        )
        orchestrator.createSession("s-ft-rv", fields, relevantEvidence(), majorityConfig())
        orchestrator.step("s-ft-rv")  // GAP_DETECTION
        orchestrator.step("s-ft-rv")  // SCOUTING
        orchestrator.step("s-ft-rv")  // EVIDENCE_VALIDATION
        val rvStep = orchestrator.step("s-ft-rv")  // REALITY_VALIDATION
        val rejected = rvStep.orchestratorResult as? OrchestratorResult.Rejected
        assertNotNull(rejected)
        assertEquals(FailureType.REALITY_UNVERIFIABLE.name, rejected!!.reason)
    }

    @Test
    fun `RealityValidationResult valid is authoritative and passed is deprecated alias`() {
        val validator = RealityValidator()
        val intent    = ReconstructionEngine().reconstruct(fullFields(), relevantEvidence())
        val result    = validator.validate(intent)
        // valid is the authoritative field
        assertTrue(result.valid)
        // passed is a deprecated alias — must equal valid
        @Suppress("DEPRECATION")
        assertEquals(result.valid, result.passed)
    }

    @Test
    fun `RealityValidationResult reasons is authoritative and issues is deprecated alias`() {
        val validator = RealityValidator()
        val fields    = mapOf(
            "objective"   to "Build app",
            "constraints" to "must work offline",
            "environment" to "cloud",
            "resources"   to "team available"
        )
        val intent = ReconstructionEngine().reconstruct(fields, relevantEvidence())
        val result = validator.validate(intent)
        assertTrue(result.reasons.isNotEmpty())
        // issues is a deprecated alias — must equal reasons exactly
        @Suppress("DEPRECATION")
        assertEquals(result.reasons, result.issues)
    }

    // ── IRS-05C-FINAL-DETERMINISM-VALIDATION: 3-layer structural replay proof ────

    @Test
    fun `IRS pipeline is deterministic across 5 identical replay runs`() {
        data class PipelineRun(
            val stageSequence:       List<IrsStage>,
            val finalResult:         OrchestratorResult?,
            val simulationEvaluations: List<SimulationRuleResult>
        )

        fun runPipeline(): PipelineRun {
            val orchestrator = IrsOrchestrator()
            val id = "replay-determinism-${System.nanoTime()}"
            orchestrator.createSession(id, fullFields(), relevantEvidence(), majorityConfig())
            repeat(9) {
                val step = orchestrator.step(id)
                if (step.terminal) return@repeat
            }
            return PipelineRun(
                stageSequence        = orchestrator.replayHistory(id).map { it.stage },
                finalResult          = orchestrator.replayHistory(id).last().orchestratorResult
                    ?: orchestrator.step(id).orchestratorResult,
                simulationEvaluations = orchestrator.realitySimulationResult(id)?.evaluations
                    ?: emptyList()
            )
        }

        val runs = (1..5).map { runPipeline() }
        val first = runs.first()

        // Layer 1 — Stage sequence equality
        assertFalse("Stage sequence must not be empty", first.stageSequence.isEmpty())
        runs.forEachIndexed { i, run ->
            assertEquals(
                "Stage sequence divergence on run ${i + 1}",
                first.stageSequence,
                run.stageSequence
            )
        }

        // Layer 2 — Full OrchestratorResult structural equality (not just class name)
        runs.forEachIndexed { i, run ->
            assertEquals(
                "OrchestratorResult divergence on run ${i + 1}",
                first.finalResult,
                run.finalResult
            )
        }

        // Layer 3 — Simulation trace equality: ruleIds, triggered flags, messages
        assertFalse("Simulation evaluations must not be empty", first.simulationEvaluations.isEmpty())
        runs.forEachIndexed { i, run ->
            assertEquals(
                "Simulation evaluation count divergence on run ${i + 1}",
                first.simulationEvaluations.size,
                run.simulationEvaluations.size
            )
            first.simulationEvaluations.zip(run.simulationEvaluations).forEachIndexed { j, (expected, actual) ->
                assertEquals("ruleId mismatch at evaluation[$j] on run ${i + 1}", expected.ruleId,    actual.ruleId)
                assertEquals("triggered mismatch at evaluation[$j] on run ${i + 1}", expected.triggered, actual.triggered)
                assertEquals("message mismatch at evaluation[$j] on run ${i + 1}", expected.message,  actual.message)
            }
        }
    }

    // ── IRS-05C-AUDIT-CLOSURE-REVISION: Audit Report Validation (STEP 3) ─────

    @Test
    fun `CcfScore computes totalScore percentage and riskLevel correctly`() {
        val ccf = CcfScore(
            mutationControl     = 3,
            scopeDrift          = 3,
            lifecycleCompliance = 3,
            hiddenLogic         = 3,
            outputIntegrity     = 3
        )
        assertEquals(15, ccf.totalScore)
        assertEquals(100.0, ccf.percentage, 0.001)
        assertEquals("LOW", ccf.riskLevel)

        val medium = CcfScore(3, 2, 2, 2, 2)
        assertEquals(11, medium.totalScore)
        assertEquals("MEDIUM", medium.riskLevel)

        val high = CcfScore(1, 1, 1, 2, 1)
        assertEquals(6, high.totalScore)
        assertEquals("HIGH", high.riskLevel)
    }

    @Test
    fun `IrsAuditReport is fully populated and reflects APPROVED status`() {
        val ccf = CcfScore(
            mutationControl     = 3,
            scopeDrift          = 3,
            lifecycleCompliance = 3,
            hiddenLogic         = 3,
            outputIntegrity     = 2
        )
        val report = IrsAuditReport(
            contractId                = "IRS-05C-AUDIT-CLOSURE-REVISION",
            deterministic             = true,
            traceComplete             = true,
            violations                = emptyList(),
            pureAggregation           = true,
            hiddenLogicDetected       = false,
            confidenceMappingCorrect  = true,
            legacyFieldsPresent       = false,
            markedDeprecated          = true,
            removalRequired           = false,
            orchestratorCompliant     = true,
            driftPoints               = emptyList(),
            taxonomyStandardized      = true,
            unmappedFailures          = emptyList(),
            replayDeterministic       = true,
            replayRunsCompared        = 5,
            divergenceDetected        = false,
            ccf                       = ccf,
            approvalStatus            = "APPROVED"
        )

        assertEquals("IRS-05C-AUDIT-CLOSURE-REVISION", report.contractId)
        assertTrue(report.deterministic)
        assertTrue(report.traceComplete)
        assertTrue(report.violations.isEmpty())
        assertTrue(report.pureAggregation)
        assertFalse(report.hiddenLogicDetected)
        assertTrue(report.confidenceMappingCorrect)
        assertFalse(report.legacyFieldsPresent)
        assertTrue(report.orchestratorCompliant)
        assertTrue(report.driftPoints.isEmpty())
        assertTrue(report.taxonomyStandardized)
        assertTrue(report.unmappedFailures.isEmpty())
        assertTrue(report.replayDeterministic)
        assertEquals(5, report.replayRunsCompared)
        assertFalse(report.divergenceDetected)
        assertEquals(14, report.ccf.totalScore)
        assertTrue(report.ccf.totalScore >= 13)
        assertEquals("LOW", report.ccf.riskLevel)
        assertEquals("APPROVED", report.approvalStatus)
    }

    // ── SSA-SYSTEM-VERIFICATION-01 ────────────────────────────────────────────

    @Test
    fun `SystemVerificationContract passes all six checks at intent_submitted boundary`() {
        val store = store(listOf(Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "build"))))
        val report = SystemVerificationContract(store).verify("p")
        assertTrue("overall report must be valid", report.valid)
    }

    @Test
    fun `Check 1 ledger integrity passes for single intent_submitted event`() {
        val store = store(listOf(Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"))))
        val report = SystemVerificationContract(store).verify("p")
        assertTrue(report.ledgerIntegrity.passed)
        assertEquals(1, report.ledgerIntegrity.eventCount)
        assertEquals(EventTypes.INTENT_SUBMITTED, report.ledgerIntegrity.firstEventType)
    }

    @Test
    fun `Check 1 ledger integrity fails for empty ledger`() {
        val store = store(emptyList())
        val report = SystemVerificationContract(store).verify("p")
        assertFalse(report.ledgerIntegrity.passed)
        assertEquals(0, report.ledgerIntegrity.eventCount)
        assertNull(report.ledgerIntegrity.firstEventType)
        assertFalse(report.valid)
    }

    @Test
    fun `Check 1 ledger integrity fails when ledger has more than one event`() {
        val store = store(listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "a")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0))
        ))
        val report = SystemVerificationContract(store).verify("p")
        assertFalse(report.ledgerIntegrity.passed)
        assertEquals(2, report.ledgerIntegrity.eventCount)
    }

    @Test
    fun `Check 2 replay consistency returns correct snapshot at intent_submitted`() {
        val store = store(listOf(Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "o"))))
        val report = SystemVerificationContract(store).verify("p")
        assertTrue(report.replayConsistency.passed)
        assertEquals(EventTypes.INTENT_SUBMITTED, report.replayConsistency.phase)
        assertEquals("0/0", report.replayConsistency.contracts)
        assertFalse(report.replayConsistency.execStarted)
    }

    @Test
    fun `Check 2 replay consistency fails when execution has started`() {
        val store = store(listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "o")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 3.0))
        ))
        val report = SystemVerificationContract(store).verify("p")
        assertFalse(report.replayConsistency.passed)
        assertTrue(report.replayConsistency.execStarted)
    }

    @Test
    fun `Check 3 governor inactivity passes when no contract or execution events exist`() {
        val store = store(listOf(Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "x"))))
        val report = SystemVerificationContract(store).verify("p")
        assertTrue(report.governorInactivity.passed)
        assertFalse(report.governorInactivity.hasContractStarted)
        assertFalse(report.governorInactivity.hasExecutionStarted)
    }

    @Test
    fun `Check 3 governor inactivity fails when execution_started is present`() {
        val store = store(listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "o")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 3.0))
        ))
        val report = SystemVerificationContract(store).verify("p")
        assertFalse(report.governorInactivity.passed)
        assertTrue(report.governorInactivity.hasExecutionStarted)
    }

    @Test
    fun `Check 3 governor inactivity fails when contract_started is present`() {
        val store = store(listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "o")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 3.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("position" to 1.0, "total" to 3.0))
        ))
        val report = SystemVerificationContract(store).verify("p")
        assertFalse(report.governorInactivity.passed)
        assertTrue(report.governorInactivity.hasContractStarted)
    }

    @Test
    fun `Check 4 SSM integrity confirms StateSurfaceMirror is the sole state authority`() {
        val store = store(listOf(Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "s"))))
        val report = SystemVerificationContract(store).verify("p")
        assertTrue(report.ssmIntegrity.passed)
        assertEquals("StateSurfaceMirror", report.ssmIntegrity.ssmClassName)
    }

    @Test
    fun `Check 5 CSL dormancy passes at intent_submitted — no contract events in ledger`() {
        val store = store(listOf(Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "d"))))
        val report = SystemVerificationContract(store).verify("p")
        assertTrue(report.cslDormancy.passed)
        assertFalse(report.cslDormancy.cslEvaluated)
        assertFalse(report.cslDormancy.driftTriggered)
    }

    @Test
    fun `Check 5 CSL dormancy fails when contract_started event implies CSL was called`() {
        val store = store(listOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "o")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 3.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("position" to 1.0, "total" to 3.0))
        ))
        val report = SystemVerificationContract(store).verify("p")
        assertFalse(report.cslDormancy.passed)
        assertTrue(report.cslDormancy.cslEvaluated)
    }

    @Test
    fun `Check 6 SSA isolation confirms StructuralStateAwareness is in governance package`() {
        val store = store(listOf(Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "i"))))
        val report = SystemVerificationContract(store).verify("p")
        assertTrue(report.ssaIsolation.passed)
        assertTrue(report.ssaIsolation.isolatedFromCore)
        assertTrue(report.ssaIsolation.ssaPackage.contains("governance"))
        assertTrue(report.ssaIsolation.notInExecutionPath)
    }
}

private class InMemoryEventRepository(initial: List<Event>) : EventRepository {
    private val ledger = initial.toMutableList()
    override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        ledger.add(Event(type, payload))
    }
    override fun loadEvents(projectId: String): List<Event> = ledger.toList()
}
