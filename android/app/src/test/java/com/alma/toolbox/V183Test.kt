package com.alma.toolbox

import com.alma.toolbox.core.JsonStore
import com.alma.toolbox.core.Stores
import com.alma.toolbox.ledger.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V183Test {
    @Test fun doubleEntry() {
        val dir = kotlin.io.path.createTempDirectory("de").toFile()
        Stores.ledger = JsonStore(File(dir, "l"), LedgerData.serializer()) {
            LedgerData(accounts = listOf(LAccount(1, 1, "الصندوق"), LAccount(2, 1, "أحمد")), doubleEntry = true)
        }
        val box = Stores.ledger.value.accounts[0]; val ahmed = Stores.ledger.value.accounts[1]
        // «له» 5000 for Ahmed, taken from the cash box → the box gets «عليه» 5000
        val t = Ledger.addWithCounter(LTx(10, ahmed.id, 5000.0, onHim = false, currency = "ريال يمني"), box)
        var d = Stores.ledger.value
        assertEquals(2, d.txs.size)
        assertEquals(mapOf("ريال يمني" to -5000.0), d.balance(ahmed.id))
        assertEquals(mapOf("ريال يمني" to 5000.0), d.balance(box.id))
        val twin = Ledger.twinOf(d, d.txs.first { it.id == t.id })!!
        assertEquals(box.id, twin.accountId); assertTrue(twin.onHim); assertEquals(t.id, twin.linkId)
        assertEquals("من الصندوق", d.txs.first { it.id == t.id }.note); assertEquals("لـ أحمد", twin.note)
        // editing one side updates the other
        Ledger.saveTx(d.txs.first { it.id == t.id }.copy(amount = 7000.0))
        d = Stores.ledger.value
        assertEquals(mapOf("ريال يمني" to 7000.0), d.balance(box.id))
        // deleting either side removes both
        Ledger.deleteTx(twin.id)
        assertTrue(Stores.ledger.value.txs.isEmpty())
        // without a counter account nothing extra is added
        Ledger.addWithCounter(LTx(20, ahmed.id, 100.0, true, "ريال يمني"), null)
        assertEquals(1, Stores.ledger.value.txs.size)
        // old data (no linkId) still decodes and defaults to the simple interface
        val old = com.alma.toolbox.core.AppJson.decodeFromString(LedgerData.serializer(), """{"txs":[{"id":1,"accountId":1,"amount":5,"onHim":true,"currency":"x"}]}""")
        assertEquals(0L, old.txs[0].linkId); assertTrue(old.simpleUi); assertFalse(old.doubleEntry)
    }
}
