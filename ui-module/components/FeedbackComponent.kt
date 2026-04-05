package agoii.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Displays feedback messages to the user.
 *
 * Shows the most recent response (if any) and/or an error message.
 * Purely presentational — no state derivation.
 *
 * @param response The last successful response string, or null.
 * @param error    The last error message string, or null.
 */
@Composable
fun FeedbackComponent(
    response: String?,
    error: String?
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        response?.let {
            Text(it, modifier = Modifier.padding(8.dp))
        }

        error?.let {
            Text(it, color = Color.Red, modifier = Modifier.padding(8.dp))
        }
    }
}
