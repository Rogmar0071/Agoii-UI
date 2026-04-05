package agoii.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Agoii Design System — Color Palette.
 *
 * Layer-aligned colors enforce visual layer separation (UI-05).
 * Each architectural layer has a distinct color group.
 */
object AgoiiColors {

    // ── Governance Layer ──────────────────────────────────────────────
    val GovernancePrimary   = Color(0xFF1A73E8) // Blue 600
    val GovernanceSecondary = Color(0xFFE8F0FE) // Blue 50
    val GovernanceAccent    = Color(0xFF174EA6) // Blue 800
    val GovernanceText      = Color(0xFF202124) // Grey 900

    // ── Execution Layer ───────────────────────────────────────────────
    val ExecutionPrimary    = Color(0xFF34A853) // Green 600
    val ExecutionSecondary  = Color(0xFFE6F4EA) // Green 50
    val ExecutionAccent     = Color(0xFF0D652D) // Green 800
    val ExecutionText       = Color(0xFF202124) // Grey 900

    // ── Audit Layer ───────────────────────────────────────────────────
    val AuditPrimary        = Color(0xFFF9AB00) // Amber 600
    val AuditSecondary      = Color(0xFFFEF7E0) // Amber 50
    val AuditAccent         = Color(0xFFE37400) // Amber 800
    val AuditText           = Color(0xFF202124) // Grey 900

    // ── Status Colors ─────────────────────────────────────────────────
    val StatusClosed        = Color(0xFF34A853) // Green — healthy
    val StatusOpen          = Color(0xFFEA4335) // Red — circuit open
    val StatusHalfOpen      = Color(0xFFF9AB00) // Amber — probing
    val StatusNone          = Color(0xFF9AA0A6) // Grey — inactive

    // ── Surface / Background ──────────────────────────────────────────
    val Background          = Color(0xFFF8F9FA) // Grey 50
    val Surface             = Color(0xFFFFFFFF) // White
    val SurfaceVariant      = Color(0xFFF1F3F4) // Grey 100
    val Divider             = Color(0xFFDADCE0) // Grey 300

    // ── Text ──────────────────────────────────────────────────────────
    val TextPrimary         = Color(0xFF202124) // Grey 900
    val TextSecondary       = Color(0xFF5F6368) // Grey 600
    val TextDisabled        = Color(0xFF9AA0A6) // Grey 400

    // ── Interactive ───────────────────────────────────────────────────
    val ButtonPrimary       = Color(0xFF1A73E8) // Blue 600
    val ButtonDisabled      = Color(0xFFDADCE0) // Grey 300
    val InputBorder         = Color(0xFFDADCE0) // Grey 300
    val InputFocusBorder    = Color(0xFF1A73E8) // Blue 600
}
