package com.echo.livetranslate.provider.translate

import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/** DeepL：句子级质量好，延迟通常在 200ms 内，适合做 Deepgram 的下游。 */
class DeepLTranslator(private val s: Settings) : Translator {

    override suspend fun translate(text: String, targetLang: String): String =
        withContext(Dispatchers.IO) {
            val host = if (s.deeplFreeTier) "api-free.deepl.com" else "api.deepl.com"
            val form = FormBody.Builder()
                .add("text", text)
                .add("target_lang", toDeepLCode(targetLang))
                .build()

            val request = Request.Builder()
                .url("https://$host/v2/translate")
                .addHeader("Authorization", "DeepL-Auth-Key ${s.deeplKey.trim()}")
                .post(form)
                .build()

            Http.restClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                check(resp.isSuccessful) { "DeepL ${resp.code}: ${body.take(200)}" }
                JSONObject(body)
                    .getJSONArray("translations")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            }
        }

    /** DeepL 用的是 ZH / EN-US 这种写法，做一次映射。 */
    private fun toDeepLCode(lang: String): String = when {
        lang.startsWith("zh", true) -> "ZH"
        lang.equals("en", true) || lang.startsWith("en-", true) -> "EN-US"
        lang.startsWith("pt", true) -> "PT-BR"
        else -> lang.substringBefore('-').uppercase()
    }
}
