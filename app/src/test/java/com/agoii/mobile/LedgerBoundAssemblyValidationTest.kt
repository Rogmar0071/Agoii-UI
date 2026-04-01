package com.agoii.mobile

import com.agoii.mobile.assembly.AssemblyExecutionResult
import com.agoii.mobile.assembly.AssemblyModule
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventStorage
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerAudit
import org.junit.Assert.*
import org.junit.Test

// ── CONTRACT: AGOII-CLC-1E-RV ─────────────────────────────────────────────────
// REAL ASSEMBLY VALIDATION (LEDGER-BOUND)
//
// ALL writes go through EventLedger.appendEvent() → ValidationLayer (REAL).
// NO mocks, NO synthetic state, NO direct event injection.
// Ledger is the ONLY source of truth.
// ──────────────────────────────────────────────────────────────────────────────

/**
 * In-memory [EventStorage] for JVM unit tests.
 *
 * Implements [EventStorage] directly — no Android [android.content.Context] required.
 * Thread-safe: uses synchronized access on the mutable state.
 */
private class InMemoryEventStorage : EventStorage {
    private val data = mutableMapOf<String, MutableList<Event>>()

    override fun appendEvent(projectId: String, event: Event, priorEvents: List<Event>) {
        synchronized(this) {
            data.getOrPut(projectId) { mutableListOf() }.add(event)
        }
    }

    override fun loadEvents(projectId: String): List<Event> =
        synchronized(this) { data.getOrDefault(projectId, mutableListOf()).toList() }
}

/**
 * Builds a REAL [EventLedger] backed by [InMemoryEventStorage].
 *
 * - [EventLedger] enforces [com.agoii.mobile.core.ValidationLayer] on every write.
 * - [EventLedger] enforces [com.agoii.mobile.core.LedgerIntegrity] after every write.
 * - No mocking; no bypass; ValidationLayer is triggered on every appendEvent() call.
 */
private fun realLedger(): EventLedger = EventLedger(InMemoryEventStorage())

// ── LEDGER CONSTRUCTION HELPER ────────────────────────────────────────────────

/**
 * Builds a fully valid execution ledger for [projectId] via the REAL [EventLedger].
 *
 * Event chain (N = 2 contracts):
 *   INTENT_SUBMITTED
 *   → CONTRACTS_GENERATED
 *   → CONTRACTS_READY
 *   → CONTRACTS_APPROVED
 *   → EXECUTION_STARTED
 *   → CONTRACT_STARTED (position=1)
 *   → TASK_ASSIGNED
 *   → TASK_STARTED
 *   → TASK_EXECUTED (SUCCESS / VALIDATED)
 *   → TASK_COMPLETED
 *   → CONTRACT_COMPLETED (position=1)
 *   → CONTRACT_STARTED (position=2)
 *   → TASK_ASSIGNED
 *   → TASK_STARTED
 *   → TASK_EXECUTED (SUCCESS / VALIDATED)
 *   → TASK_COMPLETED
 *   → CONTRACT_COMPLETED (position=2)
 *   → EXECUTION_COMPLETED
 *
 * Every event is appended via [EventLedger.appendEvent]; ValidationLayer fires on each call.
 */
private fun buildTwoContractLedger(ledger: EventLedger, projectId: String) {
    ledger.appendEvent(
        projectId, EventTypes.INTENT_SUBMITTED,
        mapOf("objective" to "test-assembly-clc1erv")
    )
    ledger.appendEvent(
        projectId, EventTypes.CONTRACTS_GENERATED,
        mapOf(
            "intentId"       to "intent-clc1erv",
            "contractSetId"  to "cset-clc1erv",
            "report_reference" to "rrid-clc1erv",
            "total"          to 2,
            "contracts"      to listOf(
                mapOf("contractId" to "c1", "position" to 1),
                mapOf("contractId" to "c2", "position" to 2)
            )
        )
    )
    ledger.appendEvent(projectId, EventTypes.CONTRACTS_READY, emptyMap())
    ledger.appendEvent(projectId, EventTypes.CONTRACTS_APPROVED, emptyMap())
    ledger.appendEvent(projectId, EventTypes.EXECUTION_STARTED, emptyMap())

    // ── Contract 1 ────────────────────────────────────────────────────────────
    ledger.appendEvent(
        projectId, EventTypes.CONTRACT_STARTED,
        mapOf("contract_id" to "c1", "position" to 1, "total" to 2)
    )
    ledger.appendEvent(
        projectId, EventTypes.TASK_ASSIGNED,
        mapOf(
            "taskId"           to "t1",
            "position"         to 1,
            "total"            to 2,
            "contractId"       to "c1",
            "report_reference" to "rrid-clc1erv",
            "requirements"     to "build-feature-alpha",
            "constraints"      to "none"
        )
    )
    ledger.appendEvent(
        projectId, EventTypes.TASK_STARTED,
        mapOf("taskId" to "t1", "position" to 1, "total" to 2)
    )
    ledger.appendEvent(
        projectId, EventTypes.TASK_EXECUTED,
        mapOf(
            "taskId"            to "t1",
            "contractId"        to "c1",
            "contractorId"      to "llm-contractor",
            "artifactReference" to "artifact-t1",
            "executionStatus"   to "SUCCESS",
            "validationStatus"  to "VALIDATED",
            "validationReasons" to "all-checks-passed",
            "report_reference"  to "rrid-clc1erv",
            "position"          to 1,
            "total"             to 2
        )
    )
    ledger.appendEvent(
        projectId, EventTypes.TASK_COMPLETED,
        mapOf("taskId" to "t1", "position" to 1, "total" to 2)
    )
    ledger.appendEvent(
        projectId, EventTypes.CONTRACT_COMPLETED,
        mapOf(
            "position"         to 1,
            "total"            to 2,
            "contractId"       to "c1",
            "report_reference" to "rrid-clc1erv"
        )
    )

    // ── Contract 2 ────────────────────────────────────────────────────────────
    ledger.appendEvent(
        projectId, EventTypes.CONTRACT_STARTED,
        mapOf("contract_id" to "c2", "position" to 2, "total" to 2)
    )
    ledger.appendEvent(
        projectId, EventTypes.TASK_ASSIGNED,
        mapOf(
            "taskId"           to "t2",
            "position"         to 2,
            "total"            to 2,
            "contractId"       to "c2",
            "report_reference" to "rrid-clc1erv",
            "requirements"     to "build-feature-beta",
            "constraints"      to "none"
        )
    )
    ledger.appendEvent(
        projectId, EventTypes.TASK_STARTED,
        mapOf("taskId" to "t2", "position" to 2, "total" to 2)
    )
    ledger.appendEvent(
        projectId, EventTypes.TASK_EXECUTED,
        mapOf(
            "taskId"            to "t2",
            "contractId"        to "c2",
            "contractorId"      to "llm-contractor",
            "artifactReference" to "artifact-t2",
            "executionStatus"   to "SUCCESS",
            "validationStatus"  to "VALIDATED",
            "validationReasons" to "all-checks-passed",
            "report_reference"  to "rrid-clc1erv",
            "position"          to 2,
            "total"             to 2
        )
    )
    ledger.appendEvent(
        projectId, EventTypes.TASK_COMPLETED,
        mapOf("taskId" to "t2", "position" to 2, "total" to 2)
    )
    ledger.appendEvent(
        projectId, EventTypes.CONTRACT_COMPLETED,
        mapOf(
            "position"         to 2,
            "total"            to 2,
            "contractId"       to "c2",
            "report_reference" to "rrid-clc1erv"
        )
    )

    // ── Execution completed ───────────────────────────────────────────────────
    ledger.appendEvent(
        projectId, EventTypes.EXECUTION_COMPLETED,
        mapOf("total" to 2)
    )
}

// ── TESTS ─────────────────────────────────────────────────────────────────────

class LedgerBoundAssemblyValidationTest {

    // ── STEP 1: Ledger construction ───────────────────────────────────────────

    @Test
    fun `STEP 1 - ledger construction produces valid full execution trace via appendEvent only`() {
        val ledger    = realLedger()
        val projectId = "clc1erv-step1"

        buildTwoContractLedger(ledger, projectId)

        val events = ledger.loadEvents(projectId)

        // Exactly 18 events before assembly
        assertEquals("Expected 18 events in pre-assembly ledger", 18, events.size)

        // First event must be INTENT_SUBMITTED
        assertEquals(EventTypes.INTENT_SUBMITTED, events.first().type)

        // Last pre-assembly event must be EXECUTION_COMPLETED
        assertEquals(EventTypes.EXECUTION_COMPLETED, events.last().type)

        // N ≥ 2 contracts completed
        val completedCount = events.count { it.type == EventTypes.CONTRACT_COMPLETED }
        assertTrue("Expected N ≥ 2 CONTRACT_COMPLETED, got $completedCount", completedCount >= 2)

        // LedgerAudit must pass — no illegal transitions, no unknown types
        val audit = LedgerAudit(ledger).auditLedger(projectId)
        assertTrue(
            "LedgerAudit failed — errors: ${audit.errors}",
            audit.valid
        )
        assertEquals("LedgerAudit must report zero errors", 0, audit.errors.size)
        assertEquals("LedgerAudit must have checked all 18 events", 18, audit.checkedEvents)

        // Sequence numbers must be contiguous 0..17
        events.forEachIndexed { idx, ev ->
            assertEquals("Event[$idx] sequenceNumber mismatch", idx.toLong(), ev.sequenceNumber)
        }
    }

    // ── STEP 2 + 3: Assembly execution and output capture ─────────────────────

    @Test
    fun `STEP 2 and 3 - assemble emits ASSEMBLY_STARTED and ASSEMBLY_COMPLETED via ledger`() {
        val ledger    = realLedger()
        val projectId = "clc1erv-step2"

        buildTwoContractLedger(ledger, projectId)

        val result = AssemblyModule().assemble(projectId, ledger)

        // Must succeed
        assertTrue("assemble() must return Assembled, got $result", result is AssemblyExecutionResult.Assembled)
        val assembled = result as AssemblyExecutionResult.Assembled

        // Ledger must now contain ASSEMBLY_STARTED + ASSEMBLY_COMPLETED
        val events = ledger.loadEvents(projectId)
        assertTrue(
            "Ledger must contain ASSEMBLY_STARTED after assemble()",
            events.any { it.type == EventTypes.ASSEMBLY_STARTED }
        )
        assertTrue(
            "Ledger must contain ASSEMBLY_COMPLETED after assemble()",
            events.any { it.type == EventTypes.ASSEMBLY_COMPLETED }
        )

        // Full post-assembly size: 18 (execution) + 2 (assembly events) = 20
        assertEquals("Post-assembly ledger must have 20 events", 20, events.size)

        // AssemblyContractReport integrity
        val report = assembled.assemblyReport
        assertEquals("rrid-clc1erv", report.reportReference)
        assertEquals("cset-clc1erv", report.contractSetId)
        assertEquals(2, report.totalContracts)
        assertTrue("taskId must follow pattern assembly_<RRID>", report.taskId.startsWith("assembly_"))
        assertEquals(report.taskId, report.assemblyId)

        // FinalArtifact integrity
        val artifact = assembled.finalArtifact
        assertEquals("rrid-clc1erv", artifact.reportReference)
        assertEquals("cset-clc1erv", artifact.contractSetId)
        assertEquals(2, artifact.totalContracts)
        assertEquals(2, artifact.contractOutputs.size)
        assertEquals(2, artifact.traceMap.size)
    }

    // ── STEP 4A: Structural completeness ─────────────────────────────────────

    @Test
    fun `STEP 4A - every CONTRACT_COMPLETED appears in FinalArtifact contractOutputs`() {
        val ledger    = realLedger()
        val projectId = "clc1erv-4a"

        buildTwoContractLedger(ledger, projectId)

        val result   = AssemblyModule().assemble(projectId, ledger) as AssemblyExecutionResult.Assembled
        val artifact = result.finalArtifact

        // All contractIds from CONTRACT_COMPLETED must be present
        val completedIds = ledger.loadEvents(projectId)
            .filter { it.type == EventTypes.CONTRACT_COMPLETED }
            .mapNotNull { it.payload["contractId"]?.toString() }

        val outputIds = artifact.contractOutputs.map { it.contractId }.toSet()

        completedIds.forEach { id ->
            assertTrue("Contract '$id' from CONTRACT_COMPLETED missing in FinalArtifact", id in outputIds)
        }
        assertEquals("No missing contracts", completedIds.size, artifact.contractOutputs.size)
    }

    // ── STEP 4B: Determinism (3 runs on identical ledger) ─────────────────────

    @Test
    fun `STEP 4B - assembly is deterministic — 3 runs on identical ledger produce identical output`() {
        // Each run gets a FRESH ledger with the same events to satisfy the idempotency guard.
        fun run(runId: Int): AssemblyExecutionResult.Assembled {
            val ledger    = realLedger()
            val projectId = "clc1erv-4b-run$runId"
            buildTwoContractLedger(ledger, projectId)
            return AssemblyModule().assemble(projectId, ledger) as AssemblyExecutionResult.Assembled
        }

        val run1 = run(1)
        val run2 = run(2)
        val run3 = run(3)

        // FinalArtifact must be structurally identical across all three runs
        assertEquals("Run 1 vs Run 2: reportReference mismatch", run1.finalArtifact.reportReference, run2.finalArtifact.reportReference)
        assertEquals("Run 1 vs Run 3: reportReference mismatch", run1.finalArtifact.reportReference, run3.finalArtifact.reportReference)

        assertEquals("Run 1 vs Run 2: totalContracts mismatch", run1.finalArtifact.totalContracts, run2.finalArtifact.totalContracts)
        assertEquals("Run 1 vs Run 3: totalContracts mismatch", run1.finalArtifact.totalContracts, run3.finalArtifact.totalContracts)

        assertEquals("Run 1 vs Run 2: contractOutputs size mismatch", run1.finalArtifact.contractOutputs.size, run2.finalArtifact.contractOutputs.size)
        assertEquals("Run 1 vs Run 3: contractOutputs size mismatch", run1.finalArtifact.contractOutputs.size, run3.finalArtifact.contractOutputs.size)

        // contractOutputs must match element-by-element (order, contractId, artifactReference)
        run1.finalArtifact.contractOutputs.forEachIndexed { i, out1 ->
            val out2 = run2.finalArtifact.contractOutputs[i]
            val out3 = run3.finalArtifact.contractOutputs[i]
            assertEquals("Run 1 vs Run 2: contractOutputs[$i].contractId",       out1.contractId,        out2.contractId)
            assertEquals("Run 1 vs Run 3: contractOutputs[$i].contractId",       out1.contractId,        out3.contractId)
            assertEquals("Run 1 vs Run 2: contractOutputs[$i].position",         out1.position,          out2.position)
            assertEquals("Run 1 vs Run 3: contractOutputs[$i].position",         out1.position,          out3.position)
            assertEquals("Run 1 vs Run 2: contractOutputs[$i].artifactReference", out1.artifactReference, out2.artifactReference)
            assertEquals("Run 1 vs Run 3: contractOutputs[$i].artifactReference", out1.artifactReference, out3.artifactReference)
        }

        // traceMap must match
        assertEquals("Run 1 vs Run 2: traceMap mismatch", run1.finalArtifact.traceMap, run2.finalArtifact.traceMap)
        assertEquals("Run 1 vs Run 3: traceMap mismatch", run1.finalArtifact.traceMap, run3.finalArtifact.traceMap)

        // AssemblyContractReport must match
        assertEquals("Run 1 vs Run 2: assemblyId mismatch", run1.assemblyReport.assemblyId, run2.assemblyReport.assemblyId)
        assertEquals("Run 1 vs Run 3: assemblyId mismatch", run1.assemblyReport.assemblyId, run3.assemblyReport.assemblyId)
    }

    // ── STEP 4C: Ordering integrity ───────────────────────────────────────────

    @Test
    fun `STEP 4C - contractOutputs are strictly ordered by position ascending`() {
        val ledger    = realLedger()
        val projectId = "clc1erv-4c"

        buildTwoContractLedger(ledger, projectId)

        val result   = AssemblyModule().assemble(projectId, ledger) as AssemblyExecutionResult.Assembled
        val outputs  = result.finalArtifact.contractOutputs

        // Positions must be strictly ascending: 1, 2, ..., N
        outputs.forEachIndexed { idx, out ->
            assertEquals("contractOutputs[$idx].position must equal ${idx + 1}", idx + 1, out.position)
        }

        // Verify explicitly sorted order
        val sorted = outputs.sortedBy { it.position }
        assertEquals("contractOutputs must already be sorted by position", sorted, outputs)
    }

    // ── STEP 4D: No silent drops ──────────────────────────────────────────────

    @Test
    fun `STEP 4D - every TASK_EXECUTED SUCCESS artifact appears in FinalArtifact`() {
        val ledger    = realLedger()
        val projectId = "clc1erv-4d"

        buildTwoContractLedger(ledger, projectId)

        val result   = AssemblyModule().assemble(projectId, ledger) as AssemblyExecutionResult.Assembled
        val artifact = result.finalArtifact

        // Collect all TASK_EXECUTED(SUCCESS) artifactReferences from ledger
        val executedArtifacts = ledger.loadEvents(projectId)
            .filter { ev ->
                ev.type == EventTypes.TASK_EXECUTED &&
                ev.payload["executionStatus"]?.toString() == "SUCCESS"
            }
            .mapNotNull { it.payload["artifactReference"]?.toString() }

        // Every artifact must appear in contractOutputs
        val outputArtifacts = artifact.contractOutputs.map { it.artifactReference }.toSet()
        executedArtifacts.forEach { ref ->
            assertTrue("artifactReference '$ref' from TASK_EXECUTED(SUCCESS) missing in FinalArtifact", ref in outputArtifacts)
        }
        assertEquals("Count mismatch: FinalArtifact must have exactly one output per SUCCESS task", executedArtifacts.size, artifact.contractOutputs.size)
    }

    // ── STEP 4E: No duplication ───────────────────────────────────────────────

    @Test
    fun `STEP 4E - contractId and artifactReference each appear exactly once in FinalArtifact`() {
        val ledger    = realLedger()
        val projectId = "clc1erv-4e"

        buildTwoContractLedger(ledger, projectId)

        val result  = AssemblyModule().assemble(projectId, ledger) as AssemblyExecutionResult.Assembled
        val outputs = result.finalArtifact.contractOutputs

        // contractId uniqueness
        val contractIds = outputs.map { it.contractId }
        assertEquals("contractId must appear exactly once each", contractIds.size, contractIds.toSet().size)

        // artifactReference uniqueness
        val artifactRefs = outputs.map { it.artifactReference }
        assertEquals("artifactReference must appear exactly once each", artifactRefs.size, artifactRefs.toSet().size)

        // ASSEMBLY_COMPLETED must appear exactly once in ledger
        val assemblyCompletedCount = ledger.loadEvents(projectId).count { it.type == EventTypes.ASSEMBLY_COMPLETED }
        assertEquals("ASSEMBLY_COMPLETED must appear exactly once", 1, assemblyCompletedCount)
    }

    // ── STEP 4F: Idempotency ──────────────────────────────────────────────────

    @Test
    fun `STEP 4F - second assemble call on same ledger produces no new ledger writes`() {
        val ledger    = realLedger()
        val projectId = "clc1erv-4f"

        buildTwoContractLedger(ledger, projectId)

        // First call — should assemble successfully
        val first = AssemblyModule().assemble(projectId, ledger)
        assertTrue("First assemble must return Assembled", first is AssemblyExecutionResult.Assembled)

        val sizeAfterFirst = ledger.loadEvents(projectId).size

        // Second call — ASSEMBLY_COMPLETED already present; must return AlreadyCompleted
        val second = AssemblyModule().assemble(projectId, ledger)
        assertTrue(
            "Second assemble must return AlreadyCompleted (idempotency guard), got $second",
            second is AssemblyExecutionResult.AlreadyCompleted
        )

        val sizeAfterSecond = ledger.loadEvents(projectId).size

        assertEquals(
            "Idempotency: second assemble() must produce NO new ledger writes",
            sizeAfterFirst,
            sizeAfterSecond
        )
    }

    // ── BLOCK CONDITIONS verification ─────────────────────────────────────────

    @Test
    fun `BLOCK - assemble is blocked when EXECUTION_COMPLETED is absent`() {
        val ledger    = realLedger()
        val projectId = "clc1erv-block-no-exec"

        // Build a complete single-contract execution trace that stops before EXECUTION_COMPLETED.
        // Every event is appended via appendEvent() → ValidationLayer; no bypass.
        ledger.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "block-test"))
        ledger.appendEvent(
            projectId, EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "intentId" to "intent-block", "contractSetId" to "cset-block",
                "report_reference" to "rrid-block", "total" to 1,
                "contracts" to listOf(mapOf("contractId" to "cb1", "position" to 1))
            )
        )
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_READY, emptyMap())
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_APPROVED, emptyMap())
        ledger.appendEvent(projectId, EventTypes.EXECUTION_STARTED, emptyMap())
        ledger.appendEvent(
            projectId, EventTypes.CONTRACT_STARTED,
            mapOf("contract_id" to "cb1", "position" to 1, "total" to 1)
        )
        ledger.appendEvent(
            projectId, EventTypes.TASK_ASSIGNED,
            mapOf(
                "taskId" to "tb1", "position" to 1, "total" to 1,
                "contractId" to "cb1", "report_reference" to "rrid-block",
                "requirements" to "req-block", "constraints" to "none"
            )
        )
        ledger.appendEvent(
            projectId, EventTypes.TASK_STARTED,
            mapOf("taskId" to "tb1", "position" to 1, "total" to 1)
        )
        ledger.appendEvent(
            projectId, EventTypes.TASK_EXECUTED,
            mapOf(
                "taskId" to "tb1", "contractId" to "cb1", "contractorId" to "llm-contractor",
                "artifactReference" to "artifact-tb1", "executionStatus" to "SUCCESS",
                "validationStatus" to "VALIDATED", "validationReasons" to "ok",
                "report_reference" to "rrid-block", "position" to 1, "total" to 1
            )
        )
        ledger.appendEvent(
            projectId, EventTypes.TASK_COMPLETED,
            mapOf("taskId" to "tb1", "position" to 1, "total" to 1)
        )
        ledger.appendEvent(
            projectId, EventTypes.CONTRACT_COMPLETED,
            mapOf("position" to 1, "total" to 1, "contractId" to "cb1", "report_reference" to "rrid-block")
        )
        // EXECUTION_COMPLETED intentionally omitted — assembly trigger condition unmet

        val sizeBeforeAttempt = ledger.loadEvents(projectId).size

        val result = AssemblyModule().assemble(projectId, ledger)
        assertEquals(
            "AssemblyModule must return NotTriggered when EXECUTION_COMPLETED is absent",
            AssemblyExecutionResult.NotTriggered,
            result
        )

        // No assembly events must have been written
        val eventsAfter = ledger.loadEvents(projectId)
        assertEquals("Ledger size must not change when assemble() is blocked", sizeBeforeAttempt, eventsAfter.size)
        assertFalse("ASSEMBLY_STARTED must NOT be written", eventsAfter.any { it.type == EventTypes.ASSEMBLY_STARTED })
        assertFalse("ASSEMBLY_COMPLETED must NOT be written", eventsAfter.any { it.type == EventTypes.ASSEMBLY_COMPLETED })
    }

    // ── SELF VALIDATION ───────────────────────────────────────────────────────

    @Test
    fun `SELF VALIDATION - ledger authority preserved, ValidationLayer triggered, no bypass`() {
        val ledger    = realLedger()
        val projectId = "clc1erv-selfcheck"

        // ValidationLayer rejection confirms it fires on every appendEvent() call.
        // Attempt an illegal transition — CONTRACTS_GENERATED directly after INTENT_SUBMITTED is legal,
        // but attempting EXECUTION_COMPLETED as first event (bypassing the chain) must be rejected.
        var rejectedByValidation = false
        try {
            ledger.appendEvent(
                projectId, EventTypes.EXECUTION_COMPLETED,
                mapOf("total" to 1)
            )
        } catch (e: Exception) {
            rejectedByValidation = true
        }
        assertTrue(
            "ValidationLayer must reject illegal first event (not INTENT_SUBMITTED)",
            rejectedByValidation
        )

        // Confirm ledger is empty — no bypass occurred
        val events = ledger.loadEvents(projectId)
        assertTrue("Ledger must remain empty after rejected write", events.isEmpty())

        // Full valid ledger confirms REAL path works end-to-end
        buildTwoContractLedger(ledger, projectId)
        val audit = LedgerAudit(ledger).auditLedger(projectId)
        assertTrue("REAL ledger authority preserved: LedgerAudit passes", audit.valid)

        val result = AssemblyModule().assemble(projectId, ledger)
        assertTrue("REAL execution path produces Assembled result", result is AssemblyExecutionResult.Assembled)
    }
}
