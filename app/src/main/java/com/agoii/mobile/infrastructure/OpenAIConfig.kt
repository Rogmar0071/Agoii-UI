package com.agoii.mobile.infrastructure

// ─── OpenAIConfig ─────────────────────────────────────────────────────────────

/**
 * Immutable configuration for the OpenAI communication layer.
 *
 * RULES:
 *  - NO hardcoded values inside logic layers.
 *  - Config MUST be injected via [ConfigProvider].
 *
 * CONTRACT: AGOII-RCF-EXTERNAL-COMMUNICATION-ISOLATION-01
 */
data class OpenAIConfig(
    val endpoint:  String,
    val model:     String,
    val apiKey:    String,
    val timeoutMs: Long
)
