package co.electriccoin.zcash.ui.screen.chat.datasource

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W-72 (register ZC-847, ZC-855), SPEC_F_20261019 rules R4 and R6 as settled in SPEC_DECISIONS D4 and D5, the store part of
 * RED tests 10 and 11: while a pre-update mark stands, the probe id N is made once ("legacyprobe:<address>" in the key store
 * names it, with the probe's sent transactions), from a fresh id with no `conv:` or own-id entry, written with its `conv:`
 * entry and own-id record; `peer:` is left as it was. Every later probe send reuses N; the record goes with the mark. On the
 * contact's side the reply id ("replyconv:<address>" in the conversation-id store: N, the height of the memo that set it,
 * answered or not) is armed by a newer memo only, answered once, and re-armed by a later probe.
 *
 * The REAL [ZchatPreferencesImpl] functions over in-memory files. Today none of it exists. Every address, key and id is
 * synthetic.
 */
class LegacyProbeRecordsTest {
    private val keys = ConversationIdStoreFixture.RecordingPrefs()
    private val ids = ConversationIdStoreFixture.RecordingPrefs()
    private val store: ZchatPreferencesImpl =
        (unsafeAllocate(ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl).also { impl ->
            set(impl, "e2ePrefs", keys)
            set(impl, "convMappingPrefs", ids)
            set(impl, "modePrefs", ConversationIdStoreFixture.RecordingPrefs())
            set(impl, "_e2eHandshakeTicks", MutableStateFlow(0L))
        }

    @Test
    fun `(10) the probe id is made once while the mark stands, with conv and own entries, peer unchanged, and goes with the mark`() {
        assertNull("a probe was made with no mark", call("startLegacyProbe", PEER))
        keys
            .edit()
            .putString("e2e_peer_pub_$PEER", KEY)
            .putString("legacykey:$PEER", KEY)
            .commit()
        ids
            .edit()
            .putString("peer:$PEER", OLD_ID)
            .putString("conv:$OLD_ID", PEER)
            .commit()

        val n = call("startLegacyProbe", PEER) as String?
        assertNotNull("startLegacyProbe is missing or made no id", n)
        assertEquals("the id is not a ZMSG v4 id", 8, n!!.length)
        assertNotEquals(OLD_ID, n)
        assertEquals("conv: does not name the address", PEER, ids.getString("conv:$n", null))
        assertEquals("the own-id record does not name the address", PEER, ids.getString("own:$n", null))
        assertEquals("peer: moved to the probe id", OLD_ID, ids.getString("peer:$PEER", null))
        assertEquals(n, call("getLegacyProbe", PEER))
        assertEquals("a later probe send made another id", n, call("startLegacyProbe", PEER))

        assertEquals(true, call("addLegacyProbeTx", PEER, n, "synthetic-probe-tx-1"))
        assertEquals(true, call("addLegacyProbeTx", PEER, n, "synthetic-probe-tx-2"))
        assertEquals("a transaction of another id was recorded", false, call("addLegacyProbeTx", PEER, OLD_ID, "synthetic-other"))
        assertEquals("synthetic-probe-tx-1\nsynthetic-probe-tx-2", call("getLegacyProbeTxIds", PEER))

        // Another key: the mark does not stand, so neither does the probe.
        keys.edit().putString("e2e_peer_pub_$PEER", OTHER).commit()
        assertNull("the probe outlived its mark", call("getLegacyProbe", PEER))
        keys.edit().putString("e2e_peer_pub_$PEER", KEY).commit()
        assertEquals(true, call("releaseLegacyKey", PEER, false))
        assertFalse("the release left the probe record", keys.contains("legacyprobe:$PEER"))
        val second = call("startLegacyProbe", PEER)
        assertNull("a probe was made after the mark went", second)
    }

    @Test
    fun `(11) the reply id is armed by a newer memo only, answered once, re-armed by a later probe`() {
        ids
            .edit()
            .putString("conv:$PROBE_ID", PEER)
            .putString("conv:$LATER_ID", PEER)
            .commit()
        assertEquals("armReplyConversationId is missing or did not save", true, call("armReplyConversationId", PEER, PROBE_ID, 3_500_000L))
        assertEquals(PROBE_ID, call("getReplyConversationId", PEER))
        assertTrue("the reply id is not in the conversation-id store", ids.contains("replyconv:$PEER"))

        assertEquals(true, call("armReplyConversationId", PEER, LATER_ID, 3_400_000L))
        assertEquals("an older memo replaced the reply id", PROBE_ID, call("getReplyConversationId", PEER))

        assertEquals(true, call("answerReplyConversationId", PEER, PROBE_ID))
        assertNull("an answered reply id is used again", call("getReplyConversationId", PEER))
        assertEquals(true, call("armReplyConversationId", PEER, PROBE_ID, 3_500_000L))
        assertNull("the same memo, read by the next pass, re-armed the reply id", call("getReplyConversationId", PEER))

        assertEquals(true, call("armReplyConversationId", PEER, PROBE_ID, 3_500_100L))
        assertEquals("a later probe did not re-arm the reply id", PROBE_ID, call("getReplyConversationId", PEER))

        // An id whose conv: entry names another address is never used.
        ids.edit().putString("conv:$PROBE_ID", "usyntheticsomeoneelse0000001").commit()
        assertNull("a reply id filed under another address is used", call("getReplyConversationId", PEER))
    }

    private fun call(
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            ZchatPreferencesImpl::class.java.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("the store has no $name with ${args.size} arguments")
        return try {
            method.invoke(store, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun set(
        impl: ZchatPreferencesImpl,
        name: String,
        value: Any,
    ) {
        ZchatPreferencesImpl::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(impl, value)
    }

    private fun unsafeAllocate(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe =
            unsafeClass
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz)!!
    }

    private companion object {
        const val PEER = "usyntheticlegacyprobe0000001"
        const val KEY = "c3ludGhldGljLWxlZ2FjeS1wcm9iZQ"
        const val OTHER = "c3ludGhldGljLW90aGVyLWtleQ"
        const val OLD_ID = "ZC72OLD1"
        const val PROBE_ID = "ZC72PRB9"
        const val LATER_ID = "ZC72LTR1"
    }
}
