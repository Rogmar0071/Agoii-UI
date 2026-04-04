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

        // SECTION C: Single entry point
        val replay = replayState ?: return@Column

        // State panel section (inline) - SECTION D: Strict read from replay only
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            replay.governanceView.let {
                Text("Governance: ${it.totalContracts} contracts", style = MaterialTheme.typography.bodySmall)
            }
            
            // SECTION E: No fallback logic - strict execution status from executionView
            replay.executionView?.let { execView ->
                val executionStatus = when {
                    execView.taskStatus.values.any { it == "EXECUTED_FAILURE" || it == "FAILED" } -> "failed"
                    execView.taskStatus.values.all { it == "COMPLETED" || it == "VALIDATED" } && execView.taskStatus.isNotEmpty() -> "success"
                    execView.taskStatus.isNotEmpty() -> "running"
                    else -> ""
                }
                if (executionStatus.isNotEmpty()) {
                    Text("Execution: $executionStatus", style = MaterialTheme.typography.bodySmall)
                }
            }
            
            replay.auditView.let {
                Text("Audit: ${it.contracts.valid}", style = MaterialTheme.typography.bodySmall)
            }
            
            interactionResult?.let {
                Text("Interaction: ${it.content}", style = MaterialTheme.typography.bodySmall)
            }
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
                items(events) { event ->
                    // EventRow (inline)
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

        // Commit panel section (inline) - SECTION D: Use replay.governanceView only
        val ev = replay.executionView
        val commitPending = ev != null &&
                ev.commitContractExists &&
                !ev.commitExecuted &&
                !ev.commitAborted

        if (commitPending) {
            val lastPayload = replay.governanceView.lastEventPayload
            val reportRef = lastPayload["report_reference"]?.toString() ?: ""
            val artifactRef = lastPayload["finalArtifactReference"]?.toString() ?: ""

            @Suppress("UNCHECKED_CAST")
            val actions = lastPayload["proposedActions"] as? List<String> ?: emptyList()

            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Commit Pending", style = MaterialTheme.typography.titleMedium)
                    if (reportRef.isNotEmpty()) {
                        Text("Report: $reportRef", style = MaterialTheme.typography.bodySmall)
                    }
                    if (artifactRef.isNotEmpty()) {
                        Text("Artifact: $artifactRef", style = MaterialTheme.typography.bodySmall)
                    }
                    if (actions.isNotEmpty()) {
                        Text("Actions: ${actions.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = {
                                bridge.loadEvents(projectId)
                                bridge.approveContracts(projectId)
                                reload()
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Approve")
                        }
                        Button(
                            onClick = {
                                bridge.loadEvents(projectId)
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
