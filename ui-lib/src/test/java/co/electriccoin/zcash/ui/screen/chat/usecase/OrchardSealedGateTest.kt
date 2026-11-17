package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * RED pin for the Orchard-sealed gate at the single on-chain chokepoint.
 *
 * Since NU6.3 an Orchard Action may only pay its own address (ZIP 258, enableCrossAddress = 0). A
 * wallet whose shielded value is entirely in Orchard therefore cannot reach a peer at all — and the
 * SDK's input selector, which draws on Orchard LAST but does draw on it, would otherwise fund the
 * attempt as a turnstile crossing whose amount is public (ZIP 318). The product decision is to
 * refuse and explain rather than warn or cross silently.
 *
 * The refusal lives in [CreateChunkedMessageProposalUseCase] because it is the one place every
 * on-chain memo passes through: user messages, reactions, receipts, and the automatic
 * KEX/KEXACK/ZBOOT handshake memos alike. Fourteen senders in the ViewModel reach it; gating only
 * `sendMessage` would leave thirteen open.
 *
 * The exception is deliberately NOT an InsufficientFundsException: the handshake retry loop treats
 * a transient insufficient-funds error as "wait for the next block", and a sealed pool does not
 * resolve with time. Automatic callers catch and skip; user sends surface it.
 */
class OrchardSealedGateTest {
    private fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

    private val zero = balance(0)

    /** Ironwood value that has landed but not yet confirmed: total > 0, available == 0. */
    private fun pending(valuePending: Long) = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(valuePending))

    private suspend fun account(orchard: Long, ironwood: Long) = account(balance(orchard), balance(ironwood))

    private suspend fun account(orchard: WalletBalance, ironwood: WalletBalance) =
        ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), orchard, ironwood),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), zero),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )

    private suspend fun useCase(orchard: Long, ironwood: Long) = useCase(balance(orchard), balance(ironwood))

    private suspend fun useCase(orchard: WalletBalance, ironwood: WalletBalance): CreateChunkedMessageProposalUseCase {
        val accounts = Zc101FakeAccountDataSource(account(orchard, ironwood))
        val dataSource = Zc101RecordingProposalDataSource()
        return CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository =
                KeystoneProposalRepositoryImpl(accountDataSource = accounts, proposalDataSource = dataSource),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = dataSource,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ),
            accountDataSource = accounts,
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = Zc101SuspendingBiometricRepository(),
        )
    }

    private val peer = "usynthetic-peer"
    private val self = "usynthetic-self"

    /** The HONOR test device: 135_000 zatoshi in Orchard, nothing in Ironwood. */
    @Test
    fun orchard_only_user_message_is_refused_with_the_sealed_pool_error() =
        runTest {
            val e =
                assertFailsWith<OrchardSealedException> {
                    useCase(orchard = 135_000, ironwood = 0)(
                        destinationAddress = peer,
                        senderAddress = self,
                        message = "hello",
                        isFirstMessage = true,
                        skipNavigation = true,
                        directSubmit = true,
                        conversationId = "ABCD1234",
                    )
                }
            assertEquals(Zatoshi(135_000), e.orchardAvailable)
        }

    /** Automatic handshake memos take the same gate — they must skip, not cross the turnstile. */
    @Test
    fun orchard_only_raw_handshake_memo_is_refused_too() =
        runTest {
            assertFailsWith<OrchardSealedException> {
                useCase(orchard = 811_000, ironwood = 0)(
                    destinationAddress = peer,
                    senderAddress = self,
                    message = "ZBOOT|…",
                    isFirstMessage = false,
                    skipNavigation = true,
                    directSubmit = true,
                    rawMemo = true,
                )
            }
        }

    /** With Ironwood value present the gate must stay out of the way. */
    @Test
    fun ironwood_funded_send_is_not_refused_by_the_gate() =
        runTest {
            val thrown =
                runCatching {
                    useCase(orchard = 0, ironwood = 811_000)(
                        destinationAddress = peer,
                        senderAddress = self,
                        message = "hello",
                        isFirstMessage = true,
                        skipNavigation = true,
                        directSubmit = true,
                        conversationId = "ABCD1234",
                    )
                }.exceptionOrNull()
            // Downstream fakes may fail for their own reasons; the one thing that must not happen is
            // the sealed-pool refusal.
            assertNotEquals(OrchardSealedException::class, thrown?.let { it::class })
        }

    /** A split wallet has Ironwood to spend from; the gate must not fire on the Orchard remainder. */
    @Test
    fun split_wallet_is_not_refused() =
        runTest {
            val thrown =
                runCatching {
                    useCase(orchard = 135_000, ironwood = 676_000)(
                        destinationAddress = peer,
                        senderAddress = self,
                        message = "hello",
                        isFirstMessage = true,
                        skipNavigation = true,
                        directSubmit = true,
                        conversationId = "ABCD1234",
                    )
                }.exceptionOrNull()
            assertNotEquals(OrchardSealedException::class, thrown?.let { it::class })
        }

    /**
     * Mid-crossing, the dangerous window: the first Ironwood transfer has landed but is still
     * confirming (total > 0, available == 0) while Orchard is still spendable. A gate keyed on the
     * Ironwood TOTAL lets this through, and the SDK's input selector then either draws on Orchard —
     * a turnstile crossing whose amount is public (ZIP 318) — or fails with an "insufficient funds"
     * message that contradicts the healthy balance the widget shows. Spendability is what matters.
     */
    @Test
    fun ironwood_pending_only_is_still_refused_until_it_confirms() =
        runTest {
            val e =
                assertFailsWith<OrchardSealedException> {
                    useCase(orchard = balance(135_000), ironwood = pending(676_000))(
                        destinationAddress = peer,
                        senderAddress = self,
                        message = "hello",
                        isFirstMessage = true,
                        skipNavigation = true,
                        directSubmit = true,
                        conversationId = "ABCD1234",
                    )
                }
            assertEquals(Zatoshi(135_000), e.orchardAvailable)
        }
}
