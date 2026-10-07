package com.echo.livetranslate.data

import org.junit.Assert.*
import org.junit.Test

class AzureSettingsTest {
    @Test fun azureConfiguration() {
        val settings = Settings(asrProvider = AsrProvider.AZURE, azureKey = "test", capturePackage = "pikpak")
        assertEquals("germanywestcentral", settings.azureRegion)
        assertEquals("ja-JP", settings.azureSourceLangs)
        assertTrue(settings.translationComesFromAsr)
        assertNull(settings.validate())
        assertNotNull(settings.copy(azureRegion = "https://germanywestcentral.api.cognitive.microsoft.com/").validate())
        assertNotNull(settings.copy(azureSourceLangs = "").validate())
        assertNotNull(settings.copy(azureSourceLangs = "ja-JP,").validate())
        assertNull(settings.copy(azureSourceLangs = "ja-JP, en-US").validate())
    }
}
