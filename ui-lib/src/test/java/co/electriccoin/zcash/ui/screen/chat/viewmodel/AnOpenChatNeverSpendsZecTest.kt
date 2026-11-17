package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * T7BH-N2 (register ZC-880, HIGH; owner rule W-57, 13 Oct 2026: "OPEN CHAT NEVER PAY and should be available to start
 * this chat without any ZEC on the balance") — for a contact whose chat is Open, ZChat builds no proposal: no key
 * exchange, no answer, no ZBOOT, no owed key exchange; no flow of an Open chat needs a spendable balance.
 *
 * One case per path of the handshake design's table (T7B-HANDSHAKE-SPEC rev 2, section 2.24), read at this base, each on
 * a funded wallet with the standing handshake consent given (the strongest case: nothing else stops a payment):
 * - the answer (KEXACK) when a contact's key exchange arrives, and the responder's re-answer at a chat open;
 * - our ZBOOT at a chat open and on a Reconnect tap when no relay takes it;
 * - our key exchange on a Reconnect or Call tap (and at a chat open) when the contact's relay key is not stored;
 * - Reset encryption when the contact's relay key is not stored;
 * - the "Security key changed" reconnect and the accept of a remembered new key;
 * - an owed key exchange left from before the chat became Open (a block, a chat open).
 * Accept on a chat request in an Open chat is a pin (ZC-1017 made it pay nothing; its Tunnel offer switches first).
 * The paid steps an Open chat skips are said with T11 (`chat_open_no_onchain_step`).
 *
 * Controls: the same KEX answer, Reconnect and owed key exchange in a Tunnel chat pay as today; the owner's sentence, an
 * Open chat used with a zero balance and no consent, builds nothing and asks nothing about money.
 *
 * Step R9 (phones) was not run in this lane; these unit tests exercise the local product paths. Every address, key
 * and transaction id is synthetic.
 */
class AnOpenChatNeverSpendsZecTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        LedgerTests.setUp(sendScopes)
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        LedgerTests.tearDown(sendScopes)
    }

    private fun funded() =
        Zc101FakeAccountDataSource(
            runBlocking {
                syntheticZashiAccount().let {
                    it.copy(unified = it.unified.copy(ironwood = WalletBalance(Zatoshi(1_000_000L), Zatoshi(0), Zatoshi(0))))
                }
            },
        )

    private fun world(
        mode: ConversationMode,
        exchangeComplete: Boolean,
        consent: Boolean = true,
        funded: Boolean = true,
    ) = LedgerWorld(
        mode = mode,
        exchangeComplete = exchangeComplete,
        consent = consent,
        accountSource = if (funded) funded() else null,
    )

    private fun questionOf(vm: ChatViewModel): String? =
        HandshakeHarness.sendMessageState(vm).javaClass.simpleName.takeIf {
            it == "NeedsPaidActionConfirmation" || it == "NeedsHandshakeSpendConsent"
        }

    private fun saysT11(vm: ChatViewModel): Boolean = T11_START in HandshakeHarness.sendMessageState(vm).toString()

    /** A zero-balance Open chat must not replace a skipped free step with a funds failure. */
    private fun fundsErrorOf(vm: ChatViewModel): String? =
        (HandshakeHarness.sendMessageState(vm) as? SendMessageState.Error)?.message?.takeIf { message ->
            message == ChatSendCopy.INSUFFICIENT_BALANCE_SHORT ||
                message == ChatSendCopy.FAILED_FUNDS_CONFIRMING ||
                message.contains("insufficient", ignoreCase = true) ||
                message.contains("not enough ZEC", ignoreCase = true) ||
                message.contains("balance", ignoreCase = true)
        }

    // ---- the answer to a contact's key exchange ------------------------------------------------------------------

    /** A mode-backed store for all mode readers on the inbound and automatic paths. */
    private fun inMode(
        store: ZchatPreferences,
        mode: ConversationMode,
        peer: String,
    ): ZchatPreferences {
        val modes = ConcurrentHashMap<String, ConversationMode>()
        return Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getConversationMode" -> modes[args!![0] as String] ?: mode
                "getConversationModeOrNull" -> modes[args!![0] as String] ?: mode
                "getTunnelModePeers" ->
                    (listOf(peer) + modes.keys).distinct().filter { (modes[it] ?: mode) == ConversationMode.TUNNEL }
                "setConversationMode" -> {
                    val peer = args!![0] as String
                    val next = args[1] as ConversationMode?
                    if (next == null) modes.remove(peer) else modes[peer] = next
                    null
                }
                else ->
                    try {
                        method.invoke(store, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
    }

    /**
     * A known contact's key exchange arrives (its key stored, not a first exchange, so no Accept hold): the answer is
     * automatic under the standing consent. @return the proposals built.
     */
    private data class KexArrival(
        val proposals: Int,
        val budgetUsed: Int,
    )

    private suspend fun kexArrives(mode: ConversationMode): KexArrival {
        val peer = "usyntheticn2kexarrives0001"
        val contact = E2EEncryption.generateKeyPair()
        val prefs =
            HandshakePrefs(peer).apply {
                e2ePeerKey[peer] = contact.publicKey
                receivedKex[peer] = contact.publicKey
            }
        val seam = HandshakeSeam(peer)
        val vm = HandshakeHarness.chatViewModelWith(inMode(prefs.proxy, mode, peer), seam)
        HandshakeHarness.handleKexMemo(vm, HandshakeHarness.kexMemoFrom(peer, contact), "synthetic-n2-kex-$mode")
        awaitIdle(vm)
        return KexArrival(seam.proposalRequests.get(), prefs.budgetConsumed.get())
    }

    @Test
    fun `the answer to a contact's key exchange is not paid in an Open chat`() =
        runBlocking {
            val arrival = kexArrives(ConversationMode.OPEN)
            assertEquals(
                "an Open chat paid the answer (KEXACK) to a key exchange under the standing consent; D3 budgetUsed=${arrival.budgetUsed}",
                0,
                arrival.proposals,
            )
        }

    @Test
    fun `control - the answer to a contact's key exchange is paid in a Tunnel chat`() =
        runBlocking {
            val built = kexArrives(ConversationMode.TUNNEL).proposals
            assertTrue("fixture: the Tunnel chat's answer was not paid ($built proposals)", built >= 1)
        }

    @Test
    fun `the responder's re-answer at a chat open is not paid in an Open chat`() =
        runBlocking {
            val w = world(ConversationMode.OPEN, exchangeComplete = false)
            w.asResponder()
            val vm = w.openVm()

            vm.retryKexAckIfResponder(w.peer)
            awaitIdle(vm)

            assertEquals("an Open chat re-paid the answer at a chat open", 0, w.seam.proposals.get())
        }

    // ---- our ZBOOT and our key exchange (chat open, Reconnect, Call) --------------------------------------------

    @Test
    fun `our ZBOOT at a chat open is not paid on-chain in an Open chat when no relay takes it`() =
        runBlocking {
            val w = world(ConversationMode.OPEN, exchangeComplete = true)
            val vm = w.openVm()

            AutoSpendVm.sendNostrBoot(vm, w.peer)
            awaitIdle(vm)

            assertEquals("an Open chat paid an on-chain ZBOOT at a chat open", 0, w.seam.proposals.get())
            assertNull("an Open chat asked about money at a chat open", questionOf(vm))
        }

    @Test
    fun `a Reconnect tap on a complete Open chat pays no ZBOOT`() =
        runBlocking {
            val w = world(ConversationMode.OPEN, exchangeComplete = true)
            val vm = w.openVm()

            vm.reSendOurIdentity(w.peer)
            awaitIdle(vm)

            assertEquals("Reconnect in an Open chat paid an on-chain ZBOOT", 0, w.seam.proposals.get())
        }

    @Test
    fun `a Reconnect or Call tap in an Open chat whose relay key is not stored pays no key exchange`() =
        runBlocking {
            val w = world(ConversationMode.OPEN, exchangeComplete = false)
            val vm = w.openVm()

            vm.reSendOurIdentity(w.peer)
            awaitIdle(vm)

            assertEquals("an Open chat paid a key exchange because the contact's relay key was not stored", 0, w.seam.proposals.get())
        }

    @Test
    fun `control - the same Reconnect tap in a Tunnel chat pays its key exchange`() =
        runBlocking {
            val w = world(ConversationMode.TUNNEL, exchangeComplete = false)
            val vm = w.openVm()

            vm.reSendOurIdentity(w.peer)
            awaitIdle(vm)

            assertEquals("fixture: the Tunnel chat's key exchange was not paid", 1, w.seam.proposals.get())
        }

    // ---- other on-chain ZBOOT paths -------------------------------------------------------------------------------

    @Test
    fun `the reply to a contact's verified ZBOOT in an Open chat is relay only`() =
        runBlocking {
            val w = world(ConversationMode.OPEN, exchangeComplete = true)
            val contact = E2EEncryption.generateKeyPair()
            w.base.peerKeys[w.peer] = contact.publicKey
            val vm = w.openVm()
            val modeStore = inMode(w.prefs.proxy, ConversationMode.OPEN, w.peer)
            PaymentHarness.setField(vm, "zchatPreferences", modeStore)
            assertEquals(
                "fixture: the signed ZBOOT was not received in Open mode",
                ConversationMode.OPEN,
                modeStore.getConversationMode(w.peer),
            )
            assertEquals("fixture: the contact's verified key is missing", contact.publicKey, w.base.peerKeys[w.peer])
            val convId = w.base.convIdFor(w.peer)
            val relayKey = "ab".repeat(32)
            val relay = "wss://relay.synthetic.example"
            val signature = E2EEncryption.sign(contact.privateKey, ZBootMessage.signedDataFor(convId, relayKey, relay, 0L))
            val raw = ZBootMessage(convId, relayKey, relay, signature, epoch = 0L).serialize()

            val taken =
                try {
                    ChatViewModel::class.java
                        .getDeclaredMethod("routeIncomingBoot", String::class.java, String::class.java)
                        .apply { isAccessible = true }
                        .invoke(vm, w.peer, raw) as Boolean
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            awaitIdle(vm)

            assertTrue("fixture: the signed contact ZBOOT was not taken", taken)
            assertEquals("a reply ZBOOT to a contact's ZBOOT paid in an Open chat", 0, w.seam.proposals.get())
        }

    @Test
    fun `the ZBOOT after a contact's KEXACK is not paid in an Open chat`() =
        runBlocking {
            // The KEX was legitimately sent while this was Tunnel; the user chose Open before its answer arrived.
            val w = world(ConversationMode.TUNNEL, exchangeComplete = false)
            val vm = w.openVm()
            vm.sendKEXMessage(w.peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            assertEquals("fixture: no earlier Tunnel KEX was sent", 1, w.seam.proposals.get())
            w.base.modes[w.peer] = ConversationMode.OPEN
            val beforeAnswer = w.seam.proposals.get()
            val contact = E2EEncryption.generateKeyPair()
            val memo =
                ZMSGProtocol.createV4KEXAckMessage(
                    w.base.convIdFor(w.peer),
                    w.peer,
                    E2EEncryption.createKEXAckPayload(w.peer, contact.publicKey, contact.privateKey),
                )
            val id = TransactionId.new("%064x".format(880))
            val at = Instant.now()
            val incoming =
                ReceiveTransaction.Success(
                    id,
                    Zatoshi(1_000L),
                    at,
                    1,
                    emptyList(),
                    ScanPassHarness.overview(id, LedgerWorld.H, at, sent = false, amount = 1_000L),
                    null,
                )
            val repository =
                Proxy.newProxyInstance(
                    TransactionRepository::class.java.classLoader,
                    arrayOf(TransactionRepository::class.java),
                ) { proxy, method, args ->
                    when (method.name) {
                        "getMemos" -> listOf(memo)
                        "getAllRecipientAddresses" -> emptyList<String>()
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.get(0)
                        "toString" -> "SyntheticIncomingKexAck"
                        else -> error("unexpected TransactionRepository.${method.name}")
                    }
                } as TransactionRepository
            val synchronizer =
                Proxy.newProxyInstance(
                    Synchronizer::class.java.classLoader,
                    arrayOf(Synchronizer::class.java),
                ) { proxy, method, args ->
                    when (method.name) {
                        "getFullyScannedHeight", "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(LedgerWorld.H))
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.get(0)
                        "toString" -> "SyntheticKexAckHeight"
                        else -> error("unexpected Synchronizer.${method.name}")
                    }
                } as Synchronizer

            ScanPassHarness.scan(
                vm,
                w.prefs.proxy,
                ScanPassHarness.noContacts(),
                repository,
                synchronizer,
                listOf(incoming),
                userAddress = HandshakeHarness.SELF,
            )
            awaitIdle(vm)

            assertEquals("fixture: the contact's KEXACK was not taken", contact.publicKey, w.base.peerKeys[w.peer])
            assertEquals("the KEXACK caused an on-chain ZBOOT after the chat became Open", beforeAnswer, w.seam.proposals.get())
        }

    @Test
    fun `a quoted ZBOOT owed before a switch to Open is not paid and stays owed`() =
        runBlocking {
            val w = world(ConversationMode.OPEN, exchangeComplete = true)
            val vm = w.openVm()
            w.prefs.zbootQuoted += w.peer
            w.prefs.zbootOwed += w.peer

            w.blockTick(vm, LedgerWorld.H + 1L)

            assertEquals("an Open chat paid its earlier quoted ZBOOT on a block tick", 0, w.seam.proposals.get())
            assertTrue("the earlier ZBOOT quote was dropped before a later mode switch", w.peer in w.prefs.zbootQuoted)
            assertTrue("the owed ZBOOT mark was dropped before a later mode switch", w.peer in w.prefs.zbootOwed)
        }

    @Test
    fun `Reconnect in an Open chat with a failed setup offer asks no money question`() =
        runBlocking {
            val w = world(ConversationMode.OPEN, exchangeComplete = false)
            val vm = w.openVm()
            val entry =
                w.ledger.seedLive(
                    "KEX",
                    w.peer,
                    w.ourKeys.publicKey,
                    startedAt = System.currentTimeMillis() - 1_000L,
                    attemptHeight = LedgerWorld.H,
                    accountHash = w.accountHash(),
                )
            w.ledger.seedNotSent(entry, LedgerWorld.H + LedgerWorld.MARGIN)
            assertEquals("fixture: the failed setup offer is not stored", "NOT_SENT", w.ledger.one("KEX")?.state)

            vm.reSendOurIdentity(w.peer)
            awaitIdle(vm)

            assertNull("Reconnect offered a paid Retry for a failed setup in an Open chat", questionOf(vm))
            assertEquals("Reconnect paid the failed setup without a question", 0, w.seam.proposals.get())
        }

    // ---- an owed key exchange left from another mode ---------------------------------------------------------------

    @Test
    fun `an owed key exchange left from before the chat became Open is not paid and stays owed`() =
        runBlocking {
            val w = world(ConversationMode.OPEN, exchangeComplete = false)
            val vm = w.openVm()
            w.prefs.kexOwed += w.peer

            vm.retryOwedKeyExchange(w.peer)
            awaitIdle(vm)

            assertEquals("an Open chat paid a key exchange owed from another mode", 0, w.seam.proposals.get())
            assertTrue("the owed mark was dropped: a later switch to Tunnel could not pay it on its question", w.peer in w.prefs.kexOwed)
        }

    @Test
    fun `control - the same owed key exchange in a Tunnel chat is paid once`() =
        runBlocking {
            val w = world(ConversationMode.TUNNEL, exchangeComplete = false)
            val vm = w.openVm()
            w.prefs.kexOwed += w.peer

            vm.retryOwedKeyExchange(w.peer)
            awaitIdle(vm)

            assertEquals("fixture: the Tunnel chat's owed key exchange was not paid", 1, w.seam.proposals.get())
        }

    // ---- Accept on a chat request (pin, ZC-1017) ------------------------------------------------------------------

    @Test
    fun `pin - Accept on a chat request in an Open chat pays nothing`() =
        runBlocking {
            val w = world(ConversationMode.OPEN, exchangeComplete = false)
            val theirs = w.asResponder()
            val vm = w.openVm()
            PaymentHarness.setField(vm, "zchatPreferences", holding(w, theirs))

            vm.acceptInboundKexRequest(w.peer)
            awaitIdle(vm)

            assertEquals("Accept on a chat request paid in an Open chat", 0, w.seam.proposals.get())
        }

    /** [w]'s store with the Accept hold on [key] (the store keeps no hold of its own). */
    private fun holding(
        w: LedgerWorld,
        key: String,
    ): ZchatPreferences {
        val hold = ConcurrentHashMap<String, String>().apply { put(w.peer, key) }
        return Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getInboundKexAwaitingAccept" -> hold[args!![0] as String]
                "setInboundKexAwaitingAccept" -> {
                    val value = args!![1] as String?
                    if (value == null) hold.remove(args[0] as String) else hold[args[0] as String] = value
                    true
                }
                else ->
                    try {
                        method.invoke(w.prefs.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
    }

    // ---- Reset, the key-change reconnect and the accept of a new key ------------------------------------------------

    @Test
    fun `Reset encryption in an Open chat whose relay key is not stored asks nothing, pays nothing and says why`() =
        runBlocking {
            val f = ResetFixture("usyntheticn2resetopen00001", ConversationMode.OPEN)
            val vm = f.viewModel()

            vm.resetSecureSession(f.peer)
            awaitIdle(vm)

            assertNull("Reset in an Open chat asked to pay", questionOf(vm))
            assertEquals("Reset in an Open chat built a proposal", 0, f.seam.proposalRequests.get())
            f.assertWiped()
            assertTrue("the skipped paid step is not explained (T11): ${HandshakeHarness.sendMessageState(vm)}", saysT11(vm))
        }

    @Test
    fun `the key-change reconnect in an Open chat asks nothing, pays nothing, accepts the key and says why`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER_RECONNECT, consent = true, mode = ConversationMode.OPEN)
            val vm = chat.viewModel()

            vm.dismissE2EKeyChanged(PEER_RECONNECT)
            awaitIdle(vm)
            awaitTrue { PEER_RECONNECT !in KeyAcceptApi.reconnectsInFlight() }
            awaitIdle(vm)

            assertNull("the reconnect of an Open chat asked to pay", questionOf(vm))
            assertEquals("the reconnect of an Open chat paid", 0, chat.seam.submitted.size)
            assertFalse("the new key was not accepted (the free part)", chat.keyChanged)
            assertTrue("the skipped paid step is not explained (T11): ${HandshakeHarness.sendMessageState(vm)}", saysT11(vm))
        }

    @Test
    fun `the key-change reconnect without standing consent in an Open chat asks nothing and pays nothing`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER_RECONNECT, consent = false, mode = ConversationMode.OPEN)
            val vm = chat.viewModel()

            vm.dismissE2EKeyChanged(PEER_RECONNECT)
            awaitIdle(vm)
            awaitTrue { PEER_RECONNECT !in KeyAcceptApi.reconnectsInFlight() }
            awaitIdle(vm)

            assertNull("the Open reconnect without consent asked to pay", questionOf(vm))
            assertEquals("the Open reconnect without consent paid", 0, chat.seam.submitted.size)
            assertFalse("the Open reconnect without consent did not accept the new key", chat.keyChanged)
            assertTrue("the skipped paid step is not explained (T11)", saysT11(vm))
        }

    @Test
    fun `the accept of a remembered new key in an Open chat asks nothing, pays nothing, adopts it and says why`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER_ACCEPT, mode = ConversationMode.OPEN, consent = true)
            val vm = chat.viewModel()
            val newKey = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, newKey)
            assertTrue("fixture: the new key is not remembered", chat.flag())

            val asked = chat.accept(vm, newKey.publicKey, pay = true)

            assertNull("the accept in an Open chat asked to pay: $asked", asked)
            assertEquals("the accept in an Open chat built a proposal", 0, chat.seam.proposals())
            chat.assertAdopted(newKey, "after the accept in an Open chat")
            assertTrue("the skipped paid step is not explained (T11): ${HandshakeHarness.sendMessageState(vm)}", saysT11(vm))
        }

    // ---- the owner's sentence: an Open chat with a zero balance ------------------------------------------------------

    /** Each trigger gets a fresh zero-balance wallet, so one RED assertion cannot hide another path. */
    private suspend fun zeroBalanceWithConsent(
        trigger: String,
        responder: Boolean = false,
        action: (LedgerWorld, ChatViewModel) -> Unit,
    ) {
        val w = world(ConversationMode.OPEN, exchangeComplete = false, consent = true, funded = false)
        if (responder) w.asResponder()
        val vm = w.openVm()
        action(w, vm)
        awaitIdle(vm)
        assertNull("$trigger in a zero-balance Open chat asked about money", questionOf(vm))
        assertNull("$trigger in a zero-balance Open chat showed a funds error", fundsErrorOf(vm))
        assertEquals("$trigger in a zero-balance Open chat built a paid setup", 0, w.seam.proposals.get())
    }

    @Test
    fun `zero balance with consent - opening an Open chat needs no funds`() =
        runBlocking {
            zeroBalanceWithConsent("chat open") { w, vm -> vm.ensureNostrBootstrapSent(w.peer) }
        }

    @Test
    fun `zero balance with consent - Reconnect in an Open chat needs no funds`() =
        runBlocking {
            zeroBalanceWithConsent("Reconnect") { w, vm -> vm.reSendOurIdentity(w.peer) }
        }

    @Test
    fun `zero balance with consent - Call in an Open chat needs no funds`() =
        runBlocking {
            // AndroidChat's Call callback uses this exact forced bootstrap entry.
            zeroBalanceWithConsent("Call") { w, vm -> vm.ensureNostrBootstrapSent(w.peer, force = true) }
        }

    @Test
    fun `zero balance with consent - responder re-answer in an Open chat needs no funds`() =
        runBlocking {
            zeroBalanceWithConsent("responder re-answer", responder = true) { w, vm -> vm.retryKexAckIfResponder(w.peer) }
        }

    @Test
    fun `control - with a zero balance and no consent an Open chat's open, Reconnect and Call build nothing and ask nothing`() =
        runBlocking {
            val w = world(ConversationMode.OPEN, exchangeComplete = false, consent = false, funded = false)
            w.asResponder()
            val vm = w.openVm()

            vm.ensureNostrBootstrapSent(w.peer)
            awaitIdle(vm)
            assertNoConsentControlStep("chat open", w, vm)
            vm.reSendOurIdentity(w.peer)
            awaitIdle(vm)
            assertNoConsentControlStep("Reconnect", w, vm)
            // AndroidChat's Call callback invokes this forced bootstrap.
            vm.ensureNostrBootstrapSent(w.peer, force = true)
            awaitIdle(vm)
            assertNoConsentControlStep("Call", w, vm)
            vm.retryKexAckIfResponder(w.peer)
            awaitIdle(vm)
            assertNoConsentControlStep("responder re-answer", w, vm)
        }

    private fun assertNoConsentControlStep(
        trigger: String,
        w: LedgerWorld,
        vm: ChatViewModel,
    ) {
        assertEquals("$trigger in a zero-balance Open chat without consent built a proposal", 0, w.seam.proposals.get())
        assertNull("$trigger in a zero-balance Open chat without consent asked about money", questionOf(vm))
        assertNull("$trigger in a zero-balance Open chat without consent showed a funds error", fundsErrorOf(vm))
    }

    private companion object {
        const val PEER_RECONNECT = "usyntheticn2reconnectopen01"
        const val PEER_ACCEPT = "usyntheticn2acceptopen00001"
        const val T11_START = "This is an Open chat, so ZChat paid nothing and sent nothing on-chain."

        suspend fun awaitTrue(condition: () -> Boolean) = HandshakeHarness.awaitTrue(condition)
    }
}
