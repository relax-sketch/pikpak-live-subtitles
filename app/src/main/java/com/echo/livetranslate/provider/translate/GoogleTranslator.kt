package com.echo.livetranslate.provider.translate

import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/** Google Cloud Translation v2，延迟低、语种全，按字符计费。 */
class GoogleTranslator(private val s: Settings) : Translator {

    override suspend fun translate(text: String, targetLang: String): String =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("q", text)
                .add("target", toGoogleCode(targetLang))
                .add("format", "text")
                .build()

            val request = Request.Builder()
                .url("https://translation.googleapis.com/language/translate/v2?key=${s.googleKey.trim()}")
                .post(form)
                .build()

            Http.restClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                check(resp.isSuccessful) { "Google ${resp.code}: ${body.take(200)}" }
                JSONObject(body)
                    .getJSONObject("data")
                    .getJSONArray("translations")
                    .getJSONObject(0)
                    .getString("translatedText")
                    .trim()
            }
        }

    private fun toGoogleCode(lang: String): String = when (lang) {
        "zh-Hans" -> "zh-CN"
        "zh-Hant" -> "zh-TW"
        else -> lang
    }
}
