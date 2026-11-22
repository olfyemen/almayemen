package com.alma.toolbox

import com.alma.toolbox.adhkar.AdhkarData
import com.alma.toolbox.adhkar.HisnChapter
import com.alma.toolbox.core.AppJson
import com.alma.toolbox.core.JsonStore
import com.alma.toolbox.core.Stores
import com.alma.toolbox.prayer.AdhanData
import com.alma.toolbox.prayer.AdhanScheduler
import com.alma.toolbox.silent.SilentData
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class PrayerAdhkarTest {
    init { java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Aden")) } // times are for Yemen

    private fun stores(enabled: Set<Int>) {
        val dir = kotlin.io.path.createTempDirectory("p").toFile()
        Stores.silent = JsonStore(File(dir, "s"), SilentData.serializer()) { SilentData() }
        Stores.adhan = JsonStore(File(dir, "a"), AdhanData.serializer()) { AdhanData(enabled = enabled) }
    }

    @Test fun nextAdhanSkipsDisabledAndWrapsToTomorrow() {
        stores(setOf(0, 4)) // fajr + maghrib only, Sana'a default
        val day = LocalDate.of(2026, 11, 17)
        val (p1, t1) = AdhanScheduler.next(Stores.adhan.value, day.atTime(12, 0))!!
        assertEquals(4, p1); assertEquals(day, t1.toLocalDate()); assertTrue(t1.hour == 17)
        val (p2, t2) = AdhanScheduler.next(Stores.adhan.value, day.atTime(20, 0))!!
        assertEquals(0, p2); assertEquals(day.plusDays(1), t2.toLocalDate()); assertTrue(t2.hour == 4)
        stores(emptySet()); assertNull(AdhanScheduler.next(Stores.adhan.value, day.atTime(12, 0)))
    }

    @Test fun hisnAssetIsComplete() {
        val l = AppJson.decodeFromString(ListSerializer(HisnChapter.serializer()), File("src/main/assets/adhkar/hisn.json").readText())
        assertEquals(132, l.size)
        val morning = l.first { it.id == 27 }
        assertEquals("أذكار الصباح والمساء", morning.title)
        assertTrue(AdhkarData.norm(morning.items[0].t).contains(AdhkarData.norm("اللَّهُ لاَ إِلَهَ إِلاَّ هُوَ الْحَيُّ الْقَيُّومُ"))) // Ayat al-Kursi first
        assertEquals(3, morning.items[1].r)
        assertTrue(l.all { c -> c.items.isNotEmpty() && c.items.all { it.t.isNotBlank() && it.r >= 1 } })
    }

    @Test fun sahifaAssetIsComplete() {
        val book = AppJson.decodeFromString(com.alma.toolbox.adhkar.SahifaBook.serializer(), File("src/main/assets/adhkar/sahifa.json").readText())
        assertEquals((1..7).toSet(), book.week.map { it.dow }.toSet())           // one dua per weekday
        assertTrue(book.week.all { it.text.length > 500 })
        assertEquals(28, book.famous.size)
        assertTrue(book.famous.any { it.n == 7 && AdhkarData.norm(it.text).startsWith(AdhkarData.norm("يَا مَنْ تُحَلُّ بِهِ عُقَدُ الْمَكَارِهِ")) })
        assertTrue(book.famous.any { it.n == 20 && it.title.contains("مكارم الأخلاق") })
        val first = book.famous.first { it.n == 1 }
        assertTrue(AdhkarData.norm(first.text).startsWith(AdhkarData.norm("الْحَمْدُ لِلَّهِ الْأَوَّلِ بِلَا أَوَّلٍ كَانَ قَبْلَهُ")))
        val bad = Regex("[{}|=<>]|تصنيف:|[a-z]{2}:")
        assertTrue(book.famous.none { bad.containsMatchIn(it.text) || it.text.contains('ی') })
    }

    @Test fun arabicSearchNormalisation() {
        assertEquals(AdhkarData.norm("أَذْكَارُ الصَّبَاحِ"), AdhkarData.norm("اذكار الصباح"))
        assertTrue(AdhkarData.norm("الْحَمْدُ لِلَّهِ").contains(AdhkarData.norm("الحمد")))
    }
}
