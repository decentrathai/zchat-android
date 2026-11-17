package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * ZC-1391, ZC-1392: the phone of AcceptWaitsUntilTheFundsCanPayTest (ZC-1375) as a reusable fixture. Accept on a held key
 * exchange in a Tunnel chat while the funds confirm; the answer's first build meets the "still confirming" shortfall, so the
 * Accept waits for the funds and its wait is in the store. [restart] ends the process (its memory goes, the store stays) and
 * opens a new screen that has seen no block yet. The selected account can be changed to [accountB], another account of the
 * same wallet. Everything is synthetic.
 */
internal class AcceptWaitPhone private constructor(
    val phone: OpenChatTunnelOfferPhone,
    val accounts: SwitchableAccounts,
    val vm: ChatViewModel,
    val heights: HeightFeed,
) {
    /** Selected and only account, switchable; the wallet's balance as each read finds it. */
    class SwitchableAccounts(
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

        fun now(): ZashiAccount = current.value

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

    fun submits(): Int =
        phone.world.seam.submits
            .get()

    /** The ZEC matured: spendable, and the standing consent reads as before; [account] is the one selected now. */
    fun fundsSpendable(account: Account = accountA) {
        phone.world.base.consent = true
        accounts.set(wallet(WALLET + CONFIRMING, 0L, account))
    }

    /** The process ends and starts again: only the store is left. The new screen has seen no block yet. */
    suspend fun restart(): ChatViewModel {
        LedgerProcess.reset()
        clearProcessMemory()
        return phone.screen().also { PaymentHarness.setField(it, "synchronizerProvider", HeightFeed().provider) }
    }

    /** New tips reach [screen]; each waits until something was paid or the screen is idle. */
    suspend fun blocks(
        screen: ChatViewModel,
        vararg tips: Long,
    ) {
        for (height in tips) {
            tip(screen, height)
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { submits() > 0 }
            HandshakeHarness.awaitIdle(screen)
        }
    }

    companion object {
        const val WALLET = 20_000L
        const val CONFIRMING = 23_000L
        const val SDK_HAVE = 11_000L
        const val SDK_NEED = 12_000L
        const val START_TIP = 3_600_000L

        private val accountA: Account = runBlocking { syntheticZashiAccount().sdkAccount }
        val accountB: Account = AccountFixture.new(accountUuid = UUID.fromString("00000000-0000-4000-8000-000000001392"))

        fun wallet(
            spendable: Long,
            confirming: Long,
            account: Account = accountA,
        ): ZashiAccount =
            runBlocking {
                syntheticZashiAccount().let {
                    it.copy(
                        sdkAccount = account,
                        unified = it.unified.copy(ironwood = WalletBalance(Zatoshi(spendable), Zatoshi(0), Zatoshi(confirming))),
                    )
                }
            }

        /** Accept tapped on the held key exchange of [OpenChatTunnelOfferPhone]'s contact, as account A, funds confirming. */
        suspend fun acceptedWhileConfirming(): AcceptWaitPhone {
            val accounts = SwitchableAccounts(wallet(WALLET, CONFIRMING))
            val phone =
                OpenChatTunnelOfferPhone(LedgerWorld(mode = ConversationMode.TUNNEL, consent = true, accountSource = accounts))
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
            val armed = AcceptWaitPhone(phone, accounts, vm, heights)
            assertEquals("fixture: something was paid before the block", 0, armed.submits())
            return armed
        }

        fun tip(
            vm: ChatViewModel,
            height: Long,
        ) {
            ChatViewModel::class.java
                .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
                .apply { isAccessible = true }
                .invoke(vm, height)
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

        fun clearProcessMemory() {
            processMap("acceptsWaitingForFunds").clear()
            processMap("acceptedChatRequests").clear()
        }
    }
}
