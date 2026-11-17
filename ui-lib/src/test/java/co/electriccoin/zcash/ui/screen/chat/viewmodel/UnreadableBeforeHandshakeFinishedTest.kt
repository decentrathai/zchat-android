package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.R
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
 * ZC-1792 F2 and F3, ZC-1575 (QA2 lane k1; Fable root cause ZC1792_ROOT_CAUSE.md, 12 Nov; owner W-137: no crypto,
 * protocol, key-derivation or wire change) — what the receiver shows for a message that can never be opened.
 *
 * The HONOR on 12 Nov: the Seeker's message was sealed before the Seeker's answer (KEXACK) was paid, so it was mined BEFORE
 * the chat's first KEXACK. When the answer mined, the HONOR's root moved to one that includes it, the old envelope failed
 * its AES-GCM check, and the bubble said "its decryption key isn't on this device (usually it predates a wallet restore)"
 * while the "keys out of sync" banner offered a paid Re-establish that cannot bring the message back and tears down a session
 * that works. The banner also stayed up 20 minutes later because the failing row re-armed it on every pass.
 *
 * Wanted, over the real history pass ([HandshakePassFixture], the HONOR with the KEX and the KEXACK both mined and stored):
 * - F2: a failed row mined before the chat's first trusted KEXACK shows `chat_decrypt_placeholder_before_key_exchange`
 *   (the honest "sent before the key exchange finished"); a failed row mined AFTER keeps `chat_decrypt_placeholder_key_missing`
 *   (a genuine desync). The pre-handshake "yet" bubble (no contact key yet, so no processor) shows the two-case
 *   `chat_decrypt_placeholder_awaiting_answer`.
 * - F3: a failed row mined before the contact's newest trusted KEXACK never arms the keys-out-of-sync banner
 *   (hasDecryptFailure); a failed row after it still does (control); the banner clears on a pass where no qualifying row
 *   fails.
 *
 * The three new strings are reached by name (R.string by reflection, texts in Qa2K1CopyTest). No crypto change: the sealed
 * envelopes are made with the production root derivation over the material each side holds. All values are synthetic.
 *
 * ZC-1828 (QA2 pa3, [test-change-ok: ZC-1828]): since P-A (ZC-1792, W-142) a row the contact sealed before its answer OPENS
 * with the root as it stood at its height, so it is no longer an example of a row that cannot be opened. F2 and F3 take a
 * row sealed under a key that is not this chat's (a foreign key pair, the same KEX material) mined at the same height: it
 * fails every root, as the 12 Nov row did before P-A. The ZC-1828 cases below keep the real pre-answer row and pin how a
 * row the fallback opens composes with the banner settlement (QA2 k1b2's height rule, DecryptOutcomes.opened).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnreadableBeforeHandshakeFinishedTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val peer = contact(seed = 17921)
    private val ours = E2EEncryption.generateKeyPair()
    private val theirs = E2EEncryption.generateKeyPair()
    private val foreign = E2EEncryption.generateKeyPair()
    private val ownId = "ZC1792XA"

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

    // ---- F2 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `F2 - a failed row mined before the first KEXACK says it was sent before the key exchange finished`() =
        runBlocking {
            val f = handshakeDone(bogusAfter = false)
            f.pass(times = 2)

            val shown = textOf(f, PRE)
            assertTrue("fixture: the row did not open and shows a note ($shown)", NOTE.matches(shown))
            assertEquals(
                "the bubble of a message sealed before the answer keeps the wallet-restore text",
                note("chat_decrypt_placeholder_before_key_exchange"),
                shown,
            )
        }

    @Test
    fun `F2 control - a failed row mined after the first KEXACK keeps the genuine key-missing text`() =
        runBlocking {
            val f = handshakeDone(bogusAfter = true)
            f.pass(times = 2)

            assertEquals(note("chat_decrypt_placeholder_key_missing"), textOf(f, BOGUS))
        }

    @Test
    fun `F2 control - a row sealed after the answer opens`() =
        runBlocking {
            val f = handshakeDone(bogusAfter = false)
            f.pass(times = 2)

            assertEquals(POST_TEXT, textOf(f, POST))
        }

    @Test
    fun `F2 - the pre-handshake bubble, no contact key yet, carries the two-case text`() =
        runBlocking {
            val f = phone(withPeerKey = false)
            f.ownKex(KEX, to = peer, convId = ownId, minedAt = NEW + 5)
            f.received(PRE, replyMemo(sealedBeforeAnswer("zc1792 before")), minedAt = NEW + 8)
            f.pass(times = 2)

            val shown = textOf(f, PRE)
            assertTrue("fixture: the row shows a note ($shown)", NOTE.matches(shown))
            assertEquals(
                "no processor exists before the contact's key does: the bubble must say both cases",
                note("chat_decrypt_placeholder_awaiting_answer"),
                shown,
            )
        }

    // ---- F3 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `F3 - a failed row mined before the newest trusted KEXACK arms no keys-out-of-sync banner`() =
        runBlocking {
            val f = handshakeDone(bogusAfter = false)
            f.pass(times = 2)

            assertTrue("fixture: the row is in the chat and did not open", textOf(f, PRE) != "zc1792 before")
            assertFalse(
                "the banner offers a paid Re-establish for a message it cannot bring back",
                f.vm.hasDecryptFailure(peer),
            )
        }

    @Test
    fun `F3 control - a failed row after the KEXACK still arms the banner (a genuine desync)`() =
        runBlocking {
            val f = handshakeDone(bogusAfter = true)
            f.pass(times = 2)

            assertTrue("a real desync lost its banner", f.vm.hasDecryptFailure(peer))
        }

    @Test
    fun `F3 - the banner clears on a pass where no qualifying row fails`() =
        runBlocking {
            val f = handshakeDone(bogusAfter = true)
            f.pass(times = 2)
            assertTrue("fixture: the genuine failure armed the banner", f.vm.hasDecryptFailure(peer))

            // The failing row after the KEXACK is gone (hidden); only the row mined before it still fails.
            f.hidden += BOGUS
            f.pass(times = 2)

            assertFalse(
                "the banner stayed although only a row Re-establish cannot help still fails",
                f.vm.hasDecryptFailure(peer),
            )
        }

    // ---- ZC-1828: P-A's fallback and the banner settlement ------------------------------------------------------------

    @Test
    fun `ZC-1828 - the banner a genuine failure armed clears once the only other failing row is the one the fallback opens`() =
        runBlocking {
            val f = handshakeDone(bogusAfter = true, preOpensWithFallback = true)
            f.pass(times = 2)
            assertEquals("fixture: P-A opens the row sealed before the answer", "zc1792 before", textOf(f, PRE))
            assertTrue(
                "a row the fallback opens below the newest trusted KEXACK answers no later failure: the genuine one keeps the banner",
                f.vm.hasDecryptFailure(peer),
            )

            f.hidden += BOGUS
            f.pass(times = 2)

            assertEquals("zc1792 before", textOf(f, PRE))
            assertFalse(
                "the banner stayed although the only failure left was opened by the fallback",
                f.vm.hasDecryptFailure(peer),
            )
        }

    @Test
    fun `ZC-1828 - a row the fallback opens, mined above the newest trusted KEXACK and after a failure, answers it`() =
        runBlocking {
            // The HONOR's KEX (+5), the Seeker's KEXACK (+20), a genuine failure (+40), a Seeker row sealed after the answer
            // (+45), then the HONOR's next KEX (+50) that the current root mixes in. The +45 row fails the current root and
            // opens with the root at its height: the session works after the failure, as a fresh open would show.
            val f = phone(withPeerKey = true)
            f.ownKex(KEX, to = peer, convId = ownId, minedAt = NEW + 5)
            f.received(ACK, ackMemo(), minedAt = NEW + 20)
            f.received(BOGUS, replyMemo(sealedUnderOtherKey()), minedAt = NEW + 40)
            f.received(LATE, replyMemo(sealedUnder(f, setOf(KEX), setOf(ACK), LATE_TEXT)), minedAt = NEW + 45)
            f.ownKex(KEX2, to = peer, convId = ownId, minedAt = NEW + 50)
            root.kex[peer] = setOf(f.txid(KEX), f.txid(KEX2))
            root.acks[peer] = setOf(f.txid(ACK))
            f.pass(times = 2)

            assertEquals("fixture: P-A opens the row with the root at its height", LATE_TEXT, textOf(f, LATE))
            assertEquals("fixture: the genuine failure stays a placeholder", note("chat_decrypt_placeholder_key_missing"), textOf(f, BOGUS))
            assertFalse(
                "a contact row this phone opened above the newest trusted KEXACK and after the failure is recovery evidence " +
                    "(DecryptOutcomes.opened with its height), whichever root opened it",
                f.vm.hasDecryptFailure(peer),
            )
        }

    // ---- QA2 pa4: ZC-1865, ZC-1866 ------------------------------------------------------------------------------------

    @Test
    fun `ZC-1865 - our own key exchange still pending does not stop P-A opening a row`() =
        runBlocking {
            // After a Re-establish our new KEX is sent but not mined; the current root already mixes it. The row sealed
            // before the answer opens with the root at its height: the pending KEX is above every row, not unknown.
            val f = handshakeDone(bogusAfter = false, preOpensWithFallback = true)
            f.ownKex(KEX2, to = peer, convId = ownId, minedAt = null)
            root.kex[peer] = setOf(f.txid(KEX), f.txid(KEX2))
            f.pass(times = 2)

            assertEquals(
                "a pending own key exchange (listed by the wallet, no height yet) made the fallback fail closed",
                "zc1792 before",
                textOf(f, PRE),
            )
        }

    @Test
    fun `ZC-1866 - a stored earlier-root text read where the current root consumed the counter answers a failure`() =
        runBlocking {
            // As the ZC-1828 case: a genuine failure (+40), a row the fallback opens (+45, counter 0 under the earlier root)
            // and our next KEX (+50); plus a row under the current root with the same counter 0 (+55).
            val f = phone(withPeerKey = true)
            f.ownKex(KEX, to = peer, convId = ownId, minedAt = NEW + 5)
            f.received(ACK, ackMemo(), minedAt = NEW + 20)
            f.received(BOGUS, replyMemo(sealedUnderOtherKey()), minedAt = NEW + 40)
            f.received(LATE, replyMemo(sealedUnder(f, setOf(KEX), setOf(ACK), LATE_TEXT)), minedAt = NEW + 45)
            f.ownKex(KEX2, to = peer, convId = ownId, minedAt = NEW + 50)
            f.received(NEWER, replyMemo(sealedUnder(f, setOf(KEX, KEX2), setOf(ACK), NEWER_TEXT)), minedAt = NEW + 55)
            root.kex[peer] = setOf(f.txid(KEX), f.txid(KEX2))
            root.acks[peer] = setOf(f.txid(ACK))
            f.pass(times = 1)
            assertEquals("fixture: the current root opened the newer row", NEWER_TEXT, textOf(f, NEWER))
            assertEquals("fixture: P-A opened the late row", LATE_TEXT, textOf(f, LATE))

            // The newer row is hidden: the late row is the only open left. The current root has consumed counter 0, so it
            // answers the late row as a replay and its stored earlier-root text is read in the replay catch.
            f.hidden += NEWER
            f.pass(times = 2)

            assertEquals("the late row lost its text", LATE_TEXT, textOf(f, LATE))
            assertFalse(
                "the stored earlier-root text read in the replay catch was not noted as an open: the banner stayed",
                f.vm.hasDecryptFailure(peer),
            )
        }

    // ---- the phone -----------------------------------------------------------------------------------------------------

    /**
     * The HONOR with its KEX (mined at +5), a message sealed before the Seeker's answer (+8), the Seeker's KEXACK (+20), a
     * message sealed after it (+30) and, with [bogusAfter], one more row after the KEXACK that was sealed under a key that is
     * nobody's. The stored KEX and KEXACK sets already hold both ids, as on the HONOR after it read the answer.
     */
    private suspend fun handshakeDone(
        bogusAfter: Boolean,
        preOpensWithFallback: Boolean = false,
    ): HandshakePassFixture {
        val f = phone(withPeerKey = true)
        f.ownKex(KEX, to = peer, convId = ownId, minedAt = NEW + 5)
        // ZC-1828: by default the pre-answer row is one no root of this chat opens (a foreign key); with
        // [preOpensWithFallback] it is the real 12 Nov row, which P-A opens with the root at its height.
        val preEnvelope = if (preOpensWithFallback) sealedBeforeAnswer("zc1792 before") else sealedUnderForeignKey("zc1792 before")
        f.received(PRE, replyMemo(preEnvelope), minedAt = NEW + 8)
        f.received(ACK, ackMemo(), minedAt = NEW + 20)
        f.received(POST, replyMemo(sealedAfterAnswer(f)), minedAt = NEW + 30)
        if (bogusAfter) f.received(BOGUS, replyMemo(sealedUnderOtherKey()), minedAt = NEW + 40)
        root.kex[peer] = setOf(f.txid(KEX))
        root.acks[peer] = setOf(f.txid(ACK))
        return f
    }

    private val root = Root()

    private suspend fun phone(withPeerKey: Boolean): HandshakePassFixture {
        val f = HandshakePassFixture(scheduler, peer, firstContact = false)
        f.extra = { method, args -> root.answer(method.name, args) }
        f.prefs.ourKeyStore[peer] = ours.publicKey to ours.privateKey
        if (withPeerKey) f.prefs.e2ePeerKey[peer] = theirs.publicKey
        f.ids.store.restoreOwnConversationId(ownId, peer)
        f.open()
        PaymentHarness.setField(f.vm, "ratchetStateStore", InMemoryRatchetStateStore())
        return f
    }

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

    /** The contact answered nothing yet when it sealed: the KEX is the HONOR's only (its txid is known only after [phone]). */
    private suspend fun sealedBeforeAnswer(text: String): String = seal(senderRoot(setOf(kexTxid()), emptySet()), text)

    private suspend fun sealedAfterAnswer(f: HandshakePassFixture): String =
        seal(senderRoot(setOf(f.txid(KEX)), setOf(f.txid(ACK))), POST_TEXT)

    private suspend fun sealedUnderOtherKey(): String = seal(ByteArray(32) { 9 }, "zc1792 nobody's key")

    /** ZC-1828: sealed before the answer (KEX only) but from a key pair that is not the contact's: no root here opens it. */
    private suspend fun sealedUnderForeignKey(text: String): String =
        seal(
            E2ERatchet.deriveRatchetRoot(
                E2EEncryption.deriveSharedSecret(foreign.privateKey, ours.publicKey),
                null,
                E2ERatchet.canonicalTxidMaterial(setOf(kexTxid()), null),
                E2ERatchet.canonicalTxidMaterial(emptySet(), null),
            ),
            text,
        )

    /** ZC-1828: sealed under the root after the answer (KEX + KEXACK), the txids given by label. */
    private suspend fun sealedUnder(
        f: HandshakePassFixture,
        kexLabels: Set<String>,
        ackLabels: Set<String>,
        text: String,
    ): String = seal(senderRoot(kexLabels.mapTo(HashSet()) { f.txid(it) }, ackLabels.mapTo(HashSet()) { f.txid(it) }), text)

    /** The txid the fixture gives [KEX] (a function of the label only). */
    private fun kexTxid(): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(KEX.toByteArray())
            .joinToString("") { "%02x".format(it) }

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

    /** What the view model's test note copy gives for the string resource [name]: the resource id, not its words. */
    private fun note(name: String): String {
        val id =
            try {
                R.string::class.java.getField(name).getInt(null)
            } catch (e: NoSuchFieldException) {
                throw AssertionError("R.string.$name does not exist: the bubble cannot say what really happened", e)
            }
        return "note#$id()"
    }

    // ---- the stored root material (the ids a pass stores, as PendingOwnKeyExchangeRootTest) --------------------------------

    private class Root {
        val kex = ConcurrentHashMap<String, Set<String>>()
        val acks = ConcurrentHashMap<String, Set<String>>()
        val kexScalar = ConcurrentHashMap<String, String>()
        val ackScalar = ConcurrentHashMap<String, String>()

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
                // The production store keeps V2 keys (the fixture's HandshakePrefs knows no version).
                "getE2EKeyVersion" -> 2
                else -> ClaimMarkMaps.UNHANDLED
            }
    }

    private companion object {
        val NOTE = Regex("note#\\d+\\(\\)")
        const val KEX = "zc1792-own-kex"
        const val PRE = "zc1792-pre-answer-message"
        const val ACK = "zc1792-contact-answer"
        const val POST = "zc1792-post-answer-message"
        const val BOGUS = "zc1792-post-answer-unreadable"
        const val POST_TEXT = "zc1792 after the answer"
        const val KEX2 = "zc1828-own-second-kex"
        const val LATE = "zc1828-late-row"
        const val LATE_TEXT = "zc1828 opened by the fallback"
        const val NEWER = "zc1866-newer-row"
        const val NEWER_TEXT = "zc1866 under the current root"
    }
}
