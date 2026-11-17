package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1233 round 3 (review-1230b, GPT and Grok P1) — Pay on the Accept banner's true-price question answers key K. After the
 * tap the contact's next key exchange does one of two things: it only raises isE2EKeyChanged (stored key and hold stay K),
 * or it moves both to K2. Nothing may be paid in either case, and K2's own Accept stays.
 *
 * Three moments after the tap: before the send starts, during a wait for a block (the gate runs again), and while the
 * proposal is being built (the gate has passed, nothing has been submitted).
 *
 * The next-open question reaches the same function with the same argument, so the banner stands for both. No wire or stored
 * format is involved. Every address, key and id is synthetic.
 */
class InboundPayStaysWithItsKeyUntilTheSpendTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    /** Spendable above the floor, and change still confirming, so a refused build waits for a block. */
    private fun confirmingWallet() =
        Zc101FakeAccountDataSource(
            runBlocking {
                syntheticZashiAccount().let {
                    it.copy(unified = it.unified.copy(ironwood = WalletBalance(Zatoshi(20_000L), Zatoshi(100_000L), Zatoshi(0))))
                }
            },
        )

    private inner class Asked {
        val phone =
            OpenChatTunnelOfferPhone(LedgerWorld(mode = ConversationMode.TUNNEL, consent = true, accountSource = confirmingWallet()))
        val seam get() = phone.world.seam
        val peer get() = phone.world.peer
        val heights = HeightFeed()
        lateinit var vm: ChatViewModel
        lateinit var key: String

        suspend fun open() {
            key = phone.heldKex(ConversationMode.TUNNEL)
            vm = phone.screen()
            AutoSpendVm.setField(vm, "synchronizerProvider", heights.provider)
            seam.proposalFeeDelta = ABOVE_THE_HANDSHAKE
            phone.call(vm, "acceptInboundKexRequest")
            HandshakeHarness.awaitIdle(vm)
            assertTrue(
                "fixture: not asked: ${HandshakeHarness.sendMessageState(vm)}",
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation,
            )
        }

        fun onlyTheFlagRises() {
            phone.keyChanged += peer
        }

        fun bothMoveToANewKey(): String {
            val newKey = E2EEncryption.generateKeyPair().publicKey
            phone.world.base.peerKeys[peer] = newKey
            phone.world.prefs.receivedKex[peer] = newKey
            phone.hold[peer] = newKey
            return newKey
        }

        suspend fun payBeforeTheSendStarts(meanwhile: () -> Unit) {
            phone.afterHoldRead = meanwhile
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)
        }

        suspend fun payDuringABlockWait(meanwhile: () -> Unit) {
            seam.failNextProposalWith = InsufficientFundsException(have = Zatoshi(20_000L), need = Zatoshi(22_000L))
            vm.confirmPaidAction()
            until { heights.isWaiting() }
            meanwhile()
            heights.advance()
            HandshakeHarness.awaitIdle(vm)
        }

        suspend fun payDuringTheBuild(meanwhile: () -> Unit) {
            val built = seam.proposals.get()
            val held = CompletableDeferred<Unit>()
            seam.holdProposals = held
            vm.confirmPaidAction()
            until { seam.proposals.get() > built }
            meanwhile()
            seam.holdProposals = null
            held.complete(Unit)
            HandshakeHarness.awaitIdle(vm)
        }

        fun assertNothingPaidAndTheFlagStands() {
            assertEquals("Pay was spent while the contact's new key waits for the user", 0, seam.submits.get())
            assertTrue("the new key's flag is gone", peer in phone.keyChanged)
            assertTrue("the request on hold changed", phone.hold[peer] == key)
        }

        /** Nothing paid, K2 still on hold, and K2's own Accept answers K2. */
        suspend fun assertNothingPaidAndTheNewKeysAcceptStands(newKey: String) {
            assertEquals("Pay on K's question paid an answer after K2 arrived", 0, seam.submits.get())
            assertTrue("K2's request is not on hold any more", phone.hold[peer] == newKey)

            vm.resetSendState()
            seam.proposalFeeDelta = 0L
            phone.call(vm, "acceptInboundKexRequest")
            HandshakeHarness.awaitIdle(vm)

            assertEquals("K2's own Accept did not pay", 1, seam.submits.get())
            assertTrue("K2's Accept did not answer K2", phone.world.prefs.sentKexAck[peer] == newKey)
        }

        private suspend fun until(condition: () -> Boolean) {
            withTimeout(WAIT_MS) {
                while (!condition()) delay(POLL_MS)
            }
        }
    }

    private fun asked(body: suspend Asked.() -> Unit) =
        runBlocking {
            Asked().run {
                open()
                body()
            }
        }

    @Test
    fun `the flag rising before the send starts pays nothing`() =
        asked {
            payBeforeTheSendStarts { onlyTheFlagRises() }
            assertNothingPaidAndTheFlagStands()
        }

    @Test
    fun `the flag rising during a block wait pays nothing`() =
        asked {
            payDuringABlockWait { onlyTheFlagRises() }
            assertNothingPaidAndTheFlagStands()
        }

    @Test
    fun `the flag rising during the build pays nothing`() =
        asked {
            payDuringTheBuild { onlyTheFlagRises() }
            assertNothingPaidAndTheFlagStands()
        }

    @Test
    fun `both moving to K2 before the send starts pays nothing and K2's Accept stays`() =
        asked {
            var newKey = ""
            payBeforeTheSendStarts { newKey = bothMoveToANewKey() }
            assertNothingPaidAndTheNewKeysAcceptStands(newKey)
        }

    @Test
    fun `both moving to K2 during a block wait pays nothing and K2's Accept stays`() =
        asked {
            var newKey = ""
            payDuringABlockWait { newKey = bothMoveToANewKey() }
            assertNothingPaidAndTheNewKeysAcceptStands(newKey)
        }

    @Test
    fun `both moving to K2 during the build pays nothing and K2's Accept stays`() =
        asked {
            var newKey = ""
            payDuringTheBuild { newKey = bothMoveToANewKey() }
            assertNothingPaidAndTheNewKeysAcceptStands(newKey)
        }

    private companion object {
        const val ABOVE_THE_HANDSHAKE = 5_000L
        const val WAIT_MS = 8_000L
        const val POLL_MS = 5L
    }
}
