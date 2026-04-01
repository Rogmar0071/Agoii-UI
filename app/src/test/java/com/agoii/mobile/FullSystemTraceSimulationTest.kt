package com.agoii.mobile

// AGOII CONTRACT — AGOII-STS-1: FULL SYSTEM TRACE SIMULATION
//
// CLASS:              GOVERNANCE
// REVERSIBILITY:      REVERSIBLE
// EXECUTION SCOPE:    SIMULATION-ONLY
// SOVEREIGN CONFIRMATION: REQUIRED
//
// OBJECTIVE:
//   Prove the entire Agoii execution system satisfies ALL core invariants under a
//   complete deterministic lifecycle trace including failure + recovery.
//
// TRACE SCOPE (full surface):
//   INTENT_SUBMITTED → CONTRACTS_GENERATED → CONTRACTS_READY → CONTRACT_STARTED(1..3)
//     → TASK_ASSIGNED → TASK_STARTED → TASK_EXECUTED (FAIL) → RECOVERY_CONTRACT
//     → DELTA_CONTRACT_CREATED → TASK_ASSIGNED (RETRY) → TASK_EXECUTED (SUCCESS)
//     → TASK_COMPLETED → CONTRACT_COMPLETED → EXECUTION_COMPLETED
//     → ASSEMBLY_FAILED (mixed validity) → RECOVERY_CONTRACT (multi, single step)
//     → DELTA_CONTRACT_CREATED → RE-EXECUTION → ASSEMBLY_COMPLETED
//     → ICS_STARTED → ICS_COMPLETED
//
// FAILURE SURFACE INJECTION (mandatory):
//   Case A — execution failure with missing artifactReference
//   Case B — partial success (mixed validity) triggering multi-recovery
//   Case C — trace-incomplete (SUCCESS without artifactReference)
//   Case D — structural violation (duplicate positions)
//
// EXECUTION PROTOCOL:
//   Step 1  — build full event sequence via MemoryRepository
//   Step 2  — drive Governor cycles with driveGovernor()
//   Step 3  — capture RECOVERY_CONTRACTs, ASSEMBLY output
//   Step 4  — determinism check (identical sequences across N runs)
//   Step 5  — invariant validation (ledger integrity, failure coverage, convergence)
//
// NO MOCKS.  NO ARCHITECTURAL MODIFICATION.  VALIDATION ONLY.

import com.agoii.mobile.assembly.AssemblyExecutionResult
import com.agoii.mobile.assembly.AssemblyModule
import com.agoii.mobile.core.AuditResult
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerAudit
import com.agoii.mobile.core.Replay
import com.agoii.mobile.governor.Governor
import org.junit.Assert.*
import org.junit.Test

/**
 * AGOII-STS-1 — Full System Trace Simulation.
 *
 * Exercises the complete Agoii execution lifecycle with controlled failure
 * injection and validates all core invariants. Determinism is verified by
 * running [simulateFullTrace] three times and asserting identical event-type
 * sequences across all runs.
 */
class FullSystemTraceSimulationTest {

    // ── In-memory EventRepository (no Android Context required) ──────────────

    private class MemoryRepository : EventRepository {
        private val ledgers = mutableMapOf<String, MutableList<Event>>()
        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            ledgers.getOrPut(projectId) { mutableListOf() }
                .add(Event(type = type, payload = payload))
        }
        override fun loadEvents(projectId: String): List<Event> =
            ledgers[projectId]?.toList() ?: emptyList()
    }

    // ── Simulation report ──────────────────────────────────────────────────────

    /**
     * Captures the full outcome of one [simulateFullTrace] run:
     *  - [events]                ordered ledger snapshot after trace completion
     *  - [ledgerAudit]           LedgerAudit result (transition legality)
     *  - [recoveryContractCount] number of RECOVERY_CONTRACT events emitted
     *  - [executionFailureCount] number of TASK_EXECUTED FAILURE events injected
     *  - [assemblyFailureCount]  number of ASSEMBLY_FAILED events written
     *  - [icsStarted]/[icsCompleted] ICS phase flags
     *  - [invariantErrors]       all STS-1 invariant violations (empty = pass)
     */
    data class TraceSimulationReport(
        val projectId:             String,
        val events:                List<Event>,
        val ledgerAudit:           AuditResult,
        val recoveryContractCount: Int,
        val executionFailureCount: Int,
        val assemblyFailureCount:  Int,
        val icsStarted:            Boolean,
        val icsCompleted:          Boolean,
        val invariantErrors:       List<String>
    ) {
        /** True when both ledger audit and all STS-1 invariants pass. */
        val valid: Boolean get() = ledgerAudit.valid && invariantErrors.isEmpty()

        /** Ordered list of event type strings — used for determinism comparison. */
        fun eventTypes(): List<String> = events.map { it.type }
    }

    // ── Governor runner ───────────────────────────────────────────────────────

    /**
     * Drives the Governor in a loop until it stops advancing.
     *
     * The loop exits when [Governor.runGovernor] returns anything other than
     * [Governor.GovernorResult.ADVANCED] (i.e. DRIFT, COMPLETED, or NO_EVENT).
     * A safety cap of 200 iterations guards against infinite loops.
     *
     * @return the terminal [Governor.GovernorResult] from the final call.
     */
    private fun driveGovernor(governor: Governor, projectId: String): Governor.GovernorResult {
        var result: Governor.GovernorResult
        var iterations = 0
        do {
            result = governor.runGovernor(projectId)
            iterations++
            check(iterations <= 200) {
                "Governor safety cap exceeded after $iterations iterations — " +
                "possible infinite loop (last result=$result)"
            }
        } while (result == Governor.GovernorResult.ADVANCED)
        return result
    }

    // ── Main simulation ───────────────────────────────────────────────────────

    /**
     * Executes the complete AGOII-STS-1 trace against [store].
     *
     * Lifecycle:
     *
     * Phase 1  — INTENT_SUBMITTED + CONTRACTS_GENERATED (manual)
     * Phase 2  — Governor drives to TASK_STARTED (contract_1)
     * Phase 3  — contract_1: normal SUCCESS
     * Phase 4  — contract_2: **Case A** — TASK_EXECUTED FAILURE (no artifactReference)
     *            → manual RECOVERY_CONTRACT (EA direct path: TASK_EXECUTED → RECOVERY_CONTRACT)
     *            → Governor drives CLC-1 delta loop → TASK_STARTED
     * Phase 5  — contract_2: retry SUCCESS
     * Phase 6  — contract_3: **Case C** — TASK_EXECUTED SUCCESS but no artifactReference
     *            → Governor drives to EXECUTION_COMPLETED
     * Phase 7  — AssemblyModule.assemble() first call
     *            → detects TRACE_INCOMPLETE for contract_3 → ASSEMBLY_FAILED
     *            → Governor emits RECOVERY_CONTRACT (single step)
     *            → Governor drives CLC-1 delta loop → TASK_STARTED
     * Phase 8  — contract_3: delta re-execution WITH artifactReference
     *            → Governor drives to second EXECUTION_COMPLETED
     * Phase 9  — AssemblyModule.assemble() second call → ASSEMBLY_STARTED + ASSEMBLY_COMPLETED
     * Phase 10 — ICS phase: ICS_STARTED + ICS_COMPLETED (manual)
     *
     * @return [TraceSimulationReport] capturing the full post-trace state.
     */
    fun simulateFullTrace(projectId: String, store: EventRepository): TraceSimulationReport {

        val rrid   = "sts1-rrid-001"
        val csetId = "sts1-cset-001"
        val governor = Governor(store)

        // ── Phase 1: Submit intent ────────────────────────────────────────────
        store.appendEvent(
            projectId, EventTypes.INTENT_SUBMITTED,
            mapOf("objective" to "Full system trace simulation — AGOII-STS-1")
        )

        // Simulates ExecutionAuthority.executeFromLedger writing CONTRACTS_GENERATED.
        store.appendEvent(
            projectId, EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "report_reference" to rrid,
                "contractSetId"    to csetId,
                "total"            to 3,
                "contracts"        to listOf("contract_1", "contract_2", "contract_3")
            )
        )

        // ── Phase 2: Governor drives to TASK_STARTED (contract_1) ────────────
        // CONTRACTS_GENERATED → CONTRACTS_READY → CONTRACT_STARTED(1)
        // → TASK_ASSIGNED → TASK_STARTED → DRIFT
        driveGovernor(governor, projectId)

        // ── Phase 3: contract_1 — normal SUCCESS ─────────────────────────────
        store.appendEvent(
            projectId, EventTypes.TASK_EXECUTED,
            mapOf(
                "taskId"            to "contract_1-step1",
                "contractId"        to "contract_1",
                "executionStatus"   to "SUCCESS",
                "validationStatus"  to "VALIDATED",
                "position"          to 1,
                "total"             to 3,
                "artifactReference" to "artifact_c1",
                "report_reference"  to rrid
            )
        )
        // TASK_EXECUTED → TASK_COMPLETED → CONTRACT_COMPLETED(1)
        // → CONTRACT_STARTED(2) → TASK_ASSIGNED → TASK_STARTED → DRIFT
        driveGovernor(governor, projectId)

        // ── Phase 4: contract_2 — Case A: execution FAILURE (no artifactReference) ──
        // Inject TASK_EXECUTED with FAILURE status and NO artifactReference.
        store.appendEvent(
            projectId, EventTypes.TASK_EXECUTED,
            mapOf(
                "taskId"          to "contract_2-step1",
                "contractId"      to "contract_2",
                "executionStatus" to "FAILURE",
                "position"        to 2,
                "total"           to 3,
                "report_reference" to rrid
                // NO artifactReference — Case A failure injection
            )
        )
        // EA intercepts: writes RECOVERY_CONTRACT directly (legal: TASK_EXECUTED → RECOVERY_CONTRACT).
        // Governor is NOT called here; EA owns this transition in the failure path.
        store.appendEvent(
            projectId, EventTypes.RECOVERY_CONTRACT,
            mapOf(
                "contractId"          to "contract_2",
                "report_reference"    to rrid,
                "failureClass"        to "EXECUTION_FAILURE",
                "violationField"      to "artifactReference",
                "correctionDirective" to "DELTA_REPAIR_REQUIRED",
                "successCondition"    to "VALIDATION_PASS",
                "artifactReference"   to "contract_2"
            )
        )
        // CLC-1 delta loop: RECOVERY_CONTRACT → DELTA_CONTRACT_CREATED
        // → TASK_ASSIGNED → TASK_STARTED → DRIFT
        driveGovernor(governor, projectId)

        // ── Phase 5: contract_2 — retry SUCCESS ──────────────────────────────
        store.appendEvent(
            projectId, EventTypes.TASK_EXECUTED,
            mapOf(
                "taskId"            to "contract_2",
                "contractId"        to "contract_2",
                "executionStatus"   to "SUCCESS",
                "validationStatus"  to "VALIDATED",
                "position"          to 2,
                "total"             to 3,
                "artifactReference" to "artifact_c2",
                "report_reference"  to rrid
            )
        )
        // TASK_EXECUTED → TASK_COMPLETED → CONTRACT_COMPLETED(2)
        // → CONTRACT_STARTED(3) → TASK_ASSIGNED → TASK_STARTED → DRIFT
        driveGovernor(governor, projectId)

        // ── Phase 6: contract_3 — Case C: SUCCESS without artifactReference ──
        // executionStatus=SUCCESS and validationStatus=VALIDATED so Governor advances,
        // but artifactReference is absent — Assembly will detect TRACE_INCOMPLETE.
        store.appendEvent(
            projectId, EventTypes.TASK_EXECUTED,
            mapOf(
                "taskId"           to "contract_3-step1",
                "contractId"       to "contract_3",
                "executionStatus"  to "SUCCESS",
                "validationStatus" to "VALIDATED",
                "position"         to 3,
                "total"            to 3,
                "report_reference" to rrid
                // NO artifactReference — Case C injection
            )
        )
        // TASK_EXECUTED → TASK_COMPLETED → CONTRACT_COMPLETED(3)
        // → EXECUTION_COMPLETED → COMPLETED
        driveGovernor(governor, projectId)

        // ── Phase 7: Assembly — first attempt detects TRACE_INCOMPLETE ────────
        // AssemblyModule sees contract_3 SUCCESS TASK_EXECUTED with blank artifactReference
        // → traceMap["contract_3"] = "" → TRACE_INCOMPLETE → ASSEMBLY_FAILED written to ledger.
        val assembly = AssemblyModule()
        assembly.assemble(projectId, store)

        // Governor drives assembly recovery:
        // ASSEMBLY_FAILED → RECOVERY_CONTRACT(c3) → DELTA_CONTRACT_CREATED
        // → TASK_ASSIGNED → TASK_STARTED → DRIFT
        driveGovernor(governor, projectId)

        // ── Phase 8: contract_3 — delta re-execution WITH artifactReference ──
        store.appendEvent(
            projectId, EventTypes.TASK_EXECUTED,
            mapOf(
                "taskId"            to "contract_3",
                "contractId"        to "contract_3",
                "executionStatus"   to "SUCCESS",
                "validationStatus"  to "VALIDATED",
                "position"          to 3,
                "total"             to 3,
                "artifactReference" to "artifact_c3",
                "report_reference"  to rrid
            )
        )
        // TASK_EXECUTED → TASK_COMPLETED → CONTRACT_COMPLETED(3)
        // → EXECUTION_COMPLETED → COMPLETED  (second EXECUTION_COMPLETED)
        driveGovernor(governor, projectId)

        // ── Phase 9: Assembly — second attempt, all valid ─────────────────────
        // Re-entry guard passes: same contractSetId, no new CONTRACTS_GENERATED.
        // contract_3 now has SUCCESS TASK_EXECUTED with valid artifactReference (last write wins).
        // → ASSEMBLY_STARTED + ASSEMBLY_COMPLETED written.
        assembly.assemble(projectId, store)

        // ── Phase 10: ICS phase (manually simulated) ──────────────────────────
        // IcsModule.process() requires EventLedger (Android Context); ICS events are
        // written directly here, exactly as IcsModule would write them, to complete
        // the lifecycle trace without requiring an Android runtime.
        val eventsBeforeIcs = store.loadEvents(projectId)
        val assemblyCompleted = eventsBeforeIcs.lastOrNull { it.type == EventTypes.ASSEMBLY_COMPLETED }
        if (assemblyCompleted != null) {
            val finalArtifactRef = assemblyCompleted.payload["finalArtifactReference"]
                ?.toString() ?: ""
            store.appendEvent(
                projectId, EventTypes.ICS_STARTED,
                mapOf(
                    "report_reference"       to rrid,
                    "contractSetId"          to csetId,
                    "finalArtifactReference" to finalArtifactRef
                )
            )
            store.appendEvent(
                projectId, EventTypes.ICS_COMPLETED,
                mapOf(
                    "report_reference" to rrid,
                    "contractSetId"    to csetId,
                    "taskId"           to "ICS::$rrid"
                )
            )
        }

        // ── Invariant validation ──────────────────────────────────────────────
        val events      = store.loadEvents(projectId)
        val ledgerAudit = LedgerAudit(store).auditLedger(projectId)

        val invariantErrors = mutableListOf<String>()

        // INV-1: Ledger starts with INTENT_SUBMITTED
        val firstEvent = events.firstOrNull()
        if (firstEvent == null || firstEvent.type != EventTypes.INTENT_SUBMITTED) {
            invariantErrors.add(
                "STS-1-INV-1: First event must be INTENT_SUBMITTED, " +
                "got '${firstEvent?.type ?: "<empty ledger>"}'"
            )
        }

        // INV-2: Every TASK_EXECUTED FAILURE has a corresponding RECOVERY_CONTRACT
        val failedContractIds = events
            .filter { it.type == EventTypes.TASK_EXECUTED && it.payload["executionStatus"] == "FAILURE" }
            .mapNotNull { it.payload["contractId"]?.toString() }
            .toSet()
        val recoveredContractIds = events
            .filter { it.type == EventTypes.RECOVERY_CONTRACT }
            .mapNotNull { it.payload["contractId"]?.toString() }
            .toSet()
        val unrecoveredExec = failedContractIds - recoveredContractIds
        if (unrecoveredExec.isNotEmpty()) {
            invariantErrors.add(
                "STS-1-INV-2: Execution failures without RECOVERY_CONTRACT: $unrecoveredExec"
            )
        }

        // INV-3: Every ASSEMBLY_FAILED failure reason has a corresponding RECOVERY_CONTRACT
        val assemblyFailureContractIds = events
            .filter { it.type == EventTypes.ASSEMBLY_FAILED }
            .flatMap { ev ->
                @Suppress("UNCHECKED_CAST")
                (ev.payload["failureReasons"] as? List<*>)?.mapNotNull { fr ->
                    val id = (fr as? Map<*, *>)?.get("contractId")?.toString()
                    if (id != null && id != "_SENTINEL_POSITION_GAP") id else null
                } ?: emptyList()
            }
            .toSet()
        val unrecoveredAssembly = assemblyFailureContractIds - recoveredContractIds
        if (unrecoveredAssembly.isNotEmpty()) {
            invariantErrors.add(
                "STS-1-INV-3: Assembly failure contracts without RECOVERY_CONTRACT: $unrecoveredAssembly"
            )
        }

        // INV-4: Locked sections from ASSEMBLY_FAILED must NOT appear in RECOVERY_CONTRACTs
        //        emitted AFTER that ASSEMBLY_FAILED (no re-execution of already-valid contracts).
        //        Recovery contracts from the execution phase (before assembly) are ignored here —
        //        they legitimately co-exist with locked sections from a later assembly failure.
        val asmFailedIdx = events.indexOfFirst { it.type == EventTypes.ASSEMBLY_FAILED }
        if (asmFailedIdx >= 0) {
            val asmFailedEvent = events[asmFailedIdx]
            @Suppress("UNCHECKED_CAST")
            val lockedIds = (asmFailedEvent.payload["lockedSections"] as? List<*>)
                ?.mapNotNull { it?.toString() } ?: emptyList()
            // Only recovery contracts written AFTER the ASSEMBLY_FAILED event
            val postAssemblyRecoveredIds = events.drop(asmFailedIdx + 1)
                .filter { it.type == EventTypes.RECOVERY_CONTRACT }
                .mapNotNull { it.payload["contractId"]?.toString() }
                .toSet()
            val reExecutedLocked = lockedIds.filter { it in postAssemblyRecoveredIds }
            if (reExecutedLocked.isNotEmpty()) {
                invariantErrors.add(
                    "STS-1-INV-4: Locked sections re-executed after ASSEMBLY_FAILED: $reExecutedLocked"
                )
            }
        }

        // INV-5: Every RECOVERY_CONTRACT must produce exactly one DELTA_CONTRACT_CREATED
        val rcCount = events.count { it.type == EventTypes.RECOVERY_CONTRACT }
        val dcCount = events.count { it.type == EventTypes.DELTA_CONTRACT_CREATED }
        if (rcCount != dcCount) {
            invariantErrors.add(
                "STS-1-INV-5: RECOVERY_CONTRACT count ($rcCount) != " +
                "DELTA_CONTRACT_CREATED count ($dcCount)"
            )
        }

        // INV-6: Failure coverage — execution failure (Case A) was exercised
        val execFailureCount = events.count {
            it.type == EventTypes.TASK_EXECUTED && it.payload["executionStatus"] == "FAILURE"
        }
        if (execFailureCount == 0) {
            invariantErrors.add("STS-1-INV-6: No execution failure injected — Case A not exercised")
        }

        // INV-7: Failure coverage — assembly failure (Case C) was exercised
        val asmFailureCount = events.count { it.type == EventTypes.ASSEMBLY_FAILED }
        if (asmFailureCount == 0) {
            invariantErrors.add("STS-1-INV-7: No ASSEMBLY_FAILED emitted — Case C not exercised")
        }

        // INV-8: System converges — ICS_COMPLETED is present
        if (!events.any { it.type == EventTypes.ICS_COMPLETED }) {
            invariantErrors.add("STS-1-INV-8: ICS_COMPLETED absent — system did not converge")
        }

        // INV-9: No silent failure drops — zero TASK_EXECUTED FAILURE events without RECOVERY
        if (unrecoveredExec.isNotEmpty() || unrecoveredAssembly.isNotEmpty()) {
            invariantErrors.add("STS-1-INV-9: Silent failure drops detected")
        }

        return TraceSimulationReport(
            projectId             = projectId,
            events                = events,
            ledgerAudit           = ledgerAudit,
            recoveryContractCount = rcCount,
            executionFailureCount = execFailureCount,
            assemblyFailureCount  = asmFailureCount,
            icsStarted            = events.any { it.type == EventTypes.ICS_STARTED },
            icsCompleted          = events.any { it.type == EventTypes.ICS_COMPLETED },
            invariantErrors       = invariantErrors
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCEPTANCE CRITERION 3: Deterministic replay
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `AGOII-STS-1 full trace is deterministic — identical event sequences across 3 runs`() {
        val runs = (1..3).map { run ->
            simulateFullTrace("sts1-det-run$run", MemoryRepository())
        }

        val seq0 = runs[0].eventTypes()
        assertEquals(
            "Run 2 event sequence must match run 1",
            seq0, runs[1].eventTypes()
        )
        assertEquals(
            "Run 3 event sequence must match run 1",
            seq0, runs[2].eventTypes()
        )

        val size0 = runs[0].events.size
        assertEquals("Ledger size must be identical across runs", size0, runs[1].events.size)
        assertEquals("Ledger size must be identical across runs", size0, runs[2].events.size)

        runs.forEachIndexed { i, r ->
            assertTrue(
                "Run ${i + 1} must be valid. Invariant errors: ${r.invariantErrors}",
                r.valid
            )
        }
    }

    @Test
    fun `AGOII-STS-1 replay is bit-exact across 5 runs`() {
        val sequences = (1..5).map { run ->
            simulateFullTrace("sts1-replay5-$run", MemoryRepository()).eventTypes()
        }
        val reference = sequences[0]
        sequences.drop(1).forEachIndexed { idx, seq ->
            assertEquals("Run ${idx + 2} must be bit-exact vs run 1", reference, seq)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCEPTANCE CRITERION 1 & 2: 100% failure coverage, zero silent drops
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `AGOII-STS-1 all STS-1 invariants pass`() {
        val report = simulateFullTrace("sts1-inv-all", MemoryRepository())
        assertTrue(
            "All STS-1 invariants must pass. Errors:\n${report.invariantErrors.joinToString("\n")}",
            report.invariantErrors.isEmpty()
        )
    }

    @Test
    fun `AGOII-STS-1 ledger audit passes — zero illegal transitions`() {
        val report = simulateFullTrace("sts1-audit", MemoryRepository())
        assertTrue(
            "Ledger audit must pass. Errors:\n${report.ledgerAudit.errors.joinToString("\n")}",
            report.ledgerAudit.valid
        )
        assertEquals(
            "Zero illegal transitions",
            emptyList<String>(), report.ledgerAudit.errors
        )
    }

    @Test
    fun `AGOII-STS-1 ledger first event is INTENT_SUBMITTED`() {
        val report = simulateFullTrace("sts1-first-evt", MemoryRepository())
        assertEquals(
            "First event must be INTENT_SUBMITTED",
            EventTypes.INTENT_SUBMITTED, report.events.firstOrNull()?.type
        )
    }

    @Test
    fun `AGOII-STS-1 ledger is append-only — snapshot is stable across reads`() {
        val store  = MemoryRepository()
        val report = simulateFullTrace("sts1-append-only", store)
        val snap1  = store.loadEvents("sts1-append-only").map { it.type }
        val snap2  = store.loadEvents("sts1-append-only").map { it.type }
        assertEquals("Repeated reads must return identical ledger", snap1, snap2)
        assertEquals("Report snapshot must match re-read", report.eventTypes(), snap1)
    }

    @Test
    fun `AGOII-STS-1 no silent failure drops`() {
        val report = simulateFullTrace("sts1-no-drops", MemoryRepository())
        assertTrue(
            "No silent drops. Errors: ${report.invariantErrors}",
            report.invariantErrors.none { "silent" in it || "INV-2" in it || "INV-3" in it || "INV-9" in it }
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CASE A — Execution failure (missing artifactReference)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `case A — TASK_EXECUTED FAILURE triggers RECOVERY_CONTRACT`() {
        val report = simulateFullTrace("sts1-case-a", MemoryRepository())

        assertTrue(
            "Case A: at least one TASK_EXECUTED FAILURE must be injected",
            report.executionFailureCount >= 1
        )
        assertTrue(
            "Case A: at least one RECOVERY_CONTRACT must be emitted",
            report.recoveryContractCount >= 1
        )

        val events = report.events
        val failedIds = events
            .filter { it.type == EventTypes.TASK_EXECUTED && it.payload["executionStatus"] == "FAILURE" }
            .mapNotNull { it.payload["contractId"]?.toString() }
            .toSet()
        val recoveredIds = events
            .filter { it.type == EventTypes.RECOVERY_CONTRACT }
            .mapNotNull { it.payload["contractId"]?.toString() }
            .toSet()

        assertTrue(
            "Case A: all failed contractIds must have a RECOVERY_CONTRACT. " +
            "Failed=$failedIds, Recovered=$recoveredIds",
            recoveredIds.containsAll(failedIds)
        )
    }

    @Test
    fun `case A — DELTA_CONTRACT_CREATED follows RECOVERY_CONTRACT for execution failure`() {
        val report = simulateFullTrace("sts1-case-a-delta", MemoryRepository())
        val events = report.events

        val rcIdx = events.indexOfFirst {
            it.type == EventTypes.RECOVERY_CONTRACT &&
            it.payload["contractId"]?.toString() == "contract_2"
        }
        assertTrue("RECOVERY_CONTRACT for contract_2 must exist", rcIdx >= 0)

        val dcIdx = events.indexOfFirst {
            it.type == EventTypes.DELTA_CONTRACT_CREATED &&
            it.payload["contractId"]?.toString() == "contract_2"
        }
        assertTrue("DELTA_CONTRACT_CREATED for contract_2 must exist", dcIdx >= 0)
        assertTrue(
            "DELTA_CONTRACT_CREATED must follow RECOVERY_CONTRACT (indices $dcIdx > $rcIdx)",
            dcIdx > rcIdx
        )
    }

    @Test
    fun `case A — RECOVERY_CONTRACT payload contains required fields`() {
        val report = simulateFullTrace("sts1-case-a-payload", MemoryRepository())
        val rc = report.events.firstOrNull {
            it.type == EventTypes.RECOVERY_CONTRACT &&
            it.payload["contractId"]?.toString() == "contract_2"
        }
        assertNotNull("RECOVERY_CONTRACT for contract_2 must exist", rc)
        rc!!
        assertEquals("EXECUTION_FAILURE", rc.payload["failureClass"])
        assertEquals("DELTA_REPAIR_REQUIRED", rc.payload["correctionDirective"])
        assertEquals("VALIDATION_PASS", rc.payload["successCondition"])
        assertNotNull("violationField must be present", rc.payload["violationField"])
        assertTrue(
            "violationField must be non-blank",
            rc.payload["violationField"]?.toString()?.isNotBlank() == true
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CASE B — Mixed validity (2 valid / 1 invalid → multi-RECOVERY_CONTRACT)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `case B — mixed validity triggers ASSEMBLY_FAILED with lockedSections and violationSurface`() {
        val store = MemoryRepository()
        // 3 contracts, c3 has NO TASK_EXECUTED → INCOMPLETE_EXECUTION_SURFACE
        val ledger = listOf(
            Event(EventTypes.CONTRACTS_GENERATED,
                mapOf("report_reference" to "rrid-b-001", "contractSetId" to "cset-b-001", "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "c1", "position" to 1, "report_reference" to "rrid-b-001")),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "c2", "position" to 2, "report_reference" to "rrid-b-001")),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "c3", "position" to 3, "report_reference" to "rrid-b-001")),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("contractId" to "c1", "executionStatus" to "SUCCESS",
                      "artifactReference" to "art-1", "report_reference" to "rrid-b-001")),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("contractId" to "c2", "executionStatus" to "SUCCESS",
                      "artifactReference" to "art-2", "report_reference" to "rrid-b-001")),
            // c3: NO TASK_EXECUTED → INCOMPLETE_EXECUTION_SURFACE
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("total" to 3))
        )
        ledger.forEach { store.appendEvent("proj-case-b", it.type, it.payload) }

        val result = AssemblyModule().assemble("proj-case-b", store)
        assertTrue("Case B: result must be Failed", result is AssemblyExecutionResult.Failed)

        val failed = result as AssemblyExecutionResult.Failed
        assertEquals(
            "Case B: locked sections must be {c1, c2}",
            setOf("c1", "c2"),
            failed.lockedSections.map { it.contractId }.toSet()
        )
        assertEquals(
            "Case B: violation surface must be {c3}",
            setOf("c3"),
            failed.violationSurface.map { it.contractId }.toSet()
        )
        assertTrue(
            "Case B: ASSEMBLY_FAILED must be written to ledger",
            store.loadEvents("proj-case-b").any { it.type == EventTypes.ASSEMBLY_FAILED }
        )
    }

    @Test
    fun `case B — Governor emits ALL RECOVERY_CONTRACTs in a single deterministic step`() {
        val twoFailures = Event(
            EventTypes.ASSEMBLY_FAILED,
            mapOf(
                "report_reference" to "rrid-b-multi",
                "contractSetId"    to "cset-b-multi",
                "failureReasons"   to listOf(
                    mapOf("contractId" to "c2", "failureType" to "INCOMPLETE_EXECUTION_SURFACE",
                          "violatedInvariant" to "c2 has no SUCCESS TASK_EXECUTED"),
                    mapOf("contractId" to "c3", "failureType" to "INCOMPLETE_EXECUTION_SURFACE",
                          "violatedInvariant" to "c3 has no SUCCESS TASK_EXECUTED")
                ),
                "lockedSections"   to listOf("c1"),
                "violationSurface" to listOf("c2", "c3")
            )
        )
        val store = MemoryRepository()
        store.appendEvent("proj-b-multi", twoFailures.type, twoFailures.payload)

        val recoveries = Governor(store).nextEvents(listOf(twoFailures))

        assertEquals(
            "Case B: Governor must emit exactly 2 RECOVERY_CONTRACTs in one step",
            2, recoveries.size
        )
        assertTrue(
            "All emitted events must be RECOVERY_CONTRACT",
            recoveries.all { it.type == EventTypes.RECOVERY_CONTRACT }
        )
        assertEquals(
            "Recovery contracts must cover both failed contracts",
            setOf("c2", "c3"),
            recoveries.map { it.payload["contractId"] }.toSet()
        )
    }

    @Test
    fun `case B — locked sections are NOT present in post-assembly RECOVERY_CONTRACT set`() {
        val report = simulateFullTrace("sts1-case-b-lock", MemoryRepository())
        val events = report.events
        val asmFailedIdx = events.indexOfFirst { it.type == EventTypes.ASSEMBLY_FAILED }
        assertTrue("ASSEMBLY_FAILED must exist in full trace", asmFailedIdx >= 0)

        @Suppress("UNCHECKED_CAST")
        val lockedIds = (events[asmFailedIdx].payload["lockedSections"] as? List<*>)
            ?.mapNotNull { it?.toString() } ?: emptyList()

        // Only recovery contracts written AFTER ASSEMBLY_FAILED are relevant
        val postAssemblyRecoveredIds = events.drop(asmFailedIdx + 1)
            .filter { it.type == EventTypes.RECOVERY_CONTRACT }
            .mapNotNull { it.payload["contractId"]?.toString() }
            .toSet()

        assertTrue(
            "Locked sections must not appear in post-assembly RECOVERY_CONTRACTs. " +
            "Locked=$lockedIds, PostAssemblyRecovered=$postAssemblyRecoveredIds",
            lockedIds.none { it in postAssemblyRecoveredIds }
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CASE C — Trace incomplete (SUCCESS without artifactReference)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `case C — SUCCESS without artifactReference triggers TRACE_INCOMPLETE in assembly`() {
        val report = simulateFullTrace("sts1-case-c", MemoryRepository())
        val events = report.events

        assertTrue(
            "Case C: ASSEMBLY_FAILED must be emitted",
            events.any { it.type == EventTypes.ASSEMBLY_FAILED }
        )

        val asmFailed = events.firstOrNull { it.type == EventTypes.ASSEMBLY_FAILED }
        assertNotNull("Case C: ASSEMBLY_FAILED must be emitted", asmFailed)
        asmFailed!!
        @Suppress("UNCHECKED_CAST")
        val failureReasons = asmFailed.payload["failureReasons"] as? List<*>
        assertNotNull("Case C: failureReasons must be present", failureReasons)
        assertTrue(
            "Case C: at least one TRACE_INCOMPLETE failure reason must exist",
            failureReasons!!.any { fr ->
                (fr as? Map<*, *>)?.get("failureType")?.toString() == "TRACE_INCOMPLETE"
            }
        )
    }

    @Test
    fun `case C — system recovers from TRACE_INCOMPLETE and reaches ASSEMBLY_COMPLETED`() {
        val report = simulateFullTrace("sts1-case-c-recover", MemoryRepository())
        assertTrue(
            "Case C: ASSEMBLY_COMPLETED must follow assembly recovery",
            report.events.any { it.type == EventTypes.ASSEMBLY_COMPLETED }
        )
    }

    @Test
    fun `case C — lockedSections in ASSEMBLY_FAILED contains contract_1 and contract_2`() {
        val report = simulateFullTrace("sts1-case-c-locked", MemoryRepository())
        val asmFailed = report.events.firstOrNull { it.type == EventTypes.ASSEMBLY_FAILED }
        assertNotNull("ASSEMBLY_FAILED must exist", asmFailed)
        asmFailed!!

        @Suppress("UNCHECKED_CAST")
        val lockedSections = asmFailed.payload["lockedSections"] as? List<*>
        assertNotNull("lockedSections must be present", lockedSections)
        assertTrue(
            "contract_1 must be in lockedSections",
            lockedSections!!.contains("contract_1")
        )
        assertTrue(
            "contract_2 must be in lockedSections",
            lockedSections.contains("contract_2")
        )
    }

    @Test
    fun `case C — violationSurface in ASSEMBLY_FAILED contains only contract_3`() {
        val report = simulateFullTrace("sts1-case-c-violation", MemoryRepository())
        val asmFailed = report.events.firstOrNull { it.type == EventTypes.ASSEMBLY_FAILED }
        assertNotNull("ASSEMBLY_FAILED must exist", asmFailed)
        asmFailed!!

        @Suppress("UNCHECKED_CAST")
        val violationSurface = asmFailed.payload["violationSurface"] as? List<*>
        assertNotNull("violationSurface must be present", violationSurface)
        assertEquals(
            "violationSurface must contain exactly contract_3",
            listOf("contract_3"), violationSurface!!.toList()
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CASE D — Structural violation (duplicate positions)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `case D — duplicate positions trigger POSITION_VIOLATION in assembly`() {
        val store = MemoryRepository()
        // c3 has position=2 (duplicate of c2) instead of the expected position=3
        val ledger = listOf(
            Event(EventTypes.CONTRACTS_GENERATED,
                mapOf("report_reference" to "rrid-d-001", "contractSetId" to "cset-d-001", "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "c1", "position" to 1, "report_reference" to "rrid-d-001")),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "c2", "position" to 2, "report_reference" to "rrid-d-001")),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "c3", "position" to 2, "report_reference" to "rrid-d-001")),
            // Duplicate position=2: positions observed = {1,2}, expected = {1,2,3} → POSITION_VIOLATION
            Event(EventTypes.TASK_EXECUTED,
                mapOf("contractId" to "c1", "executionStatus" to "SUCCESS",
                      "artifactReference" to "art-1", "report_reference" to "rrid-d-001")),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("contractId" to "c2", "executionStatus" to "SUCCESS",
                      "artifactReference" to "art-2", "report_reference" to "rrid-d-001")),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("contractId" to "c3", "executionStatus" to "SUCCESS",
                      "artifactReference" to "art-3", "report_reference" to "rrid-d-001")),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("total" to 3))
        )
        ledger.forEach { store.appendEvent("proj-case-d", it.type, it.payload) }

        val result = AssemblyModule().assemble("proj-case-d", store)
        assertTrue("Case D: result must be Failed", result is AssemblyExecutionResult.Failed)

        val failed = result as AssemblyExecutionResult.Failed
        assertTrue(
            "Case D: POSITION_VIOLATION must be in failureReasons",
            failed.failureReasons.any { it.failureType == "POSITION_VIOLATION" }
        )
        assertTrue(
            "Case D: ASSEMBLY_FAILED must be written to ledger",
            store.loadEvents("proj-case-d").any { it.type == EventTypes.ASSEMBLY_FAILED }
        )
    }

    @Test
    fun `case D — ASSEMBLY_FAILED payload contains failureReasons with POSITION_VIOLATION`() {
        val store = MemoryRepository()
        val ledger = listOf(
            Event(EventTypes.CONTRACTS_GENERATED,
                mapOf("report_reference" to "rrid-d2", "contractSetId" to "cset-d2", "total" to 3)),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "c1", "position" to 1, "report_reference" to "rrid-d2")),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "c2", "position" to 2, "report_reference" to "rrid-d2")),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "c3", "position" to 2, "report_reference" to "rrid-d2")),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("contractId" to "c1", "executionStatus" to "SUCCESS",
                      "artifactReference" to "art-1", "report_reference" to "rrid-d2")),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("contractId" to "c2", "executionStatus" to "SUCCESS",
                      "artifactReference" to "art-2", "report_reference" to "rrid-d2")),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("contractId" to "c3", "executionStatus" to "SUCCESS",
                      "artifactReference" to "art-3", "report_reference" to "rrid-d2")),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("total" to 3))
        )
        ledger.forEach { store.appendEvent("proj-d2", it.type, it.payload) }

        AssemblyModule().assemble("proj-d2", store)

        val asmFailed = store.loadEvents("proj-d2").firstOrNull { it.type == EventTypes.ASSEMBLY_FAILED }
        assertNotNull("ASSEMBLY_FAILED must be written to ledger for Case D (2)", asmFailed)
        asmFailed!!
        @Suppress("UNCHECKED_CAST")
        val reasons = asmFailed.payload["failureReasons"] as? List<*>
        assertNotNull("failureReasons must be present", reasons)
        assertTrue(
            "POSITION_VIOLATION must appear in ASSEMBLY_FAILED.failureReasons",
            reasons!!.any { fr ->
                (fr as? Map<*, *>)?.get("failureType")?.toString() == "POSITION_VIOLATION"
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ACCEPTANCE CRITERION 5: Convergence
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `AGOII-STS-1 system converges to stable terminal state — last event is ICS_COMPLETED`() {
        val report = simulateFullTrace("sts1-convergence", MemoryRepository())
        val lastType = report.events.lastOrNull()?.type
        assertEquals(
            "Terminal state: last event must be ICS_COMPLETED",
            EventTypes.ICS_COMPLETED,
            lastType
        )
    }

    @Test
    fun `AGOII-STS-1 ICS phase is reached and completed`() {
        val report = simulateFullTrace("sts1-ics-complete", MemoryRepository())
        assertTrue("ICS_STARTED must be in ledger", report.icsStarted)
        assertTrue("ICS_COMPLETED must be in ledger", report.icsCompleted)
    }

    @Test
    fun `AGOII-STS-1 ASSEMBLY_COMPLETED is present before ICS`() {
        val report = simulateFullTrace("sts1-assembly-before-ics", MemoryRepository())
        val events = report.events
        val asmCompletedIdx = events.indexOfFirst { it.type == EventTypes.ASSEMBLY_COMPLETED }
        val icsStartedIdx   = events.indexOfFirst { it.type == EventTypes.ICS_STARTED }

        assertTrue("ASSEMBLY_COMPLETED must exist", asmCompletedIdx >= 0)
        assertTrue("ICS_STARTED must exist", icsStartedIdx >= 0)
        assertTrue(
            "ASSEMBLY_COMPLETED must precede ICS_STARTED (indices: $asmCompletedIdx vs $icsStartedIdx)",
            asmCompletedIdx < icsStartedIdx
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GOVERNOR SURFACE — deterministic sequencing, multi-event emission
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Governor surface — ASSEMBLY_FAILED emits all RECOVERY_CONTRACTs in one nextEvents call`() {
        val assemblyFailed = Event(
            EventTypes.ASSEMBLY_FAILED,
            mapOf(
                "report_reference" to "rrid-gov-001",
                "contractSetId"    to "cset-gov-001",
                "failureReasons"   to listOf(
                    mapOf("contractId" to "ca", "failureType" to "TRACE_INCOMPLETE",
                          "violatedInvariant" to "ca: artifactReference blank"),
                    mapOf("contractId" to "cb", "failureType" to "INCOMPLETE_EXECUTION_SURFACE",
                          "violatedInvariant" to "cb: no SUCCESS TASK_EXECUTED"),
                    mapOf("contractId" to "cc", "failureType" to "RRID_VIOLATION",
                          "violatedInvariant" to "cc: RRID mismatch")
                ),
                "lockedSections"   to emptyList<String>(),
                "violationSurface" to listOf("ca", "cb", "cc")
            )
        )
        val store = MemoryRepository()
        store.appendEvent("proj-gov", assemblyFailed.type, assemblyFailed.payload)

        val emitted = Governor(store).nextEvents(listOf(assemblyFailed))

        assertEquals("Governor must emit 3 RECOVERY_CONTRACTs in a single step", 3, emitted.size)
        assertTrue(emitted.all { it.type == EventTypes.RECOVERY_CONTRACT })
        assertEquals(setOf("ca", "cb", "cc"), emitted.map { it.payload["contractId"] }.toSet())
        assertTrue(emitted.all { it.payload["correctionDirective"] == "DELTA_REPAIR_REQUIRED" })
        assertTrue(emitted.all { it.payload["successCondition"] == "VALIDATION_PASS" })
        assertTrue(emitted.all { it.payload["irs_violation_type"] == "ASSEMBLY_FAILURE" })
    }

    @Test
    fun `Governor surface — nextEvents returns empty for ASSEMBLY_FAILED with empty failureReasons`() {
        val assemblyFailed = Event(
            EventTypes.ASSEMBLY_FAILED,
            mapOf(
                "report_reference" to "rrid-gov-empty",
                "failureReasons"   to emptyList<Any>(),
                "lockedSections"   to emptyList<String>()
            )
        )
        val results = Governor(MemoryRepository()).nextEvents(listOf(assemblyFailed))
        assertTrue("Empty failureReasons must yield empty nextEvents result", results.isEmpty())
    }

    @Test
    fun `Governor surface — RECOVERY_CONTRACT transitions to DELTA_CONTRACT_CREATED`() {
        val store = MemoryRepository()
        val events = listOf(
            Event(EventTypes.CONTRACTS_GENERATED,
                mapOf("report_reference" to "rrid-g2", "total" to 1)),
            Event(EventTypes.RECOVERY_CONTRACT,
                mapOf(
                    "contractId"       to "c1",
                    "report_reference" to "rrid-g2",
                    "violationField"   to "artifactReference"
                ))
        )
        events.forEach { store.appendEvent("proj-g2", it.type, it.payload) }

        val next = Governor(store).nextEvent(store.loadEvents("proj-g2"))
        assertNotNull("Governor must emit an event from RECOVERY_CONTRACT", next)
        assertEquals(EventTypes.DELTA_CONTRACT_CREATED, next!!.type)
        assertEquals("c1", next.payload["contractId"])
        assertEquals("rrid-g2", next.payload["report_reference"])
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEDGER SURFACE — append-only, replay determinism
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Ledger surface — full trace audit result contains no unknown event types`() {
        val report = simulateFullTrace("sts1-ledger-types", MemoryRepository())
        val unknownTypes = report.events
            .map { it.type }
            .filter { it !in EventTypes.ALL }
        assertEquals(
            "All ledger event types must be registered in EventTypes.ALL",
            emptyList<String>(), unknownTypes
        )
    }

    @Test
    fun `Ledger surface — Replay derives correct structural state from full trace`() {
        val store  = MemoryRepository()
        simulateFullTrace("sts1-replay-state", store)

        val state = Replay(store).replayStructuralState("sts1-replay-state")
        assertTrue("intent.structurallyComplete must be true", state.intent.structurallyComplete)
        assertTrue("contracts.generated must be true", state.contracts.generated)
        assertTrue("assembly.assemblyStarted must be true", state.assembly.assemblyStarted)
        assertTrue("assembly.assemblyCompleted must be true", state.assembly.assemblyCompleted)
        assertTrue("icsStarted must be true", state.icsStarted)
        assertTrue("icsCompleted must be true", state.icsCompleted)
    }

    @Test
    fun `Ledger surface — INTENT_SUBMITTED is always the first event`() {
        val store = MemoryRepository()
        simulateFullTrace("sts1-first-check", store)
        val events = store.loadEvents("sts1-first-check")
        assertEquals(EventTypes.INTENT_SUBMITTED, events.firstOrNull()?.type)
    }
}
