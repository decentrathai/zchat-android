package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.OLD
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-599 (FIRST-CONTACT-SPEC revision 4a, 5.4 items 2 and 3, 6.3 cases m, n, o, p) — the asked rule's restore, read-failure,
 * two-pass and network-table cases the earlier lanes left unwritten, as guards over the real history pass
 * ([HandshakePassFixture]): (m) a restore reads our first key exchange and its answer in one list, old or new, and takes
 * the key with its asked record; (n) a pass that could not read one of our own memos marks no unasked answer processed;
 * (o) an unasked answer is marked processed only by a second complete pass at a higher scanned height above its height
 * plus one expiry window; (p) on a network with no upgrade height leg 3 is off. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnswerAskedRuleRestoreAndExpiryTest {
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
        ChainTiming.current = ChainTiming.MAINNET
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private val a = SyntheticUnifiedAddress.mainnet(seed = 59931)

    @Test
    fun `(m) a restore reads our first key exchange and its answer in one list, old and new - the key is taken with its asked record`() =
        runBlocking {
            for (base in listOf(OLD, NEW)) {
                AutoSpendVm.resetProcessState()
                AutoSpendVm.setHistoryRead(true)
                val f = HandshakePassFixture(scheduler, a)
                val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
                f.open()
                f.ownKex("zc599-m-own-$base", a, own, minedAt = base + 5)
                val theirs = E2EEncryption.generateKeyPair()
                f.received("zc599-m-ack-$base", answerFrom(a, theirs, own), minedAt = base + 7)

                f.pass()

                val label = if (base == OLD) "old" else "new"
                assertEquals("$label: the restored answer's key was not taken", theirs.publicKey, f.prefs.e2ePeerKey[a])
                assertEquals("$label: no asked record names it", theirs.publicKey, f.prefs.askedKey[a])
                assertEquals("$label: nothing is paid", 0, f.seam.proposalRequests.get())
            }
        }

    @Test
    fun `(n) a pass that could not read one of our own memos marks no unasked answer processed`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a)
            f.open()
            f.sent("zc599-n-own-unreadable", "unreadable", SyntheticUnifiedAddress.mainnet(seed = 59932), minedAt = NEW + 2)
            f.unreadable += "zc599-n-own-unreadable"
            f.received("zc599-n-ack", answerFrom(a, E2EEncryption.generateKeyPair(), "ZNNN1111"), minedAt = NEW + 10)

            f.pass()
            f.tip(HandshakePassFixture.TIP + 100)
            f.pass()

            assertFalse(
                "an unasked answer was marked processed by a pass with an unread own memo",
                f.txid("zc599-n-ack") in f.prefs.processedKexTx,
            )
            assertNull(f.prefs.e2ePeerKey[a])
        }

    @Test
    fun `(o) an unasked answer is marked processed only by a second complete pass above its height plus one expiry window`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, a)
            f.open()
            val h = HandshakePassFixture.TIP - 10
            f.received("zc599-o-ack", answerFrom(a, E2EEncryption.generateKeyPair(), "ZOOO1111"), minedAt = h)
            val tx = f.txid("zc599-o-ack")

            f.pass()
            assertFalse("marked after one complete pass", tx in f.prefs.processedKexTx)

            f.tip(h + ChainTiming.PRE_NU7_EXPIRY_DELTA)
            f.pass()
            assertFalse("marked after a second pass inside the expiry window", tx in f.prefs.processedKexTx)

            f.tip(h + ChainTiming.PRE_NU7_EXPIRY_DELTA + 1)
            f.pass()
            assertTrue("not marked after a second pass above the expiry window", tx in f.prefs.processedKexTx)
            assertNull("the unasked answer stored a key", f.prefs.e2ePeerKey[a])
        }

    @Test
    fun `(p) on a network with no upgrade height an old answer under another id is not taken by leg 3`() =
        runBlocking {
            ChainTiming.current = ChainTiming.TESTNET
            val f = HandshakePassFixture(scheduler, a, firstContact = false)
            f.open()
            f.received("zc599-p-ack", answerFrom(a, E2EEncryption.generateKeyPair(), "ZPPP2222"), minedAt = OLD + 10)

            f.pass()

            assertNull("leg 3 took an answer on a network with no upgrade height", f.prefs.e2ePeerKey[a])
        }

    private fun answerFrom(
        sender: String,
        keys: E2EKeyPair,
        convId: String,
    ): String =
        ZMSGProtocol.createV4KEXAckMessage(
            convId,
            sender,
            E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey),
        )
}
