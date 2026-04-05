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
 * CONTRACT: MQP-UI-REPLACEMENT-AUTHORITATIVE-SWAP-v1 Phase 5
 * - Implements agoii.ui.bridge.CoreBridge
 * - Maps core ReplayStructuralState → UI ReplayStructuralState
 * - Contains ZERO business logic — PURE PROJECTION
 */
class CoreBridgeAdapter(context: Context) : UiCoreBridge {

    private val bridge = CoreBridge(context)

    /** Default project ID for session scope. */
    private var boundProjectId: String = "default"

    /** Bind a project ID for subsequent calls. */
    fun bindProject(projectId: String) {
        boundProjectId = projectId
    }

    override fun replayState(): UiReplayStructuralState =
        bridge.replayState(boundProjectId).toUiReplayState()

    override fun processInteraction(input: String) {
        bridge.processInteraction(boundProjectId, input)
    }

    override fun approveContracts(contractId: String) {
        // Routed through the governed pipeline — no direct execution
        bridge.processInteraction(boundProjectId, "approve:$contractId")
    }

    // ── Mapping: core ReplayStructuralState → UI ReplayStructuralState ───────

    private fun ReplayStructuralState.toUiReplayState() = UiReplayStructuralState(
        governanceView = UiGovernanceView(
            lastEventType    = governanceView.lastEventType ?: "",
            lastEventPayload = governanceView.lastEventPayload.toString(),
            reportReference  = governanceView.reportReference,
            hasLastEvent     = governanceView.lastEventType != null
        ),
        executionView = UiExecutionView(
            executionStatus      = executionView.executionStatus,
            showCommitPanel      = executionView.showCommitPanel,
            lastContractStartedId = governanceView.lastContractStartedId
        ),
        auditView = UiAuditView(
            totalEvents  = auditView.execution.totalTasks,
            contractIds  = governanceView.deltaContractRecoveryIds.toList(),
            hasContracts = governanceView.totalContracts > 0
        )
    )
}
