package com.echo.livetranslate.core

import android.util.Log
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.provider.Caption
import com.echo.livetranslate.provider.translate.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class CaptionState(
    val original: String = "",
    val translated: String = "",
    val isFinal: Boolean = false
)

/**
 * 把识别结果变成屏幕上的一行字幕，顺带把定稿的句子交给历史记录。
 *
 * 核心是「句」的概念：识别端对同一句话会连续吐出越来越长的中间结果，
 * 最后给一条定稿。译文按句归属——只要还在同一句里，晚到的译文依然有效
 * 并且照常上屏；一旦进入下一句，前一句的译文立刻作废。
 *
 * 早先的实现要求「译文回来时原文必须和送出去时完全一致」，而原文每隔
 * 几百毫秒就在变长，于是翻译越慢丢得越多，慢到一定程度就完全不出中文了。
 */
class CaptionPipeline(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val onState: (CaptionState) -> Unit,
    private val onFinalLine: (original: String, translated: String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val translator: Translator? = Translator.from(settings)

    /** 中间结果的节流间隔，不能比服务商的限速还密 */
    private val partialInterval: Long =
        maxOf(PARTIAL_INTERVAL_MS, translator?.minIntervalMs ?: 0L)

    /**
     * 限速太严时干脆放弃翻译中间结果。
     * 配额都花在半句话上，真正该翻的定稿反而要排队，得不偿失。
     */
    private val translatePartials: Boolean =
        (translator?.minIntervalMs ?: 0L) <= PARTIAL_BUDGET_MS

    /** 当前是第几句。晚到的译文靠它判断自己是否已经过期 */
    @Volatile private var utterance = 0L
    @Volatile private var awaitingNextUtterance = false

    @Volatile private var original = ""
    @Volatile private var translated = ""

    @Volatile private var lastTranslatedSource = ""
    @Volatile private var lastPartialSentAt = 0L
    @Volatile private var lastRecorded: Pair<String, String>? = null

    private var inFlight: Job? = null

    fun submit(caption: Caption) {
        val text = caption.original.trim()
        // 千问只显示译文：ASR 事件和译文事件独立，不能强行按到达顺序配对。
        if (text.isEmpty() && !caption.translated.isNullOrBlank()) {
            original = ""
            translated = caption.translated
            emit(caption.isFinal)
            if (caption.isFinal) record("", translated)
            return
        }
        if (text.isEmpty()) return

        // 上一句已经定稿，这条属于新的一句：先把译文清空。
        // 否则旧译文会挂在新原文下面，看起来像翻译错了
        if (awaitingNextUtterance) {
            awaitingNextUtterance = false
            utterance++
            translated = ""
            lastTranslatedSource = ""
            lastPartialSentAt = 0L
        }
        val mine = utterance

        original = text

        // 识别端自带译文时直接用，一次网络往返都不用多花
        caption.translated?.let { ready ->
            translated = ready
            emit(caption.isFinal)
            if (caption.isFinal) {
                record(text, ready)
                awaitingNextUtterance = true
            }
            return
        }

        emit(caption.isFinal)

        val t = translator
        if (t == null) {
            if (caption.isFinal) {
                record(text, "")
                awaitingNextUtterance = true
            }
            return
        }

        if (caption.isFinal) {
            // 这一句的译文已经拿到过且原文没再变，不用再请求一次
            if (text == lastTranslatedSource && translated.isNotEmpty()) {
                record(text, translated)
                awaitingNextUtterance = true
                return
            }
        } else {
            if (!translatePartials) return
            if (text == lastTranslatedSource) return
            val now = System.currentTimeMillis()
            if (now - lastPartialSentAt < partialInterval) return
            if (text.length < MIN_PARTIAL_CHARS) return
            lastPartialSentAt = now
        }

        // 后来者胜：同一句里只保留最新一版在飞，避免请求排队越拖越长
        inFlight?.cancel()
        inFlight = scope.launch {
            val result = runCatching { t.translate(text, settings.targetLang) }
            result.onSuccess { done ->
                // 只要还在同一句里就照常上屏。译文可能比原文少几个词，
                // 但那是同一句话，读起来是连贯的——总好过什么都不显示
                if (mine == utterance) {
                    lastTranslatedSource = text
                    translated = done
                    emit(caption.isFinal)
                }
                if (caption.isFinal) {
                    record(text, done)
                    awaitingNextUtterance = true
                }
            }.onFailure { e ->
                if (e is CancellationException) return@onFailure
                Log.w(TAG, "translate failed: ${e.message}")
                // 静默失败最难查：屏幕上只是没有中文，看不出为什么
                onError("翻译失败：${e.message}")
                if (caption.isFinal) {
                    // 翻译挂了也别丢句子，原文照样存
                    record(text, "")
                    awaitingNextUtterance = true
                }
            }
        }
    }

    private fun emit(isFinal: Boolean) {
        onState(CaptionState(original, translated, isFinal))
    }

    private fun record(text: String, translation: String) {
        val line = text to translation
        if (line == lastRecorded) return
        lastRecorded = line
        onFinalLine(text, translation)
    }

    fun close() {
        inFlight?.cancel()
    }

    private companion object {
        const val TAG = "CaptionPipeline"
        /** 中间结果最多这么频繁地送去翻译 */
        const val PARTIAL_INTERVAL_MS = 400L
        /** 服务商限速比这还慢，就只翻定稿 */
        const val PARTIAL_BUDGET_MS = 600L
        const val MIN_PARTIAL_CHARS = 6
    }
}
