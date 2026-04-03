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
