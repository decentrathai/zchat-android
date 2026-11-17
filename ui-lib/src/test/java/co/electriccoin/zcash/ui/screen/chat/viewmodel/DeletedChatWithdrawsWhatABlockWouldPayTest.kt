package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.QuotedChatSetup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * ZC-1350 (money-path rule 2): Accept with funds confirming stays armed for an hour (ZC-1338). hideChat withdraws a quoted
 * ZBOOT and a covered key exchange, and leaves the armed answer (acceptsWaitingForFunds, the Accept hold, acceptedChatRequests),
 * so Accept, delete chat, and a block within the hour pays for the chat the user just deleted.
 *
 * Must hold: after the delete, neither the waiting run nor a later block nor a chat open pays the answer. The controls pin the
 * other withdrawals: hideChat on a covered key exchange, and Cancel on New Chat's question. Everything is synthetic.
 */
class DeletedChatWithdrawsWhatABlockWouldPayTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private class Armed(
        val phone: OpenChatTunnelOfferPhone,
        val vm: ChatViewModel,
        val heights: HeightFeed,
    )

    /** A held key exchange in a Tunnel chat, Accept tapped, the funds still confirming: its run waits for a block. */
    private suspend fun acceptedWhileConfirming(): Armed {
        val phone =
            OpenChatTunnelOfferPhone(
                LedgerWorld(
                    mode = ConversationMode.TUNNEL,
                    consent = true,
                    accountSource =
                        Zc101FakeAccountDataSource(
                            syntheticZashiAccount().let {
                                it.copy(
                                    unified =
                                        it.unified.copy(
                                            ironwood = WalletBalance(Zatoshi(WALLET), Zatoshi(0), Zatoshi(CONFIRMING)),
                                        ),
                                )
                            },
                        ),
                ),
            )
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
        assertEquals("fixture: something was paid before the block", 0, submits(phone))
        return Armed(phone, vm, heights)
    }

    private fun submits(phone: OpenChatTunnelOfferPhone): Int =
        phone.world.seam.submits
            .get()

    private fun tip(
        vm: ChatViewModel,
        height: Long,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, height)
    }

    private suspend fun settle(armed: Armed) {
        PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { submits(armed.phone) > 0 }
    }

    private fun delete(armed: Armed) {
        armed.vm.hideChat(armed.phone.world.peer)
    }

    @Test
    fun `control - with the chat kept, the block pays the accepted answer`() =
        runBlocking {
            val armed = acceptedWhileConfirming()

            armed.heights.advance()
            settle(armed)

            assertEquals("fixture: the armed answer was not paid on its block", 1, submits(armed.phone))
        }

    @Test
    fun `a deleted chat's waiting answer is not paid by the run that waits for the block`() =
        runBlocking {
            val armed = acceptedWhileConfirming()

            delete(armed)
            armed.heights.advance()
            settle(armed)

            assertEquals("deleted the chat with Accept waiting: the next block paid its answer", 0, submits(armed.phone))
        }

    @Test
    fun `a deleted chat's accepted answer is not paid by later tips, and its Accept is forgotten`() =
        runBlocking {
            val armed = acceptedWhileConfirming()

            delete(armed)
            armed.heights.advance()
            settle(armed)
            tip(armed.vm, START_TIP)
            settle(armed)
            tip(armed.vm, START_TIP + 1)
            settle(armed)

            assertEquals("deleted the chat with Accept waiting: a later tip paid its answer", 0, submits(armed.phone))
            // A chat open reads this Accept to let the answer past its hold. By name: it is private to ChatViewModel.
            assertFalse(
                "the deleted chat's Accept still stands: its next open builds the answer",
                acceptedChatRequests().containsKey(armed.phone.world.peer),
            )
        }

    private fun acceptedChatRequests(): Map<*, *> =
        ChatViewModel::class.java
            .declaredClasses
            .plus(ChatViewModel::class.java)
            .firstNotNullOf { owner ->
                owner.declaredFields.firstOrNull { it.name == "acceptedChatRequests" }?.let { field ->
                    field.isAccessible = true
                    val holder =
                        if (Modifier.isStatic(field.modifiers)) {
                            null
                        } else {
                            owner.getField("INSTANCE").get(null)
                        }
                    field.get(holder) as Map<*, *>
                }
            }

    @Test
    fun `control - deleting the chat withdraws a key exchange New Chat's question covered`() =
        runBlocking {
            val world = OneDecisionWorld()
            world.memory.answers["getQuotedChatSetupPeers"] = setOf(world.peer)
            world.fundsConfirmed()
            val form = world.newChatIn(ConversationMode.TUNNEL)
            world.tap(form)
            world.pay(form)
            world.funds.paymentsSettled()
            val paid = world.submits()
            val vm = world.chatScreen()

            vm.hideChat(world.peer)
            world.funds.block(vm)
            world.funds.paymentsSettled()

            assertNull(
                "the delete left the covered key exchange on disk",
                QuotedChatSetup.decode(world.store.getQuotedChatSetup(world.peer))?.kex,
            )
            assertEquals("a block paid the key exchange of a deleted chat", paid, world.submits())
        }

    @Test
    fun `control - Cancel on New Chat's question leaves no leg and a block pays nothing`() =
        runBlocking {
            val world = OneDecisionWorld()
            world.memory.answers["getQuotedChatSetupPeers"] = setOf(world.peer)
            world.fundsConfirmed()
            val form = world.newChatIn(ConversationMode.TUNNEL)
            world.tap(form)
            NewChatPriceQuestion.require(form)

            form.dismissPaidAction()
            val vm = world.chatScreen()
            world.funds.block(vm)
            world.funds.paymentsSettled()

            assertNull("Cancel left a covered leg on disk", world.store.getQuotedChatSetup(world.peer))
            assertEquals("a block paid after Cancel on the question", 0, world.submits())
        }

    private companion object {
        const val WALLET = 20_000L
        const val CONFIRMING = 23_000L
        const val SDK_HAVE = 11_000L
        const val SDK_NEED = 12_000L
        const val START_TIP = 3_600_000L
    }
}
