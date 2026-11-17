package com.alma.toolbox

import com.alma.toolbox.ledger.Ledger
import com.alma.toolbox.ledger.LTx
import com.alma.toolbox.ledger.LedgerData
import com.alma.toolbox.ledger.balance
import com.alma.toolbox.periodic.PeriodicAlert
import com.alma.toolbox.periodic.PeriodicScheduler
import com.alma.toolbox.reminder.Reminder
import com.alma.toolbox.reminder.ReminderScheduler
import com.alma.toolbox.silent.*
import com.alma.toolbox.tools.Morse
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LogicTest {
    private val riyadhZone = ZoneId.of("Asia/Riyadh") // UTC+3, same as Yemen

    private fun hm(h: Double) = String.format("%02d:%02d", (Math.round(h * 60) / 60).toInt(), (Math.round(h * 60) % 60).toInt())
    private fun assertNear(label: String, expected: String, actualHours: Double, tolMin: Int = 3) {
        val (eh, em) = expected.split(":").map { it.toInt() }
        val diff = Math.abs(eh * 60 + em - Math.round(actualHours * 60))
        assertTrue("$label expected $expected got ${hm(actualHours)}", diff <= tolMin)
    }

    @Test fun prayerTimesMakkahUmmAlQura() {
        // Reference values cross-checked with the astral library (NOAA algorithm), Makkah, 1 Jan 2024
        val t = PrayerCalc.times(LocalDate.of(2024, 1, 1), PrayerSettings(lat = 21.4225, lon = 39.8262, method = "UmmAlQura"), riyadhZone)
        println("Makkah 2024-01-01: " + t.joinToString { hm(it) })
        assertNear("fajr", "05:36", t[0]); assertNear("sunrise", "06:58", t[1]); assertNear("dhuhr", "12:23", t[2])
        assertNear("asr", "15:28", t[3]); assertNear("maghrib", "17:49", t[4]); assertNear("isha", "19:19", t[5])
    }

    @Test fun prayerTimesSanaaSane() {
        val t = PrayerCalc.times(LocalDate.of(2026, 11, 17), PrayerSettings(), riyadhZone)
        println("Sanaa 2026-11-17 UmmAlQura: " + t.joinToString { hm(it) })
        val j = PrayerCalc.times(LocalDate.of(2026, 11, 17), PrayerSettings(method = "Jafari"), riyadhZone)
        println("Sanaa 2026-11-17 Jafari:    " + j.joinToString { hm(it) })
        for (i in 0 until 5) assertTrue(t[i] < t[i + 1])
        assertTrue(t[2] in 11.3..12.0)       // dhuhr near solar noon
        assertTrue(j[4] > t[4])              // Jafari maghrib after sunset
    }

    @Test fun periodicEveryHourInWindow() {
        val a = PeriodicAlert(1, days = setOf(DayOfWeek.TUESDAY.value), startMin = 8 * 60, endMin = 22 * 60, intervalMin = 60)
        val tue = LocalDate.of(2026, 11, 17) // Tuesday
        assertEquals(tue.atTime(8, 0), PeriodicScheduler.nextFire(a, tue.atTime(6, 0)))
        assertEquals(tue.atTime(11, 0), PeriodicScheduler.nextFire(a, tue.atTime(10, 15)))
        assertEquals(tue.atTime(11, 0), PeriodicScheduler.nextFire(a, tue.atTime(10, 0)))
        assertEquals(tue.plusDays(7).atTime(8, 0), PeriodicScheduler.nextFire(a, tue.atTime(22, 0)))
    }

    @Test fun periodicCrossesMidnight() {
        val a = PeriodicAlert(1, startMin = 22 * 60, endMin = 2 * 60, intervalMin = 90)
        val d = LocalDate.of(2026, 11, 17)
        assertEquals(d.plusDays(1).atTime(1, 0), PeriodicScheduler.nextFire(a, d.plusDays(1).atTime(0, 10)))
    }

    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun reminderRepeats() {
        val start = LocalDateTime.of(2026, 1, 31, 9, 0)
        val monthly = Reminder(1, startAt = ms(start), repeat = "month")
        assertEquals(LocalDateTime.of(2026, 2, 28, 9, 0), ReminderScheduler.occurrenceAfter(monthly, start))
        val every15 = Reminder(1, startAt = ms(start), repeat = "minute", every = 15)
        assertEquals(start.plusMinutes(30), ReminderScheduler.occurrenceAfter(every15, start.plusMinutes(16)))
        val weekdays = Reminder(1, startAt = ms(start), repeat = "weekdays", weekdays = setOf(DayOfWeek.MONDAY.value))
        assertEquals(LocalDateTime.of(2026, 2, 2, 9, 0), ReminderScheduler.occurrenceAfter(weekdays, start))
        val counted = Reminder(1, startAt = ms(start), repeat = "day", endType = "count", count = 3, fired = 3)
        assertNull(ReminderScheduler.occurrenceAfter(counted, start))
        val window = Reminder(1, startAt = ms(LocalDateTime.of(2026, 1, 31, 21, 0)), repeat = "hour", limitHours = true, fromMin = 8 * 60, toMin = 22 * 60)
        assertEquals(LocalDateTime.of(2026, 2, 1, 8, 0), ReminderScheduler.occurrenceAfter(window, LocalDateTime.of(2026, 1, 31, 22, 30)))
    }

    @Test fun silentJumuahReplacesFridayDhuhr() {
        val d = SilentData(rules = listOf(
            SilentRule(1, kind = "prayer", prayers = setOf(2, JUMUAH), afterMin = 60),
            SilentRule(2, kind = "time", days = setOf(DayOfWeek.FRIDAY.value), startMin = 19 * 60, endMin = 22 * 60),
        ))
        val fri = LocalDate.of(2026, 11, 20)
        val iv = SilentEngine.intervals(d, fri, 1)
        assertEquals(listOf("صلاة الجمعة", "وقت مجدول"), iv.map { it.label })
        assertEquals(60, Duration.between(iv[0].start, iv[0].end).toMinutes())
    }

    @Test fun ledgerAmountsAndBalance() {
        assertEquals(750.0, Ledger.parseAmount("500+250")!!, 1e-9)
        assertEquals(1500.0, Ledger.parseAmount("١٥٠٠")!!, 1e-9)
        assertEquals(12000.0, Ledger.parseAmount("12,000")!!, 1e-9)
        assertEquals(1100.0, Ledger.parseAmount("100+500*2")!!, 1e-9)
        assertNull(Ledger.parseAmount("abc")); assertNull(Ledger.parseAmount("0"))
        val d = LedgerData(txs = listOf(LTx(1, 9, 1000.0, true, "ريال يمني"), LTx(2, 9, 300.0, false, "ريال يمني"), LTx(3, 9, 50.0, false, "دولار")))
        assertEquals(mapOf("ريال يمني" to 700.0, "دولار" to -50.0), d.balance(9))
    }

    @Test fun phoneNormalization() {
        assertEquals("967777123456", Ledger.normalizePhone("777 123 456", "967"))
        assertEquals("967777123456", Ledger.normalizePhone("0777123456", "967"))
        assertEquals("966501234567", Ledger.normalizePhone("+966 50 123 4567", "967"))
        assertEquals("966501234567", Ledger.normalizePhone("00966501234567", "967"))
    }

    @Test fun morse() {
        assertEquals("... --- ...", Morse.encode("sos"))
        assertEquals("SOS", Morse.decode("... --- ...", ar = false))
        assertEquals(".- .-.. / -- .-", Morse.encode("أل ما").let { it })
        assertEquals("ال ما", Morse.decode(".- .-.. / -- .-", ar = true))
    }
}
