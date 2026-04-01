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

    /** Builds the new-format ASSEMBLY_FAILED payload for a single-failure scenario. */
    private fun singleFailurePayload(
        contractId: String = "contract_3",
        failureType: String = "INCOMPLETE_EXECUTION_SURFACE",
        violatedInvariant: String = "$contractId has no SUCCESS TASK_EXECUTED",
        contractSetId: String = "cset-001",
        reportReference: String = "rrid-mix-001",
        lockedSections: List<String> = listOf("contract_1", "contract_2"),
        violationSurface: List<String> = listOf(contractId)
    ): Map<String, Any> = mapOf(
        "report_reference" to reportReference,
        "contractSetId"    to contractSetId,
        "failureReasons"   to listOf(
            mapOf(
                "contractId"        to contractId,
                "failureType"       to failureType,
                "violatedInvariant" to violatedInvariant
            )
        ),
        "lockedSections"   to lockedSections,
        "violationSurface" to violationSurface
    )

    @Test
    fun `ASSEMBLY_FAILED triggers RECOVERY_CONTRACT from Governor`() {
        val events = mixedValidityLedger() + Event(
            EventTypes.ASSEMBLY_FAILED,
            singleFailurePayload()
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
    fun `RECOVERY_CONTRACT payload contains lockedSections from ASSEMBLY_FAILED`() {
        val events = mixedValidityLedger() + Event(
            EventTypes.ASSEMBLY_FAILED,
            singleFailurePayload(lockedSections = listOf("contract_1", "contract_2"))
        )

        val next = Governor(MemoryRepository(events)).nextEvent(events)
        assertNotNull(next)
        assertEquals(EventTypes.RECOVERY_CONTRACT, next!!.type)
        @Suppress("UNCHECKED_CAST")
        val locked = next.payload["lockedSections"] as? List<*>
        assertNotNull("lockedSections must be present in RECOVERY_CONTRACT payload", locked)
        assertTrue("lockedSections must contain contract_1", locked!!.contains("contract_1"))
        assertTrue("lockedSections must contain contract_2", locked.contains("contract_2"))
    }

    @Test
    fun `Governor emits one RECOVERY_CONTRACT per failureReason (multi-failure cycling)`() {
        val assemblyFailedEvent = Event(
            EventTypes.ASSEMBLY_FAILED,
            mapOf(
                "report_reference" to "rrid-multi-001",
                "contractSetId"    to "cset-multi",
                "failureReasons"   to listOf(
                    mapOf("contractId" to "c1", "failureType" to "INCOMPLETE_EXECUTION_SURFACE",
                          "violatedInvariant" to "c1 has no SUCCESS TASK_EXECUTED"),
                    mapOf("contractId" to "c2", "failureType" to "RRID_VIOLATION",
                          "violatedInvariant" to "c2 has wrong RRID")
                ),
                "lockedSections"   to emptyList<String>(),
                "violationSurface" to listOf("c1", "c2")
            )
        )

        // First call: ASSEMBLY_FAILED last, no prior RECOVERY_CONTRACTs → emit for c1
        val events1 = listOf(assemblyFailedEvent)
        val next1   = Governor(MemoryRepository(events1)).nextEvent(events1)
        assertNotNull("First RECOVERY_CONTRACT must be emitted", next1)
        assertEquals(EventTypes.RECOVERY_CONTRACT, next1!!.type)
        assertEquals("c1", next1.payload["contractId"])

        // Second call: c1 already recovered, ASSEMBLY_FAILED still last → emit for c2
        val recoveryForC1 = Event(
            EventTypes.RECOVERY_CONTRACT,
            mapOf("contractId" to "c1", "report_reference" to "rrid-multi-001")
        )
        val events2 = listOf(recoveryForC1, assemblyFailedEvent)
        val next2   = Governor(MemoryRepository(events2)).nextEvent(events2)
        assertNotNull("Second RECOVERY_CONTRACT must be emitted", next2)
        assertEquals(EventTypes.RECOVERY_CONTRACT, next2!!.type)
        assertEquals("c2", next2.payload["contractId"])

        // Third call: both c1 and c2 already recovered → no more pending → return null
        val recoveryForC2 = Event(
            EventTypes.RECOVERY_CONTRACT,
            mapOf("contractId" to "c2", "report_reference" to "rrid-multi-001")
        )
        val events3 = listOf(recoveryForC1, recoveryForC2, assemblyFailedEvent)
        val next3   = Governor(MemoryRepository(events3)).nextEvent(events3)
        assertNull("No more RECOVERY_CONTRACTs when all failures are processed", next3)
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
                "report_reference" to "rrid-mix-001",
                "contractSetId"    to "DIFFERENT_CSET",  // mismatch
                "failureReasons"   to listOf(
                    mapOf("contractId" to "contract_3",
                          "failureType" to "INCOMPLETE_EXECUTION_SURFACE",
                          "violatedInvariant" to "contract_3 has no SUCCESS TASK_EXECUTED")
                ),
                "lockedSections"   to emptyList<String>(),
                "violationSurface" to listOf("contract_3")
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
                "report_reference" to "rrid-mix-001",
                "contractSetId"    to "cset-001",
                "failureReasons"   to listOf(
                    mapOf("contractId" to "contract_3",
                          "failureType" to "INCOMPLETE_EXECUTION_SURFACE",
                          "violatedInvariant" to "contract_3 has no SUCCESS TASK_EXECUTED")
                ),
                "lockedSections"   to emptyList<String>(),
                "violationSurface" to listOf("contract_3")
            )
        )
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
    fun `re-entry blocked when latest CONTRACTS_GENERATED RRID differs from ASSEMBLY_FAILED RRID`() {
        // Scenario: original CONTRACTS_GENERATED has RRID-A, a second CONTRACTS_GENERATED
        // (inserted before ASSEMBLY_FAILED) has RRID-B. ASSEMBLY_FAILED carries RRID-A.
        // The latest CONTRACTS_GENERATED RRID (B) != ASSEMBLY_FAILED RRID (A) → block.
        val secondContractsGenDifferentRrid = Event(
            EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "report_reference" to "rrid-mismatched-002",  // different RRID — simulates RRID drift
                "contractSetId"    to "cset-001",
                "total"            to 3
            )
        )
        val failureEventForOriginalRrid = Event(
            EventTypes.ASSEMBLY_FAILED,
            mapOf(
                "report_reference" to "rrid-mix-001",  // original RRID
                "contractSetId"    to "cset-001",
                "failureReasons"   to listOf(
                    mapOf("contractId" to "contract_3",
                          "failureType" to "INCOMPLETE_EXECUTION_SURFACE",
                          "violatedInvariant" to "contract_3 has no SUCCESS TASK_EXECUTED")
                ),
                "lockedSections"   to emptyList<String>(),
                "violationSurface" to listOf("contract_3")
            )
        )
        // Ledger order: [original CONTRACTS_GENERATED, ..., second CONTRACTS_GENERATED(RRID-B),
        //                ASSEMBLY_FAILED(RRID-A)]
        // failureIndex IS found (ASSEMBLY_FAILED.report_reference == reportReference == RRID-A).
        // newContractsGenAfterFailure = false (second CG is BEFORE ASSEMBLY_FAILED).
        // latestContractsGenRrid = RRID-B != failureRrid = RRID-A → RRID_CONTINUITY_VIOLATION.
        val ledger = mixedValidityLedger()
            .toMutableList()
            .also { it.add(it.size - 1, secondContractsGenDifferentRrid) } // insert before EXECUTION_COMPLETED
            .plus(failureEventForOriginalRrid)

        val repo = MemoryRepository(ledger)

        try {
            AssemblyModule().assemble("proj", repo)
            fail("Expected IllegalStateException for RRID continuity violation")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Error must mention RE_ENTRY_BLOCKED",
                e.message?.contains("RE_ENTRY_BLOCKED") == true
            )
        }
    }

    @Test
    fun `re-entry allowed when conditions are satisfied after ASSEMBLY_FAILED`() {
        val allowedReentryFailureEvent = Event(
            EventTypes.ASSEMBLY_FAILED,
            mapOf(
                "report_reference" to "rrid-mix-001",
                "contractSetId"    to "cset-001",
                "failureReasons"   to listOf(
                    mapOf("contractId" to "contract_3",
                          "failureType" to "INCOMPLETE_EXECUTION_SURFACE",
                          "violatedInvariant" to "contract_3 has no SUCCESS TASK_EXECUTED")
                ),
                "lockedSections"   to listOf("contract_1", "contract_2"),
                "violationSurface" to listOf("contract_3")
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

    @Test
    fun `traceMap is full projection — blank artifactReference included (not filtered)`() {
        // FIX 1: traceMap must include the blank entry so TRACE_INCOMPLETE can be caught.
        // When assembly fails, traceMap would not be returned in a successful result,
        // but we verify via the Failed result that the TRACE_INCOMPLETE was detected.
        val ledger = listOf(
            Event(EventTypes.CONTRACTS_GENERATED,
                mapOf("report_reference" to "rrid-proj-001", "contractSetId" to "cset-proj",
                      "total" to 2)),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "ca", "position" to 1,
                      "report_reference" to "rrid-proj-001")),
            Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("contractId" to "cb", "position" to 2,
                      "report_reference" to "rrid-proj-001")),
            // ca has non-blank artifactReference; cb has blank (TRACE_INCOMPLETE)
            Event(EventTypes.TASK_EXECUTED,
                mapOf("contractId" to "ca", "executionStatus" to "SUCCESS",
                      "artifactReference" to "art-ca", "report_reference" to "rrid-proj-001")),
            Event(EventTypes.TASK_EXECUTED,
                mapOf("contractId" to "cb", "executionStatus" to "SUCCESS",
                      "artifactReference" to "", "report_reference" to "rrid-proj-001")),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("total" to 2))
        )
        val repo   = MemoryRepository(ledger)
        val result = AssemblyModule().assemble("proj", repo)

        assertTrue("Expected Failed result due to blank artifactReference", result is AssemblyExecutionResult.Failed)
        val failed = result as AssemblyExecutionResult.Failed
        // cb must appear in violationSurface (its blank entry was included in traceMap and caught)
        assertTrue(
            "cb must be in violationSurface",
            failed.violationSurface.any { it.contractId == "cb" }
        )
        assertTrue(
            "TRACE_INCOMPLETE for cb must be in failureReasons",
            failed.failureReasons.any { it.contractId == "cb" && it.failureType == "TRACE_INCOMPLETE" }
        )
        // ca must be in lockedSections (non-blank artifactReference — not a trace failure)
        assertTrue(
            "ca must be in lockedSections",
            failed.lockedSections.any { it.contractId == "ca" }
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
