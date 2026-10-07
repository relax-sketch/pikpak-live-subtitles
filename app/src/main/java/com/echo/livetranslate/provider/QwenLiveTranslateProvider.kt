package com.echo.livetranslate.provider

import android.util.Base64
import android.util.Log
import com.echo.livetranslate.core.PcmSpeechGate
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** 16kHz PCM 内录直接送千问，接收中文字幕；断线自动重连，不积压电影音频。 */
class QwenLiveTranslateProvider(
    private val settings: Settings,
    private val sockets: WebSocket.Factory = Http.client,
    private val sessionDurationMs: Long = 5 * 60 * 1000L
) : SpeechProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var retry: Job? = null
    private var renewal: Job? = null
    private var renewing = false
    private var active = false
    private var ready = false
    private var vad: VadWebRTC? = null
    private var gate: PcmSpeechGate? = null
    private var speechFilter = settings.qwenSpeechFilter
    private var autoRenew = settings.qwenAutoRenew
    private var retryDelayMs = settings.qwenRetryDelayMs.coerceIn(100, 10000)
    private var caption: (Caption) -> Unit = {}
    private var error: (String) -> Unit = {}

    @Synchronized
    override fun start(onCaption: (Caption) -> Unit, onError: (String) -> Unit) {
        if (active) return
        active = true
        caption = onCaption
        error = onError
        connect()
    }

    @Synchronized
    private fun connect() {
        if (!active) return
        ready = false
        initFilter()
        val events = QwenTranslationEvents()
        val request = Request.Builder().url(QwenTranslationEvents.URL)
            .header("Authorization", "Bearer ${settings.qwenKey.trim()}").build()
        socket = sockets.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(this@QwenLiveTranslateProvider) {
                    if (!active || socket !== webSocket) { webSocket.cancel(); return }
                    webSocket.send(QwenTranslationEvents.sessionUpdate())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(this@QwenLiveTranslateProvider) {
                    if (!active || socket !== webSocket) return
                    val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                    QwenTranslationEvents.failure(event)?.let { failure ->
                        if (failure.permanent) halt(webSocket, "千问接口错误：${failure.detail}；请检查 Key / 额度 / 设置后重新开启")
                        else reconnect(webSocket)
                        return
                    }
                    when (event.optString("type")) {
                        "session.updated" -> if (!renewing) {
                            ready = true
                            scheduleRenewal(webSocket)
                        }
                        "session.finished" -> reconnect(webSocket, failed = !renewing)
                        else -> events.accept(event)?.let(caption)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@QwenLiveTranslateProvider) {
                    if (!active || socket !== webSocket) return
                    val code = response?.code
                    if (code != null && code in 400..499 && code != 408 && code != 429)
                        halt(webSocket, "千问连接被拒绝（HTTP $code），请检查 API Key、额度和模型访问权限")
                    else reconnect(webSocket)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(this@QwenLiveTranslateProvider) {
                    reconnect(webSocket)
                }
            }
        })
    }

    private fun scheduleRenewal(webSocket: WebSocket) {
        renewal?.cancel()
        if (!autoRenew) return
        renewal = scope.launch {
            delay(sessionDurationMs)
            synchronized(this@QwenLiveTranslateProvider) {
                if (!active || socket !== webSocket) return@launch
                ready = false
                renewing = true
                closeFilter()
                // 先收完已发送音频的译文；换会话期间不积压或重放电影音频。
                if (!webSocket.send(JSONObject().put("type", "session.finish").toString())) {
                    reconnect(webSocket)
                    return@launch
                }
            }
            delay(5000L)
            synchronized(this@QwenLiveTranslateProvider) {
                reconnect(webSocket, failed = false)
            }
        }
    }

    private fun reconnect(webSocket: WebSocket, failed: Boolean = true) {
        if (!active || socket !== webSocket) return
        ready = false
        socket = null
        webSocket.cancel()
        closeFilter()
        renewal?.cancel()
        renewing = false
        val waitMs = retryDelayMs.toLong()
        if (failed) error("失败，${waitMs / 1000.0}秒后重试")
        retry?.cancel()
        retry = scope.launch { if (failed) delay(waitMs); connect() }
    }

    private fun halt(webSocket: WebSocket, message: String) {
        active = false
        ready = false
        retry?.cancel()
        renewal?.cancel()
        renewing = false
        socket = null
        webSocket.cancel()
        closeFilter()
        error(message)
    }

    @Synchronized
    override fun feed(pcm: ByteArray, len: Int) {
        if (!active || !ready || len <= 0) return
        val filter = gate
        if (filter != null) filter.feed(pcm, len) else sendPcm(pcm, len)
    }

    private fun sendPcm(pcm: ByteArray, len: Int) {
        val ws = socket ?: return
        if (!active || !ready || len <= 0) return
        // 弱网时丢弃积压并重连，避免字幕越来越落后。
        if (ws.queueSize() > 128 * 1024) {
            reconnect(ws)
            return
        }
        val frame = JSONObject().put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(pcm, 0, len, Base64.NO_WRAP)).toString()
        if (!ws.send(frame)) reconnect(ws)
    }

    @Synchronized
    override fun stop() {
        active = false
        ready = false
        retry?.cancel()
        renewal?.cancel()
        renewing = false
        socket?.cancel()
        socket = null
        scope.cancel()
        closeFilter()
    }

    @Synchronized
    fun setRetryDelay(ms: Int) { retryDelayMs = ms.coerceIn(100, 10000) }

    @Synchronized
    fun setAutoRenew(enabled: Boolean) {
        if (autoRenew == enabled) return
        autoRenew = enabled
        // 已发送 session.finish 时仍需完成此次换会话，避免停止上传后一直等待。
        if (renewing) return
        if (!enabled) renewal?.cancel()
        else if (active && ready) socket?.let { scheduleRenewal(it) }
    }

    @Synchronized
    fun setSpeechFilter(enabled: Boolean) {
        if (speechFilter == enabled) return
        speechFilter = enabled
        if (active) initFilter()
    }

    private fun initFilter() {
        closeFilter()
        if (!speechFilter) return
        try {
            val detector = VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320, Mode.NORMAL)
            vad = detector
            var failed = false
            // ponytail: 宽松 WebRTC 可能放行音乐；优先不漏对白，需更精准时再换 Silero。
            gate = PcmSpeechGate({ frame ->
                if (failed) true else try { detector.isSpeech(frame) } catch (e: Exception) {
                    failed = true
                    Log.w("QwenVAD", "检测失败，恢复连续上传", e)
                    true
                }
            }, ::sendPcm)
        } catch (e: Exception) {
            Log.w("QwenVAD", "无法初始化，恢复连续上传", e)
        } catch (e: LinkageError) {
            Log.w("QwenVAD", "设备不支持检测库，恢复连续上传", e)
        }
    }

    private fun closeFilter() {
        gate = null
        vad?.let { runCatching { it.close() } }
        vad = null
    }
}
