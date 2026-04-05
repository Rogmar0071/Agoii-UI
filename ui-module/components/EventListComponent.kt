package agoii.ui.components

import agoii.ui.bridge.UIEvent
import agoii.ui.theme.OnSurface
import agoii.ui.theme.Surface

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Renders an ordered list of [UIEvent]s in a scrollable column.
 *
 * Each event is displayed as a card showing its type and payload.
 * An empty-state placeholder is shown when no events are present.
 *
 * @param events    The ordered list of events to display.
 * @param listState Compose lazy-list state for scroll control.
 * @param modifier  Layout modifier applied to the outer [LazyColumn].
 */
@Composable
fun EventListComponent(
    events: List<UIEvent>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier
    ) {
        if (events.isEmpty()) {
            item {
                Text(
                    "No events yet",
                    color = OnSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            items(events) { event ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("type=${event.type}")
                        Text(
                            "payload=${event.payload}",
                            color = OnSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
