package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
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
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R2-6 (c), p2-3414e39f2fe4, chat half: a first message that was refused before anything was submitted is
 * still a FIRST message when it is retried.
 *
 * The first message to a new contact is a v4 INIT: it carries our address, which is how the recipient learns
 * who wrote and can answer. At 566b866d2 the send asks getOrCreateConversationId, which STORES a new
 * conversation id before the send has been built. When the send is then refused before submission (an
 * Orchard-only wallet, a shortfall), the id stays behind; Retry finds it, treats the conversation as started
 * and sends a REPLY carrying only a hash of our address. The recipient gets a message from nobody it knows.
 *
 * Must hold: the retry (Chat's Retry, or a fresh chat after a reopen) sends a v4 INIT naming our address;
 * an INIT that reached submission is never sent again as an INIT; and nothing here may weaken the P2a rule
 * that a message whose submit started is never paid twice.
 *
 * Drives the real ChatViewModel send and Retry through the real use case and repository (PaymentHarness), with
 * the real conversation-id store ([RealConversationIds]). All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailedFirstMessageRetriedAsInitChatTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticfirstmessagepeer01"
        const val SELF = PaymentHarness.SELF
        const val SHORTFALL = "Insufficient balance (have 0, need 3000)"
    }

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
    fun `a first message refused for a shortfall is retried from Chat as a v4 INIT carrying our address`() =
        runBlocking {
            val conversations = RealConversationIds()
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException(SHORTFALL))
            val vm = chatViewModel(prefs, seam, conversations)

            vm.sendMessage(PEER, "synthetic first hello")
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }
            assertTrue(isInitFrom(firstMemo(seam.proposalUris[0]), SELF), "fixture sanity: the first attempt was an INIT")
            assertEquals(0, seam.submits.get(), "fixture sanity: nothing was submitted")

            assertTrue(vm.retryMessage(PEER, failed.id))
            awaitIdle(vm)

            assertEquals(emptyList<String>(), prefs.unexpectedCalls, "fixture sanity")
            assertEquals(2, seam.proposalUris.size)
            val retried = firstMemo(seam.proposalUris[1])
            assertTrue(
                isInitFrom(retried, SELF),
                "the retried first message is not a v4 INIT naming our address: ${retried.take(40)}…",
            )
            assertEquals(1, seam.submits.get(), "the retry is sent once")
        }

    @Test
    fun `a first message refused because the wallet is Orchard-only is sent as an INIT once Ironwood arrives`() =
        runBlocking {
            val conversations = RealConversationIds()
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val accounts = ChangingAccountDataSource(walletWith(orchard = 500_000, ironwood = 0))
            val vm = chatViewModel(prefs, seam, conversations, accounts)

            vm.sendMessage(PEER, "synthetic first hello")
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }
            assertTrue(seam.proposalUris.isEmpty(), "fixture sanity: the sealed-Orchard refusal comes before any proposal")

            accounts.account.value = walletWith(orchard = 500_000, ironwood = 500_000)
            assertTrue(vm.retryMessage(PEER, failed.id))
            awaitIdle(vm)

            assertEquals(emptyList<String>(), prefs.unexpectedCalls, "fixture sanity")
            val sent = firstMemo(seam.proposalUris.single())
            assertTrue(isInitFrom(sent, SELF), "the first message went out as a REPLY: ${sent.take(40)}…")
            assertEquals(1, seam.submits.get())
        }

    /** The refusal survives a reopen: a new chat screen over the same store still sends the INIT. */
    @Test
    fun `after a refused first message, the next message from a reopened chat is the INIT`() =
        runBlocking {
            val conversations = RealConversationIds()
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException(SHORTFALL))
            val first = chatViewModel(prefs, seam, conversations)
            first.sendMessage(PEER, "synthetic first hello")
            awaitIdle(first)

            val reopened = chatViewModel(prefs, seam, conversations)
            reopened.sendMessage(PEER, "synthetic second try")
            awaitIdle(reopened)

            assertEquals(2, seam.proposalUris.size)
            val sent = firstMemo(seam.proposalUris[1])
            assertTrue(isInitFrom(sent, SELF), "the first message that went out was a REPLY: ${sent.take(40)}…")
        }

    /** Control, green before and after: once an INIT went out, the next message is a REPLY. */
    @Test
    fun `control - an INIT that was sent is never sent again`() =
        runBlocking {
            val conversations = RealConversationIds()
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val vm = chatViewModel(prefs, seam, conversations)

            vm.sendMessage(PEER, "synthetic first hello")
            awaitIdle(vm)
            vm.sendMessage(PEER, "synthetic second message")
            awaitIdle(vm)

            assertEquals(2, seam.submits.get())
            assertTrue(isInitFrom(firstMemo(seam.proposalUris[0]), SELF))
            assertFalse(firstMemo(seam.proposalUris[1]).contains("|INIT|"), "the INIT was sent a second time")
        }

    /**
     * Control (P2a): the first message's submit started and its outcome is unknown. Retry stays refused (never
     * pay twice), and the next message is a REPLY: the INIT may already be on the wire.
     */
    @Test
    fun `control - an INIT whose submit started is never retried and never re-sent`() =
        runBlocking {
            val conversations = RealConversationIds()
            val prefs = PaymentPrefs(PEER)
            val seam =
                PaymentSeam(PEER, firstSubmitOutcome = SubmitResult.SentUnconfirmed(emptyList(), "synthetic refresh failure"))
            val vm = chatViewModel(prefs, seam, conversations)

            vm.sendMessage(PEER, "synthetic first hello")
            awaitIdle(vm)
            val first = PaymentHarness.pendingMessages(vm).first()
            assertFalse(vm.retryMessage(PEER, first.id), "a message that may be on the wire was retried")

            vm.sendMessage(PEER, "synthetic second message")
            awaitIdle(vm)

            assertEquals(2, seam.submits.get())
            assertTrue(isInitFrom(firstMemo(seam.proposalUris[0]), SELF))
            assertFalse(firstMemo(seam.proposalUris[1]).contains("|INIT|"), "the INIT was sent a second time")
        }

    /** The refused send leaves no started conversation behind: no id, or one still marked as needing its INIT. */
    @Test
    fun `a refused first message leaves no conversation that counts as started`() =
        runBlocking {
            val conversations = RealConversationIds()
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException(SHORTFALL))
            val vm = chatViewModel(prefs, seam, conversations)

            vm.sendMessage(PEER, "synthetic first hello")
            awaitIdle(vm)

            val convId = conversations.impl.getConversationId(PEER)
            if (convId != null) {
                val snapshot = conversations.file.snapshot()
                assertNotNull(
                    snapshot.entries.firstOrNull { (key, value) -> key != "peer:$PEER" && key.contains(PEER) && value == convId },
                    "a conversation id was stored for a first message that never went out, with nothing marking " +
                        "its INIT as not sent (keys: ${snapshot.keys.map { it.substringBefore(':') }})",
                )
            }
        }

    // --------------------------------------------------------------- harness

    private suspend fun chatViewModel(
        prefs: PaymentPrefs,
        seam: PaymentSeam,
        conversations: RealConversationIds,
        accounts: ChangingAccountDataSource? = null,
    ): ChatViewModel {
        val vm = paymentViewModel(prefs, seam)
        PaymentHarness.setField(vm, "zchatPreferences", conversations.wrap(prefs.proxy))
        if (accounts != null) {
            val repository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = seam,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ).also { SendHarnessScopes.current().own(it) }
            PaymentHarness.setField(vm, "accountDataSource", accounts)
            PaymentHarness.setField(
                vm,
                "createChunkedMessageProposal",
                CreateChunkedMessageProposalUseCase(
                    keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                    zashiProposalRepository = repository,
                    accountDataSource = accounts,
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                ),
            )
        }
        return vm
    }
}
