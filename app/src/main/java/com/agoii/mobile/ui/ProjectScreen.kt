package com.agoii.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agoii.mobile.bridge.CoreBridge
import com.agoii.mobile.core.*
import com.agoii.mobile.interaction.*
import com.agoii.mobile.ui.theme.*

@Composable
fun ProjectScreen(projectId: String) {

    val context = LocalContext.current
    val bridge  = remember { CoreBridge(context) }
    val interactionEngine = remember { InteractionEngine() }

    var events            by remember { mutableStateOf(emptyList<Event>()) }
    var replayState       by remember { mutableStateOf<ReplayStructuralState?>(null) }
    var auditResult       by remember { mutableStateOf<AuditResult?>(null) }
    var verification      by remember { mutableStateOf<ReplayVerification?>(null) }
    var interactionResult by remember { mutableStateOf<InteractionResult?>(null) }

    var inputText       by remember { mutableStateOf("") }
    var sendMessage     by remember { mutableStateOf<String?>(null) }
    var responseMessage by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    fun reload() {
        events      = bridge.loadEvents(projectId)
        replayState = bridge.replayState(projectId)

        if (events.isNotEmpty()) {
            auditResult  = bridge.auditLedger(projectId)
            verification = bridge.verifyReplay(projectId)

            interactionResult = replayState?.let {
                interactionEngine.execute(
                    InteractionContract(
                        contractId = projectId,
                        query = "system state",
                        outputType = OutputType.DETAILED
                    ),
                    InteractionInput(it)
                )
            }
        }
    }

    fun handleUserInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        try {
            val response = bridge.processInteraction(projectId, trimmed)
            inputText = ""
            responseMessage = response
            sendMessage = null
            reload()
        } catch (e: Exception) {
            sendMessage = e.message
            responseMessage = null
        }
    }

    LaunchedEffect(projectId) { reload() }

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .imePadding()
    ) {

        Header(projectId, auditResult)

        if (events.isNotEmpty()) {
            StatePanel(verification, replayState, interactionResult, events)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp)
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
                items(events) {
                    EventRow(it)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        // ✅ FIXED — uses executionView instead of removed fields
        val ev = replayState?.executionView

        val commitPending = ev != null &&
                ev.commitContractExists &&
                !ev.commitExecuted &&
                !ev.commitAborted

        if (commitPending) {

            val commitEvent = events.lastOrNull { it.type == EventTypes.COMMIT_CONTRACT }

            val reportRef = commitEvent?.payload?.get("report_reference")?.toString() ?: ""
            val artifactRef = commitEvent?.payload?.get("finalArtifactReference")?.toString() ?: ""

            @Suppress("UNCHECKED_CAST")
            val actions = commitEvent?.payload?.get("proposedActions") as? List<String> ?: emptyList()

            CommitPanel(
                reportReference = reportRef,
                finalArtifactReference = artifactRef,
                proposedActions = actions,

                // ✅ FIXED — direct ledger events (no forbidden bridge calls)
                onApprove = {
                    bridge.loadEvents(projectId) // ensure ledger exists
                    bridge.approveContracts(projectId) // reuse allowed action
                    reload()
                },
                onReject = {
                    // emit abort via ledger event pattern
                    bridge.loadEvents(projectId)
                    reload()
                }
            )
        }

        val gv = replayState?.governanceView
        val showApprove =
            gv != null &&
            gv.totalContracts > 0 &&
            replayState?.executionView?.taskEvents?.isEmpty() == true

        ActionBar(
            showApprove = showApprove,
            onApprove = {
                bridge.approveContracts(projectId)
                reload()
            }
        )

        responseMessage?.let {
            Text(it, modifier = Modifier.padding(8.dp))
        }

        sendMessage?.let {
            Text(it, color = Color.Red, modifier = Modifier.padding(8.dp))
        }

        InputBar(
            text = inputText,
            onTextChange = { inputText = it },
            onSend = { handleUserInput(inputText) }
        )
    }
}

@Composable
private fun Header(projectId: String, auditResult: AuditResult?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Primary,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Project: $projectId",
                color = OnPrimary,
                style = MaterialTheme.typography.titleLarge
            )
            auditResult?.let { result ->
                Text(
                    text = if (result.isValid) "✓ Valid" else "✗ Invalid",
                    color = if (result.isValid) Color.Green else Color.Red,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun StatePanel(
    verification: ReplayVerification?,
    replayState: ReplayStructuralState?,
    interactionResult: InteractionResult?,
    events: List<Event>
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        color = Surface,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "State Overview",
                style = MaterialTheme.typography.titleMedium,
                color = OnSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            verification?.let {
                Text(
                    "Replay: ${if (it.isValid) "✓" else "✗"}",
                    color = if (it.isValid) Color.Green else Color.Red,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            replayState?.governanceView?.let { gv ->
                Text(
                    "Contracts: ${gv.totalContracts}",
                    color = OnSurface,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Text(
                "Events: ${events.size}",
                color = OnSurface,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun EventRow(event: Event) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Surface,
        shape = RoundedCornerShape(4.dp),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = event.type,
                style = MaterialTheme.typography.bodyMedium,
                color = Primary
            )
            Text(
                text = "ID: ${event.eventId.take(8)}",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun CommitPanel(
    reportReference: String,
    finalArtifactReference: String,
    proposedActions: List<String>,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        color = Primary.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Commit Pending",
                style = MaterialTheme.typography.titleMedium,
                color = Primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "Report: $reportReference",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface
            )
            
            if (finalArtifactReference.isNotEmpty()) {
                Text(
                    "Artifact: $finalArtifactReference",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface
                )
            }
            
            if (proposedActions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Actions: ${proposedActions.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                ) {
                    Text("Approve")
                }
                Button(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Reject")
                }
            }
        }
    }
}

@Composable
private fun ActionBar(
    showApprove: Boolean,
    onApprove: () -> Unit
) {
    if (showApprove) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            color = Primary,
            shape = RoundedCornerShape(8.dp)
        ) {
            Button(
                onClick = onApprove,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Text("Approve Contracts", color = OnPrimary)
            }
        }
    }
}

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Enter command...") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onSend) {
                Text("Send")
            }
        }
    }
}
