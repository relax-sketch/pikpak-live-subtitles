package com.echo.livetranslate.provider

import com.echo.livetranslate.core.CaptionPipeline
import com.echo.livetranslate.core.CaptionState
import com.echo.livetranslate.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QwenTranslationEventsTest {
    private fun frame(type: String, id: String, text: String) = JSONObject()
        .put("type", type).put("response_id", id).put("item_id", "item_$id")
        .put(if (type.endsWith("done")) "text" else "delta", text)

    @Test fun streamsTranslationWithoutMixingResponses() {
        val events = QwenTranslationEvents()
        assertEquals("你好", events.accept(frame("response.text.delta", "a", "你好"))?.translated)
        assertEquals("你好世界", events.accept(frame("response.text.delta", "a", "世界"))?.translated)
        val final = events.accept(frame("response.text.done", "a", "你好，世界。"))!!
        assertTrue(final.isFinal)
        assertEquals("你好，世界。", final.translated)
        assertNull(events.accept(frame("response.text.done", "a", "你好，世界。")))
        assertEquals("再见", events.accept(frame("response.text.delta", "b", "再见"))?.translated)
        assertNull(events.accept(frame("response.text.delta", "a", "旧片段")))
    }

    @Test fun lateOldResponseCannotOverwriteNewSubtitle() {
        val events = QwenTranslationEvents()
        events.accept(frame("response.text.delta", "a", "旧字幕"))
        events.accept(frame("response.text.delta", "b", "新字幕"))
        assertNull(events.accept(frame("response.text.done", "a", "旧字幕定稿")))
        assertEquals("新字幕继续", events.accept(frame("response.text.delta", "b", "继续"))?.translated)
    }

    @Test fun ignoresAsrAndEmptyEvents() {
        val events = QwenTranslationEvents()
        assertNull(events.accept(JSONObject().put("type", "conversation.item.input_audio_transcription.completed")
            .put("transcript", "日本語")))
        assertNull(events.accept(frame("response.text.delta", "a", "")))
        assertNull(events.accept(JSONObject().put("type", "response.text.delta").put("delta", "无ID")))
    }

    @Test fun sessionRequestsChineseTextOnly() {
        val session = JSONObject(QwenTranslationEvents.sessionUpdate()).getJSONObject("session")
        assertEquals("[\"text\"]", session.getJSONArray("output_modalities").toString())
        assertEquals("zh", session.getJSONObject("translation").getString("language"))
        assertEquals("wss://maas.qianwenaiapi.com/api-ws/v1/realtime?model=qwen3.8-livetranslate-flash-realtime",
            QwenTranslationEvents.URL)
    }

    @Test fun pipelineDisplaysAndRecordsTranslationWithoutSourceText() {
        val states = mutableListOf<CaptionState>()
        val records = mutableListOf<Pair<String, String>>()
        val pipeline = CaptionPipeline(CoroutineScope(Dispatchers.Unconfined), Settings(),
            { states.add(it) }, { source, translated -> records.add(source to translated) },
            { fail(it) })
        pipeline.submit(Caption("", "你好", false))
        pipeline.submit(Caption("", "你好世界", true))
        pipeline.submit(Caption("", "再见", true))
        assertEquals(listOf("你好", "你好世界", "再见"), states.map { it.translated })
        assertEquals(listOf("" to "你好世界", "" to "再见"), records)
        pipeline.close()
    }
}
