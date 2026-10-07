package com.echo.livetranslate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.echo.livetranslate.MainActivity
import com.echo.livetranslate.R
import com.echo.livetranslate.core.AudioCapture
import com.echo.livetranslate.core.CaptionPipeline
import com.echo.livetranslate.data.EngineMode
import com.echo.livetranslate.data.AsrProvider
import com.echo.livetranslate.data.HistoryStore
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.data.SettingsStore
import com.echo.livetranslate.ocr.ScreenTextReader
import com.echo.livetranslate.provider.AsrProviderFactory
import com.echo.livetranslate.provider.Caption
import com.echo.livetranslate.provider.SpeechProvider
import com.echo.livetranslate.provider.QwenLiveTranslateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 字幕的总闸。前台服务持有 MediaProjection、采集、识别、翻译和悬浮窗，
 * 全程只有这一个实例，退出时统一收尾。
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var audio: AudioCapture? = null
    @Volatile private var speech: SpeechProvider? = null
    private var ocr: ScreenTextReader? = null
    // 识别回调线程在读它，设置监听线程可能在换它
    @Volatile private var pipeline: CaptionPipeline? = null
    private var overlay: OverlayController? = null
    private var sessionId = 0L
    private var pipelineSignature: List<Any> = emptyList()

    @Volatile private var settings = Settings()
    @Volatile private var paused = false
    @Volatile private var lastErrorShownAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户在系统弹窗里点了「停止共享」
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14 起必须先进前台、且声明 mediaProjection 类型，才能拿到投屏对象
        startInForeground()

        scope.launch {
            val s = SettingsStore.flow(this@CaptureService).first()
            settings = s
            main.post { startEverything(resultCode, resultData, s) }
            observeSettings()
        }

        return START_STICKY
    }

    private fun startEverything(resultCode: Int, resultData: Intent, s: Settings) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = runCatching { manager.getMediaProjection(resultCode, resultData) }.getOrNull()
        if (mp == null) {
            toast("投屏授权失效，请重新开启")
            stopSelf()
            return
        }
        projection = mp
        mp.registerCallback(projectionCallback, main)

        overlay = OverlayController(this, ::togglePaused, ::toggleTokenMode, {
            toast("字幕已关闭")
            stopSelf()
        }) { x, y ->
            scope.launch { SettingsStore.save(this@CaptureService, settings.copy(overlayX = x, overlayY = y)) }
        }.also { it.show(s) }

        val history = HistoryStore.get(this)
        sessionId = runCatching { history.beginSession(s.engineMode) }.getOrDefault(0L)

        buildPipeline(s)

        when (s.engineMode) {
            EngineMode.AUDIO -> startAudio(mp, s)
            EngineMode.OCR -> startOcr(mp, s)
        }
    }

    private fun startAudio(mp: MediaProjection, s: Settings) {
        val uid = runCatching { packageManager.getApplicationInfo(s.capturePackage, 0).uid }.getOrNull()
        if (uid == null) {
            toast("所选应用已卸载或无法访问，请重新选择")
            stopSelf()
            return
        }
        startSpeech(s)
        audio = AudioCapture(
            projection = mp,
            targetSampleRate = speech?.sampleRate ?: 16000,
            targetUid = uid,
            onPcm = { buffer, len -> if (!paused) speech?.feed(buffer, len) },
            onError = { message -> if (!paused) reportError(message) }
        ).also { it.start() }
    }

    private fun startSpeech(s: Settings) {
        val provider = AsrProviderFactory.create(s)
        speech = provider
        overlay?.showMessage("正在连接，播放所选应用的视频后将显示字幕…")
        scope.launch(Dispatchers.IO) {
            synchronized(provider) {
                if (speech !== provider || paused) return@launch
                provider.start(
                    onCaption = { caption -> if (!paused && speech === provider) pipeline?.submit(caption) },
                    onError = { message -> main.post { if (!paused && speech === provider) reportError(message) } }
                )
            }
        }
    }

    private fun stopSpeech() {
        val provider = speech
        speech = null
        provider?.let { synchronized(it) { it.stop() } }
    }

    private fun togglePaused() {
        paused = !paused
        overlay?.setPaused(paused)
        if (paused) {
            stopSpeech()
            pipeline?.close()
            pipeline = null
            overlay?.showMessage("字幕已暂停 · 双击继续")
            toast("字幕已暂停，停止翻译请求；双击继续")
        } else {
            buildPipeline(settings)
            if (settings.engineMode == EngineMode.AUDIO) startSpeech(settings)
            else overlay?.showMessage("字幕已继续，等待画面上的字幕…")
            toast("字幕已继续")
        }
    }

    private fun toggleTokenMode() {
        if (settings.engineMode != EngineMode.AUDIO || settings.asrProvider != AsrProvider.QWEN_LIVE) {
            toast("省 Token 模式仅用于千问音频翻译")
            return
        }
        scope.launch {
            val enabled = SettingsStore.toggleQwenSpeechFilter(this@CaptureService)
            main.post {
                (speech as? QwenLiveTranslateProvider)?.setSpeechFilter(enabled)
                val message = "省 Token 模式已${if (enabled) "开启" else "关闭"}"
                if (paused) overlay?.showMessage("字幕已暂停 · 双击继续\n$message")
                toast(message)
            }
        }
    }

    private fun startOcr(mp: MediaProjection, s: Settings) {
        overlay?.showMessage("OCR 模式已启动，等待画面上的字幕…")
        ocr = ScreenTextReader(
            projection = mp,
            metrics = resources.displayMetrics,
            script = s.ocrScript,
            bottomPct = s.ocrBottomPct,
            intervalMs = s.ocrIntervalMs.toLong(),
            onText = { text -> if (!paused) pipeline?.submit(Caption(text, null, true)) },
            onError = { message -> if (!paused) reportError(message) }
        ).also { it.start() }
    }

    private fun buildPipeline(s: Settings) {
        val history = HistoryStore.get(this)
        pipeline?.close()
        pipeline = CaptionPipeline(
            scope = scope,
            settings = s,
            onState = { state -> overlay?.update(state) },
            onFinalLine = { original, translated ->
                scope.launch(Dispatchers.IO) { history.appendLine(sessionId, original, translated) }
            },
            onError = { message -> reportError(message, replacesCaption = false) }
        )
        pipelineSignature = s.translationSignature()
    }

    /**
     * 设置改了就热更新：外观直接刷，翻译配置变了重建管线。
     * 换识别服务商这类要重连的改动仍需停止后重开。
     */
    private suspend fun observeSettings() {
        SettingsStore.flow(this).collect { s ->
            main.post {
                settings = s
                overlay?.apply(s)
                (speech as? QwenLiveTranslateProvider)?.setSpeechFilter(s.qwenSpeechFilter)
                (speech as? QwenLiveTranslateProvider)?.setAutoRenew(s.qwenAutoRenew)
                (speech as? QwenLiveTranslateProvider)?.setRetryDelay(s.qwenRetryDelayMs)
                if (pipeline != null && s.translationSignature() != pipelineSignature) buildPipeline(s)
            }
        }
    }

    /**
     * [replacesCaption] 区分两类错误：连接断了没字幕可显示，占用字幕位置是对的；
     * 翻译只是这一句没拿到译文，原文还好好的，擦掉整条反而更糟。
     */
    private fun reportError(message: String, replacesCaption: Boolean = true) {
        Log.w(TAG, message)
        val now = System.currentTimeMillis()
        val retrying = message.startsWith("失败，") && message.endsWith("秒后重试")
        if (!retrying && now - lastErrorShownAt < ERROR_THROTTLE_MS) return
        lastErrorShownAt = now
        main.post {
            if (retrying) overlay?.showRetry(message)
            else {
                if (replacesCaption) overlay?.showMessage(message)
                toast(message)
            }
        }
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("字幕运行中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        running = false
        paused = true
        runCatching { audio?.stop() }
        runCatching { ocr?.stop() }
        runCatching { stopSpeech() }
        runCatching { pipeline?.close() }
        runCatching { overlay?.hide() }
        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }
        val id = sessionId
        if (id > 0) {
            // 空会话不留在历史里
            runCatching { HistoryStore.get(applicationContext).dropIfEmpty(id) }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onScreenChanged()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "captions"
        private const val NOTIFICATION_ID = 42
        private const val ERROR_THROTTLE_MS = 6000L

        const val ACTION_STOP = "com.echo.livetranslate.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        @Volatile var running = false
            private set

        fun launch(context: Context, resultCode: Int, data: Intent) {
            running = true
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            running = false
            context.startService(
                Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
