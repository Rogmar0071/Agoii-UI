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
        } catch (e: Exception) {
            // SECTION C: Do not show raw exception messages
            // Execution state is shown deterministically from replay
            val errorMsg = e.message ?: ""
            if (errorMsg != "Execution failed") {
                sendMessage = errorMsg
            }
            responseMessage = null
        } finally {
            reload()
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
        
        auditResult?.let {
            Text(
                text = "Audit: ${it.valid}",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // State panel section (inline)
        if (events.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                verification?.let {
                    Text("Verification: ${if (it.valid) "VALID" else "INVALID"}", style = MaterialTheme.typography.bodySmall)
                }
                replayState?.governanceView?.let { gv ->
                    Text("Governance: ${gv.totalContracts} contracts", style = MaterialTheme.typography.bodySmall)
                }
                
                // SECTION B: Event-driven execution state (CONTRACT AGOII-UI-EXECUTION-STATE-001)
                // Derive execution state from events ONLY, not from derived maps
                val lastTaskStarted = events.lastOrNull { it.type == EventTypes.TASK_STARTED }
                val lastTaskExecuted = events.lastOrNull { it.type == EventTypes.TASK_EXECUTED }
                
                val executionStatus = when {
                    lastTaskStarted == null -> "not_started"
                    lastTaskExecuted == null -> "running"
                    else -> {
                        // Both exist: compare positions using sequenceNumber
                        val startedAfterExecuted = lastTaskStarted.sequenceNumber > lastTaskExecuted.sequenceNumber
                        if (startedAfterExecuted) {
                            "running"
                        } else {
                            // Last execution determines final state (CONTRACT AGOII-UI-EXECUTION-STATE-002)
                            val executionResult = lastTaskExecuted.payload["executionStatus"]?.toString()
                            when (executionResult) {
                                "SUCCESS" -> "success"
                                "FAILURE" -> "failed"
                                else -> "running" // fallback for null/unknown - DO NOT guess success
                            }
                        }
                    }
                }
                Text("Execution: $executionStatus", style = MaterialTheme.typography.bodySmall)
                
                replayState?.auditView?.let {
                    Text("Audit: ${it.contracts.valid}", style = MaterialTheme.typography.bodySmall)
                }
                interactionResult?.let {
                    Text("Interaction: ${it.content}", style = MaterialTheme.typography.bodySmall)
                }
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

        // Commit panel section (inline)
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

            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Commit Pending", style = MaterialTheme.typography.titleMedium)
                    Text("Report: $reportRef", style = MaterialTheme.typography.bodySmall)
                    Text("Artifact: $artifactRef", style = MaterialTheme.typography.bodySmall)
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

        // Action bar section (inline)
        val gv = replayState?.governanceView
        val showApprove = gv != null && gv.totalContracts > 0 && replayState?.executionView != null

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
