package com.echo.livetranslate.provider.translate

import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任何走 /chat/completions 的服务都能用：OpenAI、DeepSeek、Kimi、
 * OpenRouter、硅基流动、本地 Ollama/vLLM ——改 base URL 和模型名即可。
 *
 * 提示词刻意压得很短：字幕场景下每多一个 token 都是延迟。
 */
class OpenAiCompatTranslator(private val s: Settings) : Translator {

    override suspend fun translate(text: String, targetLang: String): String =
        withContext(Dispatchers.IO) {
            try {
                request(text, targetLang)
            } catch (e: RetryableException) {
                // 限流和 5xx 多是瞬时的，等一下再来一次通常就过了。
                // 只重一次：字幕的时效性撑不起更久的等待
                delay(RETRY_DELAY_MS)
                request(text, targetLang)
            }
        }

    private class RetryableException(message: String) : Exception(message)

    private fun request(text: String, targetLang: String): String {
            val messages = JSONArray()
                .put(
                    JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            "You are a subtitle translator. Translate the user's line into " +
                                "$targetLang. Output only the translation, no quotes, no notes. " +
                                "Keep it short and natural for on-screen subtitles."
                        )
                )
                .put(JSONObject().put("role", "user").put("content", text))

            val payload = JSONObject()
                .put("model", s.openaiTransModel.trim())
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("max_tokens", 300)
                .put("stream", false)

            val request = Request.Builder()
                .url(s.openaiTransUrl.trim())
                .addHeader("Authorization", "Bearer ${s.openaiTransKey.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()

        return Http.restClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 429 || resp.code >= 500) {
                throw RetryableException("翻译接口 ${resp.code}: ${body.take(200)}")
            }
            check(resp.isSuccessful) { "翻译接口 ${resp.code}: ${body.take(200)}" }
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val RETRY_DELAY_MS = 350L
    }
}
