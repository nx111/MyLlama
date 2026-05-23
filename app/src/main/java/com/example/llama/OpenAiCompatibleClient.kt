package com.example.llama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OpenAiCompatibleClient {
    suspend fun complete(
        baseUrl: String,
        apiKey: String?,
        model: String,
        history: List<Message>,
        prompt: String,
        maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        require(baseUrl.isNotBlank()) { "OpenAI 兼容接口地址不能为空" }
        require(model.isNotBlank()) { "OpenAI 兼容模型名不能为空" }

        val connection = URL(chatCompletionsUrl(baseUrl)).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 90_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        if (!apiKey.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        }

        try {
            val body = JSONObject()
                .put("model", model.trim())
                .put("messages", requestMessages(history, prompt))
                .put("max_tokens", maxTokens.coerceAtLeast(1))
                .put("stream", false)

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val code = connection.responseCode
            val responseText = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (code !in 200..299) {
                error("OpenAI compatible request failed: HTTP $code $responseText")
            }

            JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .ifBlank { error("OpenAI compatible response has no message content") }
        } finally {
            connection.disconnect()
        }
    }

    private fun requestMessages(history: List<Message>, prompt: String): JSONArray {
        val messages = JSONArray()
        history.filter { it.content.isNotBlank() }.takeLast(MAX_HISTORY_MESSAGES).forEach { message ->
            messages.put(
                JSONObject()
                    .put("role", if (message.isUser) "user" else "assistant")
                    .put("content", message.content)
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        return messages
    }

    private fun chatCompletionsUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private companion object {
        private const val MAX_HISTORY_MESSAGES = 12
    }
}
