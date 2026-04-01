// STS-1 — FULL SYSTEM TRACE SIMULATION
// CONTRACT: AGOII-ALIGN-1 / AGOII-ALIGN-1-PATCH — Execution Authority & ICS Boundary Enforcement
//
// Verifies that the invariant surface enforced by AGOII-ALIGN-1 and AGOII-ALIGN-1-PATCH holds:
//   ✔ RECOVERY_CONTRACT may only be written with source=EXECUTION_AUTHORITY
//   ✔ ICS_STARTED may only be written with source=GOVERNOR
//   ✔ ICS_COMPLETED may only be written with source=ICS_MODULE
//   ✔ Governor emits ICS_STARTED (source=GOVERNOR) after ASSEMBLY_COMPLETED
//   ✔ Governor does NOT emit RECOVERY_CONTRACT for ASSEMBLY_FAILED
//   ✔ IcsModule triggers on ICS_STARTED, emits ICS_COMPLETED (source=ICS_MODULE)
//   ✔ ExecutionAuthority.executeFromLedger auto-emits RECOVERY_CONTRACT on TASK_EXECUTED(FAILURE)
//   ✔ ExecutionAuthority.executeFromLedger auto-executes ICS on ICS_STARTED
//   ✔ ValidationLayer blocks any non-RECOVERY_CONTRACT event after TASK_EXECUTED(FAILURE)

package com.agoii.mobile

import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerValidationException
import com.agoii.mobile.core.ValidationLayer
import com.agoii.mobile.execution.DriverRegistry
import com.agoii.mobile.execution.ExecutionAuthority
import com.agoii.mobile.execution.ExecutionAuthorityExecutionResult
import com.agoii.mobile.governor.Governor
import com.agoii.mobile.ics.IcsExecutionResult
import com.agoii.mobile.ics.IcsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * STS-1: Full System Trace Simulation Test
 *
 * Validates the invariant surface defined in AGOII-ALIGN-1 and AGOII-ALIGN-1-PATCH:
 *  - Single Write Authority enforced (RECOVERY_CONTRACT, ICS_STARTED, ICS_COMPLETED)
 *  - Recovery path fully owned by ExecutionAuthority (source=EXECUTION_AUTHORITY)
 *  - Governor remains a pure state machine (emits ICS_STARTED, not RECOVERY_CONTRACT)
 *  - ICS is contract-driven (no bypass of ICS_STARTED / ICS_COMPLETED lifecycle)
 *  - ExecutionAuthority.executeFromLedger auto-emits RECOVERY_CONTRACT on TASK_EXECUTED(FAILURE)
 *  - ExecutionAuthority.executeFromLedger auto-executes ICS on ICS_STARTED
 *  - ValidationLayer Invariant 7: only RECOVERY_CONTRACT may follow TASK_EXECUTED(FAILURE)
 *
 * All tests run on the JVM — no Android framework or network access required.
 */
class FullSystemTraceSimulationTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * In-memory [EventRepository] for unit tests.
     * Sequence numbers are automatically assigned on append.
     */
    private class InMemoryEventRepository(initial: List<Event> = emptyList()) : EventRepository {
        private val ledger = initial.toMutableList()
        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            ledger.add(Event(type = type, payload = payload, sequenceNumber = ledger.size.toLong()))
        }
        override fun loadEvents(projectId: String): List<Event> = ledger.toList()
    }

    /**
     * Builds the minimal valid ledger sequence that ends with [EventTypes.TASK_EXECUTED]
     * (FAILURE) for a single-contract project. Used as context for RECOVERY_CONTRACT tests.
     */
    private fun ledgerEndingWithTaskExecutedFailure(projectId: String): List<Event> = listOf(
        Event(
            type           = EventTypes.INTENT_SUBMITTED,
            payload        = mapOf("objective" to "test"),
            sequenceNumber = 0L
        ),
        Event(
            type           = EventTypes.CONTRACTS_GENERATED,
            payload        = mapOf(
                "intentId"         to "intent-1",
                "contractSetId"    to "cs-1",
                "total"            to 1,
                "contracts"        to listOf(mapOf("contractId" to "c-1", "position" to 1)),
                "report_reference" to "rr-1"
            ),
            sequenceNumber = 1L
        ),
        Event(
            type           = EventTypes.CONTRACTS_READY,
            payload        = emptyMap(),
            sequenceNumber = 2L
        ),
        Event(
            type           = EventTypes.CONTRACT_STARTED,
            payload        = mapOf("position" to 1, "total" to 1, "contract_id" to "c-1"),
            sequenceNumber = 3L
        ),
        Event(
            type           = EventTypes.TASK_ASSIGNED,
            payload        = mapOf(
                "taskId"           to "task-1",
                "contractId"       to "c-1",
                "position"         to 1,
                "total"            to 1,
                "report_reference" to "rr-1",
                "requirements"     to emptyList<Any>(),
                "constraints"      to emptyList<Any>()
            ),
            sequenceNumber = 4L
        ),
        Event(
            type           = EventTypes.TASK_STARTED,
            payload        = mapOf("taskId" to "task-1", "position" to 1, "total" to 1),
            sequenceNumber = 5L
        ),
        Event(
            type           = EventTypes.TASK_EXECUTED,
            payload        = mapOf(
                "taskId"            to "task-1",
                "contractId"        to "c-1",
                "contractorId"      to "contractor-1",
                "artifactReference" to "artifact-1",
                "executionStatus"   to "FAILURE",
                "validationStatus"  to "FAILED",
                "validationReasons" to emptyList<Any>(),
                "report_reference"  to "rr-1",
                "position"          to 1,
                "total"             to 1
            ),
            sequenceNumber = 6L
        )
    )

    /**
     * Builds the minimal valid ledger sequence ending with [EventTypes.ASSEMBLY_COMPLETED].
     * Used as context for ICS_STARTED / ICS_COMPLETED tests.
     */
    private fun ledgerEndingWithAssemblyCompleted(): List<Event> = listOf(
        Event(
            type           = EventTypes.INTENT_SUBMITTED,
            payload        = mapOf("objective" to "ics-test"),
            sequenceNumber = 0L
        ),
        Event(
            type           = EventTypes.CONTRACTS_GENERATED,
            payload        = mapOf(
                "intentId"         to "intent-ics",
                "contractSetId"    to "cs-ics",
                "total"            to 1,
                "contracts"        to listOf(mapOf("contractId" to "c-ics-1", "position" to 1)),
                "report_reference" to "rr-ics"
            ),
            sequenceNumber = 1L
        ),
        Event(
            type           = EventTypes.CONTRACTS_READY,
            payload        = emptyMap(),
            sequenceNumber = 2L
        ),
        Event(
            type           = EventTypes.CONTRACT_STARTED,
            payload        = mapOf("position" to 1, "total" to 1, "contract_id" to "c-ics-1"),
            sequenceNumber = 3L
        ),
        Event(
            type           = EventTypes.TASK_ASSIGNED,
            payload        = mapOf(
                "taskId"           to "task-ics-1",
                "contractId"       to "c-ics-1",
                "position"         to 1,
                "total"            to 1,
                "report_reference" to "rr-ics",
                "requirements"     to emptyList<Any>(),
                "constraints"      to emptyList<Any>()
            ),
            sequenceNumber = 4L
        ),
        Event(
            type           = EventTypes.TASK_STARTED,
            payload        = mapOf("taskId" to "task-ics-1", "position" to 1, "total" to 1),
            sequenceNumber = 5L
        ),
        Event(
            type           = EventTypes.TASK_EXECUTED,
            payload        = mapOf(
                "taskId"            to "task-ics-1",
                "contractId"        to "c-ics-1",
                "contractorId"      to "contractor-1",
                "artifactReference" to "artifact-ics-1",
                "executionStatus"   to "SUCCESS",
                "validationStatus"  to "VALIDATED",
                "validationReasons" to emptyList<Any>(),
                "report_reference"  to "rr-ics",
                "position"          to 1,
                "total"             to 1
            ),
            sequenceNumber = 6L
        ),
        Event(
            type           = EventTypes.TASK_COMPLETED,
            payload        = mapOf("taskId" to "task-ics-1", "position" to 1, "total" to 1),
            sequenceNumber = 7L
        ),
        Event(
            type           = EventTypes.CONTRACT_COMPLETED,
            payload        = mapOf(
                "position"         to 1,
                "total"            to 1,
                "contractId"       to "c-ics-1",
                "report_reference" to "rr-ics"
            ),
            sequenceNumber = 8L
        ),
        Event(
            type           = EventTypes.EXECUTION_COMPLETED,
            payload        = mapOf("total" to 1),
            sequenceNumber = 9L
        ),
        Event(
            type           = EventTypes.ASSEMBLY_STARTED,
            payload        = mapOf(
                "report_reference" to "rr-ics",
                "contractSetId"    to "cs-ics",
                "totalContracts"   to 1
            ),
            sequenceNumber = 10L
        ),
        Event(
            type           = EventTypes.ASSEMBLY_COMPLETED,
            payload        = mapOf(
                "report_reference"       to "rr-ics",
                "contractSetId"          to "cs-ics",
                "totalContracts"         to 1,
                "finalArtifactReference" to "final-artifact-ics",
                "taskId"                 to "assembly-task-ics",
                "assemblyId"             to "assembly-ics-1",
                "traceMap"               to emptyMap<String, String>()
            ),
            sequenceNumber = 11L
        )
    )

    // ── RULE 1 — RECOVERY ORIGIN LOCK ────────────────────────────────────────

    @Test
    fun `ValidationLayer blocks RECOVERY_CONTRACT without source field`() {
        val prior   = ledgerEndingWithTaskExecutedFailure("proj-rcf")
        val layer   = ValidationLayer()
        try {
            layer.validate(
                projectId      = "proj-rcf",
                type           = EventTypes.RECOVERY_CONTRACT,
                payload        = mapOf(
                    "contractId"          to "c-1",
                    "report_reference"    to "rr-1",
                    "failureClass"        to "LOGICAL_FAILURE",
                    "violationField"      to "executionStatus",
                    "correctionDirective" to "DELTA_REPAIR_REQUIRED",
                    "successCondition"    to "VALIDATION_PASS",
                    "artifactReference"   to "artifact-1",
                    "irs_violation_type"  to "EXECUTION_FAILURE",
                    "lockedSections"      to emptyList<Any>()
                    // source deliberately omitted
                ),
                currentEvents  = prior
            )
            fail("Expected LedgerValidationException — source=EXECUTION_AUTHORITY is required")
        } catch (e: LedgerValidationException) {
            assertTrue(
                "Exception message should reference EXECUTION_AUTHORITY",
                e.message?.contains("EXECUTION_AUTHORITY") == true
            )
        }
    }

    @Test
    fun `ValidationLayer blocks RECOVERY_CONTRACT with wrong source`() {
        val prior   = ledgerEndingWithTaskExecutedFailure("proj-rcf-wrong")
        val layer   = ValidationLayer()
        try {
            layer.validate(
                projectId      = "proj-rcf-wrong",
                type           = EventTypes.RECOVERY_CONTRACT,
                payload        = mapOf(
                    "contractId"          to "c-1",
                    "report_reference"    to "rr-1",
                    "failureClass"        to "LOGICAL_FAILURE",
                    "violationField"      to "executionStatus",
                    "correctionDirective" to "DELTA_REPAIR_REQUIRED",
                    "successCondition"    to "VALIDATION_PASS",
                    "artifactReference"   to "artifact-1",
                    "irs_violation_type"  to "EXECUTION_FAILURE",
                    "lockedSections"      to emptyList<Any>(),
                    "source"              to "GOVERNOR"   // wrong source
                ),
                currentEvents  = prior
            )
            fail("Expected LedgerValidationException — source must be EXECUTION_AUTHORITY")
        } catch (e: LedgerValidationException) {
            assertTrue(
                "Exception message should reference EXECUTION_AUTHORITY",
                e.message?.contains("EXECUTION_AUTHORITY") == true
            )
        }
    }

    @Test
    fun `ValidationLayer accepts RECOVERY_CONTRACT with source=EXECUTION_AUTHORITY`() {
        val prior   = ledgerEndingWithTaskExecutedFailure("proj-rcf-ok")
        val layer   = ValidationLayer()
        // Should NOT throw
        layer.validate(
            projectId      = "proj-rcf-ok",
            type           = EventTypes.RECOVERY_CONTRACT,
            payload        = mapOf(
                "contractId"          to "c-1",
                "report_reference"    to "rr-1",
                "failureClass"        to "LOGICAL_FAILURE",
                "violationField"      to "executionStatus",
                "correctionDirective" to "DELTA_REPAIR_REQUIRED",
                "successCondition"    to "VALIDATION_PASS",
                "artifactReference"   to "artifact-1",
                "irs_violation_type"  to "EXECUTION_FAILURE",
                "lockedSections"      to emptyList<Any>(),
                "source"              to "EXECUTION_AUTHORITY"
            ),
            currentEvents  = prior
        )
    }

    // ── RULE 3 — ICS ENTRY LOCK ───────────────────────────────────────────────

    @Test
    fun `ValidationLayer blocks ICS_STARTED without source field`() {
        val prior   = ledgerEndingWithAssemblyCompleted()
        val layer   = ValidationLayer()
        try {
            layer.validate(
                projectId      = "proj-ics-ns",
                type           = EventTypes.ICS_STARTED,
                payload        = mapOf(
                    "report_reference"       to "rr-ics",
                    "finalArtifactReference" to "final-artifact-ics",
                    "taskId"                 to "ICS::rr-ics"
                    // source deliberately omitted
                ),
                currentEvents  = prior
            )
            fail("Expected LedgerValidationException — source=GOVERNOR is required")
        } catch (e: LedgerValidationException) {
            assertTrue(
                "Exception message should reference GOVERNOR",
                e.message?.contains("GOVERNOR") == true
            )
        }
    }

    @Test
    fun `ValidationLayer blocks ICS_STARTED with wrong source`() {
        val prior   = ledgerEndingWithAssemblyCompleted()
        val layer   = ValidationLayer()
        try {
            layer.validate(
                projectId      = "proj-ics-ws",
                type           = EventTypes.ICS_STARTED,
                payload        = mapOf(
                    "report_reference"       to "rr-ics",
                    "finalArtifactReference" to "final-artifact-ics",
                    "taskId"                 to "ICS::rr-ics",
                    "source"                 to "ICS_MODULE"  // wrong source
                ),
                currentEvents  = prior
            )
            fail("Expected LedgerValidationException — source must be GOVERNOR")
        } catch (e: LedgerValidationException) {
            assertTrue(
                "Exception message should reference GOVERNOR",
                e.message?.contains("GOVERNOR") == true
            )
        }
    }

    @Test
    fun `ValidationLayer accepts ICS_STARTED with source=GOVERNOR`() {
        val prior   = ledgerEndingWithAssemblyCompleted()
        val layer   = ValidationLayer()
        // Should NOT throw
        layer.validate(
            projectId      = "proj-ics-ok",
            type           = EventTypes.ICS_STARTED,
            payload        = mapOf(
                "report_reference"       to "rr-ics",
                "finalArtifactReference" to "final-artifact-ics",
                "taskId"                 to "ICS::rr-ics",
                "source"                 to "GOVERNOR"
            ),
            currentEvents  = prior
        )
    }

    // ── RULE 5 — MODULE OWNERSHIP (ICS_COMPLETED) ────────────────────────────

    @Test
    fun `ValidationLayer blocks ICS_COMPLETED without source field`() {
        val icsStartedEvent = Event(
            type           = EventTypes.ICS_STARTED,
            payload        = mapOf(
                "report_reference"       to "rr-ics",
                "finalArtifactReference" to "final-artifact-ics",
                "taskId"                 to "ICS::rr-ics",
                "source"                 to "GOVERNOR"
            ),
            sequenceNumber = 12L
        )
        val prior = ledgerEndingWithAssemblyCompleted() + icsStartedEvent
        val layer = ValidationLayer()
        try {
            layer.validate(
                projectId      = "proj-icsc-ns",
                type           = EventTypes.ICS_COMPLETED,
                payload        = mapOf(
                    "report_reference"   to "rr-ics",
                    "taskId"             to "ICS::rr-ics",
                    "icsOutputReference" to "ics:rr-ics"
                    // source deliberately omitted
                ),
                currentEvents  = prior
            )
            fail("Expected LedgerValidationException — source=ICS_MODULE is required")
        } catch (e: LedgerValidationException) {
            assertTrue(
                "Exception message should reference ICS_MODULE",
                e.message?.contains("ICS_MODULE") == true
            )
        }
    }

    @Test
    fun `ValidationLayer accepts ICS_COMPLETED with source=ICS_MODULE`() {
        val icsStartedEvent = Event(
            type           = EventTypes.ICS_STARTED,
            payload        = mapOf(
                "report_reference"       to "rr-ics",
                "finalArtifactReference" to "final-artifact-ics",
                "taskId"                 to "ICS::rr-ics",
                "source"                 to "GOVERNOR"
            ),
            sequenceNumber = 12L
        )
        val prior = ledgerEndingWithAssemblyCompleted() + icsStartedEvent
        val layer = ValidationLayer()
        // Should NOT throw
        layer.validate(
            projectId      = "proj-icsc-ok",
            type           = EventTypes.ICS_COMPLETED,
            payload        = mapOf(
                "report_reference"   to "rr-ics",
                "taskId"             to "ICS::rr-ics",
                "icsOutputReference" to "ics:rr-ics",
                "source"             to "ICS_MODULE"
            ),
            currentEvents  = prior
        )
    }

    // ── RULE 4 — GOVERNOR PURITY ──────────────────────────────────────────────

    @Test
    fun `Governor does NOT emit RECOVERY_CONTRACT for ASSEMBLY_FAILED`() {
        val store = InMemoryEventRepository(
            listOf(
                Event(
                    type           = EventTypes.ASSEMBLY_FAILED,
                    payload        = mapOf(
                        "report_reference" to "rr-af",
                        "contractSetId"    to "cs-af",
                        "failureReasons"   to listOf(
                            mapOf(
                                "contractId"       to "c-af-1",
                                "failureType"      to "STRUCTURAL",
                                "violatedInvariant" to "typeInventory"
                            )
                        ),
                        "lockedSections"   to emptyList<String>(),
                        "violationSurface" to "typeInventory"
                    ),
                    sequenceNumber = 0L
                )
            )
        )
        val events = store.loadEvents("proj-af")
        val nextEvts = Governor(store).nextEvents(events)
        assertTrue(
            "Governor must NOT emit any RECOVERY_CONTRACT events (AGOII-ALIGN-1 RULE 4)",
            nextEvts.none { it.type == EventTypes.RECOVERY_CONTRACT }
        )
    }

    // ── RULE 3 — GOVERNOR EMITS ICS_STARTED after ASSEMBLY_COMPLETED ─────────

    @Test
    fun `Governor emits ICS_STARTED with source=GOVERNOR after ASSEMBLY_COMPLETED`() {
        val events = ledgerEndingWithAssemblyCompleted()
        val store  = InMemoryEventRepository(events)
        val governor = Governor(store)

        val nextEvts = governor.nextEvents(events)

        assertEquals("Governor should emit exactly one event after ASSEMBLY_COMPLETED", 1, nextEvts.size)
        val icsStarted = nextEvts.first()
        assertEquals(EventTypes.ICS_STARTED, icsStarted.type)
        assertEquals(
            "ICS_STARTED source must be GOVERNOR",
            "GOVERNOR",
            icsStarted.payload["source"]
        )
        assertEquals("rr-ics", icsStarted.payload["report_reference"])
        assertEquals("final-artifact-ics", icsStarted.payload["finalArtifactReference"])
        assertNotNull("ICS_STARTED taskId must be present", icsStarted.payload["taskId"])
    }

    @Test
    fun `Governor runGovernor appends ICS_STARTED with source=GOVERNOR`() {
        val events = ledgerEndingWithAssemblyCompleted()
        val store  = InMemoryEventRepository(events)
        val result = Governor(store).runGovernor("proj-ics")

        assertEquals(Governor.GovernorResult.ADVANCED, result)
        val appended = store.loadEvents("proj-ics")
        val icsStarted = appended.lastOrNull()
        assertNotNull(icsStarted)
        assertEquals(EventTypes.ICS_STARTED, icsStarted!!.type)
        assertEquals("GOVERNOR", icsStarted.payload["source"])
    }

    // ── RULE 5 — ICS_MODULE EMITS ICS_COMPLETED ───────────────────────────────

    @Test
    fun `IcsModule triggers on ICS_STARTED and emits ICS_COMPLETED with source=ICS_MODULE`() {
        // Build ledger: full trace ending with ICS_STARTED (written by Governor)
        val baseEvents = ledgerEndingWithAssemblyCompleted()
        val store = InMemoryEventRepository(baseEvents)
        val governor = Governor(store)

        // Governor advances: ASSEMBLY_COMPLETED → ICS_STARTED
        val result = governor.runGovernor("proj-ics-module")
        assertEquals(Governor.GovernorResult.ADVANCED, result)

        // IcsModule processes ICS_STARTED → emits ICS_COMPLETED
        val icsResult = IcsModule().process("proj-ics-module", store)
        assertTrue(
            "IcsModule should succeed after ICS_STARTED",
            icsResult is IcsExecutionResult.Processed
        )

        // Verify ICS_COMPLETED is in the ledger with source=ICS_MODULE
        val allEvents = store.loadEvents("proj-ics-module")
        val icsCompleted = allEvents.lastOrNull()
        assertNotNull(icsCompleted)
        assertEquals(EventTypes.ICS_COMPLETED, icsCompleted!!.type)
        assertEquals(
            "ICS_COMPLETED source must be ICS_MODULE",
            "ICS_MODULE",
            icsCompleted.payload["source"]
        )
        assertEquals("rr-ics", icsCompleted.payload["report_reference"])
        assertNotNull("ICS_COMPLETED must carry icsOutputReference", icsCompleted.payload["icsOutputReference"])
    }

    @Test
    fun `IcsModule returns NotTriggered when last event is ASSEMBLY_COMPLETED (no bypass)`() {
        // IcsModule must NOT trigger on ASSEMBLY_COMPLETED — only Governor may emit ICS_STARTED first
        val events = ledgerEndingWithAssemblyCompleted()
        val store  = InMemoryEventRepository(events)
        val result = IcsModule().process("proj-bypass-check", store)
        assertTrue(
            "IcsModule must return NotTriggered when last event is ASSEMBLY_COMPLETED (bypass not allowed)",
            result is IcsExecutionResult.NotTriggered
        )
    }

    // ── FULL TRACE — STRUCTURAL VALIDITY ─────────────────────────────────────

    @Test
    fun `Full ICS trace ASSEMBLY_COMPLETED → ICS_STARTED → ICS_COMPLETED is structurally valid`() {
        val baseEvents = ledgerEndingWithAssemblyCompleted()
        val store = InMemoryEventRepository(baseEvents)

        // Step 1: Governor emits ICS_STARTED
        assertEquals(Governor.GovernorResult.ADVANCED, Governor(store).runGovernor("proj-full"))

        // Step 2: IcsModule emits ICS_COMPLETED
        val icsResult = IcsModule().process("proj-full", store)
        assertFalse("ICS execution must not be blocked", icsResult is IcsExecutionResult.Blocked)
        assertTrue("ICS execution must produce output", icsResult is IcsExecutionResult.Processed)

        val allEvents = store.loadEvents("proj-full")
        val types = allEvents.map { it.type }
        assertTrue("Trace must contain ICS_STARTED", EventTypes.ICS_STARTED in types)
        assertTrue("Trace must contain ICS_COMPLETED", EventTypes.ICS_COMPLETED in types)

        val icsStartedIndex   = types.indexOf(EventTypes.ICS_STARTED)
        val icsCompletedIndex = types.indexOf(EventTypes.ICS_COMPLETED)
        assertTrue(
            "ICS_STARTED must precede ICS_COMPLETED in the trace",
            icsStartedIndex < icsCompletedIndex
        )

        val icsStarted   = allEvents[icsStartedIndex]
        val icsCompleted = allEvents[icsCompletedIndex]
        assertEquals("GOVERNOR", icsStarted.payload["source"])
        assertEquals("ICS_MODULE", icsCompleted.payload["source"])
    }

    // ── AGOII-ALIGN-1-PATCH: RECOVERY TRIGGER LOCK ───────────────────────────

    @Test
    fun `executeFromLedger auto-emits RECOVERY_CONTRACT when TASK_EXECUTED(FAILURE) is last event`() {
        val prior = ledgerEndingWithTaskExecutedFailure("proj-auto-rcf")
        val store = InMemoryEventRepository(prior)
        val ea    = ExecutionAuthority(ContractorRegistry(), DriverRegistry())

        val result = ea.executeFromLedger("proj-auto-rcf", store)

        assertTrue(
            "executeFromLedger must return RecoveryEmitted for TASK_EXECUTED(FAILURE)",
            result is ExecutionAuthorityExecutionResult.RecoveryEmitted
        )

        val allEvents = store.loadEvents("proj-auto-rcf")
        val lastEvent = allEvents.last()
        assertEquals(
            "Last event must be RECOVERY_CONTRACT after auto-recovery",
            EventTypes.RECOVERY_CONTRACT,
            lastEvent.type
        )
        assertEquals(
            "RECOVERY_CONTRACT must carry source=EXECUTION_AUTHORITY",
            "EXECUTION_AUTHORITY",
            lastEvent.payload["source"]
        )
    }

    @Test
    fun `executeFromLedger returns NotTriggered for TASK_EXECUTED(SUCCESS)`() {
        // Build a ledger ending with TASK_EXECUTED(SUCCESS)
        val priorWithSuccess = ledgerEndingWithTaskExecutedFailure("proj-te-success").dropLast(1) +
            Event(
                type           = EventTypes.TASK_EXECUTED,
                payload        = mapOf(
                    "taskId"            to "task-1",
                    "contractId"        to "c-1",
                    "contractorId"      to "contractor-1",
                    "artifactReference" to "artifact-1",
                    "executionStatus"   to "SUCCESS",
                    "validationStatus"  to "VALIDATED",
                    "validationReasons" to emptyList<Any>(),
                    "report_reference"  to "rr-1",
                    "position"          to 1,
                    "total"             to 1
                ),
                sequenceNumber = 6L
            )
        val store  = InMemoryEventRepository(priorWithSuccess)
        val ea     = ExecutionAuthority(ContractorRegistry(), DriverRegistry())
        val result = ea.executeFromLedger("proj-te-success", store)

        assertTrue(
            "executeFromLedger must return NotTriggered for TASK_EXECUTED(SUCCESS) — Governor handles this",
            result is ExecutionAuthorityExecutionResult.NotTriggered
        )
        assertEquals(
            "No new events must be appended for TASK_EXECUTED(SUCCESS)",
            priorWithSuccess.size,
            store.loadEvents("proj-te-success").size
        )
    }

    // ── AGOII-ALIGN-1-PATCH: ICS EXECUTION COUPLING ──────────────────────────

    @Test
    fun `executeFromLedger auto-executes ICS and returns IcsCompleted when ICS_STARTED is last event`() {
        val baseEvents = ledgerEndingWithAssemblyCompleted()
        val store      = InMemoryEventRepository(baseEvents)
        val governor   = Governor(store)

        // Governor writes ICS_STARTED
        assertEquals(Governor.GovernorResult.ADVANCED, governor.runGovernor("proj-ics-ea"))

        // Now last event is ICS_STARTED → executeFromLedger should auto-run ICS
        val ea     = ExecutionAuthority(ContractorRegistry(), DriverRegistry())
        val result = ea.executeFromLedger("proj-ics-ea", store)

        assertTrue(
            "executeFromLedger must return IcsCompleted for ICS_STARTED trigger",
            result is ExecutionAuthorityExecutionResult.IcsCompleted
        )

        val allEvents = store.loadEvents("proj-ics-ea")
        val lastEvent = allEvents.last()
        assertEquals(
            "Last event must be ICS_COMPLETED after auto-ICS execution",
            EventTypes.ICS_COMPLETED,
            lastEvent.type
        )
        assertEquals(
            "ICS_COMPLETED must carry source=ICS_MODULE",
            "ICS_MODULE",
            lastEvent.payload["source"]
        )
    }

    @Test
    fun `executeFromLedger throws if IcsModule cannot complete after ICS_STARTED`() {
        // Build a ledger with ICS_STARTED but missing ASSEMBLY_COMPLETED
        // so IcsModule cannot reconstruct the FinalArtifact and returns Blocked.
        val minimalIcsStarted = listOf(
            Event(
                type           = EventTypes.INTENT_SUBMITTED,
                payload        = mapOf("objective" to "minimal"),
                sequenceNumber = 0L
            ),
            Event(
                type           = EventTypes.ICS_STARTED,
                payload        = mapOf(
                    "report_reference"       to "rr-minimal",
                    "finalArtifactReference" to "far-minimal",
                    "taskId"                 to "ICS::rr-minimal",
                    "source"                 to "GOVERNOR"
                ),
                sequenceNumber = 1L
            )
        )
        val store = InMemoryEventRepository(minimalIcsStarted)
        val ea    = ExecutionAuthority(ContractorRegistry(), DriverRegistry())

        try {
            ea.executeFromLedger("proj-ics-blocked", store)
            fail("Expected IllegalStateException when ICS cannot complete")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message must reference AGOII-ALIGN-1-PATCH",
                e.message?.contains("AGOII-ALIGN-1-PATCH") == true ||
                e.message?.contains("ICS") == true
            )
        }
    }

    // ── AGOII-ALIGN-1-PATCH: VALIDATION LAYER INVARIANT 7 ────────────────────

    @Test
    fun `ValidationLayer blocks TASK_FAILED after TASK_EXECUTED(FAILURE) - Invariant 7`() {
        val prior = ledgerEndingWithTaskExecutedFailure("proj-inv7-fail")
        val layer = ValidationLayer()
        try {
            layer.validate(
                projectId     = "proj-inv7-fail",
                type          = EventTypes.TASK_FAILED,
                payload       = mapOf("taskId" to "task-1", "position" to 1, "total" to 1),
                currentEvents = prior
            )
            fail("Expected LedgerValidationException — Invariant 7 must block TASK_FAILED after TASK_EXECUTED(FAILURE)")
        } catch (e: LedgerValidationException) {
            assertTrue(
                "Exception must reference Invariant 7 or RECOVERY_CONTRACT",
                e.message?.contains("Invariant 7") == true || e.message?.contains("RECOVERY_CONTRACT") == true
            )
        }
    }

    @Test
    fun `ValidationLayer blocks TASK_COMPLETED after TASK_EXECUTED(FAILURE) - Invariant 7`() {
        val prior = ledgerEndingWithTaskExecutedFailure("proj-inv7-tc")
        val layer = ValidationLayer()
        try {
            layer.validate(
                projectId     = "proj-inv7-tc",
                type          = EventTypes.TASK_COMPLETED,
                payload       = mapOf("taskId" to "task-1", "position" to 1, "total" to 1),
                currentEvents = prior
            )
            fail("Expected LedgerValidationException — Invariant 7 must block TASK_COMPLETED after TASK_EXECUTED(FAILURE)")
        } catch (e: LedgerValidationException) {
            assertTrue(
                "Exception must reference Invariant 7 or RECOVERY_CONTRACT",
                e.message?.contains("Invariant 7") == true || e.message?.contains("RECOVERY_CONTRACT") == true
            )
        }
    }

    @Test
    fun `ValidationLayer allows RECOVERY_CONTRACT after TASK_EXECUTED(FAILURE) - Invariant 7`() {
        val prior = ledgerEndingWithTaskExecutedFailure("proj-inv7-ok")
        val layer = ValidationLayer()
        // Should NOT throw — RECOVERY_CONTRACT is the only permitted event after TASK_EXECUTED(FAILURE)
        layer.validate(
            projectId     = "proj-inv7-ok",
            type          = EventTypes.RECOVERY_CONTRACT,
            payload       = mapOf(
                "contractId"          to "RCF_c-1_EXECUTION",
                "report_reference"    to "rr-1",
                "failureClass"        to "LOGICAL",
                "violationField"      to "TASK_EXECUTED_FAILURE",
                "correctionDirective" to "Resolve LOGICAL for task task-1",
                "successCondition"    to "TASK_EXECUTED written with executionStatus=SUCCESS",
                "artifactReference"   to "artifact-1",
                "source"              to "EXECUTION_AUTHORITY"
            ),
            currentEvents = prior
        )
    }

    @Test
    fun `Full auto-recovery trace TASK_EXECUTED(FAILURE) → RECOVERY_CONTRACT via executeFromLedger`() {
        val prior = ledgerEndingWithTaskExecutedFailure("proj-auto-trace")
        val store = InMemoryEventRepository(prior)
        val ea    = ExecutionAuthority(ContractorRegistry(), DriverRegistry())

        // ExecutionAuthority auto-emits RECOVERY_CONTRACT
        val result = ea.executeFromLedger("proj-auto-trace", store)

        assertTrue(result is ExecutionAuthorityExecutionResult.RecoveryEmitted)

        val allEvents = store.loadEvents("proj-auto-trace")
        val types     = allEvents.map { it.type }
        assertTrue("Trace must contain RECOVERY_CONTRACT", EventTypes.RECOVERY_CONTRACT in types)

        val teIndex  = types.lastIndexOf(EventTypes.TASK_EXECUTED)
        val rcfIndex = types.indexOf(EventTypes.RECOVERY_CONTRACT)
        assertTrue(
            "RECOVERY_CONTRACT must immediately follow TASK_EXECUTED(FAILURE)",
            rcfIndex == teIndex + 1
        )

        val recoveryEvent = allEvents[rcfIndex]
        assertEquals("EXECUTION_AUTHORITY", recoveryEvent.payload["source"])
        assertNotNull("RECOVERY_CONTRACT must carry contractId", recoveryEvent.payload["contractId"])
        assertNotNull("RECOVERY_CONTRACT must carry report_reference", recoveryEvent.payload["report_reference"])
    }
}
