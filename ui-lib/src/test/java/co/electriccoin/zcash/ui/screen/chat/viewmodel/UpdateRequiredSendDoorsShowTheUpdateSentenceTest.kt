package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeGuard
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeRequiredException
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.PaymentQuote
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.error
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FirstMessagePlanChat.Companion.restored
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-782 (review of 12 Oct, finding 2) — while the app is in its "update required" state, send doors showed an older
 * "try again" text instead of the update sentence. This class holds two of the four doors and the wording pins; the
 * Shielded attachment is a case of ShieldedAttachmentLinkGoesOnChainTest and the old-balance move a case of
 * MoveOldBalanceVMTest (their rigs are private to those classes).
 *
 * - The first Shielded message to a new contact: the plan check throws the update refusal, and
 *   `firstMessagePlanRefusal` showed FIRST_MESSAGE_PLAN_UNCHECKED ("...Try again in a moment").
 * - The chat payment sheet: `quotePayment` answered `Unavailable(null)`, which the sheet shows as
 *   `payment_quote_unavailable` ("...Try again in a moment").
 *
 * Wanted at both: the existing update sentence (`update_required_refusal`), nothing priced, proposed or paid, no
 * question asked. The sentence itself must not invite a second payment or a retry, in English or Spanish. The real
 * ChatViewModel send path over the shared fixtures; every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRequiredSendDoorsShowTheUpdateSentenceTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER_FIRST = "usyntheticzc782firstmessage1"
        const val PEER_PAY = "usyntheticzc782paymentsheet1"
        const val TYPED = "synthetic first words to a new contact"
    }

    @Before
    fun setUp() {
        NetworkUpgradeTestApi.resetIfPresent()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        NetworkUpgradeTestApi.resetIfPresent()
    }

    private fun latchUpdateRequired() {
        NetworkUpgradeGuard.onSubmitResult(SubmitResult.Failure(emptyList(), 0, "incorrect consensus branch id"))
        assertTrue(NetworkUpgradeGuard.refusal() != null, "fixture: the app is in its update-required state")
    }

    @Test
    fun `the first Shielded message to a new contact shows the update sentence and pays nothing`() =
        runBlocking {
            val chat = FirstMessagePlanChat(PEER_FIRST)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck(throwing = NetworkUpgradeRequiredException()))

            chat.typeAndSend(vm, TYPED)

            assertFalse(chat.asked(vm), "the user was asked to pay in the update-required state")
            assertEquals(0, chat.proposals(), "a refused first message asked for a payment")
            assertFalse(chat.owedMarkWritten(), "the owed-KEX mark was written")
            assertFalse(chat.keyCreated(), "our key for the contact was created")
            assertEquals(NetworkUpgradeTestApi.MESSAGE, error(vm), "the first-message door must show the update sentence")
            assertEquals(PEER_FIRST to TYPED, restored(vm), "the typed text was not handed back to the input")
        }

    @Test
    fun `the chat payment sheet shows the update sentence and prices nothing`() =
        runBlocking {
            val seam = PaymentSeam(PEER_PAY)
            val vm = paymentViewModel(PaymentPrefs(PEER_PAY), seam)
            PaymentHarness.setField(vm, "accountDataSource", Zc101FakeAccountDataSource(syntheticZashiAccount()))
            latchUpdateRequired()

            val quote = vm.quotePayment(PEER_PAY, 1_000L, "")

            assertEquals(
                PaymentQuote.Unavailable(NetworkUpgradeTestApi.MESSAGE),
                quote,
                "the payment sheet must give the update sentence as the reason, not the 'try again' text",
            )
            assertEquals(0, seam.proposalUris.size, "a quote builds no proposal")
            assertEquals(0, seam.submits.get(), "nothing is paid")
        }

    @Test
    fun `the update sentence has a resource and never invites a retry or a second payment`() {
        assertEquals(R.string.update_required_refusal, ChatSendCopy.localizedResource(NetworkUpgradeTestApi.MESSAGE))
        val banned = listOf("try again", "retry", "send again", "pay again", "inténtalo", "reintentar", "de nuevo", "otra vez")
        listOf("values", "values-es").forEach { folder ->
            val xml = File(repoRoot, "ui-lib/src/main/res/ui/update/$folder/update_required_strings.xml").readText()
            val sentence =
                Regex("""<string name="update_required_refusal">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .find(xml)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            assertTrue(sentence.isNotBlank(), "$folder has no update_required_refusal")
            banned.forEach { phrase ->
                assertFalse(sentence.contains(phrase, ignoreCase = true), "$folder: the update sentence says '$phrase'")
            }
        }
    }

    @Test
    fun `the old-balance sheet shows the update title and sentence for its update block`() {
        val src =
            SourceScan.stripComments(
                File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/moveoldbalance/MoveOldBalanceView.kt").readText(),
            )
        assertTrue(
            Regex(
                """MoveOldBalanceBlock\.UpdateRequired\s*->\s*stringRes\(R\.string\.update_required_title\)\s*to\s*""" +
                    """stringRes\(R\.string\.update_required_refusal\)""",
            ).containsMatchIn(src),
            "the old-balance sheet must show update_required_title and update_required_refusal for the update block",
        )
    }

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "repo root not found above the test working dir" }
    }
}
