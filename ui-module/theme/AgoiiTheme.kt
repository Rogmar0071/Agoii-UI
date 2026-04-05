package agoii.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary      = Primary,
    background   = Background,
    surface      = Surface,
    onBackground = OnBackground,
    onSurface    = OnSurface,
    onPrimary    = Color.Black
)

/**
 * Agoii dark theme wrapper.
 *
 * Applies the Agoii colour scheme and Material 3 defaults to all
 * content inside [content]. This is the single theme entry point
 * for the entire UI module.
 */
@Composable
fun AgoiiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content     = content
    )
}
