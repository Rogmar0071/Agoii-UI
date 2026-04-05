package com.agoii.mobile.ui

import android.os.Bundle
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
            AgoiiTheme {
                var currentProjectId by remember { mutableStateOf("default") }
                var refreshTrigger by remember { mutableIntStateOf(0) }
                val scope = rememberCoroutineScope()

                val adapter = remember(currentProjectId) {
                    CoreBridgeAdapter(systemBridge, currentProjectId)
                }
                val binder = remember(adapter) { UiStateBinder(adapter) }
                val dispatcher = remember(adapter) { UiActionDispatcher(adapter) }

                val model = remember(refreshTrigger, currentProjectId) {
                    binder.getUiModel()
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
                        refreshTrigger++
                    },
                    onInteraction = { input ->
                        scope.launch(Dispatchers.IO) {
                            dispatcher.sendInteraction(input)
                            withContext(Dispatchers.Main) {
                                refreshTrigger++
                            }
                        }
                    },
                    onApproveContract = { contractId ->
                        scope.launch(Dispatchers.IO) {
                            dispatcher.approve(contractId)
                            withContext(Dispatchers.Main) {
                                refreshTrigger++
                            }
                        }
                    }
                )
            }
        }
    }
}

