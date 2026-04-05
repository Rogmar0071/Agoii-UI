package agoii.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Action bar with text input and send button.
 *
 * Provides the user input field and send action. The caller manages
 * state and handles the send callback.
 *
 * @param inputText    Current text in the input field.
 * @param onInputChange Callback when text changes.
 * @param onSend       Callback when user triggers send (button or keyboard).
 */
@Composable
fun ActionBarComponent(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            placeholder = { Text("Enter command...") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            singleLine = true
        )
        Button(onClick = onSend) {
            Text("Send")
        }
    }
}
