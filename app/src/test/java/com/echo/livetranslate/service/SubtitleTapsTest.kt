package com.echo.livetranslate.service

import org.junit.Assert.*
import org.junit.Test

class SubtitleTapsTest {
    @Test fun doubleTapWaitsAndFourTapsNeverFireDoubleTap() {
        val taps = SubtitleTaps()
        assertFalse(taps.tap())
        assertFalse(taps.finish())
        repeat(2) { assertFalse(taps.tap()) }
        assertTrue(taps.finish())
        repeat(3) { assertFalse(taps.tap()) }
        assertTrue(taps.tap())
        assertFalse(taps.finish())
        repeat(3) { assertFalse(taps.tap()) }
        assertFalse(taps.finish())
        repeat(2) { taps.tap() }
        taps.cancel()
        assertFalse(taps.finish())
    }
}
