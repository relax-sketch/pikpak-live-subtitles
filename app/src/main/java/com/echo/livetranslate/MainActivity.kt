package com.echo.livetranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.data.SettingsStore
import com.echo.livetranslate.service.CaptureService
import com.echo.livetranslate.service.ProjectionRequestActivity
import com.echo.livetranslate.ui.AppTheme
import com.echo.livetranslate.ui.HistoryScreen
import com.echo.livetranslate.ui.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ProjectionRequestActivity.start(this)
        else Toast.makeText(this, "内部音频捕获需要录音权限", Toast.LENGTH_LONG).show()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也能跑，只是通知栏没有停止按钮 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent { AppTheme { Root() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var settings by remember { mutableStateOf(Settings()) }
        var showHistory by remember { mutableStateOf(false) }
        var hasOverlay by remember { mutableStateOf(canDrawOverlay()) }
        var running by remember { mutableStateOf(CaptureService.running) }

        // 只在进入时读一次：之后的写入以界面上的值为准，避免回流把正在输入的内容冲掉
        LaunchedEffect(Unit) {
            settings = SettingsStore.flow(context).first()
        }

        // 从系统权限页或投屏弹窗回来时，刷新一下开关状态
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasOverlay = canDrawOverlay()
                    running = CaptureService.running
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text(if (showHistory) "字幕记录" else getString(R.string.app_name)) })
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (showHistory) {
                    HistoryScreen(onBack = { showHistory = false })
                } else {
                    SettingsScreen(
                        s = settings,
                        running = running,
                        hasOverlay = hasOverlay,
                        onChange = { updated ->
                            settings = updated
                            scope.launch { SettingsStore.save(context, updated) }
                        },
                        onRequestOverlay = ::requestOverlay,
                        onStart = { scope.launch {
                            SettingsStore.save(context, settings)
                            start(settings)
                        } },
                        onStop = {
                            running = false
                            CaptureService.stop(this@MainActivity)
                        },
                        onOpenHistory = { showHistory = true }
                    )
                }
            }
        }
    }

    private fun start(settings: Settings) {
        val problem = settings.validate()
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }
        if (settings.engineMode == com.echo.livetranslate.data.EngineMode.AUDIO &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else ProjectionRequestActivity.start(this)
    }

    private fun canDrawOverlay(): Boolean = AndroidSettings.canDrawOverlays(this)

    private fun requestOverlay() {
        startActivity(
            Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }
}
