package agoii.ui.screens

import agoii.ui.bridge.BridgeContract
import agoii.ui.bridge.UIEvent
import agoii.ui.core.EventTimelineRenderer
import agoii.ui.layout.ScreenScaffold
import agoii.ui.theme.OnSurface
import agoii.ui.theme.Surface

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Detail screen composable.
 *
 * Displays the full event timeline for a project using [EventTimelineRenderer]
 * to convert raw events into presentable timeline blocks.
 *
 * @param projectId The project identifier to inspect.
 * @param bridge    The bridge contract for core communication.
 */
@Composable
fun DetailScreen(projectId: String, bridge: BridgeContract) {

    var events by remember { mutableStateOf(emptyList<UIEvent>()) }
    var error  by remember { mutableStateOf<String?>(null) }

    val renderer = remember { EventTimelineRenderer() }

    LaunchedEffect(projectId) {
        try {
            events = bridge.loadEvents(projectId)
        } catch (e: Exception) {
            error = e.message
        }
    }

    val blocks = renderer.render(events)

    ScreenScaffold {
        Text(
            text = "Detail: $projectId",
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            style = MaterialTheme.typography.headlineSmall
        )

        if (events.isEmpty() && error == null) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp)
        ) {
            items(blocks) { block ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "#${block.position}",
                            modifier = Modifier.width(40.dp),
                            color = OnSurface.copy(alpha = 0.5f)
                        )
                        Column {
                            Text(block.type)
                            Text(
                                block.status,
                                color = OnSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
