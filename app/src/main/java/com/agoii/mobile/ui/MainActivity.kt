package com.agoii.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.agoii.mobile.ui.theme.AgoiiTheme

/**
 * Single-activity entry point.
 * Renders ProjectScreen for the default project.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AgoiiTheme {
                ProjectScreen(projectId = "agoii-project-001")
            }
        }
    }
}
