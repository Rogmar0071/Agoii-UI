package com.agoii.mobile.bridge

import android.content.Context
import agoii.ui.bridge.BridgeContract
import agoii.ui.bridge.UIAuditView
import agoii.ui.bridge.UIAssemblyState
import agoii.ui.bridge.UIContractState
import agoii.ui.bridge.UIEvent
import agoii.ui.bridge.UIExecutionState
import agoii.ui.bridge.UIExecutionView
import agoii.ui.bridge.UIGovernanceView
import agoii.ui.bridge.UIIntentState
import agoii.ui.bridge.UIReplayState
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.ReplayStructuralState

/**
 * Adapter that implements the UI module's [BridgeContract] by wrapping
 * the system [CoreBridge].
 *
 * This is the ONLY mapping boundary between core and UI types.
 * All core types are mapped to UI bridge types here — zero core types
 * leak into the UI module.
 *
 * CONTRACT: MQP-UI-FINAL-CONSOLIDATED Phase 5B
 * - Implements agoii.ui.bridge.BridgeContract
 * - Maps core ReplayStructuralState → UIReplayState
 * - Maps core Event → UIEvent
 * - Contains ZERO business logic
 */
class CoreBridgeAdapter(context: Context) : BridgeContract {

    private val bridge = CoreBridge(context)

    override fun loadEvents(projectId: String): List<UIEvent> =
        bridge.loadEvents(projectId).map { it.toUIEvent() }

    override fun replayState(projectId: String): UIReplayState =
        bridge.replayState(projectId).toUIReplayState()

    override fun processInteraction(projectId: String, input: String): String =
        bridge.processInteraction(projectId, input)

    // ── Mapping: core Event → UI UIEvent ─────────────────────────────────────

    private fun Event.toUIEvent() = UIEvent(
        type           = type,
        payload        = payload,
        id             = id,
        sequenceNumber = sequenceNumber,
        timestamp      = timestamp
    )

    // ── Mapping: core ReplayStructuralState → UI UIReplayState ───────────────

    private fun ReplayStructuralState.toUIReplayState() = UIReplayState(
        governanceView = UIGovernanceView(
            lastEventType            = governanceView.lastEventType,
            lastEventPayload         = governanceView.lastEventPayload,
            totalContracts           = governanceView.totalContracts,
            reportReference          = governanceView.reportReference,
            deltaContractRecoveryIds = governanceView.deltaContractRecoveryIds,
            taskAssignedTaskIds      = governanceView.taskAssignedTaskIds,
            lastContractStartedId    = governanceView.lastContractStartedId,
            lastContractStartedPosition = governanceView.lastContractStartedPosition
        ),
        executionView = UIExecutionView(
            taskStatus           = executionView.taskStatus,
            icsStarted           = executionView.icsStarted,
            icsCompleted         = executionView.icsCompleted,
            commitContractExists = executionView.commitContractExists,
            commitExecuted       = executionView.commitExecuted,
            commitAborted        = executionView.commitAborted,
            executionStatus      = executionView.executionStatus,
            showCommitPanel      = executionView.showCommitPanel
        ),
        auditView = UIAuditView(
            intent = UIIntentState(
                structurallyComplete = auditView.intent.structurallyComplete
            ),
            contracts = UIContractState(
                generated      = auditView.contracts.generated,
                valid          = auditView.contracts.valid,
                totalContracts = auditView.contracts.totalContracts
            ),
            execution = UIExecutionState(
                totalTasks      = auditView.execution.totalTasks,
                assignedTasks   = auditView.execution.assignedTasks,
                completedTasks  = auditView.execution.completedTasks,
                validatedTasks  = auditView.execution.validatedTasks,
                successfulTasks = auditView.execution.successfulTasks
            ),
            assembly = UIAssemblyState(
                assemblyStarted   = auditView.assembly.assemblyStarted,
                assemblyValidated = auditView.assembly.assemblyValidated,
                assemblyCompleted = auditView.assembly.assemblyCompleted
            )
        )
    )
}
