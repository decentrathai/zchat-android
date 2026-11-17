package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.zc538FundedAccount
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
import co.electriccoin.zcash.ui.common.model.PlanProbe
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ZC-538: paid memo builders and price probes cannot ask for a proposal while the guard is latched. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPaidActionsAreRefusedBeforeAnyProposalWhileUpdateRequiredTest {
    private val scopes = SendHarnessScopes()

    @Before
    fun setUp() {
        NetworkUpgradeTestApi.resetIfPresent()
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scopes.close()
        Dispatchers.resetMain()
        NetworkUpgradeTestApi.resetIfPresent()
    }

    private class ReachedRepository : RuntimeException("synthetic proposal/probe reached")

    private class CountingData(
        private val backstop: Boolean = false,
    ) : ProposalDataSource by Zc101RecordingProposalDataSource() {
        var proposals = 0
        var probes = 0

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            proposals++
            if (!backstop) throw ReachedRepository()
            return syntheticZip321Proposal(PEER, 1_000, "synthetic")
        }

        override suspend fun probeZip321Fee(
            account: WalletAccount,
            zip321Uri: String,
        ): FeeProbe {
            probes++
            throw ReachedRepository()
        }

        override suspend fun probeZip321Plan(
            account: WalletAccount,
            zip321Uri: String,
        ): PlanProbe {
            probes++
            throw ReachedRepository()
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult =
            SubmitResult.Failure(emptyList(), 0, NetworkUpgradeTestApi.MESSAGE)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult = submitTransaction(proposal, usk)
    }

    private suspend fun useCase(data: CountingData): CreateChunkedMessageProposalUseCase {
        val accounts = Zc101FakeAccountDataSource(zc538FundedAccount())
        val repository =
            ZashiProposalRepositoryImpl(accounts, data, Zc101FakeZashiSpendingKeyDataSource()).also(scopes::own)
        return CreateChunkedMessageProposalUseCase(
            Zc101FakeKeystoneProposalRepository(),
            repository,
            accounts,
            Zc101RecordingNavigationRouter(),
            Zc101SuspendingBiometricRepository(),
        )
    }

    private suspend fun actions(use: CreateChunkedMessageProposalUseCase): List<Pair<String, suspend () -> Any?>> =
        listOf(
            "invoke" to
                suspend {
                    use(
                        PEER,
                        SELF,
                        "synthetic memo",
                        false,
                        skipNavigation = true,
                        directSubmit = true,
                        rawMemo = true,
                    )
                },
            "priceMessage" to suspend { use.priceMessage(PEER, 1, Zatoshi(1_000)) },
            "probeFirstMessagePlan" to suspend { use.probeFirstMessagePlan(PEER, 1, Zatoshi(1_000)) },
            "priceSendAll" to suspend { use.priceSendAll(PEER, 1, Zatoshi(0)) },
        )

    @Test
    fun control_every_paid_action_reaches_its_proposal_or_probe_when_compatible() =
        runTest {
            val data = CountingData()
            for ((name, call) in actions(useCase(data))) {
                val before = data.proposals + data.probes
                runCatching { call() }
                assertTrue(data.proposals + data.probes > before, "$name fixture did not reach its repository")
            }
        }

    @Test
    fun paid_action_and_all_three_price_probes_refuse_before_repository_access() =
        runTest {
            val data = CountingData()
            val actions = actions(useCase(data))
            NetworkUpgradeTestApi.latch()
            for ((name, call) in actions) {
                NetworkUpgradeTestApi.assertRefusal(runCatching { call() }.exceptionOrNull())
                assertEquals(0, data.proposals, "$name built a proposal")
                assertEquals(0, data.probes, "$name asked for a price")
            }
        }

    @Test
    fun update_refusal_is_definite_nothing_sent_and_never_a_transient_block_wait() {
        val error = NetworkUpgradeTestApi.exception()
        assertTrue(memoFailureProvesNothingSent(error, false))
        assertFalse(isTransientWait(error))
    }

    @Test
    fun backstop_empty_id_failure_reaches_chat_as_nothing_stored_and_the_update_refusal_text() =
        runTest {
            val use = useCase(CountingData(backstop = true))
            val error = assertFailsWith<SubmitRejectedException> { actions(use).first().second() }
            assertTrue(error.nothingStored)
            assertTrue(error.txIds.isEmpty())
            assertTrue(memoFailureProvesNothingSent(error, true))
            assertEquals(NetworkUpgradeTestApi.MESSAGE, error.toZchatUserMessage("generic retry"))
        }

    @Test
    fun refusal_order_keeps_keystone_and_own_address_before_update_and_update_before_orchard() {
        val source =
            SourceScan.stripComments(
                ChatResourceText.source(
                    "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/usecase/CreateChunkedMessageProposalUseCase.kt"
                )
            )
        val block = SourceScan.braceBlock(source, "suspend operator fun invoke(")
        val keystone = block.indexOf("throw KeystoneChatSendRefusedException()")
        val own = block.indexOf("throw OwnAddressChatSendRefusedException()")
        val update = block.indexOf("NetworkUpgradeGuard.refusal()")
        val orchard = block.indexOf("orchardSealedRefusal(")
        assertTrue(
            keystone >= 0 && own > keystone && update > own && orchard > update,
            "existing refusal order changed or update guard is missing",
        )
    }

    private companion object {
        // Established harmless fixtures used by SendQuoteMustMatchProposalTest; pass the real ZIP321 validation.
        const val PEER = "usyntheticsendquotepeer00001"
        const val SELF = "usyntheticsendquoteself00001"
    }
}
