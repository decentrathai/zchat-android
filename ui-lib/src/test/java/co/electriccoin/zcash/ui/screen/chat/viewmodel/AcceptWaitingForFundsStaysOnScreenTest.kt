package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1363 (phone matrix 1 Nov, Seeker release, 08:48:47 to 09:20): after Accept with funds confirming the only sign was a
 * toast (SendMessageState.Error), which the screen consumes at once (resetSendState); the banner still said
 * "New contact / Accept" and nothing told the user the Accept was waiting.
 *
 * Seam: `ChatViewModel.acceptWaitingForFunds(peer)`, non-null while that contact's Accept waits for its funds, the state
 * ChatDetailView's waiting line (chat_accept_waits_for_funds_banner) is drawn from. It stays after the toast is consumed.
 */
class AcceptWaitingForFundsStaysOnScreenTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val WALLET = 20_000L
        const val CONFIRMING = 23_000L
        const val SDK_HAVE = 11_000L
        const val SDK_NEED = 12_000L
    }

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun phone() =
        OpenChatTunnelOfferPhone(
            LedgerWorld(
                mode = ConversationMode.TUNNEL,
                consent = true,
                accountSource =
                    Zc101FakeAccountDataSource(
                        runBlocking {
                            syntheticZashiAccount().let {
                                it.copy(
                                    unified =
                                        it.unified.copy(
                                            ironwood = WalletBalance(Zatoshi(WALLET), Zatoshi(0), Zatoshi(CONFIRMING)),
                                        ),
                                )
                            }
                        },
                    ),
            ),
        )

    @Test
    fun `an Accept waiting for its funds stays on screen after the toast is gone`() =
        runBlocking {
            val phone = phone()
            phone.heldKex(ConversationMode.TUNNEL)
            val vm = phone.screen()
            val heights = HeightFeed()
            PaymentHarness.setField(vm, "synchronizerProvider", heights.provider)
            phone.world.seam.failNextProposalWith = InsufficientFundsException(have = Zatoshi(SDK_HAVE), need = Zatoshi(SDK_NEED))

            phone.call(vm, "acceptInboundKexRequest")
            assertTrue(
                "fixture: the answer is not waiting for a block; state ${HandshakeHarness.sendMessageState(vm)}",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { heights.isWaiting() },
            )
            assertTrue(
                "fixture: something was paid",
                phone.world.seam.submits
                    .get() == 0,
            )

            // The chat screen shows the toast and consumes it (AndroidChat: Toast, then resetSendState()).
            vm.resetSendState()

            assertNotNull(
                "Accept waits for confirming funds, but once the toast is gone the chat has no waiting line for it",
                phone.call(vm, "acceptWaitingForFunds"),
            )
        }
}
