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
import androidx.compose.runtime.Composable
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
        try {
            events = bridge.loadEvents(projectId)
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
        } catch (e: Exception) {
            sendMessage = e.message
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
        if (events.isNotEmpty()) {
            listState.animateScrollToItem(events.size - 1)
        }
    }

    val replay = replayState
    val interaction = interactionResult
    val response = responseMessage
    val sendError = sendMessage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .imePadding()
    ) {

        // HEADER
        Text(
            text = "Project: $projectId",
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            style = MaterialTheme.typography.headlineSmall
        )

        // LOADING
        if (replay == null) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
        }

        // STATE PANEL (STRICT SAFE)
        replay?.let {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {

                Text(
                    "Events Loaded: ${events.size}",
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    "Last Event: ${it.governanceView.lastEventType ?: "none"}",
                    style = MaterialTheme.typography.bodySmall
                )

                interaction?.let { result ->
                    Text(
                        "Interaction: ${result.content}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // EVENT LIST
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
                            Text("type=${event.type}")
                            Text(
                                "payload=${event.payload}",
                                color = OnSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }

        // FEEDBACK
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            response?.let {
                Text(it, modifier = Modifier.padding(8.dp))
            }

            sendError?.let {
                Text(it, color = Color.Red, modifier = Modifier.padding(8.dp))
            }
        }

        // INPUT BAR
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
            Button(onClick = { handleUserInput(inputText) }) {
                Text("Send")
            }
        }
    }
}
