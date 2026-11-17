package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone matrix 1 Nov, HONOR release, the Accept banner of a Tunnel chat.
 * - ZC-1338: taps at 00:57, 00:58, 01:00 and 01:03 with funds confirming showed nothing and queued nothing: the build's
 *   "still confirming" is a transient wait, so the answer parks silently for a block and a later tap returns IN_FLIGHT.
 * - ZC-1343: the toast at 00:55 said the answer needs 0.00012 ZEC (the SDK's figure for one small note) under a banner that
 *   says at least 0.00034, while one answer's own price is 0.00017.
 */
class AcceptWhileFundsConfirmSaysWhatHappensTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val WALLET = 20_000L
        const val CONFIRMING = 23_000L
        const val SDK_HAVE = 11_000L
        const val SDK_NEED = 12_000L
        val SDK_ZEC = Regex("""(?<![\d.,])0[.,]00012(?!\d)""")
        val PRICE_ZEC = Regex("""(?<![\d.,])0[.,]000(17|34)(?!\d)""")
    }

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun phone(confirming: Long = 0L) =
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
                                            ironwood = WalletBalance(Zatoshi(WALLET), Zatoshi(0), Zatoshi(confirming)),
                                        ),
                                )
                            }
                        },
                    ),
            ),
        )

    @Test
    fun `a tap with funds confirming tells the user`() =
        runBlocking {
            val phone = phone(confirming = CONFIRMING)
            phone.heldKex(ConversationMode.TUNNEL)
            val vm = phone.screen()
            // A chain tip that does not move within this test: the answer's retry loop stays in its wait for a block, as on the phone.
            val heights = HeightFeed()
            PaymentHarness.setField(vm, "synchronizerProvider", heights.provider)
            phone.world.seam.failNextProposalWith = InsufficientFundsException(have = Zatoshi(SDK_HAVE), need = Zatoshi(SDK_NEED))

            phone.call(vm, "acceptInboundKexRequest")
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) {
                phone.world.seam.proposals
                    .get() >= 1
            }
            assertTrue(
                "fixture: the answer is not waiting for a block; state ${HandshakeHarness.sendMessageState(vm)}",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { heights.isWaiting() },
            )

            assertEquals(
                "fixture: the build was not reached",
                1,
                phone.world.seam.proposals
                    .get(),
            )
            assertEquals(
                "fixture: something was paid",
                0,
                phone.world.seam.submits
                    .get(),
            )
            val state = HandshakeHarness.sendMessageState(vm)
            assertTrue("Accept with funds confirming: the screen says nothing and no row stands: $state", state is SendMessageState.Error)
        }

    @Test
    fun `the refusal names what the answer costs, not the SDK's figure for one small note`() =
        runBlocking {
            val phone = phone()
            phone.heldKex(ConversationMode.TUNNEL)
            val vm = phone.screen()
            phone.world.seam.failNextProposalWith = InsufficientFundsException(have = Zatoshi(SDK_HAVE), need = Zatoshi(SDK_NEED))

            phone.call(vm, "acceptInboundKexRequest")
            HandshakeHarness.awaitIdle(vm)

            assertEquals(
                "fixture: the build was not reached",
                1,
                phone.world.seam.proposals
                    .get(),
            )
            val message = (HandshakeHarness.sendMessageState(vm) as SendMessageState.Error).message
            assertFalse("the notice says 0.00012 under a banner that says at least 0.00034: $message", SDK_ZEC.containsMatchIn(message))
            assertTrue("the notice does not name the answer's price: $message", PRICE_ZEC.containsMatchIn(message))
        }
}
