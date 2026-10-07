package com.echo.livetranslate.provider

/**
 * 一条字幕。partial 会被后续内容覆盖，final 是这句话的定稿。
 * [translated] 只有识别端自带翻译时才非空，否则由管线补上。
 */
data class Caption(
    val original: String,
    val translated: String? = null,
    val isFinal: Boolean = false
)

/**
 * 流式语音识别服务商。生命周期：start → 持续 feed → stop。
 * 实现必须容忍在任意时刻被 stop，并保证回调发生在非阻塞的线程上。
 */
interface SpeechProvider {

    /** 送进来的 PCM 采样率，音频采集端会按这个值重采样。 */
    val sampleRate: Int get() = 16000

    fun start(onCaption: (Caption) -> Unit, onError: (String) -> Unit)

    /** [pcm] 为 16-bit 小端单声道，长度 [len] 字节。 */
    fun feed(pcm: ByteArray, len: Int)

    fun stop()
}
