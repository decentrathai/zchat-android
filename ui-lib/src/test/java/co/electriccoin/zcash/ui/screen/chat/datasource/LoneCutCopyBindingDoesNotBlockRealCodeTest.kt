package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1 fixup (train T4b cross review, finding G-M1-2): a relay key whose ONLY binding is a cut copy of an address never
 * reaches the real address.
 *
 * 2.12.4's paste check only asked for "u1" and more than 100 characters, so a contact code cut to 102 characters bound
 * the contact's relay key K to the cut copy T alone. After the upgrade, M1's one-time repair looks only at keys bound
 * to more than one address and then sets its done flag, so this lone binding stays. When the user later pastes the
 * contact's full code (the real address F with K), bindPeerNostrKey asks findPeerByNostrPubkey(K), which returns T
 * unranked, and answers KEY_BOUND_ELSEWHERE: F never gets K and the contact's relay messages keep landing in T.
 *
 * Wanted: a holder that fails NostrPeerBinding.isBindable does not count as bound elsewhere, so F is bound and K
 * routes to F at once; T's rows are not touched by the bind itself (a live screen may still hold them), and the repair
 * runs again at the next start, which unbinds T and moves its delivered relay rows to F as it does for any doubly bound
 * key. A VALID lone holder still blocks, as before. Real ZchatPreferencesImpl over in-memory files ([realPreferences]);
 * the repair is reached by name. All values are synthetic.
 */
class LoneCutCopyBindingDoesNotBlockRealCodeTest {
    private val real = SyntheticUnifiedAddress.mainnet(56_001)
    private val cut = real.take(102)
    private val other = SyntheticUnifiedAddress.mainnet(56_002)
    private val key = RelayKeyBinding.relayKey(601)
    private val note = "synthetic rows-moved note"

    private fun row(
        id: String,
        peer: String,
        outgoing: Boolean,
        status: MessageStatus,
        at: Long,
    ) = ZchatPreferences.PendingMessageData(
        id = id,
        text = "synthetic $id",
        timestampMillis = at,
        peerAddress = peer,
        isOutgoing = outgoing,
        isPending = false,
        status = status.name,
    )

    private fun repair(prefs: ZchatPreferencesImpl): Int =
        try {
            prefs.javaClass
                .getMethod("repairDoubleBoundNostrKeysOnce", Function0::class.java)
                .invoke(prefs, { note }) as Int
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    /**
     * A wallet upgraded from 2.12.4 with K bound only to the cut copy, holding two of the contact's relay rows and one
     * failed send there, after M1's first repair already ran (it leaves a lone binding alone and sets its flag).
     */
    private fun upgradedWallet(): Pair<ZchatPreferencesImpl, Map<String, MemorySharedPreferences>> {
        val (prefs, files) = realPreferences()
        ZchatPreferencesImpl::class.java
            .getDeclaredField("selfAddresses\$delegate")
            .apply { isAccessible = true }
            .set(prefs, lazyOf(SelfAddressRegistry(MemorySharedPreferences())))
        files
            .getValue("modePrefs")
            .edit()
            .putString("pubkey:$cut", key)
            .commit()
        listOf(
            row("nmsg-cut-in-1", cut, outgoing = false, status = MessageStatus.SENT, at = 1_000),
            row("nmsg-cut-out-2", cut, outgoing = true, status = MessageStatus.SENT, at = 2_000),
            row("nostr-cut-out-3", cut, outgoing = true, status = MessageStatus.FAILED, at = 3_000),
        ).forEach { prefs.addPendingMessage(it) }
        assertEquals(0, repair(prefs), "fixture: M1's first repair moved rows of a lone binding")
        assertEquals(key, prefs.getPeerNostrPubkey(cut), "fixture: M1's first repair unbound a lone binding")
        assertEquals(-1, repair(prefs), "fixture: M1's first repair did not set its done flag")
        return prefs to files
    }

    @Test
    fun `pasting the full code binds the real address when the key's only holder is a cut copy`() {
        val (prefs, _) = upgradedWallet()

        val outcome = prefs.bindPeerNostrKey(real, key, RelayKeyBinding.RELAY, mayTakeFromBrokenHolder = true)

        assertEquals(
            NostrBindResult.BOUND,
            outcome.result,
            "the cut copy blocked the real code (otherPeer ${outcome.otherPeer?.take(10)}…)"
        )
        assertEquals(key, prefs.getPeerNostrPubkey(real), "the real address did not get the relay key")
        assertEquals(RelayKeyBinding.RELAY, prefs.getPeerNostrRelay(real))
        assertEquals(real, prefs.findPeerByNostrPubkey(key), "the contact's relay messages still route to the cut copy")
    }

    @Test
    fun `the bind moves no row, and the repair at the next start unbinds the cut copy and moves its relay rows`() {
        val (prefs, _) = upgradedWallet()
        val before = prefs.getPendingMessages().associateBy { it.id }

        prefs.bindPeerNostrKey(real, key, RelayKeyBinding.RELAY, mayTakeFromBrokenHolder = true)
        assertEquals(before, prefs.getPendingMessages().associateBy { it.id }, "the bind itself changed stored rows")

        val moved = repair(prefs)

        assertEquals(2, moved, "the repair did not run again after the bind, or moved the wrong rows")
        assertNull(prefs.getPeerNostrPubkey(cut), "the cut copy is still bound after the next start's repair")
        assertEquals(key, prefs.getPeerNostrPubkey(real))
        assertEquals(real, prefs.findPeerByNostrPubkey(key))
        val after = prefs.getPendingMessages().associateBy { it.id }
        for (id in listOf("nmsg-cut-in-1", "nmsg-cut-out-2")) {
            val row = assertNotNull(after[id], "$id was lost")
            assertEquals(before.getValue(id).copy(peerAddress = real), row, "$id was not moved to the real address as it was")
        }
        assertEquals(before["nostr-cut-out-3"], after["nostr-cut-out-3"], "a FAILED row was moved (its Retry would pay in the other chat)")
        assertEquals(1, after.values.count { it.text == note && it.peerAddress == real }, "not exactly one rows-moved note")
        assertEquals(-1, repair(prefs), "the repair did not set its done flag again")
    }

    @Test
    fun `a valid lone holder still blocks another address (guard)`() {
        val (prefs, files) = upgradedWallet()
        files
            .getValue("modePrefs")
            .edit()
            .remove("pubkey:$cut")
            .putString("pubkey:$other", key)
            .commit()

        val outcome = prefs.bindPeerNostrKey(real, key, RelayKeyBinding.RELAY, mayTakeFromBrokenHolder = true)

        assertEquals(NostrBindResult.KEY_BOUND_ELSEWHERE, outcome.result)
        assertEquals(other, outcome.otherPeer)
        assertNull(prefs.getPeerNostrPubkey(real), "a key held by a valid address was bound a second time")
        assertEquals(-1, repair(prefs), "a refused bind re-armed the repair")
    }

    @Test
    fun `the cut copy itself is still refused (guard)`() {
        val (prefs, _) = upgradedWallet()

        val outcome = prefs.bindPeerNostrKey(cut.dropLast(1), key, RelayKeyBinding.RELAY, mayTakeFromBrokenHolder = true)

        assertEquals(NostrBindResult.INVALID_ADDRESS, outcome.result)
        assertTrue(prefs.getPeerNostrPubkey(cut.dropLast(1)) == null, "a cut address was bound")
    }
}
