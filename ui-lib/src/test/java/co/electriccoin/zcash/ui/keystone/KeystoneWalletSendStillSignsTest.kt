package co.electriccoin.zcash.ui.keystone

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.ProcessSwapTransactionUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeMetadataRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * K0 (fix-all 2.13.0, train T1) — control, green before and after: closing Keystone chat signing must not close the
 * Wallet tab. A Keystone account connected earlier still sends from Wallet → Send along the upstream path: the
 * proposal and its PCZT are built, the Review screen opens, and after the confirm the Keystone signing QR opens
 * (SignKeystoneTransaction). Nothing is submitted without the device's signature. The signature scan-back itself
 * can't be run without a Keystone 3 Pro.
 */
class KeystoneWalletSendStillSignsTest {
    @Test
    fun `Wallet Send on a Keystone account builds the proposal and PCZT and opens Review`() =
        runBlocking {
            val accounts = TwoAccountDataSource.keystoneSelected()
            val keystone = RecordingKeystoneProposalRepository()
            val router = RouteRecordingNavigationRouter()
            val useCase =
                CreateProposalUseCase(
                    keystoneProposalRepository = keystone,
                    zashiProposalRepository = zashiRepository(accounts),
                    accountDataSource = accounts,
                    navigationRouter = router,
                )

            useCase(
                ZecSend(
                    destination = WalletAddress.Unified.new("usynthetic-k0-wallet-send-peer"),
                    amount = Zatoshi(50_000),
                    memo = Memo(""),
                    proposal = null,
                ),
                floor = false,
            )

            assertEquals(listOf("createProposal", "createPCZTFromProposal"), keystone.calls)
            assertEquals(listOf("forward(ReviewTransactionArgs)"), router.commands)
        }

    @Test
    fun `confirming the Review on a Keystone account opens the signing QR and submits nothing`() =
        runBlocking {
            val accounts = TwoAccountDataSource.keystoneSelected()
            val keystone = RecordingKeystoneProposalRepository().apply { holdWalletSession() }
            val router = RouteRecordingNavigationRouter()
            val metadata = Zc101FakeMetadataRepository()
            val dataSource = Zc101RecordingProposalDataSource()
            val useCase =
                SubmitProposalUseCase(
                    navigationRouter = router,
                    accountDataSource = accounts,
                    zashiProposalRepository = zashiRepository(accounts, dataSource),
                    keystoneProposalRepository = keystone,
                    biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                    swapRepository = Zc101FakeSwapRepository(),
                    metadataRepository = metadata,
                    processSwapTransaction =
                        ProcessSwapTransactionUseCase(metadataRepository = metadata, swapDataSource = Zc101FakeSwapDataSource()),
                    prefillSend = PrefillSendUseCase(),
                )

            useCase(checkNotNull(keystone.sessionProposal()))

            assertEquals(listOf("replace(SignKeystoneTransactionArgs)"), router.commands)
            assertEquals("nothing may be submitted before the device signs", emptyList<String>(), keystone.calls)
            assertEquals(emptyList<Any>(), dataSource.submittedProposals)
        }

    private fun zashiRepository(
        accounts: TwoAccountDataSource,
        dataSource: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) = ZashiProposalRepositoryImpl(
        accountDataSource = accounts,
        proposalDataSource = dataSource,
        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
    )
}
