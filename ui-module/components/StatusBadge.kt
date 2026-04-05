package agoii.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiSpacing
import agoii.ui.theme.AgoiiTypography

/**
 * StatusBadge — displays a replay-provided status string with color coding.
 *
 * UI-02 ENFORCEMENT: No derivation. Color is resolved from the status VALUE,
 * not computed from events or collections.
 *
 * @param status  The status string (read directly from a replay view field)
 * @param modifier Optional Compose modifier
 */
@Composable
fun StatusBadge(
    status: String,
    modifier: Modifier = Modifier
) {
    val backgroundColor = statusBackgroundColor(status)
    val textColor = statusTextColor(status)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AgoiiSpacing.BadgeCornerRadius))
            .background(backgroundColor)
            .padding(
                horizontal = AgoiiSpacing.Small,
                vertical = AgoiiSpacing.XSmall
            )
    ) {
        Text(
            text = status,
            style = AgoiiTypography.LabelMedium,
            color = textColor
        )
    }
}

/**
 * Map a status string to its background color.
 * Uses ONLY the replay-provided status value — no derivation.
 */
private fun statusBackgroundColor(status: String): Color = when (status) {
    "closed"         -> AgoiiColors.ExecutionSecondary
    "open"           -> Color(0xFFFCE8E6) // Red 50
    "half_open"      -> AgoiiColors.AuditSecondary
    "assigned"       -> AgoiiColors.GovernanceSecondary
    "awaiting_probe" -> AgoiiColors.AuditSecondary
    "none"           -> AgoiiColors.SurfaceVariant
    else             -> AgoiiColors.SurfaceVariant
}

/**
 * Map a status string to its text color.
 * Uses ONLY the replay-provided status value — no derivation.
 */
private fun statusTextColor(status: String): Color = when (status) {
    "closed"         -> AgoiiColors.ExecutionAccent
    "open"           -> Color(0xFFC5221F) // Red 800
    "half_open"      -> AgoiiColors.AuditAccent
    "assigned"       -> AgoiiColors.GovernanceAccent
    "awaiting_probe" -> AgoiiColors.AuditAccent
    "none"           -> AgoiiColors.TextSecondary
    else             -> AgoiiColors.TextSecondary
}
