package com.agoii.mobile.infrastructure

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OpenAIClient {

    fun send(config: OpenAIConfig, requestBody: String): String {
        val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod  = "POST"
            connectTimeout = config.timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            readTimeout    = config.timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            doOutput       = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "<no error details available>"
                throw Exception("LLM_HTTP_ERROR: CODE=$code BODY=$errorBody")
            }

            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }
}
