package com.echo.livetranslate.service

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.echo.livetranslate.core.CaptionState
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.data.AsrProvider
import kotlin.math.roundToInt

/**
 * 屏幕上那条字幕。用原生 View 而不是 Compose：悬浮窗每秒会刷新很多次，
 * 少一层组合和重组就少一分掉帧的机会。
 */
class OverlayController(
    private val context: Context,
    private val onDoubleTap: () -> Unit,
    private val onFourTaps: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onMoved: (x: Int, y: Int) -> Unit
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var originalView: TextView? = null
    private var translatedView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var settings = Settings()
    private var paused = false
    private var lastCaption = CaptionState()
    private var retryMessage: String? = null
    private val touchHandler = Handler(Looper.getMainLooper())
    private val taps = SubtitleTaps()
    private val finishTaps = Runnable { if (taps.finish()) onDoubleTap() }
    private var longPressed = false
    private val closeOnHold = Runnable {
        longPressed = true
        taps.cancel()
        onLongPress()
    }

    fun show(initial: Settings) {
        if (root != null) {
            apply(initial)
            return
        }
        settings = initial

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(10)
            setPadding(pad, dp(6), pad, dp(6))
        }

        val original = TextView(context).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.05f)
        }
        val translated = TextView(context).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.05f)
        }

        container.addView(
            original,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            translated,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val lp = WindowManager.LayoutParams(
            widthFor(initial),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseFlags(initial.locked),
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initial.overlayX
            y = if (initial.overlayY >= 0) initial.overlayY else defaultY()
        }

        attachGestures(container, lp)

        root = container
        originalView = original
        translatedView = translated
        params = lp

        windowManager.addView(container, lp)
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampPosition() }
        apply(initial)
    }

    private fun attachGestures(view: View, lp: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop

        view.setOnTouchListener { _, event ->
            if (settings.locked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchHandler.removeCallbacks(finishTaps)
                    touchHandler.removeCallbacks(closeOnHold)
                    longPressed = false
                    touchHandler.postDelayed(closeOnHold, 6000L)
                    downX = event.rawX; downY = event.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - downX) > slop || kotlin.math.abs(event.rawY - downY) > slop) moved = true
                    if (!moved) return@setOnTouchListener true
                    touchHandler.removeCallbacks(closeOnHold)
                    taps.cancel()
                    lp.x = (startX + (event.rawX - downX).roundToInt()).coerceIn(0, maxX())
                    lp.y = (startY + (event.rawY - downY).roundToInt()).coerceIn(0, maxY())
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    touchHandler.removeCallbacks(closeOnHold)
                    if (longPressed) return@setOnTouchListener true
                    if (moved) onMoved(lp.x, lp.y)
                    else if (event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()) {
                        view.performClick()
                        if (taps.tap()) onFourTaps()
                        else touchHandler.postDelayed(finishTaps, ViewConfiguration.getDoubleTapTimeout().toLong())
                    } else taps.cancel()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchHandler.removeCallbacks(finishTaps)
                    touchHandler.removeCallbacks(closeOnHold)
                    taps.cancel()
                    true
                }
                else -> false
            }
        }
    }

    /** 设置变了就整体重刷一遍样式，比逐项 diff 简单且开销可以忽略。 */
    fun apply(s: Settings) {
        if (s.locked) {
            touchHandler.removeCallbacks(finishTaps)
            touchHandler.removeCallbacks(closeOnHold)
            taps.cancel()
        }
        settings = s
        val container = root ?: return
        val lp = params ?: return

        val background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(s.backgroundArgb)
        }
        container.background = background

        originalView?.applyStyle(s, s.originalColor)
        translatedView?.applyStyle(s, s.translatedColor)

        originalView?.visibility = if (paused || s.showOriginal) View.VISIBLE else View.GONE
        translatedView?.visibility = if (s.bilingual || !s.showOriginal) View.VISIBLE else View.GONE
        if (retryMessage != null && !paused) renderCaption()

        lp.width = widthFor(s)
        lp.flags = baseFlags(s.locked)
        // Android 12+ 对不可信悬浮窗的触摸穿透有透明度上限。
        lp.alpha = if (s.locked && Build.VERSION.SDK_INT >= 31)
            context.getSystemService(android.hardware.input.InputManager::class.java)
                .maximumObscuringOpacityForTouch else 1f
        if (s.overlayY >= 0) lp.y = s.overlayY
        lp.x = s.overlayX
        clampPosition()
        runCatching { windowManager.updateViewLayout(container, lp) }
    }

    private fun TextView.applyStyle(s: Settings, color: Int) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, s.fontSizeSp.toFloat())
        setTextColor(color)
        maxLines = s.maxLines.coerceAtLeast(1)
        if (s.outlineText) {
            // 描边让字幕在亮画面上也读得清，比加深背景更不挡视频
            setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.BLACK)
        } else {
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    fun update(state: CaptionState) {
        root?.post {
            if (paused) return@post
            lastCaption = state
            retryMessage = null
            renderCaption()
        }
    }

    private fun renderCaption() {
        val suffix = retryMessage?.let { "（$it）" }.orEmpty()
        val translated = if (settings.asrProvider == AsrProvider.QWEN_LIVE)
            lastCaption.translated.takeLast(100) else lastCaption.translated
        val onOriginal = settings.showOriginal && (!settings.bilingual || translated.isBlank()) && lastCaption.original.isNotBlank()
        originalView?.visibility = if (settings.showOriginal) View.VISIBLE else View.GONE
        translatedView?.visibility = if (settings.bilingual || !settings.showOriginal || (suffix.isNotEmpty() && !onOriginal)) View.VISIBLE else View.GONE
        originalView?.text = lastCaption.original + if (onOriginal) suffix else ""
        translatedView?.text = translated + if (!onOriginal) suffix else ""
        val lines = if (suffix.isNotEmpty()) maxOf(settings.maxLines, MESSAGE_MAX_LINES) else settings.maxLines.coerceAtLeast(1)
        originalView?.maxLines = lines
        translatedView?.maxLines = lines
    }

    fun showRetry(message: String) {
        root?.post {
            if (paused) return@post
            retryMessage = message
            renderCaption()
        }
    }

    fun setPaused(value: Boolean) { paused = value }

    /** 错误提示不受字幕行数限制——被截断的报错等于没报。 */
    fun showMessage(text: String) {
        root?.post {
            retryMessage = null
            lastCaption = CaptionState()
            originalView?.maxLines = MESSAGE_MAX_LINES
            originalView?.visibility = View.VISIBLE
            originalView?.text = text
            translatedView?.text = ""
        }
    }

    fun hide() {
        touchHandler.removeCallbacks(finishTaps)
        touchHandler.removeCallbacks(closeOnHold)
        taps.cancel()
        val container = root ?: return
        runCatching { windowManager.removeView(container) }
        root = null; originalView = null; translatedView = null; params = null
    }

    private fun widthFor(s: Settings): Int {
        val screen = context.resources.displayMetrics.widthPixels
        return (screen * s.overlayWidthPct.coerceIn(30, 100) / 100)
    }

    private fun defaultY(): Int =
        (context.resources.displayMetrics.heightPixels * 0.78f).roundToInt()

    fun onScreenChanged() {
        val lp = params ?: return
        lp.width = widthFor(settings)
        // 从竖屏进入电影横屏时重新贴底，随后仍可自由拖动。
        lp.x = ((context.resources.displayMetrics.widthPixels - lp.width) / 2).coerceAtLeast(0)
        lp.y = defaultY()
        root?.let { windowManager.updateViewLayout(it, lp) }
        root?.post { clampPosition(); onMoved(lp.x, lp.y) }
    }

    private fun maxX() = (context.resources.displayMetrics.widthPixels - (params?.width ?: 0)).coerceAtLeast(0)
    private fun maxY() = (context.resources.displayMetrics.heightPixels - (root?.height ?: 0) - dp(24)).coerceAtLeast(0)

    private fun clampPosition() {
        val lp = params ?: return
        val x = lp.x.coerceIn(0, maxX())
        val y = lp.y.coerceIn(0, maxY())
        if (x == lp.x && y == lp.y) return
        lp.x = x; lp.y = y
        root?.let { runCatching { windowManager.updateViewLayout(it, lp) } }
    }

    private fun baseFlags(locked: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // 锁定后触摸直接穿透给下面的播放器，滑视频不会被字幕挡住
        if (locked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val MESSAGE_MAX_LINES = 6
    }
}

/** 双击等连击结束再确认，四击立即确认，避免四击同时触发暂停。 */
internal class SubtitleTaps {
    private var count = 0
    fun tap(): Boolean = (++count == 4).also { if (it) count = 0 }
    fun finish(): Boolean = (count == 2).also { count = 0 }
    fun cancel() { count = 0 }
}
