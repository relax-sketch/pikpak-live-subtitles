package com.echo.livetranslate.core

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用 MediaProjection 抓系统播放出来的声音（不经过麦克风，不需要外放）。
 *
 * 只能抓到 usage 为 MEDIA / GAME / UNKNOWN 且没有声明
 * `allowAudioPlaybackCapture=false` 的应用。DRM 视频（Netflix 等）会拿到静音流，
 * 这类内容得走 OCR 模式。
 */
class AudioCapture(
    private val projection: MediaProjection,
    private val targetSampleRate: Int,
    private val targetUid: Int,
    private val onPcm: (ByteArray, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUid(targetUid)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        // 优先直接按目标采样率采集，让框架做重采样；不行再退到 48k 自己降采样
        val rate = openRecord(config, targetSampleRate) ?: openRecord(config, 48000)
        if (rate == null) {
            running.set(false)
            onError("无法启动系统内录，请确认已授予录制权限且系统为 Android 10 以上")
            return
        }

        val rec = record!!
        try {
            rec.startRecording()
        } catch (e: Exception) {
            running.set(false)
            rec.release()
            record = null
            onError("无法开始内录，请重新授权并启动字幕：${e.message}")
            return
        }

        thread = Thread({ pump(rec, rate) }, "audio-capture").apply {
            priority = Thread.MAX_PRIORITY   // 采集线程被抢占会直接变成字幕延迟
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(config: AudioPlaybackCaptureConfiguration, rate: Int): Int? {
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val rec = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }.getOrNull() ?: return null

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        record = rec
        return rate
    }

    private fun pump(rec: AudioRecord, rate: Int) {
        // 每次读约 40ms，是延迟和唤醒次数之间比较合适的一档
        val frames = rate * CHUNK_MS / 1000
        val buffer = ByteArray(frames * 2)
        val needsDownsample = rate != targetSampleRate
        var silentReads = 0

        while (running.get()) {
            val read = try {
                rec.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                Log.w(TAG, "read failed: ${t.message}")
                break
            }
            if (read <= 0) {
                if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) break
                continue
            }

            if (isSilent(buffer, read)) {
                // 全静音很可能是目标 App 屏蔽了内录，提示一次就够了
                if (++silentReads == SILENT_HINT_READS) {
                    onError("持续没有采集到声音：该应用可能禁止了内录（DRM 视频常见），可切到 OCR 模式")
                }
            } else {
                silentReads = 0
            }

            if (needsDownsample) {
                val out = downsample(buffer, read, rate, targetSampleRate)
                onPcm(out, out.size)
            } else {
                onPcm(buffer, read)
            }
        }

        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    private fun isSilent(buf: ByteArray, len: Int): Boolean {
        var i = 0
        while (i + 1 < len) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            if (sample > SILENCE_FLOOR || sample < -SILENCE_FLOOR) return false
            i += 2
        }
        return true
    }

    /** 线性插值降采样。48k→16k 这种整数比场景下足够，且比滤波器版本省 CPU。 */
    private fun downsample(src: ByteArray, len: Int, from: Int, to: Int): ByteArray {
        val inSamples = len / 2
        val outSamples = (inSamples.toLong() * to / from).toInt()
        val out = ByteArray(outSamples * 2)
        val step = inSamples.toDouble() / outSamples
        for (i in 0 until outSamples) {
            val srcIndex = (i * step).toInt().coerceAtMost(inSamples - 1)
            val lo = src[srcIndex * 2]
            val hi = src[srcIndex * 2 + 1]
            out[i * 2] = lo
            out[i * 2 + 1] = hi
        }
        return out
    }

    fun stop() {
        running.set(false)
        thread?.join(500)
        thread = null
        record = null
    }

    private companion object {
        const val TAG = "AudioCapture"
        const val CHUNK_MS = 40
        const val SILENCE_FLOOR = 60
        /** 40ms 一次读，200 次约等于 8 秒 */
        const val SILENT_HINT_READS = 200
    }
}
