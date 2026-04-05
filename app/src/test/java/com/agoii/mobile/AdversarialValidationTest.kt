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
import com.agoii.mobile.ui.core.StateProjection
import org.junit.Assert.*
import org.junit.Test

/**
 * Adversarial Validation Test Suite
 *
 * Executes adversarial scenarios against the Agoii runtime to prove that
 * governance enforcement, replay purity, and architectural invariants
 * cannot be bypassed.
 *
 * Classification: Tier A — READ-ONLY VALIDATION
 * Mutation: FORBIDDEN — no production code is modified.
 *
 * 6 attack classes:
 *   ATTACK-01: UI State Derivation (RL-01)
 *   ATTACK-02: Event Inspection Leak
 *   ATTACK-03: Governor Bypass
 *   ATTACK-04: Mutation Envelope Violation
 *   ATTACK-05: Time Drift
 *   ATTACK-06: Replay Non-Determinism (RL-01 CORE)
 */
class AdversarialValidationTest {

    // ── Test infrastructure ──────────────────────────────────────────────────

    /** In-memory event repository for isolated testing — no Android context needed. */
    private class InMemoryStore(initial: List<Event> = emptyList()) : EventRepository {
        private val ledger = initial.toMutableList()
        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            ledger.add(Event(type, payload, id = "test-${ledger.size}", sequenceNumber = ledger.size.toLong()))
        }
        override fun loadEvents(projectId: String): List<Event> = ledger.toList()
    }

    private val replay = Replay(InMemoryStore())

    /** Helper: builds an empty GovernanceView with all defaults. */
    private fun emptyGovernanceView() = GovernanceView(
        lastEventType            = null,
        lastEventPayload         = emptyMap(),
        totalContracts           = 0,
        reportReference          = "",
        deltaContractRecoveryIds = emptySet(),
        taskAssignedTaskIds      = emptySet(),
        lastContractStartedId    = "",
        lastContractStartedPosition = null
    )

    /** Helper: builds an empty ExecutionView with all flags false. */
    private fun emptyExecutionView() = ExecutionView(
        taskStatus           = emptyMap(),
        icsStarted           = false,
        icsCompleted         = false,
        commitContractExists = false,
        commitExecuted       = false,
        commitAborted        = false,
        executionStatus      = "not_started",
        showCommitPanel      = false
    )

    /** Helper: standard empty AuditView. */
    private fun emptyAuditView() = AuditView(
        intent    = IntentStructuralState(structurallyComplete = false),
        contracts = ContractStructuralState(generated = false, valid = false),
        execution = ExecutionStructuralState(0, 0, 0, 0),
        assembly  = AssemblyStructuralState(false, false, false)
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // ATTACK-01 — UI STATE DERIVATION (RL-01)
    //
    // Attempt: Force UI to derive state indirectly from empty/partial replay.
    // Expected: Missing values surface as null/explicit state, NOT defaults or
    //           computed fallbacks.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ATTACK-01a empty replay produces explicit zero-state not fallbacks`() {
        // Attack: empty event list → no events in ledger
        val state = replay.deriveStructuralState(emptyList())

        // Governance: lastEventType MUST be null (not a default string)
        assertNull("Empty replay must have null lastEventType", state.governanceView.lastEventType)
        assertEquals("Empty replay must have 0 totalContracts", 0, state.governanceView.totalContracts)
        assertEquals("Empty replay must have empty reportReference", "", state.governanceView.reportReference)

        // Execution: must be in explicit initial state, not inferred
        assertEquals("Empty replay executionStatus must be 'not_started'", "not_started", state.executionView.executionStatus)
        assertFalse("Empty replay icsStarted must be false", state.executionView.icsStarted)
        assertFalse("Empty replay icsCompleted must be false", state.executionView.icsCompleted)
        assertFalse("Empty replay showCommitPanel must be false", state.executionView.showCommitPanel)
        assertTrue("Empty replay taskStatus must be empty", state.executionView.taskStatus.isEmpty())

        // Audit: must be structurally zeroed, not inferred
        assertFalse("Empty replay intent must not be complete", state.auditView.intent.structurallyComplete)
        assertFalse("Empty replay contracts must not be generated", state.auditView.contracts.generated)
        assertFalse("Empty replay contracts must not be valid", state.auditView.contracts.valid)
        assertEquals("Empty replay assigned tasks must be 0", 0, state.auditView.execution.assignedTasks)
    }

    @Test
    fun `ATTACK-01b partial replay with only intent does not imply execution`() {
        // Attack: only INTENT_SUBMITTED — try to force execution inference
        val events = listOf(Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "break_it")))
        val state = replay.deriveStructuralState(events)

        // Intent is submitted, but nothing else should be true
        assertTrue("Intent must be structurally complete", state.auditView.intent.structurallyComplete)
        assertFalse("Contracts must NOT be generated", state.auditView.contracts.generated)
        assertEquals("Execution status must be 'not_started'", "not_started", state.executionView.executionStatus)
        assertEquals("Assigned tasks must be 0", 0, state.auditView.execution.assignedTasks)
        assertEquals("Total tasks must be 0", 0, state.auditView.execution.totalTasks)
        assertFalse("Assembly must not be started", state.auditView.assembly.assemblyStarted)
        assertFalse("ICS must not be started", state.executionView.icsStarted)
    }

    @Test
    fun `ATTACK-01c StateProjection does not inject defaults into empty state`() {
        // Attack: feed StateProjection an empty ReplayStructuralState
        val emptyState = ReplayStructuralState(
            governanceView = emptyGovernanceView(),
            executionView  = emptyExecutionView(),
            auditView      = emptyAuditView()
        )
        val uiState = StateProjection().project(emptyState)

        // UI state must NOT fabricate completion or execution signals
        assertFalse("UIState.isComplete must be false", uiState.isComplete)
        assertFalse("UIState.executionStarted must be false", uiState.executionStarted)
        assertFalse("UIState.executionCompleted must be false", uiState.executionCompleted)
        assertFalse("UIState.assemblyStarted must be false", uiState.assemblyStarted)
        assertFalse("UIState.assemblyValidated must be false", uiState.assemblyValidated)
        assertFalse("UIState.assemblyCompleted must be false", uiState.assemblyCompleted)
    }

    @Test
    fun `ATTACK-01d partial executionView with tasks but no TASK_STARTED does not infer running`() {
        // Attack: assign a task but never start it — check execution status
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 1)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 1, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 1))
        )
        val state = replay.deriveStructuralState(events)

        // Task assigned but NOT started → executionStatus must be "not_started", NOT "running"
        assertEquals("Without TASK_STARTED, executionStatus must be 'not_started'",
            "not_started", state.executionView.executionStatus)
        assertEquals("Task t1 must show ASSIGNED status", "ASSIGNED", state.executionView.taskStatus["t1"])
    }

    @Test
    fun `ATTACK-01e missing executionStatus in TASK_EXECUTED defaults to running not success`() {
        // Attack: TASK_EXECUTED with null executionStatus — system must NOT assume SUCCESS
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 1)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 1, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1")),
            // executionStatus is ABSENT from payload — adversarial!
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "t1"))
        )
        val state = replay.deriveStructuralState(events)

        // MUST NOT assume success when executionStatus is missing
        assertNotEquals("Missing executionStatus must NOT be interpreted as success",
            "success", state.executionView.executionStatus)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ATTACK-02 — EVENT INSPECTION LEAK
    //
    // Attempt: Verify that UI state derivation (StateProjection) does NOT read
    //          raw events — only ReplayStructuralState fields.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ATTACK-02a StateProjection reads only auditView not event fields`() {
        // Attack: construct two states with identical auditViews but different
        // governanceViews (different lastEventType, lastEventPayload).
        // StateProjection must produce IDENTICAL UIState.
        val auditView = AuditView(
            intent    = IntentStructuralState(structurallyComplete = true),
            contracts = ContractStructuralState(generated = true, valid = true, totalContracts = 3),
            execution = ExecutionStructuralState(3, 2, 1, 0),
            assembly  = AssemblyStructuralState(false, false, false)
        )

        val stateA = ReplayStructuralState(
            governanceView = emptyGovernanceView().copy(
                lastEventType = EventTypes.TASK_STARTED,
                lastEventPayload = mapOf("taskId" to "t1")
            ),
            executionView = emptyExecutionView(),
            auditView = auditView
        )

        val stateB = ReplayStructuralState(
            governanceView = emptyGovernanceView().copy(
                lastEventType = EventTypes.ASSEMBLY_COMPLETED,
                lastEventPayload = mapOf("report_reference" to "rr-123")
            ),
            executionView = emptyExecutionView(),
            auditView = auditView
        )

        val projection = StateProjection()
        val uiA = projection.project(stateA)
        val uiB = projection.project(stateB)

        assertEquals("StateProjection must be identical for identical auditViews", uiA, uiB)
    }

    @Test
    fun `ATTACK-02b StateProjection ignores executionView entirely`() {
        // Attack: different executionViews with same auditView → same UIState
        val auditView = AuditView(
            intent    = IntentStructuralState(structurallyComplete = true),
            contracts = ContractStructuralState(generated = true, valid = true, totalContracts = 1),
            execution = ExecutionStructuralState(1, 1, 1, 1),
            assembly  = AssemblyStructuralState(true, true, true)
        )

        val execViewA = ExecutionView(
            taskStatus = mapOf("t1" to "EXECUTED_SUCCESS"),
            icsStarted = true, icsCompleted = true,
            commitContractExists = true, commitExecuted = true, commitAborted = false,
            executionStatus = "success", showCommitPanel = false
        )

        val execViewB = ExecutionView(
            taskStatus = mapOf("t1" to "FAILED"),
            icsStarted = false, icsCompleted = false,
            commitContractExists = false, commitExecuted = false, commitAborted = false,
            executionStatus = "failed", showCommitPanel = false
        )

        val projection = StateProjection()
        val uiA = projection.project(ReplayStructuralState(emptyGovernanceView(), execViewA, auditView))
        val uiB = projection.project(ReplayStructuralState(emptyGovernanceView(), execViewB, auditView))

        assertEquals("StateProjection must ignore executionView differences", uiA, uiB)
    }

    @Test
    fun `ATTACK-02c ReplayStructuralState events field does not leak into state derivation`() {
        // Attack: verify that adding more events with different payloads does not
        // leak event-specific data into the structural state beyond what's accumulated.
        val baseEvents = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2)),
            Event(EventTypes.CONTRACTS_READY, emptyMap())
        )

        val stateA = replay.deriveStructuralState(baseEvents)

        // Same events but with extra payload noise in CONTRACTS_READY
        val noisyEvents = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2)),
            Event(EventTypes.CONTRACTS_READY, mapOf("extra_noise" to "should_be_ignored", "danger" to true))
        )
        val stateB = replay.deriveStructuralState(noisyEvents)

        // Key structural fields must be identical regardless of noise
        assertEquals("Intent state must match", stateA.auditView.intent, stateB.auditView.intent)
        assertEquals("Contract state must match", stateA.auditView.contracts, stateB.auditView.contracts)
        assertEquals("Execution state must match", stateA.auditView.execution, stateB.auditView.execution)
        assertEquals("Assembly state must match", stateA.auditView.assembly, stateB.auditView.assembly)
        assertEquals("ExecutionView must match", stateA.executionView, stateB.executionView)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ATTACK-03 — GOVERNOR BYPASS
    //
    // Attempt: Trigger execution without valid contract. Direct processInteraction
    //          bypass, malformed contract, null contract.
    // Expected: BLOCKED before execution — Governor returns NO_EVENT or DRIFT.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ATTACK-03a Governor returns NO_EVENT on empty ledger`() {
        // Attack: call Governor with no events — execution must not proceed
        val store = InMemoryStore()
        val governor = Governor(store)
        val result = governor.runGovernor("attack")
        assertEquals("Empty ledger must return NO_EVENT", Governor.GovernorResult.NO_EVENT, result)
    }

    @Test
    fun `ATTACK-03b Governor blocks on intent-only without contracts`() {
        // Attack: only INTENT_SUBMITTED — no CONTRACTS_GENERATED exists yet
        // Governor MUST NOT derive contracts itself (that's ExecutionAuthority's job)
        val store = InMemoryStore(listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "attack"), id = "e0", sequenceNumber = 0)
        ))
        val governor = Governor(store)
        val result = governor.runGovernor("attack")

        // Governor must wait for ExecutionAuthority to write CONTRACTS_GENERATED
        assertEquals("Governor must return NO_EVENT waiting for CONTRACTS_GENERATED",
            Governor.GovernorResult.NO_EVENT, result)
    }

    @Test
    fun `ATTACK-03c Governor nextEvent returns null on intent-only`() {
        // Attack: pure projection on intent-only ledger
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "attack"))
        )
        val governor = Governor(InMemoryStore())
        val next = governor.nextEvent(events)
        assertNull("Governor nextEvent must be null for intent-only ledger", next)
    }

    @Test
    fun `ATTACK-03d Governor blocks when CONTRACTS_GENERATED has zero total`() {
        // Attack: malformed contract count = 0
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "attack")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 0)),
            Event(EventTypes.CONTRACTS_READY, emptyMap())
        )
        val governor = Governor(InMemoryStore())
        val next = governor.nextEvent(events)

        // With total=0, CONTRACTS_READY → CONTRACT_STARTED requires totalContracts>0
        assertNull("Governor must not issue CONTRACT_STARTED when total=0", next)
    }

    @Test
    fun `ATTACK-03e Governor blocks at terminal state EXECUTION_COMPLETED`() {
        // Attack: try to continue after EXECUTION_COMPLETED
        val store = InMemoryStore(listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"), id = "e0", sequenceNumber = 0),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1), id = "e1", sequenceNumber = 1),
            Event(EventTypes.CONTRACTS_READY, emptyMap(), id = "e2", sequenceNumber = 2),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap(), id = "e3", sequenceNumber = 3),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 1), id = "e4", sequenceNumber = 4),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 1, "contract_id" to "c1"), id = "e5", sequenceNumber = 5),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 1, "report_reference" to "rr"), id = "e6", sequenceNumber = 6),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1", "position" to 1, "total" to 1), id = "e7", sequenceNumber = 7),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "t1", "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED", "position" to 1, "total" to 1, "artifactReference" to "art1", "report_reference" to "rr"), id = "e8", sequenceNumber = 8),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "t1", "position" to 1, "total" to 1), id = "e9", sequenceNumber = 9),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 1, "total" to 1, "contractId" to "c1", "report_reference" to "rr"), id = "e10", sequenceNumber = 10),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("total" to 1), id = "e11", sequenceNumber = 11)
        ))
        val governor = Governor(store)
        val result = governor.runGovernor("attack")

        assertEquals("After EXECUTION_COMPLETED, Governor must return COMPLETED",
            Governor.GovernorResult.COMPLETED, result)
    }

    @Test
    fun `ATTACK-03f Governor nextEvent returns null for TASK_STARTED (waiting for EA)`() {
        // Attack: Governor must NOT execute tasks itself — only ExecutionAuthority does
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 1)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 1, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "c1-step1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "c1-step1", "position" to 1, "total" to 1))
        )
        val governor = Governor(InMemoryStore())
        val next = governor.nextEvent(events)
        assertNull("Governor must return null for TASK_STARTED — EA owns execution", next)
    }

    @Test
    fun `ATTACK-03g Governor nextEvent returns null for TASK_FAILED (requires external escalation)`() {
        // Attack: Governor must NOT auto-retry failed tasks
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 1)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 1, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "c1-step1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "c1-step1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "c1-step1", "executionStatus" to "FAILURE", "validationStatus" to "FAILED", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_FAILED, mapOf("taskId" to "c1-step1", "position" to 1, "total" to 1))
        )
        val governor = Governor(InMemoryStore())
        val next = governor.nextEvent(events)
        assertNull("Governor must NOT auto-retry on TASK_FAILED", next)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ATTACK-04 — MUTATION ENVELOPE VIOLATION (CSL Gate)
    //
    // Attempt: Exceed the Velocity Ceiling to trigger DRIFT.
    // Expected: Governor blocks issuance when EL > VC.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ATTACK-04a CSL gate blocks position 4 when VC is 5`() {
        // Attack: position=4 → EL = 2 + 4 = 6 > VC=5 → DRIFT
        // After contract 3 completes, attempt to start contract 4
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 4)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 4)),
            // Contracts 1-3 pass CSL
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 4, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "c1-step1", "position" to 1, "total" to 4)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "c1-step1", "position" to 1, "total" to 4)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "c1-step1", "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED", "position" to 1, "total" to 4, "artifactReference" to "a1", "report_reference" to "rr")),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "c1-step1", "position" to 1, "total" to 4)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 1, "total" to 4, "contractId" to "c1", "report_reference" to "rr")),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 2, "total" to 4, "contract_id" to "c2")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "c2-step1", "position" to 2, "total" to 4)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "c2-step1", "position" to 2, "total" to 4)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "c2-step1", "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED", "position" to 2, "total" to 4, "artifactReference" to "a2", "report_reference" to "rr")),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "c2-step1", "position" to 2, "total" to 4)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 2, "total" to 4, "contractId" to "c2", "report_reference" to "rr")),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 3, "total" to 4, "contract_id" to "c3")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "c3-step1", "position" to 3, "total" to 4)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "c3-step1", "position" to 3, "total" to 4)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "c3-step1", "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED", "position" to 3, "total" to 4, "artifactReference" to "a3", "report_reference" to "rr")),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "c3-step1", "position" to 3, "total" to 4)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 3, "total" to 4, "contractId" to "c3", "report_reference" to "rr"))
        )

        val governor = Governor(InMemoryStore())
        val next = governor.nextEvent(events)

        // Position 4 → EL = 2+4 = 6 > VC=5 → must NOT issue CONTRACT_STARTED
        assertNull("CSL gate must block position 4 (EL=6 > VC=5)", next)
    }

    @Test
    fun `ATTACK-04b CSL gate allows position 3 when VC is 5`() {
        // Verify: position=3 → EL = 2+3 = 5 ≤ VC=5 → ALLOWED
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 3)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 3, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "c1-step1", "position" to 1, "total" to 3)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "c1-step1", "position" to 1, "total" to 3)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "c1-step1", "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED", "position" to 1, "total" to 3, "artifactReference" to "a1", "report_reference" to "rr")),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "c1-step1", "position" to 1, "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 1, "total" to 3, "contractId" to "c1", "report_reference" to "rr")),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 2, "total" to 3, "contract_id" to "c2")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "c2-step1", "position" to 2, "total" to 3)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "c2-step1", "position" to 2, "total" to 3)),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "c2-step1", "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED", "position" to 2, "total" to 3, "artifactReference" to "a2", "report_reference" to "rr")),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "c2-step1", "position" to 2, "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 2, "total" to 3, "contractId" to "c2", "report_reference" to "rr"))
        )

        val governor = Governor(InMemoryStore())
        val next = governor.nextEvent(events)

        // Position 3 → EL = 2+3 = 5 ≤ VC=5 → must issue CONTRACT_STARTED(3)
        assertNotNull("CSL gate must allow position 3 (EL=5 ≤ VC=5)", next)
        assertEquals("Must issue CONTRACT_STARTED", EventTypes.CONTRACT_STARTED, next!!.type)
        assertEquals("Position must be 3", 3, next.payload["position"])
    }

    @Test
    fun `ATTACK-04c Governor rejects missing payload fields in CONTRACT_STARTED`() {
        // Attack: CONTRACT_STARTED with no contract_id → Governor cannot issue TASK_ASSIGNED
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 1)),
            // Missing contract_id!
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 1))
        )

        val governor = Governor(InMemoryStore())
        val next = governor.nextEvent(events)

        // Without contract_id, Governor cannot construct a valid TASK_ASSIGNED
        assertNull("Governor must not derive TASK_ASSIGNED from malformed CONTRACT_STARTED", next)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ATTACK-05 — TIME DRIFT
    //
    // Attempt: Inject runtime timestamps into events and verify replay ignores them.
    // Expected: Replay derives state from event TYPE and PAYLOAD only, not timestamps.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ATTACK-05a replay produces identical state regardless of event timestamps`() {
        // Attack: same events with different timestamps — replay must produce same state
        val eventsA = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"), "e0", 0L, 1000L),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1), "e1", 1L, 2000L),
            Event(EventTypes.CONTRACTS_READY, emptyMap(), "e2", 2L, 3000L)
        )

        val eventsB = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"), "e0", 0L, 9999999L),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1), "e1", 1L, 0L),
            Event(EventTypes.CONTRACTS_READY, emptyMap(), "e2", 2L, Long.MAX_VALUE)
        )

        val stateA = replay.deriveStructuralState(eventsA)
        val stateB = replay.deriveStructuralState(eventsB)

        assertEquals("GovernanceView must be identical regardless of timestamps",
            stateA.governanceView, stateB.governanceView)
        assertEquals("ExecutionView must be identical regardless of timestamps",
            stateA.executionView, stateB.executionView)
        assertEquals("AuditView must be identical regardless of timestamps",
            stateA.auditView, stateB.auditView)
    }

    @Test
    fun `ATTACK-05b replay produces identical state regardless of event ids`() {
        // Attack: same event types/payloads with different UUIDs → same state
        val eventsA = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"), "aaa-111", 0L, 0L),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2), "aaa-222", 1L, 0L)
        )

        val eventsB = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"), "zzz-999", 0L, 0L),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2), "zzz-888", 1L, 0L)
        )

        val stateA = replay.deriveStructuralState(eventsA)
        val stateB = replay.deriveStructuralState(eventsB)

        assertEquals("AuditView must match regardless of event ids",
            stateA.auditView, stateB.auditView)
        assertEquals("ExecutionView must match regardless of event ids",
            stateA.executionView, stateB.executionView)
    }

    @Test
    fun `ATTACK-05c replay produces identical state regardless of sequence numbers`() {
        // Attack: same events with different sequenceNumbers → same state
        val eventsA = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"), "e0", 0L),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1), "e1", 1L)
        )

        val eventsB = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"), "e0", 999L),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1), "e1", 1000L)
        )

        val stateA = replay.deriveStructuralState(eventsA)
        val stateB = replay.deriveStructuralState(eventsB)

        assertEquals("GovernanceView must be identical regardless of sequenceNumbers",
            stateA.governanceView, stateB.governanceView)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ATTACK-06 — REPLAY NON-DETERMINISM (RL-01 CORE)
    //
    // Attempt: Run same events twice → must produce identical ReplayStructuralState.
    //          Run events in different order → verify structural invariant.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ATTACK-06a same events replayed twice produce identical state`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "determinism")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2, "report_reference" to "rr-1")),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 2)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 2, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1")),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "t1", "executionStatus" to "SUCCESS")),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "t1"))
        )

        val stateA = replay.deriveStructuralState(events)
        val stateB = replay.deriveStructuralState(events)

        assertEquals("Same events replayed twice must produce identical GovernanceView",
            stateA.governanceView, stateB.governanceView)
        assertEquals("Same events replayed twice must produce identical ExecutionView",
            stateA.executionView, stateB.executionView)
        assertEquals("Same events replayed twice must produce identical AuditView",
            stateA.auditView, stateB.auditView)
        assertEquals("Full state must be identical",
            stateA, stateB)
    }

    @Test
    fun `ATTACK-06b same events replayed 5 times produce identical state`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "five-times")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3, "report_reference" to "rr")),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 3)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 3, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 3)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1")),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "t1", "executionStatus" to "SUCCESS")),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "t1")),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 1, "total" to 3, "contractId" to "c1", "report_reference" to "rr"))
        )

        val states = (1..5).map { replay.deriveStructuralState(events) }
        val reference = states[0]

        for (i in 1 until states.size) {
            assertEquals("Run ${i + 1} must match run 1 (GovernanceView)", reference.governanceView, states[i].governanceView)
            assertEquals("Run ${i + 1} must match run 1 (ExecutionView)", reference.executionView, states[i].executionView)
            assertEquals("Run ${i + 1} must match run 1 (AuditView)", reference.auditView, states[i].auditView)
        }
    }

    @Test
    fun `ATTACK-06c replay with full lifecycle is deterministic`() {
        // Full happy-path lifecycle: intent → contracts → execution → assembly → ICS
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "full")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1, "report_reference" to "rr")),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 1)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 1, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1")),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "t1", "executionStatus" to "SUCCESS")),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "t1")),
            Event(EventTypes.TASK_VALIDATED, mapOf("taskId" to "t1")),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 1, "total" to 1, "contractId" to "c1", "report_reference" to "rr")),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("total" to 1)),
            Event(EventTypes.ASSEMBLY_STARTED, mapOf("report_reference" to "rr")),
            Event(EventTypes.ASSEMBLY_VALIDATED, emptyMap()),
            Event(EventTypes.ASSEMBLY_COMPLETED, mapOf("report_reference" to "rr", "finalArtifactReference" to "fa1")),
            Event(EventTypes.ICS_STARTED, mapOf("report_reference" to "rr", "taskId" to "ics-1")),
            Event(EventTypes.ICS_COMPLETED, mapOf("taskId" to "ics-1"))
        )

        val state1 = replay.deriveStructuralState(events)
        val state2 = replay.deriveStructuralState(events)

        // Verify full determinism
        assertEquals("Full lifecycle replay must be deterministic", state1, state2)

        // Verify terminal state correctness
        assertTrue("Intent must be complete", state1.auditView.intent.structurallyComplete)
        assertTrue("Contracts must be generated", state1.auditView.contracts.generated)
        assertTrue("Assembly must be completed", state1.auditView.assembly.assemblyCompleted)
        assertTrue("ICS must be started", state1.executionView.icsStarted)
        assertTrue("ICS must be completed", state1.executionView.icsCompleted)
        assertEquals("Execution status must be success", "success", state1.executionView.executionStatus)
    }

    @Test
    fun `ATTACK-06d replay accumulates correctly across task lifecycle`() {
        // Verify counting is deterministic — assignedTasks, completedTasks, validatedTasks
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "count")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 2)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 2, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 2)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1")),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "t1", "executionStatus" to "SUCCESS")),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "t1")),
            Event(EventTypes.TASK_VALIDATED, mapOf("taskId" to "t1")),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 1, "total" to 2, "contractId" to "c1", "report_reference" to "rr")),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 2, "total" to 2, "contract_id" to "c2")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t2", "position" to 2, "total" to 2)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t2")),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "t2", "executionStatus" to "FAILURE")),
            Event(EventTypes.TASK_FAILED, mapOf("taskId" to "t2"))
        )

        val state = replay.deriveStructuralState(events)

        // Verify exact counts
        assertEquals("assignedTasks must be 2", 2, state.auditView.execution.assignedTasks)
        assertEquals("completedTasks must be 1", 1, state.auditView.execution.completedTasks)
        assertEquals("validatedTasks must be 1", 1, state.auditView.execution.validatedTasks)
        assertEquals("successfulTasks must be 1", 1, state.auditView.execution.successfulTasks)

        // Task status map must be exact
        assertEquals("t1 must be VALIDATED", "VALIDATED", state.executionView.taskStatus["t1"])
        assertEquals("t2 must be FAILED", "FAILED", state.executionView.taskStatus["t2"])

        // Execution status must reflect last TASK_EXECUTED which was FAILURE
        assertEquals("executionStatus must be 'failed' (last TASK_EXECUTED was FAILURE)",
            "failed", state.executionView.executionStatus)

        // Re-run to confirm determinism
        val state2 = replay.deriveStructuralState(events)
        assertEquals("Re-run must produce identical state", state, state2)
    }

    @Test
    fun `ATTACK-06e commit panel visibility is deterministic`() {
        // Test showCommitPanel logic: commitContractExists && !commitExecuted && !commitAborted
        val eventsWithCommit = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "commit")),
            Event(EventTypes.COMMIT_CONTRACT, mapOf("taskId" to "commit-1"))
        )
        val stateCommit = replay.deriveStructuralState(eventsWithCommit)
        assertTrue("showCommitPanel must be true when commit exists and not executed/aborted",
            stateCommit.executionView.showCommitPanel)

        // Add COMMIT_EXECUTED → panel must hide
        val eventsExecuted = eventsWithCommit + Event(EventTypes.COMMIT_EXECUTED, mapOf("taskId" to "commit-1"))
        val stateExecuted = replay.deriveStructuralState(eventsExecuted)
        assertFalse("showCommitPanel must be false after COMMIT_EXECUTED",
            stateExecuted.executionView.showCommitPanel)

        // Alternatively: COMMIT_ABORTED → panel must also hide
        val eventsAborted = eventsWithCommit + Event(EventTypes.COMMIT_ABORTED, mapOf("taskId" to "commit-1"))
        val stateAborted = replay.deriveStructuralState(eventsAborted)
        assertFalse("showCommitPanel must be false after COMMIT_ABORTED",
            stateAborted.executionView.showCommitPanel)
    }

    @Test
    fun `ATTACK-06f Governor transition is deterministic for same state`() {
        // Attack: run Governor transition multiple times on same state → identical output
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "det")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2)),
            Event(EventTypes.CONTRACTS_READY, emptyMap())
        )

        val governor = Governor(InMemoryStore())
        val results = (1..5).map { governor.nextEvent(events) }

        // All 5 runs must produce the same next event
        for (i in 1 until results.size) {
            assertEquals("Governor run ${i + 1} must match run 1 type",
                results[0]?.type, results[i]?.type)
            assertEquals("Governor run ${i + 1} must match run 1 payload",
                results[0]?.payload, results[i]?.payload)
        }
    }
}
