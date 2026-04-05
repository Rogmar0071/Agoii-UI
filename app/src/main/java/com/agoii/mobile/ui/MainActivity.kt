package com.agoii.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.agoii.mobile.bridge.CoreBridgeAdapter
import com.agoii.mobile.core.CrashHandler
import agoii.ui.bridge.CoreBridge
import agoii.ui.screens.ProjectScreen

/**
 * Main activity — the sole UI entry point.
 *
 * CONTRACT: MQP-UI-FINAL-CONSOLIDATED Phase 5C
 *
 * Flow: CoreBridge(CoreBridgeAdapter) → ProjectScreen
 *
 * - Injects [CoreBridgeAdapter] as the [BridgeContract] implementation
 * - Renders the ui-module's [ProjectScreen] composable
 * - Contains ZERO business logic beyond wiring
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CrashHandler.install(applicationContext)

        val adapter = CoreBridgeAdapter(applicationContext)
        val bridge = CoreBridge(adapter)

        setContent {
            ProjectScreen(
                projectId = "default",
                bridge = bridge
            )
        }
    }
}
