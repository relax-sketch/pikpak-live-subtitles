package com.echo.livetranslate.provider

import org.json.JSONArray
import org.json.JSONObject

/** 千问 3.8 的 delta 是新增片段；不同 response 的片段不能拼成同一句。 */
internal class QwenTranslationEvents {
    private val texts = linkedMapOf<String, StringBuilder>()
    private val completed = mutableSetOf<String>()
    private var latest = ""

    fun accept(event: JSONObject): Caption? {
        val type = event.optString("type")
        if (type != "response.text.delta" && type != "response.text.done") return null
        val response = event.optString("response_id")
        if (response.isBlank()) return null
        val key = "$response/${event.optString("item_id")}/${event.optInt("content_index") }"
        if (key in completed) return null
        val text = texts.getOrPut(key) {
            latest = key
            StringBuilder()
        }
        val final = type == "response.text.done"
        if (final) {
            if (event.has("text")) {
                text.setLength(0)
                text.append(event.optString("text"))
            }
            completed.add(key)
        } else {
            text.append(event.optString("delta"))
        }
        // ponytail: 保留最近 64 段去重；更长时间的乱序需按服务端时间戳排序。
        while (texts.size > 64) {
            val oldest = texts.keys.first()
            texts.remove(oldest)
            completed.remove(oldest)
        }
        if (key != latest || text.isBlank()) return null
        return Caption(original = "", translated = text.toString(), isFinal = final)
    }

    companion object {
        const val URL = "wss://maas.qianwenaiapi.com/api-ws/v1/realtime?model=qwen3.8-livetranslate-flash-realtime"

        data class Failure(val detail: String, val permanent: Boolean)

        /** 失败有三种事件入口；只重建会话接收新音频，不重放失败片段。 */
        fun failure(event: JSONObject): Failure? {
            val problem = when (event.optString("type")) {
                "error", "conversation.item.input_audio_transcription.failed" -> event.optJSONObject("error")
                "response.done" -> {
                    val response = event.optJSONObject("response") ?: return null
                    val details = response.optJSONObject("status_details")
                    if (response.optString("status") != "failed" &&
                        !(response.optString("status") == "incomplete" && details?.optString("reason") == "content_filter")) return null
                    details?.optJSONObject("error") ?: details
                }
                else -> return null
            }
            val code = problem?.optString("code").orEmpty()
            val message = problem?.optString("message").orEmpty()
            val reason = problem?.optString("reason").orEmpty()
            val detail = listOf(code, message, reason).filter { it.isNotBlank() }.joinToString("：").ifBlank { "翻译响应失败" }
            val normalized = detail.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]"), "")
            val rejectedContent = listOf("contentfilter", "datainspection", "inappropriate", "sensitive", "moderation", "审核", "敏感", "不合规").any { it in normalized }
            val permanent = !rejectedContent && (
                problem?.optString("type") == "invalid_request_error" ||
                listOf("invalidapikey", "invalidvalue", "invalidparameter", "authentication", "unauthorized", "accessdenied",
                    "permissiondenied", "insufficientquota", "quotaexceeded", "arrearage", "accountoverdue", "insufficientbalance",
                    "modelnotfound", "unsupported", "额度不足", "额度已用完", "欠费", "余额不足").any { it in normalized })
            return Failure(detail, permanent)
        }

        fun sessionUpdate(): String = JSONObject()
            .put("type", "session.update")
            .put("session", JSONObject()
                .put("output_modalities", JSONArray().put("text"))
                .put("translation", JSONObject().put("language", "zh")))
            .toString()
    }
}
