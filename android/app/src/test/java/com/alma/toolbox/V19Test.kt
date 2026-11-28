package com.alma.toolbox

import com.alma.toolbox.core.Tones
import com.alma.toolbox.data.DataGuard
import com.alma.toolbox.data.DataMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V19Test {
    private val internet = setOf("a", "b", "w", "h")
    private val heavy = setOf("h")

    @Test fun blockingModes() {
        val user = setOf("u")
        val white = setOf("w")
        // normal: only what the user blocked
        assertEquals(user, DataGuard.blockedSet(DataMode.NORMAL, true, user, white, internet, heavy))
        // ultra: everything with internet except whitelisted, plus the user's list
        assertEquals(setOf("u", "a", "b", "h"), DataGuard.blockedSet(DataMode.ULTRA, true, user, white, internet, heavy))
        // medium: only the heavy users (not whitelisted)
        assertEquals(setOf("u", "h"), DataGuard.blockedSet(DataMode.MEDIUM, true, user, white, internet, heavy))
        // background: screen on = normal, screen off = everything except whitelist
        assertEquals(user, DataGuard.blockedSet(DataMode.BG, true, user, white, internet, heavy))
        assertEquals(setOf("u", "a", "b", "h"), DataGuard.blockedSet(DataMode.BG, false, user, white, internet, heavy))
        // whitelist never blocks the user's own explicit choice... but does exempt automatic blocks
        assertTrue("w" !in DataGuard.blockedSet(DataMode.ULTRA, false, user, white, internet, heavy))
    }

    @Test fun tonesRoundTrip() {
        assertEquals("bell", Tones.keyOf("android.resource://com.alma.toolbox/raw/tone_bell"))
        assertNull(Tones.keyOf("content://media/external/audio/5"))
        assertEquals("🎵 صفير", Tones.titleOf("android.resource://com.alma.toolbox/raw/tone_whistle"))
        // every built-in tone must be bundled
        Tones.all.forEach { t ->
            val f = File("src/main/res/raw/tone_${t.key}.mp3")
            assertTrue("missing ${f.path}", f.exists() && f.length() > 1000)
        }
    }
}
