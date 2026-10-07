package com.echo.livetranslate.provider.translate

import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.data.TransProvider

interface Translator {
    /** 返回译文；失败时抛异常，由调用方决定是否降级显示原文。 */
    suspend fun translate(text: String, targetLang: String): String

    /**
     * 两次请求之间至少要隔多久。服务商有 QPS 限制时靠它避免撞墙——
     * 管线拿它当中间结果的节流下限，翻译器内部也按它排队。
     */
    val minIntervalMs: Long get() = 0L

    companion object {
        fun from(s: Settings): Translator? = when (s.transProvider) {
            TransProvider.NONE -> null
            TransProvider.BAIDU -> BaiduTranslator(s)
            TransProvider.OPENAI_COMPAT -> OpenAiCompatTranslator(s)
            TransProvider.DEEPL -> DeepLTranslator(s)
            TransProvider.GOOGLE -> GoogleTranslator(s)
        }
    }
}
