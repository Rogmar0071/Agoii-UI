package com.agoii.mobile.infrastructure

import com.agoii.mobile.BuildConfig

object ConfigProvider {

    private const val DEFAULT_TIMEOUT_MS = 30_000L

    fun openAI(): OpenAIConfig {
        return OpenAIConfig(
            apiKey    = BuildConfig.OPENAI_API_KEY,
            endpoint  = "https://api.openai.com/v1/chat/completions",
            model     = "gpt-4o-mini",
            timeoutMs = DEFAULT_TIMEOUT_MS
        )
    }

    fun nemoClaw(): NemoClawConfig {
        return NemoClawConfig(
            apiKey    = BuildConfig.NEMOCLAW_API_KEY,
            endpoint  = "https://api.nemoclaw.io/v1/execute",
            timeoutMs = DEFAULT_TIMEOUT_MS
        )
    }
}
