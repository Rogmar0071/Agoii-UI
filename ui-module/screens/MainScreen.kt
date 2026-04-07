package agoii.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import agoii.ui.core.ProjectDescriptor
import agoii.ui.core.UiModel
import agoii.ui.layout.ProjectSwitcher
import agoii.ui.theme.AgoiiColors

/**
 * MainScreen — root container for the Agoii UI module.
 *
 * Responsibilities:
 *   - Root container (full-screen, panels above fixed interaction bar)
 *   - Project selection (ProjectSwitcher)
 *   - Layer panel rendering (GovernancePanel → ExecutionPanel → AuditPanel)
 *   - Chat surface (ChatPanel — conversation from Replay)
 *   - Status blocks (last event, execution status, contract presence, total events)
 *   - Interaction panel (fixed at bottom, no overlap)
 *
 * UI-01 ENFORCEMENT: ALL data comes from UiModel (mapped from ReplayStructuralState).
 * UI-02 ENFORCEMENT: ZERO derivation — values displayed as-is.
 * UI-03 ENFORCEMENT: Interactions delegated via callbacks.
 *
 * MQP-UNIFIED-EXECUTION-LOOP-v1 — Section 2.2/2.3:
 *   Chat surface renders USER_MESSAGE_SUBMITTED (user bubble) and
 *   SYSTEM_MESSAGE_EMITTED (system bubble) from Replay conversation list.
 *   Status blocks are driven exclusively by Replay-projected values.
 *
 * @param model              UiModel bound from ReplayStructuralState
 * @param projects           Available project descriptors
 * @param selectedProjectId  Currently active project ID
 * @param onSelectProject    Project selection callback
 * @param onInteraction      User interaction callback (routes to CoreBridge)
 * @param onApproveContract  Contract approval callback (routes to CoreBridge)
 * @param onApproveIntent    Intent approval callback (routes to CoreBridge)
 * @param onRejectIntent     Intent rejection callback (routes to CoreBridge)
 */
@Composable
fun MainScreen(
    model: UiModel,
    projects: List<ProjectDescriptor>,
    selectedProjectId: String,
    onSelectProject: (ProjectDescriptor) -> Unit,
    onInteraction: (String) -> Unit,
    onApproveContract: (String) -> Unit,
    onApproveIntent: (String) -> Unit,
    onRejectIntent: (String) -> Unit
) {
    Log.e("AGOII_TRACE", "MAIN_SCREEN_RENDERED")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AgoiiColors.Background)
    ) {

        // ─────────────────────────────────────────────
        // SCROLLABLE CONTENT (ONLY THIS SCROLLS)
        // ─────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            ProjectSwitcher(
                projects = projects,
                selectedProjectId = selectedProjectId,
                onSelect = onSelectProject
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Section 2.2: Chat Surface (Replay-driven) ────────────────
            // USER_MESSAGE_SUBMITTED → user bubble
            // SYSTEM_MESSAGE_EMITTED → system bubble
            // NO local state. NO formatting logic beyond role distinction.
            ChatPanel(chat = model.chat)

            Spacer(modifier = Modifier.height(12.dp))

            GovernancePanel(governance = model.governance)

            Spacer(modifier = Modifier.height(12.dp))

            if (model.governance.showIntentApprovalPanel) {
                IntentApprovalPanel(
                    governance = model.governance,
                    onApproveIntent = onApproveIntent,
                    onRejectIntent = onRejectIntent
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            ExecutionPanel(execution = model.execution)

            Spacer(modifier = Modifier.height(12.dp))

            AuditPanel(audit = model.audit)

            Spacer(modifier = Modifier.height(12.dp))

            // ── Section 2.3: Status Blocks (Replay-driven) ───────────────
            // Last event type, execution status, contract presence, total events.
            // Values come exclusively from model.audit — ZERO derivation.
            Text(
                text = "Status: ${model.audit.executionStatus}",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            model.audit.lastEventType?.let {
                Text(
                    text = "Last Event: $it",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            model.audit.lastEventPayload?.let {
                Text(
                    text = "Payload: $it",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            model.audit.finalOutput?.let {
                Text(
                    text = "Output: $it",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // ─────────────────────────────────────────────
        // INTERACTION PANEL (FIXED — NO OVERLAP)
        // ─────────────────────────────────────────────
        InteractionPanel(
            modifier = Modifier
                .fillMaxWidth()
                .background(AgoiiColors.Surface)
                .padding(12.dp),
            onSend = onInteraction
        )
    }
}
