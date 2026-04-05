package agoii.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Displays the current system state panel.
 *
 * Shows event count, last event type, and optional interaction content.
 * All inputs are pre-derived values — no state derivation occurs here.
 *
 * @param eventsCount        Total number of loaded events.
 * @param lastEventType      The type of the most recent event, or null.
 * @param interactionContent The content of the last interaction result, or null.
 */
@Composable
fun StatePanelComponent(
    eventsCount: Int,
    lastEventType: String?,
    interactionContent: String?
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {

        Text(
            "Events Loaded: $eventsCount",
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            "Last Event: ${lastEventType ?: "none"}",
            style = MaterialTheme.typography.bodySmall
        )

        interactionContent?.let { content ->
            Text(
                "Interaction: $content",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
