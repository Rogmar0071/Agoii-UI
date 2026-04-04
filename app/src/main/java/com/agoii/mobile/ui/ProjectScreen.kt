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

    var replayState       by remember { mutableStateOf<ReplayStructuralState?>(null) }

    var inputText       by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    fun reload() {
        replayState = bridge.replayState(projectId)
    }

    fun handleUserInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        try {
            bridge.processInteraction(projectId, trimmed)
            inputText = ""
            reload()
        } catch (e: Exception) {
            // Errors not displayed per SECTION B (no parallel state)
        }
    }

    LaunchedEffect(projectId) { reload() }

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

        // SECTION C: Single entry point - mandatory guard
        val replay = replayState ?: run {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        // State panel section (inline) - SECTION D: Strict read from replay only
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("Governance: ${replay.governanceView.totalContracts} contracts", style = MaterialTheme.typography.bodySmall)
            
            // SECTION E: No fallback logic - direct read only
            replay.executionView?.let { execView ->
                execView.taskStatus.values.firstOrNull { it == "EXECUTED_FAILURE" || it == "FAILED" }?.let {
                    Text("Execution: failed", style = MaterialTheme.typography.bodySmall)
                }
                if (execView.taskStatus.values.all { it == "COMPLETED" || it == "VALIDATED" } && execView.taskStatus.isNotEmpty()) {
                    Text("Execution: success", style = MaterialTheme.typography.bodySmall)
                }
                if (execView.taskStatus.values.none { it == "EXECUTED_FAILURE" || it == "FAILED" || it == "COMPLETED" || it == "VALIDATED" } && execView.taskStatus.isNotEmpty()) {
                    Text("Execution: running", style = MaterialTheme.typography.bodySmall)
                }
            }
            
            Text("Audit: ${replay.auditView.contracts.valid}", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.weight(1f))

        // Commit panel section (inline) - SECTION D: Use replay only, SECTION A: No gating
        replay.executionView?.let { ev ->
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
                        Text("Actions: ${actions.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
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

        // Action bar section (inline) - SECTION D: Use replay only, SECTION A: No gating  
        replay.executionView?.let {
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
