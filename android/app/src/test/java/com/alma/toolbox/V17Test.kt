package com.alma.toolbox

import com.alma.toolbox.about.Updater
import com.alma.toolbox.memories.Memory
import com.alma.toolbox.memories.Prompts
import com.alma.toolbox.memories.locked
import com.alma.toolbox.memories.promptOfDay
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class V17Test {
    @Test fun releaseParsing() {
        // shape of api.github.com/repos/{owner}/{repo}/releases/latest
        val json = """{"tag_name":"v1.7","name":"حقيبة الما 1.7","body":"- الذكريات","assets":[
            {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
            {"name":"alma-8.apk","browser_download_url":"https://github.com/olfyemen/almayemen/releases/download/v1.7/alma-8.apk","size":6500000}]}"""
        val r = Updater.parse(json)!!
        assertEquals(8L, r.code); assertEquals("حقيبة الما 1.7", r.name); assertEquals(6500000L, r.size)
        assertTrue(r.url.endsWith("alma-8.apk"))
        assertNull(Updater.parse("""{"tag_name":"v1","assets":[{"name":"other.apk","browser_download_url":"u"}]}"""))
    }

    @Test fun timeCapsule() {
        val today = LocalDate.of(2026, 11, 21)
        assertTrue(Memory(1, openOn = "2027-11-21").locked(today))
        assertFalse(Memory(1, openOn = "2026-11-21").locked(today)) // opens on the day
        assertFalse(Memory(1).locked(today))
    }

    @Test fun promptsRotate() {
        assertEquals(30, Prompts.size)
        assertEquals(Prompts.size, (1..Prompts.size).map { promptOfDay(LocalDate.of(2026, 1, 1).plusDays(it.toLong())) }.toSet().size)
    }
}
