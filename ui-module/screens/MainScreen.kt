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
 *   - Interaction panel (fixed at bottom, no overlap)
 *
 * UI-01 ENFORCEMENT: ALL data comes from UiModel (mapped from ReplayStructuralState).
 * UI-02 ENFORCEMENT: ZERO derivation — values displayed as-is.
 * UI-03 ENFORCEMENT: Interactions delegated via callbacks.
 *
 * @param model              UiModel bound from ReplayStructuralState
 * @param projects           Available project descriptors
 * @param selectedProjectId  Currently active project ID
 * @param onSelectProject    Project selection callback
 * @param onInteraction      User interaction callback (routes to CoreBridge)
 * @param onApproveContract  Contract approval callback (routes to CoreBridge)
 */
@Composable
fun MainScreen(
    model: UiModel,
    projects: List<ProjectDescriptor>,
    selectedProjectId: String,
    onSelectProject: (ProjectDescriptor) -> Unit,
    onInteraction: (String) -> Unit,
    onApproveContract: (String) -> Unit
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

            GovernancePanel(governance = model.governance)

            Spacer(modifier = Modifier.height(12.dp))

            ExecutionPanel(execution = model.execution)

            Spacer(modifier = Modifier.height(12.dp))

            AuditPanel(audit = model.audit)

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
