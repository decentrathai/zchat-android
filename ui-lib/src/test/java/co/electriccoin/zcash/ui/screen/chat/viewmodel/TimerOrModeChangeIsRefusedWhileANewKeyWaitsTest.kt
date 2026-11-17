package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.model.MultipleFailureException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** ZC-662: the banner also stops paid controls, including their actual Pay callback and suspended planning. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class TimerOrModeChangeIsRefusedWhileANewKeyWaitsTest(
    private val rememberedOffer: Boolean,
) {
    private val scopes = SendHarnessScopes()
    private val publishes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun open() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetProcess()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterChatNotifier()
    }

    @After
    fun close() {
        scopes.close()
        NostrChatBridge.unregisterPublisher()
        resetProcess()
        Dispatchers.resetMain()
    }

    @Test
    fun `timer Apply refuses before asking a price`() =
        runBlocking {
            val f = fixture()
            f.flag()
            f.vm.setDisappearingTtl(PEER, 3_600L)
            awaitIdle(f.vm)
            f.assertRefused()
        }

    @Test
    fun `already confirmed timer refuses before any local change`() =
        runBlocking {
            val f = fixture()
            f.flag()
            f.vm.setDisappearingTtl(PEER, 3_600L, costConfirmed = true)
            awaitIdle(f.vm)
            f.assertRefused()
        }

    @Test
    fun `timer Pay rechecks a key changed while the question was open`() =
        runBlocking {
            val f = fixture()
            f.vm.setDisappearingTtl(PEER, 3_600L)
            awaitIdle(f.vm)
            f.assertQuote("DISAPPEARING_TIMER", 1, false)
            f.flag()
            f.vm.confirmPaidAction()
            awaitIdle(f.vm)
            f.assertRefused()
        }

    @Test
    fun `Shielded to Tunnel refuses before planning or bootstrap`() =
        runBlocking {
            refusedSwitch(ConversationMode.VAULT, ConversationMode.TUNNEL)
        }

    @Test
    fun `Shielded to Open refuses before planning or bootstrap`() =
        runBlocking {
            refusedSwitch(ConversationMode.VAULT, ConversationMode.OPEN)
        }

    @Test
    fun `Tunnel to Shielded refuses a free mode control`() =
        runBlocking {
            refusedSwitch(ConversationMode.TUNNEL, ConversationMode.VAULT)
        }

    @Test
    fun `same mode selection cannot pin while a key waits`() =
        runBlocking {
            refusedSwitch(ConversationMode.VAULT, ConversationMode.VAULT)
        }

    @Test
    fun `direct confirmed mode entry refuses before changing or pinning`() =
        runBlocking {
            val f = fixture()
            f.flag()
            f.vm.changeConversationMode(PEER, ConversationMode.TUNNEL, costConfirmed = true)
            awaitIdle(f.vm)
            f.assertRefused()
        }

    @Test
    fun `mode Pay rechecks the flag and cannot bootstrap after refusal`() =
        runBlocking {
            val f = fixture()
            f.vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
            awaitIdle(f.vm)
            f.assertQuote("MODE_CHANGE", 2, true)
            f.flag()
            f.vm.confirmPaidAction()
            awaitIdle(f.vm)
            f.assertRefused()
        }

    @Test
    fun `flag raised during the wallet read stops the parked planner`() =
        runBlocking {
            val f = fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val wallet =
                object : PersistableWalletProvider by FixtureSeedWalletProvider {
                    override suspend fun requirePersistableWallet(): PersistableWallet {
                        entered.complete(Unit)
                        release.await()
                        return FixtureSeedWalletProvider.requirePersistableWallet()
                    }
                }
            AutoSpendVm.setField(f.vm, "persistableWalletProvider", wallet)
            try {
                f.vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
                withTimeout(HandshakeHarness.TIMEOUT_MS) { entered.await() }
                f.flag()
                release.complete(Unit)
                awaitIdle(f.vm)
                f.assertRefused()
            } finally {
                release.complete(Unit)
                awaitIdle(f.vm)
            }
        }

    @Test
    fun `control timer quote and actual Pay keep the exact cost and consent`() =
        runBlocking {
            val f = fixture()
            f.vm.setDisappearingTtl(PEER, 3_600L)
            awaitIdle(f.vm)
            f.assertQuote("DISAPPEARING_TIMER", 1, false)
            f.assertUntouched()
            f.vm.confirmPaidAction()
            awaitIdle(f.vm)
            assertEquals(3_600L, f.timer()?.ttlSeconds)
            assertEquals(1, f.seam.proposalRequests.get())
            assertEquals(1, f.seam.submits.get())
            f.assertRequestedWithinQuote(1)
            assertEquals(1, f.noteCount())
            f.assertConsentAndAllowance()
        }

    @Test
    fun `control mode quote counts the control and ZBOOT and Pay spends exactly those two`() =
        runBlocking {
            val f = fixture()
            f.vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
            awaitIdle(f.vm)
            f.assertQuote("MODE_CHANGE", 2, true)
            f.assertUntouched()
            f.vm.confirmPaidAction()
            awaitIdle(f.vm)
            assertEquals(ConversationMode.TUNNEL, f.chat.impl.getConversationMode(PEER))
            assertTrue(f.chat.impl.isConversationModeExplicit(PEER))
            assertEquals(2, f.seam.proposalRequests.get())
            assertEquals(2, f.seam.submits.get())
            f.assertRequestedWithinQuote(2)
            assertEquals(1, f.noteCount())
            f.assertConsentAndAllowance()
        }

    @Test
    fun `control Cancel leaves timer mode pin notes and consent untouched`() =
        runBlocking {
            val f = fixture()
            f.vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
            awaitIdle(f.vm)
            f.assertQuote("MODE_CHANGE", 2, true)
            f.vm.dismissPaidAction()
            awaitIdle(f.vm)
            f.assertUntouched()
        }

    @Test
    fun `control remembered offer dismissal lets both controls work again`() =
        runBlocking {
            val f = fixture()
            f.flag(forceRemember = true)
            KeyAcceptApi.dismiss(f.vm, PEER)
            awaitIdle(f.vm)
            assertFalse(f.chat.flag())
            assertTrue("dismissal retains the old encryption key", f.chat.storedKey() == f.chat.oldContact.publicKey)
            f.vm.setDisappearingTtl(PEER, 3_600L)
            awaitIdle(f.vm)
            f.assertQuote("DISAPPEARING_TIMER", 1, false)
            f.vm.confirmPaidAction()
            awaitIdle(f.vm)
            f.assertRequestedWithinQuote(1)
            f.vm.resetSendState()
            f.vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
            awaitIdle(f.vm)
            f.assertQuote("MODE_CHANGE", 2, true, expectedProposals = 1)
            f.vm.confirmPaidAction()
            awaitIdle(f.vm)
            assertEquals(3, f.seam.proposalRequests.get())
            assertEquals(3, f.seam.submits.get())
            f.assertRequestedWithinQuote(2, start = 1)
            assertEquals(ConversationMode.TUNNEL, f.chat.impl.getConversationMode(PEER))
            assertEquals(3_600L, f.timer()?.ttlSeconds)
            f.assertConsentAndAllowance()
        }

    @Test
    fun `control Keystone stays first when key changes during timer Pay`() =
        runBlocking {
            val f = fixture()
            f.vm.setDisappearingTtl(PEER, 3_600L)
            awaitIdle(f.vm)
            f.assertQuote("DISAPPEARING_TIMER", 1, false)
            f.flag()
            f.selectKeystone()
            f.vm.confirmPaidAction()
            awaitIdle(f.vm)
            f.assertKeystone()
        }

    @Test
    fun `control Keystone stays first when key changes during mode Pay`() =
        runBlocking {
            val f = fixture()
            f.vm.switchConversationMode(PEER, ConversationMode.TUNNEL)
            awaitIdle(f.vm)
            f.assertQuote("MODE_CHANGE", 2, true)
            f.flag()
            f.selectKeystone()
            f.vm.confirmPaidAction()
            awaitIdle(f.vm)
            f.assertKeystone()
        }

    @Test
    fun `control already authenticated incoming controls remain adoptable under the banner`() =
        runBlocking {
            val f = fixture(ConversationMode.TUNNEL)
            f.flag()
            f.chat.impl.setPeerNostrPubkey(PEER, "synthetic-zc662-relay")
            // Handler boundary only: the caller has already authenticated these controls. This is not a plaintext bypass.
            invoke(f.vm, "handleDisappearControl", PEER, ZMSGProtocol.createDisappearSetting(3_600L, 10_000L, PEER))
            invoke(f.vm, "handleModeControl", PEER, ZMSGProtocol.createModeChange(ConversationMode.OPEN, 10_001L, PEER))
            awaitIdle(f.vm)
            assertEquals(3_600L, f.timer()?.ttlSeconds)
            assertEquals(ConversationMode.OPEN, f.chat.impl.getConversationMode(PEER))
            assertFalse(f.chat.impl.isConversationModeExplicit(PEER))
            assertEquals(2, f.noteCount())
            assertEquals(0, f.seam.proposalRequests.get())
            assertTrue(f.chat.flag())
            f.assertConsentAndAllowance()
        }

    @Test
    fun `relay-ready timer also refuses without publishing`() =
        runBlocking {
            val f = fixture(ConversationMode.TUNNEL)
            f.chat.impl.setPeerNostrPubkey(PEER, "synthetic-zc662-relay")
            f.flag()
            recordRelays()
            try {
                f.vm.setDisappearingTtl(PEER, 3_600L)
                awaitIdle(f.vm)
                f.assertRefused()
            } finally {
                NostrChatBridge.unregisterPublisher()
            }
        }

    @Test
    fun `relay-ready direct mode also refuses without publishing`() =
        runBlocking {
            val f = fixture(ConversationMode.TUNNEL)
            f.chat.impl.setPeerNostrPubkey(PEER, "synthetic-zc662-relay")
            f.flag()
            recordRelays()
            try {
                f.vm.changeConversationMode(PEER, ConversationMode.VAULT, costConfirmed = true)
                awaitIdle(f.vm)
                f.assertRefused()
            } finally {
                NostrChatBridge.unregisterPublisher()
            }
        }

    @Test
    fun `control a free timer reaches the recording relay without a price question`() =
        runBlocking {
            val f = fixture(ConversationMode.TUNNEL)
            f.chat.impl.setPeerNostrPubkey(PEER, "synthetic-zc662-relay")
            recordRelays()
            try {
                f.vm.setDisappearingTtl(PEER, 3_600L)
                awaitIdle(f.vm)
                assertEquals(1, publishes.size)
                assertEquals(3_600L, ZMSGProtocol.parseDisappearSetting(publishes.single())?.ttlSeconds)
                assertEquals(3_600L, f.timer()?.ttlSeconds)
                assertEquals(1, f.noteCount())
                assertEquals(SendMessageState.Idle, HandshakeHarness.sendMessageState(f.vm))
                assertEquals(0, f.seam.proposalRequests.get())
                f.assertConsentAndAllowance()
            } finally {
                NostrChatBridge.unregisterPublisher()
            }
        }

    private fun recordRelays() {
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.registerPublisher { memo, _ ->
            publishes += memo
            NostrChatBridge.PublishResult(1, "synthetic-zc662-control-${publishes.size}")
        }
    }

    private fun checkAssertions(checks: List<() -> Unit>) {
        val failures = mutableListOf<Throwable>()
        checks.forEach { check ->
            try {
                check()
            } catch (e: AssertionError) {
                failures += e
            }
        }
        MultipleFailureException.assertEmpty(failures)
    }

    private suspend fun refusedSwitch(initial: ConversationMode, target: ConversationMode) {
        val f = fixture(initial)
        f.flag()
        f.vm.switchConversationMode(PEER, target)
        awaitIdle(f.vm)
        f.assertRefused()
    }

    private suspend fun fixture(mode: ConversationMode = ConversationMode.VAULT): Fixture = Fixture(mode).also { it.open() }

    private inner class Fixture(
        private val initialMode: ConversationMode,
    ) {
        val chat = KeyChangeAcceptChat(PEER, mode = initialMode, consent = false)
        val seam =
            HandshakeSeam(PEER).apply {
                feeProbe = { FeeProbe.Built(Zatoshi(15_000L), usesOrchardInputs = false, transactionCount = 1) }
                proposalFee = 15_000L
            }
        lateinit var accounts: SelectedAccountFeed
        private lateinit var visibleAccounts: MutableStateFlow<List<WalletAccount>?>
        lateinit var vm: ChatViewModel
        private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val real =
            setOf(
                "getConversationMode",
                "getConversationModeOrNull",
                "setConversationMode",
                "isConversationModeExplicit",
                "setConversationModeExplicit",
                "setPendingModeSync",
                "getPendingModeSyncs",
                "clearPendingModeSync",
                "getDisappearingTtl",
                "setDisappearingTtl",
                "getPendingMessages",
                "addPendingMessage",
                "commitPendingMessage",
                "removePendingMessage",
                "getPeerNostrPubkey",
                "setPeerNostrPubkey",
                "advancePeerModeChangeSince",
                "getPeerModeChangeSince",
                "setPendingModeSwitch",
                "clearPendingModeSwitch",
                "getPendingModeSwitches",
            )
        private val store =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                calls += method.name
                val target = if (method.name in real) chat.impl else chat.store
                try {
                    method.invoke(target, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as ZchatPreferences

        suspend fun open() {
            chat.impl.setConversationMode(PEER, initialMode)
            accounts = SelectedAccountFeed.create()
            visibleAccounts = MutableStateFlow(listOf(accounts.getSelectedAccount()))
            val source =
                object : AccountDataSource by accounts {
                    override val allAccounts = visibleAccounts
                }
            vm = HandshakeHarness.chatViewModelWith(store, seam, accountSource = source).also(AutoSpendVm::wireBootstrapFields)
            AutoSpendVm.setField(vm, "seedKeyProvider", SeedDerivedE2EKeyProvider(store) { null })
            val processors =
                ConcurrentHashMap<String, E2EMessageProcessor>().apply {
                    put(
                        "$PEER:${KeyChangeAcceptChat.CONV_ID}",
                        E2EMessageProcessor(
                            ByteArray(32) { 7 },
                            KeyChangeAcceptChat.CONV_ID,
                            true,
                            InMemoryRatchetStateStore(),
                        ),
                    )
                }
            AutoSpendVm.setField(vm, "messageProcessors", processors)
            AutoSpendVm.setField(vm, "ratchetStateStore", InMemoryRatchetStateStore())
            AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
            AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
            AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
            AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
            AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
            calls.clear()
        }

        suspend fun selectKeystone() {
            accounts.keystoneSelected()
            visibleAccounts.value = listOf(accounts.getSelectedAccount())
        }

        fun flag(forceRemember: Boolean = rememberedOffer) {
            if (forceRemember) {
                val newKey = E2EEncryption.generateKeyPair()
                assertTrue(KeyAcceptApi.remember(chat.impl, PEER, newKey.publicKey, chat.kexMemo(newKey), "synthetic-zc662-offer", 1L))
            } else {
                chat.impl.setE2EKeyChanged(PEER, true)
                assertTrue("legacy flag has no refused offer", chat.record() == null)
            }
            assertTrue("the flag must really be stored", chat.flag())
            assertTrue("legacy flag-only also retains the usable OLD key", chat.storedKey() == chat.oldContact.publicKey)
            assertTrue(chat.impl.isE2EKeyExchangeComplete(PEER))
        }

        fun timer() = chat.impl.getDisappearingTtl(PEER)

        fun noteCount() = chat.impl.getPendingMessages().size

        private var quotedTotal = 0L

        fun assertQuote(action: String, count: Int, setup: Boolean, expectedProposals: Int = 0) {
            assertEquals("nothing pays before this quote", expectedProposals, seam.proposalRequests.get())
            val state =
                HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsPaidActionConfirmation
                    ?: throw AssertionError("an exact paid-action question must be on screen")
            assertEquals(action, state.confirmation.action.name)
            assertEquals(count, state.confirmation.onChainMessages)
            assertEquals(count * 17_000L, state.confirmation.totalCost.value)
            assertEquals(setup, state.confirmation.includesChatSetup)
            quotedTotal = state.confirmation.totalCost.value
            assertFalse(chat.owed.consent)
            assertEquals(0, chat.owed.consentWrites.get())
        }

        fun assertRequestedWithinQuote(expectedTransactions: Int, start: Int = 0) {
            val requests = seam.proposalUris.drop(start)
            assertEquals("transaction requests match the exact quote", expectedTransactions, requests.size)
            val actualTotal =
                requests.sumOf { uri ->
                    val amounts =
                        Regex("[?&]amount(?:\\.\\d+)?=([^&]+)")
                            .findAll(uri)
                            .map { BigDecimal(it.groupValues[1]).movePointRight(8).longValueExact() }
                            .toList()
                    assertEquals("one carrier and one Team output", listOf(1_000L, 1_000L), amounts)
                    amounts.sum() + 15_000L
                }
            assertEquals("requested outputs plus fixture fees equal the confirmed quote", quotedTotal, actualTotal)
        }

        fun assertRefused() {
            checkAssertions(
                listOf(
                    {
                        assertEquals(
                            "ZC-662: a control must refuse with the key-change cue",
                            SendMessageState.Error(ChatSendCopy.KEY_CHANGED_VERIFY),
                            HandshakeHarness.sendMessageState(vm),
                        )
                    },
                    { assertEquals("ZC-662: the refusal must name this contact", PEER, vm.keyChangeRefusal.value) },
                    { assertTrue(chat.flag()) },
                ) + untouchedChecks(),
            )
        }

        fun assertKeystone() {
            assertEquals(SendMessageState.Error(ChatSendCopy.KEYSTONE_CHATS_CLOSED), HandshakeHarness.sendMessageState(vm))
            assertEquals(null, vm.keyChangeRefusal.value)
            assertUntouched()
        }

        fun assertUntouched() = checkAssertions(untouchedChecks())

        private fun untouchedChecks(): List<() -> Unit> =
            listOf(
                { assertEquals("ZC-662: stored timer must stay", null, chat.impl.getDisappearingTtl(PEER)) },
                { assertEquals("ZC-662: stored mode must stay", initialMode, chat.impl.getConversationMode(PEER)) },
                { assertFalse("ZC-662: no explicit mode pin", chat.impl.isConversationModeExplicit(PEER)) },
                { assertTrue("ZC-662: no pending mode control", chat.impl.getPendingModeSyncs().isEmpty()) },
                { assertTrue("ZC-662: no persisted note", chat.impl.getPendingMessages().isEmpty()) },
                { assertEquals("ZC-662: no relay publish", 0, publishes.size) },
                { assertEquals("ZC-662: no proposal", 0, seam.proposalRequests.get()) },
                { assertEquals("ZC-662: no submission", 0, seam.submits.get()) },
                { assertFalse("ZC-662: no bootstrap marker", "setOwnBootSent" in calls) },
                { assertFalse("ZC-662: no delivered ZBOOT marker", "setSentNostrBootPubkey" in calls) },
                { assertFalse("ZC-662: no allowance write, even if consumed later", "setZbootQuoted" in calls) },
                { assertConsentAndAllowance() },
            )

        fun assertConsentAndAllowance() {
            assertFalse("standing spend consent stays false", chat.owed.consent)
            assertEquals("no standing consent write", 0, chat.owed.consentWrites.get())
            assertFalse("no owed KEX", PEER in chat.owed.kexOwed)
            assertFalse("no lingering ZBOOT allowance", chat.owed.zbootQuoted[PEER] == true)
            assertFalse("no owed ZBOOT", PEER in chat.owed.zbootOwed)
        }
    }

    private fun invoke(vm: ChatViewModel, name: String, vararg args: Any) {
        try {
            ChatViewModel::class.java.declaredMethods
                .first { it.name == name && it.parameterCount == args.size }
                .apply { isAccessible = true }
                .invoke(vm, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun resetProcess() {
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        NostrChatBridge.unregisterPublisher()
        for (name in listOf("modeSyncInFlight", "reconnectsInFlight", "peerSessionLocks", "peerSessionEpochs", "kexAckedKeys")) {
            ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) }?.let {
                it.isAccessible = true
                (it.get(null) as? MutableCollection<*>)?.clear()
                (it.get(null) as? MutableMap<*, *>)?.clear()
            }
        }
    }

    companion object {
        private const val PEER = "usyntheticzc662controls"

        @JvmStatic
        @Parameterized.Parameters(name = "rememberedOffer={0}")
        fun episodes(): List<Array<Boolean>> = listOf(arrayOf(true), arrayOf(false))
    }
}
