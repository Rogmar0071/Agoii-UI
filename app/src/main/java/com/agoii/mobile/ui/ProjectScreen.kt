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
import com.agoii.mobile.core.AuditResult
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.Governor
import com.agoii.mobile.core.ReplayState
import com.agoii.mobile.core.ReplayVerification
import com.agoii.mobile.ui.theme.*

/**
 * ProjectScreen — the single screen composable.
 *
 * Layout (fixed, per spec):
 *   HEADER      — project_id + audit status
 *   STATE PANEL — derived from replay only
 *   EVENT LIST  — raw ledger events (scrollable, no grouping)
 *   ACTION BAR  — RUN STEP + conditional APPROVE
 *   INPUT BAR   — text field + SEND
 *
 * UI Rules:
 *  - No auto-execution loops.
 *  - No background processing.
 *  - Each user action = exactly ONE core operation.
 *  - After every action: reload events, state, and audit from bridge.
 *  - No caching.
 */
@Composable
fun ProjectScreen(projectId: String) {
    val context = LocalContext.current
    val bridge  = remember { CoreBridge(context) }

    // ── UI state — all derived from ledger, never directly mutated ──────────
    var events       by remember { mutableStateOf(emptyList<Event>()) }
    var replayState  by remember { mutableStateOf<ReplayState?>(null) }
    var auditResult  by remember { mutableStateOf<AuditResult?>(null) }
    var verification by remember { mutableStateOf<ReplayVerification?>(null) }
    var inputText    by remember { mutableStateOf("") }
    var statusMsg    by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    /** Reload ALL data from the bridge after every action. No caching. */
    fun reload() {
        events       = bridge.loadEvents(projectId)
        replayState  = bridge.replayState(projectId)
        auditResult  = bridge.auditLedger(projectId)
        verification = bridge.verifyReplay(projectId)
    }

    // Initial load
    LaunchedEffect(projectId) { reload() }

    // Scroll to bottom when events change
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── HEADER ──────────────────────────────────────────────────────────
        Header(projectId = projectId, auditResult = auditResult)

        // ── STATE PANEL ─────────────────────────────────────────────────────
        StatePanel(state = replayState, verification = verification)

        // ── STATUS MESSAGE ──────────────────────────────────────────────────
        if (statusMsg.isNotBlank()) {
            Text(
                text     = statusMsg,
                color    = Primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                textAlign = TextAlign.Center
            )
        }

        // ── EVENT LIST ──────────────────────────────────────────────────────
        LazyColumn(
            state    = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            if (events.isEmpty()) {
                item {
                    Text(
                        text     = "No events yet. Submit an intent to begin.",
                        color    = OnSurface.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                items(events) { event ->
                    EventRow(event = event)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        // ── ACTION BAR ──────────────────────────────────────────────────────
        val phase = replayState?.phase ?: "idle"
        val showApprove = phase == EventTypes.CONTRACTS_READY

        ActionBar(
            showApprove   = showApprove,
            onRunStep     = {
                val result = bridge.runGovernorStep(projectId)
                statusMsg = when (result) {
                    Governor.GovernorResult.ADVANCED             -> "Governor advanced."
                    Governor.GovernorResult.WAITING_FOR_APPROVAL -> "Waiting for approval."
                    Governor.GovernorResult.COMPLETED            -> "Execution complete."
                    Governor.GovernorResult.NO_EVENT             -> "No event to process."
                }
                reload()
            },
            onApprove     = {
                bridge.approveContracts(projectId)
                statusMsg = "Contracts approved."
                reload()
            }
        )

        // ── INPUT BAR ───────────────────────────────────────────────────────
        InputBar(
            text      = inputText,
            onTextChange = { inputText = it },
            onSend    = {
                val objective = inputText.trim()
                if (objective.isNotEmpty()) {
                    bridge.submitIntent(projectId, objective)
                    inputText = ""
                    statusMsg = "Intent submitted."
                    reload()
                }
            }
        )
    }
}

// ── HEADER ───────────────────────────────────────────────────────────────────

@Composable
private fun Header(projectId: String, auditResult: AuditResult?) {
    val auditColor = when {
        auditResult == null      -> OnSurface
        auditResult.valid        -> EventComplete
        else                     -> Color(0xFFD32F2F)
    }
    val auditLabel = when {
        auditResult == null      -> "–"
        auditResult.valid        -> "VALID"
        else                     -> "INVALID"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            text     = projectId,
            color    = OnBackground,
            fontSize = 14.sp,
            style    = LabelStyle
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(auditColor, shape = RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = auditLabel, color = auditColor, fontSize = 13.sp)
        }
    }
    Divider(color = SurfaceVariant, thickness = 1.dp)
}

// ── STATE PANEL ──────────────────────────────────────────────────────────────

@Composable
private fun StatePanel(state: ReplayState?, verification: ReplayVerification?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("STATE  (from replay)", color = OnSurface.copy(alpha = 0.6f), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StateItem("phase",               state?.phase ?: "idle")
            StateItem("contracts",           if (state != null) "${state.contractsCompleted}/${state.totalContracts}" else "0/0")
            StateItem("exec_started",        state?.executionStarted?.toString() ?: "false")
            StateItem("exec_completed",      state?.executionCompleted?.toString() ?: "false")
        }
        if (verification != null) {
            Spacer(modifier = Modifier.height(4.dp))
            val verifyColor = if (verification.valid) EventComplete else Color(0xFFD32F2F)
            Text(
                text     = "replay_valid=${verification.valid}  |  events_checked=${verification.auditResult.checkedEvents}",
                color    = verifyColor,
                style    = MonoStyle,
                fontSize = 11.sp
            )
            if (verification.invariantErrors.isNotEmpty()) {
                verification.invariantErrors.forEach {
                    Text(text = "⚠ $it", color = Color(0xFFD32F2F), style = MonoStyle, fontSize = 10.sp)
                }
            }
        }
    }
    Divider(color = Surface, thickness = 1.dp)
}

@Composable
private fun StateItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = OnSurface.copy(alpha = 0.5f), fontSize = 10.sp)
        Text(text = value, color = OnBackground,                 fontSize = 12.sp, style = LabelStyle)
    }
}

// ── EVENT ROW ────────────────────────────────────────────────────────────────

@Composable
private fun EventRow(event: Event) {
    val bubbleColor = eventBubbleColor(event.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bubbleColor.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp))
            .border(1.dp, bubbleColor.copy(alpha = 0.4f), shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column {
            Text(text = event.type, color = bubbleColor, style = MonoStyle, fontSize = 12.sp)
            if (event.payload.isNotEmpty()) {
                Text(
                    text  = event.payload.entries.joinToString(" | ") { "${it.key}=${it.value}" },
                    color = OnSurface.copy(alpha = 0.7f),
                    style = MonoStyle,
                    fontSize = 10.sp
                )
            }
        }
    }
}

private fun eventBubbleColor(type: String): Color = when (type) {
    EventTypes.ASSEMBLY_COMPLETED                    -> EventComplete
    EventTypes.CONTRACTS_APPROVED, EventTypes.CONTRACTS_READY -> EventApproval
    EventTypes.CONTRACT_EXECUTED, EventTypes.EXECUTION_STARTED -> EventExecution
    else                                             -> EventSystem  // intent_submitted, contracts_generated
}

// ── ACTION BAR ───────────────────────────────────────────────────────────────

@Composable
private fun ActionBar(
    showApprove: Boolean,
    onRunStep:   () -> Unit,
    onApprove:   () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick  = onRunStep,
            modifier = Modifier.weight(1f),
            colors   = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text("RUN STEP", color = Color.Black, fontSize = 13.sp)
        }

        if (showApprove) {
            Button(
                onClick  = onApprove,
                modifier = Modifier.weight(1f),
                colors   = ButtonDefaults.buttonColors(containerColor = EventApproval)
            ) {
                Text("APPROVE", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

// ── INPUT BAR ────────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    text:         String,
    onTextChange: (String) -> Unit,
    onSend:       () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value         = text,
            onValueChange = onTextChange,
            placeholder   = { Text("Enter objective…", color = OnSurface.copy(alpha = 0.4f), fontSize = 13.sp) },
            modifier      = Modifier.weight(1f),
            singleLine    = true,
            colors        = OutlinedTextFieldDefaults.colors(
                focusedTextColor       = OnBackground,
                unfocusedTextColor     = OnBackground,
                focusedBorderColor     = Primary,
                unfocusedBorderColor   = OnSurface.copy(alpha = 0.3f),
                cursorColor            = Primary
            ),
            textStyle = MonoStyle.copy(fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() })
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onSend,
            colors  = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text("SEND", color = Color.Black, fontSize = 13.sp)
        }
    }
}
