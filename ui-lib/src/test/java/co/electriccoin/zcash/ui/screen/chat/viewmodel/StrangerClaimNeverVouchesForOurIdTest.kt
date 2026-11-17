package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.CURRENT_FEE
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.contact
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.mix
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.receiverForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ZC-1082 review finding 1 (P1) — an address a stranger claimed never vouches for our own id.
 *
 * At a4b7b006a the own-send resolver counts every chat's address as "known" and trusts the one known address that holds a
 * restored send's receiver, and an exact match returns trusted before the holders are counted. A stranger S's first
 * message maps its claimed address to a chat. So after a restore, while the contact C is not known yet:
 * (a) S claimed a Unified Address made of C's Orchard receiver and S's own: it is the only holder, our send is trusted,
 * FC-ID ties our own key exchange's id to S's address, and C's answer under that id passes leg 1 and stores C's key at S's
 * address WITHOUT the claim mark; (b) S claimed C's single-receiver form itself: the exact match returns trusted even when
 * C's full address is known too (two holders), with the same outcome.
 *
 * The same through the stranger's key exchange (recheck of finding 1): its key is stored with no claim mark (a first
 * contact waits for Accept instead), so "a key stored without a mark" does not show that this phone vouches for it.
 *
 * Wanted: an address known only from a stranger's claim may decide where a send is filed but never vouches; the exact
 * text counts as one holder. Our id gets no own-id record, nothing is stored at S's addresses, and C's answer is filed
 * under C, its key taken by leg 2 WITH the claim mark. Nothing is paid. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrangerClaimNeverVouchesForOurIdTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val peer = contact(seed = 10828)
    private val peerForm = receiverForm(peer)
    private val strangerOwn = contact(seed = 10829)
    private val feeForm = receiverForm(CURRENT_FEE)

    /** A stranger's claimed address: its own Sapling receiver and the contact's Orchard receiver. */
    private val built = mix(strangerOwn, 2, peer, 3)

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
    fun `(a) a stranger's address built around the contact's receiver, the only holder, vouches for nothing`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            // The stranger's first message mapped its claimed address to a chat (under the stranger's own id).
            f.ids.store.setConversationMapping("ZSTA1082", built)
            f.open()
            val (own, theirs) = restoredExchange(f, "a")

            f.pass()

            assertEquals("own-id records were written", emptySet<String>(), f.ids.ownKeys())
            assertEquals("our id is mapped to", "contact", name(f.ids.conv(own)))
            assertNull("a key is stored at the stranger's address", f.prefs.e2ePeerKey[built])
            assertEquals("the contact's answer's key at the contact", theirs.publicKey, f.prefs.e2ePeerKey[peer])
            assertNotNull("the contact's key has no claim mark", f.prefs.claims[peer])
            assertEquals("nothing is paid", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `(b) a stranger's claim of the contact's receiver form vouches for nothing, the contact's address known too`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            f.ids.store.setConversationMapping("ZSTB1082", peerForm)
            f.ids.store.setConversationMapping("ZCHT1082", peer)
            f.open()
            val (own, theirs) = restoredExchange(f, "b")

            f.pass()

            assertEquals("own-id records were written", emptySet<String>(), f.ids.ownKeys())
            assertEquals("our id is mapped to", "contact", name(f.ids.conv(own)))
            assertNull("a key is stored at the claimed receiver form", f.prefs.e2ePeerKey[peerForm])
            assertEquals("the contact's answer's key at the contact", theirs.publicKey, f.prefs.e2ePeerKey[peer])
            assertNotNull("the contact's key has no claim mark", f.prefs.claims[peer])
            assertEquals("nothing is paid", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `(a) a stranger's key exchange from an address built around the contact's receiver vouches for nothing`() =
        runBlocking { strangerKeyExchangeVouchesForNothing(claimed = built, label = "C") }

    @Test
    fun `(b) a stranger's key exchange claiming the contact's receiver form vouches for nothing`() =
        runBlocking { strangerKeyExchangeVouchesForNothing(claimed = peerForm, label = "D") }

    /**
     * Recheck of finding 1: the stranger comes in through its own key exchange (stored with no claim mark: it waits for
     * Accept instead), before our restored key exchange to the contact's receiver; the contact is not known yet. Three
     * complete passes (the stranger's key is taken at a stable one), then the contact's answer under our id arrives.
     */
    private suspend fun strangerKeyExchangeVouchesForNothing(
        claimed: String,
        label: String,
    ) {
        val f = HandshakePassFixture(scheduler, peer)
        f.open()
        val strangerKeys = E2EEncryption.generateKeyPair()
        val strangerOffer =
            ZMSGProtocol.createV4KEXMessage(
                "ZST${label}1082",
                claimed,
                E2EEncryption.createKEXPayload(claimed, strangerKeys.publicKey, strangerKeys.privateKey),
            )
        f.received("zc1082-r2-stranger-$label", strangerOffer, minedAt = NEW + 1)
        val own = "ZOW${label}1082"
        f.ownKexRestored("zc1082-r2-own-$label", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 5)

        f.pass(times = 3)

        assertEquals("$label: the stranger's key was taken (the case's premise)", strangerKeys.publicKey, f.prefs.e2ePeerKey[claimed])
        assertEquals("$label: own-id records were written", emptySet<String>(), f.ids.ownKeys())

        val theirs = E2EEncryption.generateKeyPair()
        val answer =
            ZMSGProtocol.createV4KEXAckMessage(own, peer, E2EEncryption.createKEXAckPayload(peer, theirs.publicKey, theirs.privateKey))
        f.received("zc1082-r2-ack-$label", answer, minedAt = NEW + 7)

        f.pass()

        assertEquals("$label: own-id records were written", emptySet<String>(), f.ids.ownKeys())
        assertEquals("$label: our id is mapped to", "contact", name(f.ids.conv(own)))
        assertEquals("$label: the stranger's address holds another key", strangerKeys.publicKey, f.prefs.e2ePeerKey[claimed])
        assertEquals("$label: the contact's answer's key at the contact", theirs.publicKey, f.prefs.e2ePeerKey[peer])
        assertNotNull("$label: the contact's key has no claim mark", f.prefs.claims[peer])
    }

    /** Our restored key exchange to the contact's receiver under our own id, and the contact's answer under that id. */
    private fun restoredExchange(
        f: HandshakePassFixture,
        label: String,
    ): Pair<String, E2EKeyPair> {
        val own = "ZOW${label.uppercase()}1082"
        f.ownKexRestored(
            "zc1082-r1-own-$label",
            keyFor = peer,
            outputs = listOf(feeForm, peerForm),
            convId = own,
            minedAt = NEW + 5,
        )
        val theirs = E2EEncryption.generateKeyPair()
        val answer =
            ZMSGProtocol.createV4KEXAckMessage(own, peer, E2EEncryption.createKEXAckPayload(peer, theirs.publicKey, theirs.privateKey))
        f.received("zc1082-r1-ack-$label", answer, minedAt = NEW + 7)
        return own to theirs
    }

    private fun name(address: String?): String =
        when (address) {
            null -> "none"
            peer -> "contact"
            peerForm -> "contact form"
            built -> "stranger's built address"
            else -> "other (${address.length} chars)"
        }
}
