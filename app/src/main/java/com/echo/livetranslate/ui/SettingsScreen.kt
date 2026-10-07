package com.echo.livetranslate.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.livetranslate.data.AsrProvider
import com.echo.livetranslate.data.EngineMode
import com.echo.livetranslate.data.OcrScript
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.data.TransProvider

private val TARGET_LANGS = listOf(
    "zh-Hans" to "简体中文",
    "zh-Hant" to "繁體中文",
    "en" to "English",
    "ja" to "日本語",
    "ko" to "한국어",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "ru" to "Русский"
)

@Composable
fun SettingsScreen(
    s: Settings,
    running: Boolean,
    hasOverlay: Boolean,
    onChange: (Settings) -> Unit,
    onRequestOverlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        SubtitlePreview(s)

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasOverlay) {
                Button(onClick = onRequestOverlay, modifier = Modifier.weight(1f)) {
                    Text("① 授予悬浮窗权限")
                }
            } else if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("停止字幕") }
            } else {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("开始字幕") }
            }
            OutlinedButton(onClick = onOpenHistory) { Text("历史") }
        }

        s.validate()?.let { warning ->
            Text(
                "⚠ $warning",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        Section("引擎", "音频模式听系统声音；遇到 DRM 视频（Netflix 等）抓不到声音时切 OCR") {
            Picker("模式", s.engineMode, EngineMode.entries, { it.label }) {
                onChange(s.copy(engineMode = it))
            }
        }

        if (s.engineMode == EngineMode.AUDIO) {
            CaptureAppSection(s, onChange)
            AudioSection(s, onChange)
        } else {
            OcrSection(s, onChange)
        }

        TranslateSection(s, onChange)
        AppearanceSection(s, onChange)
    }
}

@Composable
private fun CaptureAppSection(s: Settings, onChange: (Settings) -> Unit) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedWith(compareBy<Pair<String, String>> { !it.second.contains("pikpak", true) }
                .thenBy { it.second.lowercase() })
    }
    Section("捕获应用", "只捕获所选应用的内部声音，不使用麦克风。更换应用后需停止再开启。") {
        Picker("应用", s.capturePackage, listOf("") + apps.map { it.first }, { pkg ->
            if (pkg.isBlank()) "请选择 PikPak 或其他视频应用"
            else apps.firstOrNull { it.first == pkg }?.second ?: pkg
        }) { onChange(s.copy(capturePackage = it)) }
    }
}

@Composable
private fun AudioSection(s: Settings, onChange: (Settings) -> Unit) {
    Section("实时语音翻译", "千问和 Azure 可直接流式返回译文。更换服务或 Key 后需停止再开启。") {
        Picker("服务商", s.asrProvider, AsrProvider.entries, { it.label }) {
            onChange(if (it.oneShotTranslate) s.copy(
                asrProvider = it, transProvider = TransProvider.NONE,
                showOriginal = false, bilingual = false
            ) else s.copy(asrProvider = it))
        }

        when (s.asrProvider) {
            AsrProvider.QWEN_LIVE -> {
                Field("千问AI平台 API Key", s.qwenKey, secret = true,
                    hint = "使用千问官网按量付费的 Key；字幕为中文，源语言自动识别。") {
                    onChange(s.copy(qwenKey = it))
                }
                Toggle("省流量 / Token：本地人声检测", s.qwenSpeechFilter) {
                    onChange(s.copy(qwenSpeechFilter = it))
                }
                Picker("失败后重试等待", s.qwenRetryDelayMs,
                    listOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000, 3000, 5000, 10000),
                    { "${it / 1000.0} 秒" }) { onChange(s.copy(qwenRetryDelayMs = it)) }
                Text("下次失败时使用新等待时间；保留上一条字幕，在后面显示重试提示。",
                    style = MaterialTheme.typography.bodySmall)
                Toggle("每 5 分钟重连", s.qwenAutoRenew, "重建会话，清除旧会话上下文。切换立即生效；可能短暂中断字幕，节省费用的效果尚未确认。") {
                    onChange(s.copy(qwenAutoRenew = it))
                }
                Text("宽松检测，保留句首 400ms、句尾 800ms；讲话时立即上传。若漏对白请关闭，切换立即生效。字幕条双击暂停/继续，连续四击切换省 Token 模式，按住超过 6 秒关闭字幕；请先解除位置锁定。",
                    style = MaterialTheme.typography.bodySmall)
            }
            AsrProvider.AZURE -> {
                Field("Azure Speech Key", s.azureKey, secret = true) {
                    onChange(s.copy(azureKey = it))
                }
                Field("区域", s.azureRegion, hint = "德国中西部填 germanywestcentral，不要填网址。") {
                    onChange(s.copy(azureRegion = it))
                }
                Field(
                    "源语言", s.azureSourceLangs,
                    hint = "看日本电影填 ja-JP。多语种可用逗号分隔，如 ja-JP,en-US。"
                ) { onChange(s.copy(azureSourceLangs = it)) }
                Text("译文由 Azure 直接返回，无需另外配置文本翻译。双击字幕条暂停/继续，按住超过 6 秒关闭；请先解除位置锁定。四击省 Token 模式仅用于千问。",
                    style = MaterialTheme.typography.bodySmall)
            }

            AsrProvider.DEEPGRAM -> {
                Field("Deepgram API Key", s.deepgramKey, secret = true) {
                    onChange(s.copy(deepgramKey = it))
                }
                Field("接口地址", s.deepgramUrl) { onChange(s.copy(deepgramUrl = it)) }
                Field("模型", s.deepgramModel, hint = "nova-3 最准，nova-2 更便宜") {
                    onChange(s.copy(deepgramModel = it))
                }
                Field(
                    "语言", s.deepgramLanguage,
                    hint = "只能填一个代码，不要用逗号列多个。\n" +
                        "固定一种语言最准最快，例如 en / ja / ko；\n" +
                        "内容会在多种语言间切换时才填 multi"
                ) { onChange(s.copy(deepgramLanguage = it)) }
            }

            AsrProvider.OPENAI_REALTIME -> {
                Field("API Key", s.realtimeKey, secret = true) { onChange(s.copy(realtimeKey = it)) }
                Field("WebSocket 地址", s.realtimeUrl, hint = "换成中转/自建地址也可以") {
                    onChange(s.copy(realtimeUrl = it))
                }
                Field("转写模型", s.realtimeModel) { onChange(s.copy(realtimeModel = it)) }
                Field("语言", s.realtimeLanguage, hint = "留空=自动判断；填 en / ja 之类可提升准确度") {
                    onChange(s.copy(realtimeLanguage = it))
                }
            }
        }
    }
}

@Composable
private fun OcrSection(s: Settings, onChange: (Settings) -> Unit) {
    Section("OCR", "识别在手机本地完成，不走网络，只有翻译需要联网") {
        Picker("识别文字", s.ocrScript, OcrScript.entries, { it.label }) {
            onChange(s.copy(ocrScript = it))
        }
        SliderRow("扫描间隔", s.ocrIntervalMs, 300..2000, " ms") {
            onChange(s.copy(ocrIntervalMs = it))
        }
        SliderRow("扫描屏幕下方", s.ocrBottomPct, 10..60, "%") {
            onChange(s.copy(ocrBottomPct = it))
        }
    }
}

@Composable
private fun TranslateSection(s: Settings, onChange: (Settings) -> Unit) {
    if (s.engineMode == EngineMode.AUDIO && s.asrProvider == AsrProvider.QWEN_LIVE) {
        Section("中文字幕", "由千问直接翻译，不额外调用文本翻译，也不生成配音。") {
            Text("日语 / 其他源语言 → 中文")
        }
        return
    }
    Section(
        "翻译",
        if (s.translationComesFromAsr) "当前由语音服务直接给出译文，无需再配"
        else "识别服务只出原文，需要在这里配一个翻译服务"
    ) {
        Picker("目标语言", s.targetLang, TARGET_LANGS.map { it.first },
            { code -> TARGET_LANGS.firstOrNull { it.first == code }?.second ?: code }) {
            onChange(s.copy(targetLang = it))
        }

        Picker("服务商", s.transProvider, TransProvider.entries, { it.label }) {
            onChange(s.copy(transProvider = it))
        }

        when (s.transProvider) {
            TransProvider.NONE -> Unit

            TransProvider.BAIDU -> {
                Field("APP ID", s.baiduAppId, hint = "fanyi-api.baidu.com 控制台，个人实名即可") {
                    onChange(s.copy(baiduAppId = it))
                }
                Field("密钥", s.baiduSecret, secret = true) { onChange(s.copy(baiduSecret = it)) }
                SliderRow("QPS 上限", s.baiduQps, 1..10, " 次/秒") {
                    onChange(s.copy(baiduQps = it))
                }
                Text(
                    "标准版 1，高级版 10。填高了也不会一直报错——撞到限速会自动降速，" +
                        "稳定后再慢慢收回来。\n" +
                        "注意：实名认证只是拿到资格，还要去百度控制台把服务版本手动切到高级版，" +
                        "QPS 才真的变成 10。\n" +
                        "填 1 时只翻译整句，中文会在一句话说完后才出现。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TransProvider.OPENAI_COMPAT -> {
                Field(
                    "接口地址", s.openaiTransUrl,
                    hint = "DeepSeek: https://api.deepseek.com/chat/completions\n" +
                        "本地 Ollama: http://127.0.0.1:11434/v1/chat/completions"
                ) { onChange(s.copy(openaiTransUrl = it)) }
                Field("API Key", s.openaiTransKey, secret = true) {
                    onChange(s.copy(openaiTransKey = it))
                }
                Field(
                    "模型", s.openaiTransModel,
                    hint = "大模型首字延迟 1-3 秒，字幕会明显滞后。\n" +
                        "追求低延迟请改用上面的百度翻译"
                ) { onChange(s.copy(openaiTransModel = it)) }
            }

            TransProvider.DEEPL -> {
                Field("DeepL Key", s.deeplKey, secret = true) { onChange(s.copy(deeplKey = it)) }
                Toggle("使用免费版接口", s.deeplFreeTier, "Key 结尾带 :fx 的选这个") {
                    onChange(s.copy(deeplFreeTier = it))
                }
            }

            TransProvider.GOOGLE -> {
                Field("Google API Key", s.googleKey, secret = true) {
                    onChange(s.copy(googleKey = it))
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(s: Settings, onChange: (Settings) -> Unit) {
    Section("字幕外观") {
        if (s.asrProvider != AsrProvider.QWEN_LIVE || s.engineMode != EngineMode.AUDIO) {
        Toggle("双语字幕", s.bilingual, "上面原文，下面译文") { onChange(s.copy(bilingual = it)) }
        Toggle("显示原文", s.showOriginal, "关掉就只剩译文一行") { onChange(s.copy(showOriginal = it)) }
        }
        Toggle("文字描边", s.outlineText, "亮画面上也能看清，比加深背景更不挡视频") {
            onChange(s.copy(outlineText = it))
        }
        Toggle("锁定位置", s.locked, "锁定后触摸会穿透字幕，滑视频不受影响") {
            onChange(s.copy(locked = it))
        }

        SliderRow("字号", s.fontSizeSp, 12..36, " sp") { onChange(s.copy(fontSizeSp = it)) }
        SliderRow("每段最多行数", s.maxLines, 1..4, " 行") { onChange(s.copy(maxLines = it)) }
        SliderRow("宽度", s.overlayWidthPct, 40..100, "%") { onChange(s.copy(overlayWidthPct = it)) }
        SliderRow("背景不透明度", s.backgroundAlpha, 0..100, "%") {
            onChange(s.copy(backgroundAlpha = it))
        }

        ColorPicker("背景颜色", s.backgroundColor) { onChange(s.copy(backgroundColor = it)) }
        ColorPicker("原文颜色", s.originalColor) { onChange(s.copy(originalColor = it)) }
        ColorPicker("译文颜色", s.translatedColor) { onChange(s.copy(translatedColor = it)) }
    }
}

/** 所见即所得：改颜色和透明度不用反复开关服务去看效果。 */
@Composable
private fun SubtitlePreview(s: Settings) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Color(0xFF1B2430), RoundedCornerShape(12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(s.overlayWidthPct / 100f)
                .background(Color(s.backgroundArgb), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (s.showOriginal) {
                Text(
                    "This is what the subtitle looks like.",
                    color = Color(s.originalColor),
                    fontSize = s.fontSizeSp.sp,
                    textAlign = TextAlign.Center,
                    maxLines = s.maxLines
                )
            }
            if (s.bilingual || !s.showOriginal) {
                Text(
                    "字幕大概长这样。",
                    color = Color(s.translatedColor),
                    fontSize = s.fontSizeSp.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = s.maxLines
                )
            }
        }
    }
}
