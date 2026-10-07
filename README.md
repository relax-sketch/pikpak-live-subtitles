# PikPak Live Subtitles

基于 [echo-yu2007/live-translate](https://github.com/echo-yu2007/live-translate) 修改，面向 Android 平板上的 PikPak 观影场景。已添加 **千问 3.8 流式中文字幕、单应用音频捕获、横屏悬浮字幕和 Azure 通道验证**。

1.1.8：失败时保留字幕，在后面追加 `（失败，0.5秒后重试）`，新字幕到达后清除提示。千问重试等待可选 0.1～10 秒，默认 0.5 秒；变更从下一次失败生效。双击暂停/继续，长按超过 6 秒关闭；四击切换千问人声过滤。

Key 在应用中填写，不包含在源码或 APK 中。使用 JDK 17/21 和 Android SDK 35，执行 `./gradlew :app:assembleRelease :app:testDebugUnitTest`，Windows 可执行 `./build-local.ps1`。个人测试包用本机 debug 签名；GitHub Actions 保留手动构建入口，版本发布页提供本地测试过的 APK。

完整设置和测试范围见 [使用说明](千问版使用说明.md)。以下为原项目介绍；其中跨服务商的延迟比较未经本项目实测。

Android 悬浮窗实时字幕。抓手机/平板**系统内部播放的声音**（不用外放、不走麦克风），
流式识别 + 翻译，以可调透明度的悬浮窗显示双语字幕。遇到禁止内录的应用可切到 OCR 模式读硬字幕。

所有识别和翻译都走**你自己的 API Key**，应用内填写，不经过第三方服务器。

## 功能

- **系统内录**：Android 10+ 的 AudioPlaybackCapture，插耳机/静音也能翻译
- **低延迟流式识别**：WebSocket 长连接，原文一到立刻上屏，不等翻译
- **可插拔服务商**：Azure 语音翻译 / Deepgram / OpenAI Realtime；翻译支持任意 OpenAI 兼容接口、DeepL、Google
- **双语字幕**：上原文下译文，可只留译文
- **外观全可调**：背景颜色与透明度、原文/译文颜色分别设置、字号、行数、宽度、位置拖拽、锁定后触摸穿透
- **OCR 备用模式**：端侧 ML Kit 识别屏幕下方硬字幕，用于 DRM 视频等抓不到声音的场景
- **字幕记录**：每句定稿的原文和译文自动入库，可按会话回看、全文搜索、导出分享

## 快速上手

**[→ 完整配置教程（Deepgram + 百度翻译）](使用教程.md)** —— 从注册账号到调优的每一步，
含所有网址和报错对照表。第一次用建议直接看这个。

## 拿到 APK

在 GitHub Actions 手动运行构建，可下载构建产物；版本发布页另有本地测试过的 APK。
也可以在 Actions 页面下载 artifact。

## 怎么用

1. 安装后打开，授予**悬浮窗权限**
2. 选引擎模式和识别服务商，填 API Key
3. 需要的话再配翻译服务（Azure 自带翻译，可以不配）
4. 点「开始字幕」，系统会弹投屏授权 —— 这一步是内录必需的
5. 切到视频应用，字幕会浮在最上层。拖动可移位，设置里开「锁定位置」后触摸会穿透

## 服务商怎么选

| 需求 | 建议 |
|---|---|
| 延迟最低、配置最省事 | Azure 语音翻译（识别+翻译一次返回，只需 Key + 区域） |
| 识别准确度优先 | Deepgram `nova-3` + DeepL |
| 已有 OpenAI 兼容的中转/自建 | OpenAI Realtime + OpenAI 兼容翻译，两处地址都可改 |
| 想省钱 | Deepgram `nova-2` + DeepSeek（翻译接口填 `https://api.deepseek.com/chat/completions`） |

翻译模型**选小的**：字幕都是短句，小模型和大模型质量差别不大，但延迟差一截。

## 已知限制

- **iOS 做不到**：系统内录是 Android 独有能力
- **DRM 视频抓不到声音**：Netflix、Disney+ 等声明了禁止内录，会得到静音流。应用会提示你切 OCR 模式
- **需要 Android 10 以上**
- OCR 模式识别的是画面里已有的字幕，画面上没字就没东西可翻

## 调延迟

在 `CaptionPipeline` 里：

- `PARTIAL_INTERVAL_MS` — 中间结果送去翻译的最小间隔。调小更跟手，翻译调用次数和费用上升
- `PARTIAL_SETTLE_MS` — 翻译前的稳定等待，调大译文更完整、更晚出现

Deepgram 的 `endpointing=300`（在 `DeepgramProvider`）决定句子多快定稿，调小句子切得更碎但更早出结果。

## 工程结构

```
core/       音频采集、字幕管线
provider/   识别服务商适配 + 翻译服务商适配
service/    前台服务、悬浮窗、投屏授权
ocr/        截屏 + 端侧文字识别
data/       设置存储、字幕历史（SQLite）
ui/         Compose 设置与历史界面
```
