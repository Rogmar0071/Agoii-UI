package agoii.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
