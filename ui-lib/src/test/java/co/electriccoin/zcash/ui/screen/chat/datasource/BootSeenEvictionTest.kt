package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MED-B: the ZBOOT-seen dedup store is bounded by [evictOldestToCapacity], which caps it at MAX_SEEN_BOOT_SIGS
 * (500) and evicts OLDEST-first. This is the DoS bound that lets ZBOOT signatures churn freely in their OWN
 * store without ever evicting a genuine KEX/KEXACK txid from the separate KEX-txid FIFO (the shared-FIFO
 * eviction was the false "peer key changed" bug MED-B fixed). Pure (no Context) → src/test. (The store
 * SEPARATION + persistence is covered on real prefs in the androidTest ZchatPreferencesTest.)
 */
class BootSeenEvictionTest {

    private fun setOf(vararg items: String) = LinkedHashSet<String>().apply { addAll(items) }

    @Test
    fun `below capacity keeps every entry in insertion order`() {
        val s = setOf("a", "b", "c")
        evictOldestToCapacity(s, maxSize = 5)
        assertEquals(listOf("a", "b", "c"), s.toList())
    }

    @Test
    fun `exactly at capacity evicts nothing`() {
        val s = setOf("a", "b", "c")
        evictOldestToCapacity(s, maxSize = 3)
        assertEquals(listOf("a", "b", "c"), s.toList())
    }

    @Test
    fun `over capacity evicts the OLDEST first (FIFO)`() {
        val s = setOf("a", "b", "c", "d", "e")
        evictOldestToCapacity(s, maxSize = 3)
        // "a" and "b" (oldest) dropped; the three newest survive, order preserved.
        assertEquals(listOf("c", "d", "e"), s.toList())
        assertFalse(s.contains("a"))
        assertFalse(s.contains("b"))
    }

    @Test
    fun `caps a large churn at the 500 bound, keeping the newest and dropping the oldest`() {
        val s = LinkedHashSet<String>()
        repeat(502) { s.add("boot-sig-$it") }
        evictOldestToCapacity(s, maxSize = 500)
        assertEquals(500, s.size)
        assertFalse("oldest evicted", s.contains("boot-sig-0"))
        assertFalse("second-oldest evicted", s.contains("boot-sig-1"))
        assertTrue("first surviving entry", s.contains("boot-sig-2"))
        assertTrue("newest kept", s.contains("boot-sig-501"))
    }
}
