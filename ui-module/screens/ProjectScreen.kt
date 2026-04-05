package agoii.ui.screens

import agoii.ui.bridge.BridgeContract
import agoii.ui.bridge.UIEvent
import agoii.ui.bridge.UIReplayState
import agoii.ui.components.ActionBarComponent
import agoii.ui.components.EventListComponent
import agoii.ui.components.FeedbackComponent
import agoii.ui.components.StatePanelComponent
import agoii.ui.layout.ScreenScaffold
import agoii.ui.theme.Background

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Main project screen composable.
 *
 * Renders the full project view: header, state panel, event list,
 * feedback area, and input bar. All system state is derived from
 * [UIReplayState] via the [BridgeContract].
 *
 * @param projectId The project identifier to load.
 * @param bridge    The bridge contract for core communication.
 */
@Composable
fun ProjectScreen(projectId: String, bridge: BridgeContract) {

    var events      by remember { mutableStateOf(emptyList<UIEvent>()) }
    var replayState by remember { mutableStateOf<UIReplayState?>(null) }

    var inputText       by remember { mutableStateOf("") }
    var responseMessage by remember { mutableStateOf<String?>(null) }
    var sendError       by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    fun reload() {
        try {
            events = bridge.loadEvents(projectId)
            replayState = bridge.replayState(projectId)
        } catch (e: Exception) {
            sendError = e.message
        }
    }

    fun handleUserInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        try {
            val response = bridge.processInteraction(projectId, trimmed)
            inputText = ""
            responseMessage = response
            sendError = null
            reload()
        } catch (e: Exception) {
            sendError = e.message
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
    val response = responseMessage
    val error = sendError

    ScreenScaffold {
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

        // STATE PANEL
        replay?.let {
            StatePanelComponent(
                eventsCount = events.size,
                lastEventType = it.governanceView.lastEventType,
                interactionContent = null
            )
        }

        // EVENT LIST
        EventListComponent(
            events = events,
            listState = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp)
        )

        // FEEDBACK
        FeedbackComponent(
            response = response,
            error = error
        )

        // INPUT BAR
        ActionBarComponent(
            inputText = inputText,
            onInputChange = { inputText = it },
            onSend = { handleUserInput(inputText) }
        )
    }
}
