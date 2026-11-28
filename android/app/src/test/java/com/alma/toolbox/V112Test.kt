package com.alma.toolbox

import com.alma.toolbox.data.DataGuard
import com.alma.toolbox.data.DataMode
import org.junit.Assert.*
import org.junit.Test

class V112Test {
    private val wa = DataGuard.WHATSAPP
    private val internet = setOf("a", "b") + wa

    @Test fun whatsappStaysConnectedInEveryMode() {
        listOf(DataMode.NORMAL, DataMode.MEDIUM, DataMode.ULTRA, DataMode.BG).forEach { m ->
            listOf(true, false).forEach { screen ->
                val b = DataGuard.blockedSet(m, screen, emptySet(), wa, internet, internet)
                assertTrue("$m/$screen", b.none { it in wa })
            }
        }
    }

    @Test fun whatsappOffCutsItEvenWhenWhitelisted() {
        val b = DataGuard.blockedSet(DataMode.NORMAL, true, emptySet(), wa, internet, emptySet(), waOff = true, whatsapp = setOf("com.whatsapp"))
        assertEquals(setOf("com.whatsapp"), b)
    }

    @Test fun sortByTodayOrMonth() {
        val apps = listOf(
            DataGuard.AppUse("x", "X", 1, today = 5, month = 100),
            DataGuard.AppUse("y", "Y", 2, today = 50, month = 60),
        )
        assertEquals(listOf("y", "x"), DataGuard.sorted(apps, byMonth = false).map { it.pkg })
        assertEquals(listOf("x", "y"), DataGuard.sorted(apps, byMonth = true).map { it.pkg })
    }
}
