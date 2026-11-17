package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.invoke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-1507 (B1a cross-family review grok-2; VERIFY_B1a.md item 4; FIX_PLAN REVISION 7; W-122 "adoption never spends", M1).
 *
 * An Open chat whose key exchange finished earlier but whose own ZBOOT was never marked sent; the contact switches it to
 * Tunnel. At e626e879d ModeAdoption.movesToPaid(TUNNEL, exchangeComplete = true) is false, so no "moved by the contact"
 * mark is set; the next chat open runs ensureNostrBootstrapSent, takes the `switch == null` leg and sends our ZBOOT under
 * the standing consent: relays first, and with no relay acknowledging it, on-chain (one paid ZBOOT, no price shown).
 * The per-block retryPendingTunnelZboots repeats it. Wanted: movesToPaid(TUNNEL) is true when the exchange is incomplete
 * OR our ZBOOT was never marked sent, so the adopted Tunnel pays nothing by itself; and the Tunnel switch is not adopted
 * when that mark does not read back (as for Shielded).
 *
 * Zero-spend guards plus a paying control (the same chat set to Tunnel by the user pays its ZBOOT on a chat open).
 * Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdoptedTunnelNeverPaysOurZbootTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1507tunnelzboot01"
        val PEER_RELAY_KEY: String = "c1507".padEnd(64, 'e')
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

    /** Keys exchanged, our ZBOOT never marked sent, the standing consent given; the relays take nothing. */
    private fun phone(mode: ConversationMode): RealModeStorePhone =
        RealModeStorePhone(PEER, mode, consent = true, exchangeComplete = true, relayKey = PEER_RELAY_KEY).also { p ->
            p.real.recordPeerIronwoodCap(PEER, true, System.currentTimeMillis() / 1000 - 60)
            NostrChatBridge.unregisterPublisher()
            NostrChatBridge.registerPublisher { memo, to ->
                p.published += memo to to
                NostrChatBridge.PublishResult(0, null)
            }
        }

    /** What AndroidChat's chat-open effect calls for this chat (as PeerModeSwitchIsAdoptedOnBothPhonesTest.openChat). */
    private fun openChat(
        vm: ChatViewModel,
        phone: RealModeStorePhone,
    ) {
        vm.askAgainForHandshakeConsent()
        vm.retryKexAckIfResponder(PEER)
        vm.retryOwedKeyExchange(PEER)
        vm.repairUnconfirmedRelayIdentity(PEER)
        if (phone.mode() != ConversationMode.VAULT) vm.ensureNostrBootstrapSent(PEER)
    }

    /** The per-block retries (a new block arrives while the chat is open). */
    private fun tick(vm: ChatViewModel) {
        for (name in listOf("retryPendingTunnelZboots", "retryOwedKeyExchanges")) {
            val method =
                ChatViewModel::class.java.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 1 } ?: continue
            method.isAccessible = true
            runCatching { method.invoke(vm, HandshakeHarness.START_HEIGHT + 1) }
        }
    }

    @Test
    fun `a contact's switch to Tunnel in a chat whose ZBOOT was never sent pays nothing on a chat open or a block`() =
        runBlocking {
            val phone = phone(ConversationMode.OPEN)
            val vm = phone.viewModel()
            assertEquals("fixture: our ZBOOT was never marked sent", null, phone.store.getSentNostrBootPubkey(PEER))

            invoke(vm, "handleModeControl", PEER, ZMSGProtocol.createModeChange(ConversationMode.TUNNEL, System.currentTimeMillis(), PEER))
            awaitIdle(vm)
            assertEquals("fixture: the contact's switch to Tunnel is adopted", ConversationMode.TUNNEL, phone.mode())

            openChat(vm, phone)
            awaitIdle(vm)
            tick(vm)
            awaitIdle(vm)
            PaymentHarness.eventually(500L) { phone.seam.proposalRequests.get() > 0 }

            assertEquals(
                "the adopted Tunnel paid for our ZBOOT by itself (W-122: adoption never spends; M1)",
                0,
                phone.seam.proposalRequests.get(),
            )
            assertEquals(0, phone.seam.submits.get())
        }

    @Test
    fun `a contact's switch to Tunnel is not adopted when the moved-by-the-contact mark does not read back`() =
        runBlocking {
            val phone = phone(ConversationMode.OPEN)
            val vm = phone.viewModel()
            dropMovedToPaidMark(vm)

            invoke(vm, "handleModeControl", PEER, ZMSGProtocol.createModeChange(ConversationMode.TUNNEL, System.currentTimeMillis(), PEER))
            awaitIdle(vm)

            assertEquals(
                "a Tunnel that can pay for our ZBOOT was adopted without its mark on disk (refuse it, as for Shielded)",
                ConversationMode.OPEN,
                phone.mode(),
            )
            assertEquals(0, phone.seam.proposalRequests.get())
        }

    @Test
    fun `control - the same chat put in Tunnel by the user pays its ZBOOT on a chat open under the standing consent`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL)
            val vm = phone.viewModel()

            openChat(vm, phone)
            awaitIdle(vm)
            PaymentHarness.eventually { phone.seam.proposalRequests.get() > 0 }

            assertEquals("fixture: the ZBOOT spend path is reachable in this fixture", 1, phone.seam.proposalRequests.get())
        }

    /** The store keeps every call except a write that sets the M1 mark: that one does not land (a failed commit). */
    private fun dropMovedToPaidMark(vm: ChatViewModel) {
        val field = ChatViewModel::class.java.getDeclaredField("zchatPreferences").apply { isAccessible = true }
        val under = field.get(vm) as ZchatPreferences
        val wrapped =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
                if (method.name == "setModeMovedToPaidByPeer" && args?.get(1) == true) {
                    Unit
                } else {
                    try {
                        method.invoke(under, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            } as ZchatPreferences
        PaymentHarness.setField(vm, "zchatPreferences", wrapped)
    }
}
