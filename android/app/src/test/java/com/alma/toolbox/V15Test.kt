package com.alma.toolbox

import com.alma.toolbox.core.JsonStore
import com.alma.toolbox.core.Stores
import com.alma.toolbox.daily.Occasion
import com.alma.toolbox.daily.Occasions
import com.alma.toolbox.daily.Zakat
import com.alma.toolbox.ledger.*
import com.alma.toolbox.prayer.AdhanData
import com.alma.toolbox.prayer.AdhanScheduler
import com.alma.toolbox.silent.SilentData
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

class V15Test {
    init { java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Aden")) }
    private val dir = kotlin.io.path.createTempDirectory("v").toFile()

    @Test fun zakat() {
        // 100 g of 21k gold = 87.5 g pure; at 100/g → 8750; plus cash 1250 = 10000 → zakat 250
        val r = Zakat.compute(1250.0, 0.0, 100.0, 0.0, 0.0, 0.0, 0.0, 0.0, 100.0, 1.0, false)
        assertEquals(10000.0, r.total, 1e-6); assertEquals(8500.0, r.nisab, 1e-6); assertTrue(r.due); assertEquals(250.0, r.zakat, 1e-6)
        val below = Zakat.compute(5000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1000.0, 100.0, 1.0, false)
        assertFalse(below.due); assertEquals(0.0, below.zakat, 0.0)
        assertTrue(Zakat.compute(1000.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 100.0, 1.0, true).due) // silver nisab 595
    }

    @Test fun occasionsGregorian() {
        val today = LocalDate.of(2026, 11, 21)
        val bd = Occasion(1, "عيد ميلاد", date = "1990-11-25")
        assertEquals(LocalDate.of(2026, 11, 25), Occasions.next(bd, today)); assertEquals(4L, Occasions.daysLeft(bd, today)); assertEquals(36, Occasions.years(bd, today))
        val passed = Occasion(2, "ذكرى", date = "2020-03-01")
        assertEquals(LocalDate.of(2027, 3, 1), Occasions.next(passed, today))
        assertEquals(0L, Occasions.daysLeft(Occasion(3, "اليوم", date = "2000-11-21"), today))
        assertEquals(LocalDate.of(2027, 1, 5), Occasions.next(Occasion(4, "مرة", date = "2027-01-05", yearly = false), today))
    }

    @Test fun ledgerBaseCurrencyMergeOverdue() {
        val d = LedgerData(currencies = listOf("ريال يمني", "ريال سعودي"), baseCurrency = "ريال يمني", rates = mapOf("ريال سعودي" to 140.0))
        assertEquals(1000.0 + 10 * 140.0, d.toBase(mapOf("ريال يمني" to 1000.0, "ريال سعودي" to 10.0))!!, 1e-9)
        assertNull(d.toBase(mapOf("دولار" to 5.0))) // missing rate
        assertNull(d.copy(baseCurrency = "").toBase(mapOf("ريال يمني" to 1.0)))

        Stores.ledger = JsonStore(File(dir, "l"), LedgerData.serializer()) {
            LedgerData(accounts = listOf(LAccount(10, 1, "أحمد")), txs = listOf(LTx(1, 10, 500.0, true, "ريال يمني", "2026-01-01")))
        }
        val other = LedgerData(groups = listOf(LGroup(1, "العملاء"), LGroup(77, "فرع")),
            accounts = listOf(LAccount(10, 1, "أحمد (نسخة)"), LAccount(11, 77, "سالم"), LAccount(12, 999, "يتيم")),
            txs = listOf(LTx(1, 10, 500.0, true, "ريال يمني"), LTx(2, 11, 300.0, true, "دولار")), currencies = listOf("دولار"))
        assertEquals(Triple(1, 2, 1), Ledger.merge(other))
        val m = Stores.ledger.value
        assertEquals("أحمد", m.accounts.first { it.id == 10L }.name) // existing kept
        assertEquals(1L, m.accounts.first { it.id == 12L }.groupId) // orphan moved to first group
        assertTrue("دولار" in m.currencies)
        assertEquals(0, Ledger.merge(other).third) // idempotent

        val od = m.copy(overdueDays = 30).overdue(LocalDate.of(2026, 11, 21))
        assertEquals(listOf("أحمد"), od.map { it.name }) // سالم's tx is today (no date → today) so not overdue
    }

    @Test fun adhanWeeklyMute() {
        Stores.silent = JsonStore(File(dir, "s"), SilentData.serializer()) { SilentData() }
        val fri = LocalDate.of(2026, 11, 20); assertEquals(DayOfWeek.FRIDAY, fri.dayOfWeek)
        val d = AdhanData(enabled = setOf(2, 3), muted = setOf("5-2")) // mute Friday dhuhr
        val (p, at) = AdhanScheduler.next(d, fri.atTime(9, 0))!!
        assertEquals(3, p); assertEquals(fri, at.toLocalDate()) // skips to asr
        val (p2, _) = AdhanScheduler.next(d, fri.minusDays(1).atTime(9, 0))!!
        assertEquals(2, p2) // Thursday dhuhr still on
    }
}
