package com.agoii.mobile.infrastructure

import com.agoii.mobile.BuildConfig

// ─── ConfigProvider ───────────────────────────────────────────────────────────

/**
 * ConfigProvider — runtime provider of infrastructure configuration.
 *
 * RULES:
 *  - This is the ONLY place [BuildConfig] references are permitted.
 *  - NO other layer may import or reference [BuildConfig].
 *  - Configuration MUST be injected into all downstream components via this provider.
 *
 * CONTRACT: AGOII-RCF-EXTERNAL-COMMUNICATION-ISOLATION-01
 */
object ConfigProvider {

    fun openAI(): OpenAIConfig {
        return OpenAIConfig(
            endpoint  = "https://api.openai.com/v1/chat/completions",
            model     = "gpt-4o-mini",
            apiKey    = BuildConfig.OPENAI_API_KEY,
            timeoutMs = 30000
        )
    }
}
