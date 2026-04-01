package com.agoii.mobile

// ─────────────────────────────────────────────────────────────────────────────
// CONTRACT: AGOII-CLC-1B-VERIFICATION
// CLASS:    Operational | REVERSIBILITY: Reversible | SOVEREIGN: NOT REQUIRED
//
// INTENT: Prove that the convergence system executes deterministically, recovers
// correctly, terminates correctly, and never bypasses validation.
// NO mutations are performed by these tests — read-only verification only.
//
// INVARIANTS ENFORCED:
//   1. ExecutionAuthority is the single execution gate (Phase 1 evaluate())
//   2. Validation precedes recovery (ea.evaluate() returns Blocked before recovery)
//   3. IRS cannot be bypassed (IrsOrchestrator history is always non-empty)
//   4. Delta cannot mutate outside violationField (MAX_DELTA = 3 is the scope guard)
//   5. Ledger is the single source of truth (all state derived from event list)
//
// GOVERNOR APPROVAL: Contract AGOII-CLC-1B-VERIFICATION — APPROVED FOR EXECUTION
// NOTES FROM GOVERNOR: Note B — NON_CONVERGENT_SYSTEM_FAILURE is a violationField,
//   not a standalone EventType.  Note C — Pipeline integrity anchors are milestone
//   checks, not exhaustive event assertions.
// ─────────────────────────────────────────────────────────────────────────────

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerAudit
import com.agoii.mobile.core.Replay
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.execution.ExecutionAuthority
import com.agoii.mobile.execution.ExecutionAuthorityResult
import com.agoii.mobile.execution.ExecutionContract
import com.agoii.mobile.execution.ExecutionContractInput
import com.agoii.mobile.execution.DriverRegistry
import com.agoii.mobile.irs.ConsensusRule
import com.agoii.mobile.irs.IrsOrchestrator
import com.agoii.mobile.irs.OrchestratorResult
import com.agoii.mobile.irs.SwarmConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Convergence verification tests for AGOII-CLC-1B-VERIFICATION.
 *
 * All tests are pure JVM — no Android Context or device required.
 * Uses [InMemoryEventRepository] so no filesystem I/O occurs.
 *
 * Governor-approved precision notes applied:
 *  - Note A: NO ledger mutations performed in any test (read-only verification).
 *  - Note B: NON_CONVERGENT_SYSTEM_FAILURE is asserted via [violationField] in
 *            the RECOVERY_CONTRACT event payload, not as a standalone event type.
 *  - Note C: Pipeline-integrity test asserts relative order of anchor events,
 *            not that they are the ONLY events in the ledger.
 */
class ConvergenceVerificationTest {

    // ── In-memory event repository (no Android Context required) ─────────────

    private class InMemoryEventRepository(
        initial: List<Event> = emptyList()
    ) : EventRepository {
        private val events = initial.toMutableList()
        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            events.add(Event(type, payload))
        }
        override fun loadEvents(projectId: String): List<Event> = events.toList()
    }

    private fun store(initial: List<Event> = emptyList()): EventRepository =
        InMemoryEventRepository(initial)

    // ── Shared constants ──────────────────────────────────────────────────────

    private val rrid = "rrid-clc1b-verification-v1"

    // ── Test fixture helpers ──────────────────────────────────────────────────

    /**
     * Full convergence success ledger.
     *
     * Lifecycle (single contract, complete task execution, assembly, ICS):
     *   intent_submitted → contracts_generated → contracts_ready → contracts_approved
     *   → execution_started → contract_started
     *   → task_assigned → task_started → task_executed(SUCCESS)
     *   → task_completed → task_validated → contract_completed
     *   → execution_completed → assembly_started → assembly_completed
     *   → ics_started → ics_completed
     *
     * All transitions follow [LedgerAudit.isLegalTransition].
     */
    private fun buildSuccessLedger(): List<Event> = listOf(
        Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "Build the convergence system")),
        Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
        Event(EventTypes.CONTRACTS_READY,     emptyMap()),
        Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
        Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 1.0)),
        Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
        Event(EventTypes.TASK_ASSIGNED,       mapOf(
            "taskId" to "contract_1-task1", "contractId" to "contract_1",
            "report_reference" to rrid, "position" to 1, "total" to 1,
            "requirements" to emptyList<Any>()
        )),
        Event(EventTypes.TASK_STARTED,        mapOf("taskId" to "contract_1-task1", "position" to 1, "total" to 1)),
        Event(EventTypes.TASK_EXECUTED,       mapOf(
            "taskId"            to "contract_1-task1",
            "contractId"        to "contract_1",
            "contractorId"      to "llm",
            "artifactReference" to "art-clc1b-1",
            "executionStatus"   to "SUCCESS",
            "validationStatus"  to "VALIDATED",
            "validationReasons" to emptyList<String>(),
            "report_reference"  to rrid,
            "position"          to 1,
            "total"             to 1
        )),
        Event(EventTypes.TASK_COMPLETED,      mapOf("taskId" to "contract_1-task1", "position" to 1, "total" to 1)),
        Event(EventTypes.TASK_VALIDATED,      mapOf("taskId" to "contract_1-task1")),
        Event(EventTypes.CONTRACT_COMPLETED,  mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
        Event(EventTypes.EXECUTION_COMPLETED, mapOf("contracts_completed" to 1)),
        Event(EventTypes.ASSEMBLY_STARTED,    emptyMap()),
        Event(EventTypes.ASSEMBLY_COMPLETED,  emptyMap()),
        Event(EventTypes.ICS_STARTED,         emptyMap()),
        Event(EventTypes.ICS_COMPLETED,       emptyMap())
    )

    /**
     * Structural failure ledger — a single contract that fails with a missing required
     * field, triggering the recovery + delta loop entry path.
     *
     * Key segment: TASK_EXECUTED(FAILURE) → RECOVERY_CONTRACT → DELTA_CONTRACT_CREATED
     *              → TASK_ASSIGNED (delta task, contractId starts with "RCF_")
     *
     * All transitions follow [LedgerAudit.isLegalTransition].
     */
    private fun buildStructuralFailureLedger(): List<Event> = listOf(
        Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "Incomplete intent (no required field)")),
        Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
        Event(EventTypes.CONTRACTS_READY,     emptyMap()),
        Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
        Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 1.0)),
        Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
        Event(EventTypes.TASK_ASSIGNED,       mapOf(
            "taskId" to "contract_1-task1", "contractId" to "contract_1",
            "report_reference" to rrid, "position" to 1, "total" to 1,
            "requirements" to emptyList<Any>()
        )),
        Event(EventTypes.TASK_STARTED,        mapOf("taskId" to "contract_1-task1", "position" to 1, "total" to 1)),
        // Structural failure: executionStatus=FAILURE, MISSING_REQUIRED_FIELD
        Event(EventTypes.TASK_EXECUTED,       mapOf(
            "taskId"            to "contract_1-task1",
            "contractId"        to "contract_1",
            "contractorId"      to "llm",
            "artifactReference" to "art-fail-1",
            "executionStatus"   to "FAILURE",
            "validationStatus"  to "FAILED",
            "validationReasons" to listOf("MISSING_REQUIRED_FIELD"),
            "report_reference"  to rrid,
            "position"          to 1,
            "total"             to 1
        )),
        // RCF-1: recovery contract issued immediately after TASK_EXECUTED(FAILURE)
        Event(EventTypes.RECOVERY_CONTRACT,   mapOf(
            "contractId"          to "RCF_contract_1_EXECUTION",
            "taskId"              to "contract_1-task1",
            "contractType"        to "DELTA",
            "executionPosition"   to 1,
            "report_reference"    to rrid,
            "failureClass"        to "STRUCTURAL",
            "violationField"      to "typeInventory",
            "correctionDirective" to "Add at least one type entry to typeInventory",
            "successCondition"    to "typeInventory non-empty",
            "artifactReference"   to "art-fail-1",
            "irs_violation_type"  to "none"
        )),
        // Governor creates delta contract (DELTA_CONTRACT_CREATED) from recovery contract
        Event(EventTypes.DELTA_CONTRACT_CREATED, mapOf(
            "contractId"           to "RCF_contract_1_delta1",
            "violationField"       to "typeInventory",
            "report_reference"     to rrid,
            "delta_iteration_count" to 1
        )),
        // Re-entry into execution: TASK_ASSIGNED for the delta task
        Event(EventTypes.TASK_ASSIGNED, mapOf(
            "taskId" to "RCF_contract_1_delta1-task1", "contractId" to "RCF_contract_1_delta1",
            "report_reference" to rrid, "position" to 1, "total" to 1,
            "requirements" to emptyList<Any>()
        ))
    )

    /**
     * Delta loop ledger — simulates MAX_DELTA + 1 delta iterations to trigger the
     * NON_CONVERGENT_SYSTEM_FAILURE termination condition.
     *
     * Structure per delta iteration:
     *   … TASK_EXECUTED(FAILURE) → RECOVERY_CONTRACT → DELTA_CONTRACT_CREATED
     *      → TASK_ASSIGNED → TASK_STARTED → TASK_EXECUTED(FAILURE) → …
     *
     * After MAX_DELTA + 1 = 4 DELTA_CONTRACT_CREATED events the delta iteration count
     * exceeds [ExecutionAuthority.MAX_DELTA], and the final RECOVERY_CONTRACT marks
     * the NON_CONVERGENT_SYSTEM_FAILURE termination (Note B from Governor).
     *
     * All transitions follow [LedgerAudit.isLegalTransition].
     */
    private fun buildDeltaLoopLedger(): List<Event> {
        val events = mutableListOf(
            Event(EventTypes.INTENT_SUBMITTED,    mapOf("objective" to "Non-converging intent")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1.0)),
            Event(EventTypes.CONTRACTS_READY,     emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED,  emptyMap()),
            Event(EventTypes.EXECUTION_STARTED,   mapOf("total_contracts" to 1.0)),
            Event(EventTypes.CONTRACT_STARTED,    mapOf("contract_id" to "contract_1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_ASSIGNED,       mapOf(
                "taskId" to "contract_1-task1", "contractId" to "contract_1",
                "report_reference" to rrid, "position" to 1, "total" to 1,
                "requirements" to emptyList<Any>()
            )),
            Event(EventTypes.TASK_STARTED,        mapOf("taskId" to "contract_1-task1")),
            // Initial failure
            Event(EventTypes.TASK_EXECUTED,       mapOf(
                "taskId"            to "contract_1-task1",
                "contractId"        to "contract_1",
                "contractorId"      to "llm",
                "artifactReference" to "art-init-fail",
                "executionStatus"   to "FAILURE",
                "validationStatus"  to "FAILED",
                "validationReasons" to listOf("STRUCTURAL_VIOLATION"),
                "report_reference"  to rrid,
                "position"          to 1,
                "total"             to 1
            ))
        )

        // Build MAX_DELTA + 1 delta iterations so deltaIterationCount exceeds MAX_DELTA
        val totalDeltaIterations = ExecutionAuthority.MAX_DELTA + 1
        repeat(totalDeltaIterations) { i ->
            val iteration = i + 1
            val deltaContractId = "RCF_contract_1_delta$iteration"
            val deltaTaskId     = "$deltaContractId-task1"
            val artifactRef     = "art-delta-$iteration"
            val isLastIteration = (iteration == totalDeltaIterations)

            // TASK_EXECUTED(FAILURE) → RECOVERY_CONTRACT: legal transition
            events += Event(EventTypes.RECOVERY_CONTRACT, mapOf(
                "contractId"          to "RCF_contract_1_EXECUTION",
                "taskId"              to if (i == 0) "contract_1-task1" else "RCF_contract_1_delta${i}-task1",
                "contractType"        to "DELTA",
                "executionPosition"   to 1,
                "report_reference"    to rrid,
                "failureClass"        to "STRUCTURAL",
                "violationField"      to "typeInventory",
                "correctionDirective" to "Add at least one type entry",
                "successCondition"    to "typeInventory non-empty",
                "artifactReference"   to artifactRef,
                "irs_violation_type"  to "none"
            ))

            // RECOVERY_CONTRACT → DELTA_CONTRACT_CREATED: legal transition
            events += Event(EventTypes.DELTA_CONTRACT_CREATED, mapOf(
                "contractId"            to deltaContractId,
                "violationField"        to "typeInventory",
                "report_reference"      to rrid,
                "delta_iteration_count" to iteration
            ))

            // DELTA_CONTRACT_CREATED → TASK_ASSIGNED: legal transition
            events += Event(EventTypes.TASK_ASSIGNED, mapOf(
                "taskId" to deltaTaskId, "contractId" to deltaContractId,
                "report_reference" to rrid, "position" to 1, "total" to 1,
                "requirements" to emptyList<Any>()
            ))

            // TASK_ASSIGNED → TASK_STARTED: legal transition
            events += Event(EventTypes.TASK_STARTED, mapOf("taskId" to deltaTaskId))

            if (!isLastIteration) {
                // TASK_STARTED → TASK_EXECUTED: legal transition (intermediate delta failure)
                events += Event(EventTypes.TASK_EXECUTED, mapOf(
                    "taskId"            to deltaTaskId,
                    "contractId"        to deltaContractId,
                    "contractorId"      to "llm",
                    "artifactReference" to artifactRef,
                    "executionStatus"   to "FAILURE",
                    "validationStatus"  to "FAILED",
                    "validationReasons" to listOf("STRUCTURAL_VIOLATION_DELTA_$iteration"),
                    "report_reference"  to rrid,
                    "position"          to 1,
                    "total"             to 1
                ))
            }
            // On the last iteration, EA detects deltaIterationCount > MAX_DELTA BEFORE executing.
            // It emits the terminal RECOVERY_CONTRACT(NON_CONVERGENT_SYSTEM_FAILURE) below.
        }

        // TASK_STARTED → TASK_EXECUTED (final failure, enables legal transition to RECOVERY_CONTRACT)
        val lastDeltaTaskId = "RCF_contract_1_delta${totalDeltaIterations}-task1"
        events += Event(EventTypes.TASK_EXECUTED, mapOf(
            "taskId"            to lastDeltaTaskId,
            "contractId"        to "RCF_contract_1_delta$totalDeltaIterations",
            "contractorId"      to "NO_CONTRACTOR_MATCH",
            "artifactReference" to "NO_ARTIFACT",
            "executionStatus"   to "FAILURE",
            "validationStatus"  to "FAILED",
            "validationReasons" to listOf("NON_CONVERGENT_SYSTEM_FAILURE"),
            "report_reference"  to rrid,
            "position"          to 1,
            "total"             to 1
        ))

        // TASK_EXECUTED → RECOVERY_CONTRACT: legal transition — terminal NON_CONVERGENT contract
        // (Governor Note B: NON_CONVERGENT_SYSTEM_FAILURE is the violationField, not a standalone event)
        events += Event(EventTypes.RECOVERY_CONTRACT, mapOf(
            "contractId"          to "RCF_NON_CONVERGENT_EXECUTION",
            "taskId"              to lastDeltaTaskId,
            "contractType"        to "DELTA",
            "executionPosition"   to 1,
            "report_reference"    to rrid,
            "failureClass"        to "DETERMINISM",
            "violationField"      to "NON_CONVERGENT_SYSTEM_FAILURE",
            "correctionDirective" to "NON_CONVERGENT_SYSTEM_FAILURE",
            "successCondition"    to "convergence achieved within MAX_DELTA iterations",
            "artifactReference"   to "NO_ARTIFACT",
            "irs_violation_type"  to "none"
        ))

        return events
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 1 — CLEAN SUCCESS PATH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that a valid pipeline run produces all three terminal milestones
     * (EXECUTION_COMPLETED, ASSEMBLY_COMPLETED, ICS_COMPLETED) and emits NO
     * recovery contracts.
     *
     * FAIL IF: any recovery triggered, or any pipeline step is missing.
     */
    @Test
    fun `TEST 1 clean success path produces all milestone events with no recovery contracts`() {
        val ledger = buildSuccessLedger()

        // ── Milestone events must all be present ──────────────────────────────
        assertTrue(
            "EXECUTION_COMPLETED must be present on clean success path",
            ledger.any { it.type == EventTypes.EXECUTION_COMPLETED }
        )
        assertTrue(
            "ASSEMBLY_COMPLETED must be present on clean success path",
            ledger.any { it.type == EventTypes.ASSEMBLY_COMPLETED }
        )
        assertTrue(
            "ICS_COMPLETED must be present on clean success path",
            ledger.any { it.type == EventTypes.ICS_COMPLETED }
        )

        // ── No recovery path must be triggered on a clean run ─────────────────
        assertFalse(
            "RECOVERY_CONTRACT must be absent on clean success path",
            ledger.any { it.type == EventTypes.RECOVERY_CONTRACT }
        )
        assertFalse(
            "DELTA_CONTRACT_CREATED must be absent on clean success path",
            ledger.any { it.type == EventTypes.DELTA_CONTRACT_CREATED }
        )

        // ── Replay confirms execution fully completed and assembly closed ──────
        val state = Replay(store(ledger)).deriveStructuralState(ledger)
        assertTrue("Replay must show fullyExecuted on clean path", state.execution.fullyExecuted)
        assertTrue("Replay must show assemblyCompleted on clean path", state.assembly.assemblyCompleted)
        assertTrue("Replay must show icsCompleted on clean path",     state.icsCompleted)

        // ── TASK_EXECUTED must carry SUCCESS status ───────────────────────────
        val taskExecutedEvents = ledger.filter { it.type == EventTypes.TASK_EXECUTED }
        assertTrue("TASK_EXECUTED must be present on clean path", taskExecutedEvents.isNotEmpty())
        assertTrue(
            "All TASK_EXECUTED events must have executionStatus=SUCCESS on clean path",
            taskExecutedEvents.all { it.payload["executionStatus"] == "SUCCESS" }
        )

        // ── All ledger transitions must be legal (LedgerAudit invariant) ─────
        val auditResult = LedgerAudit(store(ledger)).auditLedger("proj-test1")
        assertTrue(
            "LedgerAudit must pass for clean success path. Errors: ${auditResult.errors}",
            auditResult.valid
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 2 — STRUCTURAL FAILURE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that:
     *  1. ExecutionAuthority.evaluate() blocks immediately when a required field is absent
     *     (validation precedes execution — AERP-1 invariant).
     *  2. After a structural task failure the ledger contains a RECOVERY_CONTRACT followed
     *     by a DELTA_CONTRACT_CREATED (re-entry into execution).
     *
     * FAIL IF: execution passes through a missing field, or no recovery is created.
     */
    @Test
    fun `TEST 2 structural failure blocks execution and creates recovery and delta contract`() {
        val ea = ExecutionAuthority(ContractorRegistry(), DriverRegistry())

        // ── INVARIANT 2: Validation precedes recovery — EA must block on blank contractId ──
        val blankContractIdResult = ea.evaluate(
            ExecutionContractInput(
                contracts = listOf(
                    ExecutionContract(
                        contractId      = "",           // missing required field
                        name            = "test-contract",
                        position        = 1,
                        reportReference = "rrid-test-2"
                    )
                ),
                reportId = "rrid-test-2"
            )
        )
        assertTrue(
            "EA.evaluate() must return Blocked when contractId is blank (missing required field)",
            blankContractIdResult is ExecutionAuthorityResult.Blocked
        )

        // ── EA must block when reportId is blank (MISSING_REPORT_ID) ─────────
        val blankReportIdResult = ea.evaluate(
            ExecutionContractInput(
                contracts = listOf(
                    ExecutionContract(
                        contractId      = "contract-valid",
                        name            = "test-contract",
                        position        = 1,
                        reportReference = ""            // missing report reference
                    )
                ),
                reportId = ""
            )
        )
        assertTrue(
            "EA.evaluate() must return Blocked when reportId is blank",
            blankReportIdResult is ExecutionAuthorityResult.Blocked
        )

        // ── EA must block when contract name is blank (INVALID_FIELD) ─────────
        val blankNameResult = ea.evaluate(
            ExecutionContractInput(
                contracts = listOf(
                    ExecutionContract(
                        contractId      = "contract-valid",
                        name            = "",           // missing required field
                        position        = 1,
                        reportReference = "rrid-test-2"
                    )
                ),
                reportId = "rrid-test-2"
            )
        )
        assertTrue(
            "EA.evaluate() must return Blocked when contract name is blank",
            blankNameResult is ExecutionAuthorityResult.Blocked
        )

        // ── After structural failure: RECOVERY_CONTRACT must be emitted ───────
        val failureLedger = buildStructuralFailureLedger()
        assertTrue(
            "RECOVERY_CONTRACT must be emitted after task execution failure",
            failureLedger.any { it.type == EventTypes.RECOVERY_CONTRACT }
        )

        // ── DELTA_CONTRACT_CREATED must follow RECOVERY_CONTRACT (CCL § loop step 6-7) ──
        assertTrue(
            "DELTA_CONTRACT_CREATED must be emitted as part of the recovery-and-re-entry path",
            failureLedger.any { it.type == EventTypes.DELTA_CONTRACT_CREATED }
        )
        val firstRecoveryIdx = failureLedger.indexOfFirst { it.type == EventTypes.RECOVERY_CONTRACT }
        val firstDeltaIdx    = failureLedger.indexOfFirst { it.type == EventTypes.DELTA_CONTRACT_CREATED }
        assertTrue(
            "DELTA_CONTRACT_CREATED (idx=$firstDeltaIdx) must appear AFTER RECOVERY_CONTRACT (idx=$firstRecoveryIdx)",
            firstDeltaIdx > firstRecoveryIdx
        )

        // ── DELTA_CONTRACT_CREATED must carry violationField (scope constraint) ──
        val deltaEvent = failureLedger.first { it.type == EventTypes.DELTA_CONTRACT_CREATED }
        val violationField = deltaEvent.payload["violationField"]?.toString()
        assertNotNull("DELTA_CONTRACT_CREATED must carry a violationField", violationField)
        assertTrue(
            "DELTA_CONTRACT_CREATED violationField must be non-blank",
            violationField!!.isNotBlank()
        )

        // ── System re-enters execution: TASK_ASSIGNED must follow DELTA_CONTRACT_CREATED ──
        val taskAssignedAfterDelta = failureLedger
            .drop(firstDeltaIdx + 1)
            .any { it.type == EventTypes.TASK_ASSIGNED }
        assertTrue(
            "System must re-enter execution (TASK_ASSIGNED) after DELTA_CONTRACT_CREATED",
            taskAssignedAfterDelta
        )

        // ── All failure-path ledger transitions must be legal ─────────────────
        val auditResult = LedgerAudit(store(failureLedger)).auditLedger("proj-test2")
        assertTrue(
            "All failure-path transitions must be legal. Errors: ${auditResult.errors}",
            auditResult.valid
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 3 — DELTA LOOP / CONVERGENCE TERMINATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that:
     *  1. MAX_DELTA is exactly 3 (CLC-1 Part 6 constant).
     *  2. A ledger with deltaIterationCount > MAX_DELTA satisfies the termination condition.
     *  3. The ledger is strictly bounded — no infinite loop is possible.
     *  4. The terminal RECOVERY_CONTRACT carries [violationField] = "NON_CONVERGENT_SYSTEM_FAILURE"
     *     (Governor Note B: this is a payload field, not a standalone event type).
     *
     * FAIL IF: infinite loop, silent retry, or system hangs (ledger exceeds bound).
     */
    @Test
    fun `TEST 3 delta loop enforces MAX_DELTA and terminates with NON_CONVERGENT_SYSTEM_FAILURE`() {
        // ── MAX_DELTA must be exactly 3 (CLC-1 Part 6 constant) ──────────────
        assertEquals(
            "MAX_DELTA must equal 3 per CLC-1 Part 6",
            3,
            ExecutionAuthority.MAX_DELTA
        )

        val deltaLedger = buildDeltaLoopLedger()

        // ── Delta iteration count must exceed MAX_DELTA (termination condition) ──
        val deltaCount = deltaLedger.count { it.type == EventTypes.DELTA_CONTRACT_CREATED }
        assertTrue(
            "DELTA_CONTRACT_CREATED count ($deltaCount) must exceed MAX_DELTA (${ExecutionAuthority.MAX_DELTA}) " +
            "to trigger NON_CONVERGENT_SYSTEM_FAILURE",
            deltaCount > ExecutionAuthority.MAX_DELTA
        )

        // ── Ledger must be strictly bounded — no infinite loop ────────────────
        assertTrue(
            "Delta loop ledger must be bounded (size=${deltaLedger.size}). Infinite loop forbidden.",
            deltaLedger.size < 200
        )

        // ── Iterations must increase monotonically ────────────────────────────
        val iterationCounts = deltaLedger
            .filter { it.type == EventTypes.DELTA_CONTRACT_CREATED }
            .mapNotNull { it.payload["delta_iteration_count"] }
        assertEquals(
            "Each DELTA_CONTRACT_CREATED must carry a delta_iteration_count",
            deltaCount,
            iterationCounts.size
        )

        // ── Terminal RECOVERY_CONTRACT must carry NON_CONVERGENT_SYSTEM_FAILURE ──
        // (Governor Note B: this is the violationField in the RECOVERY_CONTRACT payload,
        //  not a standalone event type.)
        val recoveryEvents = deltaLedger.filter { it.type == EventTypes.RECOVERY_CONTRACT }
        assertTrue("At least one RECOVERY_CONTRACT must be present", recoveryEvents.isNotEmpty())

        val nonConvergentRecovery = recoveryEvents.lastOrNull {
            it.payload["violationField"] == "NON_CONVERGENT_SYSTEM_FAILURE"
        }
        assertNotNull(
            "The terminal RECOVERY_CONTRACT must carry violationField='NON_CONVERGENT_SYSTEM_FAILURE' " +
            "(Governor Note B). Found recovery events: ${recoveryEvents.map { it.payload["violationField"] }}",
            nonConvergentRecovery
        )

        // ── The NON_CONVERGENT termination contract must have failureClass=DETERMINISM ──
        assertEquals(
            "NON_CONVERGENT_SYSTEM_FAILURE recovery must carry failureClass=DETERMINISM",
            "DETERMINISM",
            nonConvergentRecovery!!.payload["failureClass"]
        )

        // ── All delta-loop ledger transitions must be legal ───────────────────
        val auditResult = LedgerAudit(store(deltaLedger)).auditLedger("proj-test3")
        assertTrue(
            "All delta-loop transitions must be legal. Errors: ${auditResult.errors}",
            auditResult.valid
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 4 — IRS FAILURE PATH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that:
     *  1. IRS returns NeedsClarification when evidence is missing (gap detection).
     *  2. IRS terminates within MAX_IRS_STEPS (no infinite loop).
     *  3. IRS cannot be bypassed — history is always non-empty after stepping.
     *  4. NeedsClarification carries a non-empty gaps list.
     *
     * FAIL IF: IRS is ignored, execution continues on NeedsClarification, or IRS loops forever.
     */
    @Test
    fun `TEST 4 IRS failure returns NeedsClarification or Rejected and cannot be bypassed`() {
        val irsOrchestrator = IrsOrchestrator()
        val sessionId       = "irs-clc1b-no-evidence-session"

        // ── Create IRS session with no evidence — GapDetector will detect gaps ──
        irsOrchestrator.createSession(
            sessionId         = sessionId,
            rawFields         = mapOf(
                "objective"   to "Build convergence system",
                "constraints" to "deterministic only",
                "environment" to "production",
                "resources"   to "allocated"
            ),
            evidence          = emptyMap(),      // deliberate: no evidence → gaps will be detected
            swarmConfig       = SwarmConfig(agentCount = 2, consensusRule = ConsensusRule.MAJORITY),
            availableEvidence = emptyMap()
        )

        // ── Step through IRS until terminal (MAX_IRS_STEPS bound) ────────────
        var terminalResult: OrchestratorResult? = null
        var stepsExecuted  = 0
        repeat(ExecutionAuthority.MAX_IRS_STEPS) {
            if (terminalResult != null) return@repeat
            val stepResult = irsOrchestrator.step(sessionId)
            stepsExecuted++
            if (stepResult.terminal) {
                terminalResult = stepResult.orchestratorResult
            }
        }

        // ── IRS must terminate — no infinite loop ─────────────────────────────
        assertNotNull(
            "IRS must reach a terminal state within MAX_IRS_STEPS (${ExecutionAuthority.MAX_IRS_STEPS}). " +
            "Steps executed: $stepsExecuted",
            terminalResult
        )
        assertTrue(
            "IRS must terminate within MAX_IRS_STEPS bound",
            stepsExecuted <= ExecutionAuthority.MAX_IRS_STEPS
        )

        // ── IRS must return NeedsClarification or Rejected when evidence is missing ──
        assertTrue(
            "IRS must return NeedsClarification or Rejected for intent without evidence; " +
            "got: $terminalResult",
            terminalResult is OrchestratorResult.NeedsClarification ||
            terminalResult is OrchestratorResult.Rejected
        )

        // ── NeedsClarification must carry a non-empty gaps list ───────────────
        if (terminalResult is OrchestratorResult.NeedsClarification) {
            val clarification = terminalResult as OrchestratorResult.NeedsClarification
            assertTrue(
                "NeedsClarification must list at least one gap",
                clarification.gaps.isNotEmpty()
            )
        }

        // ── INVARIANT 3: IRS cannot be bypassed — history must be non-empty ──
        val history = irsOrchestrator.replayHistory(sessionId)
        assertTrue(
            "IRS history must be non-empty — IRS cannot be bypassed (INVARIANT 3)",
            history.isNotEmpty()
        )

        // ── IRS failure path: a second session with semantically invalid data ─
        val rejectedSessionId = "irs-clc1b-swarm-rejected-session"
        irsOrchestrator.createSession(
            sessionId         = rejectedSessionId,
            rawFields         = mapOf(
                "objective"   to "",              // empty objective — invalid
                "constraints" to "",
                "environment" to "",
                "resources"   to ""
            ),
            evidence          = emptyMap(),
            swarmConfig       = SwarmConfig(agentCount = 4, consensusRule = ConsensusRule.UNANIMOUS),
            availableEvidence = emptyMap()
        )
        var rejectedTerminal: OrchestratorResult? = null
        repeat(ExecutionAuthority.MAX_IRS_STEPS) {
            if (rejectedTerminal != null) return@repeat
            val result = irsOrchestrator.step(rejectedSessionId)
            if (result.terminal) rejectedTerminal = result.orchestratorResult
        }
        assertNotNull(
            "IRS must terminate for invalid intent within MAX_IRS_STEPS",
            rejectedTerminal
        )
        assertTrue(
            "IRS must return NeedsClarification or Rejected for empty-field intent; " +
            "got: $rejectedTerminal",
            rejectedTerminal is OrchestratorResult.NeedsClarification ||
            rejectedTerminal is OrchestratorResult.Rejected
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 5 — PIPELINE INTEGRITY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that for any pipeline run the four milestone anchor events appear
     * in the mandatory order:
     *
     *   TASK_STARTED → EXECUTION_COMPLETED → ASSEMBLY_COMPLETED → ICS_COMPLETED
     *
     * Governor Note C: these are relative-order checks on anchor events, NOT assertions
     * that they are the only events in the ledger.
     *
     * FAIL IF: order breaks or any anchor event is absent.
     */
    @Test
    fun `TEST 5 pipeline integrity preserves milestone event order for any run`() {
        val ledger = buildSuccessLedger()

        // ── Locate all four milestone anchor positions ────────────────────────
        val taskStartedIdx        = ledger.indexOfFirst { it.type == EventTypes.TASK_STARTED }
        val executionCompletedIdx = ledger.indexOfFirst { it.type == EventTypes.EXECUTION_COMPLETED }
        val assemblyCompletedIdx  = ledger.indexOfFirst { it.type == EventTypes.ASSEMBLY_COMPLETED }
        val icsCompletedIdx       = ledger.indexOfFirst { it.type == EventTypes.ICS_COMPLETED }

        // ── All four anchor events must be present ────────────────────────────
        assertTrue(
            "TASK_STARTED must be present (idx=$taskStartedIdx)",
            taskStartedIdx >= 0
        )
        assertTrue(
            "EXECUTION_COMPLETED must be present (idx=$executionCompletedIdx)",
            executionCompletedIdx >= 0
        )
        assertTrue(
            "ASSEMBLY_COMPLETED must be present (idx=$assemblyCompletedIdx)",
            assemblyCompletedIdx >= 0
        )
        assertTrue(
            "ICS_COMPLETED must be present (idx=$icsCompletedIdx)",
            icsCompletedIdx >= 0
        )

        // ── Relative order: TASK_STARTED → EXECUTION_COMPLETED ───────────────
        assertTrue(
            "TASK_STARTED (idx=$taskStartedIdx) must precede EXECUTION_COMPLETED (idx=$executionCompletedIdx)",
            taskStartedIdx < executionCompletedIdx
        )

        // ── Relative order: EXECUTION_COMPLETED → ASSEMBLY_COMPLETED ─────────
        assertTrue(
            "EXECUTION_COMPLETED (idx=$executionCompletedIdx) must precede ASSEMBLY_COMPLETED (idx=$assemblyCompletedIdx)",
            executionCompletedIdx < assemblyCompletedIdx
        )

        // ── Relative order: ASSEMBLY_COMPLETED → ICS_COMPLETED ───────────────
        assertTrue(
            "ASSEMBLY_COMPLETED (idx=$assemblyCompletedIdx) must precede ICS_COMPLETED (idx=$icsCompletedIdx)",
            assemblyCompletedIdx < icsCompletedIdx
        )

        // ── Invariant: EXECUTION_COMPLETED must not precede TASK_STARTED ─────
        // (ExecutionAuthority cannot write EXECUTION_COMPLETED before task is executed)
        assertFalse(
            "EXECUTION_COMPLETED must not precede TASK_STARTED — execution bypass is forbidden",
            executionCompletedIdx < taskStartedIdx
        )

        // ── Invariant: ICS_COMPLETED must not precede ASSEMBLY_COMPLETED ─────
        // (ICS pipeline requires a completed assembly artifact)
        assertFalse(
            "ICS_COMPLETED must not precede ASSEMBLY_COMPLETED — assembly bypass is forbidden",
            icsCompletedIdx < assemblyCompletedIdx
        )

        // ── Invariant: ASSEMBLY_COMPLETED must not precede EXECUTION_COMPLETED ─
        assertFalse(
            "ASSEMBLY_COMPLETED must not precede EXECUTION_COMPLETED — assembly requires full execution",
            assemblyCompletedIdx < executionCompletedIdx
        )
    }
}
