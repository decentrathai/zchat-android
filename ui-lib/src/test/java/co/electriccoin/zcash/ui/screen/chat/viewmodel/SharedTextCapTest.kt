package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B9, SHARED-TEXT-CAP (found by audit, PIN audit RECOMMENDATION section 4.2): text shared into ZChat skips the
 * 5,000-character composer cap.
 *
 * AndroidSharePicker hands the shared text to ChatViewModel.saveDraft, which stores it whole; the composer then
 * starts from that draft, and the 5,000-character cap lives only in the composer's onValueChange. Nothing on the
 * send path checks the length, so one tap on Send turns a 200,000-character share into a transaction of about 400
 * memo outputs (the only hard limit is ZMSGProtocol's 1,000 parts) and pays a dust output plus a ZIP 317 action
 * for each.
 *
 * Wanted: the stored draft is capped at 5,000 characters, and the send itself never pays for more memo outputs
 * than a 5,000-character message needs, whatever text reaches it (a draft stored by an older version, any caller).
 *
 * Drives the real ChatViewModel through the real use case and repository over the real conversation-id store and
 * a recording proposal seam (the FirstMessageQuoteMatchesSendTest harness). All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedTextCapTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticsharedtextcap00001"
        val SELF = "u1" + "q".repeat(211)
        const val LIMIT = 5000
        val SHARED: String = "x".repeat(200_000)
        val AT_LIMIT: String = "x".repeat(LIMIT)
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a shared 200,000-character text is stored as a 5,000-character draft`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            vm.saveDraft(PEER, SHARED)

            val stored = chat.drafts.single()
            assertEquals(LIMIT, stored.length, "the shared text was stored whole as the chat's draft")
            assertEquals(SHARED.take(LIMIT), stored)
        }

    @Test
    fun `control - a short draft is stored as typed`() =
        runBlocking {
            val chat = Chat()
            val vm = chat.viewModel()

            vm.saveDraft(PEER, "hola")

            assertEquals(listOf("hola"), chat.drafts)
        }

    @Test
    fun `a 200,000-character first message pays for no more memos than a 5,000-character one`() =
        runBlocking {
            val oracle = memosOfFirstMessage(AT_LIMIT)
            assertTrue(oracle > 1, "fixture sanity: a 5,000-character message is chunked ($oracle memos)")

            val sent = memosOfFirstMessage(SHARED)

            assertEquals(oracle, sent, "one tap paid for $sent memo outputs; a 5,000-character message needs $oracle")
        }

    @Test
    fun `the first-message prompt quotes no more than a 5,000-character message`() =
        runBlocking {
            val oracle = quotedFirstMessage(AT_LIMIT)
            val quoted = quotedFirstMessage(SHARED)
            assertEquals(oracle, quoted, "the prompt quoted $quoted zatoshi; a 5,000-character message costs $oracle")
        }

    @Test
    fun `a 200,000-character later message pays for no more memos than a 5,000-character one`() =
        runBlocking {
            val oracle = memosOfLaterMessage(AT_LIMIT)
            assertTrue(oracle > 1, "fixture sanity: a 5,000-character message is chunked ($oracle memos)")

            val sent = memosOfLaterMessage(SHARED)

            assertEquals(oracle, sent, "one tap paid for $sent memo outputs; a 5,000-character message needs $oracle")
        }

    @Test
    fun `a 200,000-character reply pays for no more memos than a 5,000-character one`() =
        runBlocking {
            val oracle = memosOfReply(AT_LIMIT)
            assertTrue(oracle > 1, "fixture sanity: a 5,000-character reply is chunked ($oracle memos)")

            val sent = memosOfReply(SHARED)

            assertEquals(oracle, sent, "one tap paid for $sent memo outputs; a 5,000-character reply needs $oracle")
        }

    /** Control, green before and after: a message under the cap is sent whole — the cap changes nothing else. */
    @Test
    fun `control - a short message is sent as one memo, as before`() =
        runBlocking {
            assertEquals(1, memosOfLaterMessage("synthetic short message"))
        }

    // --------------------------------------------------------------- harness

    private suspend fun memosOfFirstMessage(text: String): Int {
        val chat = Chat()
        val vm = chat.viewModel()
        vm.sendMessage(PEER, text)
        awaitIdle(vm)
        confirmIfAsked(vm)
        return memoCount(chat.messageUris().single())
    }

    private suspend fun quotedFirstMessage(text: String): Long {
        val chat = Chat()
        val vm = chat.viewModel()
        vm.sendMessage(PEER, text)
        awaitIdle(vm)
        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals("NeedsPaidActionConfirmation", state.javaClass.simpleName, "fixture sanity: asked first, was $state")
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        val total = confirmation.javaClass.getMethod("getTotalCost").invoke(confirmation)
        return total.javaClass.getMethod("getValue").invoke(total) as Long
    }

    private suspend fun memosOfLaterMessage(text: String): Int {
        val chat = Chat()
        val vm = chat.viewModel()
        PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
        vm.sendMessage(PEER, "synthetic first hello")
        awaitIdle(vm)
        confirmIfAsked(vm)
        vm.sendMessage(PEER, text)
        awaitIdle(vm)
        confirmIfAsked(vm)
        val uris = chat.messageUris()
        assertEquals(2, uris.size, "fixture sanity: two messages were sent")
        assertTrue(!firstMemo(uris[1]).contains("|INIT|"), "fixture sanity: the later message is not an INIT")
        return memoCount(uris[1])
    }

    private suspend fun memosOfReply(text: String): Int {
        val chat = Chat()
        val vm = chat.viewModel()
        PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
        vm.sendMessage(PEER, "synthetic first hello")
        awaitIdle(vm)
        confirmIfAsked(vm)
        vm.sendReply(PEER, text, "synthetic-reply-target", "synthetic preview")
        awaitIdle(vm)
        confirmIfAsked(vm)
        val uris = chat.messageUris()
        assertEquals(2, uris.size, "fixture sanity: the first message and the reply were sent")
        return memoCount(uris[1])
    }

    private fun confirmIfAsked(vm: ChatViewModel) {
        runBlocking { awaitIdle(vm) }
        if (HandshakeHarness.sendMessageState(vm).javaClass.simpleName == "NeedsPaidActionConfirmation") {
            vm.confirmPaidAction()
        }
        runBlocking { awaitIdle(vm) }
    }

    /** A new contact in a Shielded chat, no keys yet, over the real conversation-id store and a recording seam. */
    private inner class Chat {
        val fixture = PaidChatFixture(PEER, ConversationMode.VAULT, haveOurKey = false)
        val conversations = RealConversationIds()
        val seam = PaymentSeam(PEER)
        val drafts: MutableList<String> = Collections.synchronizedList(mutableListOf())

        suspend fun viewModel(): ChatViewModel {
            val vm = fixture.viewModel()
            PaymentHarness.setField(vm, "zchatPreferences", recordingDrafts(conversations.wrap(fixture.proxy)))
            PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(SELF))
            val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
            PaymentHarness.setField(vm, "accountDataSource", accounts)
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
                        ).also { SendHarnessScopes.current().own(it) },
                    accountDataSource = accounts,
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                ),
            )
            return vm
        }

        /** Every proposal that is a user message (not our KEX), in order. */
        fun messageUris(): List<String> =
            seam.proposalUris.filterNot {
                val memo = firstMemo(it)
                ZMSGProtocol.isKEXMessage(memo) || ZMSGProtocol.isKEXAckMessage(memo)
            }

        /** Records every setDraft the ViewModel makes for [PEER]; everything else goes to [base]. */
        private fun recordingDrafts(base: ZchatPreferences): ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "setDraft" -> {
                        if (args!![0] == PEER) drafts += args[1] as String
                        null
                    }
                    "toString" -> "RecordingDrafts($base)"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else ->
                        try {
                            method.invoke(base, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
    }
}
