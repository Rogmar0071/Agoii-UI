package com.agoii.mobile.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.agoii.mobile.bridge.CoreBridge
import com.agoii.mobile.bridge.CoreBridgeAdapter
import com.agoii.mobile.core.CrashHandler
import com.agoii.mobile.ui.theme.AgoiiTheme
import agoii.ui.core.AuditView
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
                        Log.e("AGOII_TRACE", "ROOT_SEND_TRIGGERED: $input")
                        scope.launch {
                            try {
                                Log.e("AGOII_TRACE", "SEND_FLOW_START")
                                val updated = withContext(Dispatchers.IO) {
                                    dispatcher.sendInteraction(input)
                                    Log.e("AGOII_TRACE", "DISPATCH_RETURNED")
                                    binder.getUiModel()
                                }
                                Log.e("AGOII_TRACE", "MODEL_RECEIVED")
                                model = updated
                                Log.e("AGOII_TRACE", "MODEL_APPLIED")
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
}

