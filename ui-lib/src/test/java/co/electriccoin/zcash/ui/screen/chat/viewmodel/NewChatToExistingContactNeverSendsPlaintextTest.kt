package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.ChatDetail
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.UserStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.zip317ChatPrice
import co.electriccoin.zcash.ui.screen.chat.usecase.zip321Amounts
import co.electriccoin.zcash.ui.screen.chat.view.composeBlurb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 8 P4 (PHONE_TRIAGE F6) — New Chat never sends outside the ratchet to a contact that already has a chat.
 *
 * At 701289e99 ZchatComposeVM.doSendMessage sends every message through CreateChunkedMessageProposalUseCase, which
 * never encrypts (encryptOutgoing, getOrCreateMessageProcessor and isE2EEnabled appear nowhere in the screen). For a
 * contact with a chat, and with the end-to-end session in place, the message goes out as a plaintext memo outside the
 * ratchet. It bypasses the refusal the chat screen gives while E2E is on without keys, the receiver shows it with the
 * "Sender not verified" chip, and a Tunnel contact whose handshake is done pays an on-chain memo that the chat would
 * have sent free. The screen also takes its new-chat default mode (Tunnel) for such a contact when no mode is stored,
 * and writes it on Send.
 *
 * Wanted: when the recipient already has a chat (a conversation whose INIT went out, E2E on, or a completed key
 * exchange), Send builds no proposal and writes no mode. It puts the text into that chat's draft (keeping a draft
 * already there) and opens ChatDetail(recipient), where the chat's own send path encrypts or refuses. The mode shown
 * is the chat's stored one (Shielded when unset) and the picker is locked. A chat the user hid (no visible messages)
 * still opens, empty, instead of "Conversation not found". A brand-new recipient is unchanged: the priced INIT, once.
 *
 * Harness: the ZchatComposeStoredRejectionIsNotResentTest idiom (a real ZchatComposeVM on an unconfined main
 * dispatcher, the real use case and repository, the real conversation-id store) with a stateful preferences fake.
 * Names that do not exist at the base (the Ready flag, ChatViewModel.emptyConversationFor, the new string file) are
 * read by reflection or from disk, so this file compiles at the base. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewChatToExistingContactNeverSendsPlaintextTest {
    private val vms = mutableListOf<ZchatComposeVM>()
    private val conversations = RealConversationIds()
    private val accounts = ChangingAccountDataSource(walletWith(orchard = 0, ironwood = IRONWOOD))
    private val proposals = ScriptedProposals(ironwoodNotes = listOf(IRONWOOD))
    private val navigationRouter = RoutesRecordingNavigationRouter()
    private val store = StatefulPrefs()

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

    /** (1) A Shielded contact with E2E on and the ratchet keys held. */
    @Test
    fun `an existing Shielded contact with E2E ready gets the chat with a draft and nothing is sent`() =
        runBlocking {
            existingConversation(PEER_ADDRESS)
            store.modes[PEER_ADDRESS] = ConversationMode.VAULT
            store.e2eEnabled += PEER_ADDRESS
            store.keyExchangeComplete += PEER_ADDRESS

            val vm = buildVm()
            typeAndSend(vm, PEER_ADDRESS)

            assertEquals(0, proposals.proposed.size, "New Chat built a proposal outside the ratchet")
            assertEquals(0, proposals.submitted.size, "New Chat submitted a plaintext memo outside the ratchet")
            assertEquals(listOf<Any>(ChatDetail(PEER_ADDRESS)), navigationRouter.replaced, "the chat did not open")
            assertEquals(MESSAGE, store.drafts[PEER_ADDRESS], "the typed text is not in the chat's draft")
            assertEquals(ConversationMode.VAULT, store.modes[PEER_ADDRESS], "the chat's mode changed")
            assertEquals(0, store.modeWrites, "New Chat wrote a mode for an existing chat")
        }

    /** (2) E2E on, but no ratchet keys yet (and no conversation id): the chat refuses; New Chat must not send. */
    @Test
    fun `E2E on without ratchet keys sends nothing from New Chat`() =
        runBlocking {
            store.e2eEnabled += PEER_ADDRESS

            val vm = buildVm()
            typeAndSend(vm, PEER_ADDRESS)

            assertEquals(0, proposals.proposed.size, "New Chat built a plaintext proposal while E2E is on")
            assertEquals(0, proposals.submitted.size, "New Chat submitted a plaintext memo while E2E is on")
            assertEquals(listOf<Any>(ChatDetail(PEER_ADDRESS)), navigationRouter.replaced, "the chat did not open")
            assertEquals(MESSAGE, store.drafts[PEER_ADDRESS], "the typed text is not in the chat's draft")
        }

    /** (3) A Tunnel contact whose ZBOOT handshake is done: the chat sends free over the relays, never on-chain. */
    @Test
    fun `an existing Tunnel contact with the handshake done pays nothing on-chain from New Chat`() =
        runBlocking {
            existingConversation(PEER_ADDRESS)
            store.modes[PEER_ADDRESS] = ConversationMode.TUNNEL
            store.nostrPubkeys[PEER_ADDRESS] = "ab".repeat(32)
            store.bootSent += PEER_ADDRESS
            store.keyExchangeComplete += PEER_ADDRESS

            val vm = buildVm()
            typeAndSend(vm, PEER_ADDRESS)

            assertEquals(0, proposals.proposed.size, "New Chat built an on-chain proposal for a Tunnel chat")
            assertEquals(0, proposals.submitted.size, "New Chat paid an on-chain memo for a Tunnel chat")
            assertEquals(listOf<Any>(ChatDetail(PEER_ADDRESS)), navigationRouter.replaced, "the chat did not open")
            assertEquals(ConversationMode.TUNNEL, store.modes[PEER_ADDRESS], "the chat's mode changed")
        }

    /** (4) An existing conversation with no stored mode, picked from CONTACTS: no new-chat default, nothing written. */
    @Test
    fun `an existing chat with no stored mode shows Shielded, locks the picker and writes no mode`() =
        runBlocking {
            existingConversation(PEER_ADDRESS)
            val vm = buildVm(contacts = listOf(Contact(PEER_ADDRESS, "Synthetic peer", Instant.EPOCH)))

            vm.ready().onContactSelect(vm.ready().contacts.single())
            vm.ready().onMessageChange(MESSAGE)
            val shown = vm.ready()
            assertEquals(ConversationMode.VAULT, shown.selectedMode, "the new-chat default replaced the chat's mode")
            assertEquals(true, readyFlag(shown, "opensExistingChat"), "the picker is not locked for an existing chat")

            shown.onSendClick()
            settle(vm)
            assertNull(store.getModeOrNull(PEER_ADDRESS), "Send wrote a mode for a chat that had none")
            assertEquals(0, store.modeWrites, "New Chat wrote a mode for an existing chat")
            assertEquals(0, proposals.submitted.size, "New Chat sent to an existing chat")
        }

    /** (4b) A draft the chat already holds is kept, and the typed text is added to it. */
    @Test
    fun `a draft already in the chat is kept next to the typed text`() =
        runBlocking {
            existingConversation(PEER_ADDRESS)
            store.drafts[PEER_ADDRESS] = EARLIER_DRAFT

            val vm = buildVm()
            typeAndSend(vm, PEER_ADDRESS)

            val draft = store.drafts[PEER_ADDRESS].orEmpty()
            assertTrue(EARLIER_DRAFT in draft, "the chat's earlier draft was lost: $draft")
            assertTrue(MESSAGE in draft, "the typed text is not in the chat's draft: $draft")
            assertEquals(0, proposals.submitted.size, "New Chat sent to an existing chat")
        }

    /** (4c) Nothing is spent here, so the ZEC cost notice is not in the way of opening the chat. */
    @Test
    fun `an existing chat opens without the cost notice`() =
        runBlocking {
            existingConversation(PEER_ADDRESS)
            store.costAcknowledged = false

            val vm = buildVm()
            typeAndSend(vm, PEER_ADDRESS)

            assertFalse(vm.showCostDisclaimer.value, "the ZEC cost notice was shown for a send that spends nothing")
            assertEquals(listOf<Any>(ChatDetail(PEER_ADDRESS)), navigationRouter.replaced, "the chat did not open")
        }

    /** (5) Control, green before and after: a brand-new recipient still sends the priced INIT once. */
    @Test
    fun `control - a brand-new recipient sends the priced INIT once, quote equal to the charge`() =
        runBlocking {
            val vm = buildVm()
            vm.ready().onRecipientChange(PEER_ADDRESS)
            vm.ready().onMessageChange(MESSAGE)
            val shown = vm.ready()
            shown.onSendClick()
            NewChatPriceQuestion.payIfAsked(vm)
            settle(vm)

            assertEquals(1, proposals.submitted.size, "a brand-new recipient must be sent the INIT once")
            val uri = proposals.proposed.single()
            assertTrue(isInitFrom(firstMemo(uri), SELF_ADDRESS), "the first message to a new recipient is not the INIT")
            val charged = zip321Amounts(uri).sum()
            val quoted =
                CreateChunkedMessageProposalUseCase
                    .totalCost(
                        shown.chunkCount,
                        Zatoshi(CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT.value),
                    ).value
            assertEquals(quoted, charged, "the charge differs from the quote")
            assertEquals(String.format("%.5f ZEC", charged / 100_000_000.0), shown.totalAmountDisplay, "quote shown")
            assertEquals(listOf<Any>(ChatDetail(PEER_ADDRESS)), navigationRouter.replaced)
            assertNotEquals(true, readyFlag(shown, "opensExistingChat") ?: false, "a new recipient's picker was locked")
        }

    /** (5b) Control, green before and after: a first send refused before submission is sent again as the INIT. */
    @Test
    fun `control - a conversation whose INIT never went out is still sent the INIT`() =
        runBlocking {
            conversations.impl.getOrCreateConversationIdForMessage(PEER_ADDRESS)
            assertTrue(conversations.impl.needsConversationInit(PEER_ADDRESS), "fixture sanity")

            val vm = buildVm()
            typeAndSend(vm, PEER_ADDRESS)

            assertEquals(1, proposals.submitted.size, "a refused first message must be sent again as the INIT")
            assertTrue(isInitFrom(firstMemo(proposals.proposed.single()), SELF_ADDRESS), "not sent as the INIT")
        }

    /** (6) A chat the user hid has no visible messages; ChatDetail must still open it (empty), with the draft. */
    @Test
    fun `a hidden chat of an existing contact opens empty with the draft, a stranger gets none`() {
        existingConversation(PEER_ADDRESS)
        store.drafts[PEER_ADDRESS] = MESSAGE
        val chat = chatViewModel()

        val method =
            ChatViewModel::class.java.methods.firstOrNull { it.name == "emptyConversationFor" && it.parameterCount == 1 }
        assertNotNull(method, "ChatViewModel cannot open a hidden chat of an existing contact")
        val opened = method.invoke(chat, PEER_ADDRESS) as Conversation?
        assertNotNull(opened, "a hidden chat of an existing contact shows \"Conversation not found\"")
        assertEquals(PEER_ADDRESS, opened.peerAddress)
        assertTrue(opened.messages.isEmpty(), "the hidden messages came back")
        assertEquals(MESSAGE, opened.draft, "the draft New Chat handed over is not in the composer")

        assertNull(method.invoke(chat, STRANGER_ADDRESS), "a ghost chat was opened for an address with no chat")
    }

    /** (7) The Shielded caption says when E2E starts (D9 qualifier), in English and Spanish. */
    @Test
    fun `the Shielded caption says E2E starts once the contact answered the key exchange`() {
        val blurb = ConversationMode.VAULT.composeBlurb()
        assertTrue("E2E encrypted once your contact has answered the key exchange" in blurb, blurb)
        val en = stringRes("ui-lib/src/main/res/ui/common/values/new_chat_existing_chat_strings.xml", "new_chat_mode_blurb_vault")
        assertEquals(blurb, en, "the English resource differs from the pinned caption")
        val es = stringRes("ui-lib/src/main/res/ui/common/values-es/new_chat_existing_chat_strings.xml", "new_chat_mode_blurb_vault")
        assertTrue("una vez que tu contacto respondió al intercambio de claves" in es, es)
        for (name in listOf("new_chat_existing_chat_note", "new_chat_open_chat_button")) {
            val enText = stringRes("ui-lib/src/main/res/ui/common/values/new_chat_existing_chat_strings.xml", name)
            val esText = stringRes("ui-lib/src/main/res/ui/common/values-es/new_chat_existing_chat_strings.xml", name)
            assertNotEquals(enText, esText, "$name is not translated")
        }
    }

    // --------------------------------------------------------------- harness

    /** A conversation whose INIT went out: the id exists and is not marked "INIT not sent". */
    private fun existingConversation(peer: String) {
        conversations.impl.getOrCreateConversationId(peer)
        assertFalse(conversations.impl.needsConversationInit(peer), "fixture sanity: the conversation exists")
    }

    private suspend fun typeAndSend(
        vm: ZchatComposeVM,
        peer: String,
    ) {
        vm.ready().onRecipientChange(peer)
        vm.ready().onMessageChange(MESSAGE)
        vm.ready().onSendClick()
        NewChatPriceQuestion.payIfAsked(vm)
        settle(vm)
    }

    /** Waits until the screen navigated, or is no longer loading or sending. */
    private suspend fun settle(vm: ZchatComposeVM) {
        withTimeout(TIMEOUT_MS) {
            while (navigationRouter.replaced.isEmpty()) {
                val s = vm.state.value
                if (s !is ZchatComposeState.Loading && !(s is ZchatComposeState.Ready && s.isSending)) break
                delay(5)
            }
        }
    }

    private fun readyFlag(
        state: ZchatComposeState.Ready,
        name: String,
    ): Boolean? =
        runCatching {
            state.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(state) as Boolean
        }.getOrNull()

    private fun stringRes(
        path: String,
        name: String,
    ): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        val file = File(assertNotNull(dir, "repo root not found"), path)
        assertTrue(file.isFile, "missing $path")
        val m = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(file.readText())
        return assertNotNull(m, "string $name missing in $path").groupValues[1]
    }

    private suspend fun buildVm(contacts: List<Contact> = emptyList()): ZchatComposeVM {
        val synchronizerProvider = AnsweringSynchronizerProvider()
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accounts)
        val vm =
            ZchatComposeVM(
                contactBook = ListContactBook(contacts),
                getSelectedWalletAccount = getSelectedWalletAccount,
                getDefaultUnifiedAddress = GetDefaultUnifiedAddressUseCase(synchronizerProvider, getSelectedWalletAccount),
                createChunkedMessageProposal =
                    CreateChunkedMessageProposalUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = accounts,
                                proposalDataSource = proposals,
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ),
                        accountDataSource = accounts,
                        navigationRouter = Zc101RecordingNavigationRouter(),
                        biometricRepository = Zc101SuspendingBiometricRepository().apply { approve() },
                    ),
                navigationRouter = navigationRouter,
                prefillZchat = PrefillZchatUseCase(),
                transactionRepository = EmptyTransactionRepository(),
                zchatPreferences = conversations.wrap(store.proxy),
                validateAddress = ValidateAddressUseCase(synchronizerProvider),
            )
        vms += vm
        return vm
    }

    /** A ChatViewModel with no constructor run, holding only what emptyConversationFor reads. */
    private fun chatViewModel(): ChatViewModel {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val vm = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ChatViewModel::class.java) as ChatViewModel

        fun set(
            name: String,
            value: Any,
        ) = ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
        set("zchatPreferences", neverMuted(conversations.wrap(store.proxy)))
        set("contactBook", ListContactBook(emptyList()))
        set("peerStatuses", MutableStateFlow<Map<String, UserStatus>>(emptyMap()))
        return vm
    }

    /** [prefs], with no conversation muted (RealConversationIds would route the mute read to its bare store). */
    private fun neverMuted(prefs: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "toString" -> "NeverMuted($prefs)"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args!![0]
                "isConversationMuted" -> false
                else ->
                    try {
                        method.invoke(prefs, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private fun ZchatComposeVM.ready(): ZchatComposeState.Ready =
        state.value as? ZchatComposeState.Ready
            ?: error("VM state was ${state.value}, expected Ready")

    /** The per-peer values this screen and the chat read, kept in memory; everything else reads as unset. */
    private class StatefulPrefs {
        val modes = Collections.synchronizedMap(mutableMapOf<String, ConversationMode>())
        val drafts = Collections.synchronizedMap(mutableMapOf<String, String>())
        val nostrPubkeys = Collections.synchronizedMap(mutableMapOf<String, String>())
        val e2eEnabled: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val keyExchangeComplete: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val bootSent: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        @Volatile var costAcknowledged = true

        @Volatile var modeWrites = 0

        fun getModeOrNull(peer: String): ConversationMode? = modes[peer]

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                fun peer() = args!![0] as String
                when (method.name) {
                    "toString" -> "NewChatStatefulPrefs"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args!![0]
                    "getConversationModeOrNull" -> modes[peer()]
                    "getConversationMode" -> modes[peer()] ?: ConversationMode.VAULT
                    "setConversationMode" -> {
                        modeWrites++
                        val mode = args!![1] as ConversationMode?
                        if (mode == null) modes.remove(peer()) else modes[peer()] = mode
                        Unit
                    }
                    "hasAcknowledgedMessageCost" -> costAcknowledged
                    "setAcknowledgedMessageCost" -> {
                        costAcknowledged = true
                        Unit
                    }
                    "isE2EEnabled" -> peer() in e2eEnabled
                    "isE2EKeyExchangeComplete" -> peer() in keyExchangeComplete
                    "getPeerNostrPubkey" -> nostrPubkeys[peer()]
                    "isOwnBootSent" -> peer() in bootSent
                    "getDraft" -> drafts[peer()]
                    "setDraft" -> {
                        val draft = args!![1] as String
                        if (draft.isBlank()) drafts.remove(peer()) else drafts[peer()] = draft
                        Unit
                    }
                    "clearDraft" -> {
                        drafts.remove(peer())
                        Unit
                    }
                    "hasDraft" -> !drafts[peer()].isNullOrBlank()
                    "getAllDrafts" -> drafts.toMap()
                    else -> unset(method.returnType)
                }
            } as ZchatPreferences

        private fun unset(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                List::class.java -> emptyList<Any>()
                Set::class.java -> emptySet<Any>()
                Map::class.java -> emptyMap<Any, Any>()
                else -> null
            }
    }

    /** Records every route passed to replace, and counts the rest. */
    private class RoutesRecordingNavigationRouter : NavigationRouter {
        val replaced: MutableList<Any> = Collections.synchronizedList(mutableListOf())
        val others: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun forward(vararg routes: Any) {
            others += "forward"
        }

        override fun replace(vararg routes: Any) {
            replaced.addAll(routes)
        }

        override fun replaceAll(vararg routes: Any) {
            others += "replaceAll"
        }

        override fun back() {
            others += "back"
        }

        override fun backTo(route: KClass<*>) {
            others += "backTo"
        }

        override fun backToRoot() {
            others += "backToRoot"
        }

        override fun observePipeline(): Flow<NavigationCommand> = emptyFlow()
    }

    /**
     * Ironwood proposals priced like SDK 3.0.0 over [ironwoodNotes]; each proposal URI and each submit is recorded,
     * and every submit succeeds.
     */
    private class ScriptedProposals(
        val ironwoodNotes: List<Long>,
        val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val proposed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val submitted: MutableList<Proposal> = Collections.synchronizedList(mutableListOf())

        override suspend fun probeZip321Fee(
            account: WalletAccount,
            zip321Uri: String,
        ): FeeProbe = zip317ChatPrice(ironwoodNotes, zip321Amounts(zip321Uri))

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            proposed += zip321Uri
            val amounts = zip321Amounts(zip321Uri)
            val synthetic = syntheticZip321Proposal("usynthetic-new-chat-peer", amounts.first(), "")
            val priced = zip317ChatPrice(ironwoodNotes, amounts) as? FeeProbe.Built ?: return synthetic
            return synthetic.copy(
                proposal =
                    SweepProposalBytes.proposal(
                        inputs = ironwoodNotes.map { SweepProposalBytes.ironwood(it) },
                        fee = priced.fee.value,
                        paymentPools = amounts.map { SweepProposalBytes.IRONWOOD },
                        change = priced.change.orEmpty(),
                    ),
            )
        }

        // F4: the 1:1 chat senders now call this overload (to record the stored ids before the submit). This fake
        // reports no stored ids: it settles through its own two-argument submit below, exactly as before F4.
        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
        ): SubmitResult = submitTransaction(proposal, usk)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult {
            submitted += proposal
            return SubmitResult.Success(txIds = listOf("synthetic-txid-${submitted.size}"))
        }
    }

    /** Answers the two Synchronizer calls this screen makes: our own address, and "valid" for any other. */
    private class AnsweringSynchronizerProvider : SynchronizerProvider {
        private val answering =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "AnsweringSynchronizer"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    "getUnifiedAddress" -> SELF_ADDRESS
                    "validateAddress" -> AddressType.Unified
                    else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
                }
            } as Synchronizer

        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(answering)

        override suspend fun getSynchronizer(): Synchronizer = answering

        override fun resetSynchronizer() = Unit
    }

    private class EmptyTransactionRepository : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)

        override suspend fun getMemos(transaction: Transaction): List<String> = emptyList()

        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

        override suspend fun getTransactions(): List<Transaction> = emptyList()

        override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()
    }

    private class ListContactBook(
        private val contacts: List<Contact>,
    ) : ContactBook {
        override fun addContact(contact: Contact) = Unit

        override fun removeContact(address: String) = Unit

        override fun getContact(address: String): Contact? = contacts.firstOrNull { it.address == address }

        override fun getAllContacts(): List<Contact> = contacts

        override fun hasContact(address: String): Boolean = contacts.any { it.address == address }

        override fun updateContactName(
            address: String,
            newName: String
        ) = Unit

        override fun clearAll() = Unit
    }

    private companion object {
        const val IRONWOOD = 443_002L
        const val TIMEOUT_MS = 10_000L
        const val MESSAGE = "synthetic hello"
        const val EARLIER_DRAFT = "synthetic earlier draft"

        /** Bare-address shaped and longer than 100 chars, as the screen requires. Synthetic. */
        val PEER_ADDRESS = "u1synthetic" + "peer".repeat(25)
        val STRANGER_ADDRESS = "u1synthetic" + "strn".repeat(25)
        val SELF_ADDRESS = "u1synthetic" + "self".repeat(25)
    }
}
