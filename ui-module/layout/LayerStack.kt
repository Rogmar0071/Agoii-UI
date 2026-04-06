package agoii.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import agoii.ui.theme.AgoiiSpacing

/**
 * LayerStack — vertical arrangement of architectural layer panels.
 *
 * UI-05 ENFORCEMENT: Each layer is rendered independently in sequence:
 *   1. Governance Layer
 *   2. Execution Layer
 *   3. Audit Layer
 *
 * LayerStack provides consistent spacing between layers and enables scrolling
 * when content exceeds the viewport.
 *
 * @param modifier Optional Compose modifier
 * @param content  Composable slot — should contain GovernancePanel, ExecutionPanel, AuditPanel
 */
@Composable
fun LayerStack(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    android.util.Log.e("AGOII_TRACE", "DEAD_UI_PATH_TRIGGERED")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AgoiiSpacing.LayerGap),
        content = content
    )
}
