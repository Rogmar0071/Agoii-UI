package agoii.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import agoii.ui.components.ExpandableSection
import agoii.ui.components.LayerCard
import agoii.ui.components.StatusBadge
import agoii.ui.core.GovernanceView
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiSpacing
import agoii.ui.theme.AgoiiTypography

/**
 * GovernancePanel — renders the governance layer from ReplayStructuralState.
 *
 * Reads ONLY:
 *   governanceView.lastEventType
 *   governanceView.lastEventPayload
 *   governanceView.reportReference
 *
 * Displays:
 *   - System status
 *   - Last governance action
 *   - Report link
 *
 * UI-01: All values from replay.governanceView.
 * UI-02: ZERO derivation — every displayed value is a direct field read.
 *
 * @param governance GovernanceView from UiModel
 */
@Composable
fun GovernancePanel(governance: GovernanceView) {
    LayerCard(
        title = "Governance",
        accentColor = AgoiiColors.GovernancePrimary
    ) {
        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        // ── System Status ─────────────────────────────────────────────
        ExpandableSection(title = "System Status") {
            if (governance.hasLastEvent) {
                StatusBadge(status = governance.lastEventType)
            } else {
                Text(
                    text = "No governance events",
                    style = AgoiiTypography.BodyMedium,
                    color = AgoiiColors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        // ── Last Governance Action ────────────────────────────────────
        ExpandableSection(title = "Last Action") {
            Text(
                text = governance.lastEventPayload.ifEmpty { "—" },
                style = AgoiiTypography.BodyMedium,
                color = AgoiiColors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        // ── Report Reference ──────────────────────────────────────────
        ExpandableSection(title = "Report Reference") {
            Text(
                text = governance.reportReference.ifEmpty { "—" },
                style = AgoiiTypography.Mono,
                color = AgoiiColors.GovernanceAccent
            )
        }
    }
}
