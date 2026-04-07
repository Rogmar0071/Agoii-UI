package agoii.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import agoii.ui.components.ActionButton
import agoii.ui.components.ExpandableSection
import agoii.ui.components.LayerCard
import agoii.ui.components.StatusBadge
import agoii.ui.core.GovernanceView
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiSpacing
import agoii.ui.theme.AgoiiTypography

@Composable
fun IntentApprovalPanel(
    governance: GovernanceView,
    onApproveIntent: (String) -> Unit,
    onRejectIntent: (String) -> Unit
) {
    LayerCard(
        title = "Intent Approval",
        accentColor = AgoiiColors.GovernancePrimary
    ) {
        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        ExpandableSection(title = "Approval Status") {
            StatusBadge(status = governance.intentApprovalStatus)
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        ExpandableSection(title = "Objective") {
            Text(
                text = governance.pendingIntentObjective.ifEmpty { "—" },
                style = AgoiiTypography.BodyMedium,
                color = AgoiiColors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        ExpandableSection(title = "Intent Id") {
            Text(
                text = governance.pendingIntentId.ifEmpty { "—" },
                style = AgoiiTypography.Mono,
                color = AgoiiColors.GovernanceAccent
            )
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        Row(modifier = Modifier.fillMaxWidth()) {
            ActionButton(
                label = "Approve",
                onClick = { onApproveIntent(governance.pendingIntentId) },
                enabled = governance.pendingIntentId.isNotEmpty(),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(AgoiiSpacing.Small))
            ActionButton(
                label = "Reject",
                onClick = { onRejectIntent(governance.pendingIntentId) },
                enabled = governance.pendingIntentId.isNotEmpty(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
