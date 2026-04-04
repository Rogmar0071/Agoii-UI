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
    var sendMessage     by remember { mutableStateOf<String?>(null) }
    var responseMessage by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    fun reload() {
        replayState = bridge.replayState(projectId)
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
            replay.governanceView.let {
                Text("Governance: ${it.totalContracts} contracts", style = MaterialTheme.typography.bodySmall)
            }
            
            // SECTION E: No fallback logic - strict execution status from executionView
            replay.executionView?.let { execView ->
                execView.taskStatus.values.firstOrNull { it == "EXECUTED_FAILURE" || it == "FAILED" }?.let {
                    Text("Execution: failed", style = MaterialTheme.typography.bodySmall)
                } ?: run {
                    if (execView.taskStatus.values.all { it == "COMPLETED" || it == "VALIDATED" } && execView.taskStatus.isNotEmpty()) {
                        Text("Execution: success", style = MaterialTheme.typography.bodySmall)
                    } else if (execView.taskStatus.isNotEmpty()) {
                        Text("Execution: running", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            
            replay.auditView.let {
                Text("Audit: ${it.contracts.valid}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Commit panel section (inline) - SECTION D: Use replay.governanceView only
        val ev = replay.executionView
        val commitPending = ev != null &&
                ev.commitContractExists &&
                !ev.commitExecuted &&
                !ev.commitAborted

        if (commitPending) {
            val lastPayload = replay.governanceView.lastEventPayload
            val reportRef = lastPayload["report_reference"]?.toString()
            val artifactRef = lastPayload["finalArtifactReference"]?.toString()

            @Suppress("UNCHECKED_CAST")
            val actions = lastPayload["proposedActions"] as? List<String>

            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Commit Pending", style = MaterialTheme.typography.titleMedium)
                    reportRef?.let {
                        Text("Report: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    artifactRef?.let {
                        Text("Artifact: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    actions?.let {
                        if (it.isNotEmpty()) {
                            Text("Actions: ${it.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
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
                            onClick = {
                                reload()
                            }
                        ) {
                            Text("Reject")
                        }
                    }
                }
            }
        }

        // Action bar section (inline) - SECTION D: Use replay.governanceView only
        val gv = replay.governanceView
        val showApprove = gv.totalContracts > 0 && replay.executionView != null

        if (showApprove) {
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
