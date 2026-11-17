package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.PrivacyStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.UserStatus
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-283 (fix-all 2.13.0, train T5b; r8-findings SPECS §2) — a status broadcast to three Shielded contacts A, B and C, as
 * the chat list runs it: setUserStatus(text, broadcast = true), the paid-action question, Pay, one memo per contact
 * through the block-aware retry and the real send chokepoint.
 *
 * [store] stands for one wallet's preferences across process kills: the saved status text and the status broadcast
 * record (the record is reached by its method names, getStatusBroadcastRecord / setStatusBroadcastRecord, so this file
 * compiles before the store has them) live in maps shared by every ViewModel a test makes ([viewModel] again is the next
 * process). [refuseRecordWrite] makes a record write report "not saved". [seam] is the SDK settlement seam: per contact
 * it can refuse the proposal ([StatusSeam.failProposal]) or report a submit the SDK stored and then rejected
 * ([StatusSeam.submitOutcome], `Failure(txIds = [T])`, which the chokepoint reads as "may have been sent").
 * Every address and txid is synthetic.
 */
internal class StatusBroadcastChat(
    failProposal: (destination: String, n: Int) -> Exception? = { _, _ -> null },
    submitOutcome: (destination: String) -> SubmitResult? = { null },
) {
    val fixture = PaidChatFixture(CONTACT_A, ConversationMode.VAULT)
    val heights = HeightFeed()
    val seam = StatusSeam(failProposal, submitOutcome)

    @Volatile var savedStatus: String = ""

    @Volatile var record: String? = null

    /** Every value written to the status broadcast record, in order (null = removed). */
    val recordWrites: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** Given the record before and the value to write, true makes the write report "not saved" (nothing changes). */
    @Volatile var refuseRecordWrite: (before: String?, value: String?) -> Boolean = { _, _ -> false }

    /** The record as it was each time a memo reached the SDK, by contact (null = nothing on disk). */
    val recordAtSubmit: MutableMap<String, String?> = Collections.synchronizedMap(mutableMapOf())

    val store: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getUserStatus" -> savedStatus
                "setUserStatus" -> {
                    savedStatus = args!![0] as String
                    null
                }
                "getStatusBroadcastRecord" -> record
                "setStatusBroadcastRecord" -> {
                    val value = args!![0] as String?
                    if (refuseRecordWrite(record, value)) {
                        false
                    } else {
                        record = value
                        recordWrites += value
                        true
                    }
                }
                else ->
                    try {
                        method.invoke(fixture.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    init {
        seam.onSubmit = { destination -> recordAtSubmit[destination] = record }
    }

    /** A ViewModel (a process) over [store] and [seam], with A, B and C in the chat list. */
    suspend fun viewModel(accounts: OwnChangeAccounts): ChatViewModel {
        val vm = HandshakeHarness.chatViewModelWith(store, HandshakeSeam(CONTACT_A), heights, accountSource = accounts)
        AutoSpendVm.setField(vm, "createChunkedMessageProposal", seam.useCase(accounts))
        AutoSpendVm.setField(vm, "_userStatus", MutableStateFlow(UserStatus.DEFAULT))
        AutoSpendVm.setField(
            vm,
            "_chatListState",
            MutableStateFlow<ChatListState>(
                ChatListState.Success(
                    conversations = CONTACTS.map { Conversation(peerAddress = it, messages = emptyList(), lastMessage = null) },
                    currentUserAddress = HandshakeHarness.SELF,
                    privacyStatus = PrivacyStatus.DEFAULT,
                ),
            ),
        )
        return vm
    }

    companion object {
        const val CONTACT_A = "usyntheticzc283statuscontacta"
        const val CONTACT_B = "usyntheticzc283statuscontactb"
        const val CONTACT_C = "usyntheticzc283statuscontactc"
        val CONTACTS = listOf(CONTACT_A, CONTACT_B, CONTACT_C)
        const val STATUS = "synthetic zc283 status"
        const val OTHER_STATUS = "synthetic zc283 other status"

        /** The record's hash of a contact (SPECS §2.4: SHA-256 hex of the address, first 32). */
        fun peerHash(address: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(address.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(32)

        fun state(vm: ChatViewModel): SendMessageState = HandshakeHarness.sendMessageState(vm)

        fun stateName(vm: ChatViewModel): String = state(vm).javaClass.simpleName

        fun intProperty(
            state: SendMessageState,
            name: String,
        ): Int =
            state.javaClass
                .getMethod("get${name.replaceFirstChar { it.uppercase() }}")
                .invoke(state) as Int

        /** The question on screen: its action's name and its on-chain message count. */
        fun question(vm: ChatViewModel): Pair<String, Int> {
            val state = state(vm)
            assertEquals("the user must be asked first, was $state", "NeedsPaidActionConfirmation", state.javaClass.simpleName)
            val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
            val action = confirmation.javaClass.getMethod("getAction").invoke(confirmation) as Enum<*>
            val count = confirmation.javaClass.getMethod("getOnChainMessages").invoke(confirmation) as Int
            return action.name to count
        }

        /** Mine a block each time a send waits for one, until [vm]'s jobs are over. Returns the blocks mined. */
        suspend fun runToEnd(
            vm: ChatViewModel,
            heights: HeightFeed,
        ): Int {
            var mined = 0
            withTimeout(HandshakeHarness.TIMEOUT_MS) {
                while (vm.viewModelScope.coroutineContext.job.children
                        .any { it.isActive }
                ) {
                    if (heights.isWaiting()) {
                        heights.advance()
                        mined++
                    }
                    delay(5)
                }
            }
            return mined
        }

        /** The process-wide "a status broadcast runs" flag, cleared between tests (absent before ZC-283). */
        fun resetStatusBroadcastFlag() {
            ChatViewModel::class.java
                .declaredFields
                .filter { it.name == "statusBroadcastRunning" && Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    field.isAccessible = true
                    (field.get(null) as? AtomicBoolean)?.set(false)
                }
        }
    }
}

/**
 * ZC-283 — the SDK settlement seam under the real repository, per destination: [failProposal] can refuse a proposal
 * (nothing reaches the SDK), [submitOutcome] can replace a submit's Success (null keeps it). [onSubmit] runs as each
 * submit reaches the SDK, before it is counted. Modelled on [ScriptedSeam]; balances and txids are synthetic.
 */
internal class StatusSeam(
    private val failProposal: (destination: String, n: Int) -> Exception?,
    private val submitOutcome: (destination: String) -> SubmitResult?,
    private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
) : ProposalDataSource by recording {
    val proposalRequests = AtomicInteger(0)
    val submits = AtomicInteger(0)

    @Volatile var onSubmit: ((String) -> Unit)? = null

    /** The address of every proposal asked for, in order. */
    val proposedTo: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The address of every proposal handed to the SDK, in submit order. */
    val submittedTo: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val destinations = Collections.synchronizedMap(mutableMapOf<Proposal, String>())

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean
    ): Zip321TransactionProposal {
        val n = proposalRequests.incrementAndGet()
        val destination = zip321Uri.removePrefix("zcash:").substringBefore('?')
        proposedTo += destination
        failProposal(destination, n)?.let { throw it }
        return syntheticZip321Proposal(destination, amount = 1_000L, memo = "synthetic-zc283-memo").also {
            destinations[it.proposal] = destination
        }
    }

    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
    ): SubmitResult = submitTransaction(proposal, usk)

    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): SubmitResult {
        val destination = destinations[proposal] ?: "unknown"
        onSubmit?.invoke(destination)
        val n = submits.incrementAndGet()
        submittedTo += destination
        return submitOutcome(destination) ?: SubmitResult.Success(txIds = listOf("synthetic-zc283-txid-$n"))
    }

    /** The real send chokepoint over this seam and [accounts], owned by the running test. */
    fun useCase(accounts: AccountDataSource): CreateChunkedMessageProposalUseCase =
        CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = this,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ).also(SendHarnessScopes.current()::own),
            accountDataSource = accounts,
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
        )
}
