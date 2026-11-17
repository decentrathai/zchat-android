package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * T2-extra (a) (fix-all 2.13.0, train T2; carry-over from train T1b): rule (E), "never send to one of our own
 * addresses", covers only the owed first-message KEX (T1b-1, payOwedKex). The automatic ZBOOT setup message and the
 * other automatic setups have no such check.
 *
 * At 1af524ccf sendNostrBootHandshake pays an on-chain ZBOOT to whatever address it is given: a KEXACK attributed to one
 * of our own addresses pays one under the standing consent in a Tunnel chat, and in a Shielded chat it spends the
 * "ZBOOT quoted" allowance a first-message question left for that address (the HONOR debug app still holds such an
 * allowance, for its own address, from the T1 L11 quote). Reconnect re-sends our identity to our own address the same
 * way, and the Tunnel bootstrap pays a KEX to it: sendAutomaticHandshakeMemoOutcome, the one door of every automatic
 * setup, never asks whose address it pays.
 *
 * Must hold: no automatic setup (ZBOOT, KEX) is paid to an address ZchatPreferences.isSelfAddress recognises, no
 * question is asked for it, and a quoted-ZBOOT allowance for such an address is dropped. Control: the same KEXACK from
 * a contact still pays its ZBOOT in a Tunnel chat.
 *
 * Drives the real ChatViewModel through the real use case and repository (HandshakeHarness, AutoSpendPrefs). Every
 * address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoAutomaticSetupToOwnAddressTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val OWN = "usynthetict2extraownaddr0001"
        const val CONTACT = "usynthetict2extracontact001"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a KEXACK attributed to one of our own addresses pays no ZBOOT in a Tunnel chat`() =
        runBlocking {
            val chat = Chat(OWN, ConversationMode.TUNNEL, complete = false)
            val vm = chat.viewModel()

            handleKexMemo(vm, kexAckMemo(OWN, chat.prefs.convIdFor(OWN), E2EEncryption.generateKeyPair()), "synthetic-t2x-01")
            awaitIdle(vm)

            assertEquals("an automatic ZBOOT was paid to one of our own addresses", 0, chat.seam.proposalRequests.get())
            assertNoQuestion(vm)
        }

    @Test
    fun `a quoted ZBOOT to one of our own addresses is never paid and its allowance is dropped`() =
        runBlocking {
            val chat = Chat(OWN, ConversationMode.VAULT, complete = false)
            chat.zbootQuoted += OWN // what the T1 L11 quote left on the HONOR debug app
            val vm = chat.viewModel()

            handleKexMemo(vm, kexAckMemo(OWN, chat.prefs.convIdFor(OWN), E2EEncryption.generateKeyPair()), "synthetic-t2x-02")
            awaitIdle(vm)

            assertEquals(
                "a stored ZBOOT allowance paid a ZBOOT to one of our own addresses",
                0,
                chat.seam.proposalRequests.get(),
            )
            assertFalse("the allowance for our own address must be dropped", OWN in chat.zbootQuoted)
        }

    @Test
    fun `Reconnect to one of our own addresses pays no ZBOOT`() =
        runBlocking {
            val chat = Chat(OWN, ConversationMode.TUNNEL, complete = true)
            val vm = chat.viewModel()

            vm.reSendOurIdentity(OWN)
            awaitIdle(vm)

            assertEquals("Reconnect paid a ZBOOT to one of our own addresses", 0, chat.seam.proposalRequests.get())
            assertNoQuestion(vm)
        }

    @Test
    fun `a Tunnel bootstrap pays no KEX to one of our own addresses`() =
        runBlocking {
            val chat = Chat(OWN, ConversationMode.TUNNEL, complete = false)
            val vm = chat.viewModel()

            vm.ensureNostrBootstrapSent(OWN, force = true)
            awaitIdle(vm)

            assertEquals("the Tunnel bootstrap paid a KEX to one of our own addresses", 0, chat.seam.proposalRequests.get())
            assertNoQuestion(vm)
        }

    /** Control, green before and after: a contact's KEXACK in a Tunnel chat pays our ZBOOT under the standing consent. */
    @Test
    fun `control - the same KEXACK from a contact pays its ZBOOT in a Tunnel chat`() =
        runBlocking {
            val chat = Chat(CONTACT, ConversationMode.TUNNEL, complete = false)
            val vm = chat.viewModel()

            handleKexMemo(
                vm,
                kexAckMemo(CONTACT, chat.prefs.convIdFor(CONTACT), E2EEncryption.generateKeyPair()),
                "synthetic-t2x-03"
            )
            awaitIdle(vm)

            assertEquals(1, chat.seam.submits.get())
        }

    // --------------------------------------------------------------- harness

    private fun assertNoQuestion(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertFalse(
            "a question was raised for a setup to our own address: $state",
            state.javaClass.simpleName.startsWith("Needs"),
        )
    }

    /** A signed first-contact KEXACK from [peer] (its address fills the memo; no relay fields) under [convId]. */
    private fun kexAckMemo(
        peer: String,
        convId: String,
        keys: E2EKeyPair,
    ): String =
        ZMSGProtocol.createV4KEXAckMessage(
            convId,
            peer,
            E2EEncryption.createKEXAckPayload(peer, keys.publicKey, keys.privateKey),
        )

    /**
     * [peer] in a chat of [mode] with our E2E key stored ([complete]: and theirs), the standing handshake consent given.
     * Our own addresses are only [OWN]; the "ZBOOT quoted" allowance is kept in [zbootQuoted].
     */
    private class Chat(
        val peer: String,
        mode: ConversationMode,
        complete: Boolean,
    ) {
        val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = complete, mode = mode) }
        val seam = HandshakeSeam(peer)
        val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private val store: ZchatPreferences =
            intercept(
                prefs.proxy,
                mapOf(
                    "isSelfAddress" to { a -> a!![0] == OWN },
                    "isZbootQuoted" to { a -> a!![0] in zbootQuoted },
                    "setZbootQuoted" to { a ->
                        if (a!![1] as Boolean) zbootQuoted += a[0] as String else zbootQuoted -= a[0] as String
                        true
                    },
                ),
            )

        suspend fun viewModel(): ChatViewModel = chatViewModelWith(store, seam).also(AutoSpendVm::wireBootstrapFields)
    }
}

/** [delegate] with the calls named in [handlers] answered by them instead. */
private fun intercept(
    delegate: ZchatPreferences,
    handlers: Map<String, (Array<Any?>?) -> Any?>,
): ZchatPreferences =
    Proxy.newProxyInstance(
        ZchatPreferences::class.java.classLoader,
        arrayOf(ZchatPreferences::class.java),
    ) { _, method, args ->
        val handler = handlers[method.name]
        if (handler != null) {
            handler(args)
        } else {
            try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    } as ZchatPreferences
