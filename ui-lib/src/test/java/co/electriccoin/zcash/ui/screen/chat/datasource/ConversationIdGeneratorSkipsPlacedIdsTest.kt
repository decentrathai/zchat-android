package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.security.Provider
import java.security.SecureRandomSpi
import java.security.Security
import java.util.concurrent.atomic.AtomicInteger

/**
 * FC-ID (ZC-892; FIRST-CONTACT-SPEC revision 4a, 6.2a case b, its second half): a generated id that already has an entry
 * in the store is never handed out. The generator's random source is made repeatable for the test (a SecureRandom
 * provider put first in the JVM's list, removed after): the first id it draws is known, an entry is placed under it, and
 * the store must draw again. Ids and addresses are synthetic.
 */
class ConversationIdGeneratorSkipsPlacedIdsTest {
    private val a = "u1synthetic-fcidx-contact-a"
    private val b = "u1synthetic-fcidx-contact-b"

    @Before
    fun setUp() {
        Security.insertProviderAt(RepeatableRandom, 1)
        calls.set(0)
    }

    @After
    fun tearDown() {
        Security.removeProvider(RepeatableRandom.name)
    }

    @Test
    fun `(b) a generated id that already has an entry is not returned and the entry is untouched`() {
        val placed = ZMSGProtocol.generateConversationId()
        calls.set(0)
        assertEquals("fixture: the random source repeats", placed, ZMSGProtocol.generateConversationId())
        val f = ConversationIdStoreFixture()
        f.store.setConversationMapping(placed, b)

        calls.set(0)
        val (id, _) = f.store.getOrCreateConversationIdForMessage(a)

        assertNotEquals("the generator handed out an id that another address holds", placed, id)
        assertEquals("the placed entry moved", b, f.conv(placed))
        assertNull("the placed id got an own-id record", f.own(placed))
        assertEquals("the new id is recorded as this phone's own for its address", a, f.own(id))
    }

    @Test
    fun `(b) an id with only an own-id record left behind is not returned either`() {
        val placed = ZMSGProtocol.generateConversationId()
        val f = ConversationIdStoreFixture()
        f.file
            .edit()
            .putString("own:$placed", b)
            .commit()

        calls.set(0)
        val (id, _) = f.store.getOrCreateConversationId(a)

        assertNotEquals("the generator handed out an id with an own-id record of another address", placed, id)
        assertEquals(b, f.own(placed))
    }
}

private val calls = AtomicInteger(0)

/** FC-ID test random source: the first 8 draws (one id) are zero bytes; later draws differ, so the next id differs. */
internal class FcIdRepeatableSpi : SecureRandomSpi() {
    override fun engineSetSeed(seed: ByteArray) = Unit

    override fun engineNextBytes(bytes: ByteArray) {
        val call = calls.getAndIncrement()
        for (i in bytes.indices) bytes[i] = if (call < 8) 0 else ((call * 37 + i * 11) and 0x7f).toByte()
    }

    override fun engineGenerateSeed(numBytes: Int): ByteArray = ByteArray(numBytes)
}

@Suppress("DEPRECATION")
internal object RepeatableRandom : Provider("FcIdRepeatableRandom", 1.0, "FC-ID test random source") {
    private fun readResolve(): Any = RepeatableRandom

    init {
        put("SecureRandom.FcIdRepeatable", FcIdRepeatableSpi::class.java.name)
    }
}
