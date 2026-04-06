package agoii.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiSpacing

/**
 * RootLayout — top-level container for the Agoii UI module.
 *
 * Provides consistent screen padding, background, and vertical arrangement.
 * Content slots are filled by MainScreen:
 *   1. ProjectSwitcher
 *   2. LayerStack (Governance → Execution → Audit)
 *   3. InteractionPanel
 *
 * @param modifier Optional Compose modifier
 * @param content  Composable slot for screen content
 */
@Composable
fun RootLayout(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    android.util.Log.e("AGOII_TRACE", "DEAD_UI_PATH_TRIGGERED")
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AgoiiColors.Background)
            .padding(AgoiiSpacing.ScreenPadding),
        content = content
    )
}
