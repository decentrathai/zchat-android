package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections

/** ZC-662: an automatic, free mode retry waits silently for the key decision and retains its original payload. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class PendingModeChangeIsNotResentWhileANewKeyWaitsTest(
    private val rememberedOffer: Boolean,
) {
    private val scopes = SendHarnessScopes()
    private val published: MutableList<Publish> = Collections.synchronizedList(mutableListOf())
    private var acknowledgements = 1
    private var decisionMade = false

    private data class Publish(
        val recipient: String,
        val memo: String,
        val afterDecision: Boolean,
    )

    @Before
    fun open() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetProcess()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.registerPublisher { memo, recipient ->
            published += Publish(recipient, memo, decisionMade)
            NostrChatBridge.PublishResult(acknowledgements, "synthetic-zc662-retry-${published.size}")
        }
    }

    @After
    fun close() {
        scopes.close()
        NostrChatBridge.unregisterPublisher()
        resetProcess()
        Dispatchers.resetMain()
    }

    @Test
    fun `flagged retry leaves its exact pending entry and changes no screen state`() =
        runBlocking {
            val f = fixture()
            f.flag()
            f.preserveExistingCue()
            val before = f.pending()
            retry(f.vm)
            assertEquals("ZC-662: the flag must hold every automatic mode publish", 0, published.size)
            assertEquals("ZC-662: preserve exact mode and since while held", before, f.pending())
            assertTrue("ZC-662: flagged relay key is not read", f.relayReads.isEmpty())
            f.assertSilentAndFree()
        }

    @Test
    fun `Not their key releases one original mode control only after the real decision`() =
        runBlocking {
            val f = fixture()
            // Dismissal has a remembered offer in both parameter episodes; legacy flag-only has no such decision.
            f.flag(forceRemember = true)
            retry(f.vm)
            val sendsBeforeDecision = published.size
            val pendingBeforeDecision = f.pending()
            KeyAcceptApi.dismiss(f.vm, PEER)
            awaitIdle(f.vm)
            assertFalse(f.chat.flag())
            assertTrue("Not their key retains old encryption key", f.chat.storedKey() == f.chat.oldContact.publicKey)
            assertTrue("Not their key retains relay identity", f.chat.impl.getPeerNostrPubkey(PEER) == RELAY)
            decisionMade = true
            retry(f.vm)
            retry(f.vm)
            assertEquals("ZC-662: no publish before Not their key", 0, sendsBeforeDecision)
            assertEquals("ZC-662: hold original payload until decision", original(), pendingBeforeDecision[PEER])
            assertEquals("exactly one publish over all retries", 1, published.size)
            assertTrue("ZC-662: publish follows the decision", published.single().afterDecision)
            assertPayload(published.single(), RELAY, ConversationMode.VAULT, SINCE)
            assertTrue(f.pending().isEmpty())
            f.assertSilentAndFree()
        }

    @Test
    fun `a mixed pass serves the unflagged contact and retains only the flagged contact`() =
        runBlocking {
            val f = fixture()
            f.flag()
            f.chat.impl.setConversationMode(OTHER, ConversationMode.OPEN)
            f.chat.impl.setPeerNostrPubkey(OTHER, OTHER_RELAY)
            f.chat.impl.setPendingModeSync(OTHER, ConversationMode.OPEN.name, SINCE + 1)
            retry(f.vm)
            assertEquals("ZC-662: one held contact must not hold or expose another", listOf(OTHER_RELAY), published.map { it.recipient })
            assertEquals(mapOf(PEER to original()), f.pending())
            assertEquals(listOf(OTHER), f.relayReads)
            assertPayload(published.single(), OTHER_RELAY, ConversationMode.OPEN, SINCE + 1)
            f.assertSilentAndFree()
        }

    @Test
    fun `control no flag publishes original mode and since once and clears on acknowledgement`() =
        runBlocking {
            val f = fixture()
            retry(f.vm)
            retry(f.vm)
            assertEquals(1, published.size)
            assertPayload(published.single(), RELAY, ConversationMode.VAULT, SINCE)
            assertTrue(f.pending().isEmpty())
            f.assertSilentAndFree()
        }

    @Test
    fun `control zero acknowledgements retain original entry and release the in-flight claim`() =
        runBlocking {
            val f = fixture()
            acknowledgements = 0
            retry(f.vm)
            assertEquals(mapOf(PEER to original()), f.pending())
            acknowledgements = 1
            retry(f.vm)
            assertEquals("a zero-ack attempt must not strand its claim", 2, published.size)
            published.forEach { assertPayload(it, RELAY, ConversationMode.VAULT, SINCE) }
            assertTrue(f.pending().isEmpty())
            f.assertSilentAndFree()
        }

    @Test
    fun `control Keystone holds the entry without showing a refusal or spending`() =
        runBlocking {
            val f = fixture()
            f.selectKeystone()
            retry(f.vm)
            assertEquals(0, published.size)
            assertEquals(mapOf(PEER to original()), f.pending())
            f.assertSilentAndFree()
        }

    @Test
    fun `control malformed and superseded entries are dropped without publishing`() =
        runBlocking {
            val f = fixture()
            f.chat.impl.setPendingModeSync(PEER, "not-a-mode", SINCE)
            f.chat.impl.setConversationMode(OTHER, ConversationMode.TUNNEL)
            f.chat.impl.setPeerNostrPubkey(OTHER, OTHER_RELAY)
            f.chat.impl.setPendingModeSync(OTHER, ConversationMode.OPEN.name, SINCE + 1)
            retry(f.vm)
            assertEquals(0, published.size)
            assertTrue("malformed and superseded rows leave the retry store", f.pending().isEmpty())
            // Subsequent valid rows prove that both cleanup paths released their process claims.
            f.chat.impl.setPendingModeSync(PEER, ConversationMode.VAULT.name, SINCE + 2)
            f.chat.impl.setPendingModeSync(OTHER, ConversationMode.TUNNEL.name, SINCE + 3)
            retry(f.vm)
            assertEquals(2, published.size)
            assertPayload(published.single { it.recipient == RELAY }, RELAY, ConversationMode.VAULT, SINCE + 2)
            assertPayload(published.single { it.recipient == OTHER_RELAY }, OTHER_RELAY, ConversationMode.TUNNEL, SINCE + 3)
            assertTrue(f.pending().isEmpty())
            f.assertSilentAndFree()
        }

    @Test
    fun `control no relay key retains pending until a channel appears`() =
        runBlocking {
            val f = fixture()
            f.chat.impl.setPeerNostrPubkey(PEER, null)
            retry(f.vm)
            assertEquals(0, published.size)
            assertEquals(mapOf(PEER to original()), f.pending())
            f.chat.impl.setPeerNostrPubkey(PEER, RELAY)
            retry(f.vm)
            assertEquals(1, published.size)
            assertPayload(published.single(), RELAY, ConversationMode.VAULT, SINCE)
            assertTrue(f.pending().isEmpty())
            f.assertSilentAndFree()
        }

    private fun original() = ZchatPreferences.PendingModeSync(ConversationMode.VAULT.name, SINCE)

    private fun assertPayload(p: Publish, relay: String, mode: ConversationMode, since: Long) {
        assertEquals(relay, p.recipient)
        val parsed = ZMSGProtocol.parseModeChange(p.memo) ?: throw AssertionError("retry published no valid mode control")
        assertEquals(mode, parsed.mode)
        assertEquals(since, parsed.sinceMillis)
    }

    private suspend fun retry(vm: ChatViewModel) {
        try {
            ChatViewModel::class.java
                .getDeclaredMethod("retryPendingModeSyncs")
                .apply { isAccessible = true }
                .invoke(vm)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
        awaitIdle(vm)
    }

    private suspend fun fixture(): Fixture = Fixture().also { it.open() }

    private inner class Fixture {
        val chat = KeyChangeAcceptChat(PEER, consent = false)
        val seam = HandshakeSeam(PEER)
        lateinit var accounts: SelectedAccountFeed
        private lateinit var visibleAccounts: MutableStateFlow<List<WalletAccount>?>
        val relayReads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        lateinit var vm: ChatViewModel
        private var stateBefore: SendMessageState = SendMessageState.Idle
        private var refusalBefore: String? = null
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
                "getPeerNostrPubkey",
                "setPeerNostrPubkey",
                "getPendingMessages",
                "addPendingMessage",
                "commitPendingMessage",
                "removePendingMessage",
                "getDisappearingTtl",
                "setDisappearingTtl",
            )
        private val store =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                if (method.name == "getPeerNostrPubkey") relayReads += args!![0] as String
                try {
                    method.invoke(if (method.name in real) chat.impl else chat.store, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as ZchatPreferences

        suspend fun open() {
            chat.impl.setConversationMode(PEER, ConversationMode.VAULT)
            chat.impl.setPeerNostrPubkey(PEER, RELAY)
            chat.impl.setPendingModeSync(PEER, ConversationMode.VAULT.name, SINCE)
            accounts = SelectedAccountFeed.create()
            visibleAccounts = MutableStateFlow(listOf(accounts.getSelectedAccount()))
            val source =
                object : AccountDataSource by accounts {
                    override val allAccounts = visibleAccounts
                }
            vm = HandshakeHarness.chatViewModelWith(store, seam, accountSource = source).also(AutoSpendVm::wireBootstrapFields)
        }

        suspend fun selectKeystone() {
            accounts.keystoneSelected()
            visibleAccounts.value = listOf(accounts.getSelectedAccount())
        }

        fun preserveExistingCue() {
            assertTrue(chat.sendsRefused(vm))
            stateBefore = HandshakeHarness.sendMessageState(vm)
            refusalBefore = vm.keyChangeRefusal.value
        }

        fun flag(forceRemember: Boolean = rememberedOffer) {
            if (forceRemember) {
                val offered = E2EEncryption.generateKeyPair()
                assertTrue(
                    KeyAcceptApi.remember(
                        chat.impl,
                        PEER,
                        offered.publicKey,
                        chat.kexMemo(offered),
                        "synthetic-zc662-retry-offer",
                        1L,
                    ),
                )
            } else {
                chat.impl.setE2EKeyChanged(PEER, true)
                assertTrue("legacy flag has no refused offer", chat.record() == null)
            }
            assertTrue(chat.flag())
            assertTrue("the old encryption key remains usable", chat.storedKey() == chat.oldContact.publicKey)
        }

        fun pending() = chat.impl.getPendingModeSyncs()

        fun assertSilentAndFree() {
            assertEquals("automatic retry preserves send state", stateBefore, HandshakeHarness.sendMessageState(vm))
            assertEquals("automatic retry preserves existing refusal banner", refusalBefore, vm.keyChangeRefusal.value)
            assertEquals("retry never proposes on-chain", 0, seam.proposalRequests.get())
            assertEquals("retry never submits", 0, seam.submits.get())
            assertFalse(chat.owed.consent)
            assertEquals(0, chat.owed.consentWrites.get())
            assertTrue(chat.owed.kexOwed.isEmpty())
            assertTrue(chat.owed.zbootQuoted.isEmpty())
            assertTrue(chat.owed.zbootOwed.isEmpty())
            assertTrue("retry writes no note", chat.impl.getPendingMessages().isEmpty())
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
        private const val PEER = "usyntheticzc662retry"
        private const val OTHER = "usyntheticzc662other"
        private const val RELAY = "synthetic-zc662-relay"
        private const val OTHER_RELAY = "synthetic-zc662-other-relay"
        private const val SINCE = 10_000L

        @JvmStatic
        @Parameterized.Parameters(name = "rememberedOffer={0}")
        fun episodes(): List<Array<Boolean>> = listOf(arrayOf(true), arrayOf(false))
    }
}
