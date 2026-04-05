package agoii.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import agoii.ui.components.ExpandableSection
import agoii.ui.components.LayerCard
import agoii.ui.components.StatusBadge
import agoii.ui.components.TimelineView
import agoii.ui.core.ExecutionView
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiSpacing
import agoii.ui.theme.AgoiiTypography

/**
 * ExecutionPanel — renders the execution layer from ReplayStructuralState.
 *
 * Reads ONLY:
 *   executionView.circuitState
 *   executionView.circuitFailures
 *   executionView.probeStatus
 *   executionView.probeOwner
 *   executionView.lastTransitionAt
 *   executionView.executionStatus
 *   executionView.lastContractStartedId
 *
 * Displays:
 *   - Circuit state badge
 *   - Execution status
 *   - Probe lifecycle
 *   - Failure count
 *   - Contract progress
 *
 * UI-01: All values from replay.executionView.
 * UI-02: ZERO derivation — every displayed value is a direct field read.
 *        Forbidden: if (events.any { ... }) ❌
 *        Required:  Text(execution.circuitState) ✅
 *
 * @param execution ExecutionView from UiModel
 */
@Composable
fun ExecutionPanel(execution: ExecutionView) {
    LayerCard(
        title = "Execution",
        accentColor = AgoiiColors.ExecutionPrimary
    ) {
        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        // ── Circuit State ─────────────────────────────────────────────
        ExpandableSection(title = "Circuit State") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(status = execution.circuitState)
                Spacer(modifier = Modifier.width(AgoiiSpacing.Small))
                Text(
                    text = "Failures: ${execution.circuitFailures}",
                    style = AgoiiTypography.BodyMedium,
                    color = AgoiiColors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        // ── Execution Status ──────────────────────────────────────────
        ExpandableSection(title = "Execution Status") {
            Text(
                text = execution.executionStatus.ifEmpty { "—" },
                style = AgoiiTypography.BodyMedium,
                color = AgoiiColors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        // ── Probe Lifecycle ───────────────────────────────────────────
        ExpandableSection(title = "Probe") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Status: ",
                    style = AgoiiTypography.LabelMedium,
                    color = AgoiiColors.TextSecondary
                )
                StatusBadge(status = execution.probeStatus)
            }

            Spacer(modifier = Modifier.height(AgoiiSpacing.XSmall))

            Text(
                text = "Owner: ${execution.probeOwner ?: "—"}",
                style = AgoiiTypography.Mono,
                color = AgoiiColors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        // ── Last Transition ───────────────────────────────────────────
        ExpandableSection(title = "Last Transition") {
            Text(
                text = execution.lastTransitionAt?.toString() ?: "—",
                style = AgoiiTypography.Mono,
                color = AgoiiColors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        // ── Contract Progress ─────────────────────────────────────────
        ExpandableSection(title = "Contract Progress") {
            Text(
                text = execution.lastContractStartedId.ifEmpty { "No active contract" },
                style = AgoiiTypography.Mono,
                color = AgoiiColors.ExecutionAccent
            )
        }
    }
}
