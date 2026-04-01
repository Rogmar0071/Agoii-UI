package com.agoii.mobile

// CONTRACT: AGOII-CLC-1E-ASSEMBLY-VALIDATION
// CLASS: Operational | REVERSIBILITY: Reversible | SCOPE: Simulation-only
//
// Validates that AssemblyModule produces correct, complete, and stable outputs
// when given a structurally valid, fully executed ledger ending at EXECUTION_COMPLETED.
//
// RULES:
//   - NO changes to production code
//   - NO CoreBridge execution
//   - NO ICS invocation
//   - NO external dependencies within the pipeline
//   - ONLY AssemblyModule is exercised
//
// CHECKS COVERED:
//   A. Structural Completeness
//   B. Deterministic Consistency (3 identical runs)
//   C. Ordering Integrity
//   D. No Silent Drops
//   E. No Duplication
//   F. Idempotency

import com.agoii.mobile.assembly.AssemblyExecutionResult
import com.agoii.mobile.assembly.AssemblyModule
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/**
 * AGOII-CLC-1E-ASSEMBLY-VALIDATION — Assembly Module Simulation Test.
 *
 * Exercises the real [AssemblyModule] against a deterministic, fully valid
 * execution ledger (N = 2 contracts).  EventLedger is mocked so that:
 *  - [EventLedger.loadEvents] returns the in-memory event list.
 *  - [EventLedger.appendEvent] appends to the same in-memory list (simulating
 *    the write path without Android filesystem or ValidationLayer overhead).
 */
class AssemblyModuleValidationTest {

    // ── Deterministic contract constants (fixed for all runs) ─────────────────

    companion object {
        private const val PROJECT_ID      = "clc-1e-validation"
        private const val RRID            = "RRID-CLC-1E-001"
        private const val CONTRACT_SET_ID = "CS-CLC-1E-001"
        private const val CONTRACTOR_ID   = "contractor-deterministic-A"

        // Contract identity
        private const val CONTRACT_ID_1  = "c-001"
        private const val CONTRACT_ID_2  = "c-002"

        // Task identity
        private const val TASK_ID_1 = "task-001"
        private const val TASK_ID_2 = "task-002"

        // Artifact references (deterministic, bound to each contract)
        private const val ARTIFACT_REF_1 = "artifact-ref-c-001"
        private const val ARTIFACT_REF_2 = "artifact-ref-c-002"
    }

    private val module = AssemblyModule()

    // ── Kotlin/Mockito null-safety helper ─────────────────────────────────────

    /** Wraps Mockito.any() to satisfy Kotlin's non-null type system at call sites. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = Mockito.any<T>() as T

    // ── Ledger construction ───────────────────────────────────────────────────

    /**
     * Builds the canonical deterministic input ledger (N = 2 contracts).
     *
     * Sequence follows the Agoii master flow:
     *   INTENT_SUBMITTED → CONTRACTS_GENERATED → CONTRACTS_READY
     *   → CONTRACT_STARTED (×2) → TASK_ASSIGNED → TASK_STARTED → TASK_EXECUTED(SUCCESS)
     *   → TASK_COMPLETED → CONTRACT_COMPLETED (×2) → EXECUTION_COMPLETED
     *
     * ✔ Would pass ValidationLayer
     * ✔ Would pass LedgerAudit
     */
    private fun buildLedger(): List<Event> = listOf(
        Event(EventTypes.INTENT_SUBMITTED, mapOf(
            "objective" to "CLC-1E assembly validation target"
        )),
        Event(EventTypes.CONTRACTS_GENERATED, mapOf(
            "report_id"     to RRID,
            "contractSetId" to CONTRACT_SET_ID,
            "total"         to 2
        )),
        Event(EventTypes.CONTRACTS_READY, mapOf(
            "report_reference" to RRID
        )),
        // ── Contract 1 ──────────────────────────────────────────────────────
        Event(EventTypes.CONTRACT_STARTED, mapOf(
            "contractId"       to CONTRACT_ID_1,
            "position"         to 1,
            "total"            to 2,
            "report_reference" to RRID
        )),
        Event(EventTypes.TASK_ASSIGNED, mapOf(
            "taskId"           to TASK_ID_1,
            "contractId"       to CONTRACT_ID_1,
            "contractorId"     to CONTRACTOR_ID,
            "report_reference" to RRID,
            "position"         to 1,
            "total"            to 2
        )),
        Event(EventTypes.TASK_STARTED, mapOf(
            "taskId"           to TASK_ID_1,
            "contractId"       to CONTRACT_ID_1,
            "contractorId"     to CONTRACTOR_ID,
            "report_reference" to RRID,
            "position"         to 1,
            "total"            to 2
        )),
        Event(EventTypes.TASK_EXECUTED, mapOf(
            "taskId"            to TASK_ID_1,
            "contractId"        to CONTRACT_ID_1,
            "contractorId"      to CONTRACTOR_ID,
            "artifactReference" to ARTIFACT_REF_1,
            "executionStatus"   to "SUCCESS",
            "validationStatus"  to "VALIDATED",
            "validationReasons" to "output meets specification",
            "report_reference"  to RRID,
            "position"          to 1,
            "total"             to 2
        )),
        Event(EventTypes.TASK_COMPLETED, mapOf(
            "taskId"           to TASK_ID_1,
            "contractId"       to CONTRACT_ID_1,
            "report_reference" to RRID,
            "position"         to 1,
            "total"            to 2
        )),
        Event(EventTypes.CONTRACT_COMPLETED, mapOf(
            "contractId"       to CONTRACT_ID_1,
            "position"         to 1,
            "total"            to 2,
            "report_reference" to RRID
        )),
        // ── Contract 2 ──────────────────────────────────────────────────────
        Event(EventTypes.CONTRACT_STARTED, mapOf(
            "contractId"       to CONTRACT_ID_2,
            "position"         to 2,
            "total"            to 2,
            "report_reference" to RRID
        )),
        Event(EventTypes.TASK_ASSIGNED, mapOf(
            "taskId"           to TASK_ID_2,
            "contractId"       to CONTRACT_ID_2,
            "contractorId"     to CONTRACTOR_ID,
            "report_reference" to RRID,
            "position"         to 2,
            "total"            to 2
        )),
        Event(EventTypes.TASK_STARTED, mapOf(
            "taskId"           to TASK_ID_2,
            "contractId"       to CONTRACT_ID_2,
            "contractorId"     to CONTRACTOR_ID,
            "report_reference" to RRID,
            "position"         to 2,
            "total"            to 2
        )),
        Event(EventTypes.TASK_EXECUTED, mapOf(
            "taskId"            to TASK_ID_2,
            "contractId"        to CONTRACT_ID_2,
            "contractorId"      to CONTRACTOR_ID,
            "artifactReference" to ARTIFACT_REF_2,
            "executionStatus"   to "SUCCESS",
            "validationStatus"  to "VALIDATED",
            "validationReasons" to "output meets specification",
            "report_reference"  to RRID,
            "position"          to 2,
            "total"             to 2
        )),
        Event(EventTypes.TASK_COMPLETED, mapOf(
            "taskId"           to TASK_ID_2,
            "contractId"       to CONTRACT_ID_2,
            "report_reference" to RRID,
            "position"         to 2,
            "total"            to 2
        )),
        Event(EventTypes.CONTRACT_COMPLETED, mapOf(
            "contractId"       to CONTRACT_ID_2,
            "position"         to 2,
            "total"            to 2,
            "report_reference" to RRID
        )),
        // ── Execution closure ────────────────────────────────────────────────
        Event(EventTypes.EXECUTION_COMPLETED, mapOf(
            "report_reference"    to RRID,
            "contracts_completed" to 2
        ))
    )

    /**
     * Creates a fresh mock [EventLedger] backed by an in-memory mutable list.
     *
     * - [EventLedger.loadEvents] returns the current snapshot of [initial] plus
     *   any events appended since construction.
     * - [EventLedger.appendEvent] appends a new [Event] to that same list,
     *   simulating the ledger write path without Android filesystem access.
     */
    private fun makeMockLedger(initial: List<Event>): EventLedger {
        val events = initial.toMutableList()
        val ledger = mock(EventLedger::class.java)
        Mockito.`when`(ledger.loadEvents(anyString())).thenAnswer { events.toList() }
        doAnswer { inv ->
            val type    = inv.arguments[1].toString()
            @Suppress("UNCHECKED_CAST")
            val payload = inv.arguments[2] as? Map<String, Any> ?: emptyMap()
            events.add(Event(type, payload))
            null
        }.`when`(ledger).appendEvent(anyString(), anyString(), anyArg())
        return ledger
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Check A — Structural Completeness
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `A1 - assembly output contains all contracts (N=2)`() {
        val result = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
        assertTrue("Expected Assembled but got: $result", result is AssemblyExecutionResult.Assembled)
        val outputs = (result as AssemblyExecutionResult.Assembled).finalArtifact.contractOutputs
        assertEquals("Both contracts must appear in output", 2, outputs.size)
        val ids = outputs.map { it.contractId }
        assertTrue("c-001 missing from output", CONTRACT_ID_1 in ids)
        assertTrue("c-002 missing from output", CONTRACT_ID_2 in ids)
    }

    @Test
    fun `A2 - no missing task artifacts in assembly output`() {
        val result = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val outputs = result.finalArtifact.contractOutputs
        val c1 = outputs.first { it.contractId == CONTRACT_ID_1 }
        val c2 = outputs.first { it.contractId == CONTRACT_ID_2 }
        assertEquals("c-001 artifact reference must match", ARTIFACT_REF_1, c1.artifactReference)
        assertEquals("c-002 artifact reference must match", ARTIFACT_REF_2, c2.artifactReference)
    }

    @Test
    fun `A3 - no orphan mappings in traceMap`() {
        val result = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val traceMap = result.finalArtifact.traceMap
        assertEquals("traceMap must have exactly 2 entries", 2, traceMap.size)
        assertEquals("c-001 traceMap must point to RRID", RRID, traceMap[CONTRACT_ID_1])
        assertEquals("c-002 traceMap must point to RRID", RRID, traceMap[CONTRACT_ID_2])
    }

    @Test
    fun `A4 - assembly report fields are structurally complete`() {
        val result = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val report   = result.assemblyReport
        val artifact = result.finalArtifact

        assertEquals("artifact.reportReference",  RRID,             artifact.reportReference)
        assertEquals("artifact.contractSetId",   CONTRACT_SET_ID,  artifact.contractSetId)
        assertEquals("artifact.totalContracts",  2,                artifact.totalContracts)
        assertEquals("report.reportReference",   RRID,             report.reportReference)
        assertEquals("report.taskId",            "assembly_$RRID", report.taskId)
        assertEquals("report.assemblyId",        "assembly_$RRID", report.assemblyId)
        assertEquals("report.contractSetId",     CONTRACT_SET_ID,  report.contractSetId)
        assertEquals("report.totalContracts",    2,                report.totalContracts)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Check B — Deterministic Consistency (3 runs on identical ledger)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `B1 - three runs on identical ledger produce identical FinalArtifact`() {
        val run1 = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val run2 = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val run3 = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled

        // FinalArtifact fields (UUID/timestamp live in Event, not FinalArtifact)
        assertEquals("run1 vs run2: reportReference",
            run1.finalArtifact.reportReference, run2.finalArtifact.reportReference)
        assertEquals("run2 vs run3: reportReference",
            run2.finalArtifact.reportReference, run3.finalArtifact.reportReference)
        assertEquals("run1 vs run2: contractSetId",
            run1.finalArtifact.contractSetId, run2.finalArtifact.contractSetId)
        assertEquals("run1 vs run2: totalContracts",
            run1.finalArtifact.totalContracts, run2.finalArtifact.totalContracts)
        assertEquals("run1 vs run2: contractOutputs",
            run1.finalArtifact.contractOutputs, run2.finalArtifact.contractOutputs)
        assertEquals("run2 vs run3: contractOutputs",
            run2.finalArtifact.contractOutputs, run3.finalArtifact.contractOutputs)
        assertEquals("run1 vs run2: traceMap",
            run1.finalArtifact.traceMap, run2.finalArtifact.traceMap)
        assertEquals("run2 vs run3: traceMap",
            run2.finalArtifact.traceMap, run3.finalArtifact.traceMap)
    }

    @Test
    fun `B2 - three runs on identical ledger produce identical assembly reports`() {
        val run1 = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val run2 = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val run3 = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled

        assertEquals("run1 vs run2: assemblyId",
            run1.assemblyReport.assemblyId, run2.assemblyReport.assemblyId)
        assertEquals("run2 vs run3: assemblyId",
            run2.assemblyReport.assemblyId, run3.assemblyReport.assemblyId)
        assertEquals("run1 vs run2: taskId",
            run1.assemblyReport.taskId, run2.assemblyReport.taskId)
        assertEquals("run1 vs run2: reportReference",
            run1.assemblyReport.reportReference, run2.assemblyReport.reportReference)
        assertEquals("run1 vs run2: contractSetId",
            run1.assemblyReport.contractSetId, run2.assemblyReport.contractSetId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Check C — Ordering Integrity
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `C1 - contractOutputs are ordered by position ascending`() {
        val result = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val positions = result.finalArtifact.contractOutputs.map { it.position }
        assertEquals("Positions must be strictly ascending [1, 2]", listOf(1, 2), positions)
    }

    @Test
    fun `C2 - position values match contract identity in order`() {
        val result = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val outputs = result.finalArtifact.contractOutputs
        assertEquals("Position 1 must be c-001", CONTRACT_ID_1, outputs[0].contractId)
        assertEquals("Position 2 must be c-002", CONTRACT_ID_2, outputs[1].contractId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Check D — No Silent Drops
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `D1 - every TASK_EXECUTED SUCCESS artifact appears in assembly output`() {
        val result = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val outputRefs = result.finalArtifact.contractOutputs.map { it.artifactReference }.toSet()
        assertTrue("artifact-ref-c-001 must not be silently dropped", ARTIFACT_REF_1 in outputRefs)
        assertTrue("artifact-ref-c-002 must not be silently dropped", ARTIFACT_REF_2 in outputRefs)
    }

    @Test
    fun `D2 - output count equals TASK_EXECUTED SUCCESS count`() {
        val successCount = buildLedger().count { ev ->
            ev.type == EventTypes.TASK_EXECUTED &&
            ev.payload["executionStatus"] == "SUCCESS"
        }
        val result = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        assertEquals(
            "Output entries must equal the number of SUCCESS task executions",
            successCount, result.finalArtifact.contractOutputs.size
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Check E — No Duplication
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `E1 - each contractId appears exactly once in output`() {
        val result = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val ids = result.finalArtifact.contractOutputs.map { it.contractId }
        assertEquals("Duplicate contract entries detected", ids.distinct().size, ids.size)
    }

    @Test
    fun `E2 - each artifactReference appears exactly once in output`() {
        val result = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val refs = result.finalArtifact.contractOutputs.map { it.artifactReference }
        assertEquals("Duplicate artifact references detected", refs.distinct().size, refs.size)
    }

    @Test
    fun `E3 - each position value appears exactly once in output`() {
        val result = module.assemble(PROJECT_ID, makeMockLedger(buildLedger()))
            as AssemblyExecutionResult.Assembled
        val positions = result.finalArtifact.contractOutputs.map { it.position }
        assertEquals("Duplicate positions detected", positions.distinct().size, positions.size)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Check F — Idempotency
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `F1 - re-running assembly on a completed ledger returns AlreadyCompleted`() {
        val sharedLedger = makeMockLedger(buildLedger())
        val first = module.assemble(PROJECT_ID, sharedLedger)
        assertTrue("First run must be Assembled", first is AssemblyExecutionResult.Assembled)
        val second = module.assemble(PROJECT_ID, sharedLedger)
        assertTrue(
            "Second run on same ledger must return AlreadyCompleted (idempotency guard), got: $second",
            second is AssemblyExecutionResult.AlreadyCompleted
        )
    }

    @Test
    fun `F2 - idempotent re-run does not emit additional ledger events`() {
        val sharedLedger = makeMockLedger(buildLedger())
        module.assemble(PROJECT_ID, sharedLedger)  // first run: writes ASSEMBLY_STARTED + ASSEMBLY_COMPLETED
        module.assemble(PROJECT_ID, sharedLedger)  // second run: idempotency guard fires — no writes
        // appendEvent must have been called exactly twice (once per event from the first run only)
        verify(sharedLedger, times(2)).appendEvent(anyString(), anyString(), anyArg())
    }

    @Test
    fun `F3 - AlreadyCompleted carries the correct reportReference`() {
        val sharedLedger = makeMockLedger(buildLedger())
        module.assemble(PROJECT_ID, sharedLedger)
        val second = module.assemble(PROJECT_ID, sharedLedger)
            as AssemblyExecutionResult.AlreadyCompleted
        assertEquals("AlreadyCompleted must carry the RRID", RRID, second.reportReference)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Event emission checks (assembly_started / assembly_completed)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `assembly emits ASSEMBLY_STARTED then ASSEMBLY_COMPLETED in that order`() {
        val emittedTypes = mutableListOf<String>()
        val backing = buildLedger().toMutableList()
        val ledger  = mock(EventLedger::class.java)
        Mockito.`when`(ledger.loadEvents(anyString())).thenAnswer { backing.toList() }
        doAnswer { inv ->
            val type    = inv.arguments[1].toString()
            @Suppress("UNCHECKED_CAST")
            val payload = inv.arguments[2] as? Map<String, Any> ?: emptyMap()
            emittedTypes.add(type)
            backing.add(Event(type, payload))
            null
        }.`when`(ledger).appendEvent(anyString(), anyString(), anyArg())

        module.assemble(PROJECT_ID, ledger)

        assertEquals("Must emit exactly 2 events", 2, emittedTypes.size)
        assertEquals("First emission must be ASSEMBLY_STARTED",
            EventTypes.ASSEMBLY_STARTED, emittedTypes[0])
        assertEquals("Second emission must be ASSEMBLY_COMPLETED",
            EventTypes.ASSEMBLY_COMPLETED, emittedTypes[1])
    }

    @Test
    fun `ASSEMBLY_STARTED payload contains required fields`() {
        val startedPayloads = mutableListOf<Map<String, Any>>()
        val backing = buildLedger().toMutableList()
        val ledger  = mock(EventLedger::class.java)
        Mockito.`when`(ledger.loadEvents(anyString())).thenAnswer { backing.toList() }
        doAnswer { inv ->
            val type    = inv.arguments[1].toString()
            @Suppress("UNCHECKED_CAST")
            val payload = inv.arguments[2] as? Map<String, Any> ?: emptyMap()
            if (type == EventTypes.ASSEMBLY_STARTED) startedPayloads.add(payload)
            backing.add(Event(type, payload))
            null
        }.`when`(ledger).appendEvent(anyString(), anyString(), anyArg())

        module.assemble(PROJECT_ID, ledger)

        assertEquals("Exactly one ASSEMBLY_STARTED must be emitted", 1, startedPayloads.size)
        val p = startedPayloads[0]
        assertEquals("ASSEMBLY_STARTED.report_reference", RRID,          p["report_reference"])
        assertEquals("ASSEMBLY_STARTED.contractSetId",    CONTRACT_SET_ID, p["contractSetId"])
        assertEquals("ASSEMBLY_STARTED.totalContracts",   2,             p["totalContracts"])
    }

    @Test
    fun `ASSEMBLY_COMPLETED payload contains required fields`() {
        val completedPayloads = mutableListOf<Map<String, Any>>()
        val backing = buildLedger().toMutableList()
        val ledger  = mock(EventLedger::class.java)
        Mockito.`when`(ledger.loadEvents(anyString())).thenAnswer { backing.toList() }
        doAnswer { inv ->
            val type    = inv.arguments[1].toString()
            @Suppress("UNCHECKED_CAST")
            val payload = inv.arguments[2] as? Map<String, Any> ?: emptyMap()
            if (type == EventTypes.ASSEMBLY_COMPLETED) completedPayloads.add(payload)
            backing.add(Event(type, payload))
            null
        }.`when`(ledger).appendEvent(anyString(), anyString(), anyArg())

        module.assemble(PROJECT_ID, ledger)

        assertEquals("Exactly one ASSEMBLY_COMPLETED must be emitted", 1, completedPayloads.size)
        val p = completedPayloads[0]
        assertEquals("ASSEMBLY_COMPLETED.report_reference",       RRID,          p["report_reference"])
        assertEquals("ASSEMBLY_COMPLETED.contractSetId",          CONTRACT_SET_ID, p["contractSetId"])
        assertEquals("ASSEMBLY_COMPLETED.totalContracts",         2,             p["totalContracts"])
        assertNotNull("ASSEMBLY_COMPLETED.finalArtifactReference must be present",
            p["finalArtifactReference"])
        assertNotNull("ASSEMBLY_COMPLETED.traceMap must be present", p["traceMap"])
        assertEquals("ASSEMBLY_COMPLETED.assemblyId",
            "assembly_$RRID", p["assemblyId"])
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Negative / boundary checks (NotTriggered, Blocked)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `NotTriggered when EXECUTION_COMPLETED is absent`() {
        val incomplete = buildLedger().filter { it.type != EventTypes.EXECUTION_COMPLETED }
        val result = module.assemble(PROJECT_ID, makeMockLedger(incomplete))
        assertTrue("Must be NotTriggered without EXECUTION_COMPLETED",
            result is AssemblyExecutionResult.NotTriggered)
    }

    @Test
    fun `Blocked when one contract has no TASK_EXECUTED SUCCESS`() {
        val missingTask2 = buildLedger().filter { ev ->
            !(ev.type == EventTypes.TASK_EXECUTED && ev.payload["contractId"] == CONTRACT_ID_2)
        }
        val result = module.assemble(PROJECT_ID, makeMockLedger(missingTask2))
        assertTrue("Must be Blocked with missing execution surface: $result",
            result is AssemblyExecutionResult.Blocked)
        val blocked = result as AssemblyExecutionResult.Blocked
        assertTrue("Blocked reason must identify c-002",
            blocked.reason.contains("INCOMPLETE_EXECUTION_SURFACE"))
    }

    @Test
    fun `Blocked when CONTRACT_COMPLETED has RRID mismatch`() {
        val rridMismatch = buildLedger().map { ev ->
            if (ev.type == EventTypes.CONTRACT_COMPLETED && ev.payload["contractId"] == CONTRACT_ID_2) {
                Event(ev.type, ev.payload + mapOf("report_reference" to "RRID-WRONG"))
            } else ev
        }
        val result = module.assemble(PROJECT_ID, makeMockLedger(rridMismatch))
        assertTrue("Must be Blocked on RRID violation: $result",
            result is AssemblyExecutionResult.Blocked)
        val blocked = result as AssemblyExecutionResult.Blocked
        assertTrue("Blocked reason must identify RRID_VIOLATION",
            blocked.reason.contains("RRID_VIOLATION"))
    }
}
