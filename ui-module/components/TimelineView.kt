package agoii.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiSpacing
import agoii.ui.theme.AgoiiTypography

/**
 * TimelineView — renders a list of event labels as a vertical timeline.
 *
 * UI-02 ENFORCEMENT: Displays ONLY pre-built string items from replay.
 * No filtering, sorting, or derivation. The list is rendered as-is.
 *
 * @param items   List of display strings (from replay view, NOT computed)
 * @param modifier Optional Compose modifier
 */
@Composable
fun TimelineView(
    items: List<String>,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        itemsIndexed(items) { index, item ->
            TimelineEntry(
                label = item,
                isLast = index == items.lastIndex
            )
        }
    }
}

/**
 * Single timeline entry with a dot indicator and label.
 */
@Composable
private fun TimelineEntry(
    label: String,
    isLast: Boolean
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AgoiiSpacing.XSmall)
    ) {
        // Timeline dot
        Surface(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape),
            color = AgoiiColors.ExecutionPrimary
        ) {}

        Spacer(modifier = Modifier.width(AgoiiSpacing.Small))

        Column {
            Text(
                text = label,
                style = AgoiiTypography.BodyMedium,
                color = AgoiiColors.TextPrimary
            )
            if (!isLast) {
                Spacer(modifier = Modifier.height(AgoiiSpacing.XSmall))
            }
        }
    }
}
