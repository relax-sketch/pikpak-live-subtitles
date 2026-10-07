package com.echo.livetranslate.provider

import android.util.Base64
import android.util.Log
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.net.Http
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * OpenAI 兼容的 Realtime 转写通道。地址可改，所以任何照搬这套事件协议的
 * 中转/自建服务都能用。只出原文，翻译交给翻译服务。
 */
class OpenAiRealtimeProvider(private val s: Settings) : SpeechProvider {

    private var ws: WebSocket? = null
    @Volatile private var open = false
    private val partial = StringBuilder()

    override fun start(onCaption: (Caption) -> Unit, onError: (String) -> Unit) {
        val request = Request.Builder()
            .url(s.realtimeUrl.trim())
            .addHeader("Authorization", "Bearer ${s.realtimeKey.trim()}")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        ws = Http.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                webSocket.send(sessionUpdate())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "conversation.item.input_audio_transcription.delta" -> {
                            partial.append(json.optString("delta"))
                            val now = partial.toString().trim()
                            if (now.isNotEmpty()) onCaption(Caption(now, null, false))
                        }
                        "conversation.item.input_audio_transcription.completed" -> {
                            val done = json.optString("transcript").trim()
                            partial.setLength(0)
                            if (done.isNotEmpty()) onCaption(Caption(done, null, true))
                        }
                        "error" -> onError(
                            "Realtime 错误：" + (json.optJSONObject("error")?.optString("message") ?: text)
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "bad frame: ${t.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open = false
                onError("Realtime 连接失败：${response?.code ?: ""} ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
            }
        })
    }

    private fun sessionUpdate(): String {
        val transcription = JSONObject().put("model", s.realtimeModel.trim())
        s.realtimeLanguage.trim().takeIf { it.isNotEmpty() }
            ?.let { transcription.put("language", it) }

        val session = JSONObject()
            .put("input_audio_format", "pcm16")
            .put("input_audio_transcription", transcription)
            .put(
                "turn_detection",
                JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", 0.5)
                    .put("prefix_padding_ms", 200)
                    .put("silence_duration_ms", 400)
            )

        return JSONObject()
            .put("type", "transcription_session.update")
            .put("session", session)
            .toString()
    }

    override fun feed(pcm: ByteArray, len: Int) {
        if (!open) return
        val b64 = Base64.encodeToString(pcm, 0, len, Base64.NO_WRAP)
        ws?.send(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", b64)
                .toString()
        )
    }

    override fun stop() {
        open = false
        partial.setLength(0)
        runCatching { ws?.close(1000, "bye") }
        ws = null
    }

    private companion object {
        const val TAG = "Realtime"
    }
}
