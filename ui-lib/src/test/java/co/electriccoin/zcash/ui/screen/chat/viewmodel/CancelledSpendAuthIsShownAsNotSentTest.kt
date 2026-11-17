package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertNotNull

/**
 * C4 (HONOR phone check 3b, round 7 sidecar "Surprising" 2) — a message above dust whose PIN prompt the user
 * CANCELS is shown as "Failed to send message" with Retry, and deleting that row says "The transaction remains
 * on the Zcash blockchain and will reappear if you restore from seed", although nothing left the phone.
 *
 * The #248 spend-auth gate throws `SpendAuthCancelledException` BEFORE `onSubmitStarting` and before the SDK is
 * asked to store anything (the phone's wallet DB showed 48 transactions before and after). doSendMessage maps it
 * through `toZchatUserMessage("Failed to send message")`, which does not know the type, so the row's reason and
 * the toast read as a failure, not as "not sent, you cancelled". The delete dialog has one hard-coded English body
 * for every row.
 *
 * Wanted:
 * - the row says it was not sent because the check was not completed (and a Spanish phone reads it in Spanish);
 * - the delete dialog says, for a row that provably never reached the submit, that no transaction was made;
 *   every other row keeps the blockchain wording.
 *
 * All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelledSpendAuthIsShownAsNotSentTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a message whose PIN prompt was cancelled says it was not sent, not that it failed`() =
        runBlocking<Unit> {
            val (vm, seam, prefs) = sendAboveDust(CancellingBiometricRepository)

            assertEquals("fixture: nothing may reach the submit", 0, seam.submits.get())
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            val row = PaymentHarness.pendingMessages(vm).single()
            assertEquals("fixture: the row stays with Retry", MessageStatus.FAILED, row.status)
            val reason = assertNotNull(row.failureReason, "the row must say why it was not sent")
            assertNotEquals("a cancelled PIN prompt is shown as a failure", GENERIC_FAILURE, reason)
            assertTrue("the reason must say the message was not sent: $reason", reason.startsWith("Not sent"))
            assertNotNull(ChatSendCopy.localizedResource(reason), "a Spanish phone shows this reason in English: $reason")
            assertEquals("the saved row must keep the same reason", reason, prefs.pending[row.id]?.failureReason)
            val toast = PaymentHarness.sendMessageState(vm)
            assertTrue(
                "the toast must carry the same reason: $toast",
                toast is SendMessageState.Error && toast.message == reason,
            )
        }

    @Test
    fun `a message whose PIN check failed says it was not sent`() =
        runBlocking<Unit> {
            val (vm, seam, _) = sendAboveDust(FailingBiometricRepository)

            assertEquals(0, seam.submits.get())
            val reason = PaymentHarness.pendingMessages(vm).single().failureReason
            assertNotEquals("a failed PIN check is shown as a generic failure", GENERIC_FAILURE, reason)
            assertNotNull(ChatSendCopy.localizedResource(reason.orEmpty()))
        }

    @Test
    fun `deleting a row that never reached the submit does not say its transaction stays on the blockchain`() =
        runBlocking<Unit> {
            val (vm, _, _) = sendAboveDust(CancellingBiometricRepository)
            val row = PaymentHarness.pendingMessages(vm).single()

            assertEquals(
                "the delete dialog for a message that never left the phone says its transaction stays on the chain",
                "chat_delete_message_body_not_sent",
                deleteBodyName(row),
            )
        }

    /** Control: every row that may be on the chain keeps the blockchain wording. */
    @Test
    fun `rows that may be on the chain keep the blockchain wording`() {
        val failedNeverSubmitted =
            ChatMessage(
                id = "pending_synthetic",
                txId = null,
                text = "synthetic",
                timestamp = Instant.EPOCH,
                isOutgoing = true,
                peerAddress = PEER,
                status = MessageStatus.FAILED,
            )
        assertEquals("chat_delete_message_body_not_sent", deleteBodyName(failedNeverSubmitted))
        // Round 8 D8: a failed row whose submit started may have been sent, and may not: it gets its own body.
        for ((label, row) in listOf(
            "may have been sent" to failedNeverSubmitted.copy(submitStartedAtMillis = 1L),
            "stored transaction ids" to failedNeverSubmitted.copy(submittedTxIds = listOf("synthetic-T")),
        )) {
            assertEquals("$label: a row that may be on the chain", "chat_delete_message_body_maybe_sent", deleteBodyName(row))
        }
        for ((label, row) in listOf(
            "sent" to failedNeverSubmitted.copy(status = MessageStatus.SENT),
            "sending" to failedNeverSubmitted.copy(status = MessageStatus.SENDING, isPending = true),
            "incoming" to failedNeverSubmitted.copy(isOutgoing = false, status = MessageStatus.SENT),
        )) {
            assertEquals("$label: a row that may be on the chain", "chat_delete_message_body", deleteBodyName(row))
        }
    }

    @Test
    fun `the delete dialog is worded per row and in the phone's language`() {
        val detail = File(repoRoot(), "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt").readText()
        assertTrue("the delete dialog still hard-codes one English body", "The transaction remains on the" !in detail)
        assertTrue("the delete dialog does not choose its body per row", "deleteConfirmBodyFor(" in detail)
    }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    private suspend fun sendAboveDust(biometrics: BiometricRepository): Triple<ChatViewModel, PaymentSeam, PaymentPrefs> {
        val prefs = PaymentPrefs(PEER)
        val seam = PaymentSeam(PEER)
        val vm = paymentViewModel(prefs, seam)
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        PaymentHarness.setField(
            vm,
            "createChunkedMessageProposal",
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
                biometricRepository = biometrics,
            ),
        )
        // 5,000 zatoshi per output: above dust, so the #248 spend-auth prompt is shown (HONOR check 3b).
        vm.sendMessage(PEER, "synthetic above-dust hello", ABOVE_DUST)
        awaitIdle(vm)
        return Triple(vm, seam, prefs)
    }

    private fun deleteBodyName(row: ChatMessage): String {
        val method =
            ChatSendCopy::class.java.methods.firstOrNull { it.name == "deleteConfirmBodyFor" }
                ?: throw AssertionError("ChatSendCopy.deleteConfirmBodyFor(message) is missing")
        val id = method.invoke(ChatSendCopy, row) as Int
        return R.string::class.java.fields
            .firstOrNull { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }
            ?.name
            ?: throw AssertionError("no string resource with id $id")
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        return assertNotNull(dir, "repo root not found above the test working dir")
    }

    /** The user dismisses the system PIN / biometric prompt (Back, as on the HONOR). */
    private object CancellingBiometricRepository : BiometricRepository {
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest): Unit = throw BiometricsCancelledException()
    }

    /** The PIN or biometric check fails. */
    private object FailingBiometricRepository : BiometricRepository {
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest): Unit = throw BiometricsFailureException()
    }

    private companion object {
        const val PEER = "usyntheticpincancelpeer00001"
        const val ABOVE_DUST = 5_000L
        const val GENERIC_FAILURE = "Failed to send message"
    }
}
