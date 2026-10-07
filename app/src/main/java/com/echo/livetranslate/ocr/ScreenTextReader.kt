package com.echo.livetranslate.ocr

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import com.echo.livetranslate.data.OcrScript
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OCR 备用模式：截屏，只看屏幕下方那一条，端侧识别硬字幕。
 *
 * 用在两种情况：应用屏蔽了内录（DRM 视频），或者本来就是无人声的纯字幕内容。
 * 识别在本机做，不产生网络延迟，只有翻译要走网络。
 */
class ScreenTextReader(
    private val projection: MediaProjection,
    private val metrics: DisplayMetrics,
    script: OcrScript,
    private val bottomPct: Int,
    private val intervalMs: Long,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val recognizer: TextRecognizer = when (script) {
        OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    private val running = AtomicBoolean(false)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** 一帧还在识别时不排下一帧，避免慢机器上越积越多 */
    private val busy = AtomicBoolean(false)
    private var lastText = ""

    fun start() {
        if (!running.compareAndSet(false, true)) return

        // 截半分辨率就够认字幕，像素少一半识别快接近一倍
        val width = metrics.widthPixels / 2
        val height = metrics.heightPixels / 2

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader

        thread = HandlerThread("ocr-capture").apply { start() }
        handler = Handler(thread!!.looper)

        display = runCatching {
            projection.createVirtualDisplay(
                "live-translate-ocr",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, handler
            )
        }.getOrElse {
            onError("无法创建屏幕镜像：${it.message}")
            running.set(false)
            return
        }

        handler!!.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            grabAndRecognize()
            handler?.postDelayed(this, intervalMs)
        }
    }

    private fun grabAndRecognize() {
        if (!busy.compareAndSet(false, true)) return
        val image = try {
            reader?.acquireLatestImage()
        } catch (t: Throwable) {
            Log.w(TAG, "acquire failed: ${t.message}"); null
        }
        if (image == null) {
            busy.set(false)
            return
        }

        val bitmap = try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val full = Bitmap.createBitmap(
                image.width + rowPadding / plane.pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
            cropSubtitleBand(full, image.width, image.height)
        } catch (t: Throwable) {
            Log.w(TAG, "bitmap failed: ${t.message}")
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            busy.set(false)
            return
        }

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val text = result.textBlocks
                    .joinToString(" ") { it.text.replace('\n', ' ') }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (text.isNotEmpty() && text != lastText && text.length >= MIN_CHARS) {
                    lastText = text
                    onText(text)
                }
            }
            .addOnFailureListener { Log.w(TAG, "ocr failed: ${it.message}") }
            .addOnCompleteListener {
                bitmap.recycle()
                busy.set(false)
            }
    }

    /** 只留屏幕底部这一条：字幕基本都在这，切掉其余部分能挡掉大量误识别。 */
    private fun cropSubtitleBand(full: Bitmap, width: Int, height: Int): Bitmap {
        val bandHeight = (height * bottomPct.coerceIn(10, 100) / 100).coerceAtLeast(1)
        val top = (height - bandHeight).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(full, 0, top, width, bandHeight)
        if (cropped !== full) full.recycle()
        return cropped
    }

    fun stop() {
        running.set(false)
        handler?.removeCallbacksAndMessages(null)
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { recognizer.close() }
        thread?.quitSafely()
        display = null; reader = null; handler = null; thread = null
    }

    private companion object {
        const val TAG = "ScreenTextReader"
        const val MIN_CHARS = 2
    }
}
