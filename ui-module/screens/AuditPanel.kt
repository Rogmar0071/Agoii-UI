package agoii.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import agoii.ui.components.ExpandableSection
import agoii.ui.components.LayerCard
import agoii.ui.components.TimelineView
import agoii.ui.core.AuditView
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiSpacing
import agoii.ui.theme.AgoiiTypography

/**
 * AuditPanel — renders the audit layer from ReplayStructuralState.
 *
 * Reads ONLY:
 *   auditView.totalEvents
 *   auditView.contractIds
 *
 * Displays:
 *   - System volume (total event count)
 *   - Contract history (list of contract IDs)
 *
 * UI-01: All values from replay.auditView.
 * UI-02: ZERO derivation.
 *        Forbidden: if (events.any { ... }) ❌
 *        Required:  Text("${audit.totalEvents}") ✅
 *
 * @param audit AuditView from UiModel
 */
@Composable
fun AuditPanel(audit: AuditView) {
    LayerCard(
        title = "Audit",
        accentColor = AgoiiColors.AuditPrimary
    ) {
        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        // ── System Volume ─────────────────────────────────────────────
        ExpandableSection(title = "System Volume") {
            Text(
                text = "Total Events: ${audit.totalEvents}",
                style = AgoiiTypography.HeadlineMedium,
                color = AgoiiColors.AuditAccent
            )
        }

        Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

        // ── Contract History ──────────────────────────────────────────
        ExpandableSection(title = "Contract History") {
            if (audit.hasContracts) {
                TimelineView(items = audit.contractIds)
            } else {
                Text(
                    text = "No contracts recorded",
                    style = AgoiiTypography.BodyMedium,
                    color = AgoiiColors.TextSecondary
                )
            }
        }
    }
}
