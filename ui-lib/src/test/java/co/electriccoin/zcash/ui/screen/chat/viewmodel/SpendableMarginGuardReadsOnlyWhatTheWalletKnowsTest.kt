package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.Context
import android.content.ContextWrapper
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSourceImpl
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.common.provider.SelectedAccountUUIDProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.PoolType
import co.electriccoin.zcash.ui.screen.chat.model.PrivacyStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.invoke
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.state
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ZC-1834, ZC-1835, ZC-1836, ZC-1839 (QA2 lane k1c2; Fable's review of k1c, CHANGES; money-path rule 1) — the ZC-1822
 * spendable margin guard (refusedByTheSpendableMargin) before one-transaction price questions.
 *
 * - ZC-1834 (high): until the synchronizer publishes walletBalances (its StateFlow starts at null, and a published map may
 *   still lack an account), AccountDataSourceImpl builds the account with empty balances. The guard read that as a true
 *   zero and refused a funded wallet with "you have 0 ZEC". Wanted: balances not published for the selected account are
 *   unknown, and the question is asked as before (the build decides). Driven through the real AccountDataSourceImpl over a
 *   stand-in synchronizer. Control: once 1,000 zatoshi are published, the guard refuses with have/need.
 * - ZC-1835 (medium): the mode picker's Apply (switchConversationMode -> planModeSwitch) asks MODE_CHANGE with no guard;
 *   the guarded branch in changeConversationMode is not reached from the picker. Wanted: a switch whose plan is one
 *   on-chain transaction refuses a wallet the margin rule rejects, and the mode stays as it was.
 * - ZC-1836 (medium): any confirming Ironwood switched the guard off. Wanted: it is asked only when waiting would cover the
 *   cost (spendable plus confirming meets the margin rule); 18,000 spendable plus 1,000 confirming against 17,000 cannot.
 * - ZC-1839 (part): pins for the exemptions and the figure: an unknown account is asked, a wallet the shielded-funds
 *   dialog answers never gets the have/need text, and a wallet below the cost is told it needs the cost itself.
 *
 * Synthetic wallets, addresses and keys; no money.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendableMarginGuardReadsOnlyWhatTheWalletKnowsTest {
    private val sendScopes = SendHarnessScopes()
    private val dataSourceScopes = mutableListOf<CoroutineScope>()

    private companion object {
        const val PEER = "usyntheticzc1834marginpeer001"
        const val RICH = 1_000_000L
        const val ONE_MEMO = 17_000L
        val LONG_TEXT = "synthetic long message for ZC-1834 ".repeat(40)
        const val SHORT_TEXT = "synthetic first message after the switch"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        dataSourceScopes.forEach { it.cancel() }
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ---- wallets ----

    private suspend fun fakeAccount(
        ironwood: WalletBalance,
        orchard: WalletBalance = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0)),
    ): ZashiAccount {
        val base = syntheticZashiAccount()
        return base.copy(unified = base.unified.copy(balance = orchard, ironwood = ironwood))
    }

    private suspend fun hold(
        vm: ChatViewModel,
        spendable: Long,
        confirming: Long = 0L,
    ) {
        val account = fakeAccount(WalletBalance(Zatoshi(spendable), Zatoshi(confirming), Zatoshi(0)))
        AutoSpendVm.setField(vm, "accountDataSource", Zc101FakeAccountDataSource(account))
    }

    /**
     * The real AccountDataSourceImpl over a stand-in synchronizer: one account (the SDK's public fixture), synthetic
     * addresses, and [balances] as the synchronizer's walletBalances (null: not published yet).
     */
    private fun realAccounts(balances: MutableStateFlow<Map<AccountUuid, AccountBalance>?>): AccountDataSourceImpl {
        val account = AccountFixture.new()
        val synchronizer =
            Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, method, args ->
                when (method.name) {
                    "getAccountsFlow" -> flowOf(listOf(account))
                    "getWalletBalances" -> balances
                    "getCustomUnifiedAddress" -> "usynthetic-zc1834-self"
                    "getSaplingAddress" -> "zsynthetic-zc1834-self"
                    "getTransparentAddress" -> "tsynthetic-zc1834-self"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "Zc1834Sdk"
                    else -> throw UnsupportedOperationException("unexpected Synchronizer.${method.name}")
                }
            } as Synchronizer
        val provider =
            object : SynchronizerProvider {
                override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
                override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(synchronizer)

                override suspend fun getSynchronizer(): Synchronizer = synchronizer

                override fun resetSynchronizer() = Unit
            }
        val selected =
            object : SelectedAccountUUIDProvider {
                override val uuid: Flow<AccountUuid?> = flowOf(null)

                override suspend fun getUUID(): AccountUuid? = null

                override suspend fun setUUID(uuid: AccountUuid) = Unit
            }
        val impl = AccountDataSourceImpl(provider, selected, allocate(ContextWrapper::class.java) as Context)
        val scope =
            AccountDataSourceImpl::class.java
                .getDeclaredField("scope")
                .apply { isAccessible = true }
                .get(impl)
        dataSourceScopes += scope as CoroutineScope
        return impl
    }

    private suspend fun awaitAccount(
        accounts: AccountDataSourceImpl,
        what: String,
        until: (WalletAccount) -> Boolean = { true },
    ): WalletAccount {
        repeat(250) {
            accounts.allAccounts.value
                ?.firstOrNull { it.isSelected }
                ?.takeIf(until)
                ?.let { return it }
            delay(20)
        }
        throw AssertionError("fixture: the account list never held $what")
    }

    private fun allocate(type: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return checkNotNull(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }

    // ---- phones ----

    private suspend fun shieldedPhone(): Pair<RealModeStorePhone, ChatViewModel> {
        val phone = RealModeStorePhone(PEER, ConversationMode.VAULT, consent = true, exchangeComplete = true)
        val vm = phone.viewModel()
        hold(vm, RICH)
        return phone to vm
    }

    private suspend fun peerMovedPhone(): Pair<RealModeStorePhone, ChatViewModel> {
        val phone =
            RealModeStorePhone(PEER, ConversationMode.TUNNEL, consent = true, exchangeComplete = true, relayKey = "c1834".padEnd(64, 'f'))
        phone.real.recordPeerIronwoodCap(PEER, true, System.currentTimeMillis() / 1000 - 60)
        phone.relaysUp()
        val vm = phone.viewModel()
        hold(vm, RICH)
        invoke(vm, "handleModeControl", PEER, ZMSGProtocol.createModeChange(ConversationMode.VAULT, System.currentTimeMillis(), PEER))
        awaitIdle(vm)
        assertEquals("fixture: the contact's switch to Shielded is adopted", ConversationMode.VAULT, phone.mode())
        return phone to vm
    }

    private fun outgoing(phone: RealModeStorePhone): Int = phone.real.getPendingMessages().count { it.peerAddress == PEER && it.isOutgoing }

    private fun asked(
        vm: ChatViewModel,
        action: PaidAction,
    ): SendMessageState.NeedsPaidActionConfirmation {
        val shown = state(vm)
        assertTrue(
            "the price question ($action) must be asked, was $shown",
            shown is SendMessageState.NeedsPaidActionConfirmation && shown.confirmation.action == action,
        )
        return shown as SendMessageState.NeedsPaidActionConfirmation
    }

    private fun assertRefusedWithHaveNeed(
        label: String,
        phone: RealModeStorePhone,
        vm: ChatViewModel,
        have: Long,
        need: Long,
        requestsBefore: Int,
        outgoingBefore: Int,
    ) {
        val shown = state(vm)
        assertFalse("$label: asked a price its Pay cannot pay", shown is SendMessageState.NeedsPaidActionConfirmation)
        assertEquals(
            "$label: the refusal names what the wallet has and what this needs",
            SendMessageState.Error(ChatSendCopy.messageShortfall(Zatoshi(have), Zatoshi(need))),
            shown,
        )
        assertEquals("$label: nothing may be built", requestsBefore, phone.seam.proposalRequests.get())
        assertEquals("$label: nothing may be paid", 0, phone.seam.submits.get())
        assertEquals("$label: nothing may be stored", outgoingBefore, outgoing(phone))
    }

    /** The long message's quoted cost, read from the question a rich wallet is asked. */
    private suspend fun longMessageCost(): Long {
        val (_, vm) = shieldedPhone()
        vm.sendMessage(PEER, LONG_TEXT)
        awaitIdle(vm)
        val question = asked(vm, PaidAction.LONG_MESSAGE)
        assertEquals("fixture: one on-chain transaction", 1, question.confirmation.onChainMessages)
        return question.confirmation.totalCost.value
    }

    // ---- ZC-1834 ----

    @Test
    fun `ZC-1834 - before the synchronizer publishes any balance the wallet is asked as before, not told it has 0 ZEC`() =
        runBlocking {
            val (phone, vm) = shieldedPhone()
            val accounts = realAccounts(MutableStateFlow(null))
            awaitAccount(accounts, "the selected account")
            AutoSpendVm.setField(vm, "accountDataSource", accounts)

            vm.sendMessage(PEER, LONG_TEXT)
            awaitIdle(vm)

            asked(vm, PaidAction.LONG_MESSAGE)
            assertEquals("nothing may be paid before the question", 0, phone.seam.submits.get())
        }

    @Test
    fun `ZC-1834 - a published balance map that does not hold this account yet is unknown too and is asked`() =
        runBlocking {
            val (_, vm) = shieldedPhone()
            val accounts = realAccounts(MutableStateFlow(emptyMap()))
            awaitAccount(accounts, "the selected account")
            AutoSpendVm.setField(vm, "accountDataSource", accounts)

            vm.sendMessage(PEER, LONG_TEXT)
            awaitIdle(vm)

            asked(vm, PaidAction.LONG_MESSAGE)
            Unit
        }

    @Test
    fun `ZC-1834 control - once the synchronizer publishes 1,000 zatoshi the guard refuses with have and need`() =
        runBlocking {
            val cost = longMessageCost()
            val (phone, vm) = shieldedPhone()
            val balances = MutableStateFlow<Map<AccountUuid, AccountBalance>?>(null)
            val accounts = realAccounts(balances)
            val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
            balances.value =
                mapOf(
                    AccountFixture.new().accountUuid to
                        AccountBalance(
                            sapling = zero,
                            orchard = zero,
                            ironwood = WalletBalance(Zatoshi(1_000), Zatoshi(0), Zatoshi(0)),
                            unshielded = Zatoshi(0),
                        ),
                )
            awaitAccount(accounts, "the published 1,000 zatoshi") { it.peerSpendableBalance == Zatoshi(1_000) }
            AutoSpendVm.setField(vm, "accountDataSource", accounts)
            val requestsBefore = phone.seam.proposalRequests.get()
            val outgoingBefore = outgoing(phone)

            vm.sendMessage(PEER, LONG_TEXT)
            awaitIdle(vm)

            assertRefusedWithHaveNeed("published 1,000", phone, vm, 1_000L, cost, requestsBefore, outgoingBefore)
        }

    // ---- ZC-1835 ----

    /**
     * A Shielded chat whose switch to Tunnel is one on-chain transaction, the mode change: the key exchange is done, our
     * relay identity went out earlier, and the contact's relay key is known but the relays are down.
     */
    private suspend fun oneTransactionSwitchPhone(spendable: Long = RICH): Pair<RealModeStorePhone, ChatViewModel> {
        val phone =
            RealModeStorePhone(PEER, ConversationMode.VAULT, consent = true, exchangeComplete = true, relayKey = "c1835".padEnd(64, 'e'))
        phone.auto.sentNostrBoot[PEER] = "synthetic-zc1835-our-relay-identity"
        val vm = phone.viewModel()
        hold(vm, spendable)
        return phone to vm
    }

    private suspend fun modeSwitchCost(): Long {
        val (phone, vm) = oneTransactionSwitchPhone()
        vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
        awaitIdle(vm)
        val question = asked(vm, PaidAction.MODE_CHANGE)
        assertEquals("fixture: the picker's switch quotes one on-chain transaction", 1, question.confirmation.onChainMessages)
        assertEquals("fixture: nothing changes before the question", ConversationMode.VAULT, phone.mode())
        return question.confirmation.totalCost.value
    }

    @Test
    fun `ZC-1835 - the mode picker's one-transaction switch refuses a wallet the margin rule rejects and keeps the mode`() =
        runBlocking {
            val cost = modeSwitchCost()
            val (phone, vm) = oneTransactionSwitchPhone(cost + 1_000L)
            val requestsBefore = phone.seam.proposalRequests.get()
            val outgoingBefore = outgoing(phone)

            vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
            awaitIdle(vm)

            assertRefusedWithHaveNeed(
                "picker switch at cost + 1,000",
                phone,
                vm,
                cost + 1_000L,
                cost + Zip317.MARGINAL_FEE_ZATOSHI,
                requestsBefore,
                outgoingBefore,
            )
            assertEquals("the mode stays as it was", ConversationMode.VAULT, phone.mode())
        }

    @Test
    fun `ZC-1835 control - the picker's switch is still asked at the cost and at the cost plus the margin`() =
        runBlocking {
            val cost = modeSwitchCost()
            for (spendable in listOf(cost, cost + Zip317.MARGINAL_FEE_ZATOSHI)) {
                val (phone, vm) = oneTransactionSwitchPhone(spendable)
                vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
                awaitIdle(vm)
                asked(vm, PaidAction.MODE_CHANGE)
                assertEquals("$spendable: nothing changes before the question", ConversationMode.VAULT, phone.mode())
            }
        }

    // ---- ZC-1836 ----

    @Test
    fun `ZC-1836 - 18,000 spendable plus 1,000 confirming cannot pay 17,000 even after waiting and is refused`() =
        runBlocking {
            assertEquals("fixture: the one-memo floor", ONE_MEMO, onChainSendMinimumCost().value)
            val (phone, vm) = peerMovedPhone()
            hold(vm, 18_000L, confirming = 1_000L)
            val requestsBefore = phone.seam.proposalRequests.get()
            val outgoingBefore = outgoing(phone)

            val returned = vm.sendMessage(PEER, SHORT_TEXT)
            awaitIdle(vm)

            assertRefusedWithHaveNeed(
                "18,000 + 1,000 confirming",
                phone,
                vm,
                18_000L,
                ONE_MEMO + Zip317.MARGINAL_FEE_ZATOSHI,
                requestsBefore,
                outgoingBefore,
            )
            assertEquals("the refusal hands the text back", false, returned)
        }

    @Test
    fun `ZC-1836 - a long message with 1,000 confirming on top of cost + 1,000 is refused`() =
        runBlocking {
            val cost = longMessageCost()
            val (phone, vm) = shieldedPhone()
            hold(vm, cost + 1_000L, confirming = 1_000L)
            val requestsBefore = phone.seam.proposalRequests.get()
            val outgoingBefore = outgoing(phone)

            vm.sendMessage(PEER, LONG_TEXT)
            awaitIdle(vm)

            assertRefusedWithHaveNeed(
                "cost + 1,000 with 1,000 confirming",
                phone,
                vm,
                cost + 1_000L,
                cost + Zip317.MARGINAL_FEE_ZATOSHI,
                requestsBefore,
                outgoingBefore,
            )
        }

    @Test
    fun `ZC-1836 control - confirming funds that would cover the cost keep the question`() =
        runBlocking {
            // 18,000 + 4,000 = 22,000 = 17,000 + the margin: waiting covers it.
            val (_, vm) = peerMovedPhone()
            hold(vm, 18_000L, confirming = 4_000L)
            vm.sendMessage(PEER, SHORT_TEXT)
            awaitIdle(vm)
            asked(vm, PaidAction.PEER_MOVED_TO_SHIELDED)

            // Nothing spendable yet, all 17,000 confirming: waiting covers it exactly.
            val (_, vm2) = peerMovedPhone()
            hold(vm2, 0L, confirming = ONE_MEMO)
            vm2.sendMessage(PEER, SHORT_TEXT)
            awaitIdle(vm2)
            asked(vm2, PaidAction.PEER_MOVED_TO_SHIELDED)
            Unit
        }

    // ---- ZC-1839 ----

    @Test
    fun `ZC-1839 - an account that is not known yet is asked as before`() =
        runBlocking {
            val (phone, vm) = shieldedPhone()
            // No account list at all (the harness default reads none).
            ChatViewModel::class.java
                .getDeclaredField("accountDataSource")
                .apply { isAccessible = true }
                .set(vm, null)

            vm.sendMessage(PEER, LONG_TEXT)
            awaitIdle(vm)

            asked(vm, PaidAction.LONG_MESSAGE)
            assertEquals(0, phone.seam.submits.get())
        }

    @Test
    fun `ZC-1839 - a wallet the shielded-funds dialog answers never gets the have and need text`() =
        runBlocking {
            val cost = longMessageCost()
            val (phone, vm) = shieldedPhone()
            // Every satoshi in sealed Orchard, none in Ironwood: the Stranded funds dialog answers this wallet.
            val stranded =
                fakeAccount(
                    ironwood = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0)),
                    orchard = WalletBalance(Zatoshi(100_000), Zatoshi(0), Zatoshi(0)),
                )
            AutoSpendVm.setField(vm, "accountDataSource", Zc101FakeAccountDataSource(stranded))
            @Suppress("UNCHECKED_CAST")
            val list =
                ChatViewModel::class.java
                    .getDeclaredField("_chatListState")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<ChatListState>
            list.value =
                ChatListState.Success(
                    conversations = emptyList(),
                    currentUserAddress = HandshakeHarness.SELF,
                    privacyStatus =
                        PrivacyStatus(poolType = PoolType.ORCHARD, orchardBalance = Zatoshi(100_000), ironwoodBalance = Zatoshi(0)),
                )

            vm.sendMessage(PEER, LONG_TEXT)
            awaitIdle(vm)

            val shown = state(vm)
            assertFalse(
                "the stranded wallet was given the have/need text instead of its own dialog, was $shown",
                shown == SendMessageState.Error(ChatSendCopy.messageShortfall(Zatoshi(0), Zatoshi(cost))),
            )
            assertTrue("asked as before (the dialog answers after Pay), was $shown", shown is SendMessageState.NeedsPaidActionConfirmation)
            assertEquals(0, phone.seam.submits.get())
        }

    @Test
    fun `ZC-1839 - a wallet below the cost is told it needs the cost itself, not the cost plus the margin`() =
        runBlocking {
            val (phone, vm) = peerMovedPhone()
            hold(vm, 5_000L)
            val requestsBefore = phone.seam.proposalRequests.get()
            val outgoingBefore = outgoing(phone)

            vm.sendMessage(PEER, SHORT_TEXT)
            awaitIdle(vm)

            assertRefusedWithHaveNeed("5,000 against 17,000", phone, vm, 5_000L, ONE_MEMO, requestsBefore, outgoingBefore)
        }
}
