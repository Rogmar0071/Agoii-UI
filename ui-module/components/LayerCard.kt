package agoii.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiSpacing
import agoii.ui.theme.AgoiiTypography

/**
 * LayerCard — a themed card representing a single architectural layer.
 *
 * UI-05 ENFORCEMENT: Each layer is rendered independently with
 * its own color identity. Visual separation reflects system architecture.
 *
 * @param title     Layer title (e.g. "Governance", "Execution", "Audit")
 * @param accentColor Layer accent color for the title
 * @param modifier  Optional Compose modifier
 * @param content   Composable slot for layer-specific content
 */
@Composable
fun LayerCard(
    title: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AgoiiSpacing.CardCornerRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = AgoiiSpacing.CardElevation),
        colors = CardDefaults.cardColors(containerColor = AgoiiColors.Surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AgoiiSpacing.CardPadding)
        ) {
            Text(
                text = title,
                style = AgoiiTypography.HeadlineSmall,
                color = accentColor
            )

            content()
        }
    }
}
