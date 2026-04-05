package agoii.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import agoii.ui.core.ChatUiModel

/**
 * InteractionPanel — chat surface that renders Replay state as a conversation.
 *
 * CHAT-UI-03 ENFORCEMENT:
 *   - Renders messages from ChatUiModel ONLY (sourced from Replay via UiStateBinder).
 *   - Send dispatches raw input through onSend callback → CoreBridge.
 *   - NO message storage. NO history. NO state derivation in UI.
 *   - Local `input` text field state is transient UI presentation state (permitted).
 *
 * UI-03 ENFORCEMENT: ALL interactions routed through CoreBridge via onSend callback.
 *
 * @param model   ChatUiModel sourced from ReplayStructuralState.
 * @param onSend  Callback routed to CoreBridge.processInteraction().
 */
@Composable
fun InteractionPanel(
    model: ChatUiModel,
    onSend: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {

        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(model.messages) { message ->
                Text(
                    text = message.text,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        onSend(input)
                        input = ""
                    }
                }
            ) {
                Text("Send")
            }
        }
    }
}
