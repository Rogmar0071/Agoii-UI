package com.agoii.mobile.infrastructure

import com.agoii.mobile.BuildConfig

object ConfigProvider {

    fun openAI(): OpenAIConfig {
        val apiKey = BuildConfig.OPENAI_API_KEY

        return OpenAIConfig(
            apiKey = apiKey,
            endpoint = "https://api.openai.com/v1/chat/completions",
            model = "gpt-4o-mini",
            timeoutMs = 30_000L
        )
    }
}
