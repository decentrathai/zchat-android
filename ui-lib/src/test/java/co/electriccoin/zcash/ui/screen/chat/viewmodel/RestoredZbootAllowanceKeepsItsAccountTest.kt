package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.fixture.AccountFixture
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1432 (high, money-path rule 2) and ZC-1433 (medium). The quoted-ZBOOT allowance, its owed mark and its account
 * ("zbootowner:<peer>") are kept by the real ZchatPreferencesImpl here (in-memory SharedPreferences), not by
 * OneDecisionWorld's RememberingStore, which keeps the account across setZbootQuoted(false) and so hides ZC-1432.
 *
 * ZC-1432: account A confirms a Tunnel switch (owner = A). A's ZBOOT attempt stamps (consumes the allowance, which also
 * drops the owner) and its build reports funds still confirming, so nothing is stored; the unstamp puts the allowance
 * back as owed (restoreQuotedZbootAsOwed) without the owner. An allowance with no owner pays for any account: the next
 * block under account B pays A's ZBOOT out of B. Must hold: the owner is still A after the unstamp; B pays nothing; A
 * selected again pays it once.
 *
 * ZC-1433: the stamp gate lets the ZBOOT pay when the selected account cannot be read (the account list is null). Must
 * hold: a block whose account cannot be read pays nothing; once A reads again, A pays it once.
 *
 * Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoredZbootAllowanceKeepsItsAccountTest {
    private val sendScopes = SendHarnessScopes()

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

    private val accountA = walletWith(orchard = 0, ironwood = OneDecisionWorld.ONE_NOTE)
    private val accountB =
        accountA.copy(sdkAccount = AccountFixture.new(accountUuid = UUID.fromString(OTHER_ACCOUNT)))
    private val keyA = GetDefaultUnifiedAddressUseCase.accountKeyOf(accountA)

    /** The selected account, and the account list the stamp gate reads ([unreadable]: the list is null). */
    private class SelectedAccounts(
        initial: ZashiAccount,
        private val inner: ChangingAccountDataSource = ChangingAccountDataSource(initial),
    ) : AccountDataSource by inner {
        private val list = MutableStateFlow<List<WalletAccount>?>(listOf(initial))
        override val allAccounts: StateFlow<List<WalletAccount>?> = list

        fun select(account: ZashiAccount) {
            inner.account.value = account
            list.value = listOf(account)
        }

        fun unreadable() {
            list.value = null
        }
    }

    private class Phone(
        val world: OneDecisionWorld,
        val accounts: SelectedAccounts,
        val store: ZchatPreferences,
        val failedBuilds: AtomicInteger,
    ) {
        suspend fun screen(): ChatViewModel =
            world.chatScreen().also {
                PaymentHarness.setField(it, "zchatPreferences", store)
                PaymentHarness.setField(it, "accountDataSource", accounts)
            }

        suspend fun block(vm: ChatViewModel) {
            world.funds.block(vm)
            world.funds.paymentsSettled()
        }
    }

    /** [world]'s store, with the quoted-ZBOOT records in a real ZchatPreferencesImpl. */
    private fun quoteFileIsReal(world: OneDecisionWorld): ZchatPreferences {
        val real = realPreferences().first
        val other = world.store
        return Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "RealQuoteFileStore"
                else ->
                    try {
                        method.invoke(if (method.name in REAL) real else other, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
    }

    /**
     * A Shielded chat whose own KEX waits for the contact's answer. As A: Tunnel switch, Pay on its question (the ZBOOT is
     * quoted for A); the contact's answer lands, so the ZBOOT is due. From then on the first ZBOOT build reports funds
     * still confirming (nothing stored) when [failFirstZbootBuild]. Returns the phone and what was paid so far.
     */
    private suspend fun switchConfirmedAsA(failFirstZbootBuild: Boolean): Pair<Phone, Int> {
        val world = OneDecisionWorld()
        world.fundsConfirmed()
        world.chat.fixture.mode = ConversationMode.VAULT
        val accounts = SelectedAccounts(accountA)
        val phone = Phone(world, accounts, quoteFileIsReal(world), AtomicInteger(0))
        val ours = E2EEncryption.generateKeyPair()
        phone.store.setE2EOurKeys(world.peer, ours.publicKey, ours.privateKey)
        phone.store.setOwnBootSent(world.peer, true)
        val vm = phone.screen()

        vm.switchConversationMode(world.peer, ConversationMode.TUNNEL)
        HandshakeHarness.awaitIdle(vm)
        assertNotNull("fixture: the Tunnel switch asked no price question", world.paidQuestion(vm))
        vm.confirmPaidAction()
        HandshakeHarness.awaitIdle(vm)
        world.funds.paymentsSettled()
        assertTrue("fixture: Pay on the switch left no ZBOOT allowance on disk", phone.store.isZbootQuoted(world.peer))
        assertEquals("fixture: the allowance does not name account A", keyA, phone.store.getZbootQuotedAccount(world.peer))
        world.funds.leave(vm)

        world.contactAnswered()
        world.memory.answers["getTunnelModePeers"] = listOf(world.peer)
        if (failFirstZbootBuild) {
            val normal = world.wallet.feeFor
            world.wallet.feeFor = { uri ->
                if (phone.failedBuilds.getAndIncrement() == 0) throw HandshakeHarness.stillConfirming()
                normal(uri)
            }
        }
        return phone to world.submits()
    }

    /** As A: one block; the ZBOOT stamps, its build stores nothing, the unstamp restores the allowance as owed. */
    private suspend fun aAttemptStoredNothing(): Triple<Phone, ChatViewModel, Int> {
        val (phone, paid) = switchConfirmedAsA(failFirstZbootBuild = true)
        val vm = phone.screen()
        phone.block(vm)
        assertTrue("fixture: A's block built no ZBOOT", phone.failedBuilds.get() >= 1)
        assertEquals("fixture: A's failed ZBOOT build paid something", paid, phone.world.submits())
        assertTrue("fixture: the unstamp did not restore the allowance", phone.store.isZbootQuoted(phone.world.peer))
        assertTrue("fixture: the unstamp did not mark the ZBOOT owed", phone.store.isZbootOwed(phone.world.peer))
        return Triple(phone, vm, paid)
    }

    @Test
    fun `ZC-1432 an allowance restored after a ZBOOT that stored nothing still names the account that confirmed it`() =
        runBlocking {
            val (phone, _, _) = aAttemptStoredNothing()

            assertEquals(
                "the restored ZBOOT allowance lost the account that confirmed it",
                keyA,
                phone.store.getZbootQuotedAccount(phone.world.peer),
            )
        }

    @Test
    fun `ZC-1432 after A's ZBOOT stored nothing, account B's blocks pay none of it and A then pays it once`() =
        runBlocking {
            val (phone, vm, paid) = aAttemptStoredNothing()

            phone.accounts.select(accountB)
            repeat(BLOCKS) { phone.block(vm) }
            assertEquals("account B paid the restored ZBOOT account A's Tunnel switch quoted", paid, phone.world.submits())

            phone.accounts.select(accountA)
            repeat(BLOCKS) { phone.block(vm) }
            assertEquals("A selected again: its restored ZBOOT was not paid exactly once", paid + 1, phone.world.submits())
        }

    @Test
    fun `ZC-1433 a block whose selected account cannot be read pays no quoted ZBOOT, and A then pays it once`() =
        runBlocking {
            val (phone, paid) = switchConfirmedAsA(failFirstZbootBuild = false)
            val vm = phone.screen()

            phone.accounts.unreadable()
            repeat(BLOCKS) { phone.block(vm) }
            assertEquals("a block paid the quoted ZBOOT while the selected account could not be read", paid, phone.world.submits())

            phone.accounts.select(accountA)
            repeat(BLOCKS) { phone.block(vm) }
            assertEquals("A readable again: its quoted ZBOOT was not paid exactly once", paid + 1, phone.world.submits())
        }

    private companion object {
        const val BLOCKS = 3
        const val OTHER_ACCOUNT = "00000000-0000-4000-8000-000000001432"
        val REAL =
            setOf(
                "isZbootQuoted",
                "setZbootQuoted",
                "getZbootQuotedAccount",
                "setZbootQuotedAccount",
                "restoreQuotedZbootAsOwed",
                "isZbootOwed",
                "setZbootOwed",
                "getZbootOwedPeers",
            )
    }
}
