package agoii.ui.screens

import agoii.ui.bridge.BridgeContract
import agoii.ui.bridge.UIReplayState
import agoii.ui.core.StateProjection
import agoii.ui.layout.ScreenScaffold
import agoii.ui.theme.OnSurface

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Dashboard screen composable.
 *
 * Provides a high-level overview of the system state: governance,
 * execution, and assembly status derived from [UIReplayState.auditView].
 *
 * @param projectId The project identifier to display.
 * @param bridge    The bridge contract for core communication.
 */
@Composable
fun DashboardScreen(projectId: String, bridge: BridgeContract) {

    var replayState by remember { mutableStateOf<UIReplayState?>(null) }
    var error       by remember { mutableStateOf<String?>(null) }

    val projection = remember { StateProjection() }

    LaunchedEffect(projectId) {
        try {
            replayState = bridge.replayState(projectId)
        } catch (e: Exception) {
            error = e.message
        }
    }

    val replay = replayState

    ScreenScaffold {
        Text(
            text = "Dashboard: $projectId",
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            style = MaterialTheme.typography.headlineSmall
        )

        if (replay == null) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
        }

        replay?.let {
            val uiState = projection.project(it)

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {

                Text(
                    "GOVERNANCE",
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurface.copy(alpha = 0.6f)
                )
                Text(
                    "Contracts: ${it.auditView.contracts.totalContracts}",
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "EXECUTION",
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurface.copy(alpha = 0.6f)
                )
                Text(
                    when {
                        uiState.executionCompleted -> "Completed"
                        uiState.executionStarted   -> "Running"
                        else                        -> "Not started"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "ASSEMBLY",
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurface.copy(alpha = 0.6f)
                )
                Text(
                    when {
                        uiState.assemblyCompleted  -> "Assembled"
                        uiState.assemblyValidated  -> "Validated"
                        uiState.assemblyStarted    -> "Assembling"
                        else                        -> "Not started"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
