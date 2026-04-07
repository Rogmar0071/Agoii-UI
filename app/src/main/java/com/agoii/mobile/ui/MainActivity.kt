package com.agoii.mobile.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.agoii.mobile.bridge.CoreBridge
import com.agoii.mobile.bridge.CoreBridgeAdapter
import com.agoii.mobile.core.CrashHandler
import com.agoii.mobile.ui.theme.AgoiiTheme
import agoii.ui.core.AuditView
import agoii.ui.core.ChatMessage
import agoii.ui.core.ChatUiModel
import agoii.ui.core.ExecutionView
import agoii.ui.core.GovernanceView
import agoii.ui.core.UiModel
import agoii.ui.core.UiStateBinder
import agoii.ui.core.UiActionDispatcher
import agoii.ui.core.ProjectDescriptor
import agoii.ui.screens.MainScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CrashHandler.install(applicationContext)

        val systemBridge = CoreBridge(applicationContext)

        setContent {
            Log.e("AGOII_TRACE", "SET_CONTENT_ROOT")
            AgoiiTheme {
                var currentProjectId by remember { mutableStateOf("default") }
                val scope = rememberCoroutineScope()

                val adapter = remember(currentProjectId) {
                    CoreBridgeAdapter(systemBridge, currentProjectId)
                }
                val binder = remember(adapter) { UiStateBinder(adapter) }
                val dispatcher = remember(adapter) { UiActionDispatcher(adapter) }

                // Safe empty defaults — no IO on the composition (Main) thread.
                var model by remember {
                    mutableStateOf(
                        UiModel(
                            governance = GovernanceView(),
                            execution  = ExecutionView(),
                            audit      = AuditView(),
                            chat       = ChatUiModel(messages = emptyList(), currentInput = "")
                        )
                    )
                }

                // Load initial state from IO thread; update model on Main thread.
                LaunchedEffect(binder) {
                    val loaded = withContext(Dispatchers.IO) { binder.getUiModel() }
                    model = loaded
                }

                val projects = remember {
                    listOf(ProjectDescriptor("default", "Default Project"))
                }

                MainScreen(
                    model = model,
                    projects = projects,
                    selectedProjectId = currentProjectId,
                    onSelectProject = { project ->
                        currentProjectId = project.id
                    },
                    onInteraction = { input ->
                        Log.e("AGOII_TRACE", "UI_SEND: $input")
                        scope.launch {
                            try {
                                // 🔴 ONLY WRITE TO LEDGER — UI is a pure emitter
                                withContext(Dispatchers.IO) { adapter.appendUserMessage(input) }

                                // 🔴 POLL REPLAY until execution produces SYSTEM_MESSAGE_EMITTED
                                // or timeout. Spine is async; we must wait for it so the UI
                                // surface reflects the completed execution cycle.
                                val startMs = System.currentTimeMillis()
                                var snapshot = withContext(Dispatchers.IO) { binder.getUiModel() }
                                while (System.currentTimeMillis() - startMs < EXECUTION_POLL_TIMEOUT_MS) {
                                    val last = snapshot.audit.lastEventType
                                    if (last != null && last in EXECUTION_TERMINAL_STATES) break
                                    delay(EXECUTION_POLL_INTERVAL_MS)
                                    snapshot = withContext(Dispatchers.IO) { binder.getUiModel() }
                                }
                                model = snapshot
                                Log.e("AGOII_TRACE", "MODEL_APPLIED")

                            } catch (t: Throwable) {
                                Log.e("AGOII_FATAL_CRASH", t.stackTraceToString())
                                model = UiModel(
                                    governance = GovernanceView(
                                        lastEventType = "UI_ERROR",
                                        lastEventPayload = t.message ?: "UNKNOWN"
                                    ),
                                    execution = ExecutionView(
                                        executionStatus = "failed"
                                    ),
                                    audit = AuditView(
                                        finalOutput = t.stackTraceToString()
                                    ),
                                    chat = ChatUiModel(
                                        messages = listOf(
                                            ChatMessage(
                                                id = "ui_error",
                                                text = "CRASH: " + (t.message ?: "Unknown error"),
                                                isUser = false
                                            )
                                        ),
                                        currentInput = ""
                                    )
                                )
                            }
                        }
                    },
                    onApproveContract = { contractId ->
                        scope.launch {
                            try {
                                val updated = withContext(Dispatchers.IO) {
                                    dispatcher.approve(contractId)
                                    binder.getUiModel()
                                }
                                model = updated
                            } catch (t: Throwable) {
                                Log.e("AGOII_FATAL", t.stackTraceToString())
                                model = model.copy(
                                    audit = model.audit.copy(
                                        lastEventType = "ERROR",
                                        lastEventPayload = t.message ?: "Unknown error",
                                        executionStatus = "failed",
                                        finalOutput = t.stackTraceToString()
                                    )
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    companion object {
        /** Terminal event types that signal the execution spine has completed a cycle. */
        private val EXECUTION_TERMINAL_STATES = setOf(
            "system_message_emitted",
            "execution_completed",
            "assembly_completed",
            "ics_completed"
        )

        /** Maximum time (ms) to poll for execution completion before returning the current snapshot. */
        private const val EXECUTION_POLL_TIMEOUT_MS = 30_000L

        /** Polling interval (ms) between replay state reads while waiting for execution. */
        private const val EXECUTION_POLL_INTERVAL_MS = 200L
    }
}

