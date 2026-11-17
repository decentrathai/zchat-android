package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** ZC-1027 (ZC-880-R1): an Open chat must not resume an earlier paid path after its mode or retry state changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnOpenChatNeverPaysAfterASwitchOrARetryTest {
    private val scopes = SendHarnessScopes()
    private val peer = "usyntheticr1switchpeer00001"
    private var restoreOwedMutex: (() -> Unit)? = null

    @Before
    fun setUp() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        restoreOwedMutex?.invoke()
        restoreOwedMutex = null
        NostrChatBridge.unregisterPublisher()
        scopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private class ObservedOwedMutex(
        private val delegate: Mutex = Mutex(locked = true),
    ) : Mutex by delegate {
        val waiterEntered = CompletableDeferred<Unit>()

        override suspend fun lock(owner: Any?) {
            waiterEntered.complete(Unit)
            delegate.lock(owner)
        }

        fun release() = delegate.unlock()
    }

    @Suppress("UNCHECKED_CAST")
    private fun installOwedMutex(peer: String, mutex: Mutex) {
        val field =
            ChatViewModel::class.java.declaredFields
                .single { it.name == "owedKexLocks" && Modifier.isStatic(it.modifiers) }
                .apply { isAccessible = true }
        val locks = field.get(null) as MutableMap<String, Mutex>
        val previous = locks.put(peer, mutex)
        restoreOwedMutex = {
            if (previous == null) locks.remove(peer, mutex) else locks[peer] = previous
        }
    }

    private fun relayKey(): String =
        NOSTRIdentity
            .fromSeed(ByteArray(64) { (it + 19).toByte() })
            .publicKey
            .joinToString("") { "%02x".format(it) }

    private fun withKnownRelay(store: ZchatPreferences, peer: String): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "getPeerNostrPubkey" && args?.getOrNull(0) == peer) {
                relayKey()
            } else {
                try {
                    method.invoke(store, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences

    private suspend fun shieldedFirstMessage(): Pair<FirstMessagePlanChat, ChatViewModel> {
        val chat = FirstMessagePlanChat(peer)
        val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable"))
        val base = syntheticZashiAccount()
        val funded =
            Zc101FakeAccountDataSource(
                base.copy(
                    unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(1_000_000L), Zatoshi(0), Zatoshi(0))),
                ),
            )
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = funded,
                        proposalDataSource = chat.fixture.seam,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ).also(scopes::own),
                accountDataSource = funded,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            )
        PaymentHarness.setField(vm, "accountDataSource", funded)
        PaymentHarness.setField(vm, "createChunkedMessageProposal", useCase)
        PaymentHarness.setField(vm, "zchatPreferences", withKnownRelay(chat.prefs, peer))
        return chat to vm
    }

    private data class ProposalKinds(
        val kex: Int,
        val message: Int,
        val other: Int,
    )

    private fun kinds(chat: FirstMessagePlanChat): ProposalKinds {
        var kex = 0
        var message = 0
        var other = 0
        val proposalUris =
            chat.fixture.seam.proposalUris
                .toList()
        for (uri in proposalUris) {
            val memo = firstMemo(uri)
            when {
                ZMSGProtocol.isKEXMessage(memo) -> kex++
                ZMSGProtocol.isKEXAckMessage(memo) || memo.startsWith("ZBOOT|") -> other++
                ZMSGProtocol.isV4Message(memo) -> message++
                else -> other++
            }
        }
        return ProposalKinds(kex, message, other)
    }

    private suspend fun askAndConfirmFirstMessage(vm: ChatViewModel) {
        assertTrue("the Shielded send was rejected before its price question", vm.sendMessage(peer, "synthetic r1 first message"))
        awaitIdle(vm)
        val question = HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsPaidActionConfirmation
        assertNotNull("the real first-message exact-price question was absent", question)
        assertEquals("FIRST_MESSAGE", question!!.confirmation.action.name)
        assertTrue("the question priced no on-chain messages", question.confirmation.onChainMessages >= 2)
        vm.confirmPaidAction()
    }

    @Test
    fun `a confirmed Shielded message waiting for its owed KEX builds nothing after switching Open`() =
        runBlocking {
            val (chat, vm) = shieldedFirstMessage()
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(1, "synthetic-r1-mode-ack") }
            val mutex = ObservedOwedMutex()
            installOwedMutex(peer, mutex)
            try {
                askAndConfirmFirstMessage(vm)
                withTimeout(HandshakeHarness.TIMEOUT_MS) { mutex.waiterEntered.await() }
                assertTrue("the confirmed send did not leave its KEX owed", peer in chat.kexOwed)
                assertTrue("the send job did not reach its actual owed-KEX wait", mutex.waiterEntered.isCompleted)
                assertEquals("a payment occurred before the mode switch", ProposalKinds(0, 0, 0), kinds(chat))
                assertTrue(
                    "the confirmed row is not in progress",
                    PaymentHarness.pendingMessages(vm).any {
                        it.status == MessageStatus.SENDING
                    },
                )

                vm.switchConversationMode(peer, ConversationMode.OPEN)
                awaitTrue { chat.fixture.mode == ConversationMode.OPEN }
                val atSwitch = kinds(chat)
                assertEquals("the mode picker built an unexpected control proposal", ProposalKinds(0, 0, 0), atSwitch)
                mutex.release()
                awaitIdle(vm)

                val after = kinds(chat)
                assertEquals("MESSAGE proposal built after the confirmed send's chat became Open", 0, after.message - atSwitch.message)
                assertEquals("KEX proposal built after the chat became Open", 0, after.kex - atSwitch.kex)
                assertTrue("the owed KEX mark was dropped in Open", peer in chat.kexOwed)
                assertFalse(
                    "the on-chain row was reported SENT in Open",
                    PaymentHarness.pendingMessages(vm).any {
                        it.status == MessageStatus.SENT
                    },
                )
            } finally {
                if (mutex.isLocked) mutex.release()
            }
        }

    @Test
    fun `control - the same confirmed Shielded first message builds one KEX then one message`() =
        runBlocking {
            val (chat, vm) = shieldedFirstMessage()
            askAndConfirmFirstMessage(vm)
            awaitIdle(vm)
            val built = kinds(chat)
            assertEquals("the Shielded control did not build its KEX", 1, built.kex)
            assertEquals("the Shielded control did not build its message", 1, built.message)
        }

    @Test
    fun `notice Retry in an Open chat asks no money and builds nothing even after stray Confirm`() =
        runBlocking {
            val world = LedgerWorld(mode = ConversationMode.TUNNEL)
            val vm = world.openVm()
            val marker = world.kexProvenNotSent(vm)
            assertEquals("NOT_SENT", world.ledger.one("KEX")?.state)
            assertEquals(marker, world.notice(vm)[world.peer])
            val before = world.seam.proposals.get()
            world.base.modes[world.peer] = ConversationMode.OPEN

            vm.retryHandshake(world.peer, marker)
            awaitIdle(vm)
            assertNull("notice Retry offered an Open-chat payment question", world.question(vm))
            assertEquals("notice Retry built an Open-chat proposal", before, world.seam.proposals.get())
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("stray Confirm paid the Open-chat notice Retry", before, world.seam.proposals.get())
            assertEquals("the failed setup notice vanished", marker, world.notice(vm)[world.peer])
        }

    @Test
    fun `control - notice Retry in Tunnel asks then pays one KEX on Confirm`() =
        runBlocking {
            // ZC-1822: the Retry's question is asked only of a wallet that can pay it.
            val world = LedgerWorld(mode = ConversationMode.TUNNEL, accountSource = fundedAccounts())
            val vm = world.openVm()
            val marker = world.kexProvenNotSent(vm)
            val before = world.seam.proposals.get()

            vm.retryHandshake(world.peer, marker)
            awaitIdle(vm)
            assertEquals("HANDSHAKE_RETRY", world.question(vm))
            assertEquals(before, world.seam.proposals.get())
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("Tunnel Retry did not pay exactly one KEX", before + 1, world.seam.proposals.get())
        }

    @Test
    fun `a ZBOOT-only Open key-change reconnect explains failed current delivery despite an older delivered marker`() =
        runBlocking {
            val contactKeys = E2EEncryption.generateKeyPair()
            val chat =
                KeyChangeReconnectChat(
                    peer,
                    consent = false,
                    mode = ConversationMode.TUNNEL,
                    failFirstKexWithStillConfirming = true,
                    exchangeComplete = true,
                    contactKeys = contactKeys,
                )
            val vm = chat.viewModel()
            val published = Collections.synchronizedList(mutableListOf<String>())
            val kexSlot = HandshakePaidRecord.slot(AutomaticSetupKind.KEX, peer)
            val zbootSlot = HandshakePaidRecord.slot(AutomaticSetupKind.ZBOOT, peer)

            // A real prior reconnect pays KEX. A verified inbound ZBOOT binds the contact's relay key while that
            // KEX waits; its own ZBOOT then reaches a readable relay, giving the store a genuine delivered marker.
            vm.dismissE2EKeyChanged(peer)
            awaitIdle(vm)
            chat.assertAsked(vm, 2)
            vm.confirmPaidAction()
            awaitTrue { chat.owed.heights.isWaiting() }
            assertNull("the first reconnect did not drop the stale contact relay key", chat.relayKey)
            NostrChatBridge.registerPublisher { text, _ ->
                published += text
                NostrChatBridge.PublishResult(1, "synthetic-r1-readable-${published.size}")
            }
            assertTrue("the contact's signed relay identity was not accepted", chat.owed.zbootFromContact(vm, contactKeys, relayKey()))
            chat.owed.heights.advance()
            awaitTrue { chat.seam.submittedOf("KEX") == 1 }
            awaitTrue { published.any { ZBootMessage.isBootMessage(it) } }
            awaitIdle(vm)

            val delivered = published.last { ZBootMessage.isBootMessage(it) }
            val deliveredIdentity = ZBootMessage.parse(delivered)?.senderNostrPubkeyHex
            assertNotNull("fixture: readable relay ACK did not carry a signed ZBOOT", deliveredIdentity)
            val marker = chat.store.getSentNostrBootPubkey(peer)
            assertEquals("fixture: the earlier readable ACK did not write the current identity marker", deliveredIdentity, marker)
            assertEquals("fixture: first reconnect did not pay its KEX", 1, chat.seam.submittedOf("KEX"))
            assertEquals("fixture: ZBOOT was paid instead of delivered by the relay", 0, chat.seam.submittedOf("ZBOOT"))
            assertNotNull("fixture: KEX is not within its one-hour cooldown", chat.owed.paidRecords[kexSlot])
            assertNull("fixture: ZBOOT cooldown exists despite free delivery", chat.owed.paidRecords[zbootSlot])

            // A later user-selected Open mode and a new security-key-change banner. The fixture stores mode in its
            // mutable preferences field; this case tests the reconnect entry, not the picker.
            chat.owed.reset.mode = ConversationMode.OPEN
            chat.keyChanged = true
            assertNotNull("fixture: the contact relay key was not rebound before the later key change", chat.relayKey)
            val failedRelayAttempts = AtomicInteger(0)
            NostrChatBridge.registerPublisher { _, _ ->
                failedRelayAttempts.incrementAndGet()
                NostrChatBridge.PublishResult(0, "synthetic-r1-unreadable")
            }
            val proposalsBefore = chat.seam.proposals()
            vm.resetSendState()
            vm.dismissE2EKeyChanged(peer)
            awaitIdle(vm)
            awaitTrue { peer !in KeyAcceptApi.reconnectsInFlight() }
            awaitIdle(vm)

            // Reachability facts before the behavioral assertion: the prior marker still names this rotation, the
            // free reconnect dropped the peer relay route, and no current ZBOOT reached even a readable relay.
            assertEquals(
                "I2(a): the prior ACK marker changed before the new reconnect",
                deliveredIdentity,
                chat.store.getSentNostrBootPubkey(peer),
            )
            assertNull("I2(b): the key-change reconnect did not clear the old contact relay key", chat.relayKey)
            assertEquals("I2(c): relay-only delivery unexpectedly published after the key was cleared", 0, failedRelayAttempts.get())
            assertEquals("the Open reconnect built an on-chain proposal", proposalsBefore, chat.seam.proposals())
            assertFalse("the new key-change banner was not accepted", chat.keyChanged)
            assertTrue(
                "the ZBOOT-only Open reconnect hid T11 behind an older delivered marker: ${HandshakeHarness.sendMessageState(vm)}",
                HandshakeHarness.sendMessageState(vm).toString().contains(ChatSendCopy.OPEN_NO_ONCHAIN_STEP),
            )
        }
}
