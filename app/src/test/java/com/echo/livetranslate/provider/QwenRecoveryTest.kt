package com.echo.livetranslate.provider

import com.echo.livetranslate.data.Settings
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class QwenRecoveryTest {
    @Test fun configurableRetryDelayAndLiveChangesMatchNotice() {
        val connections = LinkedBlockingQueue<Connection>()
        val errors = LinkedBlockingQueue<String>()
        val provider = QwenLiveTranslateProvider(Settings(qwenKey = "test", qwenSpeechFilter = false, qwenRetryDelayMs = 700),
            WebSocket.Factory { request, listener -> Connection(request, listener).also { connections.add(it) } })
        try {
            provider.start({}, { errors.add(it) })
            connections.poll(1, TimeUnit.SECONDS)!!.receive("""{"type":"error","error":{"code":"internal_error"}}""")
            assertEquals("失败，0.7秒后重试", errors.poll())
            assertNull(connections.poll(450, TimeUnit.MILLISECONDS))
            val second = connections.poll(2, TimeUnit.SECONDS)!!
            provider.setRetryDelay(100)
            second.receive("""{"type":"error","error":{"code":"internal_error"}}""")
            assertEquals("失败，0.1秒后重试", errors.poll())
            assertNotNull(connections.poll(1, TimeUnit.SECONDS))
        } finally { provider.stop() }
    }

    private class Connection(private val request: Request, val listener: WebSocketListener) : WebSocket {
        var cancelled = false
        val sent = LinkedBlockingQueue<String>()
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (cancelled) return false
            sent.add(text)
            return true
        }
        override fun send(bytes: ByteString) = !cancelled
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
        fun receive(json: String) = listener.onMessage(this, json)
    }

    @Test fun timedRenewalFinishesCurrentAudioReconnectsQuietlyAndCancelsOnStop() {
        val connections = LinkedBlockingQueue<Connection>()
        val captions = mutableListOf<Caption>()
        val errors = mutableListOf<String>()
        val provider = QwenLiveTranslateProvider(Settings(qwenKey = "test", qwenSpeechFilter = false), WebSocket.Factory { request, listener ->
            Connection(request, listener).also { connections.add(it) }
        }, sessionDurationMs = 150L)
        try {
            provider.start({ captions.add(it) }, { errors.add(it) })
            val first = connections.poll(1, TimeUnit.SECONDS)!!
            first.receive("""{"type":"session.updated"}""")
            assertEquals("session.finish", org.json.JSONObject(first.sent.poll(2, TimeUnit.SECONDS)!!).getString("type"))
            assertFalse(first.cancelled)
            assertNull(connections.poll())
            first.receive("""{"type":"response.text.done","response_id":"last","text":"最后一句"}""")
            assertEquals("最后一句", captions.single().translated)
            first.receive("""{"type":"session.finished"}""")
            val second = connections.poll(1, TimeUnit.SECONDS)!!
            assertTrue(first.cancelled)
            assertTrue(errors.isEmpty())
            first.listener.onClosed(first, 1000, "finished")
            second.receive("""{"type":"session.updated"}""")
            provider.stop()
            assertNull(second.sent.poll(300, TimeUnit.MILLISECONDS))
            assertNull(connections.poll())
        } finally { provider.stop() }
    }

    @Test fun renewalCanBeDisabledAndEnabledLiveIncludingDuringFinish() {
        val connections = LinkedBlockingQueue<Connection>()
        val provider = QwenLiveTranslateProvider(Settings(qwenKey = "test", qwenSpeechFilter = false, qwenAutoRenew = false), WebSocket.Factory { request, listener ->
            Connection(request, listener).also { connections.add(it) }
        }, sessionDurationMs = 150L)
        try {
            provider.start({}, { fail(it) })
            val first = connections.poll(1, TimeUnit.SECONDS)!!
            first.receive("""{"type":"session.updated"}""")
            assertNull(first.sent.poll(250, TimeUnit.MILLISECONDS))
            provider.setAutoRenew(true)
            provider.setAutoRenew(false)
            assertNull(first.sent.poll(250, TimeUnit.MILLISECONDS))
            provider.setAutoRenew(true)
            assertEquals("session.finish", org.json.JSONObject(first.sent.poll(2, TimeUnit.SECONDS)!!).getString("type"))
            provider.setAutoRenew(false)
            first.receive("""{"type":"session.finished"}""")
            val second = connections.poll(1, TimeUnit.SECONDS)!!
            second.receive("""{"type":"session.updated"}""")
            assertNull(second.sent.poll(250, TimeUnit.MILLISECONDS))
            assertNull(connections.poll())
        } finally { provider.stop() }
    }

    @Test fun reconnectsOnceIgnoresOldSocketContinuesCaptionsAndStopsPendingRetry() {
        val connections = LinkedBlockingQueue<Connection>()
        val captions = mutableListOf<Caption>()
        val errors = mutableListOf<String>()
        val provider = QwenLiveTranslateProvider(Settings(qwenKey = "test", qwenSpeechFilter = false), WebSocket.Factory { request, listener ->
            Connection(request, listener).also { connections.add(it) }
        })
        try {
            provider.start({ captions.add(it) }, { errors.add(it) })
            val first = connections.poll(1, TimeUnit.SECONDS)!!
            first.receive("""{"type":"session.updated"}""")
            first.receive("""{"type":"error","error":{"code":"data_inspection_failed"}}""")
            assertTrue(first.cancelled)
            // 旧连接后续回调不得触发第二次重连或显示过期字幕。
            first.listener.onClosed(first, 1000, "closed")
            first.receive("""{"type":"response.text.delta","response_id":"old","delta":"旧字幕"}""")
            assertEquals("失败，0.5秒后重试", errors.last())
            assertNull(connections.poll(400, TimeUnit.MILLISECONDS))
            val second = connections.poll(3, TimeUnit.SECONDS)!!
            second.receive("""{"type":"session.updated"}""")
            second.receive("""{"type":"response.text.delta","response_id":"new","delta":"后面的字幕"}""")
            assertEquals(listOf("后面的字幕"), captions.map { it.translated })
            second.receive("""{"type":"response.done","response":{"status":"failed"}}""")
            assertEquals("失败，0.5秒后重试", errors.last())
            assertNull(connections.poll(400, TimeUnit.MILLISECONDS))
            val third = connections.poll(4, TimeUnit.SECONDS)!!
            third.receive("""{"type":"session.updated"}""")
            third.receive("""{"type":"response.done","response":{"status":"completed"}}""")
            third.receive("""{"type":"error","error":{"code":"internal_error"}}""")
            assertEquals("失败，0.5秒后重试", errors.last())
            provider.stop()
            assertNull(connections.poll(1200, TimeUnit.MILLISECONDS))
        } finally { provider.stop() }

        val permanent = QwenLiveTranslateProvider(Settings(qwenKey = "test", qwenSpeechFilter = false), WebSocket.Factory { request, listener ->
            Connection(request, listener).also { connections.add(it) }
        })
        try {
            permanent.start({}, { errors.add(it) })
            val denied = connections.poll(1, TimeUnit.SECONDS)!!
            denied.receive("""{"type":"error","error":{"code":"invalid_api_key"}}""")
            assertTrue(denied.cancelled)
            assertTrue(errors.last().contains("检查 Key"))
            assertNull(connections.poll(1200, TimeUnit.MILLISECONDS))
        } finally { permanent.stop() }
    }
}
