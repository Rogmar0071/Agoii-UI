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
import com.agoii.mobile.core.LedgerValidationException
import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.core.ReplayVerification
import com.agoii.mobile.interaction.InteractionContract
import com.agoii.mobile.interaction.InteractionEngine
import com.agoii.mobile.interaction.InteractionInput
import com.agoii.mobile.interaction.InteractionResult
import com.agoii.mobile.interaction.OutputType
import com.agoii.mobile.ui.theme.*

/**
 * ProjectScreen — the single screen composable.
 *
 * Layout (fixed, per spec):
 *   HEADER          — project_id + audit status
 *   STATE PANEL     — derived from replay only (executionValid/assemblyValid/icsValid)
 *   EVENT LIST      — raw ledger events (scrollable, coloured by phase)
 *   COMMIT PANEL    — shown when COMMIT_CONTRACT is PENDING (propose actions + approve/reject)
 *   ACTION BAR      — conditional APPROVE (contracts gate)
 *   INPUT BAR       — text field + SEND
 *
 * UI Rules:
 *  - No auto-execution loops.
 *  - No background processing.
 *  - Each user action = exactly ONE core operation.
 *  - After every action: reload events, state, and audit from bridge.
 *  - No caching.
 *  - UI reads from EventLedger ONLY (via bridge).
 *  - NO business logic in UI.
 *  - NO direct execution from UI.
 */
@Composable
fun ProjectScreen(projectId: String) {
    val context = LocalContext.current
    val bridge  = remember { CoreBridge(context) }
    val interactionEngine = remember { InteractionEngine() }

    // ── UI state — all derived from ledger, never directly mutated ──────────
    var events            by remember { mutableStateOf(emptyList<Event>()) }
    var replayState       by remember { mutableStateOf<ReplayStructuralState?>(null) }
    var auditResult       by remember { mutableStateOf<AuditResult?>(null) }
    var verification      by remember { mutableStateOf<ReplayVerification?>(null) }
    var interactionResult by remember { mutableStateOf<InteractionResult?>(null) }
    var inputText         by remember { mutableStateOf("") }
    var sendMessage       by remember { mutableStateOf<String?>(null) }
    var responseMessage   by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    /** Reload ALL data from the bridge after every action. No caching. */
    fun reload() {
        events       = bridge.loadEvents(projectId)
        replayState  = bridge.replayState(projectId)
        if (events.isNotEmpty()) {
            auditResult  = bridge.auditLedger(projectId)
            verification = bridge.verifyReplay(projectId)
            interactionResult = replayState?.let { state ->
                interactionEngine.execute(
                    InteractionContract(
                        contractId = projectId,
                        query      = "system state",
                        outputType = OutputType.DETAILED
                    ),
                    InteractionInput(state)
                )
            }
        } else {
            auditResult       = null
            verification      = null
            interactionResult = null
        }
    }

    /**
     * Single ICS interaction entry point (AGOII-RCF-ICS-ENFORCEMENT-LOCK-01).
     *
     * Forwards user input to CoreBridge; displays the validated response.
     * Errors display the EXACT exception message — no generic masking.
     */
    fun handleUserInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        try {
            val response = bridge.processInteraction(projectId, trimmed)
            inputText = ""
            sendMessage = null
            responseMessage = response
            reload()
        } catch (e: LedgerValidationException) {
            sendMessage = e.message ?: "Unknown error"
            responseMessage = null
        } catch (e: Exception) {
            sendMessage = e.message ?: "Unknown error"
            responseMessage = null
        }
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
            .imePadding()
    ) {
        // ── HEADER ──────────────────────────────────────────────────────────
        Header(projectId = projectId, auditResult = auditResult)

        // ── STATE PANEL — only rendered when ledger has events (truth guard) ──
        if (events.isNotEmpty()) {
            StatePanel(
                verification      = verification,
                replayState       = replayState,
                interactionResult = interactionResult,
                events            = events
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

        // ── COMMIT PANEL — shown only when COMMIT_CONTRACT is PENDING ────────
        val commitPending = replayState?.commitPending == true
        if (commitPending) {
            // Read commit metadata directly from the event payload (Replay is boolean-only)
            val commitEvent = events.lastOrNull { it.type == EventTypes.COMMIT_CONTRACT }
            val commitReportRef      = commitEvent?.payload?.get("report_reference")?.toString() ?: ""
            val commitArtifactRef    = commitEvent?.payload?.get("finalArtifactReference")?.toString() ?: ""
            @Suppress("UNCHECKED_CAST")
            val commitActions        = (commitEvent?.payload?.get("proposedActions") as? List<String>) ?: emptyList()
            CommitPanel(
                reportReference        = commitReportRef,
                finalArtifactReference = commitArtifactRef,
                proposedActions        = commitActions,
                onApprove              = {
                    bridge.signalCommitApproval(projectId)
                    reload()
                },
                onReject               = {
                    bridge.signalCommitRejection(projectId)
                    reload()
                }
            )
        }

        // ── COMMIT RESULT FEEDBACK ───────────────────────────────────────────
        if (replayState?.commitExecuted == true || replayState?.commitAborted == true) {
            CommitResultBanner(approved = replayState?.commitExecuted == true)
        }

        // ── ACTION BAR ──────────────────────────────────────────────────────
        // showApprove: contracts exist but execution hasn't started yet →
        // user must approve contracts (CONTRACTS_APPROVED gate) to begin execution.
        // Interaction contracts (type="interaction") are conversational — no approval.
        val lastContractType = events
            .lastOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
            ?.payload?.get("type")?.toString()
        val showApprove = replayState?.contracts?.valid == true &&
                          replayState?.execution?.assignedTasks == 0 &&
                          lastContractType != "interaction"

        ActionBar(
            showApprove   = showApprove,
            onApprove     = {
                bridge.approveContracts(projectId)
                reload()
            }
        )

        // ── INPUT BAR ───────────────────────────────────────────────────────
        responseMessage?.let { msg ->
            Text(
                text     = msg,
                color    = OnSurface,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        sendMessage?.let { msg ->
            Text(
                text     = msg,
                color    = Color(0xFFD32F2F),
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
        InputBar(
            text         = inputText,
            onTextChange = { inputText = it; sendMessage = null; responseMessage = null },
            onSend       = { handleUserInput(inputText) }
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
private fun StatePanel(
    verification:      ReplayVerification?,
    replayState:       ReplayStructuralState?,
    interactionResult: InteractionResult?,
    events:            List<Event>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("STATE  (from replay)", color = OnSurface.copy(alpha = 0.6f), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(4.dp))

        // ── Lifecycle truth layer ─────────────────────────────────────────────
        if (replayState != null) {
            val execColor   = if (replayState.executionValid) EventComplete else OnSurface.copy(alpha = 0.5f)
            val asmColor    = if (replayState.assemblyValid)  EventComplete else OnSurface.copy(alpha = 0.5f)
            val icsColor    = if (replayState.icsValid)       EventComplete else OnSurface.copy(alpha = 0.5f)
            val commitColor = if (replayState.commitValid)    EventComplete else OnSurface.copy(alpha = 0.5f)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("executionValid=${replayState.executionValid}", color = execColor,   style = MonoStyle, fontSize = 10.sp)
                Text("assemblyValid=${replayState.assemblyValid}",  color = asmColor,    style = MonoStyle, fontSize = 10.sp)
                Text("icsValid=${replayState.icsValid}",            color = icsColor,    style = MonoStyle, fontSize = 10.sp)
                Text("commitValid=${replayState.commitValid}",      color = commitColor, style = MonoStyle, fontSize = 10.sp)
            }
            // Per-contract execution status
            val exec = replayState.execution
            if (exec.totalTasks > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text  = "tasks: assigned=${exec.assignedTasks}  completed=${exec.completedTasks}" +
                            "  success=${exec.successfulTasks}/${exec.totalTasks}",
                    color = OnSurface.copy(alpha = 0.7f),
                    style = MonoStyle,
                    fontSize = 10.sp
                )
            }
            // ICS output reference when available
            if (replayState.icsCompleted) {
                val icsEvent = events.lastOrNull { it.type == EventTypes.ICS_COMPLETED }
                val icsOutputRef = icsEvent?.payload?.get("icsOutputReference")?.toString() ?: ""
                if (icsOutputRef.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text  = "ics_output=$icsOutputRef",
                        color = EventComplete.copy(alpha = 0.8f),
                        style = MonoStyle,
                        fontSize = 10.sp
                    )
                }
            }
        }

        if (interactionResult != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text  = interactionResult.content,
                color = OnBackground,
                style = MonoStyle,
                fontSize = 11.sp
            )
        } else if (replayState == null) {
            Text(
                text     = "Loading…",
                color    = OnSurface.copy(alpha = 0.4f),
                style    = MonoStyle,
                fontSize = 11.sp
            )
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

// ── EVENT ROW ────────────────────────────────────────────────────────────────

/** Payload keys that are rendered in dedicated panels rather than the generic event row. */
private val PAYLOAD_KEYS_EXCLUDED_FROM_ROW = setOf("proposedActions")

private fun eventColor(type: String): Color = when {
    type == EventTypes.INTENT_SUBMITTED ||
    type == EventTypes.CONTRACTS_GENERATED ||
    type == EventTypes.CONTRACTS_READY ||
    type == EventTypes.CONTRACTS_APPROVED  -> EventApproval
    type == EventTypes.RECOVERY_CONTRACT ||
    type == EventTypes.CONTRACT_FAILED     -> Color(0xFFD32F2F)
    type == EventTypes.TASK_EXECUTED        -> EventExecution
    type == EventTypes.ASSEMBLY_COMPLETED ||
    type == EventTypes.ICS_COMPLETED       -> EventComplete
    type == EventTypes.COMMIT_CONTRACT     -> Color(0xFFFFA726)  // amber — awaiting approval
    type == EventTypes.COMMIT_EXECUTED     -> EventComplete
    type == EventTypes.COMMIT_ABORTED      -> Color(0xFFD32F2F)
    else                                   -> EventSystem
}

@Composable
private fun EventRow(event: Event) {
    val color = eventColor(event.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.4f), shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column {
            Text(text = event.type, color = color, style = MonoStyle, fontSize = 12.sp)
            if (event.payload.isNotEmpty()) {
                Text(
                    text  = event.payload.entries
                        .filter { it.key !in PAYLOAD_KEYS_EXCLUDED_FROM_ROW }
                        .joinToString(" | ") { "${it.key}=${it.value}" },
                    color = OnSurface.copy(alpha = 0.7f),
                    style = MonoStyle,
                    fontSize = 10.sp
                )
            }
        }
    }
}

// ── COMMIT PANEL ─────────────────────────────────────────────────────────────

@Composable
private fun CommitPanel(
    reportReference:        String,
    finalArtifactReference: String,
    proposedActions:        List<String>,
    onApprove:              () -> Unit,
    onReject:               () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFA726).copy(alpha = 0.12f))
            .border(1.dp, Color(0xFFFFA726).copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text  = "COMMIT CONTRACT — APPROVAL REQUIRED",
            color = Color(0xFFFFA726),
            style = LabelStyle,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text("report_reference=$reportReference",            color = OnSurface.copy(alpha = 0.7f), style = MonoStyle, fontSize = 10.sp)
        Text("finalArtifactReference=$finalArtifactReference", color = OnSurface.copy(alpha = 0.7f), style = MonoStyle, fontSize = 10.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text("PROPOSED ACTIONS:", color = OnSurface.copy(alpha = 0.6f), style = MonoStyle, fontSize = 10.sp)
        proposedActions.forEachIndexed { i, action ->
            Text("  ${i + 1}. $action", color = OnBackground, style = MonoStyle, fontSize = 10.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick  = onApprove,
                modifier = Modifier.weight(1f),
                colors   = ButtonDefaults.buttonColors(containerColor = EventComplete)
            ) {
                Text("APPROVE COMMIT", color = Color.White, fontSize = 12.sp)
            }
            Button(
                onClick  = onReject,
                modifier = Modifier.weight(1f),
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) {
                Text("REJECT COMMIT", color = Color.White, fontSize = 12.sp)
            }
        }
    }
    Divider(color = Surface, thickness = 1.dp)
}

// ── COMMIT RESULT BANNER ─────────────────────────────────────────────────────

@Composable
private fun CommitResultBanner(approved: Boolean) {
    val (color, label) = if (approved) {
        EventComplete to "✓ COMMIT EXECUTED — real-world action triggered"
    } else {
        Color(0xFFD32F2F) to "✗ COMMIT ABORTED — no real-world action taken"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = color, style = MonoStyle, fontSize = 11.sp)
    }
    Divider(color = Surface, thickness = 1.dp)
}

// ── ACTION BAR ───────────────────────────────────────────────────────────────

@Composable
private fun ActionBar(
    showApprove: Boolean,
    onApprove:   () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showApprove) {
            Button(
                onClick  = onApprove,
                modifier = Modifier.fillMaxWidth(),
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
            .navigationBarsPadding()
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
