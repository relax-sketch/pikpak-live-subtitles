package com.echo.livetranslate.core

/** 16kHz PCM16：20ms 检测帧，讲话立即发送，只缓存尚未上传的非语音。 */
internal class PcmSpeechGate(
    private val isSpeech: (ByteArray) -> Boolean,
    private val emit: (ByteArray, Int) -> Unit
) {
    private var frame = ByteArray(640)
    private var filled = 0
    private var tail = 0
    private val preRoll = ArrayDeque<ByteArray>()

    fun feed(pcm: ByteArray, len: Int) {
        require(len in 0..pcm.size)
        var offset = 0
        while (offset < len) {
            val count = minOf(frame.size - filled, len - offset)
            pcm.copyInto(frame, filled, offset, offset + count)
            filled += count
            offset += count
            if (filled != frame.size) continue
            if (isSpeech(frame)) {
                while (preRoll.isNotEmpty()) preRoll.removeFirst().let { emit(it, it.size) }
                emit(frame, frame.size)
                tail = 40 // 800ms，给服务端断句和轻声句尾留足音频。
            } else if (tail > 0) {
                emit(frame, frame.size)
                tail--
            } else {
                preRoll.addLast(frame)
                if (preRoll.size > 20) preRoll.removeFirst() // 400ms 句首保护。
            }
            frame = ByteArray(640)
            filled = 0
        }
    }
}
