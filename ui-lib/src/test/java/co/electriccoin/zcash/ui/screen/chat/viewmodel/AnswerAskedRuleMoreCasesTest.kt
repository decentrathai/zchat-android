package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

/**
 * ZC-599 (FIRST-CONTACT-SPEC revision 4a, 6.3): the cases of AnswerNobodyAskedForChangesNothingTest and
 * AnswersGoOutUnderTheIdOfTheirKeyExchangeTest the first lane scoped down — (e), (g), (i), (l), (r), (s) of the first
 * class and (d), (e), (h) of the second — over the real history pass ([HandshakePassFixture]). Written after the fix
 * (4239ebb67): each asserts the rule as built; a case that fails is a finding, not a guard. Every address, key, id and
 * txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnswerAskedRuleMoreCasesTest {
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

    private val a = SyntheticUnifiedAddress.mainnet(seed = 59921)
    private val b = SyntheticUnifiedAddress.mainnet(seed = 59922)

    private fun answerFrom(
        sender: String,
        keys: E2EKeyPair,
        convId: String,
        relayKey: String? = null,
    ): String {
        val payload =
            if (relayKey == null) {
                E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey)
            } else {
                E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey, relayKey, RELAY)
            }
        return ZMSGProtocol.createV4KEXAckMessage(convId, sender, payload)
    }

    private fun kexFrom(
        sender: String,
        keys: E2EKeyPair,
        convId: String,
    ): String = ZMSGProtocol.createV4KEXMessage(convId, sender, E2EEncryption.createKEXPayload(sender, keys.publicKey, keys.privateKey))

    // ---- AnswerNobodyAskedForChangesNothingTest -------------------------------------------------------------------------

    @Test
    fun `(e) a stranger's repeat answer at a saved contact moves no id, binds no relay key and raises no flag`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false, saved = setOf(a))
            val stranger = E2EEncryption.generateKeyPair()
            f.prefs.e2ePeerKey[a] = stranger.publicKey
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
            f.open()
            f.received("zc599x-e", answerFrom(a, stranger, "ZEEE2222", relayKey = RELAY_KEY), minedAt = NEW + 10)

            f.pass()

            assertEquals("this phone's own id for the contact moved", own, f.ids.peer(a))
            assertNull("the stranger's id was mapped", f.ids.conv("ZEEE2222"))
            assertFalse("a relay key was bound: ${f.prefs.unexpectedCalls}", f.prefs.unexpectedCalls.any { it in BIND_CALLS })
            assertFalse("the key-changed flag was raised", "setE2EKeyChanged" in f.prefs.unexpectedCalls)
        }

    @Test
    fun `(g) leg 1 with our key exchange mined two blocks after the answer is taken, forty-one blocks after is not`() =
        runBlocking {
            val near = HandshakePassFixture(scheduler, a, firstContact = false)
            val (own, _) = near.ids.store.getOrCreateConversationIdForMessage(a)
            near.open()
            val theirs = E2EEncryption.generateKeyPair()
            near.received("zc599x-g1-ack", answerFrom(a, theirs, own), minedAt = NEW + 10)
            near.ownKex("zc599x-g1-kex", a, own, minedAt = NEW + 12)
            near.pass()
            assertEquals("leg 1 within one expiry window must be taken", theirs.publicKey, near.prefs.e2ePeerKey[a])

            val far = HandshakePassFixture(scheduler, a, firstContact = false)
            val (ownFar, _) = far.ids.store.getOrCreateConversationIdForMessage(a)
            far.open()
            far.received("zc599x-g2-ack", answerFrom(a, E2EEncryption.generateKeyPair(), ownFar), minedAt = NEW + 10)
            far.ownKex("zc599x-g2-kex", a, ownFar, minedAt = NEW + 51)
            far.pass()
            assertNull("an answer 41 blocks older than our key exchange was taken", far.prefs.e2ePeerKey[a])
        }

    @Test
    fun `(i) leg 2 at an address with another contact key is not taken and raises no flag, with exactly its key a repeat`() =
        runBlocking {
            val other = HandshakePassFixture(scheduler, a, firstContact = false)
            val held = E2EEncryption.generateKeyPair().publicKey
            other.prefs.e2ePeerKey[a] = held
            val (own, _) = other.ids.store.getOrCreateConversationIdForMessage(a)
            other.open()
            other.ownKex("zc599x-i1-kex", a, own, minedAt = NEW + 10)
            other.received("zc599x-i1-ack", answerFrom(a, E2EEncryption.generateKeyPair(), "ZIII2222"), minedAt = NEW + 12)
            other.pass()
            assertEquals("leg 2 replaced another contact key", held, other.prefs.e2ePeerKey[a])
            assertFalse("leg 2 refused raised the key-changed flag", "setE2EKeyChanged" in other.prefs.unexpectedCalls)

            val same = HandshakePassFixture(scheduler, a, firstContact = false)
            val theirs = E2EEncryption.generateKeyPair()
            same.prefs.e2ePeerKey[a] = theirs.publicKey
            val (ownSame, _) = same.ids.store.getOrCreateConversationIdForMessage(a)
            same.open()
            same.ownKex("zc599x-i2-kex", a, ownSame, minedAt = NEW + 10)
            same.received("zc599x-i2-ack", answerFrom(a, theirs, "ZIII3333"), minedAt = NEW + 12)
            same.pass()
            assertEquals("the repeat must be taken: its asked record", theirs.publicKey, same.prefs.askedKey[a])
        }

    @Test
    fun `(l) a new answer under another id, with our key exchange sent to another address text, is not taken`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(b)
            f.open()
            f.ownKex("zc599x-l-kex", b, own, minedAt = NEW + 5)
            f.received("zc599x-l-ack", answerFrom(a, E2EEncryption.generateKeyPair(), "ZLLL2222"), minedAt = NEW + 10)

            f.pass()

            assertNull("a new answer with no key exchange of ours to its address was taken", f.prefs.e2ePeerKey[a])
        }

    @Test
    fun `(r) an id this phone uses for A but did not make is no proof - the answer is taken by leg 2 only`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            f.ids.store.setConversationMapping("ZRRR1111", a)
            f.open()
            val theirs = E2EEncryption.generateKeyPair()
            f.ownKex("zc599x-r-kex", a, "ZRRR1111", minedAt = NEW + 10)
            f.received("zc599x-r-ack", answerFrom(a, theirs, "ZRRR1111"), minedAt = NEW + 12)

            f.pass()

            assertNull("fixture: the id has no own-id record", f.ids.own("ZRRR1111"))
            assertEquals("leg 2 takes the answer", theirs.publicKey, f.prefs.e2ePeerKey[a])
        }

    @Test
    fun `(s) an own-made id whose entry holds another address than its record is not leg 1`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
            f.ids.file
                .edit()
                .putString("conv:$own", b)
                .commit()
            f.open()
            f.ownKex("zc599x-s-kex", a, own, minedAt = NEW + 10)
            f.received("zc599x-s-ack", answerFrom(b, E2EEncryption.generateKeyPair(), own), minedAt = NEW + 12)

            f.pass()

            assertNull("an answer under an id whose record names another address was taken at B", f.prefs.e2ePeerKey[b])
        }

    // ---- AnswersGoOutUnderTheIdOfTheirKeyExchangeTest -------------------------------------------------------------------

    /**
     * A request an earlier build received and processed: the key, the responder mark and the hold, no id kept; this phone
     * has its own id for the contact, and the earlier build mapped the key exchange's id to the contact.
     */
    private fun earlierRequest(
        f: HandshakePassFixture,
        keys: E2EKeyPair,
    ) {
        f.prefs.e2ePeerKey[a] = keys.publicKey
        f.prefs.receivedKex[a] = keys.publicKey
        f.prefs.awaitingAccept[a] = keys.publicKey
        f.prefs.e2eEnabled += a
        f.prefs.ourKeyStore[a] = E2EEncryption.generateKeyPair().let { it.publicKey to it.privateKey }
        f.ids.store.getOrCreateConversationIdForMessage(a)
        f.ids.store.setConversationMapping(KEX_ID, a)
    }

    @Test
    fun `(d) a request an earlier build received is answered under the id of its mined key exchange after one pass`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a)
            val keys = E2EEncryption.generateKeyPair()
            earlierRequest(f, keys)
            f.open()
            f.received("zc599x-gd-kex", kexFrom(a, keys, KEX_ID), minedAt = NEW + 10)
            f.prefs.processedKexTx += txidOf("zc599x-gd-kex")
            f.pass()

            f.vm.acceptInboundKexRequest(a)
            f.idle()

            assertEquals("fixture sanity: Accept paid one answer", 1, f.seam.proposalRequests.get())
            assertEquals("the answer did not carry the id of its key exchange", listOf(KEX_ID), memoIds(f.seam.proposalUris))
        }

    @Test
    fun `(e) with no such key exchange in the list the answer goes out under this phone's own id`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a)
            val keys = E2EEncryption.generateKeyPair()
            earlierRequest(f, keys)
            f.open()
            f.pass()

            f.vm.acceptInboundKexRequest(a)
            f.idle()

            val ids = memoIds(f.seam.proposalUris)
            assertEquals("fixture sanity: Accept paid one answer", 1, f.seam.proposalRequests.get())
            assertFalse("an answer went out under a key exchange's id that is not in the history", KEX_ID in ids)
        }

    @Test
    fun `(h) pay-once - a second Accept and a retry pay no second answer for the same key`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a)
            val keys = E2EEncryption.generateKeyPair()
            earlierRequest(f, keys)
            f.open()
            f.received("zc599x-gh-kex", kexFrom(a, keys, KEX_ID), minedAt = NEW + 10)
            f.prefs.processedKexTx += txidOf("zc599x-gh-kex")
            f.pass()

            f.vm.acceptInboundKexRequest(a)
            f.idle()
            f.prefs.awaitingAccept[a] = keys.publicKey
            f.vm.acceptInboundKexRequest(a)
            f.idle()
            f.vm.retryKexAckIfResponder(a)
            f.idle()
            f.pass()

            assertEquals("a second answer was paid for the same key", 1, f.seam.proposalRequests.get())
        }

    private companion object {
        const val KEX_ID = "KXID5992"
        const val RELAY = "wss://relay.zsend.xyz"
        const val RELAY_KEY = "11aa22bb33cc44dd55ee66ff7788990011aa22bb33cc44dd55ee66ff77889900"
        val BIND_CALLS = setOf("isOwnNostrPubkey", "findPeerByNostrPubkey", "setPeerNostrPubkey", "setPeerNostrRelay")

        fun txidOf(label: String): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(label.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** The conversation ids of the ZMSG v4 memos in [uris] (ZIP 321 requests, memos base64url), in order. */
        fun memoIds(uris: List<String>): List<String> =
            uris
                .flatMap { uri -> Regex("""memo(?:\.\d+)?=([A-Za-z0-9_%-]+)""").findAll(uri).map { it.groupValues[1] }.toList() }
                .mapNotNull { encoded ->
                    runCatching { String(Base64.getUrlDecoder().decode(URLDecoder.decode(encoded, "UTF-8").trimEnd('='))) }.getOrNull()
                }.mapNotNull { memo -> Regex("""^ZMSG\|v4\|([A-Z0-9]{8})\|""").find(memo)?.groupValues?.get(1) }
    }
}
