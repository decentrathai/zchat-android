package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * ZC-599 (FIRST-CONTACT-SPEC revision 4a, 2.1 routes T and S, 6.3 UnaskedAnswerNeverLeadsToAPaymentTest T, S, S2 and
 * controls 2 and 3) — an answer nobody asked for never leads to a payment, end to end through the real history pass, the
 * ZBOOT route and the chat-open effect (askAgainForHandshakeConsent, retryKexAckIfResponder, retryOwedKeyExchange,
 * ensureNostrBootstrapSent when the chat is not Shielded), with the standing consent given. Guards at the tip (U1 and U3
 * are in AnswerNobodyAskedForChangesNothingTest). S and S2 run without the user's own first message of the design (the
 * first-message fixture does not run over the history pass); the request and Accept are what they pin. Every address,
 * key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnaskedAnswerNeverLeadsToAPaymentTest {
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

    private val a = SyntheticUnifiedAddress.mainnet(seed = 59941)

    @Test
    fun `(T) route T - an unasked answer, its key's ZBOOT on-chain and over the relays, then the open - nothing stored, bound or paid`() =
        runBlocking {
            val (f, maps) = pass(consent = true)
            val theirs = E2EEncryption.generateKeyPair()
            f.received("zc599-t-ack", answerFrom(a, theirs, "ZTTT1111"), minedAt = NEW + 10)
            f.pass()
            val raw = v3Boot(theirs, relayKey(71), convId = "ZTTT1111")
            attribute(f, raw)
            route(f, raw)

            open(f, maps)

            assertNull("an unasked answer stored a contact key", f.prefs.e2ePeerKey[a])
            assertTrue("a relay key was bound: ${maps.binds.size}", maps.binds.isEmpty())
            assertFalse("the chat moved to Tunnel", maps.modes[a] == ConversationMode.TUNNEL)
            assertNull("a key pair of ours was made", f.prefs.ourKeyStore[a])
            assertEquals("route T led to a payment", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `control 3 - without the standing consent route T raises no Allow question`() =
        runBlocking {
            val (f, maps) = pass(consent = false)
            val theirs = E2EEncryption.generateKeyPair()
            f.received("zc599-c3-ack", answerFrom(a, theirs, "ZTTT2222"), minedAt = NEW + 10)
            f.pass()
            attribute(f, v3Boot(theirs, relayKey(72), convId = "ZTTT2222"))

            open(f, maps)

            val state = HandshakeHarness.sendMessageState(f.vm).javaClass.simpleName
            assertFalse("an Allow question was raised for route T: $state", state.contains("Consent", ignoreCase = true))
            assertEquals(0, f.seam.proposalRequests.get())
        }

    @Test
    fun `(S) route S - an unasked answer, then a key exchange with the same key, then the open - the request waits and nothing is paid`() =
        runBlocking {
            val (f, maps) = pass(consent = true)
            val theirs = E2EEncryption.generateKeyPair()
            f.received("zc599-s-ack", answerFrom(a, theirs, "ZSSS1111"), minedAt = NEW + 10)
            f.pass()
            f.received("zc599-s-kex", kexFrom(a, theirs, "ZSSS1111"), minedAt = NEW + 12)
            f.pass()

            open(f, maps)

            assertEquals("the key exchange after an unasked answer does not wait for Accept", theirs.publicKey, f.prefs.awaitingAccept[a])
            assertEquals("an answer was proposed before Accept", 0, f.seam.proposalRequests.get())
            f.vm.acceptInboundKexRequest(a)
            f.idle()
            assertEquals("Accept pays exactly one answer", 1, f.seam.proposalRequests.get())
        }

    @Test
    fun `(S2) the same in one pass, then the open - the request waits and nothing is paid`() =
        runBlocking {
            val (f, maps) = pass(consent = true)
            val theirs = E2EEncryption.generateKeyPair()
            f.received("zc599-s2-ack", answerFrom(a, theirs, "ZSSS2222"), minedAt = NEW + 10)
            f.received("zc599-s2-kex", kexFrom(a, theirs, "ZSSS2222"), minedAt = NEW + 10)
            f.pass()

            open(f, maps)

            assertEquals(theirs.publicKey, f.prefs.awaitingAccept[a])
            assertEquals("an answer was proposed before Accept", 0, f.seam.proposalRequests.get())
            assertEquals("a budget unit was used before Accept", 0, f.prefs.budgetConsumed.get())
        }

    @Test
    fun `control 2 - a real stranger's key exchange proposes nothing before Accept`() =
        runBlocking {
            val (f, maps) = pass(consent = true)
            f.received("zc599-c2-kex", kexFrom(a, E2EEncryption.generateKeyPair(), "ZCCC2222"), minedAt = NEW + 10)
            f.pass()

            open(f, maps)

            assertEquals(0, f.seam.proposalRequests.get())
        }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private suspend fun pass(consent: Boolean): Pair<HandshakePassFixture, ClaimMarkMaps> {
        val f = HandshakePassFixture(scheduler, a, consent = consent)
        val maps = ClaimMarkMaps(f.prefs.e2ePeerKey, f.prefs.askedKey)
        f.extra = { method, args -> maps.handle(method, args) }
        f.open()
        return f to maps
    }

    /** The chat-open effect, in its order (AC AndroidChatDetail): the relay bootstrap only outside a Shielded chat. */
    private suspend fun open(
        f: HandshakePassFixture,
        maps: ClaimMarkMaps,
    ) {
        f.vm.askAgainForHandshakeConsent()
        f.vm.retryKexAckIfResponder(a)
        f.vm.retryOwedKeyExchange(a)
        f.vm.repairUnconfirmedRelayIdentity(a)
        if ((maps.modes[a] ?: ConversationMode.VAULT) != ConversationMode.VAULT) f.vm.ensureNostrBootstrapSent(a)
        f.idle()
    }

    private fun attribute(
        f: HandshakePassFixture,
        raw: String,
    ) {
        val method = ChatViewModel::class.java.getDeclaredMethod("attributeBoot", String::class.java).apply { isAccessible = true }
        try {
            method.invoke(f.vm, raw)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun route(
        f: HandshakePassFixture,
        raw: String,
    ) {
        val method =
            ChatViewModel::class.java.declaredMethods
                .first { it.name == "routeIncomingBoot" && it.parameterCount == 3 && it.parameterTypes[2] == java.lang.Boolean.TYPE }
                .apply { isAccessible = true }
        try {
            method.invoke(f.vm, a, raw, false)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
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

    private fun kexFrom(
        sender: String,
        keys: E2EKeyPair,
        convId: String,
    ): String = ZMSGProtocol.createV4KEXMessage(convId, sender, E2EEncryption.createKEXPayload(sender, keys.publicKey, keys.privateKey))
}
