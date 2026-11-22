package com.alma.toolbox

import com.alma.toolbox.daily.Occasion
import com.alma.toolbox.daily.Occasions
import com.alma.toolbox.periodic.PeriodicAlert
import com.alma.toolbox.periodic.PeriodicScheduler
import com.alma.toolbox.pomodoro.PomoData
import com.alma.toolbox.pomodoro.PomoDay
import com.alma.toolbox.pomodoro.progress
import com.alma.toolbox.tools.Ciphers
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class V18Test {
    @Test fun periodicMutedHours() {
        val a = PeriodicAlert(1, startMin = 0, endMin = 23 * 60 + 59, intervalMin = 60, mutedHours = (0..5).toSet() + 23)
        val d = LocalDate.of(2026, 11, 22)
        assertEquals(d.atTime(6, 0), PeriodicScheduler.nextFire(a, d.atTime(0, 30)))
        assertEquals(d.atTime(22, 0), PeriodicScheduler.nextFire(a, d.atTime(21, 10)))
        assertEquals(d.plusDays(1).atTime(6, 0), PeriodicScheduler.nextFire(a, d.atTime(22, 5))) // 23h muted → next morning
    }

    @Test fun occasionRepeats() {
        val today = LocalDate.of(2026, 11, 22)
        assertEquals(LocalDate.of(2026, 11, 25), Occasions.next(Occasion(1, "x", date = "2026-01-25", repeat = "monthly"), today))
        assertEquals(LocalDate.of(2027, 1, 25), Occasions.next(Occasion(1, "x", date = "2026-01-25", repeat = "monthly", every = 3), today))
        assertEquals(LocalDate.of(2026, 11, 26), Occasions.next(Occasion(1, "x", date = "2026-11-05", repeat = "weekly"), today)) // Thursdays
        assertEquals(LocalDate.of(2026, 11, 24), Occasions.next(Occasion(1, "x", date = "2026-11-14", repeat = "days", every = 10), today))
        assertEquals(LocalDate.of(2028, 3, 1), Occasions.next(Occasion(1, "x", date = "2020-03-01", repeat = "yearly", every = 4), today))
        assertEquals(LocalDate.of(2027, 3, 1), Occasions.next(Occasion(1, "x", date = "2020-03-01", yearly = true), today)) // old data
        assertEquals(LocalDate.of(2026, 11, 22), Occasions.next(Occasion(1, "x", date = "2026-11-22", repeat = "weekly"), today))
    }

    @Test fun pomodoroGoals() {
        val t = LocalDate.of(2026, 11, 22) // Sunday; week starts Saturday 21st
        val log = mapOf("2026-11-22" to PomoDay(3), "2026-11-21" to PomoDay(2), "2026-11-20" to PomoDay(4), "2026-10-31" to PomoDay(9))
        assertEquals(Triple(3, 5, 9), PomoData(log = log).progress(t))
    }

    @Test fun ciphers() {
        assertEquals("Khoor", Ciphers.caesar("Hello", 3)); assertEquals("Hello", Ciphers.caesar("Khoor", -3))
        assertEquals("ثجح", Ciphers.caesar("ابت", 3)); assertEquals("ابت", Ciphers.caesar("ثجح", -3))
        assertEquals("zyx", Ciphers.atbash("abc")); assertEquals("abc", Ciphers.atbash(Ciphers.atbash("abc")))
        assertEquals("مرحبا A", Ciphers.fromBinary(Ciphers.toBinary("مرحبا A")))
        assertEquals("01000001", Ciphers.toBinary("A"))
    }
}
