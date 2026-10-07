package com.echo.livetranslate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("live_translate")

/**
 * 全部设置读写。字段多但都是平铺的 key，读写两个方向各写一次映射，
 * 新增字段时改这两处即可。
 */
object SettingsStore {

    private object K {
        val engineMode = stringPreferencesKey("engineMode")
        val asrProvider = stringPreferencesKey("asrProvider")
        val qwenKey = stringPreferencesKey("qwenKey")
        val qwenSpeechFilter = booleanPreferencesKey("qwenSpeechFilter")
        val qwenAutoRenew = booleanPreferencesKey("qwenAutoRenew")
        val qwenRetryDelayMs = intPreferencesKey("qwenRetryDelayMs")
        val capturePackage = stringPreferencesKey("capturePackage")
        val azureKey = stringPreferencesKey("azureKey")
        val azureRegion = stringPreferencesKey("azureRegion")
        val azureSourceLangs = stringPreferencesKey("azureSourceLangs")
        val deepgramKey = stringPreferencesKey("deepgramKey")
        val deepgramUrl = stringPreferencesKey("deepgramUrl")
        val deepgramModel = stringPreferencesKey("deepgramModel")
        val deepgramLanguage = stringPreferencesKey("deepgramLanguage")
        val realtimeKey = stringPreferencesKey("realtimeKey")
        val realtimeUrl = stringPreferencesKey("realtimeUrl")
        val realtimeModel = stringPreferencesKey("realtimeModel")
        val realtimeLanguage = stringPreferencesKey("realtimeLanguage")
        val transProvider = stringPreferencesKey("transProvider")
        val targetLang = stringPreferencesKey("targetLang")
        val openaiTransUrl = stringPreferencesKey("openaiTransUrl")
        val openaiTransKey = stringPreferencesKey("openaiTransKey")
        val openaiTransModel = stringPreferencesKey("openaiTransModel")
        val baiduAppId = stringPreferencesKey("baiduAppId")
        val baiduSecret = stringPreferencesKey("baiduSecret")
        val baiduQps = intPreferencesKey("baiduQps")
        val deeplKey = stringPreferencesKey("deeplKey")
        val deeplFreeTier = booleanPreferencesKey("deeplFreeTier")
        val googleKey = stringPreferencesKey("googleKey")
        val bilingual = booleanPreferencesKey("bilingual")
        val showOriginal = booleanPreferencesKey("showOriginal")
        val fontSizeSp = intPreferencesKey("fontSizeSp")
        val maxLines = intPreferencesKey("maxLines")
        val originalColor = intPreferencesKey("originalColor")
        val translatedColor = intPreferencesKey("translatedColor")
        val backgroundColor = intPreferencesKey("backgroundColor")
        val backgroundAlpha = intPreferencesKey("backgroundAlpha")
        val outlineText = booleanPreferencesKey("outlineText")
        val overlayX = intPreferencesKey("overlayX")
        val overlayY = intPreferencesKey("overlayY")
        val overlayWidthPct = intPreferencesKey("overlayWidthPct")
        val locked = booleanPreferencesKey("locked")
        val ocrScript = stringPreferencesKey("ocrScript")
        val ocrIntervalMs = intPreferencesKey("ocrIntervalMs")
        val ocrBottomPct = intPreferencesKey("ocrBottomPct")
    }

    fun flow(context: Context): Flow<Settings> =
        context.applicationContext.dataStore.data.map { p -> p.toSettings() }

    suspend fun toggleQwenSpeechFilter(context: Context): Boolean =
        context.applicationContext.dataStore.edit { p ->
            p[K.qwenSpeechFilter] = !(p[K.qwenSpeechFilter] ?: true)
        }[K.qwenSpeechFilter] ?: true

    suspend fun save(context: Context, s: Settings) {
        context.applicationContext.dataStore.edit { p ->
            p[K.engineMode] = s.engineMode.name
            p[K.asrProvider] = s.asrProvider.name
            p[K.qwenKey] = s.qwenKey
            p[K.qwenSpeechFilter] = s.qwenSpeechFilter
            p[K.qwenAutoRenew] = s.qwenAutoRenew
            p[K.qwenRetryDelayMs] = s.qwenRetryDelayMs.coerceIn(100, 10000)
            p[K.capturePackage] = s.capturePackage
            p[K.azureKey] = s.azureKey
            p[K.azureRegion] = s.azureRegion
            p[K.azureSourceLangs] = s.azureSourceLangs
            p[K.deepgramKey] = s.deepgramKey
            p[K.deepgramUrl] = s.deepgramUrl
            p[K.deepgramModel] = s.deepgramModel
            p[K.deepgramLanguage] = s.deepgramLanguage
            p[K.realtimeKey] = s.realtimeKey
            p[K.realtimeUrl] = s.realtimeUrl
            p[K.realtimeModel] = s.realtimeModel
            p[K.realtimeLanguage] = s.realtimeLanguage
            p[K.transProvider] = s.transProvider.name
            p[K.targetLang] = s.targetLang
            p[K.openaiTransUrl] = s.openaiTransUrl
            p[K.openaiTransKey] = s.openaiTransKey
            p[K.openaiTransModel] = s.openaiTransModel
            p[K.baiduAppId] = s.baiduAppId
            p[K.baiduSecret] = s.baiduSecret
            p[K.baiduQps] = s.baiduQps
            p[K.deeplKey] = s.deeplKey
            p[K.deeplFreeTier] = s.deeplFreeTier
            p[K.googleKey] = s.googleKey
            p[K.bilingual] = s.bilingual
            p[K.showOriginal] = s.showOriginal
            p[K.fontSizeSp] = s.fontSizeSp
            p[K.maxLines] = s.maxLines
            p[K.originalColor] = s.originalColor
            p[K.translatedColor] = s.translatedColor
            p[K.backgroundColor] = s.backgroundColor
            p[K.backgroundAlpha] = s.backgroundAlpha
            p[K.outlineText] = s.outlineText
            p[K.overlayX] = s.overlayX
            p[K.overlayY] = s.overlayY
            p[K.overlayWidthPct] = s.overlayWidthPct
            p[K.locked] = s.locked
            p[K.ocrScript] = s.ocrScript.name
            p[K.ocrIntervalMs] = s.ocrIntervalMs
            p[K.ocrBottomPct] = s.ocrBottomPct
        }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            engineMode = enumOrDefault(this[K.engineMode], d.engineMode),
            asrProvider = enumOrDefault(this[K.asrProvider], d.asrProvider),
            qwenKey = this[K.qwenKey] ?: d.qwenKey,
            qwenSpeechFilter = this[K.qwenSpeechFilter] ?: d.qwenSpeechFilter,
            qwenAutoRenew = this[K.qwenAutoRenew] ?: d.qwenAutoRenew,
            qwenRetryDelayMs = (this[K.qwenRetryDelayMs] ?: d.qwenRetryDelayMs).coerceIn(100, 10000),
            capturePackage = this[K.capturePackage] ?: d.capturePackage,
            azureKey = this[K.azureKey] ?: d.azureKey,
            azureRegion = this[K.azureRegion] ?: d.azureRegion,
            azureSourceLangs = this[K.azureSourceLangs] ?: d.azureSourceLangs,
            deepgramKey = this[K.deepgramKey] ?: d.deepgramKey,
            deepgramUrl = this[K.deepgramUrl] ?: d.deepgramUrl,
            deepgramModel = this[K.deepgramModel] ?: d.deepgramModel,
            deepgramLanguage = this[K.deepgramLanguage] ?: d.deepgramLanguage,
            realtimeKey = this[K.realtimeKey] ?: d.realtimeKey,
            realtimeUrl = this[K.realtimeUrl] ?: d.realtimeUrl,
            realtimeModel = this[K.realtimeModel] ?: d.realtimeModel,
            realtimeLanguage = this[K.realtimeLanguage] ?: d.realtimeLanguage,
            transProvider = enumOrDefault(this[K.transProvider], d.transProvider),
            targetLang = this[K.targetLang] ?: d.targetLang,
            openaiTransUrl = this[K.openaiTransUrl] ?: d.openaiTransUrl,
            openaiTransKey = this[K.openaiTransKey] ?: d.openaiTransKey,
            openaiTransModel = this[K.openaiTransModel] ?: d.openaiTransModel,
            baiduAppId = this[K.baiduAppId] ?: d.baiduAppId,
            baiduSecret = this[K.baiduSecret] ?: d.baiduSecret,
            baiduQps = this[K.baiduQps] ?: d.baiduQps,
            deeplKey = this[K.deeplKey] ?: d.deeplKey,
            deeplFreeTier = this[K.deeplFreeTier] ?: d.deeplFreeTier,
            googleKey = this[K.googleKey] ?: d.googleKey,
            bilingual = this[K.bilingual] ?: d.bilingual,
            showOriginal = this[K.showOriginal] ?: d.showOriginal,
            fontSizeSp = this[K.fontSizeSp] ?: d.fontSizeSp,
            maxLines = this[K.maxLines] ?: d.maxLines,
            originalColor = this[K.originalColor] ?: d.originalColor,
            translatedColor = this[K.translatedColor] ?: d.translatedColor,
            backgroundColor = this[K.backgroundColor] ?: d.backgroundColor,
            backgroundAlpha = this[K.backgroundAlpha] ?: d.backgroundAlpha,
            outlineText = this[K.outlineText] ?: d.outlineText,
            overlayX = this[K.overlayX] ?: d.overlayX,
            overlayY = this[K.overlayY] ?: d.overlayY,
            overlayWidthPct = this[K.overlayWidthPct] ?: d.overlayWidthPct,
            locked = this[K.locked] ?: d.locked,
            ocrScript = enumOrDefault(this[K.ocrScript], d.ocrScript),
            ocrIntervalMs = this[K.ocrIntervalMs] ?: d.ocrIntervalMs,
            ocrBottomPct = this[K.ocrBottomPct] ?: d.ocrBottomPct
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
