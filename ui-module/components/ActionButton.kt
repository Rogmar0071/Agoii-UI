package agoii.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiSpacing
import agoii.ui.theme.AgoiiTypography

/**
 * ActionButton — triggers interactions routed through CoreBridge.
 *
 * UI-03 ENFORCEMENT: The onClick callback MUST delegate to
 * UiActionDispatcher (which calls CoreBridge). No direct execution.
 *
 * @param label    Button label text
 * @param onClick  Callback — MUST route through UiActionDispatcher
 * @param enabled  Whether the button is interactive
 * @param modifier Optional Compose modifier
 */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(AgoiiSpacing.ButtonCornerRadius),
        contentPadding = PaddingValues(
            horizontal = AgoiiSpacing.Default,
            vertical = AgoiiSpacing.ButtonPadding
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = AgoiiColors.ButtonPrimary,
            disabledContainerColor = AgoiiColors.ButtonDisabled
        ),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = AgoiiTypography.LabelLarge
        )
    }
}
