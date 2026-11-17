package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.crypto.DecryptedTextCacheKey
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.contact
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * QA2 lane k1b (review of the K1 fix ZC-1792 F2/F3 by Fable and GPT, 12 Nov; owner W-137: no crypto, protocol,
 * key-derivation or wire change) — how the history pass settles the keys-out-of-sync banner and the bubble text.
 *
 * Over the real history pass ([HandshakePassFixture]: the HONOR with its KEX and the contact's trusted KEXACK mined and
 * stored, as UnreadableBeforeHandshakeFinishedTest builds it):
 * - ZC-1809: a received row mined in the SAME block as the newest trusted KEXACK may have been sealed after it. It counts
 *   as a real failure (strict <): its bubble keeps chat_decrypt_placeholder_key_missing and the banner may arm.
 * - ZC-1810: recovery evidence (a fresh open that clears the banner) comes only from an INCOMING row decrypted with the
 *   current session. Our own saved outgoing plaintext, or an incoming text served from the stored cache, never clears it.
 * - ZC-1811: an incoming row the current session opened earlier in this process and now served from the in-memory cache
 *   still counts for that pass, so the next pass does not re-arm the banner (and the paid Re-establish).
 * - ZC-1819: the settlement recomputes the armed set every pass: a pass with no failure at all clears a banner a row
 *   armed that is now hidden.
 *
 * - QA2 k1b2, ZC-1841 ([test-change-ok: ZC-1841], Fable review of k1b): k1b took a stored incoming text as no evidence at
 *   all, and kept its in-memory evidence per ViewModel instance. The chat screen and the chat list each hold their own
 *   instance, and a restart starts a new one: in an answered chat the banner (and its paid Re-establish) came back on
 *   every open. Recovery has a durable basis now: an incoming row whose text this phone stored, mined strictly above the
 *   newest trusted KEXACK and after the failure, counts. Our own saved text still never counts (ZC-1810), nor a stored
 *   incoming row mined before the failure or in the KEXACK's own block.
 * - ZC-1845: a store read failure after a fresh open is no decrypt failure.
 * - ZC-1847 (GPT review of k1b): no session is read for the evidence (k1b's per-pass session could let an earlier session's
 *   memory hit answer a failure under a later one, and tagged a fresh open from store reads made after the decrypt). An
 *   open answers a failure only by its height (above the newest trusted KEXACK, after the failure); a banner a live
 *   failure armed is never cleared by a history pass.
 * - QA2 pa3 (GPT and Fable reviews of k1b2, [test-change-ok: ZC-1855 ZC-1856]): ZC-1855, ZC-1856, both production callers
 *   of tryDecryptMessage pass the pass's DecryptOutcomes, so the "live" branch (no outcomes) never ran on a phone, and its
 *   banner ownership made two ViewModels disagree (a live failure blocked a qualifying history recovery; a live success
 *   cleared a failure without the height rule). It is removed: a decrypt outside a history pass leaves the banner to the
 *   history settlement. The ZC-1847 test of that ownership is removed with it. ZC-1857, a row not mined has no height and
 *   answers no failure (it was taken as newer than every mined row). ZC-1859, our own row that fails is noted with its
 *   height, so a later incoming open answers it (with no height no open ever did, and the paid banner re-armed forever).
 *
 * Every address, key and txid is synthetic; no money.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Qa2K1bDecryptSettlementTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val peer = contact(seed = 18091)
    private val ours = E2EEncryption.generateKeyPair()
    private val theirs = E2EEncryption.generateKeyPair()
    private val ownId = "ZC1809XB"

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

    // ---- ZC-1809 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `ZC-1809 - a failed row mined in the same block as the newest trusted KEXACK keeps the key-missing text`() =
        runBlocking {
            val f = handshakeDone()
            f.received(SAME, replyMemo(sealedUnderOtherKey("zc1809 same block")), minedAt = ACK_HEIGHT)
            f.pass(times = 2)

            assertEquals(
                "a row of the KEXACK's own block may be sealed after it: it is a real desync, not 'before the key exchange'",
                note("chat_decrypt_placeholder_key_missing"),
                textOf(f, SAME),
            )
        }

    @Test
    fun `ZC-1809 - a failed row mined in the same block as the newest trusted KEXACK arms the banner`() =
        runBlocking {
            val f = handshakeDone()
            f.received(SAME, replyMemo(sealedUnderOtherKey("zc1809 same block")), minedAt = ACK_HEIGHT)
            f.pass(times = 2)

            assertTrue("fixture: the row did not open", NOTE.matches(textOf(f, SAME)))
            assertTrue(
                "a failure in the KEXACK's own block was called pre-handshake and the real desync lost its banner",
                f.vm.hasDecryptFailure(peer),
            )
        }

    // ---- ZC-1810 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `ZC-1810 - our own saved outgoing plaintext does not clear the banner of a real desync`() =
        runBlocking {
            val f = handshakeDone()
            f.received(BOGUS, replyMemo(sealedUnderOtherKey("zc1810 nobody's key")), minedAt = NEW + 40)
            // Our own message after the failure: its plaintext was saved when we sent it (cacheOwnOutgoingPlaintext).
            val own = sealedUnderOtherKey("zc1810 our own words", fill = 5)
            persisted[DecryptedTextCacheKey.of(ownId, own)] = "zc1810 our own words"
            f.sent(OUT, ZMSGProtocol.createV4ReplyMessage(ownId, HandshakePassFixture.SELF, own), to = peer, minedAt = NEW + 50)

            f.pass(times = 1)
            assertEquals("fixture: our own row shows its saved text", "zc1810 our own words", textOf(f, OUT))
            assertTrue(
                "our own saved outgoing text was taken as proof the contact's keys work: the banner cleared",
                f.vm.hasDecryptFailure(peer),
            )
            f.pass(times = 1)
            assertTrue("the banner of a real desync is gone on the next pass", f.vm.hasDecryptFailure(peer))
        }

    @Test
    fun `ZC-1810 - an incoming text served from the stored cache and mined before the failure does not clear the banner`() =
        runBlocking {
            val f = handshakeDone()
            // ZC-1841 ([test-change-ok: ZC-1841]): the stored row is mined BEFORE the failure; a stored row after it counts.
            val stored = sealedUnderOtherKey("zc1810 stored words", fill = 7)
            persisted[DecryptedTextCacheKey.of(ownId, stored)] = "zc1810 stored words"
            f.received(CACHED, replyMemo(stored), minedAt = NEW + 30)
            f.received(BOGUS, replyMemo(sealedUnderOtherKey("zc1810 nobody's key")), minedAt = NEW + 40)

            f.pass(times = 1)
            assertEquals("fixture: the stored text is shown", "zc1810 stored words", textOf(f, CACHED))
            assertTrue(
                "a stored text older than the failure was taken as recovery: the banner cleared",
                f.vm.hasDecryptFailure(peer),
            )
        }

    @Test
    fun `ZC-1841 - a stored incoming text in the newest KEXACK's own block is no recovery from a failure in that block`() =
        runBlocking {
            val f = handshakeDone()
            f.received(SAME, replyMemo(sealedUnderOtherKey("zc1841 same block")), minedAt = ACK_HEIGHT)
            val stored = sealedUnderOtherKey("zc1841 stored same block", fill = 6)
            persisted[DecryptedTextCacheKey.of(ownId, stored)] = "zc1841 stored same block"
            f.received(CACHED, replyMemo(stored), minedAt = ACK_HEIGHT)

            f.pass(times = 2)
            assertEquals("fixture: the stored text is shown", "zc1841 stored same block", textOf(f, CACHED))
            assertTrue(
                "a stored row of the KEXACK's own block (it may be sealed before it) cleared the banner of a real failure",
                f.vm.hasDecryptFailure(peer),
            )
        }

    // ---- ZC-1841 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `ZC-1841 - a second ViewModel instance keeps the banner down in an answered chat`() =
        runBlocking {
            val f = handshakeDone()
            f.received(BOGUS, replyMemo(sealedUnderOtherKey("zc1841 nobody's key")), minedAt = NEW + 25)
            f.received(POST, replyMemo(sealedAfterAnswer(f)), minedAt = NEW + 30)
            f.pass(times = 1)
            assertEquals("fixture: the current session opened the later row", POST_TEXT, textOf(f, POST))
            assertFalse("fixture: the first instance keeps the banner down", f.vm.hasDecryptFailure(peer))
            val list = f.vm

            // The chat screen's own instance (AndroidChat: the list and the screen each hold one), over the same stores.
            secondInstance(f)
            f.pass(times = 2)

            assertEquals("the second instance shows the stored text", POST_TEXT, textOf(f, POST))
            assertFalse(
                "the second instance re-armed the banner (and its paid Re-establish) in an answered chat",
                f.vm.hasDecryptFailure(peer),
            )
            assertFalse("the first instance's banner came back", list.hasDecryptFailure(peer))
        }

    @Test
    fun `ZC-1841 - after a restart the banner stays down in an answered chat`() =
        runBlocking {
            val f = handshakeDone()
            f.received(BOGUS, replyMemo(sealedUnderOtherKey("zc1841 nobody's key")), minedAt = NEW + 25)
            f.received(POST, replyMemo(sealedAfterAnswer(f)), minedAt = NEW + 30)
            f.pass(times = 1)
            assertFalse("fixture: a fresh open after the failure keeps the banner down", f.vm.hasDecryptFailure(peer))

            // A restart: every process-wide memory goes; the stored texts and the ratchet's stored state stay.
            AutoSpendVm.resetProcessState()
            AutoSpendVm.setHistoryRead(true)
            secondInstance(f)
            f.pass(times = 2)

            assertEquals("the restarted app shows the stored text", POST_TEXT, textOf(f, POST))
            assertFalse(
                "after a restart the banner (and its paid Re-establish) came back in an answered chat",
                f.vm.hasDecryptFailure(peer),
            )
        }

    // ---- ZC-1845 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `ZC-1845 - a failed read of the session fingerprint after a fresh open is no decrypt failure`() =
        runBlocking {
            val f = handshakeDone()
            f.received(POST, replyMemo(sealedAfterAnswer(f)), minedAt = NEW + 30)
            // The first store read after the opened text is saved (the fingerprint's) fails once.
            root.failReadAfterStore = true

            f.pass(times = 1)

            assertEquals("the opened row lost its text", POST_TEXT, textOf(f, POST))
            assertFalse(
                "a store read after a successful decrypt was taken as a decrypt failure: the banner armed",
                f.vm.hasDecryptFailure(peer),
            )
        }

    // ---- ZC-1811 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `ZC-1811 - an incoming success served from the in-memory cache keeps the banner down on the next pass`() =
        runBlocking {
            val f = handshakeDone()
            // A row that fails, then the contact's message sealed after the answer, which the current session opens.
            f.received(BOGUS, replyMemo(sealedUnderOtherKey("zc1811 nobody's key")), minedAt = NEW + 25)
            f.received(POST, replyMemo(sealedAfterAnswer(f)), minedAt = NEW + 30)

            f.pass(times = 1)
            assertEquals("fixture: the current session opened the later row", POST_TEXT, textOf(f, POST))
            assertFalse("fixture: a fresh open after the failure keeps the banner down", f.vm.hasDecryptFailure(peer))

            // The next pass serves the same row from the in-memory cache (the ratchet never opens a counter twice).
            f.pass(times = 1)
            assertEquals(POST_TEXT, textOf(f, POST))
            assertFalse(
                "the in-memory cache hit recorded no open: the banner (and its paid Re-establish) came back in an answered chat",
                f.vm.hasDecryptFailure(peer),
            )
        }

    // ---- ZC-1819 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `ZC-1819 - a pass with no failure at all clears a banner armed by a row now hidden`() =
        runBlocking {
            val f = handshakeDone()
            f.received(BOGUS, replyMemo(sealedUnderOtherKey("zc1819 nobody's key")), minedAt = NEW + 40)
            f.pass(times = 2)
            assertTrue("fixture: the genuine failure armed the banner", f.vm.hasDecryptFailure(peer))

            // The only failing row is hidden: the next pass meets no failure at all.
            f.hidden += BOGUS
            f.pass(times = 2)

            assertFalse(
                "the settlement returned early on a pass with no failures: the banner of a hidden row stays until restart",
                f.vm.hasDecryptFailure(peer),
            )
        }

    // ---- ZC-1847 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `ZC-1847 - a row opened under the earlier session and mined below a newer trusted KEXACK answers no later failure`() =
        runBlocking {
            val f = handshakeDone()
            f.received(POST, replyMemo(sealedAfterAnswer(f)), minedAt = NEW + 30)
            f.pass(times = 1)
            assertEquals("fixture: the earlier session opened the row", POST_TEXT, textOf(f, POST))

            // A newer trusted KEXACK (a new session), then a failure after it; the earlier row is served from memory.
            f.received(ACK2, ackMemo(), minedAt = NEW + 35)
            root.acks[peer] = setOf(f.txid(ACK), f.txid(ACK2))
            f.received(BOGUS, replyMemo(sealedUnderOtherKey("zc1847 after the new session")), minedAt = NEW + 40)
            f.pass(times = 2)

            assertTrue(
                "a row of the earlier session, served from memory, answered a failure under the newer one: the banner cleared",
                f.vm.hasDecryptFailure(peer),
            )
        }

    @Test
    fun `ZC-1847 - a fresh open is no answer by its place in the pass or a store read after it, only by its height`() =
        runBlocking {
            val f = handshakeDone()
            // A failure in the KEXACK's own block, then a row of that block the current session opens; right after the
            // open the stored root material changes (a store read after the decrypt names another session).
            f.received(SAME, replyMemo(sealedUnderOtherKey("zc1847 same block")), minedAt = ACK_HEIGHT)
            f.received(POST, replyMemo(sealedAfterAnswer(f)), minedAt = ACK_HEIGHT)
            root.changeRootAfterStore = { root.acks[peer] = setOf(f.txid(ACK), "zc1847-material-after-the-open") }

            f.pass(times = 1)
            assertEquals("fixture: the row of the KEXACK's block opened", POST_TEXT, textOf(f, POST))
            assertTrue(
                "a row of the KEXACK's own block (it may be sealed before it) answered the failure of that block",
                f.vm.hasDecryptFailure(peer),
            )
            f.pass(times = 1)
            assertTrue("the next pass cleared the banner from a session read after the open", f.vm.hasDecryptFailure(peer))
        }

    // ---- QA2 pa3: ZC-1855, ZC-1856, ZC-1857 (GPT review of k1b2) ---------------------------------------------------------

    @Test
    fun `ZC-1855 - a decrypt outside a history pass arms no banner and the history decides it alike in every ViewModel`() =
        runBlocking {
            val f = handshakeDone()
            val envelope = sealedUnderOtherKey("zc1855 failed outside a pass, then mined", fill = 3)
            liveDecrypt(f, envelope)
            assertFalse("a decrypt outside a history pass (no production caller) armed the banner", f.vm.hasDecryptFailure(peer))

            // The same envelope mined (+40), then a row the current session opens mined after it (+50), above the trusted
            // KEXACK (+20): a qualifying recovery.
            f.received(LIVE_THEN_MINED, replyMemo(envelope), minedAt = NEW + 40)
            f.received(POST, replyMemo(sealedAfterAnswer(f)), minedAt = NEW + 50)
            f.pass(times = 2)
            assertEquals("fixture: the later row opened", POST_TEXT, textOf(f, POST))
            assertFalse("the history recovery did not clear the banner in this ViewModel", f.vm.hasDecryptFailure(peer))
            secondInstance(f)
            f.pass(times = 2)
            assertFalse("control: a fresh ViewModel over the same history", f.vm.hasDecryptFailure(peer))
        }

    @Test
    fun `ZC-1856 - a decrypt outside a history pass does not clear a failure the height rule keeps`() =
        runBlocking {
            val f = handshakeDone()
            f.received(BOGUS, replyMemo(sealedUnderOtherKey("zc1856 nobody's key")), minedAt = NEW + 40)
            f.pass(times = 2)
            assertTrue("fixture: the history failure armed the banner", f.vm.hasDecryptFailure(peer))

            // A row with no height known here opens outside the pass (it may predate the failure).
            assertEquals("fixture: the row opened", POST_TEXT, liveDecrypt(f, sealedAfterAnswer(f)))

            assertTrue("a success with no height cleared a failure the height rule keeps", f.vm.hasDecryptFailure(peer))
        }

    @Test
    fun `ZC-1859 - our own row that fails is answered by a later incoming open, also in a second ViewModel`() =
        runBlocking {
            val f = handshakeDone()
            // Our own message after the answer (+40) that this phone cannot open (no stored text: sealed under a key that is
            // nobody's), then a contact row the current session opens (+50).
            val ownWire = ownSealedUnderOtherKey("zc1859 ours")
            f.sent(OUT, ZMSGProtocol.createV4ReplyMessage(ownId, HandshakePassFixture.SELF, ownWire), to = peer, minedAt = NEW + 40)
            f.received(POST, replyMemo(sealedAfterAnswer(f)), minedAt = NEW + 50)
            f.pass(times = 2)
            assertEquals("fixture: the contact's later row opened", POST_TEXT, textOf(f, POST))
            assertTrue("fixture: our own row did not open", NOTE.matches(textOf(f, OUT)))

            assertFalse(
                "our own failing row has no height, so no incoming open answers it: the paid Re-establish banner stays",
                f.vm.hasDecryptFailure(peer),
            )
            f.pass(times = 2)
            assertFalse("the next pass re-armed it", f.vm.hasDecryptFailure(peer))
            secondInstance(f)
            f.pass(times = 2)
            assertFalse("a second ViewModel re-armed it", f.vm.hasDecryptFailure(peer))
        }

    @Test
    fun `ZC-1857 - a row not mined yet answers no failure`() =
        runBlocking {
            val f = handshakeDone()
            f.received(BOGUS, replyMemo(sealedUnderOtherKey("zc1857 nobody's key")), minedAt = NEW + 40)
            f.received(POST, replyMemo(sealedAfterAnswer(f)), minedAt = null)
            f.pass(times = 2)
            assertEquals("fixture: the unmined row opened", POST_TEXT, textOf(f, POST))

            assertTrue(
                "a row with no height was taken as newer than every mined row and answered the failure",
                f.vm.hasDecryptFailure(peer),
            )
        }

    // ---- QA2 pa4: ZC-1863, ZC-1864 (Fable review of pa3) -----------------------------------------------------------------

    @Test
    fun `ZC-1863 - our own failing row mined below a newer trusted KEXACK arms no banner`() =
        runBlocking {
            val f = handshakeDone()
            // Our own row (+40) this phone cannot open, then a newer trusted KEXACK (+60, a Re-establish the user paid);
            // no incoming row after it.
            val ownWire = ownSealedUnderOtherKey("zc1863 ours")
            f.sent(OUT, ZMSGProtocol.createV4ReplyMessage(ownId, HandshakePassFixture.SELF, ownWire), to = peer, minedAt = NEW + 40)
            f.received(ACK2, ackMemo(), minedAt = NEW + 60)
            root.acks[peer] = setOf(f.txid(ACK), f.txid(ACK2))
            f.pass(times = 2)
            assertTrue("fixture: our own row did not open", NOTE.matches(textOf(f, OUT)))

            assertFalse(
                "an own row mined before the newest trusted KEXACK kept the banner: Re-establish cannot help it",
                f.vm.hasDecryptFailure(peer),
            )
        }

    @Test
    fun `ZC-1864 - a failing row with no height yet arms no banner`() =
        runBlocking {
            val f = handshakeDone()
            f.received(BOGUS, replyMemo(sealedUnderOtherKey("zc1864 not mined")), minedAt = null)
            f.pass(times = 2)
            assertTrue("fixture: the row did not open", NOTE.matches(textOf(f, BOGUS)))

            assertFalse("a failure with no height armed the paid banner; it waits for its height", f.vm.hasDecryptFailure(peer))
        }

    /** A decrypt outside the history pass (tryDecryptMessage with no outcomes), as a relay message meets it. */
    private suspend fun liveDecrypt(
        f: HandshakePassFixture,
        envelope: String,
    ): String =
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            val method =
                ChatViewModel::class.java.declaredMethods
                    .single { it.name == "tryDecryptMessage" && it.parameterCount == 5 }
                    .apply { isAccessible = true }
            try {
                method.invoke(f.vm, envelope, peer, ownId, null, continuation)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as String

    // ---- the phone -------------------------------------------------------------------------------------------------------

    /** The HONOR with its KEX (mined at +5) and the contact's trusted KEXACK (+20), both in the stored root sets. */
    private suspend fun handshakeDone(): HandshakePassFixture {
        val f = HandshakePassFixture(scheduler, peer, firstContact = false)
        f.extra = { method, args -> root.answer(method.name, args) }
        f.prefs.ourKeyStore[peer] = ours.publicKey to ours.privateKey
        f.prefs.e2ePeerKey[peer] = theirs.publicKey
        f.ids.store.restoreOwnConversationId(ownId, peer)
        f.open()
        PaymentHarness.setField(f.vm, "ratchetStateStore", ratchetStore)
        f.ownKex(KEX, to = peer, convId = ownId, minedAt = NEW + 5)
        f.received(ACK, ackMemo(), minedAt = ACK_HEIGHT)
        root.kex[peer] = setOf(f.txid(KEX))
        root.acks[peer] = setOf(f.txid(ACK))
        return f
    }

    /** The ratchet's stored state (it outlives a ViewModel instance and a restart, as the phone's store does). */
    private val ratchetStore = InMemoryRatchetStateStore()

    /** ZC-1841: another ViewModel instance over the same stores (the other screen's, or the one after a restart). */
    private suspend fun secondInstance(f: HandshakePassFixture) {
        f.open()
        PaymentHarness.setField(f.vm, "ratchetStateStore", ratchetStore)
    }

    /** The decrypted texts this phone stored earlier (getDecryptedText), by cache key. */
    private val persisted = ConcurrentHashMap<String, String>()

    private val root = Root()

    // ---- sealing (the production derivation over the material each side holds) --------------------------------------------

    private fun senderRoot(
        kexIds: Set<String>,
        ackIds: Set<String>,
    ): ByteArray =
        E2ERatchet.deriveRatchetRoot(
            E2EEncryption.deriveSharedSecret(theirs.privateKey, ours.publicKey),
            null,
            E2ERatchet.canonicalTxidMaterial(kexIds, null),
            E2ERatchet.canonicalTxidMaterial(ackIds, null),
        )

    private suspend fun seal(
        rootKey: ByteArray,
        text: String,
    ): String =
        E2EMessageProcessor(rootKey, ownId, theirs.publicKey < ours.publicKey, InMemoryRatchetStateStore()).encryptOutgoing(text)

    private suspend fun sealedAfterAnswer(f: HandshakePassFixture): String =
        seal(senderRoot(setOf(f.txid(KEX)), setOf(f.txid(ACK))), POST_TEXT)

    private suspend fun sealedUnderOtherKey(
        text: String,
        fill: Int = 9,
    ): String = seal(ByteArray(32) { fill.toByte() }, text)

    /** ZC-1859: in OUR direction, under a key that is nobody's. */
    private suspend fun ownSealedUnderOtherKey(text: String): String =
        E2EMessageProcessor(ByteArray(32) { 6 }, ownId, ours.publicKey < theirs.publicKey, InMemoryRatchetStateStore())
            .encryptOutgoing(text)

    private fun replyMemo(envelope: String): String = ZMSGProtocol.createV4ReplyMessage(ownId, peer, envelope)

    private fun ackMemo(): String =
        ZMSGProtocol.createV4KEXAckMessage(ownId, peer, E2EEncryption.createKEXAckPayload(peer, theirs.publicKey, theirs.privateKey))

    // ---- reading the result ----------------------------------------------------------------------------------------------

    private fun rowOf(
        f: HandshakePassFixture,
        label: String,
    ): ChatMessage {
        val chat = f.lastConversations?.firstOrNull { it.peerAddress == peer }
        assertNotNull("the pass produced no chat for the contact", chat)
        val row = chat!!.messages.firstOrNull { it.id == f.txid(label) }
        assertNotNull("the row $label is not in the chat: ${chat.messages.map { it.id.take(6) }}", row)
        return row!!
    }

    private fun textOf(
        f: HandshakePassFixture,
        label: String,
    ): String = rowOf(f, label).text

    /** What the view model's test note copy gives for the string resource [name]. */
    private fun note(name: String): String = "note#${R.string::class.java.getField(name).getInt(null)}()"

    // ---- the stored root material and the stored decrypted texts ----------------------------------------------------------

    private inner class Root {
        val kex = ConcurrentHashMap<String, Set<String>>()
        val acks = ConcurrentHashMap<String, Set<String>>()
        val kexScalar = ConcurrentHashMap<String, String>()
        val ackScalar = ConcurrentHashMap<String, String>()

        /** ZC-1845: the first Quantum Shield read after a stored decrypted text (the fingerprint's) throws once. */
        @Volatile var failReadAfterStore = false

        @Volatile private var armed = false

        @Volatile var readFailed = false

        /** ZC-1847: runs once right after the next stored decrypted text (a store change right after a fresh open). */
        @Volatile var changeRootAfterStore: (() -> Unit)? = null

        @Suppress("UNCHECKED_CAST")
        fun answer(
            method: String,
            a: Array<Any?>,
        ): Any? =
            when (method) {
                "getKexTxIds" -> kex[a[0] as String].orEmpty()
                "setKexTxIds" -> kex.put(a[0] as String, (a[1] as Set<String>).toSet()).let { null }
                "getKexAckTxIds" -> acks[a[0] as String].orEmpty()
                "setKexAckTxIds" -> acks.put(a[0] as String, (a[1] as Set<String>).toSet()).let { null }
                "getE2EKexTxId" -> kexScalar[a[0] as String]
                "setE2EKexTxId" -> kexScalar.put(a[0] as String, a[1] as String).let { null }
                "getE2EKexAckTxId" -> ackScalar[a[0] as String]
                "setE2EKexAckTxId" -> ackScalar.put(a[0] as String, a[1] as String).let { null }
                "getE2EKeyVersion" -> 2
                "getDecryptedText" -> persisted[a[0] as String]
                "putDecryptedText" -> {
                    if (failReadAfterStore) armed = true
                    persisted.put(a[0] as String, a[1] as String)
                    changeRootAfterStore?.also { changeRootAfterStore = null }?.invoke()
                    null
                }
                "getQuantumShieldPSK" ->
                    if (armed) {
                        armed = false
                        failReadAfterStore = false
                        readFailed = true
                        throw IllegalStateException("synthetic store read failure")
                    } else {
                        ClaimMarkMaps.UNHANDLED
                    }
                "removeDecryptedText" -> persisted.remove(a[0] as String).let { null }
                else -> ClaimMarkMaps.UNHANDLED
            }
    }

    private companion object {
        val NOTE = Regex("note#\\d+\\(\\)")
        const val ACK_HEIGHT = NEW + 20
        const val KEX = "zc1809-own-kex"
        const val ACK = "zc1809-contact-answer"
        const val SAME = "zc1809-same-block-as-the-answer"
        const val BOGUS = "zc1810-post-answer-unreadable"
        const val OUT = "zc1810-our-own-message"
        const val CACHED = "zc1810-stored-text"
        const val POST = "zc1811-post-answer-message"
        const val POST_TEXT = "zc1811 after the answer"
        const val ACK2 = "zc1847-newer-contact-answer"
        const val LIVE_THEN_MINED = "zc1855-live-then-mined"
    }
}
