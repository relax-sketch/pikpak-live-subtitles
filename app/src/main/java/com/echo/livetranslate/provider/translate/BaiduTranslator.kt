package com.echo.livetranslate.provider.translate

import android.util.Log
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest

/**
 * 百度翻译开放平台。专用机器翻译接口，一次往返通常 200-400ms，
 * 比大模型快一个数量级——字幕要的就是把一句话直译过来，用不上推理能力。
 *
 * 限速是自适应的：设置里的 QPS 只是起点，真撞上 54003 就自动拉开间隔，
 * 稳定一段时间再慢慢收回来。账号档位和填的数字对不上时也能自己收敛，
 * 不用用户去猜百度到底给了多少额度。
 */
class BaiduTranslator(private val s: Settings) : Translator {

    /** 留 15% 余量：卡着理论值发容易被判超限 */
    private val configuredInterval: Long
        get() = 1150L / s.baiduQps.coerceIn(1, 100)

    override val minIntervalMs: Long
        get() = configuredInterval + penaltyMs

    private class RateLimited(message: String) : Exception(message)

    override suspend fun translate(text: String, targetLang: String): String =
        withContext(Dispatchers.IO) {
            try {
                request(text, targetLang)
            } catch (e: RateLimited) {
                // 已经降过速了，隔一拍再来一次通常就过
                delay(minIntervalMs)
                request(text, targetLang)
            }
        }

    private suspend fun request(text: String, targetLang: String): String {
        pace()

        val appId = s.baiduAppId.trim()
        val secret = s.baiduSecret.trim()
        val salt = System.currentTimeMillis().toString()

        val form = FormBody.Builder()
            .add("q", text)
            .add("from", "auto")
            .add("to", toBaiduCode(targetLang))
            .add("appid", appId)
            .add("salt", salt)
            .add("sign", md5(appId + text + salt + secret))
            .build()

        val httpRequest = Request.Builder()
            .url("https://fanyi-api.baidu.com/api/trans/vip/translate")
            .post(form)
            .build()

        return Http.restClient.newCall(httpRequest).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "百度翻译 ${resp.code}: ${body.take(200)}" }

            val json = JSONObject(body)
            // 百度用 HTTP 200 + error_code 表示失败，得单独判
            val code = json.optString("error_code")
            if (code.isNotEmpty() && code != "52000") {
                if (code == "54003") {
                    slowDown()
                    throw RateLimited("百度翻译限速，已自动降速到 ${minIntervalMs}ms 一次")
                }
                error("百度翻译 $code: ${explain(code, json.optString("error_msg"))}")
            }

            val results = json.optJSONArray("trans_result")
                ?: error("百度翻译返回异常：${body.take(200)}")

            speedUp()
            // 长句会被拆成多段返回，拼回去
            buildString {
                for (i in 0 until results.length()) {
                    append(results.getJSONObject(i).optString("dst"))
                }
            }.trim()
        }
    }

    /** 按当前间隔排队。超过 QPS 会被直接拒，宁可等一下也别浪费一次调用。 */
    private suspend fun pace() {
        gate.withLock {
            val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private fun slowDown() {
        penaltyMs = (if (penaltyMs <= 0L) 250L else penaltyMs * 2).coerceAtMost(MAX_PENALTY_MS)
        okStreak = 0
        Log.w(TAG, "rate limited, interval -> ${minIntervalMs}ms")
    }

    /** 连续顺利一段时间就把惩罚收回去，别一次限速就永久变慢。 */
    private fun speedUp() {
        if (penaltyMs <= 0L) return
        if (++okStreak < RECOVER_AFTER) return
        okStreak = 0
        penaltyMs = (penaltyMs * 2 / 3).let { if (it < 60L) 0L else it }
    }

    private fun explain(code: String, fallback: String): String = when (code) {
        "54004" -> "账户余额不足"
        "54005" -> "长文本请求过于频繁"
        "52001", "52002" -> "百度服务超时"
        "52003" -> "未授权：确认 APP ID 和密钥没填错，且已开通通用文本翻译"
        "58000" -> "客户端 IP 非法，检查百度控制台的 IP 白名单"
        "58002" -> "服务已停用，去百度控制台确认开通状态"
        else -> fallback
    }

    /** 百度用的是自己一套语言代码，不是标准 BCP-47 */
    private fun toBaiduCode(lang: String): String = when (lang) {
        "zh-Hans" -> "zh"
        "zh-Hant" -> "cht"
        "ja" -> "jp"
        "ko" -> "kor"
        "fr" -> "fra"
        "es" -> "spa"
        else -> lang.substringBefore('-')
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }

    private companion object {
        const val TAG = "BaiduTranslate"
        const val MAX_PENALTY_MS = 1500L
        const val RECOVER_AFTER = 25

        // 限速是账号级的，管线重建换了实例也得接着算，所以放在伴生对象里
        val gate = Mutex()
        @Volatile var lastRequestAt = 0L
        @Volatile var penaltyMs = 0L
        @Volatile var okStreak = 0
    }
}
