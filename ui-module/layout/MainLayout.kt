package agoii.ui.layout

import agoii.ui.theme.Background

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Main layout container for the UI module.
 *
 * Provides the full-screen column with background colour,
 * status-bar padding, and IME padding. All screen content
 * is placed inside this container.
 *
 * @param content Composable content to render inside the layout.
 */
@Composable
fun MainLayout(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .imePadding(),
        content = content
    )
}
