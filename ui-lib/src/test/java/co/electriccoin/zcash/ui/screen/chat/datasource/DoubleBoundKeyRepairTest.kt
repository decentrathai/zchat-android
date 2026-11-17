package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1 (fix-all 2.13.0, train T4b; money SPEC §1.3 step 5, §5 R1-4): the one-time repair of a relay key bound to two
 * addresses, on an upgrade.
 *
 * A wallet that met the M1 bug holds one relay key bound to a real address F and to a broken copy T (fails the Bech32m
 * check), with the contact's relay messages stored under T. Nothing at HEAD repairs it.
 *
 * Wanted (run once per wallet, before the relay inbox dispatches): T is unbound; T's relay rows (inbound rows, and
 * outgoing rows that reached a relay: status SENT, ids nmsg-/nostr-, no transaction id) move to F keeping their ids and
 * times; T's pending_ rows, FAILED rows and on-chain rows stay on T (a moved FAILED row's Retry would pay in the other
 * chat); F's own rows are untouched; one system note tells F's chat that rows were moved; two VALID addresses on one
 * key both stay bound (unbinding a valid Tunnel or Shielded chat could turn its next send into a paid on-chain one); a
 * second run changes nothing; the done flag is written with commit(). Real ZchatPreferencesImpl over in-memory files
 * ([realPreferences]); the repair is reached by name, so this file compiles before the fix. All values are synthetic.
 */
class DoubleBoundKeyRepairTest {
    private val real = SyntheticUnifiedAddress.mainnet(55_001)
    private val cut = real.take(102)
    private val key = RelayKeyBinding.relayKey(501)
    private val valid1 = SyntheticUnifiedAddress.mainnet(55_002)
    private val valid2 = SyntheticUnifiedAddress.mainnet(55_003)
    private val key2 = RelayKeyBinding.relayKey(502)
    private val note = "synthetic rows-moved note"

    /** Records which keys each editor wrote through commit() and which through apply(). */
    private class CommitRecordingPrefs(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        val committed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val applied: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        override fun edit(): SharedPreferences.Editor = Recording(delegate.edit())

        private inner class Recording(
            private val inner: SharedPreferences.Editor,
        ) : SharedPreferences.Editor by inner {
            private val keys = mutableSetOf<String>()

            override fun putString(
                key: String,
                value: String?
            ) = apply {
                keys += key
                inner.putString(key, value)
            }

            override fun putBoolean(
                key: String,
                value: Boolean
            ) = apply {
                keys += key
                inner.putBoolean(key, value)
            }

            override fun putInt(
                key: String,
                value: Int
            ) = apply {
                keys += key
                inner.putInt(key, value)
            }

            override fun putLong(
                key: String,
                value: Long
            ) = apply {
                keys += key
                inner.putLong(key, value)
            }

            override fun remove(key: String) =
                apply {
                    keys += key
                    inner.remove(key)
                }

            override fun commit(): Boolean {
                committed += keys
                return inner.commit()
            }

            override fun apply() {
                applied += keys
                inner.apply()
            }
        }
    }

    private fun row(
        id: String,
        peer: String,
        outgoing: Boolean,
        status: MessageStatus?,
        at: Long,
        pending: Boolean = false,
        txIds: List<String> = emptyList(),
    ) = ZchatPreferences.PendingMessageData(
        id = id,
        text = "synthetic $id",
        timestampMillis = at,
        peerAddress = peer,
        isOutgoing = outgoing,
        isPending = pending,
        status = status?.name,
        submittedTxIds = txIds,
    )

    private fun repair(prefs: ZchatPreferencesImpl): Int =
        try {
            prefs.javaClass
                .getMethod("repairDoubleBoundNostrKeysOnce", Function0::class.java)
                .invoke(prefs, { note }) as Int
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun seeded(): Triple<ZchatPreferencesImpl, Map<String, MemorySharedPreferences>, CommitRecordingPrefs> {
        val (prefs, files) = realPreferences()
        val recording = CommitRecordingPrefs(files.getValue("modePrefs"))
        ZchatPreferencesImpl::class.java
            .getDeclaredField("modePrefs")
            .apply { isAccessible = true }
            .set(prefs, recording)
        val modePrefs = files.getValue("modePrefs")
        modePrefs
            .edit()
            .putString("pubkey:$cut", key)
            .putString("pubkey:$real", key)
            .putString("mode:$real", ConversationMode.OPEN.name)
            .putString("pubkey:$valid1", key2)
            .putString("pubkey:$valid2", key2)
            .putString("mode:$valid1", ConversationMode.TUNNEL.name)
            .putString("mode:$valid2", ConversationMode.VAULT.name)
            .commit()
        files
            .getValue("nicknamePrefs")
            .edit()
            .putString(real, "Synthetic HONOR")
            .commit()
        listOf(
            // On the cut copy: relay rows that move ...
            row("nmsg-in-1", cut, outgoing = false, status = MessageStatus.SENT, at = 1_000),
            row("nostr-in-2", cut, outgoing = false, status = MessageStatus.SENT, at = 2_000),
            row("nmsg-out-3", cut, outgoing = true, status = MessageStatus.SENT, at = 3_000),
            // ... and rows that stay.
            row("nostr-out-4", cut, outgoing = true, status = MessageStatus.FAILED, at = 4_000),
            row("nostr-out-5", cut, outgoing = true, status = MessageStatus.SENDING, at = 5_000, pending = true),
            row("pending_6", cut, outgoing = true, status = null, at = 6_000, pending = true),
            row("synthetic-onchain-7", cut, outgoing = true, status = MessageStatus.SENT, at = 7_000, txIds = listOf("synthetic-txid-7")),
            row("nmsg-out-8", cut, outgoing = true, status = MessageStatus.SENT, at = 8_000, txIds = listOf("synthetic-txid-8")),
            // The real address's own row, and a row of a valid pair.
            row("nmsg-real-9", real, outgoing = false, status = MessageStatus.SENT, at = 9_000),
            row("nmsg-valid2-10", valid2, outgoing = false, status = MessageStatus.SENT, at = 10_000),
        ).forEach { prefs.addPendingMessage(it) }
        return Triple(prefs, files, recording)
    }

    @Test
    fun `the cut copy is unbound and only its relay rows move to the real address`() {
        val (prefs, _, _) = seeded()
        val before = prefs.getPendingMessages().associateBy { it.id }

        repair(prefs)

        assertNull(prefs.getPeerNostrPubkey(cut), "the cut copy is still bound")
        assertEquals(key, prefs.getPeerNostrPubkey(real), "the real address lost its binding")
        assertEquals(real, prefs.findPeerByNostrPubkey(key))
        val after = prefs.getPendingMessages().associateBy { it.id }
        for (id in listOf("nmsg-in-1", "nostr-in-2", "nmsg-out-3")) {
            val moved = assertNotNull(after[id], "$id was lost")
            assertEquals(real, moved.peerAddress, "$id was not moved to the real address")
            assertEquals(before.getValue(id).copy(peerAddress = real), moved, "$id changed beyond its address")
        }
        for (id in listOf("nostr-out-4", "nostr-out-5", "pending_6", "synthetic-onchain-7", "nmsg-out-8")) {
            assertEquals(before[id], after[id], "$id (not a delivered relay row) was moved or changed")
        }
        assertEquals(before["nmsg-real-9"], after["nmsg-real-9"], "the real address's own row changed")
        val notes = after.values.filter { it.text == note }
        assertEquals(1, notes.size, "there is not exactly one rows-moved note: $notes")
        assertEquals(real, notes.single().peerAddress)
        assertTrue(notes.single().id.startsWith("sysnote-"), "the note is not a system note: ${notes.single().id}")
        assertEquals(false, notes.single().isOutgoing)
    }

    @Test
    fun `two valid addresses on one key both stay bound and keep their rows`() {
        val (prefs, _, _) = seeded()

        repair(prefs)

        assertEquals(key2, prefs.getPeerNostrPubkey(valid1), "a valid address was unbound")
        assertEquals(key2, prefs.getPeerNostrPubkey(valid2), "a valid address was unbound")
        assertEquals(valid2, prefs.getPendingMessages().single { it.id == "nmsg-valid2-10" }.peerAddress)
        assertEquals(ConversationMode.TUNNEL, prefs.getConversationModeOrNull(valid1))
        assertEquals(ConversationMode.VAULT, prefs.getConversationModeOrNull(valid2))
    }

    @Test
    fun `a second run changes nothing and the done flag is committed`() {
        val (prefs, files, recording) = seeded()

        repair(prefs)
        val modeAfterFirst = files.getValue("modePrefs").snapshot()
        val rowsAfterFirst = files.getValue("pendingMsgPrefs").snapshot()
        repair(prefs)

        assertEquals(modeAfterFirst, files.getValue("modePrefs").snapshot(), "a second run changed the bindings")
        assertEquals(rowsAfterFirst, files.getValue("pendingMsgPrefs").snapshot(), "a second run changed the rows")
        val flagKeys = (recording.committed + recording.applied).filter { it.startsWith("nostrbind_repair") }
        assertTrue(flagKeys.isNotEmpty(), "no done flag was written")
        assertTrue(flagKeys.all { it in recording.committed }, "the done flag was not written with commit()")
        assertTrue(flagKeys.none { it in recording.applied }, "the done flag was written with apply()")
    }

    @Test
    fun `a wallet loaded later is repaired when the stores are reloaded`() {
        val (prefs, files) = realPreferences()
        files
            .getValue("modePrefs")
            .edit()
            .putString("pubkey:$cut", key)
            .putString("pubkey:$real", key)
            .commit()

        prefs.reloadWalletScopedStores()

        assertNull(prefs.getPeerNostrPubkey(cut), "the loaded wallet's cut copy is still bound")
        assertEquals(key, prefs.getPeerNostrPubkey(real))
    }
}
