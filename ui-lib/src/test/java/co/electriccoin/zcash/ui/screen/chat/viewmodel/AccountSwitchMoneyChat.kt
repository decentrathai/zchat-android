package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
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
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ZC-1394, ZC-1395 — one new contact in a Shielded chat on a phone with two ZChat accounts (A = [SwitchableAccounts.zchat],
 * B = [SwitchableAccounts.otherZchat]), over the real ChatViewModel send path, the real use case and repository. The
 * proposal seam records which account each built payment was paid from and whether it was our KEX ([builds]); with
 * [failFirstKexTransiently] the first KEX build meets the "…confirm on-chain…" shortfall, so the sender waits for the next
 * block ([heights]). The store keeps the owed-KEX mark with its account and the rows, as KeystoneRefusalLeavesNothingOwedTest
 * does. Every address, key and account id is synthetic; nothing leaves the fakes.
 */
internal class AccountSwitchMoneyChat(
    val peer: String,
) {
    val prefs = Store()
    val seam = AccountRecordingSeam()
    val heights = HeightFeed()
    val accounts: SwitchableAccounts = runBlocking { SwitchableAccounts.create(IRONWOOD_CONFIRMING, OTHER_ZCHAT_UUID) }
    private val store = RealConversationIds().wrap(prefs.proxy)

    val keyA: String get() = keyOf(accounts.zchat)
    val keyB: String get() = keyOf(accounts.otherZchat)

    /** When true, the first KEX build fails with the transient shortfall (nothing built, nothing paid). */
    val failFirstKexTransiently = AtomicBoolean(false)

    suspend fun viewModel(): ChatViewModel {
        val vm = chatViewModelWith(store, HandshakeSeam(peer), heights, accountSource = accounts)
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
        vm.retryKexAckIfResponder(peer)
        vm.retryOwedKeyExchange(peer)
    }

    /** A new block: the chain tip moves and the ViewModel runs its per-block work. */
    fun tick(vm: ChatViewModel) {
        heights.advance()
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, heights.height.value?.value)
    }

    /** The send state flow, which the queue reads ("a send is running"). */
    @Suppress("UNCHECKED_CAST")
    fun sendState(vm: ChatViewModel): MutableStateFlow<SendMessageState> =
        ChatViewModel::class.java
            .getDeclaredField("_sendMessageState")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<SendMessageState>

    /** The earlier send ended: the screen takes the next queued message, as every send's end does. */
    fun earlierSendEnds(vm: ChatViewModel) {
        sendState(vm).value = SendMessageState.Idle
        ChatViewModel::class.java
            .getDeclaredMethod("processNextQueuedMessage")
            .apply { isAccessible = true }
            .invoke(vm)
    }

    fun builtBy(key: String): List<Built> = seam.builds.filter { it.accountKey == key }

    fun row(): ZchatPreferences.PendingMessageData? =
        prefs.pending.values.firstOrNull { it.peerAddress == peer && it.isOutgoing && it.id.startsWith("pending_") }

    data class Built(
        val accountKey: String,
        val kex: Boolean,
    )

    /** Records the account and kind of every payment built; a build that throws pays nothing and is not recorded. */
    inner class AccountRecordingSeam(
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val builds: MutableList<Built> = Collections.synchronizedList(mutableListOf())

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            val kex = ZMSGProtocol.isKEXMessage(firstMemo(zip321Uri))
            if (kex && failFirstKexTransiently.compareAndSet(true, false)) {
                throw InsufficientFundsException(CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE)
            }
            builds += Built(keyOf(account), kex)
            return syntheticZip321Proposal(peer, amount = 1_000L, memo = "synthetic")
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult = submitTransaction(proposal, usk)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult = SubmitResult.Success(txIds = listOf("synthetic-txid-${builds.size}"))
    }

    /** A map-backed store: our keys, the rows, the owed KEX mark with its account, the paid records, our own addresses. */
    class Store {
        val ourKeys = ConcurrentHashMap<String, E2EKeyPair>()
        val pending = ConcurrentHashMap<String, ZchatPreferences.PendingMessageData>()
        val kexOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val owedAccount = ConcurrentHashMap<String, String>()
        val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
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
                    "isSelfAddress" -> false
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
                    "toString" -> "AccountSwitchStore"
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

    companion object {
        const val IRONWOOD_CONFIRMING = 200_000L
        val OTHER_ZCHAT_UUID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000013b4")

        fun keyOf(account: WalletAccount): String = GetDefaultUnifiedAddressUseCase.accountKeyOf(account)
    }
}
