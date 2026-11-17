package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeSeam
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T3-extra (b) (fix-all 2.13.0, train T3; T2 phone finding 10 and the orchestrator's money spec) — "never pay one of
 * our own addresses" is enforced path by path at 02cc65cdb: the owed first-message KEX (T1b-1) and the automatic-setup
 * gate (T2-extra) check ZchatPreferences.isSelfAddress, but the one door every on-chain chat memo passes through,
 * CreateChunkedMessageProposalUseCase, never asks whose address it pays. Anything a user agreed to earlier and that is
 * paid later outside those two paths (a queued message, a confirmed control, a payment, a group memo) pays our own
 * address like any contact's.
 *
 * Must hold: the chokepoint refuses a send whose destination is one of our own addresses before a proposal is built,
 * so nothing is stored or paid; the refusal proves nothing was sent (memoFailureProvesNothingSent) and reads as its own
 * text, never the caller's "Failed to …"; the app wires the self registry into the chokepoint. Control: a contact's
 * send still reaches the proposal.
 *
 * Every address is synthetic.
 */
class ChatSendNeverPaysOwnAddressTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val OWN = "usynthetict3extraownaddr0001"
        const val CONTACT = "usynthetict3extracontact001"
        const val SENDER = "usynthetict3extrasender0001"
        const val CONV = "T3XA2345"
    }

    @Before
    fun setUp() = sendScopes.open()

    @After
    fun tearDown() = sendScopes.close()

    @Test
    fun `a chat message to one of our own addresses is refused before a proposal is built`() =
        runBlocking {
            val seam = HandshakeSeam(OWN)
            // Built outside the runCatching: a build without the own-address check must fail here, not pass below.
            val door = chokepoint(seam)
            val refusal =
                runCatching {
                    door(
                        destinationAddress = OWN,
                        senderAddress = SENDER,
                        message = "synthetic message",
                        isFirstMessage = false,
                        directSubmit = true,
                        skipNavigation = true,
                        conversationId = CONV,
                    )
                }.exceptionOrNull()

            assertEquals("a proposal was built for a send to one of our own addresses", 0, seam.proposalRequests.get())
            assertEquals(0, seam.submits.get())
            assertTrue("the send must be refused, not completed", refusal != null)
            assertTrue(
                "the refusal must prove nothing was sent, so no caller keeps it as maybe sent",
                memoFailureProvesNothingSent(refusal!!, submitStarted = false),
            )
            assertEquals(
                "the user must read why, not the caller's fallback",
                OwnAddressChokepointProbe.copy("OWN_ADDRESS_NOT_PAID"),
                refusal.toZchatUserMessage("synthetic fallback"),
            )
        }

    @Test
    fun `an automatic setup memo to one of our own addresses is refused the same way`() =
        runBlocking {
            val seam = HandshakeSeam(OWN)
            // Built outside the runCatching: a build without the own-address check must fail here, not pass below.
            val door = chokepoint(seam)
            val refusal =
                runCatching {
                    door(
                        destinationAddress = OWN,
                        senderAddress = SENDER,
                        message = "ZBOOT|synthetic",
                        isFirstMessage = false,
                        amountPerOutput = Zatoshi(1_000L),
                        directSubmit = true,
                        skipNavigation = true,
                        rawMemo = true,
                    )
                }.exceptionOrNull()

            assertEquals("a stored permission paid a setup memo to one of our own addresses", 0, seam.proposalRequests.get())
            assertTrue(refusal != null && memoFailureProvesNothingSent(refusal, submitStarted = false))
        }

    @Test
    fun `a chat payment to one of our own addresses is refused`() =
        runBlocking {
            val seam = HandshakeSeam(OWN)
            // Built outside the runCatching: a build without the own-address check must fail here, not pass below.
            val door = chokepoint(seam)
            val refusal =
                runCatching {
                    door(
                        destinationAddress = OWN,
                        senderAddress = SENDER,
                        message = "synthetic payment",
                        isFirstMessage = false,
                        amountPerOutput = Zatoshi(17_000L),
                        directSubmit = true,
                        skipNavigation = true,
                        conversationId = CONV,
                    )
                }.exceptionOrNull()

            assertEquals("a chat payment to one of our own addresses was built", 0, seam.proposalRequests.get())
            assertTrue(refusal != null)
        }

    /** Control, green before and after: a contact's send reaches the proposal (the seam then stops it unpaid). */
    @Test
    fun `control - a send to a contact reaches the proposal`() =
        runBlocking {
            val seam = HandshakeSeam(CONTACT, failFirstProposalWith = IllegalStateException("synthetic stop"))
            // With the own-address check where this build has it, and the chokepoint as it is where it has not.
            val door =
                runCatching { chokepoint(seam) }.getOrElse {
                    val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
                    CreateChunkedMessageProposalUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = accounts,
                                proposalDataSource = seam,
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ).also(sendScopes::own),
                        accountDataSource = accounts,
                        navigationRouter = Zc101RecordingNavigationRouter(),
                        biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                    )
                }
            runCatching {
                door(
                    destinationAddress = CONTACT,
                    senderAddress = SENDER,
                    message = "synthetic message",
                    isFirstMessage = false,
                    directSubmit = true,
                    skipNavigation = true,
                    conversationId = CONV,
                )
            }

            assertEquals(1, seam.proposalRequests.get())
            assertEquals(0, seam.submits.get())
        }

    @Test
    fun `the app wires the self registry into the chat send chokepoint`() {
        val module = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/di/UseCaseModule.kt")

        assertFalse(
            "factoryOf(::CreateChunkedMessageProposalUseCase) passes no own-address check",
            module.contains("factoryOf(::CreateChunkedMessageProposalUseCase)"),
        )
        val wiring =
            Regex("""CreateChunkedMessageProposalUseCase\((.*?)\n\s*\)""", RegexOption.DOT_MATCHES_ALL)
                .find(module)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        assertTrue(
            "the chokepoint must be built with ZchatPreferences.isSelfAddress as its own-address check: $wiring",
            Regex("""isOwnAddress\s*=\s*.*isSelfAddress""").containsMatchIn(wiring),
        )
    }

    private suspend fun chokepoint(seam: HandshakeSeam): CreateChunkedMessageProposalUseCase =
        OwnAddressChokepointProbe.useCase(
            accounts = Zc101FakeAccountDataSource(syntheticZashiAccount()),
            proposals = seam,
            biometrics = HandshakeHarness.ApprovingBiometricRepository,
            isOwnAddress = { it == OWN },
            onRepository = sendScopes::own,
        )
}
