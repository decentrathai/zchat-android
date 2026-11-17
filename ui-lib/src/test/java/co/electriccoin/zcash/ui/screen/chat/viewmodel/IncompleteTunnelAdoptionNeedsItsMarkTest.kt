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
 * ZC-1516 (B1e cross-family re-review, review-B1e/gpt.json "High"; W-122 "adoption never spends", M1).
 *
 * At b7ac6b1ed (ZC-1507) handleModeControl refuses a contact's switch to Tunnel when the "moved to a paid mode by the
 * contact" mark does not read back only when the keys are already exchanged (`needsMark`). A Tunnel whose key exchange
 * is NOT complete keeps a best-effort mark: the switch is adopted without it, and the later ZBOOT/KEX path then reads
 * no mark and pays on chain under the standing consent with no price question. Wanted: every paid Tunnel adoption
 * (incomplete exchange OR our ZBOOT never sent) is refused when the mark does not read back — the chat keeps its mode
 * and nothing is proposed.
 *
 * Fixtures as AdoptedTunnelNeverPaysOurZbootTest (ZC-1507). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncompleteTunnelAdoptionNeedsItsMarkTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1516tunnelkex01"
        val PEER_RELAY_KEY: String = "c1516".padEnd(64, 'e')
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

    /** An Open chat whose key exchange is not complete ([ourKey]: our key was already made), the standing consent given. */
    private fun phone(ourKey: Boolean): RealModeStorePhone =
        RealModeStorePhone(
            PEER,
            ConversationMode.OPEN,
            consent = true,
            exchangeComplete = false,
            ourKey = ourKey,
            relayKey = PEER_RELAY_KEY,
        ).also { p ->
            p.real.recordPeerIronwoodCap(PEER, true, System.currentTimeMillis() / 1000 - 60)
            NostrChatBridge.unregisterPublisher()
            NostrChatBridge.registerPublisher { memo, to ->
                p.published += memo to to
                NostrChatBridge.PublishResult(0, null)
            }
        }

    private suspend fun switchToTunnel(vm: ChatViewModel) {
        invoke(vm, "handleModeControl", PEER, ZMSGProtocol.createModeChange(ConversationMode.TUNNEL, System.currentTimeMillis(), PEER))
        awaitIdle(vm)
    }

    @Test
    fun `fixture - with its mark on disk the contact's switch to Tunnel in a chat still exchanging keys is adopted`() =
        runBlocking {
            val phone = phone(ourKey = true)
            val vm = phone.viewModel()
            assertEquals("fixture: the key exchange is not complete", false, phone.store.isE2EKeyExchangeComplete(PEER))

            switchToTunnel(vm)

            assertEquals(ConversationMode.TUNNEL, phone.mode())
            assertEquals("fixture: the adopted Tunnel carries its mark", true, phone.store.getModeSwitchRecord(PEER)?.movedToPaidByPeer)
        }

    @Test
    fun `a contact's switch to Tunnel in a chat still exchanging keys is not adopted when its mark does not read back`() =
        runBlocking {
            val phone = phone(ourKey = true)
            val vm = phone.viewModel()
            dropMovedToPaidMark(vm)

            switchToTunnel(vm)

            assertEquals(
                "a Tunnel with an incomplete key exchange was adopted without its mark on disk (refuse every paid Tunnel, ZC-1516)",
                ConversationMode.OPEN,
                phone.mode(),
            )
            assertEquals(0, phone.seam.proposalRequests.get())
        }

    @Test
    fun `a contact's switch to Tunnel in a chat with no keys yet is not adopted when its mark does not read back`() =
        runBlocking {
            val phone = phone(ourKey = false)
            val vm = phone.viewModel()
            dropMovedToPaidMark(vm)

            switchToTunnel(vm)

            assertEquals(
                "a Tunnel with no key exchange yet was adopted without its mark on disk (refuse every paid Tunnel, ZC-1516)",
                ConversationMode.OPEN,
                phone.mode(),
            )
            assertEquals(0, phone.seam.proposalRequests.get())
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
