package com.echo.livetranslate

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.core.CaptionState
import com.echo.livetranslate.service.OverlayController
import com.echo.livetranslate.provider.QwenLiveTranslateProvider
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import com.echo.livetranslate.core.PcmSpeechGate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.audio.AudioInputStream
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat
import com.microsoft.cognitiveservices.speech.translation.SpeechTranslationConfig
import com.microsoft.cognitiveservices.speech.translation.TranslationRecognizer
import com.echo.livetranslate.provider.AzureSpeechProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 默认离线检查；显式传入 azureKeyFile / azureAudioFile 时追加真实 API 流式翻译测试。 */
class VadSmokeInstrumentation : Instrumentation() {
    private var testArguments = Bundle()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        testArguments = arguments ?: Bundle()
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320, Mode.NORMAL).use { vad ->
                val silence = ByteArray(640)
                val start = SystemClock.elapsedRealtimeNanos()
                repeat(3000) { check(!vad.isSpeech(silence)) { "静音被误判为人声" } }
                val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0 / 3000
                check(ms < 20) { "处理慢于音频帧：${ms}ms" }
                result.putString("stream", "VAD native OK; silence 3000 frames rejected; average ${ms}ms / 20ms frame\n")
            }
            val wav = context.assets.open("speech.wav").use { it.readBytes() }
            var offset = 12
            while (String(wav, offset, 4, Charsets.US_ASCII) != "data") {
                val size = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                offset += 8 + size + (size and 1)
            }
            val length = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val speech = wav.copyOfRange(offset + 8, offset + 8 + length)
            for (divisor in listOf(1, 8)) {
                val quiet = speech.copyOf()
                for (i in 0 until quiet.size - 1 step 2) {
                    val value = (((quiet[i + 1].toInt() shl 8) or (quiet[i].toInt() and 255)).toShort().toInt() / divisor)
                    quiet[i] = value.toByte()
                    quiet[i + 1] = (value shr 8).toByte()
                }
                VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320, Mode.NORMAL).use { vad ->
                    var detected = 0
                    var sent = 0
                    val gate = PcmSpeechGate({ vad.isSpeech(it).also { yes -> if (yes) detected++ } },
                        { _, count -> sent += count })
                    val silence = ByteArray(16000 * 2 * 10)
                    gate.feed(silence, silence.size)
                    check(sent == 0)
                    gate.feed(quiet, quiet.size)
                    check(detected > 20) { "未检测到测试语音，音量 1/$divisor" }
                    gate.feed(silence, silence.size)
                    check(sent < silence.size * 2 + quiet.size)
                    result.putString("stream", result.getString("stream") + "Speech volume 1/$divisor: $detected speech frames; passed bytes $sent; padding filtered\n")
                }
            }
            testOverlayGestures()
            testLiveFilterSwitch()
            testAzureNative()
            result.putString("stream", result.getString("stream") + "Overlay: double/four taps, 6-second hold, drag cancellation, pause caption guard, locked mode OK; live VAD switch uses one socket\n")
            result.putString("stream", result.getString("stream") + "Azure SDK native: Japanese / Chinese recognizer and PCM stream constructed and closed; offline only, no API request\n")
            if (testArguments.containsKey("azureKeyFile")) {
                result.putString("stream", result.getString("stream") + testAzureTranslation())
            }
            finish(-1, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAIL: ${t.javaClass.simpleName}: ${t.message}\n")
            finish(0, result)
        }
    }

    private fun testAzureTranslation(): String {
        val key = File(requireNotNull(testArguments.getString("azureKeyFile"))).readText().trim()
        val wav = File(requireNotNull(testArguments.getString("azureAudioFile"))).readBytes()
        check(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF")
        var offset = 12
        while (String(wav, offset, 4, Charsets.US_ASCII) != "data") {
            val size = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (String(wav, offset, 4, Charsets.US_ASCII) == "fmt ") {
                val fmt = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
                check(fmt.getShort(offset + 8).toInt() == 1 && fmt.getShort(offset + 10).toInt() == 1)
                check(fmt.getInt(offset + 12) == 16000 && fmt.getShort(offset + 22).toInt() == 16)
            }
            offset += 8 + size + (size and 1)
        }
        val size = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val audio = wav.copyOfRange(offset + 8, offset + 8 + size)
        val provider = AzureSpeechProvider(Settings(azureKey = key))
        val done = CountDownLatch(1)
        val partials = AtomicInteger()
        val firstPartialMs = AtomicReference<Long>()
        val failure = AtomicReference<String>()
        val captions = java.util.Collections.synchronizedList(mutableListOf<String>())
        var feedStarted = 0L
        try {
            provider.start({ caption ->
                if (!caption.translated.isNullOrBlank()) {
                    if (!caption.isFinal) {
                        partials.incrementAndGet()
                        firstPartialMs.compareAndSet(null, SystemClock.elapsedRealtime() - feedStarted)
                    } else {
                        captions.add("${caption.original} => ${caption.translated}")
                        if (caption.original.contains("映画") && caption.translated.contains("电影")) done.countDown()
                    }
                }
            }, { failure.set(it); done.countDown() })
            feedStarted = SystemClock.elapsedRealtime()
            for (i in audio.indices step 640) {
                check(failure.get() == null) { failure.get().orEmpty() }
                val frame = audio.copyOfRange(i, minOf(i + 640, audio.size))
                provider.feed(frame, frame.size)
                SystemClock.sleep(20)
            }
            repeat(100) { provider.feed(ByteArray(640), 640); SystemClock.sleep(20) }
            check(done.await(25, TimeUnit.SECONDS)) { "Azure final Japanese / Chinese caption timed out" }
            check(failure.get() == null) { failure.get().orEmpty() }
            check(partials.get() > 0) { "No streaming Chinese partials" }
            check(captions.any { it.contains("映画") && it.contains("电影") }) { "Translation mismatch: $captions" }
            return "Azure LIVE API OK; Chinese partials=${partials.get()}; first partial=${firstPartialMs.get()}ms from audio feed start; final captions=$captions\n"
        } finally { provider.stop() }
    }

    private fun testAzureNative() {
        val config = SpeechTranslationConfig.fromSubscription("offline-test", "germanywestcentral")
        val format = AudioStreamFormat.getWaveFormatPCM(16000L, 16.toShort(), 1.toShort())
        val stream = AudioInputStream.createPushStream(format)
        val audio = AudioConfig.fromStreamInput(stream)
        try {
            config.speechRecognitionLanguage = "ja-JP"
            config.addTargetLanguage("zh-Hans")
            val recognizer = TranslationRecognizer(config, audio)
            try { stream.write(ByteArray(640)) } finally { recognizer.close() }
        } finally {
            audio.close(); stream.close(); format.close(); config.close()
        }
    }

    private fun testOverlayGestures() {
        var doubles = 0
        var fours = 0
        var moved = 0
        var holds = 0
        lateinit var overlay: OverlayController
        lateinit var view: LinearLayout
        runOnMainSync {
            overlay = OverlayController(targetContext, { doubles++ }, { fours++ }, { holds++ }) { _, _ -> moved++ }
            overlay.show(Settings(overlayX = 20, overlayY = 100))
            overlay.showMessage("测试字幕")
            view = OverlayController::class.java.getDeclaredField("root").apply { isAccessible = true }.get(overlay) as LinearLayout
        }
        waitForIdleSync()
        runOnMainSync { overlay.update(CaptionState(translated = "失败前的字幕")) }
        waitForIdleSync()
        runOnMainSync { overlay.showRetry("失败，0.5秒后重试") }
        waitForIdleSync()
        runOnMainSync {
            check((view.getChildAt(1) as TextView).text.toString() == "失败前的字幕（失败，0.5秒后重试）")
            overlay.showRetry("失败，0.7秒后重试")
        }
        waitForIdleSync()
        runOnMainSync {
            overlay.apply(Settings(fontSizeSp = 30))
            check((view.getChildAt(1) as TextView).text.toString() == "失败前的字幕（失败，0.7秒后重试）")
            overlay.update(CaptionState(translated = "恢复后的字幕"))
        }
        waitForIdleSync()
        runOnMainSync {
            check((view.getChildAt(1) as TextView).text.toString() == "恢复后的字幕")
            overlay.showMessage("字幕已暂停")
            overlay.setPaused(true)
            overlay.showRetry("失败，0.5秒后重试")
        }
        waitForIdleSync()
        runOnMainSync {
            check((view.getChildAt(0) as TextView).text.toString() == "字幕已暂停")
            overlay.setPaused(false)
        }
        fun tap(drag: Boolean = false) {
            runOnMainSync {
                val now = SystemClock.uptimeMillis()
                fun send(action: Int, x: Float) {
                    val event = MotionEvent.obtain(now, now + 20, action, x, 10f, 0)
                    view.dispatchTouchEvent(event)
                    event.recycle()
                }
                send(MotionEvent.ACTION_DOWN, 10f)
                if (drag) send(MotionEvent.ACTION_MOVE, 100f)
                send(MotionEvent.ACTION_UP, if (drag) 100f else 10f)
            }
        }
        fun settle() { SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout() + 100L); waitForIdleSync() }
        try {
            repeat(2) { tap() }; settle()
            check(doubles == 1 && fours == 0)
            repeat(4) { tap() }; settle()
            check(doubles == 1 && fours == 1)
            repeat(2) { tap() }; tap(drag = true); settle()
            check(doubles == 1 && moved == 1)
            val holdStart = SystemClock.uptimeMillis()
            runOnMainSync {
                val event = MotionEvent.obtain(holdStart, holdStart, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
            SystemClock.sleep(6200L)
            waitForIdleSync()
            check(holds == 1)
            runOnMainSync {
                val event = MotionEvent.obtain(holdStart, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 10f, 10f, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
            settle()
            check(doubles == 1 && fours == 1)
            runOnMainSync {
                overlay.setPaused(true)
                overlay.showMessage("字幕已暂停")
                overlay.update(CaptionState(original = "old", translated = "旧字幕"))
            }
            waitForIdleSync()
            runOnMainSync {
                overlay.apply(Settings(qwenSpeechFilter = false))
                check((view.getChildAt(0) as TextView).text.toString() == "字幕已暂停")
                check(view.getChildAt(0).visibility == View.VISIBLE)
                overlay.apply(Settings(locked = true))
            }
            repeat(4) { tap() }; settle()
            check(doubles == 1 && fours == 1)
        } finally { runOnMainSync { overlay.hide() } }
    }

    private fun testLiveFilterSwitch() {
        var sends = 0
        var connections = 0
        lateinit var listener: WebSocketListener
        lateinit var socket: WebSocket
        val provider = QwenLiveTranslateProvider(Settings(qwenKey = "test"), WebSocket.Factory { request, callbacks ->
            connections++
            listener = callbacks
            object : WebSocket {
                override fun request(): Request = request
                override fun queueSize() = 0L
                override fun send(text: String): Boolean { sends++; return true }
                override fun send(bytes: ByteString) = true
                override fun close(code: Int, reason: String?) = true
                override fun cancel() {}
            }.also { socket = it }
        })
        try {
            provider.start({}, { error(it) })
            listener.onMessage(socket, """{"type":"session.updated"}""")
            val silence = ByteArray(640)
            provider.feed(silence, silence.size)
            check(sends == 0)
            provider.setSpeechFilter(false)
            provider.feed(silence, silence.size)
            check(sends == 1)
            provider.setSpeechFilter(true)
            provider.feed(silence, silence.size)
            check(sends == 1 && connections == 1)
        } finally { provider.stop() }
    }
}
