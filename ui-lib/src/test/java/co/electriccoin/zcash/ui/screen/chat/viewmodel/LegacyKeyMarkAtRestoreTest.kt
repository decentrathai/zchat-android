package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.kexFrom
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * W-72 (register ZC-852), SPEC_F_20261019 rule R2 as settled in SPEC_DECISIONS D2, RED test 4: a wallet restored on this
 * build reads its history again, and a contact key it stores then is as unproven as one ZChat 2.12.4 stored. A key stored
 * for the first time is stored with a pre-update mark in the same commit (storePeerKey's fifth argument) when its memo is
 * mined at or below the network upgrade height (mainnet 3,428,143), or when this key store has not yet finished one
 * complete history read (a restore; no exception for an answer under one of our own ids, whose records a restore rebuilds).
 * The pass records that read once it acted on every key offer (setLegacyHistoryRead). A key from a memo above the height
 * stored after that read gets no mark, nor does a repeat of the stored key, and a network with no upgrade height marks only
 * by the history rule.
 *
 * Today the key's commit has no mark whatever its height and the pass records nothing. Every address, key and id is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyKeyMarkAtRestoreTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
        ClaimMarkChat.clearTunnelQueue()
        ChainTiming.current = ChainTiming.MAINNET
    }

    @After
    fun tearDown() {
        ChainTiming.current = ChainTiming.MAINNET
        NostrChatBridge.unregisterPublisher()
        ClaimMarkChat.clearTunnelQueue()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `(4) a key from a memo mined at the upgrade height is marked`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 72041), firstContact = true)
            val vm = chat.viewModel()
            val contact = E2EEncryption.generateKeyPair()

            handleKex(vm, kexFrom(chat.peer, contact, "ZC72R401"), "synthetic-w72-r2-at", ChainTiming.MAINNET_UPGRADE_HEIGHT)
            awaitIdle(vm)

            assertEquals("fixture: the key is stored", contact.publicKey, chat.prefs.e2ePeerKey[chat.peer])
            assertEquals(
                "a key from a memo mined at the upgrade height has no pre-update mark",
                contact.publicKey,
                chat.maps.legacy[chat.peer]
            )
            assertEquals(
                "the key and its mark are not one commit",
                listOf("${chat.peer} ${contact.publicKey} false false legacy"),
                chat.maps.keyCommits
            )
            assertEquals("the restore rule paid", 0, chat.proposals())
        }

    @Test
    fun `(4) a key from a memo mined below the upgrade height is marked`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 72042), firstContact = true)
            val vm = chat.viewModel()
            val contact = E2EEncryption.generateKeyPair()

            handleKex(vm, kexFrom(chat.peer, contact, "ZC72R402"), "synthetic-w72-r2-below", 3_000_000L)
            awaitIdle(vm)

            assertEquals("a key from a memo mined below the upgrade height has no mark", contact.publicKey, chat.maps.legacy[chat.peer])
        }

    @Test
    fun `(4) control - a key from a memo mined above the upgrade height is not marked`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 72043), firstContact = true)
            val vm = chat.viewModel()
            val contact = E2EEncryption.generateKeyPair()

            handleKex(vm, kexFrom(chat.peer, contact, "ZC72R403"), "synthetic-w72-r2-above", ChainTiming.MAINNET_UPGRADE_HEIGHT + 1)
            awaitIdle(vm)

            assertEquals("fixture: the key is stored", contact.publicKey, chat.prefs.e2ePeerKey[chat.peer])
            assertNull("a key from above the upgrade height got a mark", chat.maps.legacy[chat.peer])
        }

    @Test
    fun `(4) control - a network with no upgrade height never marks by height`() =
        runBlocking {
            ChainTiming.current = ChainTiming.TESTNET
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 72044), firstContact = true)
            val vm = chat.viewModel()
            val contact = E2EEncryption.generateKeyPair()

            handleKex(vm, kexFrom(chat.peer, contact, "ZC72R404"), "synthetic-w72-r2-testnet", 1_000L)
            awaitIdle(vm)

            assertEquals("fixture: the key is stored", contact.publicKey, chat.prefs.e2ePeerKey[chat.peer])
            assertNull("a testnet key got a mark", chat.maps.legacy[chat.peer])
        }

    @Test
    fun `(4) before the key store's first complete history read a key from above the upgrade height is marked`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 72045), firstContact = true)
            chat.maps.legacyHistoryRead = false
            val vm = chat.viewModel()
            val contact = E2EEncryption.generateKeyPair()

            handleKex(vm, kexFrom(chat.peer, contact, "ZC72R405"), "synthetic-w72-r2-restore", ChainTiming.MAINNET_UPGRADE_HEIGHT + 500)
            awaitIdle(vm)

            assertEquals("fixture: the key is stored", contact.publicKey, chat.prefs.e2ePeerKey[chat.peer])
            assertEquals("a key a restore rebuilt has no mark", contact.publicKey, chat.maps.legacy[chat.peer])
        }

    @Test
    fun `(4) control - a repeat of the stored key adds no mark, whatever its height`() =
        runBlocking {
            val chat = ClaimMarkChat(SyntheticUnifiedAddress.mainnet(seed = 72046), firstContact = false)
            val contact = E2EEncryption.generateKeyPair()
            chat.prefs.e2ePeerKey[chat.peer] = contact.publicKey
            chat.prefs.sentKexAck[chat.peer] = contact.publicKey
            val vm = chat.viewModel()

            handleKex(vm, kexFrom(chat.peer, contact, "ZC72R406"), "synthetic-w72-r2-repeat", 3_000_000L)
            awaitIdle(vm)

            assertNull("a repeat of the stored key (a key F may have proven) was marked again", chat.maps.legacy[chat.peer])
        }

    @Test
    fun `(4) a restore pass marks the answer it takes and records the read only after acting on every key offer`() =
        runBlocking {
            for (restored in listOf(true, false)) {
                AutoSpendVm.resetProcessState()
                AutoSpendVm.setHistoryRead(true)
                val a = SyntheticUnifiedAddress.mainnet(seed = if (restored) 72047 else 72048)
                val f = HandshakePassFixture(scheduler, a)
                val maps = ClaimMarkMaps(f.prefs.e2ePeerKey, f.prefs.askedKey)
                maps.legacyHistoryRead = !restored
                f.extra = { method, args -> maps.handle(method, args) }
                val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(a)
                f.open()
                f.ownKex("w72-r2-own-$restored", a, own, minedAt = HandshakePassFixture.NEW + 5)
                val theirs = E2EEncryption.generateKeyPair()
                f.received("w72-r2-ack-$restored", answerFrom(a, theirs, own), minedAt = HandshakePassFixture.NEW + 7)

                f.pass()

                val label = if (restored) "restore" else "control"
                assertEquals("$label: fixture: the answer's key was taken", theirs.publicKey, f.prefs.e2ePeerKey[a])
                if (restored) {
                    assertEquals("restore: the answer a restore took has no mark", theirs.publicKey, maps.legacy[a])
                    assertTrue("restore: the pass never recorded the complete read", maps.legacyHistoryReadWrites.get() > 0)
                } else {
                    assertNull("control: an answer taken after the read was marked", maps.legacy[a])
                }
                assertEquals("$label: nothing is paid", 0, f.seam.proposalRequests.get())
            }
        }

    private fun answerFrom(
        sender: String,
        keys: E2EKeyPair,
        convId: String,
    ): String =
        ZMSGProtocol.createV4KEXAckMessage(
            convId,
            sender,
            E2EEncryption.createKEXAckPayload(sender, keys.publicKey, keys.privateKey),
        )

    /** handleKEXMessage as the history scan calls it: the memo's mined height, the scan allowed to act. */
    private fun handleKex(
        vm: ChatViewModel,
        memo: String,
        txId: String,
        minedHeight: Long,
    ) {
        val method =
            ChatViewModel::class.java.getDeclaredMethod(
                "handleKEXMessage",
                String::class.java,
                String::class.java,
                String::class.java,
                java.lang.Boolean.TYPE,
                Long::class.javaObjectType,
            )
        method.isAccessible = true
        try {
            method.invoke(vm, memo, HandshakeHarness.SELF, txId, true, minedHeight)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
