package com.agoii.mobile.infrastructure

import com.agoii.mobile.BuildConfig

object ConfigProvider {

    fun openAI(): OpenAIConfig {
        return OpenAIConfig(
            apiKey    = BuildConfig.OPENAI_API_KEY,
            endpoint  = "https://api.openai.com/v1/chat/completions",
            model     = "gpt-4o-mini",
            timeoutMs = 30000
        )
    }
}
