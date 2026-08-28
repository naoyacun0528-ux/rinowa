package blog.nextlab.echo

import blog.nextlab.echo.crypto.ToDeviceLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 部屋の鍵をいつ捨ててよいかを決める規則。
 *
 * 端末の外で試す価値があるのは、防いでいる失敗が端末上で再現できないから。
 * 2段の手順の途中でプロセスが死ぬ必要があり、症状はあとから、他人の端末で、
 * 「開かないメッセージ」として出る。
 */
class ToDeviceLedgerTest {

    private class Memory(var ids: Set<String> = emptySet()) : ToDeviceLedger.Store {
        override fun read(): Set<String> = ids
        override fun write(ids: Set<String>) { this.ids = ids }
    }

    @Test
    fun `an event is never deleted the first time it is seen`() {
        val store = Memory()
        val sifted = ToDeviceLedger(store).sift(listOf("a", "b"))

        assertTrue(sifted.deletable.isEmpty())
        assertEquals(setOf("a", "b"), sifted.held)
    }

    @Test
    fun `an event seen on an earlier pass is deleted on the next one`() {
        val store = Memory()
        val ledger = ToDeviceLedger(store)

        ledger.sift(listOf("a", "b"))
        val second = ledger.sift(listOf("a", "b"))

        assertEquals(setOf("a", "b"), second.deletable)
        assertTrue(second.held.isEmpty())
    }

    /** 2回のパスの間で落ちても、削除にはならないこと。 */
    @Test
    fun `a restart with a fresh ledger holds everything again`() {
        val store = Memory()
        ToDeviceLedger(store).sift(listOf("a"))

        // プロセスは死んだが設定ファイルは残った。永続化しているのはこのため。
        val afterRestart = ToDeviceLedger(store).sift(listOf("a"))
        assertEquals(setOf("a"), afterRestart.deletable)

        // 何も永続化されていなければ、そのイベントは消さずに残す。
        val cold = ToDeviceLedger(Memory()).sift(listOf("a"))
        assertTrue(cold.deletable.isEmpty())
    }

    @Test
    fun `a new event arriving beside an old one is still held`() {
        val store = Memory()
        val ledger = ToDeviceLedger(store)

        ledger.sift(listOf("a"))
        val second = ledger.sift(listOf("a", "b"))

        assertEquals(setOf("a"), second.deletable)
        assertEquals(setOf("b"), second.held)
    }

    /** 受信箱から消えた id は追跡をやめる。でないと集合が永久に増える。 */
    @Test
    fun `ids that have gone are dropped from the ledger`() {
        val store = Memory()
        val ledger = ToDeviceLedger(store)

        ledger.sift(listOf("a", "b"))
        ledger.sift(listOf("b"))

        assertEquals(emptySet<String>(), store.ids)
    }

    @Test
    fun `a delete that failed only delays the next attempt`() {
        val store = Memory()
        val ledger = ToDeviceLedger(store)

        ledger.sift(listOf("a"))
        val deleting = ledger.sift(listOf("a"))
        assertEquals(setOf("a"), deleting.deletable)

        // 削除に失敗したので、次の回にもそのイベントがある。新しいものとして扱う。
        // 1回遅れることはあっても、1回早まることは無い。
        val again = ledger.sift(listOf("a"))
        assertTrue(again.deletable.isEmpty())
        assertEquals(setOf("a"), again.held)
    }
}
