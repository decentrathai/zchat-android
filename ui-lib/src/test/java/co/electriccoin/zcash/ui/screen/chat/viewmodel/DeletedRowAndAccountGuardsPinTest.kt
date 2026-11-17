package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1980 (Fable review of fix/2130-msg, 16 Nov 2026; register low, test gap) — pins three guards of the ZC-1958 and ZC-1959
 * fixes that no test drove: doSendMessage's re-read of a deleted row before the owed-KEX mark is written, its re-read right
 * before payOwedKex, and the paying-account check in the message's onSubmitStarting. These are green at the fix by design;
 * each fails when its one guard line is taken out (shown on a scratch copy, never committed).
 *
 * - A Delete made inside the run's read of the ZBOOT allowance (after the re-read that follows the plan check) writes no
 *   owed-KEX mark and pays nothing.
 * - A Delete made right after the owed-KEX mark is written pays no key exchange and no message.
 * - An account switched to while the message (after its KEX) is built pays no message.
 *
 * Real ChatViewModel send, block tick and delete over [FundsConfirmingChat] and [ParkedFirstMessageChat]; every address,
 * key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletedRowAndAccountGuardsPinTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1980guardpins01"
        const val TYPED = "synthetic first words for the guard pins"
        const val OTHER_ACCOUNT = "19801980-0000-4000-8000-000000001980"
        const val FUNDED = 500_000L
        const val KEX_ONLY = 1
        const val KEX_AND_MESSAGE = 2
        const val BLOCKS = 2
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
    fun `a row deleted after the plan re-read and before the owed mark writes no owed mark and pays nothing`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen().also(::canHideMessages)
            val row = world.acceptWhileConfirming(vm, TYPED)
            val hook =
                DeleteAtStoreCall(vm, row.id, after = false) { method, _ ->
                    method.name == "isZbootQuoted" && onStack("takeUnsentZbootQuote")
                }
            resumeAndSettle(world, vm, hook)

            assertFalse(
                "an owed-KEX mark was written for a message the user deleted (writes ${world.chat.kexOwedWrites})",
                world.chat.kexOwedWrites.any { it.first == PEER && it.second },
            )
            assertFalse("an owed-KEX mark stands for a message the user deleted", PEER in world.chat.kexOwed)
            assertEquals("something was paid for a message the user deleted", 0, world.seam.submits.get())
        }

    @Test
    fun `a row deleted right after its owed mark is written pays no key exchange and no message`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val vm = world.screen().also(::canHideMessages)
            val row = world.acceptWhileConfirming(vm, TYPED)
            val hook =
                DeleteAtStoreCall(vm, row.id, after = true) { method, args ->
                    method.name == "setKexOwed" && args[0] == PEER && args[1] == true
                }
            resumeAndSettle(world, vm, hook)

            assertEquals("something was paid for a message the user deleted", 0, world.seam.submits.get())
            assertEquals("a proposal was built for a message the user deleted", 0, world.seam.proposalRequests.get())
        }

    @Test
    fun `control - with no switch the message after the KEX is paid`() =
        runBlocking {
            val (c, wire) = fundedChat(switchAtMessageBuild = false)
            payFirstMessage(c)
            assertTrue(
                "fixture: the KEX and the message were not both paid: ${c.submits()} submits",
                PaymentHarness.eventually(ParkedFirstMessageChat.SETTLE_MS) { c.submits() >= KEX_AND_MESSAGE },
            )
            assertFalse("fixture: the account switched with no switch asked", wire.switched)
        }

    @Test
    fun `an account switched to while the message is built after the KEX pays no message`() =
        runBlocking {
            val (c, wire) = fundedChat(switchAtMessageBuild = true)
            payFirstMessage(c)
            assertTrue(
                "fixture: the key exchange was not paid (${c.state()})",
                PaymentHarness.eventually(ParkedFirstMessageChat.SETTLE_MS) { c.submits() >= KEX_ONLY },
            )
            assertTrue(
                "fixture: the switch did not happen in the message's build",
                PaymentHarness.eventually(ParkedFirstMessageChat.SETTLE_MS) { wire.switched },
            )
            assertTrue(
                "the message was submitted while another account was selected: ${c.submits()} submits",
                c.world.staysAt(KEX_ONLY),
            )
            repeat(BLOCKS) { c.block() }
            assertTrue("a later block paid the message under the other account: ${c.submits()} submits", c.world.staysAt(KEX_ONLY))
        }

    /** A block resumes the waiting row with the plan Fundable; [hook] deletes it once at its store call. */
    private suspend fun resumeAndSettle(
        world: FundsConfirmingChat,
        vm: ChatViewModel,
        hook: DeleteAtStoreCall,
    ) {
        assertEquals("fixture: the Pay paid while the funds confirm", 0, world.seam.submits.get())
        world.plan = "Fundable"
        world.block(vm)
        assertTrue(
            "fixture: the resumed send never reached the store call the delete is made at " +
                "(${HandshakeHarness.sendMessageState(vm)})",
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { hook.fired },
        )
        world.paymentsSettled()
        assertNull("fixture: the deleted row is still on disk", world.row())
        repeat(BLOCKS) {
            world.block(vm)
            world.paymentsSettled()
        }
    }

    /** Account A is selected and funded; the SDK builds every leg; optionally B is selected inside the message's build. */
    private suspend fun fundedChat(switchAtMessageBuild: Boolean): Pair<ParkedFirstMessageChat, SwitchAtSecondBuild> {
        val c = ParkedFirstMessageChat.open(sendScopes, PEER)
        c.funds.refuse = false
        c.wallet.select(available = FUNDED, pending = 0)
        c.vmWallet.select(available = FUNDED, pending = 0)
        val wire = SwitchAtSecondBuild(c.funds)
        if (switchAtMessageBuild) {
            wire.onSecondBuild = {
                c.wallet.selectOther(UUID.fromString(OTHER_ACCOUNT), available = FUNDED)
                c.vmWallet.selectOther(UUID.fromString(OTHER_ACCOUNT), available = FUNDED)
            }
        }
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = c.wallet,
                        proposalDataSource = wire,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ).also { sendScopes.own(it) },
                accountDataSource = c.wallet,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            )
        PaymentHarness.setField(c.vm, "createChunkedMessageProposal", useCase)
        assertEquals(
            "fixture: the accepting account is not the synthetic ZChat account",
            GetDefaultUnifiedAddressUseCase.accountKeyOf(syntheticZashiAccount()),
            GetDefaultUnifiedAddressUseCase.accountKeyOf(c.wallet.getSelectedAccount()),
        )
        return c to wire
    }

    private suspend fun payFirstMessage(c: ParkedFirstMessageChat) {
        c.world.plan = "Fundable"
        c.world.chat.typeAndSend(c.vm, ParkedFirstMessageChat.TYPED)
        c.world.question(c.vm)
        c.vm.confirmPaidAction()
    }

    private fun onStack(methodName: String): Boolean = Thread.currentThread().stackTrace.any { it.methodName == methodName }

    /**
     * The screen's store; at the first call [at] matches while [rowId] is live, the row is deleted on the screen
     * (hideMessage), before the call ([after] false) or right after it returns ([after] true).
     */
    private class DeleteAtStoreCall(
        private val vm: ChatViewModel,
        private val rowId: String,
        private val after: Boolean,
        private val at: (Method, Array<Any?>) -> Boolean,
    ) {
        @Volatile var fired: Boolean = false

        private val base: ZchatPreferences =
            ChatViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .get(vm) as ZchatPreferences

        private val store: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "toString" -> "DeleteAtStoreCall"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> {
                        val fire = !fired && LiveSenders.isLive(rowId) && at(method, args ?: emptyArray())
                        if (fire) fired = true
                        if (fire && !after) vm.hideMessage(rowId)
                        val answer =
                            try {
                                method.invoke(base, *(args ?: emptyArray()))
                            } catch (e: InvocationTargetException) {
                                throw e.targetException
                            }
                        if (fire && after) vm.hideMessage(rowId)
                        answer
                    }
                }
            } as ZchatPreferences

        init {
            PaymentHarness.setField(vm, "zchatPreferences", store)
        }
    }

    /** The wallet under the use case: runs [onSecondBuild] inside the second build (the message's, after the KEX's). */
    private class SwitchAtSecondBuild(
        private val delegate: ProposalDataSource,
    ) : ProposalDataSource by delegate {
        @Volatile var onSecondBuild: (() -> Unit)? = null

        @Volatile var switched = false
        private val builds = AtomicInteger(0)

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            if (builds.incrementAndGet() == 2) {
                onSecondBuild?.let {
                    it()
                    switched = true
                }
            }
            return delegate.createZip321Proposal(account, zip321Uri, allowMultiPayment)
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult = delegate.submitTransaction(proposal, usk)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult = delegate.submitTransaction(proposal, usk, onStored)
    }
}
