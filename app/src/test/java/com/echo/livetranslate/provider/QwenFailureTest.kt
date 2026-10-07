package com.echo.livetranslate.provider

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QwenFailureTest {
    @Test fun failedEventsRecoverButPermanentErrorsAndNormalCompletionsDoNotRetry() {
        val recoverable = listOf(
            """{"type":"error","error":{"type":"server_error","code":"internal_error","message":"Try again"}}""",
            """{"type":"error","error":{"type":"invalid_request_error","code":"data_inspection_failed","message":"Content rejected"}}""",
            """{"type":"error","error":{"code":"rate_limit_exceeded"}}""",
            """{"type":"conversation.item.input_audio_transcription.failed","error":{"message":"识别失败"}}""",
            """{"type":"response.done","response":{"status":"failed","status_details":{"error":{"code":"content_filter"}}}}""",
            """{"type":"response.done","response":{"status":"incomplete","status_details":{"reason":"content_filter"}}}""",
            """{"type":"response.done","response":{"status":"failed"}}""",
            """{"type":"error"}"""
        )
        recoverable.forEach { json ->
            val failure = QwenTranslationEvents.failure(JSONObject(json))
            assertNotNull(json, failure)
            assertFalse(json, failure!!.permanent)
            assertTrue(failure.detail.isNotBlank())
        }
        listOf("invalid_api_key", "insufficient_quota", "Account.Arrearage", "invalid_value", "model_not_found").forEach { code ->
            assertTrue(code, QwenTranslationEvents.failure(JSONObject("""{"type":"error","error":{"code":"$code"}}"""))!!.permanent)
        }
        assertTrue(QwenTranslationEvents.failure(JSONObject("""{"type":"error","error":{"type":"invalid_request_error","message":"Bad parameter"}}"""))!!.permanent)
        listOf("completed", "in_progress", "cancelled", "incomplete").forEach { status ->
            assertNull(QwenTranslationEvents.failure(JSONObject("""{"type":"response.done","response":{"status":"$status"}}""")))
        }
        assertNull(QwenTranslationEvents.failure(JSONObject("""{"type":"response.text.done","text":"字幕"}""")))
    }
}
