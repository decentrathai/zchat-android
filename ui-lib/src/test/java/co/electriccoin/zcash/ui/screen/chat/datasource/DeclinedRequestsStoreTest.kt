package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1464 (OT213n B1b; FIX_PLAN REVISION 1): the Declined requests list.
 *
 * At 669cf19e1 a rejected request is deleted and only its relay key is kept in the blocked set, so nothing can be listed
 * or undone and there is no unblock at all.
 *
 * Wanted, in the real store: declining keeps the request (key, claimed address, first words, time) in a declined list;
 * the list holds at most 50 (the newest kept); Restore takes it off the list, unblocks the key and puts the request back
 * in the requests inbox; the destroy and reset wipe (clearAll) empties the list, since it keeps message text on the
 * device. The planned store methods are found by name (decline…: one MessageRequest; getDeclined…: no argument, a list
 * whose items carry senderNostrPubkeyHex; restore…Declined… or undoDecline…: one key or request), so this compiles
 * before they exist. Real ZchatPreferencesImpl over in-memory files ([realPreferences]). Every value is synthetic.
 */
class DeclinedRequestsStoreTest {
    private fun request(n: Int) =
        ZchatPreferences.MessageRequest(
            senderNostrPubkeyHex = RelayKeyBinding.relayKey(14_640 + n),
            senderAddress = SyntheticUnifiedAddress.mainnet(14_640 + n),
            relayUrl = RelayKeyBinding.RELAY,
            firstMessage = "synthetic request words $n",
            timestampMillis = 1_700_000_000_000L + n * 1_000L,
            eventId = "synthetic-zc1464-ev-$n",
            rumorId = "synthetic-zc1464-rumor-$n",
        )

    private fun method(
        prefs: ZchatPreferencesImpl,
        what: String,
        rule: (Method) -> Boolean,
    ): Method = prefs.javaClass.methods.firstOrNull(rule) ?: fail("the store has no $what")

    private fun call(
        prefs: ZchatPreferencesImpl,
        m: Method,
        vararg args: Any?,
    ): Any? =
        try {
            m.invoke(prefs, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun decline(
        prefs: ZchatPreferencesImpl,
        request: ZchatPreferences.MessageRequest,
    ) {
        val m =
            method(prefs, "decline (one MessageRequest)") {
                it.name.startsWith("decline", ignoreCase = true) &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == ZchatPreferences.MessageRequest::class.java
            }
        call(prefs, m, request)
    }

    private fun declinedKeys(prefs: ZchatPreferencesImpl): List<String> {
        val m =
            method(prefs, "getDeclined… list") {
                it.name.startsWith("getDeclined", ignoreCase = true) &&
                    it.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(it.returnType)
            }
        return (call(prefs, m) as List<*>).map { item ->
            item!!.javaClass.getMethod("getSenderNostrPubkeyHex").invoke(item) as String
        }
    }

    private fun restore(
        prefs: ZchatPreferencesImpl,
        request: ZchatPreferences.MessageRequest,
    ) {
        val m =
            method(prefs, "Restore (restore…Declined… / undoDecline…)") {
                Regex("(?i)(restore.*declin|undo.*declin)").containsMatchIn(it.name) && it.parameterCount == 1
            }
        call(prefs, m, if (m.parameterTypes[0] == String::class.java) request.senderNostrPubkeyHex else request)
    }

    @Test
    fun `a declined request is listed with its key`() {
        val (prefs, _) = realPreferences()
        val r = request(1)
        prefs.addMessageRequest(r)

        decline(prefs, r)
        prefs.blockNostrPubkey(r.senderNostrPubkeyHex)
        prefs.removeMessageRequest(r.senderNostrPubkeyHex)

        assertEquals(listOf(r.senderNostrPubkeyHex), declinedKeys(prefs), "the declined request is not in the Declined list")
    }

    @Test
    fun `Restore unblocks the key, returns the request to the inbox and takes it off the list`() {
        val (prefs, _) = realPreferences()
        val r = request(2)
        decline(prefs, r)
        prefs.blockNostrPubkey(r.senderNostrPubkeyHex)

        restore(prefs, r)

        assertFalse(prefs.isNostrPubkeyBlocked(r.senderNostrPubkeyHex), "the restored contact's key is still blocked")
        assertTrue(
            prefs.getMessageRequests().any { it.senderNostrPubkeyHex == r.senderNostrPubkeyHex },
            "the restored request is not back in the requests inbox",
        )
        assertEquals(emptyList(), declinedKeys(prefs), "the restored request is still in the Declined list")
    }

    @Test
    fun `the Declined list holds at most 50 and keeps the newest`() {
        val (prefs, _) = realPreferences()
        val all = (1..51).map { request(100 + it) }
        all.forEach { decline(prefs, it) }

        val kept = declinedKeys(prefs)
        assertTrue(kept.size <= 50, "the Declined list holds ${kept.size} entries, over the cap of 50")
        assertTrue(all.last().senderNostrPubkeyHex in kept, "the newest decline was dropped instead of the oldest")
    }

    @Test
    fun `the destroy and reset wipe empties the Declined list`() {
        val (prefs, _) = realPreferences()
        decline(prefs, request(3))

        prefs.clearAll()

        assertEquals(emptyList(), declinedKeys(prefs), "declined requests (their message text) survive the wipe")
    }
}
