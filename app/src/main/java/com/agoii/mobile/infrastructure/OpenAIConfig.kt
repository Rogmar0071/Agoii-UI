package com.agoii.mobile.infrastructure

data class OpenAIConfig(
    val apiKey:    String,
    val endpoint:  String,
    val model:     String,
    val timeoutMs: Long
)
