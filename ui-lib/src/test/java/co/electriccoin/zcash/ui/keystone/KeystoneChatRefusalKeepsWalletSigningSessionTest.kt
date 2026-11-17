package co.electriccoin.zcash.ui.keystone

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K0-2 (fix-all 2.13.0, train T1) — a chat send refused on a Keystone account never touches the Keystone signing
 * session the Wallet tab may be using.
 *
 * The session (proposal + PCZT) is one process-wide cell. A Wallet → Send is half way through: its proposal and PCZT
 * are in the cell and the user is signing on the device. Then a chat send runs on the same Keystone account:
 * - the background shape (directSubmit + skipNavigation: receipts, KEX acks, group memos) is already refused before
 *   the try at HEAD (ZC-102), so the session survives (control);
 * - the Pay shape (directSubmit, skipNavigation = false) and the review shape (directSubmit = false) are NOT refused at
 *   HEAD: they build a new Keystone proposal and PCZT over the Wallet send's (and a failure then clears the cell), so
 *   the user's signature can no longer be submitted.
 *
 * Wanted: every chat shape is refused before the try with the same ZC-102 exception, so the Wallet send's proposal
 * and PCZT are the same objects afterwards and clear() is never called.
 */
class KeystoneChatRefusalKeepsWalletSigningSessionTest {
    @Test
    fun `control - a background chat send leaves the Wallet signing session alone`() =
        runBlocking { assertSessionKept(directSubmit = true, skipNavigation = true) }

    @Test
    fun `a chat payment leaves the Wallet signing session alone`() =
        runBlocking {
            assertSessionKept(directSubmit = true, skipNavigation = false)
        }

    @Test
    fun `a chat send for review leaves the Wallet signing session alone`() =
        runBlocking { assertSessionKept(directSubmit = false, skipNavigation = false) }

    private suspend fun assertSessionKept(
        directSubmit: Boolean,
        skipNavigation: Boolean,
    ) {
        val accounts = TwoAccountDataSource.keystoneSelected()
        val keystone = RecordingKeystoneProposalRepository().apply { holdWalletSession() }
        val walletProposal = keystone.sessionProposal()
        val walletPczt = keystone.sessionPczt
        val router = RouteRecordingNavigationRouter()
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = keystone,
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = Zc101RecordingProposalDataSource(),
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ),
                accountDataSource = accounts,
                navigationRouter = router,
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            )

        val thrown =
            runCatching {
                useCase(
                    destinationAddress = "usynthetic-k0-chat-peer",
                    senderAddress = "usynthetic-k0-keystone-self",
                    message = "synthetic k0 chat send",
                    isFirstMessage = false,
                    amountPerOutput = Zatoshi(5_000_000),
                    directSubmit = directSubmit,
                    skipNavigation = skipNavigation,
                    rawMemo = true,
                )
            }.exceptionOrNull()

        val shape = "directSubmit=$directSubmit, skipNavigation=$skipNavigation"
        assertEquals("[$shape] the chat send touched the Keystone signing session", emptyList<String>(), keystone.calls)
        assertSame("[$shape] the Wallet send's proposal was replaced", walletProposal, keystone.sessionProposal())
        assertSame("[$shape] the Wallet send's PCZT was replaced", walletPczt, keystone.sessionPczt)
        assertTrue("[$shape] the chat send opened a screen: ${router.commands}", router.commands.isEmpty())
        assertTrue(
            "[$shape] the chat send must be refused with the ZC-102 exception, got $thrown",
            thrown is UnsupportedOperationException &&
                thrown.message == CreateChunkedMessageProposalUseCase.KEYSTONE_AUTO_SEND_REFUSED_MESSAGE,
        )
    }
}
