package com.agoii.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.core.view.WindowCompat
import com.agoii.mobile.core.CrashHandler
import com.agoii.mobile.ui.theme.AgoiiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔴 Install crash handler FIRST
        CrashHandler.install(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            AgoiiTheme {

                try {
                    ProjectScreen(projectId = "agoii-project-001")
                } catch (e: Exception) {
                    // 🔴 Fallback UI so app doesn't silently die
                    Text("UI Crash: ${e.message}")
                }

            }
        }
    }
}
