package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.screen.chat.crypto.DecryptedTextCacheKey
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.Ciphertext
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.OldRootFallbackApi
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.SELF
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1792 (owner W-142, P-A; PA_DESIGN.md sections 1, 2, 4, 5) — a message the responder sealed BEFORE it paid its answer
 * opens on the initiator once the answer is mined, with the root as it stood at the message's height.
 *
 * The 12 Nov shape (ZC1792_ROOT_CAUSE.md section 1): the HONOR (initiator) sent its KEX KH; the Seeker (responder) held the
 * HONOR's key, behind the Accept hold, and sealed a message with root = HKDF(ECDH, KEX {KH}, KEXACK {}); on 12 Nov the
 * Seeker's answer KA mined and the HONOR's root became (KEX {KH}, KEXACK {KA}), so the AEAD tag of the 9 Nov message failed
 * and nothing tried the earlier root. Here the HONOR's pass (the REAL convertToConversations over [OldRootPhone]: the real
 * ratchet code path, a real [InMemoryRatchetStateStore]) must show the plaintext and no "keys out of sync" banner.
 *
 * The rules pinned (all through the real pass):
 * 1. the pre-answer message opens, in a received row and in the Seeker's own sent row (a restored wallet, no own-text cache);
 * 2. a foreign-key envelope, forged rows, and a copy of the envelope re-mined later than the expiry window after the answer
 *    stay closed, within the design's budgets (3 attempts per row, 64 failed per peer per pass, none on a complete repeat);
 * 3. nothing about the CURRENT root changes: its ratchet store is byte-equal to a run where the row was unreadable, and its
 *    next message still opens at its own counter;
 * 4. display only: an old-root timer or mode control is not applied, and the opened row sets no legacy-key evidence;
 * 5. the stored text is bound to the row (its txid), not to the envelope: the plain cache key is not written, and a TTL
 *    expiry deletes it.
 *
 * Seam the fix must create, reached by name (see OldRootFallbackApi): `crypto/ratchet/OldRootFallback.kt`. Heights use the
 * fixture's NEW (3,499,000, below NU7 = 3,543,405), so the expiry window is 40 here. Every address, key, id and txid is
 * synthetic; labels name the transactions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OldRootFallbackOpensPreAnswerMessageTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val seeker = OldRootPhone.contact(seed = 17921)
    private val honor = OldRootPhone.contact(seed = 17922)
    private val honorKeys = E2EEncryption.generateKeyPair()
    private val seekerKeys = E2EEncryption.generateKeyPair()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        freshProcess()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        OldRootFallbackApi.resetProcessState()
    }

    // ---- the controls ------------------------------------------------------------------------------------------------

    @Test
    fun `0 - control - a message sealed under the current root opens by the first path with no fallback`() =
        runBlocking {
            val honorPhone = initiator()
            val wire = honorPhone.contactSealer(honorPhone.root(setOf(KH), setOf(KA)), A).seal(TEXT)
            honorPhone.f.received("m-now", honorPhone.inboundMemo(A, wire), minedAt = NEW + 30)

            val rows = honorPhone.passes(2)

            assertEquals(TEXT, honorPhone.textOf(rows, "m-now"))
            assertEquals("the current root needed no fallback", 0L, OldRootFallbackApi.attemptCount())
        }

    // ---- the 12 Nov scenario ------------------------------------------------------------------------------------------

    @Test
    fun `1 - the responder sealed before paying its answer - the initiator's pass shows the plaintext and no banner`() =
        runBlocking {
            val honorPhone = initiator()
            val wire = honorPhone.contactSealer(honorPhone.root(setOf(KH), emptySet()), A).seal(TEXT)
            honorPhone.f.received("m-old", honorPhone.inboundMemo(A, wire), minedAt = NEW + 8)

            val rows = honorPhone.passes(3)

            assertEquals("the 9 Nov message reads, as the Seeker wrote it", TEXT, honorPhone.textOf(rows, "m-old"))
            assertFalse("no 'keys out of sync' banner for a message that reads", honorPhone.hasDecryptFailure())
            val attempts = OldRootFallbackApi.attemptCount()
            assertTrue("the earlier root was tried (attempts = $attempts)", attempts >= 1)
            assertTrue("at most 3 tries per row per pass, 3 passes (attempts = $attempts)", attempts <= 9)
        }

    @Test
    fun `2 - the responder's own sent row opens too - a restored wallet has no own-text cache`() =
        runBlocking {
            // The Seeker's phone after a restore: the HONOR's KEX KH received, its own message M and its own answer KA sent.
            val seekerPhone = responder()
            val wire = seekerPhone.ownSealer(seekerPhone.root(setOf(KH), emptySet()), A).seal(TEXT)
            seekerPhone.f.received(KH, kexMemo(A, honor, honorKeys), minedAt = NEW + 5)
            seekerPhone.f.sent("m-old", seekerPhone.ownMemo(A, wire), to = honor, minedAt = NEW + 8)
            seekerPhone.f.sent(KA, ackMemo(A, SELF, seekerKeys), to = honor, minedAt = NEW + 20)

            val rows = seekerPhone.passes(3)

            assertEquals("its own message, sealed before its answer, reads", TEXT, seekerPhone.textOf(rows, "m-old"))
        }

    @Test
    fun `3 - a 2-12-4 responder whose automatic answer failed is recovered the same way`() =
        runBlocking {
            // A 2.12.4 phone seals with the same derivation (the Frozen2124 sealer, copied from v2.12.4): HEAD reads it.
            val honorPhone = initiator()
            val wire = Frozen2124Sealer.seal(frozenRoot(setOf(KH), emptySet(), honorPhone), A, !lower(honorKeys, seekerKeys), TEXT)
            honorPhone.f.received("m-old", honorPhone.inboundMemo(A, wire), minedAt = NEW + 8)

            val rows = honorPhone.passes(3)

            assertEquals(TEXT, honorPhone.textOf(rows, "m-old"))
            assertFalse(honorPhone.hasDecryptFailure())
        }

    // ---- what stays closed --------------------------------------------------------------------------------------------

    @Test
    fun `4 - a foreign key's envelope stays a placeholder and costs at most 3 tries a row`() =
        runBlocking {
            val honorPhone = initiator()
            val stranger = E2EEncryption.generateKeyPair()
            val foreignRoot =
                E2ERatchet.deriveRatchetRoot(
                    E2EEncryption.deriveSharedSecret(stranger.privateKey, honorKeys.publicKey),
                    null,
                    E2ERatchet.canonicalTxidMaterial(setOf(honorPhone.txid(KH)), null),
                    E2ERatchet.canonicalTxidMaterial(emptySet(), null),
                )
            val wire = foreignSealer(foreignRoot, A).seal(TEXT)
            honorPhone.f.received("m-foreign", honorPhone.inboundMemo(A, wire), minedAt = NEW + 8)

            val rows = honorPhone.passes(2)

            val shown = honorPhone.textOf(rows, "m-foreign")
            assertNotEquals("a stranger's text must not read", TEXT, shown)
            assertFalse("the raw envelope must never be shown: $shown", shown.startsWith("E2E1:"))
            val attempts = OldRootFallbackApi.attemptCount()
            assertTrue("the fallback did try (attempts = $attempts)", attempts >= 1)
            assertTrue("at most 3 per row per pass, 2 passes (attempts = $attempts)", attempts <= 6)
        }

    @Test
    fun `5 - a few forged rows cost at most 3 tries each, and a complete repeat costs none`() =
        runBlocking {
            val honorPhone = initiator()
            forgedRows(honorPhone, count = 20)

            honorPhone.passes(1)
            val first = OldRootFallbackApi.attemptCount()
            honorPhone.passes(1)
            val second = OldRootFallbackApi.attemptCount() - first
            honorPhone.passes(1)
            val third = OldRootFallbackApi.attemptCount() - first - second

            assertTrue("the fallback tried the forged rows (first pass: $first)", first >= 1)
            assertTrue("20 rows x 3 tries at most (first pass: $first)", first <= 20L * OldRootFallbackApi.maxAttemptsPerRow())
            assertTrue("20 rows x 3 tries at most (second pass: $second)", second <= 20L * OldRootFallbackApi.maxAttemptsPerRow())
            assertEquals("a third complete pass over the same rows derives nothing (negative cache)", 0L, third)
        }

    @Test
    fun `6 - 200 forged rows stay within 64 failed tries per peer per pass`() =
        runBlocking {
            val honorPhone = initiator()
            forgedRows(honorPhone, count = 200)

            honorPhone.passes(1)
            val first = OldRootFallbackApi.attemptCount()

            assertTrue("the fallback tried (first pass: $first)", first >= 1)
            assertTrue(
                "at most ${OldRootFallbackApi.maxFailedAttemptsPerPeerPerPass()} failed tries for one peer in one pass (got $first)",
                first <= OldRootFallbackApi.maxFailedAttemptsPerPeerPerPass().toLong(),
            )
        }

    @Test
    fun `7 - the same envelope re-mined 41 blocks after the answer does not open, 40 blocks after it does`() =
        runBlocking {
            // The answer KA mines at NEW + 20. A copy of the envelope (someone who reads it can re-send it) mined later has
            // its own as-of root: the current one, plus a window of 40 blocks in which the answer was not yet scanned.
            val honorPhone = initiator()
            val wire = honorPhone.contactSealer(honorPhone.root(setOf(KH), emptySet()), A).seal(TEXT)
            honorPhone.f.received("m-old", honorPhone.inboundMemo(A, wire), minedAt = NEW + 8)
            honorPhone.f.received("copy-40", honorPhone.inboundMemo(A, wire), minedAt = NEW + 20 + 40)
            honorPhone.f.received("copy-41", honorPhone.inboundMemo(A, wire), minedAt = NEW + 20 + 41)

            val rows = honorPhone.passes(3)

            assertEquals("the original opens", TEXT, honorPhone.textOf(rows, "m-old"))
            assertEquals(
                "a copy inside the window opens (the accepted net window: 40 blocks after an exchange)",
                TEXT,
                honorPhone.textOf(rows, "copy-40"),
            )
            val late = honorPhone.textOf(rows, "copy-41")
            assertNotEquals(
                "a copy mined 41 blocks after the answer must stay closed (it is not answered from the original's cache)",
                TEXT,
                late,
            )
            assertFalse(late.startsWith("E2E1:"))
        }

    @Test
    fun `14 - from NU7 on the window is 120 blocks - a copy 120 blocks after the answer opens, 121 does not`() =
        runBlocking {
            // Our transactions expire 120 blocks after their target from NU7 on (ChainTiming.NU7_EXPIRY_DELTA), so the sender
            // of a copy can have missed the answer for 120 blocks, not 40.
            val base = ChainTiming.MAINNET_NU7_HEIGHT + 1_000L
            val honorPhone = initiator(base = base)
            honorPhone.f.tip(base + 500L)
            val wire = honorPhone.contactSealer(honorPhone.root(setOf(KH), emptySet()), A).seal(TEXT)
            honorPhone.f.received("m-old", honorPhone.inboundMemo(A, wire), minedAt = base + 8)
            honorPhone.f.received("copy-120", honorPhone.inboundMemo(A, wire), minedAt = base + 20 + 120)
            honorPhone.f.received("copy-121", honorPhone.inboundMemo(A, wire), minedAt = base + 20 + 121)

            val rows = honorPhone.passes(3)

            assertEquals("the original opens", TEXT, honorPhone.textOf(rows, "m-old"))
            assertEquals("a copy 120 blocks after the answer is inside the NU7 window", TEXT, honorPhone.textOf(rows, "copy-120"))
            assertNotEquals("a copy 121 blocks after the answer is outside it", TEXT, honorPhone.textOf(rows, "copy-121"))
        }

    // ---- the current root is untouched --------------------------------------------------------------------------------

    @Test
    fun `8 - opening the old message leaves the current root's ratchet store byte-equal and its next message reads`() =
        runBlocking {
            // The run with the fallback row...
            val withFallback = initiator()
            val oldWire = withFallback.contactSealer(withFallback.root(setOf(KH), emptySet()), A).seal(TEXT)
            withFallback.f.received("m-old", withFallback.inboundMemo(A, oldWire), minedAt = NEW + 8)
            val rows = withFallback.passes(3)
            assertEquals("the old message reads (fixture)", TEXT, withFallback.textOf(rows, "m-old"))
            val after = withFallback.storeSnapshot(A)

            // ...against the same chat where that row is a stranger's (nothing can open it).
            freshProcess()
            val control = initiator()
            val stranger = E2EEncryption.generateKeyPair()
            val strangerRoot =
                E2ERatchet.deriveRatchetRoot(
                    E2EEncryption.deriveSharedSecret(stranger.privateKey, honorKeys.publicKey),
                    null,
                    E2ERatchet.canonicalTxidMaterial(emptySet(), null),
                    E2ERatchet.canonicalTxidMaterial(emptySet(), null),
                )
            control.f.received("m-old", control.inboundMemo(A, foreignSealer(strangerRoot, A).seal(TEXT)), minedAt = NEW + 8)
            control.passes(3)

            assertEquals(
                "the fallback wrote nothing the current root reads (state under the id, every floor)",
                control.storeSnapshot(A),
                after,
            )

            // The current root's own next message, counter 0 of its chain, still reads on the same chat.
            withFallback.f.received(
                "m-next",
                withFallback.inboundMemo(A, withFallback.contactSealer(withFallback.root(setOf(KH), setOf(KA)), A).seal("next")),
                minedAt = NEW + 30,
            )
            val later = withFallback.passes(2)
            assertEquals("next", withFallback.textOf(later, "m-next"))
            assertEquals("and the old one still reads", TEXT, withFallback.textOf(later, "m-old"))
        }

    @Test
    fun `9 - an own-direction old envelope does not move the current root's next send counter`() =
        runBlocking {
            // The Seeker's restored phone opens its own old message (counter 0 of ITS direction under the old root). The
            // current root's send floor must stay where it was, or the next send would skip ahead of the peer's window.
            val seekerPhone = responder()
            val oldSealer = seekerPhone.ownSealer(seekerPhone.root(setOf(KH), emptySet()), A)
            repeat(3) { oldSealer.seal("filler") }
            val wire = oldSealer.seal(TEXT)
            seekerPhone.f.received(KH, kexMemo(A, honor, honorKeys), minedAt = NEW + 5)
            seekerPhone.f.sent("m-old", seekerPhone.ownMemo(A, wire), to = honor, minedAt = NEW + 8)
            seekerPhone.f.sent(KA, ackMemo(A, SELF, seekerKeys), to = honor, minedAt = NEW + 20)

            val rows = seekerPhone.passes(3)

            assertEquals(TEXT, seekerPhone.textOf(rows, "m-old"))
            val myDirection = if (lower(seekerKeys, honorKeys)) 0x00.toByte() else 0x01.toByte()
            val currentRoot = seekerPhone.root(setOf(KH), setOf(KA))
            assertEquals(
                "the current root's send floor is untouched by the old envelope's counter 3",
                0L,
                seekerPhone.ratchetStore.loadCounterFloor(E2ERatchet.floorKeyFor(currentRoot), myDirection),
            )
            assertEquals(
                "and so is the mirror under the conversation id, which the current root reads back as its floor",
                0L,
                seekerPhone.ratchetStore.loadCounterFloor(A, myDirection),
            )
            assertNull("no ratchet state was saved under the conversation id", seekerPhone.ratchetStore.load(A))
        }

    // ---- display only -------------------------------------------------------------------------------------------------

    @Test
    fun `10 - a timer or mode control sealed under the old root is not applied and shows no bubble`() =
        runBlocking {
            val honorPhone = initiator()
            val sealer = honorPhone.contactSealer(honorPhone.root(setOf(KH), emptySet()), A)
            val timer = sealer.seal(ZMSGProtocol.createDisappearSetting(60L, 0L, seeker))
            val mode = sealer.seal(ZMSGProtocol.createModeChange(ConversationMode.OPEN, 1_700_000_000_000L, seeker))
            honorPhone.f.received("c-timer", honorPhone.inboundMemo(A, timer), minedAt = NEW + 8)
            honorPhone.f.received("c-mode", honorPhone.inboundMemo(A, mode), minedAt = NEW + 9)

            val rows = honorPhone.passes(3)

            val shown = rows.flatMap { it.messages }.map { it.text.take(20) }
            assertTrue("an opened control leaves no bubble: $shown", honorPhone.message(rows, "c-timer") == null)
            assertTrue("an opened control leaves no bubble", honorPhone.message(rows, "c-mode") == null)
            assertEquals(
                "no timer or mode control was applied from an old-root message: ${honorPhone.controls}",
                emptyList<String>(),
                honorPhone.controls.toList(),
            )
        }

    @Test
    fun `11 - a message opened by the fallback sets no legacy-key evidence, one opened by the current root does`() =
        runBlocking {
            // The contact's first message under ITS OWN id B (not ours, A): evidence arms the reply id, as the control shows.
            val control = initiator(extraFiled = listOf(B))
            val now = control.contactSealer(control.root(setOf(KH), setOf(KA)), B).seal(TEXT)
            control.f.received("init-now", ZMSGProtocol.createV4InitMessage(B, seeker, now), minedAt = NEW + 30)
            control.passes(3)
            assertTrue("fixture: a message opened by the current root arms the reply id: ${control.controls}", "arm" in control.controls)

            freshProcess()
            val phone = initiator(extraFiled = listOf(B))
            val old = phone.contactSealer(phone.root(setOf(KH), emptySet()), B).seal(TEXT)
            phone.f.received("init-old", ZMSGProtocol.createV4InitMessage(B, seeker, old), minedAt = NEW + 8)
            val rows = phone.passes(3)

            assertEquals("the old message reads", TEXT, phone.textOf(rows, "init-old"))
            assertFalse("a row the fallback opened is display only: no evidence about the key: ${phone.controls}", "arm" in phone.controls)
        }

    // ---- the stored text ----------------------------------------------------------------------------------------------

    @Test
    fun `12 - the stored text is bound to the row, a repeat pass reads it with no derivation`() =
        runBlocking {
            val honorPhone = initiator()
            val wire = honorPhone.contactSealer(honorPhone.root(setOf(KH), emptySet()), A).seal(TEXT)
            honorPhone.f.received("m-old", honorPhone.inboundMemo(A, wire), minedAt = NEW + 8)

            val rows = honorPhone.passes(2)
            assertEquals(TEXT, honorPhone.textOf(rows, "m-old"))
            val afterFirst = OldRootFallbackApi.attemptCount()

            assertFalse(
                "the envelope-bound key must not be written: a re-mined copy would be answered from it",
                honorPhone.textStore.containsKey(DecryptedTextCacheKey.of(A, wire)) ||
                    honorPhone.textStore.containsKey(DecryptedTextCacheKey.legacyOf(wire)),
            )
            assertEquals("one stored text, under a key that names the row", 1, honorPhone.textStore.values.count { it == TEXT })

            val again = honorPhone.passes(2)
            assertEquals(TEXT, honorPhone.textOf(again, "m-old"))
            assertEquals("the repeat passes read the stored text and derive nothing", afterFirst, OldRootFallbackApi.attemptCount())
        }

    @Test
    fun `13 - a timer expiry deletes the stored text of a row the fallback opened`() =
        runBlocking {
            val honorPhone = initiator()
            val wire = honorPhone.contactSealer(honorPhone.root(setOf(KH), emptySet()), A).seal(TEXT)
            honorPhone.f.received("m-old", honorPhone.inboundMemo(A, wire), minedAt = NEW + 8)
            val rows = honorPhone.passes(2)
            assertEquals(TEXT, honorPhone.textOf(rows, "m-old"))
            assertEquals("fixture: the opened text is stored", 1, honorPhone.textStore.values.count { it == TEXT })

            honorPhone.ttlSeconds = 60L // the row is years old: expired, tombstoned, its decrypted text deleted
            honorPhone.passes(2)

            val remaining = honorPhone.textStore.values.count { it == TEXT }
            assertEquals("the expiry sweep removed the stored plaintext of the expired row", 0, remaining)
        }

    // ---- scenario support ---------------------------------------------------------------------------------------------

    /**
     * The HONOR on 12 Nov: the initiator, id A, its KEX [KH] mined at NEW + 5, the Seeker's answer [KA] mined at NEW + 20, and
     * what it stored after reading both (KEX {KH}, KEXACK {KA}, both scalars): the current root. No text cache.
     */
    private suspend fun initiator(
        extraFiled: List<String> = emptyList(),
        base: Long = NEW,
    ): OldRootPhone {
        val phone = OldRootPhone(scheduler, seeker, honorKeys, seekerKeys, ownConvId = A, filedConvIds = extraFiled)
        phone.open()
        phone.stored(kex = setOf(KH), kexScalar = KH, ack = setOf(KA), ackScalar = KA)
        phone.f.ownKex(KH, to = seeker, convId = A, minedAt = base + 5)
        phone.f.received(KA, ackMemo(A, seeker, seekerKeys), minedAt = base + 20)
        return phone
    }

    /** The Seeker (responder): KH received, its own answer KA sent; it filed the HONOR's id A. */
    private suspend fun responder(): OldRootPhone {
        val phone = OldRootPhone(scheduler, honor, seekerKeys, honorKeys, ownConvId = null, filedConvIds = listOf(A))
        phone.open()
        phone.stored(kex = setOf(KH), kexScalar = KH, ack = setOf(KA), ackScalar = KA)
        return phone
    }

    /** [count] received rows in the contact's direction with random bodies, mined before the answer. */
    private suspend fun forgedRows(
        phone: OldRootPhone,
        count: Int,
    ) {
        val direction = if (lower(seekerKeys, honorKeys)) 0x00.toByte() else 0x01.toByte()
        val random = java.util.Random(1792L)
        repeat(count) { i ->
            val body = ByteArray(48).also { random.nextBytes(it) }
            val wire = CiphertextWireFormat.serialize(Ciphertext(direction, i.toLong(), body))
            phone.f.received("forged-$i", phone.inboundMemo(A, wire), minedAt = NEW + 6 + (i % 13))
        }
    }

    private fun lower(
        a: E2EKeyPair,
        b: E2EKeyPair,
    ): Boolean = a.publicKey < b.publicKey

    /**
     * A ratchet of a stranger that sends to the HONOR under [root] (derived from the stranger's key). It seals in the
     * Seeker's direction on purpose: an envelope in the HONOR's own direction is refused before any try is spent.
     */
    private fun foreignSealer(
        root: ByteArray,
        convId: String,
    ) = OldRootPhone.Sealer(E2ERatchet(root, convId, lower(seekerKeys, honorKeys), InMemoryRatchetStateStore()))

    private fun frozenRoot(
        kex: Set<String>,
        ack: Set<String>,
        phone: OldRootPhone,
    ): ByteArray =
        Frozen2124Sealer.root(
            E2EEncryption.deriveSharedSecret(phone.ours.privateKey, phone.theirs.publicKey),
            kex.map { phone.txid(it) },
            ack.map { phone.txid(it) },
        )

    private fun kexMemo(
        convId: String,
        sender: String,
        keys: E2EKeyPair,
    ): String = ZMSGProtocol.createV4KEXMessage(convId, sender, E2EEncryption.createKEXPayload(sender, keys.publicKey, keys.privateKey))

    private fun ackMemo(
        convId: String,
        sender: String,
        keys: E2EKeyPair,
    ): String =
        ZMSGProtocol.createV4KEXAckMessage(convId, sender, E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey))

    private fun freshProcess() {
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        OldRootFallbackApi.resetProcessState()
    }

    private companion object {
        const val A = "ZC1792PA"
        const val B = "ZC1792PB"
        const val KH = "zc1792-honor-kex"
        const val KA = "zc1792-seeker-ack"
        const val TEXT = "zc1792 sealed before the answer was paid"
    }
}
