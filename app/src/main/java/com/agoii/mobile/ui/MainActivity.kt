package com.agoii.mobile.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectAsState
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

                // MQP-UI-REACTIVE-BINDING-v1 — observe the ledger tick emitted by
                // CoreBridge.LedgerObserver after every appendEvent.  Each new tick
                // value triggers the LaunchedEffect below, which reads the fresh
                // ReplayStructuralState on the IO thread and updates the Compose model.
                // No polling. No delays. UI is fully passive.
                val ledgerTick by adapter.ledgerTick.collectAsState()

                // Safe empty defaults — rendered until the first IO read completes.
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

                // Reload the model on every ledger tick (initial load at tick=0 included).
                LaunchedEffect(ledgerTick) {
                    val loaded = withContext(Dispatchers.IO) { binder.getUiModel() }
                    model = loaded
                    Log.e("AGOII_TRACE", "MODEL_APPLIED tick=$ledgerTick")
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
                        // MQP-UI-REACTIVE-BINDING-v1: pure ledger ingress — fire and forget.
                        // The LedgerObserver fires on each appendEvent, bumps ledgerTick,
                        // and the LaunchedEffect above reloads the model automatically.
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                adapter.appendUserMessage(input)
                            }
                        }
                    },
                    onApproveContract = { contractId ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                dispatcher.approve(contractId)
                            }
                        }
                    }
                )
            }
        }
    }
}

