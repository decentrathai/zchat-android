package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.CURRENT_FEE
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.contact
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
 * ZC-1082 (FIRST-CONTACT-SPEC N-13, 5.4 item 2 leg 2, 5.5 item 2a; plan cases e and f) — leg 2 compares by receivers and
 * fails towards the mark.
 *
 * A restored wallet that does not know the contact yet names its own key exchange by the contact's receiver form; FC-ID
 * then writes no id for it (the batch's third pair: nothing vouches there), and the contact's answer under our own id
 * claims the contact's full address. Leg 2 asks whether an own mined key exchange went to that address, and compares
 * texts, so the answer is not asked for: a new one is dropped (the restored chat gets no key at all), and an old one
 * (at or below the upgrade height) is taken by leg 3 WITHOUT the claim mark.
 *
 * Wanted: leg 2 holds when the key exchange's destination is a receiver form whose receivers sit within the address the
 * answer is filed under, still only while that address holds no key or exactly the answer's; the key is then stored at
 * the contact's address WITH its claim mark (new and old alike, leg 2 before leg 3), the id follows the contact, and
 * nothing is paid. Only an answer under that key exchange's own id is compared by receivers (only its recipient could read
 * the id): under another id the text alone counts (flow 14, a cost), so an address built around the contact's receiver
 * claims nothing. Guards: a key exchange to another contact's receiver, an answer under another id, or an address that
 * already holds another key, is not leg 2. Every address, key and id is synthetic; failure messages name addresses by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegTwoComparesReceiversTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val peer = contact(seed = 10826)
    private val peerForm = receiverForm(peer)
    private val stranger = contact(seed = 10827)
    private val feeForm = receiverForm(CURRENT_FEE)

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
    fun `(e, f) the answer to our restored key exchange is filed under the contact with its claim mark, new and old`() =
        runBlocking {
            for (base in listOf(NEW, OLD)) {
                AutoSpendVm.resetProcessState()
                AutoSpendVm.setHistoryRead(true)
                val label = if (base == NEW) "new" else "old"
                val f = HandshakePassFixture(scheduler, peer)
                f.open()
                val own = "ZLGA1082"
                f.ownKexRestored(
                    "zc1082-f-own-$label",
                    keyFor = peer,
                    outputs = listOf(feeForm, peerForm),
                    convId = own,
                    minedAt = base + 5,
                )
                val theirs = E2EEncryption.generateKeyPair()
                f.received("zc1082-f-ack-$label", answerFrom(peer, theirs, own), minedAt = base + 7)

                f.pass()

                assertEquals("$label: the answer's key at the contact", theirs.publicKey, f.prefs.e2ePeerKey[peer])
                assertNotNull("$label: the key taken by leg 2 has no claim mark", f.prefs.claims[peer])
                assertEquals("$label: our id follows", "contact", name(f.ids.conv(own)))
                assertEquals("$label: own-id records", emptySet<String>(), f.ids.ownKeys())
                assertNull("$label: a key under the contact's form", f.prefs.e2ePeerKey[peerForm])
                assertEquals("$label: nothing is paid", 0, f.seam.proposalRequests.get())
            }
        }

    @Test
    fun `a key exchange to another contact's receiver is not leg 2`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            f.open()
            val own = "ZLGB1082"
            f.ownKexRestored(
                "zc1082-f-other-own",
                keyFor = stranger,
                outputs = listOf(feeForm, receiverForm(stranger)),
                convId = own,
                minedAt = NEW + 5,
            )
            f.received("zc1082-f-other-ack", answerFrom(peer, E2EEncryption.generateKeyPair(), own), minedAt = NEW + 7)

            f.pass()

            assertNull("an answer whose key exchange went to another contact was taken", f.prefs.e2ePeerKey[peer])
        }

    @Test
    fun `an answer under another id is not leg 2 by receivers - flow 14, and an address built around the receiver`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            f.open()
            f.ownKexRestored("zc1082-f-14-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = "ZLGD1082", minedAt = NEW + 5)
            // The contact's answer under its own id (flow 14: a cost, the chat needs a new exchange).
            f.received("zc1082-f-14-ack", answerFrom(peer, E2EEncryption.generateKeyPair(), "ZTHR1082"), minedAt = NEW + 7)
            // A stranger's answer under its own id, claiming an address made of its own receiver and the contact's.
            val forged = RestoredSendScan.mix(stranger, 2, peer, 3)
            f.received("zc1082-f-forged-ack", answerFrom(forged, E2EEncryption.generateKeyPair(), "ZATK1082"), minedAt = NEW + 8)

            f.pass()

            assertNull("the contact's answer under another id was taken", f.prefs.e2ePeerKey[peer])
            assertNull("an answer claiming an address built around the receiver was taken", f.prefs.e2ePeerKey[forged])
        }

    @Test
    fun `an address that holds another key is not leg 2`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer, firstContact = false)
            val held = E2EEncryption.generateKeyPair().publicKey
            f.prefs.e2ePeerKey[peer] = held
            f.open()
            val own = "ZLGC1082"
            f.ownKexRestored("zc1082-f-held-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 5)
            f.received("zc1082-f-held-ack", answerFrom(peer, E2EEncryption.generateKeyPair(), own), minedAt = NEW + 7)

            f.pass()

            assertEquals("the held key was replaced", held, f.prefs.e2ePeerKey[peer])
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

    private fun name(address: String?): String =
        when (address) {
            null -> "none"
            peer -> "contact"
            peerForm -> "contact form"
            feeForm -> "fee form"
            stranger -> "stranger"
            else -> "other (${address.length} chars)"
        }
}
