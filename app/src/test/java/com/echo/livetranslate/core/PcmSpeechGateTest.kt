package com.echo.livetranslate.core

import org.junit.Assert.*
import org.junit.Test

class PcmSpeechGateTest {
    private fun frame(id: Int, speech: Boolean = false) = ByteArray(640).apply {
        this[0] = id.toByte()
        this[1] = if (speech) 1 else 0
    }

    @Test fun speechStartsImmediatelyWithOnlyRecentUnsentPreRoll() {
        val sent = mutableListOf<Int>()
        val gate = PcmSpeechGate({ it[1] == 1.toByte() }, { pcm, _ -> sent.add(pcm[0].toInt()) })
        for (i in 1..100) gate.feed(frame(i), 640)
        assertTrue(sent.isEmpty())
        gate.feed(frame(101, true), 640)
        assertEquals((81..101).toList(), sent) // 当前帧就发，无额外起声等待。
        gate.feed(frame(102, true), 640)
        assertEquals((81..102).toList(), sent)
    }

    @Test fun preservesEightHundredMsTailAndDoesNotRepeatUploadedAudio() {
        val sent = mutableListOf<Int>()
        val gate = PcmSpeechGate({ it[1] == 1.toByte() }, { pcm, _ -> sent.add(pcm[0].toInt()) })
        gate.feed(frame(1, true), 640)
        for (i in 2..60) gate.feed(frame(i), 640)
        assertEquals((1..41).toList(), sent)
        gate.feed(frame(61, true), 640)
        assertEquals((1..61).toList(), sent)
    }

    @Test fun arbitraryReadBoundariesPreserveEveryByteAndIgnoreUnusedBuffer() {
        val source = ByteArray(640 * 5) { (it % 127).toByte() }
        val sent = java.io.ByteArrayOutputStream()
        val gate = PcmSpeechGate({ true }, { pcm, len -> sent.write(pcm, 0, len) })
        var offset = 0
        while (offset < source.size) {
            val count = minOf(317, source.size - offset)
            val buffer = ByteArray(count + 20) { 99 }
            source.copyInto(buffer, 0, offset, offset + count)
            gate.feed(buffer, count)
            offset += count
        }
        assertArrayEquals(source, sent.toByteArray())
    }
}
