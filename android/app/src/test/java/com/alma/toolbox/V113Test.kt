package com.alma.toolbox

import com.alma.toolbox.core.Backup
import com.alma.toolbox.data.DataGuard
import com.alma.toolbox.data.DataMode
import com.alma.toolbox.ledger.LAccount
import com.alma.toolbox.ledger.LTx
import com.alma.toolbox.ledger.LedgerData
import com.alma.toolbox.ledger.dueNow
import com.alma.toolbox.screentime.ScreenTime
import com.alma.toolbox.screentime.ScreenTime.Ev
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class V113Test {
    private val M = 60_000L

    @Test fun screenOnTimeAndApps() {
        val ev = listOf(
            Ev(0, ScreenTime.SCREEN_ON), Ev(1 * M, ScreenTime.KG_HIDDEN), Ev(1 * M, ScreenTime.RESUMED, "yt"),
            Ev(11 * M, ScreenTime.PAUSED, "yt"), Ev(11 * M, ScreenTime.RESUMED, "music"),
            Ev(21 * M, ScreenTime.SCREEN_OFF), Ev(21 * M, ScreenTime.KG_SHOWN),
            Ev(40 * M, ScreenTime.SCREEN_ON), Ev(45 * M, ScreenTime.SCREEN_OFF),
        )
        val all = ScreenTime.compute(ev, 0, 60 * M, noMusic = false, music = setOf("music"))
        assertEquals(26 * M, all.total)            // 21 + 5 minutes of screen on
        assertEquals(10 * M, all.apps["yt"])
        assertEquals(1, all.unlocks)
        // no music: lock-screen time (first minute and 40..45) and the music app don't count
        val nm = ScreenTime.compute(ev, 0, 60 * M, noMusic = true, music = setOf("music"))
        assertEquals(10 * M, nm.total)
    }

    @Test fun windowClipsEarlierState() {
        // screen turned on before the window: only the part inside counts
        val ev = listOf(Ev(-30 * M, ScreenTime.SCREEN_ON), Ev(10 * M, ScreenTime.SCREEN_OFF))
        assertEquals(10 * M, ScreenTime.compute(ev, 0, 60 * M, false, emptySet()).total)
        // only an OFF event seen: the screen was on since the window start
        assertEquals(5 * M, ScreenTime.compute(listOf(Ev(5 * M, ScreenTime.SCREEN_OFF)), 0, 60 * M, false, emptySet()).total)
    }

    @Test fun bubbleEvery15ThenEvery10() {
        val goal = 180 * M
        assertFalse(ScreenTime.bubbleDue(14 * M, 0, goal))
        assertTrue(ScreenTime.bubbleDue(15 * M, 0, goal))
        // last hour: every 10 minutes
        assertFalse(ScreenTime.bubbleDue(129 * M, 120 * M, goal))
        assertTrue(ScreenTime.bubbleDue(130 * M, 120 * M, goal))
        assertFalse(ScreenTime.bubbleDue(30 * M, 0, 0))
    }

    @Test fun dataCapAlerts() {
        val cap = 1000L
        assertEquals(0, DataGuard.capLevel(700, cap, 0))
        assertEquals(80, DataGuard.capLevel(800, cap, 0))
        assertEquals(0, DataGuard.capLevel(900, cap, 80))   // already sent
        assertEquals(100, DataGuard.capLevel(1000, cap, 80))
        assertEquals(0, DataGuard.capLevel(5000, 0, 0))     // no cap
        // cap reached blocks everything not allowed, even in normal mode
        val b = DataGuard.blockedSet(DataMode.NORMAL, true, emptySet(), setOf("w"), setOf("a", "w"), emptySet(), capBlock = true)
        assertEquals(setOf("a"), b)
    }

    @Test fun dueDebts() {
        val today = LocalDate.of(2026, 12, 1)
        val d = LedgerData(accounts = listOf(LAccount(1, 1, "علي"), LAccount(2, 1, "سالم")), txs = listOf(
            LTx(10, 1, 500.0, true, "ر", "2026-11-01", due = "2026-11-30"),   // due, still owed
            LTx(11, 2, 300.0, true, "ر", "2026-11-01", due = "2026-11-30"),
            LTx(12, 2, 300.0, false, "ر", "2026-11-20"),                        // سالم paid back
            LTx(13, 1, 100.0, true, "ر", "2026-11-01", due = "2026-12-10"),   // not yet
        ))
        assertEquals(listOf(10L), d.dueNow(today).map { it.id })
    }

    @Test fun backupKeepsTypes() {
        assertEquals("\u0002S:a\u0001b", Backup.encodePref(linkedSetOf("a", "b")))
        assertEquals("\u0002L:5", Backup.encodePref(5L))
        assertEquals("\u0002s:967", Backup.encodePref("967"))
        assertEquals("نص", Backup.encodePref("نص"))
        assertEquals("7", Backup.encodePref(7))
    }
}
