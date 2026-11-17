package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ZC-1290: a send's time limits restart with each queue retry and each run (ZC-1276 limits one build, the queue allows
 * MAX_QUEUE_RETRIES more runs), so one row can show Sending for far longer than any single limit says: about 145 minutes
 * by the fix lane's count. Must hold: the total is counted from the row's first start, carried across retries, and after
 * [LIMIT_MS] of live waiting the row settles as not sent with its reason, a working Retry, and no late payment.
 *
 * The figure is this test's reading of "bounded": an hour, as in StalledSendNeverStaysSendingTest. The real wait for a
 * deposit is about 13 minutes, so the normal wait is well inside it, and ConfirmingFirstMessageSendsItselfTest pins that
 * ten confirmations are not cut.
 *
 * Virtual time on the test scheduler, so the limit must run on Dispatchers.Main like ZC-1276's: armed once, from the first
 * start, with what is left of the total.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSendWaitHasABoundedTotalTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private companion object {
        const val PEER = "usyntheticzc1290boundedwait1"
        const val PLAIN = ChatViewModel.DEFAULT_MESSAGE_AMOUNT
        const val SETTLE_MS = FundsConfirmingChat.SETTLE_MS
        const val MINUTE_MS = 60_000L
        const val LIMIT_MS = 60L * MINUTE_MS

        /** Below ZC-1276's limit for one build, so only a total counted across the runs can end this. */
        const val ONE_SLOW_BUILD_MS = 14L * MINUTE_MS
        const val BLOCK_MS = 75_000L
        val waitingForBlock = SendMessageState.Success(ChatSendCopy.QUEUED_WAITING_FOR_BLOCK)
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        ChatViewModel::class.java
            .getDeclaredField("heldGroupAcceptScope")
            .apply { isAccessible = true }
            .set(null, sendScopes.heldAcceptScope)
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
    }

    @Test
    fun `a queued message whose slow builds keep meeting confirming funds settles within the total and pays nothing late`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val builds = SlowBuilds(seam)
            val chat = PaymentHarness.paymentViewModel(prefs, seam)
            PaymentHarness.setField(chat, "createChunkedMessageProposal", useCase(builds))
            // Typed while the chat is busy, so it runs from the queue and meets the queue's retries.
            PaymentHarness.setSendMessageState(chat, SendMessageState.Sending)
            assertTrue(chat.sendMessage(PEER, "synthetic zc1290 slow builds", PLAIN))
            PaymentHarness.setSendMessageState(chat, SendMessageState.Idle)
            ChatViewModel::class.java
                .getDeclaredMethod("processNextQueuedMessage")
                .apply { isAccessible = true }
                .invoke(chat)
            val id =
                prefs.pending.values
                    .single()
                    .id

            try {
                var elapsed = 0L
                var run = 0
                var height = 1L
                while (elapsed <= LIMIT_MS && !settled(chat, id)) {
                    assertTrue(
                        "fixture sanity: run ${run + 1} did not start",
                        PaymentHarness.eventually(SETTLE_MS) { builds.entered.size > run },
                    )
                    scheduler.advanceTimeBy(ONE_SLOW_BUILD_MS)
                    scheduler.runCurrent()
                    elapsed += ONE_SLOW_BUILD_MS
                    if (elapsed > LIMIT_MS || settled(chat, id)) break
                    builds.entered[run].complete(HandshakeHarness.stillConfirming())
                    assertTrue(
                        "fixture sanity: run ${run + 1} did not go back to wait for a block",
                        PaymentHarness.eventually(SETTLE_MS) {
                            PaymentHarness.sendMessageState(chat) == waitingForBlock || settled(chat, id)
                        },
                    )
                    PaymentHarness.setBlockHeight(chat, height++)
                    run++
                }

                val row = PaymentHarness.pendingMessages(chat).single { it.id == id }
                assertTrue(
                    "after ${elapsed / MINUTE_MS} min across ${run + 1} runs the row still shows " +
                        "${row.status} (pending ${row.isPending}), with ${seam.submits.get()} paid",
                    settled(chat, id),
                )
                assertFalse("a row shown as not sent is still in flight", row.isPending)
                assertFalse("a row shown as not sent does not say why", row.failureReason.isNullOrBlank())
                assertFalse("a row shown as not sent is still held live, so its Retry is refused", LiveSenders.isLive(id))

                // The run that was parked wakes up able to build, after the row was settled.
                builds.releaseAll()
                assertFalse("the late build paid", PaymentHarness.eventually(SETTLE_MS / 4) { seam.submits.get() > 0 })

                assertTrue("Retry was refused for a row shown as not sent", chat.retryMessage(PEER, id))
                assertTrue("Retry did not pay", PaymentHarness.eventually(SETTLE_MS) { seam.submits.get() == 1 })
            } finally {
                builds.releaseAll()
            }
        }

    @Test
    fun `a first message waiting for funds that never confirm settles within the total, and a late block pays nothing`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen()
            val row = world.acceptWhileConfirming(vm, "synthetic zc1290 funds never confirm")

            var elapsed = 0L
            while (elapsed <= LIMIT_MS) {
                scheduler.advanceTimeBy(BLOCK_MS)
                scheduler.runCurrent()
                elapsed += BLOCK_MS
                world.block(vm)
                // Far inside the real wait for ten confirmations (about 13 minutes): nothing may give up yet.
                if (elapsed <= 15L * MINUTE_MS) assertTrue("the row gave up after ${elapsed / MINUTE_MS} min", world.isWaiting(row.id))
            }

            val stored = world.row()!!
            assertEquals("after an hour the row is not settled on disk", MessageStatus.FAILED.name, stored.status)
            assertFalse("after an hour the row is still in flight on disk", stored.isPending)
            assertFalse("the settled row does not say why", stored.failureReason.isNullOrBlank())
            assertEquals("the settled row is not settled on screen", MessageStatus.FAILED, world.shown(vm, row.id)?.status)
            assertEquals("something was paid", 0, world.seam.submits.get())

            // The user was told it was not sent, so funds confirming now pay nothing until they tap Retry.
            world.plan = "Fundable"
            world.block(vm)
            assertTrue("a block paid a row shown as not sent", world.staysAt(0))
        }

    /** The row reached its not-sent state. */
    private fun settled(
        chat: ChatViewModel,
        id: String,
    ): Boolean = PaymentHarness.pendingMessages(chat).single { it.id == id }.status == MessageStatus.FAILED

    private suspend fun useCase(source: ProposalDataSource): CreateChunkedMessageProposalUseCase {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        return CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = source,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ).also(sendScopes::own),
            accountDataSource = accounts,
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
        )
    }

    /** Each build stays open until the test answers it: with a refusal to throw, or null to go on and build. */
    private class SlowBuilds(
        private val inner: PaymentSeam,
    ) : ProposalDataSource by inner {
        val entered = CopyOnWriteArrayList<CompletableDeferred<Exception?>>()

        @Volatile private var released = false

        fun releaseAll() {
            released = true
            entered.forEach { it.complete(null) }
        }

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            if (!released) {
                val answer = CompletableDeferred<Exception?>()
                entered += answer
                if (released) answer.complete(null)
                answer.await()?.let { throw it }
            }
            return inner.createZip321Proposal(account, zip321Uri, allowMultiPayment)
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult = inner.submitTransaction(proposal, usk, onStored)
    }
}
