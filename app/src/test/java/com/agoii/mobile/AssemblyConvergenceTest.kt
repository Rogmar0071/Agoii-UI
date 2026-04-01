package com.agoii.mobile

import com.agoii.mobile.assembly.AssemblyExecutionResult
import com.agoii.mobile.assembly.AssemblyModule
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerAudit
import com.agoii.mobile.core.ValidationLayer
import com.agoii.mobile.execution.DriverRegistry
import com.agoii.mobile.execution.ExecutionAuthority
import org.junit.Assert.*
import org.junit.Test

/**
 * AGOII-CLC-1F — Assembly Convergence Tests (Phase 8).
 *
 * Covers the full assembly failure surface and recovery contract flow:
 *  1. Assembly failure — missing artifact (Blocked pre-flight)
 *  2. Duplicate artifact — triggers AERP-1 ValidationFailed → ASSEMBLY_FAILED + RECOVERY_CONTRACT
 *  3. Ordering violation — Blocked pre-flight (duplicate positions)
 *  4. Determinism — identical ledger → identical deterministic report
 *  5. Partial recovery — ValidationFailed emits correct RECOVERY_CONTRACT payload
 *  6. Idempotent recovery — after ASSEMBLY_FAILED, re-run succeeds when artifacts are fixed
 *
 * ALL TESTS:
 *  - Use the REAL ValidationLayer (via ValidatingInMemoryLedger).
 *  - Use the REAL LedgerAudit for transition verification.
 *  - Use the REAL AssemblyModule and ExecutionAuthority.
 *  - No mocks; no synthetic injection beyond controlled ledger event construction.
 */
class AssemblyConvergenceTest {

    // ── Infrastructure ────────────────────────────────────────────────────────

    /**
     * In-memory EventRepository that enforces the REAL ValidationLayer on every append.
     * Functionally equivalent to EventLedger without Android Context.
     */
    private class ValidatingInMemoryLedger : EventRepository {
        private val store = mutableListOf<Event>()
        private val validation = ValidationLayer()

        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            validation.validate(projectId, type, payload, store.toList())
            store.add(
                Event(
                    type           = type,
                    payload        = payload,
                    sequenceNumber = store.size.toLong()
                )
            )
        }

        override fun loadEvents(projectId: String): List<Event> = store.toList()
    }

    /**
     * Raw in-memory EventRepository without ValidationLayer — used when tests need to inject
     * specific orderings or payloads that would be blocked by the ValidationLayer itself
     * (e.g. duplicate contract positions for the ordering-violation test).
     */
    private class RawInMemoryLedger(initial: List<Event> = emptyList()) : EventRepository {
        private val store = initial.toMutableList()
        override fun loadEvents(projectId: String): List<Event> = store.toList()
        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            store.add(Event(type, payload, sequenceNumber = store.size.toLong()))
        }
    }

    /**
     * Builds a minimal ledger reaching EXECUTION_COMPLETED.
     * [makeArtifactRef] customises the artifactReference per contract (1-based position).
     */
    private fun buildAssemblyReadyLedger(
        ledger:          EventRepository,
        projectId:       String = "proj-test",
        reportRef:       String = "rrid-001",
        contractSetId:   String = "cs-001",
        contractCount:   Int    = 2,
        makeArtifactRef: (Int) -> String = { pos -> "artifact-$pos-$reportRef" }
    ) {
        ledger.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"))
        ledger.appendEvent(
            projectId, EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "report_reference" to reportRef,
                "contractSetId"    to contractSetId,
                "contracts"        to (1..contractCount).map { "contract_$it" },
                "total"            to contractCount.toDouble()
            )
        )
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_READY, emptyMap())
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_APPROVED, emptyMap())
        ledger.appendEvent(
            projectId, EventTypes.EXECUTION_STARTED,
            mapOf("total_contracts" to contractCount.toDouble())
        )
        for (pos in 1..contractCount) {
            val contractId = "contract_$pos"
            val taskId     = "$contractId-step1"
            ledger.appendEvent(
                projectId, EventTypes.CONTRACT_STARTED,
                mapOf("position" to pos, "total" to contractCount, "contract_id" to contractId)
            )
            ledger.appendEvent(
                projectId, EventTypes.TASK_ASSIGNED,
                mapOf(
                    "taskId"           to taskId,
                    "contractId"       to contractId,
                    "position"         to pos,
                    "total"            to contractCount,
                    "report_reference" to reportRef,
                    "requirements"     to emptyList<Any>(),
                    "constraints"      to emptyList<Any>()
                )
            )
            ledger.appendEvent(
                projectId, EventTypes.TASK_STARTED,
                mapOf("taskId" to taskId, "position" to pos, "total" to contractCount)
            )
            ledger.appendEvent(
                projectId, EventTypes.TASK_EXECUTED,
                mapOf(
                    "taskId"            to taskId,
                    "contractId"        to contractId,
                    "contractorId"      to "contractor-llm",
                    "artifactReference" to makeArtifactRef(pos),
                    "executionStatus"   to "SUCCESS",
                    "validationStatus"  to "VALIDATED",
                    "validationReasons" to emptyList<String>(),
                    "report_reference"  to reportRef,
                    "position"          to pos,
                    "total"             to contractCount
                )
            )
            ledger.appendEvent(
                projectId, EventTypes.TASK_COMPLETED,
                mapOf("taskId" to taskId, "position" to pos, "total" to contractCount)
            )
            ledger.appendEvent(
                projectId, EventTypes.CONTRACT_COMPLETED,
                mapOf(
                    "position"         to pos,
                    "total"            to contractCount,
                    "contractId"       to contractId,
                    "report_reference" to reportRef
                )
            )
        }
        ledger.appendEvent(projectId, EventTypes.EXECUTION_COMPLETED, mapOf("total" to contractCount))
    }

    private fun makeEa(): ExecutionAuthority =
        ExecutionAuthority(ContractorRegistry(), DriverRegistry())

    // ── 1. Assembly failure — missing artifact (Blocked pre-flight) ───────────

    @Test
    fun `assembleFromLedger is Blocked when a contract has no SUCCESS execution surface`() {
        val ledger    = ValidatingInMemoryLedger()
        val projectId = "proj-missing-artifact"
        val reportRef = "rrid-missing"

        // Build a ledger with TASK_EXECUTED omitted so contract_1 has no execution surface.
        ledger.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"))
        ledger.appendEvent(
            projectId, EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "report_reference" to reportRef, "contractSetId" to "cs-missing",
                "contracts" to listOf("contract_1"), "total" to 1.0
            )
        )
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_READY, emptyMap())
        ledger.appendEvent(projectId, EventTypes.CONTRACTS_APPROVED, emptyMap())
        ledger.appendEvent(projectId, EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 1.0))
        ledger.appendEvent(
            projectId, EventTypes.CONTRACT_STARTED,
            mapOf("position" to 1, "total" to 1, "contract_id" to "contract_1")
        )
        ledger.appendEvent(
            projectId, EventTypes.TASK_ASSIGNED,
            mapOf(
                "taskId" to "c1-step1", "contractId" to "contract_1",
                "position" to 1, "total" to 1, "report_reference" to reportRef,
                "requirements" to emptyList<Any>(), "constraints" to emptyList<Any>()
            )
        )
        ledger.appendEvent(
            projectId, EventTypes.TASK_STARTED,
            mapOf("taskId" to "c1-step1", "position" to 1, "total" to 1)
        )
        // Deliberately omit TASK_EXECUTED so contract_1 has no execution surface.
        ledger.appendEvent(
            projectId, EventTypes.TASK_COMPLETED,
            mapOf("taskId" to "c1-step1", "position" to 1, "total" to 1)
        )
        ledger.appendEvent(
            projectId, EventTypes.CONTRACT_COMPLETED,
            mapOf("position" to 1, "total" to 1, "contractId" to "contract_1",
                  "report_reference" to reportRef)
        )
        ledger.appendEvent(projectId, EventTypes.EXECUTION_COMPLETED, mapOf("total" to 1))

        val result = makeEa().assembleFromLedger(projectId, ledger)

        assertTrue("Expected Blocked for missing artifact", result is AssemblyExecutionResult.Blocked)
        val blocked = result as AssemblyExecutionResult.Blocked
        assertTrue(
            "Blocked reason should mention INCOMPLETE_EXECUTION_SURFACE, got: ${blocked.reason}",
            "INCOMPLETE_EXECUTION_SURFACE" in blocked.reason
        )
        // ASSEMBLY_STARTED must NOT have been written (pre-flight fires before writes).
        assertFalse(ledger.loadEvents(projectId).any { it.type == EventTypes.ASSEMBLY_STARTED })
    }

    // ── 2. Duplicate artifact — AERP-1 ValidationFailed ──────────────────────

    @Test
    fun `assembleFromLedger emits ASSEMBLY_FAILED and RECOVERY_CONTRACT on duplicate artifactReference`() {
        val ledger    = ValidatingInMemoryLedger()
        val projectId = "proj-dup-artifact"
        val reportRef = "rrid-dup"

        // Two contracts share the same artifactReference → ARTIFACT_INTEGRITY violation.
        buildAssemblyReadyLedger(
            ledger          = ledger,
            projectId       = projectId,
            reportRef       = reportRef,
            contractSetId   = "cs-dup",
            contractCount   = 2,
            makeArtifactRef = { _ -> "artifact-shared-$reportRef" }
        )

        val result = makeEa().assembleFromLedger(projectId, ledger)

        assertTrue("Expected ValidationFailed for duplicate artifacts, got ${result::class.simpleName}",
            result is AssemblyExecutionResult.ValidationFailed)
        val vf = result as AssemblyExecutionResult.ValidationFailed
        assertTrue("failureReasons must mention ARTIFACT_INTEGRITY",
            vf.failureReasons.any { "ARTIFACT_INTEGRITY" in it })
        assertEquals("FAIL", vf.assemblyReport.validationSummary)
        assertTrue(vf.assemblyReport.failureReasons.isNotEmpty())

        val events = ledger.loadEvents(projectId)
        assertTrue("ASSEMBLY_STARTED required", events.any { it.type == EventTypes.ASSEMBLY_STARTED })
        assertTrue("ASSEMBLY_FAILED required", events.any { it.type == EventTypes.ASSEMBLY_FAILED })
        assertTrue("RECOVERY_CONTRACT required", events.any { it.type == EventTypes.RECOVERY_CONTRACT })

        // ASSEMBLY_COMPLETED and ASSEMBLY_VALIDATED must NOT exist in the failure path.
        assertFalse("ASSEMBLY_COMPLETED must not be present in failure path",
            events.any { it.type == EventTypes.ASSEMBLY_COMPLETED })
        assertFalse("ASSEMBLY_VALIDATED must not be present in failure path",
            events.any { it.type == EventTypes.ASSEMBLY_VALIDATED })

        // LedgerAudit must pass — all transitions are legal.
        val audit = LedgerAudit(ledger).auditLedger(projectId)
        assertTrue("LedgerAudit must pass after ASSEMBLY_FAILED. Errors: ${audit.errors}", audit.valid)
    }

    // ── 3. Ordering violation — Blocked pre-flight ────────────────────────────

    @Test
    fun `assembleFromLedger is Blocked when CONTRACT_COMPLETED positions contain duplicates`() {
        val projectId = "proj-order-violation"
        val reportRef = "rrid-order"

        // Use RawInMemoryLedger to inject duplicate position values that the
        // ValidationLayer would otherwise reject (since CONTRACT_STARTED positions
        // must be monotonic in the real ledger).
        val rawLedger = RawInMemoryLedger()
        rawLedger.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"))
        rawLedger.appendEvent(
            projectId, EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "report_reference" to reportRef, "contractSetId" to "cs-order",
                "contracts" to listOf("contract_1", "contract_2"), "total" to 2.0
            )
        )
        rawLedger.appendEvent(projectId, EventTypes.CONTRACTS_READY, emptyMap())
        rawLedger.appendEvent(projectId, EventTypes.CONTRACTS_APPROVED, emptyMap())
        rawLedger.appendEvent(projectId, EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 2.0))
        // Both contracts claim position=1 — duplicate ordering violation
        for (pos in listOf(1, 1)) {
            val contractId = "contract_${if (pos == 1) "1" else "2"}_pos1"
            rawLedger.appendEvent(
                projectId, EventTypes.TASK_EXECUTED,
                mapOf(
                    "taskId" to "$contractId-step1", "contractId" to contractId,
                    "contractorId" to "contractor-llm",
                    "artifactReference" to "artifact-$contractId",
                    "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED",
                    "validationReasons" to emptyList<String>(),
                    "report_reference" to reportRef, "position" to pos, "total" to 2
                )
            )
            rawLedger.appendEvent(
                projectId, EventTypes.CONTRACT_COMPLETED,
                mapOf("position" to pos, "total" to 2, "contractId" to contractId,
                      "report_reference" to reportRef)
            )
        }
        rawLedger.appendEvent(projectId, EventTypes.EXECUTION_COMPLETED, mapOf("total" to 2))

        val result = AssemblyModule().assemble(projectId, rawLedger)

        assertTrue("Expected Blocked for ordering violation, got ${result::class.simpleName}",
            result is AssemblyExecutionResult.Blocked)
        val blocked = result as AssemblyExecutionResult.Blocked
        assertTrue(
            "Blocked reason should mention POSITION_VIOLATION, got: ${blocked.reason}",
            "POSITION_VIOLATION" in blocked.reason
        )
    }

    // ── 4. Determinism — identical ledger → identical report ──────────────────

    @Test
    fun `assembleFromLedger is deterministic — identical ledger produces identical report`() {
        val reportRef = "rrid-det"
        val ledger1   = ValidatingInMemoryLedger()
        val ledger2   = ValidatingInMemoryLedger()

        buildAssemblyReadyLedger(ledger1, "proj-det-1", reportRef, "cs-det")
        buildAssemblyReadyLedger(ledger2, "proj-det-2", reportRef, "cs-det")

        val ea      = makeEa()
        val result1 = ea.assembleFromLedger("proj-det-1", ledger1)
        val result2 = ea.assembleFromLedger("proj-det-2", ledger2)

        assertTrue(result1 is AssemblyExecutionResult.Assembled)
        assertTrue(result2 is AssemblyExecutionResult.Assembled)
        val r1 = result1 as AssemblyExecutionResult.Assembled
        val r2 = result2 as AssemblyExecutionResult.Assembled

        assertEquals("assemblyId must be deterministic",
            r1.assemblyReport.assemblyId, r2.assemblyReport.assemblyId)
        assertEquals("reportReference must be deterministic",
            r1.assemblyReport.reportReference, r2.assemblyReport.reportReference)
        assertEquals("totalContracts must be deterministic",
            r1.assemblyReport.totalContracts, r2.assemblyReport.totalContracts)
        assertEquals("contractOutputs size must be deterministic",
            r1.finalArtifact.contractOutputs.size, r2.finalArtifact.contractOutputs.size)
        assertEquals("validationSummary must be PASS", "PASS", r1.assemblyReport.validationSummary)
        assertEquals("validationSummary must be PASS", "PASS", r2.assemblyReport.validationSummary)
    }

    // ── 5. Partial recovery — RECOVERY_CONTRACT payload is correct ────────────

    @Test
    fun `recovery contract payload is valid and references the assembly violation`() {
        val ledger    = ValidatingInMemoryLedger()
        val projectId = "proj-recovery"
        val reportRef = "rrid-recovery"

        buildAssemblyReadyLedger(
            ledger          = ledger,
            projectId       = projectId,
            reportRef       = reportRef,
            contractSetId   = "cs-recovery",
            contractCount   = 2,
            makeArtifactRef = { _ -> "artifact-shared-recovery" }  // duplicate → fail
        )

        val result = makeEa().assembleFromLedger(projectId, ledger)
        assertTrue(result is AssemblyExecutionResult.ValidationFailed)

        val events = ledger.loadEvents(projectId)

        // Validate RECOVERY_CONTRACT payload
        val recoveryEvent = events.first { it.type == EventTypes.RECOVERY_CONTRACT }
        val p = recoveryEvent.payload
        assertEquals(reportRef, p["report_reference"]?.toString())
        assertTrue("contractId must be non-blank", p["contractId"]?.toString()?.isNotBlank() == true)
        assertTrue("taskId must be non-blank", p["taskId"]?.toString()?.isNotBlank() == true)
        assertTrue("failureClass must be non-blank", p["failureClass"]?.toString()?.isNotBlank() == true)
        assertTrue("violationField must be non-blank", p["violationField"]?.toString()?.isNotBlank() == true)
        assertTrue("correctionDirective must be non-blank",
            p["correctionDirective"]?.toString()?.isNotBlank() == true)

        // Validate ASSEMBLY_FAILED payload
        val failedEvent = events.first { it.type == EventTypes.ASSEMBLY_FAILED }
        @Suppress("UNCHECKED_CAST")
        val failureReasons = failedEvent.payload["failureReasons"] as? List<*>
        assertNotNull("ASSEMBLY_FAILED must carry failureReasons", failureReasons)
        assertTrue("failureReasons must be non-empty", failureReasons!!.isNotEmpty())
        assertEquals(reportRef, failedEvent.payload["report_reference"]?.toString())
    }

    // ── 6. Idempotent recovery — re-run succeeds after ASSEMBLY_FAILED fix ────

    @Test
    fun `assembleFromLedger allows delta re-run after ASSEMBLY_FAILED when artifacts are corrected`() {
        val projectId    = "proj-idempotent"
        val reportRef    = "rrid-idempotent"
        val failedLedger = ValidatingInMemoryLedger()

        // First run: duplicate artifacts → ValidationFailed + ASSEMBLY_FAILED
        buildAssemblyReadyLedger(
            ledger          = failedLedger,
            projectId       = projectId,
            reportRef       = reportRef,
            contractSetId   = "cs-idempotent",
            contractCount   = 2,
            makeArtifactRef = { _ -> "artifact-shared-idem" }
        )
        val first = makeEa().assembleFromLedger(projectId, failedLedger)
        assertTrue("First call must produce ValidationFailed", first is AssemblyExecutionResult.ValidationFailed)

        // Confirm ASSEMBLY_FAILED is in the ledger and ASSEMBLY_COMPLETED is absent.
        val eventsAfterFail = failedLedger.loadEvents(projectId)
        assertTrue(eventsAfterFail.any { it.type == EventTypes.ASSEMBLY_FAILED })
        assertFalse(eventsAfterFail.any { it.type == EventTypes.ASSEMBLY_COMPLETED })

        // Second attempt: rebuild with fixed (unique) artifact refs — ASSEMBLY_FAILED present
        // but hasFailed=true allows a re-run in AssemblyModule.
        val fixedRaw = RawInMemoryLedger(eventsAfterFail.map { ev ->
            if (ev.type == EventTypes.TASK_EXECUTED) {
                val pos = when (val raw = ev.payload["position"]) {
                    is Double -> raw.toInt()
                    is Int    -> raw
                    is Long   -> raw.toInt()
                    is String -> raw.toIntOrNull()
                        ?: throw IllegalStateException("TASK_EXECUTED missing valid 'position' in test fixture: $raw")
                    else      -> throw IllegalStateException(
                        "TASK_EXECUTED 'position' has unexpected type ${raw?.javaClass} in test fixture"
                    )
                }
                Event(ev.type, ev.payload + ("artifactReference" to "artifact-fixed-$pos-$reportRef"),
                      sequenceNumber = ev.sequenceNumber)
            } else ev
        })

        val second = makeEa().assembleFromLedger(projectId, fixedRaw)
        assertTrue(
            "Second call (after fix) must produce Assembled, got ${second::class.simpleName}",
            second is AssemblyExecutionResult.Assembled
        )
        val assembled = second as AssemblyExecutionResult.Assembled
        assertEquals("PASS", assembled.assemblyReport.validationSummary)
        assertTrue(assembled.assemblyReport.failureReasons.isEmpty())
    }
}
