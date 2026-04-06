package agoii.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * InteractionPanel — input-only surface for capturing user interaction.
 *
 * MQP-UI-LAYOUT-AUTHORITY-v1: simplified to pure input Row (TextField + Send button).
 * Chat message history rendering removed from this surface per layout authority contract.
 *
 * UI-03 ENFORCEMENT: ALL interactions routed through CoreBridge via onSend callback.
 *   - Local `input` state is transient presentation state (permitted).
 *   - NO message storage. NO history. NO state derivation in UI.
 *
 * @param modifier  Compose modifier supplied by parent layout.
 * @param onSend    Callback routed to CoreBridge.processInteraction().
 */
@Composable
fun InteractionPanel(
    modifier: Modifier = Modifier,
    onSend: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }

    Log.e("AGOII_TRACE", "INTERACTION_PANEL_RENDERED")

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔥 ACTIVE PANEL")

        TextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            placeholder = { Text("Enter interaction...") }
        )

        Button(
            enabled = true,
            onClick = {
                Log.e("AGOII_TRACE", "SEND_CLICK_CONFIRMED")
                onSend(input)
                input = ""
            }
        ) {
            Text("Send")
        }
    }
}
