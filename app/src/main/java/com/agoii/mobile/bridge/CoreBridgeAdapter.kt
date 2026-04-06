package com.agoii.mobile.bridge

import agoii.ui.bridge.CoreBridge as UiCoreBridge
import agoii.ui.core.GovernanceView as UiGovernanceView
import agoii.ui.core.ExecutionView as UiExecutionView
import agoii.ui.core.AuditView as UiAuditView
import agoii.ui.core.ConversationMessage as UiConversationMessage
import agoii.ui.core.ReplayStructuralState as UiReplayStructuralState

/**
 * CoreBridgeAdapter — wires the UI module into the Agoii runtime.
 *
 * Implements [agoii.ui.bridge.CoreBridge] (the interface the sealed UI module depends on)
 * by delegating to [com.agoii.mobile.bridge.CoreBridge] (the system orchestrator).
 *
 * Responsibilities:
 *   - Map core [com.agoii.mobile.core.ReplayStructuralState] → UI [UiReplayStructuralState]
 *   - Bind [projectId] for session scope
 *   - Route interactions and approvals
 *
 * Invariants:
 *   ARCH-07   (UI_MODULE_ISOLATION) — UI depends ONLY on this adapter
 *   ARCH-08   (UI_STATE_PIPELINE)   — coreBridge.replayState() → UiModel
 *   ARCH-03   (DEPENDENCY_DIRECTION) — UI → CoreBridge → Nemoclaw
 *
 * ZERO business logic. ZERO state derivation. Pure mapping and delegation.
 */
class CoreBridgeAdapter(
    private val systemBridge: CoreBridge,
    private val projectId: String
) : UiCoreBridge {

    override fun replayState(): UiReplayStructuralState {
        val coreState = systemBridge.replayState(projectId)
        val eventCount = systemBridge.loadEvents(projectId).size
        return mapToUiState(coreState, eventCount)
    }

    override fun processInteraction(input: String) {
        systemBridge.processInteraction(projectId, input)
    }

    override fun approveContracts(contractId: String) {
        systemBridge.approveContracts(projectId)
    }

    private fun mapToUiState(
        core: com.agoii.mobile.core.ReplayStructuralState,
        eventCount: Int
    ): UiReplayStructuralState {
        return UiReplayStructuralState(
            governanceView = UiGovernanceView(
                lastEventType = core.governanceView.lastEventType ?: "",
                lastEventPayload = core.governanceView.lastEventPayload
                    .entries.joinToString(", ") { "${it.key}=${it.value}" },
                reportReference = core.governanceView.reportReference,
                hasLastEvent = core.governanceView.lastEventType != null
            ),
            executionView = UiExecutionView(
                executionStatus = core.executionView.executionStatus,
                showCommitPanel = core.executionView.showCommitPanel,
                lastContractStartedId = core.governanceView.lastContractStartedId
            ),
            auditView = UiAuditView(
                totalEvents = eventCount,
                contractIds = core.auditView.contractIds,
                hasContracts = core.auditView.hasContracts
            ),
            conversation = core.conversation.map { msg ->
                UiConversationMessage(id = msg.id, text = msg.text, isUser = msg.isUser)
            }
        )
    }
}
