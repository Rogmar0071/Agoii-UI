package agoii.ui.layout

import agoii.ui.theme.AgoiiTheme

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.ColumnScope

/**
 * Screen scaffold composable.
 *
 * Wraps content in [AgoiiTheme] and [MainLayout], providing the
 * standard screen frame for all UI module screens.
 *
 * @param content Composable content for the screen body.
 */
@Composable
fun ScreenScaffold(
    content: @Composable ColumnScope.() -> Unit
) {
    AgoiiTheme {
        MainLayout(content = content)
    }
}
