package agoii.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import agoii.ui.core.ProjectDescriptor
import agoii.ui.core.UiModel
import agoii.ui.layout.LayerStack
import agoii.ui.layout.ProjectSwitcher
import agoii.ui.layout.RootLayout
import agoii.ui.theme.AgoiiSpacing

/**
 * MainScreen — root container for the Agoii UI module.
 *
 * Responsibilities:
 *   - Root container (RootLayout)
 *   - Project selection (ProjectSwitcher)
 *   - Layer stack rendering (GovernancePanel → ExecutionPanel → AuditPanel)
 *   - Interaction panel
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
    RootLayout {
        // ── Project Selector ──────────────────────────────────────────
        ProjectSwitcher(
            projects = projects,
            selectedProjectId = selectedProjectId,
            onSelect = onSelectProject
        )

        // ── Architectural Layer Stack ─────────────────────────────────
        LayerStack(modifier = Modifier.weight(1f)) {
            GovernancePanel(governance = model.governance)
            ExecutionPanel(execution = model.execution)
            AuditPanel(audit = model.audit)
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.Default))

        // ── Interaction Panel ─────────────────────────────────────────
        InteractionPanel(
            model = model.chat,
            onSend = onInteraction
        )
    }
}
