package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1474 logic (OT213n B1b; FIX_PLAN REVISION 1; L5 root cause; owner acknowledgement M5 pending): the reason an
 * attachment is refused comes from delivery evidence, not from "our key pair exists".
 *
 * At 669cf19e1 ChatViewModel.attachmentBlockReason answers KEY_EXCHANGE_UNANSWERED ("their answer has not arrived")
 * whenever our key pair for the contact exists and theirs does not. Our key pair is made before anything is paid (Tunnel
 * setup, sendKexOnce, payOwedKex), so a key exchange that is still owed and was never sent reads as "sent and waiting".
 * And a contact whose key exchange waits behind Accept on this phone gets the plain "needs a key exchange" text.
 *
 * Wanted (AttachmentBlockReason names from FIX_PLAN):
 * - our key pair, our key exchange still owed (isKexOwed) and none on chain (getKexTxIds empty): KEX_NOT_SENT;
 * - the contact's key exchange is held here behind Accept (getInboundKexAwaitingAccept non-null, no key pair of ours):
 *   PEER_MUST_ACCEPT (L5: "the peer's KEX sits behind Accept on our side"; the name is the plan's);
 * - control: our key exchange is on chain and no answer came: UNANSWERED (today's KEY_EXCHANGE_UNANSWERED counts);
 * - control: a shared key: null.
 * The gate creates and consumes nothing. The enum constants are compared by name, so this compiles before they exist.
 * Real attachmentBlockReason over [PaidChatFixture]'s store. All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentReasonFromDeliveryEvidenceTest {
    private val sendScopes = SendHarnessScopes()
    private var savedOwnHistoryRead = false

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        savedOwnHistoryRead = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(savedOwnHistoryRead)
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private enum class State { OWED_NOT_SENT, ON_CHAIN_UNANSWERED, HELD_FOR_OUR_ACCEPT, SHARED_KEY }

    private suspend fun reason(
        state: State,
        mode: ConversationMode,
    ): String? {
        val peer = SyntheticUnifiedAddress.mainnet(14_741 + state.ordinal)
        val base = PaidChatFixture(peer, mode, haveOurKey = state != State.HELD_FOR_OUR_ACCEPT)
        val heldKey = E2EEncryption.generateKeyPair().publicKey
        val oursOnly = state == State.OWED_NOT_SENT || state == State.ON_CHAIN_UNANSWERED
        val proxy =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                val forPeer = args?.getOrNull(0) == peer
                when {
                    oursOnly && method.name == "getE2EPeerPublicKey" -> null
                    oursOnly && method.name == "isE2EEnabled" -> false
                    oursOnly && method.name == "isE2EKeyExchangeComplete" -> false
                    method.name == "isKexOwed" -> forPeer && state == State.OWED_NOT_SENT
                    method.name == "getKexTxIds" ->
                        if (forPeer && state == State.ON_CHAIN_UNANSWERED) setOf("synthetic-own-kex-txid-1474") else emptySet<String>()
                    method.name == "getInboundKexAwaitingAccept" -> if (forPeer && state == State.HELD_FOR_OUR_ACCEPT) heldKey else null
                    state == State.HELD_FOR_OUR_ACCEPT && method.name == "getE2EPeerPublicKey" && forPeer -> heldKey
                    else ->
                        try {
                            method.invoke(base.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
        val vm = HandshakeHarness.chatViewModelWith(proxy, base.seam).also(AutoSpendVm::wireBootstrapFields)
        val before = base.seam.proposalRequests.get()
        val answer = vm.attachmentBlockReason(peer)?.name
        assertEquals(before, base.seam.proposalRequests.get(), "the gate paid or priced something")
        return answer
    }

    @Test
    fun `an owed key exchange that was never sent is KEX_NOT_SENT, not their answer has not arrived`() =
        runBlocking {
            for (mode in listOf(ConversationMode.TUNNEL, ConversationMode.VAULT, ConversationMode.OPEN)) {
                assertEquals("KEX_NOT_SENT", reason(State.OWED_NOT_SENT, mode), "in a $mode chat")
            }
        }

    @Test
    fun `a contact key exchange waiting for our Accept is PEER_MUST_ACCEPT`() =
        runBlocking {
            assertEquals("PEER_MUST_ACCEPT", reason(State.HELD_FOR_OUR_ACCEPT, ConversationMode.OPEN))
        }

    @Test
    fun `control - our key exchange is on chain and unanswered - UNANSWERED`() =
        runBlocking {
            val shown = reason(State.ON_CHAIN_UNANSWERED, ConversationMode.TUNNEL)
            assertTrue(shown == "UNANSWERED" || shown == "KEY_EXCHANGE_UNANSWERED", "a sent key exchange shows $shown")
        }

    @Test
    fun `control - a shared key lets attachments through`() =
        runBlocking {
            assertEquals(null, reason(State.SHARED_KEY, ConversationMode.TUNNEL))
        }
}
