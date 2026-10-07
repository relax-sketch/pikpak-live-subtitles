package com.echo.livetranslate.provider

import com.echo.livetranslate.data.AsrProvider
import com.echo.livetranslate.data.Settings

object AsrProviderFactory {
    fun create(s: Settings): SpeechProvider = when (s.asrProvider) {
        AsrProvider.QWEN_LIVE -> QwenLiveTranslateProvider(s)
        AsrProvider.AZURE -> AzureSpeechProvider(s)
        AsrProvider.DEEPGRAM -> DeepgramProvider(s)
        AsrProvider.OPENAI_REALTIME -> OpenAiRealtimeProvider(s)
    }
}
