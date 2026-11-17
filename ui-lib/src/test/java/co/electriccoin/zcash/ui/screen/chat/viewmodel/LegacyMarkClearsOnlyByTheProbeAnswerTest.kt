package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v3Boot
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * W-72 (register ZC-847, ZC-852, ZC-855), SPEC_F_20261019 rules R5, R5n and R5k with SPEC_DECISIONS D6 and D7, RED tests 5
 * to 9: the pre-update mark is cleared by one thing only, the contact's answer to our probe: a mined ordinary message under
 * the probe id N (an id this phone made for that address, `conv:` and own-id both naming it), mined above our probe, that
 * the ratchet opens with the marked key. Then the mark goes in one store section that hands back its held ZBOOT, which binds,
 * and nothing is paid. These never clear it: an answer the marked key does not open (a planted key: the real contact's
 * answer), a memo valid under the marked key but under an old id or a rebuilt own id (the planter, a stranger), an unmined
 * answer, an answer mined before the probe, a plaintext answer, and another key under N (not taken: nothing cleared, nothing
 * bound). The history pass reads every memo; the store's mark, probe and held ZBOOT are [ClaimMarkMaps].
 *
 * Today nothing clears the mark. Every address, key, id and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyMarkClearsOnlyByTheProbeAnswerTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `(5) the contact's answer under the probe id, mined after the probe, clears the mark and the held ZBOOT binds`() =
        runBlocking {
            val chat = MarkedChat(seed = 72151)
            chat.maps.legacyHeld[chat.peer] = v3Boot(chat.contact, relayKey(91))
            chat.probeSent(minedAt = NEW + 5)
            chat.received("w72-5-answer", chat.answer(chat.probe, "got your message"), minedAt = NEW + 10)

            chat.pass()

            assertNull("the answer to the probe did not clear the mark", chat.maps.legacy[chat.peer])
            assertEquals("the held ZBOOT did not bind after the clear", relayKey(91), chat.maps.nostr[chat.peer])
            assertTrue("the clear set the verified flag (only the user's tap does)", chat.peer !in chat.maps.verified)
            assertEquals(
                "the clear paid",
                0,
                chat.f.seam.proposalRequests
                    .get()
            )
        }

    @Test
    fun `(6) planted key - the real contact's answer under the probe id does not open with it, the mark stays`() =
        runBlocking {
            val chat = MarkedChat(seed = 72161)
            chat.probeSent(minedAt = NEW + 5)
            chat.received("w72-6-real", chat.answer(chat.probe, "real contact", root = ByteArray(32) { 9 }), minedAt = NEW + 10)

            chat.pass()

            assertEquals(
                "an answer the marked key does not open cleared the mark",
                chat.contact.publicKey,
                chat.maps.legacy[chat.peer]
            )
        }

    @Test
    fun `(6, 7) a memo valid under the marked key but under an old id or a rebuilt own id leaves the mark`() =
        runBlocking {
            val chat = MarkedChat(seed = 72171)
            chat.probeSent(minedAt = NEW + 5)
            chat.received("w72-7-old", chat.answer(chat.ownId, "the planter, under the chat's id"), minedAt = NEW + 10)
            chat.received("w72-7-their", chat.answer(OLD_THEIRS, "the planter, under the contact's old id"), minedAt = NEW + 11)
            chat.f.ids.store
                .restoreOwnConversationId(REBUILT, chat.peer)
            chat.received("w72-7-rebuilt", chat.answer(REBUILT, "under a rebuilt own id"), minedAt = NEW + 12)

            chat.pass()

            assertEquals(
                "a memo under another id than the probe's cleared the mark",
                chat.contact.publicKey,
                chat.maps.legacy[chat.peer]
            )
        }

    @Test
    fun `(8) an unmined answer, one mined before the probe, or a plaintext one leaves the mark`() =
        runBlocking {
            val cases = listOf("unmined", "before the probe", "plaintext")
            for (label in cases) {
                AutoSpendVm.resetProcessState()
                AutoSpendVm.setHistoryRead(true)
                val chat = MarkedChat(seed = 72181 + cases.indexOf(label))
                chat.probeSent(minedAt = NEW + 20)
                when (label) {
                    "unmined" -> chat.received("w72-8-a", chat.answer(chat.probe, "x"), minedAt = null)
                    "before the probe" -> chat.received("w72-8-b", chat.answer(chat.probe, "x"), minedAt = NEW + 19)
                    else -> chat.received("w72-8-c", ZMSGProtocol.createV4ReplyMessage(chat.probe, chat.peer, "plain"), minedAt = NEW + 30)
                }

                chat.pass()

                assertEquals("$label: cleared the mark", chat.contact.publicKey, chat.maps.legacy[chat.peer])
            }
        }

    @Test
    fun `(9) another key under the probe id is not taken - nothing cleared, nothing bound`() =
        runBlocking {
            val chat = MarkedChat(seed = 72191)
            chat.probeSent(minedAt = NEW + 5)
            val other = E2EEncryption.generateKeyPair()
            val payload = E2EEncryption.createKEXAckPayload(chat.peer, other.publicKey, other.privateKey)
            chat.received("w72-9-other", ZMSGProtocol.createV4KEXAckMessage(chat.probe, chat.peer, payload), minedAt = NEW + 10)

            chat.pass()

            assertEquals("another key replaced the marked one", chat.contact.publicKey, chat.f.prefs.e2ePeerKey[chat.peer])
            assertEquals("another key cleared the mark", chat.contact.publicKey, chat.maps.legacy[chat.peer])
            assertTrue("another key bound a relay identity: ${chat.maps.binds.size}", chat.maps.binds.isEmpty())
        }

    private inner class MarkedChat(
        seed: Int,
    ) {
        val peer: String = SyntheticUnifiedAddress.mainnet(seed = seed)
        val f = HandshakePassFixture(scheduler, peer, firstContact = false)
        val maps = ClaimMarkMaps(f.prefs.e2ePeerKey, f.prefs.askedKey)
        val contact = E2EEncryption.generateKeyPair()
        val ownId: String =
            f.ids.store
                .getOrCreateConversationIdForMessage(peer)
                .first
        val probe: String = ZMSGProtocol.generateConversationId()
        private val root = ByteArray(32) { 7 }

        init {
            f.prefs.e2ePeerKey[peer] = contact.publicKey
            maps.legacy[peer] = contact.publicKey
            // The probe id as startLegacyProbe left it: conv: and the own-id record name the address; peer: unchanged.
            f.ids.store.restoreOwnConversationId(probe, peer)
            maps.legacyProbe[peer] = probe
            f.extra = { method, args -> maps.handle(method, args) }
        }

        /** Our probe: the INIT under the probe id, sent by this wallet and mined at [minedAt]; its txid is the probe's. */
        fun probeSent(minedAt: Long) {
            val label = "w72-probe-${peer.takeLast(6)}"
            val wire = runBlocking { E2EMessageProcessor(root, probe, true, InMemoryRatchetStateStore()).encryptOutgoing("our probe") }
            f.sent(label, ZMSGProtocol.createV4InitMessage(probe, HandshakeHarness.SELF, wire), peer, minedAt)
            maps.legacyProbeTxIds.getOrPut(peer) { mutableListOf() } += f.txid(label)
        }

        /** The contact's ordinary message under [convId], encrypted by the contact's side of a ratchet over [root]. */
        fun answer(
            convId: String,
            text: String,
            root: ByteArray = this.root,
        ): String {
            val wire = runBlocking { E2EMessageProcessor(root, convId, false, InMemoryRatchetStateStore()).encryptOutgoing(text) }
            return ZMSGProtocol.createV4ReplyMessage(convId, peer, wire)
        }

        fun received(
            label: String,
            memo: String,
            minedAt: Long?,
        ) = f.received(label, memo, minedAt)

        suspend fun pass() {
            val vm = f.open()
            val ratchets = InMemoryRatchetStateStore()
            val processors =
                object : ConcurrentHashMap<String, E2EMessageProcessor>() {
                    override fun get(key: String): E2EMessageProcessor? =
                        super.get(key) ?: if (key.startsWith("$peer:")) {
                            computeIfAbsent(key) { E2EMessageProcessor(root, key.substringAfter(':'), true, ratchets) }
                        } else {
                            null
                        }
                }
            AutoSpendVm.setField(vm, "messageProcessors", processors)
            f.pass()
        }
    }

    private companion object {
        const val OLD_THEIRS = "ZC72OLDT"
        const val REBUILT = "ZC72RBLT"
    }
}
