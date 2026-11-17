package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.keystone.KeystoneCloseFixture
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * T1b-1 §1 (fix-all 2.13.0, train T1b; T1 ledger L11, tx fbd39d6c6ab1): a Keystone-refused first message leaves a key
 * exchange owed that the ZChat account later pays without a question.
 *
 * At 35511a97a the first-message question is asked with no Keystone check. On confirm it stores the quoted-ZBOOT
 * allowance ("zbootquoted:<peer>"), the "KEX owed" mark ("kexowed:<peer>", per contact, no account) and our E2E key; only
 * then does the send chokepoint refuse (ZC-102/K0). A send that sent nothing keeps the owed mark, so the refusal is
 * handled like a wait for funds, and the block-tick and chat-open retries pay it with whichever account is selected,
 * never asking again. On the phone: 17,000 zatoshi paid by the ZChat account after the user was told "nothing was sent
 * or paid".
 *
 * Must hold:
 * 1. (A) With Keystone selected a first message raises no question, shows the Keystone "chats closed" text, returns
 *    false (the screen keeps the typed text) and stores nothing: no owed mark, no quoted ZBOOT, no key, no row.
 * 2. (B) The account switches to Keystone while the question is open, then the user confirms: refused, nothing owed,
 *    the typed text handed back.
 * 3. The L11 replay: Keystone first message, then the ZChat account selected, a block and a chat open: 0 proposals.
 * 4. (C) The owed mark belongs to the account that confirmed it: a mark of account A is not paid (nor used up) while
 *    account B is selected, and is paid once when A is selected. A confirmed first message stores its account.
 * 5. (E) A KEX owed to one of our own addresses is never sent, and its mark is dropped.
 *
 * Drives the real ChatViewModel send path through the real use case and repository; the account data source switches
 * the selected account the way the Wallet-tab chip does ([allAccounts] carries the isSelected flags). Every address,
 * key and account id is synthetic. New API (the owed mark's account) is answered by the store fake by method name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeystoneRefusalLeavesNothingOwedTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usynthetict1bkeystoneowed001"
        const val MESSAGE = "synthetic keystone first hello"
        const val IRONWOOD_CONFIRMING = 200_000L
        val OTHER_ZCHAT_UUID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a2")
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `with Keystone selected a first message asks nothing, says chats are closed and stores nothing`() =
        runBlocking {
            val chat = Chat()
            chat.accounts.selectKeystone()
            val vm = chat.viewModel()

            val accepted = vm.sendMessage(PEER, MESSAGE)
            awaitIdle(vm)

            val state = HandshakeHarness.sendMessageState(vm)
            assertFalse(
                "a Keystone account can never pay the first message, but the paid question was raised: $state",
                state.javaClass.simpleName == "NeedsPaidActionConfirmation",
            )
            assertEquals(SendMessageState.Error(KeystoneChatScreen.closedText()), state)
            assertFalse("the send was refused, so the screen must keep the typed text (return false)", accepted)
            chat.assertNothingStored("a refused Keystone first message")
            assertEquals("nothing may be built on a Keystone account", 0, chat.seam.proposals.get())
        }

    @Test
    fun `a switch to Keystone while the first-message question is open refuses the confirm and owes nothing`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            vm.sendMessage(PEER, MESSAGE)
            awaitIdle(vm)
            assertEquals(
                "fixture: the ZChat account is asked first",
                "NeedsPaidActionConfirmation",
                HandshakeHarness.sendMessageState(vm).javaClass.simpleName,
            )

            chat.accounts.selectKeystone()
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals(SendMessageState.Error(KeystoneChatScreen.closedText()), HandshakeHarness.sendMessageState(vm))
            chat.assertNothingStored("a first message confirmed after the switch to Keystone")
            assertEquals("the typed text is handed back", MESSAGE, restoredText(vm))
            assertEquals("nothing may be built on a Keystone account", 0, chat.seam.proposals.get())
        }

    @Test
    fun `the L11 replay - a Keystone-refused first message is never paid later by the ZChat account`() =
        runBlocking {
            val chat = Chat()
            chat.accounts.selectKeystone()
            val vm = chat.viewModel()

            vm.sendMessage(PEER, MESSAGE)
            awaitIdle(vm)
            // At 35511a97a the question is raised on Keystone; the user taps Pay and the chokepoint refuses.
            if (HandshakeHarness.sendMessageState(vm).javaClass.simpleName == "NeedsPaidActionConfirmation") {
                vm.confirmPaidAction()
                awaitIdle(vm)
            }
            assertEquals("fixture: nothing is built while Keystone is selected", 0, chat.seam.proposals.get())

            chat.accounts.selectZchat()
            chat.tick(vm)
            chat.openChat(vm)
            awaitIdle(vm)
            chat.tick(vm)
            awaitIdle(vm)

            assertEquals(
                "the ZChat account paid a key exchange the user was told was never sent or paid (L11)",
                0,
                chat.seam.kexProposals.get(),
            )
            assertEquals(0, chat.seam.proposals.get())
            chat.assertNothingStored("after the switch back to the ZChat account")
        }

    @Test
    fun `a KEX owed by another account is neither paid nor used up until that account is selected`() =
        runBlocking {
            val chat = Chat()
            chat.prefs.kexOwed += PEER
            chat.prefs.owedAccount[PEER] = accountKey(chat.accounts.otherZchat)
            val vm = chat.viewModel()

            chat.openChat(vm)
            chat.tick(vm)
            awaitIdle(vm)

            assertEquals(
                "a KEX confirmed on another account was paid by the selected account without a question",
                0,
                chat.seam.kexProposals.get(),
            )
            assertTrue("the other account's mark was used up", PEER in chat.prefs.kexOwed)

            chat.accounts.selectOtherZchat()
            chat.openChat(vm)
            chat.tick(vm)
            awaitIdle(vm)

            assertEquals("the account that confirmed the KEX pays it once", 1, chat.seam.kexProposals.get())
        }

    @Test
    fun `a confirmed first message records the account that confirmed its KEX`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            vm.sendMessage(PEER, MESSAGE)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)

            assertEquals("fixture: the KEX went", 1, chat.seam.kexProposals.get())
            assertEquals(
                "the owed KEX was stored without the account that confirmed it",
                listOf(accountKey(chat.accounts.zchat)),
                chat.prefs.owedAccountWrites.toList(),
            )
        }

    @Test
    fun `a KEX owed to one of our own addresses is never sent and its mark is dropped`() =
        runBlocking {
            val chat = Chat()
            chat.prefs.selfAddresses += PEER
            chat.prefs.kexOwed += PEER
            chat.prefs.owedAccount[PEER] = accountKey(chat.accounts.zchat)
            val vm = chat.viewModel()

            chat.tick(vm)
            chat.openChat(vm)
            awaitIdle(vm)

            assertEquals("a key exchange was paid to our own address", 0, chat.seam.proposals.get())
            assertFalse("the owed mark to our own address must be dropped", PEER in chat.prefs.kexOwed)
        }

    // --------------------------------------------------------------- harness

    private fun restoredText(vm: ChatViewModel): String? = vm.restoredTypedText.value?.text

    private fun accountKey(account: WalletAccount): String =
        account.sdkAccount.accountUuid.value
            .joinToString("") { "%02x".format(it) }

    /** A new contact in a Shielded chat, no keys yet, on a phone with two ZChat accounts and a Keystone account. */
    private class Chat {
        val prefs = OwedPrefs()
        val seam = CountingSeam()
        val heights = HeightFeed()
        val accounts: SwitchableAccounts = runBlocking { SwitchableAccounts.create(IRONWOOD_CONFIRMING, OTHER_ZCHAT_UUID) }
        private val conversations = RealConversationIds()
        private val store = conversations.wrap(prefs.proxy)

        suspend fun viewModel(): ChatViewModel {
            val vm = chatViewModelWith(store, HandshakeSeam(PEER), heights, accountSource = accounts)
            AutoSpendVm.setField(
                vm,
                "createChunkedMessageProposal",
                CreateChunkedMessageProposalUseCase(
                    keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                    zashiProposalRepository =
                        ZashiProposalRepositoryImpl(
                            accountDataSource = accounts,
                            proposalDataSource = seam,
                            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                        ).also { SendHarnessScopes.current().own(it) },
                    accountDataSource = accounts,
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                ),
            )
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
            AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
            AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
            AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
            AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
            AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
            AutoSpendVm.setField(vm, "_restoredTypedText", MutableStateFlow<Any?>(null))
            AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
            return vm
        }

        /** What the chat screen runs when it opens (AndroidChatDetail). */
        fun openChat(vm: ChatViewModel) {
            vm.retryKexAckIfResponder(PEER)
            vm.retryOwedKeyExchange(PEER)
        }

        /** A new block: the chain tip moves and the ViewModel runs its per-block work. */
        fun tick(vm: ChatViewModel) {
            heights.advance()
            ChatViewModel::class.java
                .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
                .apply { isAccessible = true }
                .invoke(vm, heights.height.value?.value)
        }

        fun assertNothingStored(what: String) {
            assertFalse("$what left a KEX owed", PEER in prefs.kexOwed)
            assertFalse("$what left an on-chain ZBOOT quoted", PEER in prefs.zbootQuoted)
            assertNull("$what created our E2E key for the contact", prefs.ourKeys[PEER])
            assertEquals("$what left a message row", emptyList<String>(), prefs.pending.keys.toList())
        }
    }

    /** Counts every proposal, and tells our KEX from anything else by the first memo it was proposed with. */
    private class CountingSeam(
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val proposals = AtomicInteger(0)
        val kexProposals = AtomicInteger(0)

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            proposals.incrementAndGet()
            if (ZMSGProtocol.isKEXMessage(firstMemo(zip321Uri))) kexProposals.incrementAndGet()
            return syntheticZip321Proposal(PEER, amount = 1_000L, memo = "synthetic")
        }

        // F4: the 1:1 chat senders now call this overload (to record the stored ids before the submit). This fake
        // reports no stored ids: it settles through its own two-argument submit below, exactly as before F4.
        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
        ): SubmitResult = submitTransaction(proposal, usk)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult = SubmitResult.Success(txIds = listOf("synthetic-txid-${proposals.get()}"))
    }

    /**
     * A map-backed store for the first-message, KEX, block and chat-open paths. The owed KEX mark, its account (read by
     * name: getKexOwedAccount / setKexOwedAccount), the quoted ZBOOT and our own addresses are recorded. Other reads
     * get an empty value and other writes report that they were saved, as a working store does.
     */
    private class OwedPrefs {
        val ourKeys = ConcurrentHashMap<String, E2EKeyPair>()
        val pending = ConcurrentHashMap<String, ZchatPreferences.PendingMessageData>()
        val kexOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val owedAccount = ConcurrentHashMap<String, String>()
        val owedAccountWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val selfAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val paidRecords = ConcurrentHashMap<String, String>()
        private val ownBootSent: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Suppress("CyclomaticComplexMethod", "LongMethod")
        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                fun arg(i: Int) = args!![i]
                when (method.name) {
                    "hasAcknowledgedMessageCost" -> true
                    "hasConsentedToHandshakeSpend" -> true
                    "getConversationMode" -> ConversationMode.VAULT
                    "getConversationModeOrNull" -> ConversationMode.VAULT
                    "isE2EKeyChanged" -> false
                    "getE2EOurPublicKey" -> ourKeys[arg(0) as String]?.publicKey
                    "getE2EPrivateKey" -> ourKeys[arg(0) as String]?.privateKey
                    "setE2EOurKeys" -> {
                        ourKeys[arg(0) as String] = E2EKeyPair(arg(1) as String, arg(2) as String)
                        null
                    }
                    "getE2EPeerPublicKey" -> null
                    "isE2EEnabled" -> false
                    "isE2EKeyExchangeComplete" -> false
                    "getReceivedKexPubkey" -> null
                    "getSentKexAckPubkey" -> null
                    "getPeerNostrPubkey" -> null
                    "getSentNostrBootPubkey" -> null
                    "getInboundKexAwaitingAccept" -> null
                    "getTunnelModePeers" -> emptyList<String>()
                    "walletScopeGeneration" -> 0L
                    "isSelfAddress" -> arg(0) in selfAddresses
                    "getHandshakePaidRecord" -> paidRecords[arg(0) as String]
                    "setHandshakePaidRecord" -> {
                        val value = arg(1) as String?
                        if (value == null) paidRecords.remove(arg(0) as String) else paidRecords[arg(0) as String] = value
                        true
                    }
                    "isOwnBootSent" -> arg(0) in ownBootSent
                    "setOwnBootSent" -> {
                        if (arg(1) as Boolean) ownBootSent += arg(0) as String else ownBootSent -= arg(0) as String
                        true
                    }
                    "isKexOwed" -> arg(0) in kexOwed
                    "getKexOwedPeers" -> kexOwed.toSet()
                    "setKexOwed" -> {
                        if (arg(1) as Boolean) {
                            kexOwed += arg(0) as String
                        } else {
                            kexOwed -= arg(0) as String
                            owedAccount.remove(arg(0) as String)
                        }
                        true
                    }
                    "getKexOwedAccount" -> owedAccount[arg(0) as String]
                    "setKexOwedAccount" -> {
                        val key = arg(1) as String?
                        if (key == null) owedAccount.remove(arg(0) as String) else owedAccount[arg(0) as String] = key
                        if (key != null) owedAccountWrites += key
                        true
                    }
                    "isZbootQuoted" -> arg(0) in zbootQuoted
                    "setZbootQuoted" -> {
                        if (arg(1) as Boolean) zbootQuoted += arg(0) as String else zbootQuoted -= arg(0) as String
                        true
                    }
                    "getPendingMessages" -> pending.values.sortedBy { it.timestampMillis }
                    "addPendingMessage", "commitPendingMessage" -> {
                        val row = arg(0) as ZchatPreferences.PendingMessageData
                        pending[row.id] = row
                        null
                    }
                    "removePendingMessage" -> {
                        pending.remove(arg(0) as String)
                        null
                    }
                    "getNostrRotationIndex" -> 0
                    "resolvePeerAddress" -> arg(0)
                    "getDisplayName" -> "Synthetic Contact"
                    "toString" -> "OwedPrefs"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> {
                        val isWrite =
                            method.name.startsWith("set") || method.name.startsWith("put") || method.name.startsWith("mark")
                        if (isWrite && method.returnType == java.lang.Boolean.TYPE) true else emptyValueFor(method.returnType)
                    }
                }
            } as ZchatPreferences

        private fun emptyValueFor(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                List::class.java -> emptyList<Any>()
                Set::class.java -> emptySet<Any>()
                Map::class.java -> emptyMap<Any, Any>()
                else -> null
            }
    }
}

/**
 * T1b-1 — the accounts of one phone: the ZChat account, a second ZChat account ([otherZchat], its own synthetic id) and a
 * Keystone account. [allAccounts] carries the isSelected flags the real data source sets, so a synchronous read of it
 * sees the switch; the ViewModel and the send chokepoint read the same instance. Balances: Ironwood still confirming, as
 * in FirstMessageKexIsNotLostTest.
 */
internal class SwitchableAccounts private constructor(
    val zchat: ZashiAccount,
    val otherZchat: ZashiAccount,
    val keystone: KeystoneAccount,
) : AccountDataSource {
    private val all = MutableStateFlow<List<WalletAccount>?>(null)
    private val selected = MutableStateFlow<WalletAccount>(zchat)

    init {
        select(zchat)
    }

    fun selectZchat() = select(zchat)

    fun selectOtherZchat() = select(otherZchat)

    fun selectKeystone() = select(keystone)

    private fun select(which: WalletAccount) {
        val z = zchat.copy(isSelected = which === zchat)
        val o = otherZchat.copy(isSelected = which === otherZchat)
        val k = keystone.copy(isSelected = which === keystone)
        selected.value =
            when {
                which === zchat -> z
                which === otherZchat -> o
                else -> k
            }
        all.value = listOf(z, o, k)
    }

    override val allAccounts: StateFlow<List<WalletAccount>?> = all
    override val selectedAccount: Flow<WalletAccount?> = selected
    override val zashiAccount: Flow<ZashiAccount?> = selected.map { zchat }

    override suspend fun getAllAccounts(): List<WalletAccount> = all.value.orEmpty()

    override suspend fun getSelectedAccount(): WalletAccount = selected.value

    override suspend fun getZashiAccount(): ZashiAccount = zchat

    override suspend fun selectAccount(account: Account) = Unit

    override suspend fun selectAccount(account: WalletAccount) = select(account)

    override suspend fun importKeystoneAccount(
        ufvk: String,
        seedFingerprint: String,
        index: Long,
    ): Account = error("not used in this test")

    override suspend fun requestNextShieldedAddress() = Unit

    companion object {
        suspend fun create(
            ironwoodConfirming: Long,
            otherZchatUuid: UUID,
        ): SwitchableAccounts {
            val base = syntheticZashiAccount()
            val zchat =
                base.copy(
                    unified =
                        base.unified.copy(
                            ironwood = WalletBalance(Zatoshi(0), Zatoshi(ironwoodConfirming), Zatoshi(0)),
                        ),
                )
            val other = zchat.copy(sdkAccount = AccountFixture.new(accountUuid = otherZchatUuid), isSelected = false)
            return SwitchableAccounts(zchat, other, KeystoneCloseFixture.keystone(selected = false))
        }
    }
}
