package agoii.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import agoii.ui.components.ActionButton
import agoii.ui.core.ExecutionView
import agoii.ui.theme.AgoiiColors
import agoii.ui.theme.AgoiiSpacing
import agoii.ui.theme.AgoiiTypography

/**
 * InteractionPanel — handles user input and contract approval.
 *
 * Calls ONLY:
 *   coreBridge.processInteraction() — via onInteraction callback
 *   coreBridge.approveContracts()   — via onApproveContract callback
 *
 * UI-03 ENFORCEMENT: ALL interactions routed through CoreBridge callbacks.
 * No direct execution calls. No state mutation.
 *
 * Local text field state (interactionText) is UI-only presentation state (permitted).
 *
 * @param execution         ExecutionView — used ONLY to read showCommitPanel and lastContractStartedId
 * @param onInteraction     Callback routed to CoreBridge.processInteraction()
 * @param onApproveContract Callback routed to CoreBridge.approveContracts()
 */
@Composable
fun InteractionPanel(
    execution: ExecutionView,
    onInteraction: (String) -> Unit,
    onApproveContract: (String) -> Unit
) {
    var interactionText by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AgoiiSpacing.CardCornerRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = AgoiiSpacing.CardElevation),
        colors = CardDefaults.cardColors(containerColor = AgoiiColors.Surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AgoiiSpacing.CardPadding)
        ) {
            Text(
                text = "Interaction",
                style = AgoiiTypography.HeadlineSmall,
                color = AgoiiColors.GovernancePrimary
            )

            Spacer(modifier = Modifier.height(AgoiiSpacing.SectionGap))

            // ── User Input ────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = interactionText,
                    onValueChange = { interactionText = it },
                    placeholder = {
                        Text(
                            text = "Enter interaction…",
                            style = AgoiiTypography.BodyMedium
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(AgoiiSpacing.Small))

                ActionButton(
                    label = "Send",
                    enabled = interactionText.isNotEmpty(),
                    onClick = {
                        onInteraction(interactionText)
                        interactionText = ""
                    }
                )
            }

            // ── Contract Approval ─────────────────────────────────────
            // Shown ONLY when replay says showCommitPanel is true (UI-02: no derivation)
            if (execution.showCommitPanel) {
                Spacer(modifier = Modifier.height(AgoiiSpacing.Default))

                Text(
                    text = "Contract: ${execution.lastContractStartedId}",
                    style = AgoiiTypography.Mono,
                    color = AgoiiColors.ExecutionAccent
                )

                Spacer(modifier = Modifier.height(AgoiiSpacing.Small))

                ActionButton(
                    label = "Approve Contract",
                    onClick = {
                        onApproveContract(execution.lastContractStartedId)
                    }
                )
            }
        }
    }
}
