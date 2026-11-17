package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.OldRootFallbackApi
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * ZC-1823, ZC-1824, ZC-1825 (QA2 pa2, the Fable review of P-A, ZC-1792 / owner W-142) and P-A's composition with K1's
 * F2/F3 rules, all through the REAL history pass over [OldRootPhone] (the real ratchet code path and a real
 * [InMemoryRatchetStateStore]).
 *
 * - ZC-1823: the row-bound text an earlier root opened must never stand in for the CURRENT root. On a restored phone a reorg
 *   can take the answer away, so the earlier root becomes current again; the phone's own old row must then go through the
 *   real ratchet, which raises the own-direction send floor above its counter. Read first, the stored text hid the row
 *   and the next send could reuse its (key, nonce).
 * - ZC-1824: block timestamps do not follow heights. A key exchange mined BELOW the row but in a block with a LATER
 *   timestamp comes after the row in the pass's time order; it is still in the row's candidate C1 and the row opens.
 * - ZC-1825: a failure inside the earlier-root reader (a corrupt preference) leaves the row a placeholder, as every other
 *   failure of tryDecryptMessage; a cancellation still cancels.
 * - K1 composition: a row the fallback opens shows its text, arms no banner and is not given K1's "sent before the key
 *   exchange finished" text; a row the fallback cannot open still gets that text and no banner (F2, F3).
 *
 * Heights use the fixture's NEW (below NU7: the expiry window is 40). Every address, key, id and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OldRootFallbackReviewFindingsTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val seeker = OldRootPhone.contact(seed = 18231)
    private val honor = OldRootPhone.contact(seed = 18232)
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

    // ---- ZC-1823 -----------------------------------------------------------------------------------------------------

    @Test
    fun `ZC-1823 - the earlier root current again after a reorg raises the own floor above the own fallback-opened row`() =
        runBlocking {
            // The Seeker's restored phone: KH received, its own message at counter 3 sealed before its answer KA, KA sent.
            val seekerPhone = responder()
            val oldRoot = seekerPhone.root(setOf(KH), emptySet())
            val oldSealer = seekerPhone.ownSealer(oldRoot, A)
            repeat(3) { oldSealer.seal("filler") }
            val wire = oldSealer.seal(TEXT)
            seekerPhone.f.received(KH, kexMemo(A, honor, honorKeys), minedAt = NEW + 5)
            seekerPhone.f.sent("m-old", seekerPhone.ownMemo(A, wire), to = honor, minedAt = NEW + 8)
            seekerPhone.f.sent(KA, ackMemo(A, SELF, seekerKeys), to = honor, minedAt = NEW + 20)
            val before = seekerPhone.passes(2)
            assertEquals("fixture: the earlier root opened the own row", TEXT, seekerPhone.textOf(before, "m-old"))

            // A reorg takes KA away and the app restarts: the stored sets lose it, so the current root IS the earlier one.
            removeTx(seekerPhone, KA)
            seekerPhone.stored(kex = setOf(KH), kexScalar = KH, ack = emptySet(), ackScalar = null)
            restartProcess(seekerPhone)
            val after = seekerPhone.passes(2)

            assertEquals("the own row still reads", TEXT, seekerPhone.textOf(after, "m-old"))
            val myDirection = if (lower(seekerKeys, honorKeys)) 0x00.toByte() else 0x01.toByte()
            assertEquals(
                "the current root read the own row's counter 3 itself, so its send floor is above it",
                4L,
                seekerPhone.ratchetStore.loadCounterFloor(E2ERatchet.floorKeyFor(oldRoot), myDirection),
            )
            val next = E2ERatchet(oldRoot, A, lower(seekerKeys, honorKeys), seekerPhone.ratchetStore).encrypt("next".toByteArray())
            assertTrue(
                "the next send under that root must not reuse counter 3's (key, nonce) (got counter ${next.counter})",
                next.counter >= 4L,
            )
        }

    // ---- ZC-1824 -----------------------------------------------------------------------------------------------------

    @Test
    fun `ZC-1824 - a key exchange mined below the row in a block with a later timestamp is still in C1 and the row opens`() =
        runBlocking {
            val honorPhone = initiator()
            val wire = honorPhone.contactSealer(honorPhone.root(setOf(KH), emptySet()), A).seal(TEXT)
            honorPhone.f.received("m-old", honorPhone.inboundMemo(A, wire), minedAt = NEW + 8)
            // KH is mined at NEW + 5, but its block's timestamp is later than the row's block (NEW + 8): the pass meets it after.
            val rowTime = checkNotNull(txOf(honorPhone, "m-old").timestamp)
            retime(honorPhone, KH, rowTime.plusSeconds(60))

            val rows = honorPhone.passes(2)

            assertEquals("the row reads with C1 = (KEX {KH}, KEXACK {})", TEXT, honorPhone.textOf(rows, "m-old"))
            assertFalse("and arms no banner", honorPhone.hasDecryptFailure())
        }

    // ---- ZC-1825 -----------------------------------------------------------------------------------------------------

    @Test
    fun `ZC-1825 - a failure inside the earlier-root reader leaves the row a placeholder and the pass completes`() =
        runBlocking {
            val honorPhone = initiator()
            val wire = honorPhone.contactSealer(honorPhone.root(setOf(KH), emptySet()), A).seal(TEXT)
            honorPhone.f.received("m-old", honorPhone.inboundMemo(A, wire), minedAt = NEW + 8)
            failInsideOldRootReader(honorPhone) { IllegalArgumentException("a corrupt Quantum Shield preference") }

            val rows =
                try {
                    honorPhone.passes(2)
                } catch (e: IllegalArgumentException) {
                    throw AssertionError("an exception inside openWithOldRoot escaped the history pass", e)
                }

            val shown = honorPhone.textOf(rows, "m-old")
            assertNotEquals("nothing opened it", TEXT, shown)
            assertFalse("never the raw envelope: $shown", shown.startsWith("E2E1:"))
            assertTrue("a placeholder text: $shown", shown.isNotBlank())
        }

    @Test
    fun `ZC-1825 - a cancellation inside the earlier-root reader still cancels the pass`() =
        runBlocking {
            val honorPhone = initiator()
            val wire = honorPhone.contactSealer(honorPhone.root(setOf(KH), emptySet()), A).seal(TEXT)
            honorPhone.f.received("m-old", honorPhone.inboundMemo(A, wire), minedAt = NEW + 8)
            failInsideOldRootReader(honorPhone) { CancellationException("the pass's scope was cancelled") }

            try {
                honorPhone.passes(1)
                fail("a cancellation inside openWithOldRoot was swallowed")
            } catch (e: CancellationException) {
                assertEquals("the pass's scope was cancelled", e.message)
            }
        }

    // ---- K1 composition (F2, F3) ---------------------------------------------------------------------------------------

    @Test
    fun `K1 - on the 12 Nov scenario the fallback opens the row, no banner arms, and K1's before-key-exchange text is not shown`() =
        runBlocking {
            val honorPhone = initiator()
            val wire = honorPhone.contactSealer(honorPhone.root(setOf(KH), emptySet()), A).seal(TEXT)
            honorPhone.f.received("m-old", honorPhone.inboundMemo(A, wire), minedAt = NEW + 8)

            val rows = honorPhone.passes(3)

            val shown = honorPhone.textOf(rows, "m-old")
            assertEquals("the 9 Nov message reads", TEXT, shown)
            assertNotEquals(note("chat_decrypt_placeholder_before_key_exchange"), shown)
            assertNotEquals(note("chat_decrypt_placeholder_key_missing"), shown)
            assertFalse("no 'keys out of sync' banner", honorPhone.hasDecryptFailure())
        }

    @Test
    fun `K1 - a row the fallback cannot open still gets K1's before-key-exchange text and no banner`() =
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

            val rows = honorPhone.passes(3)

            assertTrue("fixture: the fallback did try", OldRootFallbackApi.attemptCount() >= 1)
            assertEquals(
                "mined before the answer (the session height): K1 F2's honest text",
                note("chat_decrypt_placeholder_before_key_exchange"),
                honorPhone.textOf(rows, "m-foreign"),
            )
            assertFalse("K1 F3: a row before the latest handshake arms no banner", honorPhone.hasDecryptFailure())
        }

    // ---- scenario support ---------------------------------------------------------------------------------------------

    /** The HONOR (initiator), as OldRootFallbackOpensPreAnswerMessageTest: KH at NEW + 5, the Seeker's KA at NEW + 20. */
    private suspend fun initiator(): OldRootPhone {
        val phone = OldRootPhone(scheduler, seeker, honorKeys, seekerKeys, ownConvId = A, filedConvIds = emptyList())
        phone.open()
        phone.stored(kex = setOf(KH), kexScalar = KH, ack = setOf(KA), ackScalar = KA)
        phone.f.ownKex(KH, to = seeker, convId = A, minedAt = NEW + 5)
        phone.f.received(KA, ackMemo(A, seeker, seekerKeys), minedAt = NEW + 20)
        return phone
    }

    /** The Seeker (responder): KH received, its own answer KA sent; it filed the HONOR's id A. */
    private suspend fun responder(): OldRootPhone {
        val phone = OldRootPhone(scheduler, honor, seekerKeys, honorKeys, ownConvId = null, filedConvIds = listOf(A))
        phone.open()
        phone.stored(kex = setOf(KH), kexScalar = KH, ack = setOf(KA), ackScalar = KA)
        return phone
    }

    @Suppress("UNCHECKED_CAST")
    private fun txs(phone: OldRootPhone): MutableList<Transaction> =
        HandshakePassFixture::class.java
            .getDeclaredField("txs")
            .apply { isAccessible = true }
            .get(phone.f) as MutableList<Transaction>

    private fun txOf(
        phone: OldRootPhone,
        label: String,
    ): Transaction = txs(phone).single { it.id.txIdString() == phone.txid(label) }

    /** The chain lost the transaction [label] (a reorg). */
    private fun removeTx(
        phone: OldRootPhone,
        label: String,
    ) {
        assertTrue("fixture: $label was listed", txs(phone).removeAll { it.id.txIdString() == phone.txid(label) })
    }

    /** The block of [label] carries the timestamp [at] (its height stays). */
    private fun retime(
        phone: OldRootPhone,
        label: String,
        at: java.time.Instant,
    ) {
        val list = txs(phone)
        val index = list.indexOfFirst { it.id.txIdString() == phone.txid(label) }
        assertTrue("fixture: $label was listed", index >= 0)
        list[index] =
            when (val tx = list[index]) {
                is SendTransaction.Success -> tx.copy(timestamp = at)
                is ReceiveTransaction.Success -> tx.copy(timestamp = at)
                else -> throw AssertionError("fixture: $label is a ${tx.javaClass.simpleName}")
            }
    }

    /** The app restarts: the in-memory processors and plaintext cache and the fallback's process state are gone. */
    private fun restartProcess(phone: OldRootPhone) {
        PaymentHarness.setField(phone.f.vm, "messageProcessors", ConcurrentHashMap<String, Any>())
        PaymentHarness.setField(phone.f.vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        OldRootFallbackApi.resetProcessState()
    }

    /**
     * A preference read made INSIDE ChatViewModel.openWithOldRoot (the earlier-root reader, and only there: the current
     * root reads the same preference when it builds its processor) throws [thrown].
     */
    private fun failInsideOldRootReader(
        phone: OldRootPhone,
        thrown: () -> Exception,
    ) {
        val base = checkNotNull(phone.f.extra) { "fixture: OldRootPhone answers the stored keys" }
        phone.f.extra = { method, args ->
            val inReader = Thread.currentThread().stackTrace.any { it.methodName.startsWith("openWithOldRoot") }
            if (method.name == "getQuantumShieldPSK" && inReader) throw thrown()
            base(method, args)
        }
    }

    /** What the view model's test note copy gives for the string resource [name] (as UnreadableBeforeHandshakeFinishedTest). */
    private fun note(name: String): String {
        val id =
            try {
                R.string::class.java.getField(name).getInt(null)
            } catch (e: NoSuchFieldException) {
                throw AssertionError("R.string.$name does not exist", e)
            }
        return "note#$id()"
    }

    private fun lower(
        a: E2EKeyPair,
        b: E2EKeyPair,
    ): Boolean = a.publicKey < b.publicKey

    /** A stranger's ratchet sending to the HONOR in the Seeker's direction (an own-direction envelope is refused at once). */
    private fun foreignSealer(
        root: ByteArray,
        convId: String,
    ) = OldRootPhone.Sealer(E2ERatchet(root, convId, lower(seekerKeys, honorKeys), InMemoryRatchetStateStore()))

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
        const val A = "ZC1823PA"
        const val KH = "zc1823-honor-kex"
        const val KA = "zc1823-seeker-ack"
        const val TEXT = "zc1823 sealed before the answer was paid"
    }
}
