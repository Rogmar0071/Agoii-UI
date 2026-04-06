package com.agoii.mobile.bridge

import android.util.Log
import agoii.ui.bridge.CoreBridge as UiCoreBridge
import agoii.ui.core.GovernanceView as UiGovernanceView
import agoii.ui.core.ExecutionView as UiExecutionView
import agoii.ui.core.AuditView as UiAuditView
import agoii.ui.core.ConversationMessage as UiConversationMessage
import agoii.ui.core.ReplayStructuralState as UiReplayStructuralState
import com.agoii.mobile.interaction.InteractionEngine

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
 *   - Perform interpretation (ARCH-09: interpretation MUST occur before CoreBridge)
 *
 * Invariants:
 *   ARCH-07   (UI_MODULE_ISOLATION) — UI depends ONLY on this adapter
 *   ARCH-08   (UI_STATE_PIPELINE)   — coreBridge.replayState() → UiModel
 *   ARCH-03   (DEPENDENCY_DIRECTION) — UI → CoreBridge → Nemoclaw
 *   ARCH-09   (INTERACTION_BOUNDARY) — InteractionEngine called HERE, before system CoreBridge
 *
 * ZERO business logic. ZERO state derivation. Pure mapping and delegation.
 */
class CoreBridgeAdapter(
    private val systemBridge: CoreBridge,
    private val projectId: String
) : UiCoreBridge {

    // ARCH-09: Interpretation occurs at the boundary layer, before system CoreBridge.
    // CoreBridge itself contains ZERO interpretation logic (FIX-04).
    private val interactionEngine = InteractionEngine()

    override fun replayState(): UiReplayStructuralState {
        val coreState = systemBridge.replayState(projectId)
        val eventCount = systemBridge.loadEvents(projectId).size
        return mapToUiState(coreState, eventCount)
    }

    override fun appendUserMessage(input: String) {
        Log.e("AGOII_TRACE", "COREBRIDGE_APPEND_USER_MESSAGE")
        val structuredIntent = interactionEngine.processInput(input)
        systemBridge.appendUserMessage(projectId, input, structuredIntent)
        Log.e("AGOII_TRACE", "COREBRIDGE_APPEND_USER_MESSAGE_EXIT")
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
                hasContracts = core.auditView.hasContracts,
                lastEventType = core.auditView.lastEventType,
                lastEventPayload = core.auditView.lastEventPayload,
                executionStatus = core.auditView.executionStatus,
                finalOutput = core.auditView.finalOutput
            ),
            conversation = core.conversation.map { msg ->
                UiConversationMessage(id = msg.id, text = msg.text, isUser = msg.isUser)
            }
        )
    }
}
