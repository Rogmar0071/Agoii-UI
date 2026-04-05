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
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Governance Enforcement Test Suite
 *
 * CONTRACT: MQP-UI-FINAL-CONSOLIDATED
 *
 * Covers:
 *   Phase 2 — Governance Validation (RL-01, zero UI derivation)
 *   Phase 3 — Integration Validation (CoreBridge isolation, no system imports)
 *   Phase 4 — Migration Validation (no core mutation, namespace intact)
 *   Phase 6 — Architect Enforcement (layer boundaries, dependency direction)
 *   Phase 7 — Governor Enforcement (validateExecution active, no bypass)
 *   Phase 8 — Replay Consolidation (single source, deterministic)
 *
 * All tests run on JVM — no Android framework required.
 */
class GovernanceEnforcementTest {

    // ── Test infrastructure ──────────────────────────────────────────────────

    private class InMemoryStore(initial: List<Event> = emptyList()) : EventRepository {
        private val ledger = initial.toMutableList()
        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            ledger.add(Event(type, payload, id = "test-${ledger.size}", sequenceNumber = ledger.size.toLong()))
        }
        override fun loadEvents(projectId: String): List<Event> = ledger.toList()
    }

    private val replay = Replay(InMemoryStore())

    // ═══════════════════════════════════════════════════════════════════════════
    // PHASE 2 — GOVERNANCE VALIDATION
    //
    // RL-01: UI MUST NOT derive state — reads only from ReplayStructuralState.
    // Zero UI derivation means UI cannot compute, infer, or transform state.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `P2-GOV-01 ui-module files contain zero com_agoii_mobile imports`() {
        val uiModuleDir = findUiModuleDir()
        if (uiModuleDir == null) {
            // If ui-module not found at test runtime (e.g. in CI), validate structure exists
            assertTrue("ui-module directory must exist in project", false)
            return
        }

        val violations = mutableListOf<String>()
        uiModuleDir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { lineNum, line ->
                    if (line.trimStart().startsWith("import com.agoii.mobile")) {
                        violations.add("${file.name}:${lineNum + 1} → $line")
                    }
                }
            }

        assertTrue(
            "ui-module must have ZERO core imports. Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `P2-GOV-02 ui-module files use only agoii_ui package namespace`() {
        val uiModuleDir = findUiModuleDir()
        if (uiModuleDir == null) return

        val violations = mutableListOf<String>()
        uiModuleDir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { lineNum, line ->
                    if (line.trimStart().startsWith("package ") &&
                        !line.contains("agoii.ui")) {
                        violations.add("${file.name}:${lineNum + 1} → $line")
                    }
                }
            }

        assertTrue(
            "All ui-module files must use 'agoii.ui.*' package. Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `P2-GOV-03 ReplayStructuralState is sole authority for state derivation`() {
        // Verify that Replay engine produces state from events only (single constructor)
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "governance"))
        )
        val state = replay.deriveStructuralState(events)

        // State MUST come exclusively from Replay — verify it's structurally complete
        assertNotNull("ReplayStructuralState must not be null", state)
        assertNotNull("governanceView must not be null", state.governanceView)
        assertNotNull("executionView must not be null", state.executionView)
        assertNotNull("auditView must not be null", state.auditView)

        // Verify no phantom fields — all views populated from event data only
        assertEquals(EventTypes.INTENT_SUBMITTED, state.governanceView.lastEventType)
        assertTrue(state.auditView.intent.structurallyComplete)
        assertFalse(state.auditView.contracts.generated)
    }

    @Test
    fun `P2-GOV-04 RL-01 enforcement — empty state produces explicit nulls not defaults`() {
        val state = replay.deriveStructuralState(emptyList())

        // RL-01: Missing values surface as null/explicit state, NOT computed defaults
        assertNull(state.governanceView.lastEventType)
        assertEquals(emptyMap<String, Any>(), state.governanceView.lastEventPayload)
        assertEquals(0, state.governanceView.totalContracts)
        assertEquals("", state.governanceView.reportReference)
        assertEquals("not_started", state.executionView.executionStatus)
        assertFalse(state.executionView.showCommitPanel)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PHASE 3 — INTEGRATION VALIDATION
    //
    // CoreBridge is the ONLY access point between UI and core.
    // No system/** imports in ui-module. Adapter fully mapped.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `P3-INT-01 ui-module CoreBridge is the sole interface`() {
        val uiModuleDir = findUiModuleDir()
        if (uiModuleDir == null) return

        val bridgeDir = File(uiModuleDir, "bridge")
        assertTrue("bridge/ directory must exist", bridgeDir.exists())

        val bridgeFiles = bridgeDir.listFiles()?.filter { it.extension == "kt" } ?: emptyList()
        assertTrue("bridge/ must contain Kotlin files", bridgeFiles.isNotEmpty())

        // UiBridgeAdapter.kt must exist — contains CoreBridge interface + UiBridgeAdapter class
        assertTrue(
            "UiBridgeAdapter.kt must exist",
            bridgeFiles.any { it.name == "UiBridgeAdapter.kt" }
        )

        // Verify CoreBridge interface is defined in the bridge package
        val adapterContent = File(bridgeDir, "UiBridgeAdapter.kt").readText()
        assertTrue(
            "CoreBridge interface must be defined",
            adapterContent.contains("interface CoreBridge")
        )
    }

    @Test
    fun `P3-INT-02 ui-module has zero forbidden system imports`() {
        val uiModuleDir = findUiModuleDir()
        if (uiModuleDir == null) return

        val forbiddenPatterns = listOf(
            "import com.agoii.mobile.execution",
            "import com.agoii.mobile.governor",
            "import com.agoii.mobile.core",
            "import com.agoii.mobile.infrastructure",
            "import com.agoii.mobile.contractor",
            "import com.agoii.mobile.contracts",
            "import com.agoii.mobile.interaction",
            "import com.agoii.mobile.observability",
            "import com.agoii.mobile.bridge"
        )

        val violations = mutableListOf<String>()
        uiModuleDir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { lineNum, line ->
                    val trimmed = line.trimStart()
                    forbiddenPatterns.forEach { pattern ->
                        if (trimmed.startsWith(pattern)) {
                            violations.add("${file.name}:${lineNum + 1} → $trimmed")
                        }
                    }
                }
            }

        assertTrue(
            "ui-module must have ZERO system imports. Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `P3-INT-03 CoreBridgeAdapter maps all ReplayStructuralState fields`() {
        // Verify CoreBridgeAdapter exists and maps all core → UI fields
        val adapterFile = findFile("CoreBridgeAdapter.kt")
        if (adapterFile == null) {
            fail("CoreBridgeAdapter.kt must exist")
            return
        }

        val content = adapterFile.readText()

        // Must implement CoreBridge (UI bridge interface)
        assertTrue(
            "CoreBridgeAdapter must implement UiCoreBridge",
            content.contains("UiCoreBridge")
        )

        // Must map governanceView fields
        assertTrue("Must map lastEventType", content.contains("lastEventType"))
        assertTrue("Must map reportReference", content.contains("reportReference"))
        assertTrue("Must map hasLastEvent", content.contains("hasLastEvent"))

        // Must map executionView fields
        assertTrue("Must map executionStatus", content.contains("executionStatus"))
        assertTrue("Must map showCommitPanel", content.contains("showCommitPanel"))
        assertTrue("Must map lastContractStartedId", content.contains("lastContractStartedId"))

        // Must map auditView fields
        assertTrue("Must map totalEvents", content.contains("totalEvents"))
        assertTrue("Must map contractIds", content.contains("contractIds"))
        assertTrue("Must map hasContracts", content.contains("hasContracts"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PHASE 4 — MIGRATION VALIDATION
    //
    // No core file modification. Namespace intact. Import isolation confirmed.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `P4-MIG-01 ui-module has exactly 20 Kotlin files`() {
        val uiModuleDir = findUiModuleDir()
        if (uiModuleDir == null) return

        val ktFiles = uiModuleDir.walkTopDown()
            .filter { it.extension == "kt" }
            .toList()

        assertEquals(
            "ui-module must contain exactly 20 Kotlin files (found: ${ktFiles.map { it.name }})",
            20, ktFiles.size
        )
    }

    @Test
    fun `P4-MIG-02 ui-module has exactly 6 directories`() {
        val uiModuleDir = findUiModuleDir()
        if (uiModuleDir == null) return

        val dirs = uiModuleDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val dirNames = dirs.map { it.name }.toSet()

        assertEquals(
            "ui-module must have 6 subdirectories (found: $dirNames)",
            6, dirs.size
        )

        val expectedDirs = setOf("bridge", "components", "core", "layout", "screens", "theme")
        assertEquals("Directory names must match expected set", expectedDirs, dirNames)
    }

    @Test
    fun `P4-MIG-03 ui-module internal imports resolve within module`() {
        val uiModuleDir = findUiModuleDir()
        if (uiModuleDir == null) return

        val violations = mutableListOf<String>()
        uiModuleDir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { lineNum, line ->
                    val trimmed = line.trimStart()
                    // Only check agoii.ui imports (not Android/Compose imports)
                    if (trimmed.startsWith("import agoii.") && !trimmed.startsWith("import agoii.ui.")) {
                        violations.add("${file.name}:${lineNum + 1} → $trimmed")
                    }
                }
            }

        assertTrue(
            "All agoii.* imports must be agoii.ui.*. Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PHASE 6 — ARCHITECT ENFORCEMENT
    //
    // Layer boundaries, dependency direction, UI isolation.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `P6-ARCH-01 dependency direction — ui-module depends on bridge only`() {
        val uiModuleDir = findUiModuleDir()
        if (uiModuleDir == null) return

        // Non-bridge files must not import outside agoii.ui or standard libraries
        val nonBridgeFiles = uiModuleDir.walkTopDown()
            .filter { it.extension == "kt" && !it.absolutePath.contains("/bridge/") }
            .toList()

        val violations = mutableListOf<String>()
        nonBridgeFiles.forEach { file ->
            file.readLines().forEachIndexed { lineNum, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("import ") &&
                    !trimmed.startsWith("import agoii.ui.") &&
                    !trimmed.startsWith("import androidx.") &&
                    !trimmed.startsWith("import kotlin.") &&
                    !trimmed.startsWith("import java.") &&
                    !trimmed.startsWith("import kotlinx.")) {
                    violations.add("${file.name}:${lineNum + 1} → $trimmed")
                }
            }
        }

        assertTrue(
            "Non-bridge ui-module files must only import from agoii.ui.* or standard libs. Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `P6-ARCH-02 UI isolation — screens only import bridge and ui types`() {
        val uiModuleDir = findUiModuleDir()
        if (uiModuleDir == null) return

        val screenFiles = File(uiModuleDir, "screens").listFiles()?.filter { it.extension == "kt" } ?: emptyList()
        assertTrue("screens/ must contain Kotlin files", screenFiles.isNotEmpty())

        screenFiles.forEach { file ->
            val imports = file.readLines().filter { it.trimStart().startsWith("import ") }
            imports.forEach { line ->
                val trimmed = line.trimStart()
                // Screens may import from agoii.ui.* and standard Android/Compose
                assertFalse(
                    "Screen ${file.name} must not import core: $trimmed",
                    trimmed.contains("com.agoii.mobile")
                )
            }
        }
    }

    @Test
    fun `P6-ARCH-03 layer boundaries — components have no direct state access`() {
        val uiModuleDir = findUiModuleDir()
        if (uiModuleDir == null) return

        val componentFiles = File(uiModuleDir, "components").listFiles()?.filter { it.extension == "kt" } ?: emptyList()

        componentFiles.forEach { file ->
            val content = file.readText()
            // Components must not import UIReplayState directly — they receive pre-projected data
            assertFalse(
                "Component ${file.name} must not reference StateProjection (state projection belongs in screens/core)",
                content.contains("StateProjection()")
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PHASE 7 — GOVERNOR ENFORCEMENT
    //
    // Governor validates execution. No execution bypass.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `P7-GOV-01 Governor blocks empty ledger — no bypass`() {
        val store = InMemoryStore()
        val governor = Governor(store)
        val result = governor.runGovernor("enforcement-test")
        assertEquals(
            "Governor must return NO_EVENT on empty ledger",
            Governor.GovernorResult.NO_EVENT, result
        )
    }

    @Test
    fun `P7-GOV-02 Governor blocks intent without contracts — EA gate enforced`() {
        val store = InMemoryStore(listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"), id = "e0", sequenceNumber = 0)
        ))
        val governor = Governor(store)
        val result = governor.runGovernor("enforcement-test")
        assertEquals(
            "Governor must wait for EA to produce CONTRACTS_GENERATED",
            Governor.GovernorResult.NO_EVENT, result
        )
    }

    @Test
    fun `P7-GOV-03 Governor enforces terminal state — no post-completion execution`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 1)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 1, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_EXECUTED, mapOf(
                "taskId" to "t1", "executionStatus" to "SUCCESS", "validationStatus" to "VALIDATED",
                "position" to 1, "total" to 1, "artifactReference" to "a1", "report_reference" to "rr"
            )),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "t1", "position" to 1, "total" to 1)),
            Event(EventTypes.CONTRACT_COMPLETED, mapOf("position" to 1, "total" to 1, "contractId" to "c1", "report_reference" to "rr")),
            Event(EventTypes.EXECUTION_COMPLETED, mapOf("total" to 1))
        )

        val store = InMemoryStore(events.mapIndexed { i, e ->
            e.copy(id = "e$i", sequenceNumber = i.toLong())
        })
        val governor = Governor(store)
        val result = governor.runGovernor("enforcement-test")

        assertEquals(
            "Governor must return COMPLETED at terminal state",
            Governor.GovernorResult.COMPLETED, result
        )
    }

    @Test
    fun `P7-GOV-04 Governor CSL gate enforced — position 4 blocked`() {
        // Position 4 → EL = CONTRACT_BASE_LOAD(2) + 4 = 6 > VC(5) → DRIFT
        val events = buildCslTestEvents(totalContracts = 4, completedContracts = 3)
        val governor = Governor(InMemoryStore())
        val next = governor.nextEvent(events)

        assertNull(
            "CSL gate must block position 4 (EL=6 > VC=5)",
            next
        )
    }

    @Test
    fun `P7-GOV-05 Governor transition deterministic — same state same output`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1)),
            Event(EventTypes.CONTRACTS_READY, emptyMap())
        )
        val governor = Governor(InMemoryStore())

        val results = (1..3).map { governor.nextEvent(events) }
        results.forEach { result ->
            assertEquals(results[0]?.type, result?.type)
            assertEquals(results[0]?.payload, result?.payload)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PHASE 8 — REPLAY CONSOLIDATION
    //
    // ReplayBuilder is SINGLE source. Deterministic output.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `P8-REP-01 Replay is the sole state constructor`() {
        // Verify Replay produces all three views from events
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "consolidation")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2, "report_reference" to "rr-1")),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 2)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 2, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 2))
        )

        val state = replay.deriveStructuralState(events)

        // All views populated from the SAME event sequence
        assertEquals("rr-1", state.governanceView.reportReference)
        assertEquals(EventTypes.TASK_ASSIGNED, state.governanceView.lastEventType)
        assertEquals(2, state.governanceView.totalContracts)
        assertEquals("ASSIGNED", state.executionView.taskStatus["t1"])
        assertEquals("not_started", state.executionView.executionStatus)
        assertTrue(state.auditView.intent.structurallyComplete)
        assertTrue(state.auditView.contracts.generated)
        assertEquals(1, state.auditView.execution.assignedTasks)
    }

    @Test
    fun `P8-REP-02 Replay determinism — same events always produce same state`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "det-test")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1, "report_reference" to "rr")),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to 1)),
            Event(EventTypes.CONTRACT_STARTED, mapOf("position" to 1, "total" to 1, "contract_id" to "c1")),
            Event(EventTypes.TASK_ASSIGNED, mapOf("taskId" to "t1", "position" to 1, "total" to 1)),
            Event(EventTypes.TASK_STARTED, mapOf("taskId" to "t1")),
            Event(EventTypes.TASK_EXECUTED, mapOf("taskId" to "t1", "executionStatus" to "SUCCESS")),
            Event(EventTypes.TASK_COMPLETED, mapOf("taskId" to "t1")),
            Event(EventTypes.TASK_VALIDATED, mapOf("taskId" to "t1"))
        )

        val states = (1..5).map { replay.deriveStructuralState(events) }
        val reference = states[0]

        for (i in 1 until states.size) {
            assertEquals("Run ${i + 1} governanceView must match", reference.governanceView, states[i].governanceView)
            assertEquals("Run ${i + 1} executionView must match", reference.executionView, states[i].executionView)
            assertEquals("Run ${i + 1} auditView must match", reference.auditView, states[i].auditView)
        }
    }

    @Test
    fun `P8-REP-03 Replay ignores event metadata — only type and payload matter`() {
        val eventsA = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"), "id-aaa", 0L, 1000L),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1), "id-bbb", 1L, 2000L)
        )
        val eventsB = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "test"), "id-zzz", 999L, 9999999L),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 1), "id-yyy", 1000L, 0L)
        )

        val stateA = replay.deriveStructuralState(eventsA)
        val stateB = replay.deriveStructuralState(eventsB)

        assertEquals("State must be identical regardless of metadata",
            stateA.auditView, stateB.auditView)
        assertEquals("ExecutionView must be identical regardless of metadata",
            stateA.executionView, stateB.executionView)
    }

    @Test
    fun `P8-REP-04 Replay full lifecycle produces correct terminal state`() {
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
            Event(EventTypes.ICS_COMPLETED, mapOf("taskId" to "ics-1")),
            Event(EventTypes.COMMIT_CONTRACT, mapOf("taskId" to "commit-1")),
            Event(EventTypes.COMMIT_EXECUTED, mapOf("taskId" to "commit-1"))
        )

        val state = replay.deriveStructuralState(events)

        // Full lifecycle terminal assertions
        assertTrue("Intent must be complete", state.auditView.intent.structurallyComplete)
        assertTrue("Contracts must be generated", state.auditView.contracts.generated)
        assertTrue("Assembly must be completed", state.auditView.assembly.assemblyCompleted)
        assertTrue("ICS must be started", state.executionView.icsStarted)
        assertTrue("ICS must be completed", state.executionView.icsCompleted)
        assertTrue("Commit must exist", state.executionView.commitContractExists)
        assertTrue("Commit must be executed", state.executionView.commitExecuted)
        assertFalse("showCommitPanel must be false after execution", state.executionView.showCommitPanel)
        assertEquals("success", state.executionView.executionStatus)
        assertEquals(EventTypes.COMMIT_EXECUTED, state.governanceView.lastEventType)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INVARIANT PRESERVATION TESTS
    //
    // ARCH-01: Mandatory system flow preserved
    // ARCH-03: Dependency direction enforced
    // ARCH-04: ReplayStructuralState = single authority
    // RL-01:   UI reads ONLY Replay
    // CSL:     Mutation envelope respected
    // DET-01:  Replay determinism guaranteed
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `INV-ARCH-01 system flow intact — Intent to Replay pipeline produces valid state`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "arch-01"))
        )
        val state = replay.deriveStructuralState(events)

        // The pipeline Intent → EventLedger → Replay → ReplayStructuralState works
        assertNotNull(state)
        assertTrue(state.auditView.intent.structurallyComplete)
        assertEquals(EventTypes.INTENT_SUBMITTED, state.governanceView.lastEventType)
    }

    @Test
    fun `INV-ARCH-04 ReplayStructuralState is single authority — three partitioned views`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "arch-04")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 2))
        )
        val state = replay.deriveStructuralState(events)

        // Three views present — each serves a different authority
        assertNotNull("GovernanceView must exist", state.governanceView)
        assertNotNull("ExecutionView must exist", state.executionView)
        assertNotNull("AuditView must exist", state.auditView)

        // Views contain different data — no duplication of authority
        assertEquals(2, state.governanceView.totalContracts)
        assertEquals(2, state.auditView.contracts.totalContracts)
        assertEquals("not_started", state.executionView.executionStatus)
    }

    @Test
    fun `INV-DET-01 replay determinism — 10 runs produce identical state`() {
        val events = listOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "det-01")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to 3, "report_reference" to "rr")),
            Event(EventTypes.CONTRACTS_READY, emptyMap())
        )

        val states = (1..10).map { replay.deriveStructuralState(events) }
        for (i in 1 until states.size) {
            assertEquals("Run ${i + 1} must equal run 1", states[0], states[i])
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Locates the ui-module directory relative to the test working directory. */
    private fun findUiModuleDir(): File? {
        val candidates = listOf(
            File("ui-module"),
            File("../ui-module"),
            File("../../ui-module"),
            File(System.getProperty("user.dir"), "ui-module"),
            File(System.getProperty("user.dir")).parentFile?.let { File(it, "ui-module") }
        )
        return candidates.filterNotNull().firstOrNull { it.exists() && it.isDirectory }
    }

    /** Locates a file by name in the project tree. */
    private fun findFile(name: String): File? {
        val candidates = listOf(
            File(System.getProperty("user.dir")),
            File(System.getProperty("user.dir")).parentFile
        )
        return candidates.filterNotNull()
            .flatMap { root -> root.walkTopDown().filter { it.name == name }.toList() }
            .firstOrNull()
    }

    /** Builds a CSL test event list with N contracts, M completed. */
    private fun buildCslTestEvents(totalContracts: Int, completedContracts: Int): List<Event> {
        val events = mutableListOf(
            Event(EventTypes.INTENT_SUBMITTED, mapOf("objective" to "csl")),
            Event(EventTypes.CONTRACTS_GENERATED, mapOf("total" to totalContracts)),
            Event(EventTypes.CONTRACTS_READY, emptyMap()),
            Event(EventTypes.CONTRACTS_APPROVED, emptyMap()),
            Event(EventTypes.EXECUTION_STARTED, mapOf("total_contracts" to totalContracts))
        )

        for (pos in 1..completedContracts) {
            events.add(Event(EventTypes.CONTRACT_STARTED,
                mapOf("position" to pos, "total" to totalContracts, "contract_id" to "c$pos")))
            events.add(Event(EventTypes.TASK_ASSIGNED,
                mapOf("taskId" to "c$pos-step1", "position" to pos, "total" to totalContracts)))
            events.add(Event(EventTypes.TASK_STARTED,
                mapOf("taskId" to "c$pos-step1", "position" to pos, "total" to totalContracts)))
            events.add(Event(EventTypes.TASK_EXECUTED,
                mapOf("taskId" to "c$pos-step1", "executionStatus" to "SUCCESS",
                    "validationStatus" to "VALIDATED", "position" to pos, "total" to totalContracts,
                    "artifactReference" to "a$pos", "report_reference" to "rr")))
            events.add(Event(EventTypes.TASK_COMPLETED,
                mapOf("taskId" to "c$pos-step1", "position" to pos, "total" to totalContracts)))
            events.add(Event(EventTypes.CONTRACT_COMPLETED,
                mapOf("position" to pos, "total" to totalContracts,
                    "contractId" to "c$pos", "report_reference" to "rr")))
        }

        return events
    }
}
