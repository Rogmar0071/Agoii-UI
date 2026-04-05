package com.agoii.mobile.bridge

import android.content.Context
import agoii.ui.bridge.CoreBridge as UiCoreBridge
import agoii.ui.core.AuditView as UiAuditView
import agoii.ui.core.ExecutionView as UiExecutionView
import agoii.ui.core.GovernanceView as UiGovernanceView
import agoii.ui.core.ReplayStructuralState as UiReplayStructuralState
import com.agoii.mobile.core.ReplayStructuralState

/**
 * Adapter that implements the UI module's [CoreBridge] interface by wrapping
 * the system [CoreBridge].
 *
 * This is the ONLY mapping boundary between core and UI types.
 * All core types are mapped to UI types here — zero core types leak
 * into the UI module.
 *
 * CONTRACT: MQP-UI-STATE-AUTHORITY-REPAIR-v1 Phase 5
 * - Implements agoii.ui.bridge.CoreBridge
 * - Maps core ReplayStructuralState → UI ReplayStructuralState
 * - Contains ZERO business logic — PURE PROJECTION
 * - All derived/resolved fields come from Replay (ARCH-04 / RL-01)
 */
class CoreBridgeAdapter(context: Context) : UiCoreBridge {

    private val bridge = CoreBridge(context)

    /** Default project ID for session scope. */
    private var boundProjectId: String = "default"

    /** Bind a project ID for subsequent calls. */
    fun bindProject(projectId: String) {
        boundProjectId = projectId
    }

    override fun replayState(): UiReplayStructuralState {
        val coreState = bridge.replayState(boundProjectId)
        val uiState = coreState.toUiReplayState()
        // LOG-03 — TEMP: UI Bind traceability (MQP-LIVE-TESTING-v1)
        println("[LOG-03] CoreBridgeAdapter.replayState | governance=[hasLastEvent=${uiState.governanceView.hasLastEvent}, lastEventType=${uiState.governanceView.lastEventType}] | execution=[status=${uiState.executionView.executionStatus}, showCommit=${uiState.executionView.showCommitPanel}] | audit=[totalEvents=${uiState.auditView.totalEvents}, hasContracts=${uiState.auditView.hasContracts}]")
        return uiState
    }

    override fun processInteraction(input: String) {
        bridge.processInteraction(boundProjectId, input)
    }

    override fun approveContracts(contractId: String) {
        bridge.processInteraction(boundProjectId, "approve:$contractId")
    }

    // ── Mapping: core ReplayStructuralState → UI ReplayStructuralState ───────
    // PURE PROJECTION — every field is a direct read from Replay state.
    // ZERO logic, ZERO derivation, ZERO null handling, ZERO collection ops.

    private fun ReplayStructuralState.toUiReplayState() = UiReplayStructuralState(
        governanceView = UiGovernanceView(
            lastEventType    = governanceView.lastEventTypeDisplay,
            lastEventPayload = governanceView.lastEventPayloadDisplay,
            reportReference  = governanceView.reportReference,
            hasLastEvent     = governanceView.hasLastEvent
        ),
        executionView = UiExecutionView(
            executionStatus       = executionView.executionStatus,
            showCommitPanel       = executionView.showCommitPanel,
            lastContractStartedId = governanceView.lastContractStartedId
        ),
        auditView = UiAuditView(
            totalEvents  = auditView.totalEvents,
            contractIds  = auditView.contractIds,
            hasContracts = auditView.hasContracts
        )
    )
}
