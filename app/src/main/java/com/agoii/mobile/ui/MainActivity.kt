package com.agoii.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.agoii.mobile.core.CrashHandler
import com.agoii.mobile.ui.theme.AgoiiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔴 Install crash handler FIRST
        CrashHandler.install(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            AgoiiTheme {
                // Temporary minimal UI (remove ProjectScreen risk)
                androidx.compose.material3.Text("Agoii Core Running")
            }
        }
    }
}
