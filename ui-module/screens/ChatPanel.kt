package agoii.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import agoii.ui.core.ChatUiModel
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiTypography

/**
 * ChatPanel — conversation surface.
 *
 * MQP-UNIFIED-EXECUTION-LOOP-v1 — Section 2.2:
 *   USER_MESSAGE_SUBMITTED  → user bubble  (right-aligned, surface color)
 *   SYSTEM_MESSAGE_EMITTED  → system bubble (left-aligned, accent color)
 *
 * UI Purity Law:
 *   - Renders ONLY from [ChatUiModel] mapped from ReplayStructuralState.
 *   - ZERO derivation. ZERO local message state. Pure projection.
 *
 * @param chat ChatUiModel from UiModel (source: Replay conversation list)
 */
@Composable
fun ChatPanel(chat: ChatUiModel) {
    if (chat.messages.isEmpty()) {
        Text(
            text = "No messages yet. Press \"New Intent\" to start.",
            style = AgoiiTypography.BodyMedium,
            color = AgoiiColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chat.messages.forEach { message ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (message.isUser) AgoiiColors.Surface
                            else AgoiiColors.AuditPrimary
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = message.text,
                        style = AgoiiTypography.BodyMedium,
                        color = if (message.isUser) AgoiiColors.TextPrimary
                                else AgoiiColors.Background
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}
