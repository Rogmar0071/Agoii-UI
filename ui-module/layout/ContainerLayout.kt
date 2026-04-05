package agoii.ui.layout

import agoii.ui.theme.Surface
import agoii.ui.theme.SurfaceVariant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Standard container for elevated content sections.
 *
 * Applies the surface colour, rounded corners, and standard padding.
 * Used for cards, panels, and grouped content blocks.
 *
 * @param modifier Optional modifier override.
 * @param elevated When true uses the elevated surface variant colour.
 * @param content  Composable content inside the container.
 */
@Composable
fun ContainerLayout(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val bgColor = if (elevated) SurfaceVariant else Surface
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(12.dp),
        content = content
    )
}
