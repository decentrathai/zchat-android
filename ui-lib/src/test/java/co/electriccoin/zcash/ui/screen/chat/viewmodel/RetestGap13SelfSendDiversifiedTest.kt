package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import java.lang.reflect.Proxy
import kotlin.test.Test

/**
 * RED regression pin for finding [13] (send-to-self) — ZchatComposeVM's Send-button gate
 * (updateState) only rejected the recipient when it equalled the single DEFAULT unified address
 * (userAddress). Any OTHER diversified/receiver address of the user's own wallet passed validation,
 * enabling a real on-chain self-send that burns the platform + network fee and spawns a
 * sender==recipient conversation that corrupts v4 threading.
 *
 * The fix mirrors the sibling `zchatPreferences.isSelfAddress()` guard (ChatViewModel.kt:493,
 * NostrChatBridge.kt:404), which compares by address hash and so tolerates diversifier drift.
 *
 * RED-on-the-current-code: with only the `recipientAddress != userAddress` clause, pasting a valid
 * self diversified UA (userAddress is still null while the synchronizer parks) yields isValidAddress
 * == true and this test fails; adding the isSelfAddress conjunct makes it false -> green.
 *
 * Fixture idiom mirrors RetestGap07ZeroCustomAmountCoercionTest: a REAL ZchatComposeVM on the JVM,
 * main dispatcher = UnconfinedTestDispatcher so init runs synchronously, hand-written fakes for the
 * constructor seams. Here the ZchatPreferences Proxy answers isSelfAddress(SELF_UA)=true and returns
 * sane read-only defaults for the other reads on the recipient-change path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetestGap13SelfSendDiversifiedTest {

    private companion object {
        // A valid non-default unified address (u1 + length > 100) standing in for one of the user's
        // OWN wallet's diversified receiving addresses. It is NOT equal to userAddress (which stays
        // null in this harness), so the old `!= userAddress` clause alone lets it through.
        val SELF_UA = "u1" + "z".repeat(120)

        // A different valid UA that is NOT the user's own address — must stay sendable.
        val OTHER_UA = "u1" + "y".repeat(120)
    }

    private val vms = mutableListOf<ZchatComposeVM>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `pasting the user's own diversified address disables send (isValidAddress=false)`() =
        runBlocking {
            val vm = buildVm()

            vm.readyState().onRecipientChange(SELF_UA)

            assertFalse(
                "a self diversified UA (isSelfAddress==true) must gate the Send button off, " +
                    "not just the single default userAddress",
                vm.readyState().isValidAddress
            )
        }

    @Test
    fun `a different valid non-self UA stays sendable`() =
        runBlocking {
            val vm = buildVm()

            vm.readyState().onRecipientChange(OTHER_UA)

            assertTrue(
                "a valid UA that is NOT the user's own address must keep the Send button enabled",
                vm.readyState().isValidAddress
            )
        }

    // --------------------------------------------------------------- harness

    private suspend fun buildVm(): ZchatComposeVM {
        val accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val synchronizerProvider = HangingSynchronizerProvider()
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accountDataSource)
        val vm =
            ZchatComposeVM(
                contactBook = FakeContactBook(),
                getSelectedWalletAccount = getSelectedWalletAccount,
                getDefaultUnifiedAddress =
                    GetDefaultUnifiedAddressUseCase(synchronizerProvider, getSelectedWalletAccount),
                createChunkedMessageProposal =
                    CreateChunkedMessageProposalUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = accountDataSource,
                                proposalDataSource = Zc101RecordingProposalDataSource(),
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ),
                        accountDataSource = accountDataSource,
                        navigationRouter = Zc101RecordingNavigationRouter(),
                        biometricRepository = Zc101SuspendingBiometricRepository(),
                    ),
                navigationRouter = Zc101RecordingNavigationRouter(),
                prefillZchat = PrefillZchatUseCase(),
                transactionRepository = FakeTransactionRepository(),
                zchatPreferences = selfAwarePrefs(),
                validateAddress = ValidateAddressUseCase(synchronizerProvider),
            )
        vms += vm
        return vm
    }

    private fun ZchatComposeVM.readyState(): ZchatComposeState.Ready =
        state.value as? ZchatComposeState.Ready
            ?: error("VM state was ${state.value}, expected Ready")

    // ------------------------------------------------------------------ fakes

    /**
     * ZchatPreferences Proxy that reports SELF_UA as our own address and returns benign read-only
     * defaults for every other method the recipient-change path touches (getConversationModeOrNull,
     * getPeerNostrPubkey, ...). Booleans default to false, other primitives to 0, references to null.
     */
    private fun selfAwarePrefs(): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "isSelfAddress" -> (args?.getOrNull(0) as? String) == SELF_UA
                "toString" -> "RetestGap13SelfAwareZchatPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else ->
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        java.lang.Short.TYPE -> 0.toShort()
                        java.lang.Byte.TYPE -> 0.toByte()
                        java.lang.Double.TYPE -> 0.0
                        java.lang.Float.TYPE -> 0.0f
                        java.lang.Character.TYPE -> ' '
                        else -> null
                    }
            }
        } as ZchatPreferences

    private class HangingSynchronizerProvider : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer = awaitCancellation()

        override fun resetSynchronizer() = Unit
    }

    private class FakeTransactionRepository : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)

        override suspend fun getMemos(transaction: Transaction): List<String> = emptyList()

        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

        override suspend fun getTransactions(): List<Transaction> = emptyList()

        override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()
    }

    private class FakeContactBook : ContactBook {
        override fun addContact(contact: Contact) = Unit

        override fun removeContact(address: String) = Unit

        override fun getContact(address: String): Contact? = null

        override fun getAllContacts(): List<Contact> = emptyList()

        override fun hasContact(address: String): Boolean = false

        override fun updateContactName(address: String, newName: String) = Unit

        override fun clearAll() = Unit
    }
}
