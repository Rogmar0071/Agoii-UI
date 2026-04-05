package agoii.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Agoii Design System — Spacing Scale.
 *
 * Consistent spacing values for padding, margins, and gaps.
 * 4dp base grid.
 */
object AgoiiSpacing {

    // ── Base units ────────────────────────────────────────────────────
    val None       = 0.dp
    val XXSmall    = 2.dp
    val XSmall     = 4.dp
    val Small      = 8.dp
    val Medium     = 12.dp
    val Default    = 16.dp
    val Large      = 20.dp
    val XLarge     = 24.dp
    val XXLarge    = 32.dp
    val XXXLarge   = 48.dp

    // ── Semantic aliases ──────────────────────────────────────────────
    val CardPadding       = Default       // 16dp — interior padding of LayerCard
    val PanelPadding      = Default       // 16dp — interior padding of layer panels
    val SectionGap        = Medium        // 12dp — gap between sections within a panel
    val LayerGap          = Default       // 16dp — gap between layer panels in LayerStack
    val ComponentGap      = Small         //  8dp — gap between inline components
    val ButtonPadding     = Medium        // 12dp — button interior padding
    val InputPadding      = Medium        // 12dp — text field interior padding
    val ScreenPadding     = Default       // 16dp — root screen edge insets

    // ── Elevation / Corner Radius ─────────────────────────────────────
    val CardElevation     = 2.dp
    val CardCornerRadius  = 8.dp
    val ButtonCornerRadius = 6.dp
    val BadgeCornerRadius = 4.dp
}
