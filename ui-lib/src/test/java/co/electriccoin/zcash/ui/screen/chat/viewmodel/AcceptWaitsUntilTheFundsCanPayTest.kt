package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * ZC-1375 (high, money-path rule 2), phone matrix 1 Nov (TIMELINE3 08:48-09:20, Seeker release on 697789c55): Accept tapped
 * with 0.00035 ZEC confirming said "Your Accept goes by itself once the ZEC can be spent"; the ZEC was spendable at about 09:05,
 * the chat stayed open, and at 09:20 nothing had gone; a manual Accept then went in 15 s.
 *
 * Cause (code reading): each run of the Accept re-arms its wait only when its LAST attempt was a "still confirming" shortfall.
 * An attempt that a gate stops before the build (the funds gate, the consent gate) clears the last failure first
 * (onAttemptStarting), so the run ends NOTHING_SENT with no refusal and acceptInboundKexRequest removes the wait with no word.
 * The wait is also process memory only: a restart forgets it.
 *
 * Must hold: the Accept stays waiting through such a run, and the block that can pay pays exactly one answer; a build above its
 * price asks with the true figure; within the hour the wait survives a restart. Everything is synthetic.
 */
class AcceptWaitsUntilTheFundsCanPayTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        LedgerTests.setUp(sendScopes)
        clearProcessMemory()
    }

    @After
    fun tearDown() {
        clearProcessMemory()
        LedgerTests.tearDown(sendScopes)
    }

    /** The wallet as each read finds it: a test moves the funds from confirming to spendable. */
    private class SwitchableAccounts(
        initial: ZashiAccount,
    ) : AccountDataSource {
        private val current = MutableStateFlow(initial)
        override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(initial))
        override val selectedAccount: Flow<WalletAccount?> = current
        override val zashiAccount: Flow<ZashiAccount?> = current

        fun set(account: ZashiAccount) {
            current.value = account
            (allAccounts as MutableStateFlow).value = listOf(account)
        }

        override suspend fun getAllAccounts(): List<WalletAccount> = listOf(current.value)

        override suspend fun getSelectedAccount(): WalletAccount = current.value

        override suspend fun getZashiAccount(): ZashiAccount = current.value

        override suspend fun selectAccount(account: Account) = Unit

        override suspend fun selectAccount(account: WalletAccount) = Unit

        override suspend fun importKeystoneAccount(
            ufvk: String,
            seedFingerprint: String,
            index: Long,
        ): Account = error("not used in this test")

        override suspend fun requestNextShieldedAddress() = Unit
    }

    private fun wallet(
        spendable: Long,
        confirming: Long,
    ): ZashiAccount =
        runBlocking {
            syntheticZashiAccount().let {
                it.copy(unified = it.unified.copy(ironwood = WalletBalance(Zatoshi(spendable), Zatoshi(0), Zatoshi(confirming))))
            }
        }

    private class Armed(
        val phone: OpenChatTunnelOfferPhone,
        val accounts: SwitchableAccounts,
        val vm: ChatViewModel,
        val heights: HeightFeed,
    )

    /** Accept on a held key exchange in a Tunnel chat; its first build meets the "still confirming" shortfall and waits. */
    private suspend fun acceptedWhileConfirming(): Armed {
        val accounts = SwitchableAccounts(wallet(WALLET, CONFIRMING))
        val phone = OpenChatTunnelOfferPhone(LedgerWorld(mode = ConversationMode.TUNNEL, consent = true, accountSource = accounts))
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
        return Armed(phone, accounts, vm, heights)
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

    /** The next attempt of the waiting run is stopped by the funds gate before any build (nothing can pay one handshake). */
    private suspend fun nextAttemptStoppedByTheFundsGate(armed: Armed) {
        val proposals =
            armed.phone.world.seam.proposals
                .get()
        armed.phone.world.base.consent = false
        armed.accounts.set(wallet(0L, WALLET + CONFIRMING))
        armed.heights.advance()
        PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { !armed.heights.isWaiting() }
        HandshakeHarness.awaitIdle(armed.vm)
        assertEquals(
            "fixture: the gated attempt reached the build",
            proposals,
            armed.phone.world.seam.proposals
                .get(),
        )
        assertEquals("fixture: the gated attempt paid", 0, submits(armed.phone))
    }

    /** The ZEC matured: spendable, and the standing consent reads as before. */
    private fun fundsSpendable(armed: Armed) {
        armed.phone.world.base.consent = true
        armed.accounts.set(wallet(WALLET + CONFIRMING, 0L))
    }

    private suspend fun blocks(
        vm: ChatViewModel,
        phone: OpenChatTunnelOfferPhone,
        vararg heights: Long,
    ) {
        for (height in heights) {
            tip(vm, height)
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { submits(phone) > 0 }
            HandshakeHarness.awaitIdle(vm)
        }
    }

    @Test
    fun `a run the funds gate stopped keeps the Accept waiting, and the funded block pays one answer`() =
        runBlocking {
            val armed = acceptedWhileConfirming()

            nextAttemptStoppedByTheFundsGate(armed)
            fundsSpendable(armed)
            blocks(armed.vm, armed.phone, START_TIP, START_TIP + 1, START_TIP + 2)

            assertEquals("Accept said it goes by itself; the funded blocks paid this many answers", 1, submits(armed.phone))
        }

    @Test
    fun `while it waits the screen keeps saying the Accept waits for the funds`() =
        runBlocking {
            val armed = acceptedWhileConfirming()

            nextAttemptStoppedByTheFundsGate(armed)

            val state = HandshakeHarness.sendMessageState(armed.vm)
            assertTrue(
                "after a gated attempt the screen no longer says the Accept waits: $state",
                state is SendMessageState.Error && state.message == ChatSendCopy.KEX_ANSWER_WAITS_FOR_FUNDS,
            )
        }

    @Test
    fun `a later build above its price asks with the true figure and pays nothing`() =
        runBlocking {
            val armed = acceptedWhileConfirming()

            armed.phone.world.seam.proposalFeeDelta = ABOVE_THE_PRICE
            armed.heights.advance()
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { !armed.heights.isWaiting() }
            HandshakeHarness.awaitIdle(armed.vm)

            assertEquals("an answer above its price was paid", 0, submits(armed.phone))
            val state = HandshakeHarness.sendMessageState(armed.vm)
            assertTrue(
                "a build above the price after the wait dropped the Accept with no question naming 22,000 zat: $state",
                state is SendMessageState.NeedsPaidActionConfirmation && TRUE_FIGURE.containsMatchIn(state.toString()),
            )
        }

    @Test
    fun `within the hour the Accept's wait survives a restart and the funded block pays one answer`() =
        runBlocking {
            val armed = acceptedWhileConfirming()

            // The process ends: its memory goes, the store stays.
            LedgerProcess.reset()
            clearProcessMemory()
            val restarted = armed.phone.screen()
            PaymentHarness.setField(restarted, "synchronizerProvider", HeightFeed().provider)
            fundsSpendable(armed)
            blocks(restarted, armed.phone, START_TIP, START_TIP + 1)

            assertEquals("after a restart the Accept tapped 10 minutes ago never went; answers paid", 1, submits(armed.phone))
        }

    private fun processMap(name: String): MutableMap<*, *> =
        ChatViewModel::class.java
            .declaredClasses
            .plus(ChatViewModel::class.java)
            .firstNotNullOf { owner ->
                owner.declaredFields.firstOrNull { it.name == name }?.let { field ->
                    field.isAccessible = true
                    val holder = if (Modifier.isStatic(field.modifiers)) null else owner.getField("INSTANCE").get(null)
                    field.get(holder) as MutableMap<*, *>
                }
            }

    private fun clearProcessMemory() {
        processMap("acceptsWaitingForFunds").clear()
        processMap("acceptedChatRequests").clear()
    }

    private companion object {
        const val WALLET = 20_000L
        const val CONFIRMING = 23_000L
        const val SDK_HAVE = 11_000L
        const val SDK_NEED = 12_000L
        const val ABOVE_THE_PRICE = 5_000L
        const val START_TIP = 3_600_000L
        val TRUE_FIGURE = Regex("""(?<![\d.,])(0[.,]00022|22000)(?!\d)""")
    }
}
