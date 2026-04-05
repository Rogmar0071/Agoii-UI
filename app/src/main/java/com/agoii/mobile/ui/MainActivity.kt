package com.agoii.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.agoii.mobile.bridge.CoreBridgeAdapter
import com.agoii.mobile.core.CrashHandler
import agoii.ui.bridge.UiBridgeAdapter
import agoii.ui.core.UiStateBinder
import agoii.ui.core.ProjectDescriptor
import agoii.ui.screens.MainScreen

/**
 * Main activity — the sole UI entry point.
 *
 * CONTRACT: MQP-UI-REPLACEMENT-AUTHORITATIVE-SWAP-v1 Phase 6
 *
 * Pipeline:
 *   CoreBridge.replayState()
 *     → CoreBridgeAdapter (maps core → UI types)
 *     → UiStateBinder (binds ReplayStructuralState → UiModel)
 *     → MainScreen (renders UiModel)
 *
 * Contains ZERO business logic beyond wiring.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CrashHandler.install(applicationContext)

        val adapter = CoreBridgeAdapter(applicationContext)
        adapter.bindProject("default")

        val bridgeAdapter = UiBridgeAdapter(adapter)
        val stateBinder = UiStateBinder(adapter)

        setContent {
            val model = remember { stateBinder.getUiModel() }
            val projects = remember {
                listOf(ProjectDescriptor(id = "default", name = "Default Project"))
            }

            MainScreen(
                model = model,
                projects = projects,
                selectedProjectId = "default",
                onSelectProject = { /* single project — no-op */ },
                onInteraction = { input -> bridgeAdapter.interact(input) },
                onApproveContract = { contractId -> bridgeAdapter.approve(contractId) }
            )
        }
    }
}
