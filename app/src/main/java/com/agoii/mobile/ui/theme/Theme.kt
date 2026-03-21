package com.agoii.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Palette ─────────────────────────────────────────────────────────────────
val Background     = Color(0xFF0D0D0D)
val Surface        = Color(0xFF1A1A1A)
val SurfaceVariant = Color(0xFF242424)
val OnBackground   = Color(0xFFE0E0E0)
val OnSurface      = Color(0xFFBDBDBD)
val Primary        = Color(0xFF4FC3F7)   // accent blue

// Event-bubble colours (spec-defined)
val EventSystem    = Color(0xFF424242)   // grey   – system events
val EventExecution = Color(0xFF1565C0)   // blue   – execution events
val EventApproval  = Color(0xFFE65100)   // orange – approval events
val EventComplete  = Color(0xFF2E7D32)   // green  – completion events

private val DarkColors = darkColorScheme(
    primary        = Primary,
    background     = Background,
    surface        = Surface,
    onBackground   = OnBackground,
    onSurface      = OnSurface,
    onPrimary      = Color.Black
)

@Composable
fun AgoiiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content     = content
    )
}

/** Monospace style used for event rows in the ledger viewer. */
val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize   = 12.sp,
    color      = OnSurface
)

/** Label style used for state panel values. */
val LabelStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize   = 13.sp,
    fontWeight = FontWeight.Medium,
    color      = OnBackground
)
