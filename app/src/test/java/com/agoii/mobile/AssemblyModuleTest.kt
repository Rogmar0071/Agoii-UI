package com.agoii.mobile

import com.agoii.mobile.assembly.AssemblyExecutionResult
import com.agoii.mobile.assembly.AssemblyModule
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.governor.Governor
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AssemblyModule] covering:
 *  1. Mixed-validity scenario (3 contracts: 2 valid, 1 invalid).
 *  2. Governor-only recovery: ASSEMBLY_FAILED → RECOVERY_CONTRACT.
 *  3. Re-entry guard hardening.
 *  4. Trace completeness enforcement.
 *  5. Pure projection: lockedSections and violationSurface correctness.
 */
class AssemblyModuleTest {

    // ── In-memory EventRepository for unit tests ──────────────────────────────

    private class MemoryRepository(initial: List<Event> = emptyList()) : EventRepository {
        private val store = initial.toMutableList()

        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            store.add(Event(type = type, payload = payload))
        }

        override fun loadEvents(projectId: String): List<Event> = store.toList()
    }

    // ── Shared ledger builders ────────────────────────────────────────────────

    /**
     * Builds a 3-contract ledger where:
     *  - contract_1 and contract_2 have SUCCESS TASK_EXECUTED (valid)
     *  - contract_3 has NO TASK_EXECUTED at all (invalid — INCOMPLETE_EXECUTION_SURFACE)
     */
    private fun mixedValidityLedger(): List<Event> = listOf(
        Event(
            EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "report_reference" to "rrid-mix-001",
                "contractSetId"    to "cset-001",
                "total"            to 3
            )
        ),
        Event(EventTypes.CONTRACT_COMPLETED,
            mapOf("contractId" to "contract_1", "position" to 1,
                  "report_reference" to "rrid-mix-001")),
        Event(EventTypes.CONTRACT_COMPLETED,
            mapOf("contractId" to "contract_2", "position" to 2,
                  "report_reference" to "rrid-mix-001")),
        Event(EventTypes.CONTRACT_COMPLETED,
            mapOf("contractId" to "contract_3", "position" to 3,
                  "report_reference" to "rrid-mix-001")),
        Event(EventTypes.TASK_EXECUTED,
            mapOf("contractId" to "contract_1", "executionStatus" to "SUCCESS",
                  "artifactReference" to "artifact-c1", "report_reference" to "rrid-mix-001")),
        Event(EventTypes.TASK_EXECUTED,
            mapOf("contractId" to "contract_2", "executionStatus" to "SUCCESS",
                  "artifactReference" to "artifact-c2", "report_reference" to "rrid-mix-001")),
        // contract_3 intentionally has NO TASK_EXECUTED → INCOMPLETE_EXECUTION_SURFACE
        Event(EventTypes.EXECUTION_COMPLETED, mapOf("total" to 3))
    )

    /** Fully valid 3-contract ledger (all SUCCESS). */
    private fun allValidLedger(): List<Event> = listOf(
        Event(
            EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "report_reference" to "rrid-valid-001",
                "contractSetId"    to "cset-valid-001",
                "total"            to 3
            )
        ),
        Event(EventTypes.CONTRACT_COMPLETED,
            mapOf("contractId" to "c1", "position" to 1, "report_reference" to "rrid-valid-001")),
        Event(EventTypes.CONTRACT_COMPLETED,
            mapOf("contractId" to "c2", "position" to 2, "report_reference" to "rrid-valid-001")),
        Event(EventTypes.CONTRACT_COMPLETED,
            mapOf("contractId" to "c3", "position" to 3, "report_reference" to "rrid-valid-001")),
        Event(EventTypes.TASK_EXECUTED,
            mapOf("contractId" to "c1", "executionStatus" to "SUCCESS",
                  "artifactReference" to "art-1", "report_reference" to "rrid-valid-001")),
        Event(EventTypes.TASK_EXECUTED,
            mapOf("contractId" to "c2", "executionStatus" to "SUCCESS",
                  "artifactReference" to "art-2", "report_reference" to "rrid-valid-001")),
        Event(EventTypes.TASK_EXECUTED,
            mapOf("contractId" to "c3", "executionStatus" to "SUCCESS",
                  "artifactReference" to "art-3", "report_reference" to "rrid-valid-001")),
        Event(EventTypes.EXECUTION_COMPLETED, mapOf("total" to 3))
    )

    // ── 1. Mixed-validity: 3 contracts, 2 valid, 1 invalid ───────────────────

    @Test
    fun `mixed-validity — ASSEMBLY_FAILED emitted when 1 of 3 contracts invalid`() {
        val repo   = MemoryRepository(mixedValidityLedger())
        val result = AssemblyModule().assemble("proj", repo)

        assertTrue("Expected Failed result", result is AssemblyExecutionResult.Failed)

        val written = repo.loadEvents("proj")
        assertTrue(
            "ASSEMBLY_FAILED must be written to ledger",
            written.any { it.type == EventTypes.ASSEMBLY_FAILED }
        )
        assertFalse(
            "ASSEMBLY_STARTED must NOT be written on failure",
            written.any { it.type == EventTypes.ASSEMBLY_STARTED }
        )
    }

    @Test
    fun `mixed-validity — lockedSections contains ONLY the 2 valid contracts`() {
        val repo   = MemoryRepository(mixedValidityLedger())
        val result = AssemblyModule().assemble("proj", repo) as AssemblyExecutionResult.Failed

        val lockedIds = result.lockedSections.map { it.contractId }.toSet()
        assertEquals(setOf("contract_1", "contract_2"), lockedIds)
    }

    @Test
    fun `mixed-validity — violationSurface contains ONLY the invalid contract`() {
        val repo   = MemoryRepository(mixedValidityLedger())
        val result = AssemblyModule().assemble("proj", repo) as AssemblyExecutionResult.Failed

        val violatedIds = result.violationSurface.map { it.contractId }.toSet()
        assertEquals(setOf("contract_3"), violatedIds)
    }

    @Test
    fun `mixed-validity — valid contracts are unchanged in lockedSections`() {
        val repo   = MemoryRepository(mixedValidityLedger())
        val result = AssemblyModule().assemble("proj", repo) as AssemblyExecutionResult.Failed

        val locked = result.lockedSections.associateBy { it.contractId }
        assertEquals("artifact-c1", locked["contract_1"]?.artifactReference)
        assertEquals("artifact-c2", locked["contract_2"]?.artifactReference)
    }

    @Test
    fun `mixed-validity — failure reasons are explicit with contractId, failureType, violatedInvariant`() {
        val repo   = MemoryRepository(mixedValidityLedger())
        val result = AssemblyModule().assemble("proj", repo) as AssemblyExecutionResult.Failed

        assertTrue("At least one failure reason required", result.failureReasons.isNotEmpty())
        for (reason in result.failureReasons) {
            assertTrue(
                "contractId must be non-blank: $reason",
                reason.contractId.isNotBlank()
            )
            assertTrue(
                "failureType must be non-blank: $reason",
                reason.failureType.isNotBlank()
            )
            assertTrue(
                "violatedInvariant must be non-blank: $reason",
                reason.violatedInvariant.isNotBlank()
            )
        }
        assertTrue(
            "contract_3 must appear in failureReasons",
            result.failureReasons.any { it.contractId == "contract_3" }
        )
        assertTrue(
            "INCOMPLETE_EXECUTION_SURFACE must be the failure type for contract_3",
            result.failureReasons.any {
                it.contractId == "contract_3" && it.failureType == "INCOMPLETE_EXECUTION_SURFACE"
            }
        )
    }

    // ── 2. Governor-only recovery: ASSEMBLY_FAILED → RECOVERY_CONTRACT ────────

    @Test
    fun `ASSEMBLY_FAILED triggers RECOVERY_CONTRACT from Governor`() {
        val events = mixedValidityLedger() + Event(
            EventTypes.ASSEMBLY_FAILED,
            mapOf(
                "report_reference"        to "rrid-mix-001",
                "contractSetId"           to "cset-001",
                "failureReasonContractId" to "contract_3",
                "failureType"             to "INCOMPLETE_EXECUTION_SURFACE",
                "violatedInvariant"       to "contract_3 has no SUCCESS TASK_EXECUTED",
                "lockedSections"          to listOf("contract_1", "contract_2"),
                "violationSurface"        to listOf("contract_3")
            )
        )

        val next = Governor(MemoryRepository(events)).nextEvent(events)

        assertNotNull("Governor must emit an event for ASSEMBLY_FAILED", next)
        assertEquals(EventTypes.RECOVERY_CONTRACT, next!!.type)
        assertEquals("contract_3", next.payload["contractId"])
        assertEquals("INCOMPLETE_EXECUTION_SURFACE", next.payload["failureClass"])
        assertTrue(
            "violationField must be non-blank",
            next.payload["violationField"]?.toString()?.isNotBlank() == true
        )
        assertTrue(
            "artifactReference must be non-blank",
            next.payload["artifactReference"]?.toString()?.isNotBlank() == true
        )
    }

    @Test
    fun `Governor RECOVERY_CONTRACT is followed by DELTA_CONTRACT_CREATED`() {
        val recoveryEvent = Event(
            EventTypes.RECOVERY_CONTRACT,
            mapOf(
                "contractId"       to "contract_3",
                "failureClass"     to "INCOMPLETE_EXECUTION_SURFACE",
                "violationField"   to "no SUCCESS TASK_EXECUTED",
                "artifactReference" to "assembly_failed_contract_3",
                "report_reference" to "rrid-mix-001"
            )
        )
        val events = mixedValidityLedger() + recoveryEvent
        val next   = Governor(MemoryRepository(events)).nextEvent(events)

        assertNotNull("Governor must emit DELTA_CONTRACT_CREATED", next)
        assertEquals(EventTypes.DELTA_CONTRACT_CREATED, next!!.type)
    }

    // ── 3. Re-entry guard ────────────────────────────────────────────────────

    @Test
    fun `re-entry blocked when contractSetId mismatches after ASSEMBLY_FAILED`() {
        val mismatchFailureEvent = Event(
            EventTypes.ASSEMBLY_FAILED,
            mapOf(
                "report_reference"        to "rrid-mix-001",
                "contractSetId"           to "DIFFERENT_CSET",  // mismatch
                "failureReasonContractId" to "contract_3",
                "failureType"             to "INCOMPLETE_EXECUTION_SURFACE",
                "violatedInvariant"       to "contract_3 has no SUCCESS TASK_EXECUTED",
                "lockedSections"          to emptyList<String>(),
                "violationSurface"        to listOf("contract_3")
            )
        )
        val repo = MemoryRepository(mixedValidityLedger() + mismatchFailureEvent)

        try {
            AssemblyModule().assemble("proj", repo)
            fail("Expected IllegalStateException for contractSetId mismatch re-entry")
        } catch (e: IllegalStateException) {
            assertTrue("Error must mention RE_ENTRY_BLOCKED", e.message?.contains("RE_ENTRY_BLOCKED") == true)
        }
    }

    @Test
    fun `re-entry blocked when new CONTRACTS_GENERATED appears after ASSEMBLY_FAILED`() {
        val priorFailureEvent = Event(
            EventTypes.ASSEMBLY_FAILED,
            mapOf(
                "report_reference"        to "rrid-mix-001",
                "contractSetId"           to "cset-001",
                "failureReasonContractId" to "contract_3",
                "failureType"             to "INCOMPLETE_EXECUTION_SURFACE",
                "violatedInvariant"       to "contract_3 has no SUCCESS TASK_EXECUTED",
                "lockedSections"          to emptyList<String>(),
                "violationSurface"        to listOf("contract_3")
            )
        )
        // A NEW CONTRACTS_GENERATED injected after ASSEMBLY_FAILED violates the re-entry guard
        val newContractsGen = Event(
            EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "report_reference" to "rrid-mix-001",
                "contractSetId"    to "cset-001",
                "total"            to 3
            )
        )
        val repo = MemoryRepository(mixedValidityLedger() + priorFailureEvent + newContractsGen)

        try {
            AssemblyModule().assemble("proj", repo)
            fail("Expected IllegalStateException for new CONTRACTS_GENERATED after ASSEMBLY_FAILED")
        } catch (e: IllegalStateException) {
            assertTrue("Error must mention RE_ENTRY_BLOCKED", e.message?.contains("RE_ENTRY_BLOCKED") == true)
        }
    }

    @Test
    fun `re-entry allowed when conditions are satisfied after ASSEMBLY_FAILED`() {
        // After a previous ASSEMBLY_FAILED, fix the ledger (add SUCCESS for contract_3)
        // and retry — the re-entry guard must allow it.
        val allowedReentryFailureEvent = Event(
            EventTypes.ASSEMBLY_FAILED,
            mapOf(
                "report_reference"        to "rrid-mix-001",
                "contractSetId"           to "cset-001",
                "failureReasonContractId" to "contract_3",
                "failureType"             to "INCOMPLETE_EXECUTION_SURFACE",
                "violatedInvariant"       to "contract_3 has no SUCCESS TASK_EXECUTED",
                "lockedSections"          to listOf("contract_1", "contract_2"),
                "violationSurface"        to listOf("contract_3")
            )
        )
        val fixEvent = Event(
            EventTypes.TASK_EXECUTED,
            mapOf(
                "contractId" to "contract_3", "executionStatus" to "SUCCESS",
                "artifactReference" to "artifact-c3-retry",
                "report_reference"  to "rrid-mix-001"
            )
        )
        val repo = MemoryRepository(mixedValidityLedger() + allowedReentryFailureEvent + fixEvent)

        val result = AssemblyModule().assemble("proj", repo)
        assertTrue(
            "Re-entry with fixed ledger must produce Assembled",
            result is AssemblyExecutionResult.Assembled
        )
    }

    // ── 4. Trace completeness enforcement ────────────────────────────────────

    @Test
    fun `trace incomplete — SUCCESS TASK_EXECUTED with blank artifactReference triggers ASSEMBLY_FAILED`() {
        val ledger = listOf(
            Event(EventTypes.CONTRACTS_GENERATED,
                mapOf("report_reference" to "rrid-trace-001", "contractSetId" to "cset-t",
                      "total" to 1)),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "c1", "position" to 1,
                      "report_reference" to "rrid-trace-001")),
            // artifactReference is blank → trace completeness violation
            Event(EventTypes.TASK_EXECUTED,
                mapOf("contractId" to "c1", "executionStatus" to "SUCCESS",
                      "artifactReference" to "", "report_reference" to "rrid-trace-001")),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("total" to 1))
        )
        val repo   = MemoryRepository(ledger)
        val result = AssemblyModule().assemble("proj", repo)

        assertTrue("Expected Failed result", result is AssemblyExecutionResult.Failed)
        val failed = result as AssemblyExecutionResult.Failed
        assertTrue(
            "TRACE_INCOMPLETE must appear in failure reasons",
            failed.failureReasons.any { it.failureType == "TRACE_INCOMPLETE" }
        )
    }

    // ── 5. Fully valid path still succeeds ───────────────────────────────────

    @Test
    fun `all-valid 3-contract ledger produces Assembled result`() {
        val repo   = MemoryRepository(allValidLedger())
        val result = AssemblyModule().assemble("proj", repo)

        assertTrue("Expected Assembled result", result is AssemblyExecutionResult.Assembled)

        val written = repo.loadEvents("proj")
        assertTrue(written.any { it.type == EventTypes.ASSEMBLY_STARTED })
        assertTrue(written.any { it.type == EventTypes.ASSEMBLY_COMPLETED })
        assertFalse(written.any { it.type == EventTypes.ASSEMBLY_FAILED })
    }

    @Test
    fun `traceMap maps contractId to artifactReference in successful assembly`() {
        val repo   = MemoryRepository(allValidLedger())
        val result = AssemblyModule().assemble("proj", repo) as AssemblyExecutionResult.Assembled

        val traceMap = result.finalArtifact.traceMap
        assertEquals("art-1", traceMap["c1"])
        assertEquals("art-2", traceMap["c2"])
        assertEquals("art-3", traceMap["c3"])
    }
}
