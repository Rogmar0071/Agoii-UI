package com.agoii.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
    var interactionResult by remember { mutableStateOf<InteractionResult?>(null) }

    var inputText       by remember { mutableStateOf("") }
    var sendMessage     by remember { mutableStateOf<String?>(null) }
    var responseMessage by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    fun reload() {
        events      = bridge.loadEvents(projectId)
        replayState = bridge.replayState(projectId)

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

        // Header section (inline)
        Text(
            text = "Project: $projectId",
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            style = MaterialTheme.typography.headlineSmall
        )

        // Stable tree structure - no early returns
        if (replayState == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val replay = replayState

            // State panel section - ALL SYSTEM STATE from replay.* only
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text("Governance: ${replay.governanceView.totalContracts} contracts", style = MaterialTheme.typography.bodySmall)
                
                // Execution state from executionView.executionStatus ONLY (NO derivation)
                replay.executionView?.let { execView ->
                    Text("Execution: ${execView.executionStatus}", style = MaterialTheme.typography.bodySmall)
                }
                
                Text("Audit: ${replay.auditView.contracts.valid}", style = MaterialTheme.typography.bodySmall)
                
                // Interaction result - NOT system state, interaction feedback only
                interactionResult?.let {
                    Text("Interaction: ${it.content}", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Event list - READ-ONLY display (NO state derivation)
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
                    items(events) { event ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            colors = CardDefaults.cardColors(containerColor = Surface)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("type=${event.type}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "payload=${event.payload}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }

            // Commit panel - use executionView.showCommitPanel ONLY (NO boolean composition)
            replay.executionView?.let { ev ->
                if (ev.showCommitPanel) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Commit Pending", style = MaterialTheme.typography.titleMedium)
                            replay.governanceView.lastEventPayload["report_reference"]?.let {
                                Text("Report: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            replay.governanceView.lastEventPayload["finalArtifactReference"]?.let {
                                Text("Artifact: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            @Suppress("UNCHECKED_CAST")
                            (replay.governanceView.lastEventPayload["proposedActions"] as? List<String>)?.let { actions ->
                                if (actions.isNotEmpty()) {
                                    Text("Actions: ${actions.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Row(modifier = Modifier.padding(top = 8.dp)) {
                                Button(
                                    onClick = {
                                        bridge.approveContracts(projectId)
                                        reload()
                                    },
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text("Approve")
                                }
                                Button(
                                    onClick = { reload() }
                                ) {
                                    Text("Reject")
                                }
                            }
                        }
                    }
                }
            }

            // Action bar - use replay.governanceView ONLY
            replay.executionView?.let {
                if (replay.governanceView.totalContracts > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                bridge.approveContracts(projectId)
                                reload()
                            }
                        ) {
                            Text("Approve Contracts")
                        }
                    }
                }
            }

            // UI feedback messages (ephemeral state, NOT system state)
            responseMessage?.let {
                Text(it, modifier = Modifier.padding(8.dp))
            }

            sendMessage?.let {
                Text(it, color = Color.Red, modifier = Modifier.padding(8.dp))
            }

            // Input bar section (inline)
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    placeholder = { Text("Enter command...") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { handleUserInput(inputText) }),
                    singleLine = true
                )
                Button(
                    onClick = { handleUserInput(inputText) }
                ) {
                    Text("Send")
                }
            }
        }
    }
}
