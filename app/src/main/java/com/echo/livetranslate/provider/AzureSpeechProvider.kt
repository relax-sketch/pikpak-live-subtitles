package com.echo.livetranslate.provider

import android.util.Log
import com.echo.livetranslate.data.Settings
import com.microsoft.cognitiveservices.speech.AutoDetectSourceLanguageConfig
import com.microsoft.cognitiveservices.speech.PropertyId
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.audio.AudioInputStream
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream
import com.microsoft.cognitiveservices.speech.translation.SpeechTranslationConfig
import com.microsoft.cognitiveservices.speech.translation.TranslationRecognizer

/**
 * Azure 语音翻译：识别和翻译在同一条连接里完成，原文和译文同时返回，
 * 多于一种源语言时开启连续语种识别；固定日语无需自动判断语种。
 */
class AzureSpeechProvider(private val s: Settings) : SpeechProvider {

    private var push: PushAudioInputStream? = null
    private var recognizer: TranslationRecognizer? = null
    private var config: SpeechTranslationConfig? = null
    private var audioConfig: AudioConfig? = null

    override fun start(onCaption: (Caption) -> Unit, onError: (String) -> Unit) {
        try {
            val cfg = SpeechTranslationConfig.fromSubscription(s.azureKey.trim(), s.azureRegion.trim())
            cfg.addTargetLanguage(s.targetLang)

            val langs = s.azureSourceLangs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (langs.size > 1) {
                cfg.setProperty(PropertyId.SpeechServiceConnection_LanguageIdMode, "Continuous")
            } else if (langs.size == 1) {
                cfg.speechRecognitionLanguage = langs[0]
            }
            config = cfg

            // SDK 这个重载要的是 short，Kotlin 不会自动窄化，得显式转
            val format = AudioStreamFormat.getWaveFormatPCM(
                sampleRate.toLong(), 16.toShort(), 1.toShort()
            )
            val stream = AudioInputStream.createPushStream(format)
            push = stream
            val ac = AudioConfig.fromStreamInput(stream)
            audioConfig = ac

            val rec = if (langs.size > 1) {
                TranslationRecognizer(cfg, AutoDetectSourceLanguageConfig.fromLanguages(langs), ac)
            } else {
                TranslationRecognizer(cfg, ac)
            }
            recognizer = rec

            rec.recognizing.addEventListener { _, e ->
                emit(e.result.text, e.result.translations, false, onCaption)
            }
            rec.recognized.addEventListener { _, e ->
                emit(e.result.text, e.result.translations, true, onCaption)
            }
            rec.canceled.addEventListener { _, e ->
                onError("Azure 已断开：${e.errorCode} ${e.errorDetails}")
            }

            rec.startContinuousRecognitionAsync().get()
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            onError("Azure 启动失败：${t.message}")
        }
    }

    private fun emit(
        text: String?,
        translations: Map<String, String>?,
        isFinal: Boolean,
        onCaption: (Caption) -> Unit
    ) {
        val original = text?.trim().orEmpty()
        if (original.isEmpty()) return
        // Azure 返回的 key 大小写可能与请求不完全一致，兜底做一次忽略大小写查找
        val translated = translations?.let { m ->
            m[s.targetLang] ?: m.entries.firstOrNull { it.key.equals(s.targetLang, true) }?.value
        }?.trim()?.takeIf { it.isNotEmpty() }
        onCaption(Caption(original, translated, isFinal))
    }

    override fun feed(pcm: ByteArray, len: Int) {
        val stream = push ?: return
        try {
            stream.write(if (len == pcm.size) pcm else pcm.copyOf(len))
        } catch (t: Throwable) {
            Log.w(TAG, "feed failed: ${t.message}")
        }
    }

    override fun stop() {
        runCatching { recognizer?.stopContinuousRecognitionAsync()?.get() }
        runCatching { push?.close() }
        runCatching { recognizer?.close() }
        runCatching { audioConfig?.close() }
        runCatching { config?.close() }
        push = null; recognizer = null; audioConfig = null; config = null
    }

    private companion object {
        const val TAG = "AzureSpeech"
    }
}
