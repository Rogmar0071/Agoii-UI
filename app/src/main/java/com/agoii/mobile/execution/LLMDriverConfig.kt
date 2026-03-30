package com.agoii.mobile.execution

// ─── LLMDriverConfig ─────────────────────────────────────────────────────────

/**
 * Configuration required to operate [LLMDriver].
 *
 * RULES:
 *  - NO default values — every field must be supplied by the caller.
 *  - Any blank field is treated as missing configuration; [LLMDriver] will block.
 *  - No API keys may be hardcoded anywhere in the system.
 *
 * CONTRACT: AGOII-RCF-LLM-DRIVER-IMPLEMENTATION-01
 *
 * @property apiKey     The authentication key for the external LLM provider.
 * @property endpoint   The fully-qualified HTTP(S) URL of the provider's completion endpoint.
 * @property model      The model identifier to request from the provider (e.g. "gpt-4o").
 * @property timeoutMs  Connection and read timeout in milliseconds.
 */
data class LLMDriverConfig(
    val apiKey:    String,
    val endpoint:  String,
    val model:     String,
    val timeoutMs: Long
)
