package com.echo.livetranslate.provider

import android.util.Log
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.net.Http
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * Deepgram 流式识别。只出原文，译文由 [com.echo.livetranslate.provider.translate.Translator] 补。
 * endpointing 调小可以让句子更早定稿，代价是偶尔切断长句。
 */
class DeepgramProvider(private val s: Settings) : SpeechProvider {

    private var ws: WebSocket? = null
    @Volatile private var open = false
    private lateinit var url: String

    override fun start(onCaption: (Caption) -> Unit, onError: (String) -> Unit) {
        url = buildUrl()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token ${s.deepgramKey.trim()}")
            .build()

        ws = Http.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") != "Results") return
                    val alt = json.optJSONObject("channel")
                        ?.optJSONArray("alternatives")
                        ?.optJSONObject(0) ?: return
                    val transcript = alt.optString("transcript").trim()
                    if (transcript.isEmpty()) return
                    onCaption(Caption(transcript, null, json.optBoolean("is_final", false)))
                } catch (t: Throwable) {
                    Log.w(TAG, "bad frame: ${t.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open = false
                // 握手失败时 OkHttp 已经把 body 关了，拿不到内容。
                // 重发一次普通 GET，Deepgram 会用同样的 JSON 说明哪个参数不对。
                val detail = explainFailure() ?: t.message.orEmpty()
                onError("Deepgram 连接失败：$detail")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
            }
        })
    }

    private fun buildUrl(): String {
        val base = s.deepgramUrl.trim().ifEmpty { "wss://api.deepgram.com/v1/listen" }
        return buildString {
            append(base)
            append(if (base.contains('?')) "&" else "?")
            append("encoding=linear16&sample_rate=").append(sampleRate)
            append("&channels=1&interim_results=true&smart_format=true")
            // 官方对多语种代码切换建议 100ms，顺带也让句子定稿更快
            append("&endpointing=").append(if (s.deepgramLanguage.trim() == "multi") 100 else 300)
            append("&model=").append(s.deepgramModel.trim().ifEmpty { "nova-3" })
            val lang = s.deepgramLanguage.trim()
            if (lang.isNotEmpty()) append("&language=").append(lang)
        }
    }

    /** 把 Deepgram 的错误 JSON 转成人能看懂的一句话。 */
    private fun explainFailure(): String? = runCatching {
        val probe = Request.Builder()
            .url(url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://"))
            .addHeader("Authorization", "Token ${s.deepgramKey.trim()}")
            .build()

        Http.restClient.newCall(probe).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            val message = json?.optString("err_msg").orEmpty()
            val code = json?.optString("err_code").orEmpty()
            when {
                message.isNotEmpty() -> "${resp.code} $message" +
                    if (code.isNotEmpty()) "（$code）" else ""
                body.isNotEmpty() -> "${resp.code} ${body.take(180)}"
                else -> "HTTP ${resp.code}"
            }
        }
    }.getOrNull()

    override fun feed(pcm: ByteArray, len: Int) {
        if (!open) return
        ws?.send(pcm.toByteString(0, len))
    }

    override fun stop() {
        open = false
        runCatching { ws?.send(ByteString.EMPTY) }   // Deepgram 用空帧表示流结束
        runCatching { ws?.close(1000, "bye") }
        ws = null
    }

    private companion object {
        const val TAG = "Deepgram"
    }
}
