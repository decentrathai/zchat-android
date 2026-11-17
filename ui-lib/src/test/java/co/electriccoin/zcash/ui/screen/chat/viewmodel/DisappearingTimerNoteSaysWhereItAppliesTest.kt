package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * ZC-297 — the disappearing-timer note in the chat says where the timer applies.
 *
 * At 469416b07 setDisappearingTtl sets the timer and writes "⏳ Messages now disappear after … Applies to new messages,
 * on both devices." BEFORE it reads the chat identity, publishes to the relays or submits the Shielded control. When
 * nothing reaches the contact the toast says "Timer set on this device only …", but the note stays in the chat history
 * and keeps saying "on both devices". With no chat identity nothing is sent and nothing is said, and the note is there.
 *
 * Wanted: one note per change (the same id, SysNotes.ttlNoteId), written after the send, by its outcome:
 * - handed off (a relay every ZChat version reads took it, or the Shielded submit returned): today's text;
 * - nothing went out (no relay took it, or the Shielded send was refused before its submit): the timer changed on this
 *   phone only (chat_note_timer_this_phone_only);
 * - it may have gone out (the Shielded submit started and failed with a stored transaction, or the relay send failed
 *   part-way): ZChat can't tell yet (chat_note_timer_maybe_sent);
 * - a copy only a public relay took (ZC-393: acks 0, readable 1) is not handed off: never "on both devices". Which of
 *   the two new notes it gets is not pinned here: telling it apart means reading the readable count in
 *   setDisappearingTtl, and PaidRelayFallbacksKeepTheirCountTest must list that as wording-only first;
 * - no chat identity: refused before anything is set (the D10 rule), no note, and the user is told.
 * The toasts stay as they are. The new notes carry no duration, so no English "1 hours" lands in a Spanish sentence.
 *
 * The harness is DisappearingTimerSetsWhatItSaysTest's (PaidChatFixture and a recording store); the note words come
 * from the English resources. All addresses, keys and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisappearingTimerNoteSaysWhereItAppliesTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ── (a) Tunnel and Open: no relay took the timer ─────────────────────────────────────────────────

    @Test
    fun `a free-chat timer no relay took leaves the this-phone-only note, never on both devices`() =
        runBlocking {
            for (mode in listOf(ConversationMode.TUNNEL, ConversationMode.OPEN)) {
                val chat = chat(PEER_FREE, mode)

                chat.vm.setDisappearingTtl(PEER_FREE, ONE_HOUR)
                HandshakeHarness.awaitIdle(chat.vm)

                assertEquals("$mode: the timer is set on this phone", 1, chat.timerWrites())
                assertEquals("$mode: the toast stays as it is", ChatSendCopy.TIMER_NOT_DELIVERED, chat.errorText())
                chat.assertOneNote("$mode, no relay took it", THIS_PHONE_ONLY)
                assertEquals("$mode: a free chat pays nothing", 0, chat.proposals())
            }
        }

    @Test
    fun `turning the timer off with no relay leaves the this-phone-only note, not turned off`() =
        runBlocking {
            val chat = chat(PEER_FREE, ConversationMode.TUNNEL)

            chat.vm.setDisappearingTtl(PEER_FREE, 0L)
            HandshakeHarness.awaitIdle(chat.vm)

            assertEquals(ChatSendCopy.TIMER_NOT_DELIVERED, chat.errorText())
            chat.assertOneNote("Off, no relay took it", THIS_PHONE_ONLY)
        }

    @Test
    fun `a free-chat timer only a public relay took never says on both devices`() =
        runBlocking {
            // ZC-393: acks counts ZChat's relay and nos.lol. A copy only a public relay holds is not delivered: a contact
            // on 2.12.4 does not read that relay.
            NostrChatBridge.registerPublisher { _, _ ->
                NostrChatBridge.PublishResult(acks = 0, messageId = "synthetic-rumor", readableAcks = 1)
            }
            val chat = chat(PEER_FREE, ConversationMode.OPEN, "getPeerNostrPubkey" to SYNTHETIC_PUBKEY)

            chat.vm.setDisappearingTtl(PEER_FREE, ONE_HOUR)
            HandshakeHarness.awaitIdle(chat.vm)

            assertEquals("the timer is set on this phone", 1, chat.timerWrites())
            assertEquals("the toast stays as it is", ChatSendCopy.TIMER_NOT_DELIVERED, chat.errorText())
            val note = chat.theOneNote("only a public relay took it")
            assertTrue("the note is one of the two new ones: $note", note == english(THIS_PHONE_ONLY) || note == english(MAYBE_SENT))
        }

    @Test
    fun `a free-chat timer whose relay send failed part-way leaves the cannot-tell-yet note`() =
        runBlocking {
            // The publish started and then threw, as a send cut off by the chat screen closing does: a relay may hold the
            // copy, so "Your contact wasn't told" would be a guess.
            NostrChatBridge.registerPublisher { _, _ -> throw IllegalStateException("synthetic: the relay send was cut off") }
            val chat = chat(PEER_FREE, ConversationMode.TUNNEL, "getPeerNostrPubkey" to SYNTHETIC_PUBKEY)

            chat.vm.setDisappearingTtl(PEER_FREE, ONE_HOUR)
            HandshakeHarness.awaitIdle(chat.vm)

            assertEquals("the timer is set on this phone", 1, chat.timerWrites())
            assertEquals("the toast stays as it is", ChatSendCopy.TIMER_NOT_DELIVERED, chat.errorText())
            chat.assertOneNote("the relay send failed part-way", MAYBE_SENT)
        }

    // ── (b) Shielded: refused before the submit started ──────────────────────────────────────────────

    @Test
    fun `a Shielded timer refused before its submit leaves the this-phone-only note`() =
        runBlocking {
            val seam = HandshakeSeam(PEER_SHIELDED, failFirstProposalWith = IllegalStateException("synthetic: no proposal"))
            val chat = shieldedChat(seam)

            chat.vm.setDisappearingTtl(PEER_SHIELDED, ONE_HOUR, costConfirmed = true)
            HandshakeHarness.awaitIdle(chat.vm)

            assertEquals("fixture: the send was refused before any submit", 0, seam.submits.get())
            assertNotEquals("nothing was stored", SubmitAttemptProof.MAY_HAVE_BEEN_SENT, chat.errorText())
            chat.assertOneNote("Shielded, refused before the submit", THIS_PHONE_ONLY)
        }

    // ── (c) Shielded: the submit started, then Failure(txIds = [T]) ──────────────────────────────────

    @Test
    fun `a Shielded timer stored then rejected leaves the cannot-tell-yet note`() =
        runBlocking {
            val seam = HandshakeSeam(PEER_SHIELDED, firstSubmitOutcome = GroupPaidOnceFixture.storedThenRejected())
            val chat = shieldedChat(seam)

            chat.vm.setDisappearingTtl(PEER_SHIELDED, ONE_HOUR, costConfirmed = true)
            HandshakeHarness.awaitIdle(chat.vm)

            assertEquals("fixture: one submit reached the SDK and was stored", 1, seam.submits.get())
            assertEquals("the toast stays as it is", SubmitAttemptProof.MAY_HAVE_BEEN_SENT, chat.errorText())
            chat.assertOneNote("Shielded, stored then rejected", MAYBE_SENT)
        }

    // ── (d) no chat identity ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `with no chat identity nothing is set, no note is written and the user is told`() =
        runBlocking {
            val chat = chat(PEER_FREE, ConversationMode.TUNNEL)
            AutoSpendVm.setField(chat.vm, "_currentUserAddress", MutableStateFlow<String?>(null))
            AutoSpendVm.setField(chat.vm, "accountDataSource", UnreadableAccounts.proxy)

            chat.vm.setDisappearingTtl(PEER_FREE, ONE_HOUR)
            HandshakeHarness.awaitIdle(chat.vm)

            assertEquals("the timer was set on this phone though nothing could be sent", 0, chat.timerWrites())
            assertEquals("a note was written for a timer that must not be set", emptyList<String>(), chat.noteTexts())
            val state = HandshakeHarness.sendMessageState(chat.vm)
            assertTrue("the user is told nothing: $state", state is SendMessageState.Error)
            assertEquals(ChatSendCopy.TIMER_SYNC_FAILED, (state as SendMessageState.Error).message)
        }

    // ── (e) controls: the timer was handed off ───────────────────────────────────────────────────────

    @Test
    fun `control - a free-chat timer a shared relay took keeps the on-both-devices note`() =
        runBlocking {
            for (mode in listOf(ConversationMode.TUNNEL, ConversationMode.OPEN)) {
                NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(1, "synthetic-rumor") }
                val chat = chat(PEER_FREE, mode, "getPeerNostrPubkey" to SYNTHETIC_PUBKEY)

                chat.vm.setDisappearingTtl(PEER_FREE, ONE_HOUR)
                HandshakeHarness.awaitIdle(chat.vm)

                assertEquals("$mode: the timer is set", 1, chat.timerWrites())
                val state = HandshakeHarness.sendMessageState(chat.vm)
                assertFalse("$mode: a delivered timer shows no failure: $state", state is SendMessageState.Error)
                val note = chat.noteTexts().single()
                assertTrue("$mode: the delivered note is today's: $note", note.startsWith("⏳ Messages now disappear after"))
                assertTrue("$mode: the delivered note is today's: $note", "on both devices" in note)
            }
        }

    @Test
    fun `control - a Shielded timer whose submit returned keeps the on-both-devices note and pays once`() =
        runBlocking {
            val seam = HandshakeSeam(PEER_SHIELDED)
            val chat = shieldedChat(seam)

            chat.vm.setDisappearingTtl(PEER_SHIELDED, ONE_HOUR, costConfirmed = true)
            HandshakeHarness.awaitIdle(chat.vm)

            assertEquals("the one control message is paid", 1, seam.proposalRequests.get())
            assertEquals(1, seam.submits.get())
            assertEquals(1, chat.timerWrites())
            val note = chat.noteTexts().single()
            assertTrue("the delivered note is today's: $note", "on both devices" in note)
        }

    // ── (f) the two texts ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `both notes exist in English and Spanish, with no placeholder and no promise`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for (key in listOf(THIS_PHONE_ONLY, MAYBE_SENT)) {
            assertTrue("$key is missing in values/", key in en)
            assertTrue("$key is missing in values-es/", key in es)
            for (text in listOf(en.getValue(key), es.getValue(key))) {
                assertFalse("$key takes no argument (no English duration inside a translated sentence): $text", "%" in text)
                assertTrue("$key is drawn as a timer note: $text", text.startsWith("⏳"))
                assertFalse("$key: $text", "both devices" in text || "ambos" in text)
            }
        }
        assertTrue(en.getValue(THIS_PHONE_ONLY), "this phone only" in en.getValue(THIS_PHONE_ONLY))
        assertTrue(en.getValue(MAYBE_SENT), "can't tell yet" in en.getValue(MAYBE_SENT))
        assertTrue(es.getValue(THIS_PHONE_ONLY), "solo en este teléfono" in es.getValue(THIS_PHONE_ONLY))
        assertTrue(es.getValue(MAYBE_SENT), "aún no sabe" in es.getValue(MAYBE_SENT))
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    /** One chat screen over a recording store: every store call's name, and every stored row. */
    private class Chat(
        private val fixture: PaidChatFixture,
        val vm: ChatViewModel,
        private val calls: List<String>,
        private val rows: List<ZchatPreferences.PendingMessageData>,
    ) {
        fun timerWrites(): Int = synchronized(calls) { calls.count { it == "setDisappearingTtl" } }

        fun proposals(): Int = fixture.seam.proposalRequests.get()

        /** The text of every timer-note write, in order (one id per change: SysNotes.ttlNoteId). */
        fun noteTexts(): List<String> = synchronized(rows) { rows.filter { it.id.startsWith(TTL_NOTE_PREFIX) }.map { it.text } }

        fun errorText(): String {
            val state = HandshakeHarness.sendMessageState(vm)
            assertTrue("the user must be told the timer did not reach the contact: $state", state is SendMessageState.Error)
            return (state as SendMessageState.Error).message
        }

        /** The one note of the change, which never says "on both devices". */
        fun theOneNote(case: String): String {
            val notes = noteTexts()
            assertFalse(
                "$case: the chat history says \"on both devices\", but nothing shows the contact's phone has the timer: $notes",
                notes.any { "on both devices" in it },
            )
            assertEquals("$case: one note for the change: $notes", 1, notes.size)
            return notes.single()
        }

        /** [theOneNote] is the English text of the string resource [key] (looked up after the check above). */
        fun assertOneNote(
            case: String,
            key: String,
        ) {
            val note = theOneNote(case)
            assertEquals("$case: the note says where the timer applies", english(key), note)
        }
    }

    private suspend fun chat(
        peer: String,
        mode: ConversationMode,
        vararg overrides: Pair<String, Any?>,
    ): Chat {
        val fixture = PaidChatFixture(peer, mode)
        val vm = fixture.viewModel()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val rows = Collections.synchronizedList(mutableListOf<ZchatPreferences.PendingMessageData>())
        AutoSpendVm.setField(vm, "zchatPreferences", recording(fixture.proxy, calls, rows, overrides.toMap()))
        AutoSpendVm.setField(vm, "noteCopy", EnglishNoteCopy)
        return Chat(fixture, vm, calls, rows)
    }

    /** A Shielded chat with its ratchet, sending through the real chokepoint over [seam]. */
    private suspend fun shieldedChat(seam: HandshakeSeam): Chat {
        val chat = chat(PEER_SHIELDED, ConversationMode.VAULT)
        AutoSpendVm.setField(chat.vm, "createChunkedMessageProposal", useCaseOver(seam))
        AutoSpendVm.setField(chat.vm, "addressCache", NoOpAddressCache.proxy)
        return chat
    }

    private suspend fun useCaseOver(seam: ProposalDataSource): CreateChunkedMessageProposalUseCase {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        return CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = seam,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ).also(SendHarnessScopes.current()::own),
            accountDataSource = accounts,
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
        )
    }

    /** [base], recording every call's name and every row written; the calls named in [answers] answer their fixed value. */
    private fun recording(
        base: ZchatPreferences,
        calls: MutableList<String>,
        rows: MutableList<ZchatPreferences.PendingMessageData>,
        answers: Map<String, Any?>,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            calls += method.name
            if (method.name == "addPendingMessage" || method.name == "commitPendingMessage") {
                rows += args!![0] as ZchatPreferences.PendingMessageData
            }
            if (answers.containsKey(method.name)) {
                answers[method.name]
            } else {
                try {
                    method.invoke(base, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences

    /** English resources, as the view model's getString would return them on an English phone. */
    private object EnglishNoteCopy : ChatNoteCopy {
        override fun get(
            id: Int,
            vararg args: Any,
        ): String {
            val text = ChatResourceText.english(id)
            return if (args.isEmpty()) text else String.format(text, *args)
        }
    }

    /** The selected account cannot be read, so the chat identity cannot be loaded. */
    private object UnreadableAccounts {
        val proxy: AccountDataSource =
            Proxy.newProxyInstance(
                AccountDataSource::class.java.classLoader,
                arrayOf(AccountDataSource::class.java),
            ) { _, method, _ ->
                throw IllegalStateException("synthetic: ${method.name} cannot read the account")
            } as AccountDataSource
    }

    private companion object {
        const val PEER_FREE = "usyntheticzc297timerfree0001"
        const val PEER_SHIELDED = "usyntheticzc297timershield01"
        const val SYNTHETIC_PUBKEY = "6e7e7e7e6e7e7e7e6e7e7e7e6e7e7e7e6e7e7e7e6e7e7e7e6e7e7e7e6e7e7e7e"
        const val ONE_HOUR = 3_600L
        const val THIS_PHONE_ONLY = "chat_note_timer_this_phone_only"
        const val MAYBE_SENT = "chat_note_timer_maybe_sent"
        val TTL_NOTE_PREFIX = SysNotes.ID_PREFIX + "ttl-"

        /** The English text of the string resource [name]; the assertion names the key when this build has none. */
        fun english(name: String): String =
            ChatResourceText.strings("values")[name] ?: throw AssertionError("the string resource $name does not exist")
    }
}
