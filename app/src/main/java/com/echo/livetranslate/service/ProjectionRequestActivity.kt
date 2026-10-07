package com.echo.livetranslate.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 一个只负责弹投屏授权的透明页。放在单独的 Activity 里，
 * 通知栏、悬浮窗或设置页都能发起，不用把逻辑绑在主界面上。
 */
class ProjectionRequestActivity : AppCompatActivity() {

    private val request = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.launch(this, result.resultCode, data)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // 音频由应用 UID 过滤；使用默认显示器授权避免误选本字幕应用。
        val consent = if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        request.launch(consent)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, ProjectionRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
