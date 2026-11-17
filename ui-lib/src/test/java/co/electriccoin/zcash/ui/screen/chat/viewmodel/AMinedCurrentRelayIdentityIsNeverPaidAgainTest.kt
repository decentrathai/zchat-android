package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ZC-773 (review of 12 Oct, finding 1) — a ZBOOT of our CURRENT relay identity that is mined is never paid for again
 * under the standing consent.
 *
 * A history pass reads our own rows oldest first. With an empty delivered-identity marker (a restore) the EARLIER
 * mined ZBOOT sets it, and at 9dc8d5fbf the CURRENT one cannot replace it: the pass ends with the older marker and
 * drops what it read. A later send under the standing consent (a Call tap, or a chat open while the contact's relay
 * key is missing) then finds "another identity", writes the current one itself and pays 17,000 zat for an identity
 * that is already on chain. Wanted: at the end of a complete pass a contact whose mined rows include our current
 * identity ends with that identity as its marker, also over an older marker and also for a contact with nothing owed.
 *
 * Not for a confirmed Reset or key-change reconnect: their ZBOOT is sent again on purpose (controls), and the
 * Reconnect tap still re-sends our identity once.
 *
 * Money: every case counts proposals against submits on the real use case and repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AMinedCurrentRelayIdentityIsNeverPaidAgainTest {
    private val sendScopes = SendHarnessScopes()
    private var ownHistoryReadBefore = false

    @Before
    fun setUp() {
        ownHistoryReadBefore = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ZbootSetup.resetProcess()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(ownHistoryReadBefore)
        Dispatchers.resetMain()
        ZbootSetup.resetProcess()
    }

    /** The fixture of AMinedZbootOfAnEarlierRelayIdentityDoesNotCancelTheOwedOneTest (its older-then-current case). */
    @Test
    fun `older then current mined for a contact with an owed quote - a Call tap under the standing consent pays nothing`() =
        runBlocking {
            val chat = restoredChat(consent = false)
            assertTrue(chat.store.setZbootQuoted(PEER, true))
            assertTrue(chat.store.setZbootOwed(PEER, true))
            val vm = chat.viewModel()
            val current = ZbootSetup.currentIdentity(vm)

            olderThenCurrentPass(vm, chat, current)
            assertFalse("fixture sanity: the mined current identity discharges the owed quote", PEER in chat.zbootOwed)
            assertFalse(chat.store.isZbootQuoted(PEER))

            // The user allows automatic chat setup later, and taps Call: a forced bootstrap under that consent.
            chat.consent = true
            vm.ensureNostrBootstrapSent(PEER, force = true)
            awaitIdle(vm)

            assertEquals("a ZBOOT was built for an identity that is already mined", 0, chat.seam.zbootProposals.get())
            assertEquals("17,000 zat were paid for an identity that is already mined", 0, chat.seam.submitted.size)
            assertTrue("the mined current identity must be the delivered one", chat.store.getSentNostrBootPubkey(PEER) == current)
            ZbootSetup.kill(vm)
        }

    @Test
    fun `older then current mined for a contact with nothing owed - a Call tap under the standing consent pays nothing`() =
        runBlocking {
            val chat = restoredChat(consent = true)
            val vm = chat.viewModel()
            val current = ZbootSetup.currentIdentity(vm)

            olderThenCurrentPass(vm, chat, current)
            vm.ensureNostrBootstrapSent(PEER, force = true)
            awaitIdle(vm)

            assertEquals("a ZBOOT was built for an identity that is already mined", 0, chat.seam.zbootProposals.get())
            assertEquals("17,000 zat were paid for an identity that is already mined", 0, chat.seam.submitted.size)
            assertTrue("the mined current identity must be the delivered one", chat.store.getSentNostrBootPubkey(PEER) == current)

            chat.tick(vm)
            awaitIdle(vm)
            assertEquals(0, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    @Test
    fun `older then current mined - a chat open while the contact's relay key is missing pays nothing`() =
        runBlocking {
            val chat = restoredChat(consent = true)
            val vm = chat.viewModel()
            val current = ZbootSetup.currentIdentity(vm)

            olderThenCurrentPass(vm, chat, current)
            assertEquals("fixture sanity: the contact's relay key is missing", null, chat.store.getPeerNostrPubkey(PEER))
            vm.ensureNostrBootstrapSent(PEER)
            awaitIdle(vm)

            assertEquals("a chat open built a ZBOOT for an identity that is already mined", 0, chat.seam.zbootProposals.get())
            assertEquals("a chat open paid for an identity that is already mined", 0, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    @Test
    fun `a marker the store did not save keeps the contact waiting until a later pass has saved it`() =
        runBlocking {
            val chat = restoredChat(consent = true)
            val refuseCurrent = AtomicBoolean(true)
            lateinit var current: String
            val store =
                refusing(chat.store) { name, args ->
                    name == "setSentNostrBootPubkey" && refuseCurrent.get() && args[1] == current
                }
            val vm =
                chat.viewModel().also {
                    AutoSpendVm.setField(it, "zchatPreferences", store)
                    AutoSpendVm.setField(it, "seedKeyProvider", SeedDerivedE2EKeyProvider(store) { null })
                }
            current = ZbootSetup.currentIdentity(vm)

            olderThenCurrentPass(vm, chat, current)
            refuseCurrent.set(false)
            vm.ensureNostrBootstrapSent(PEER, force = true)
            awaitIdle(vm)
            assertEquals("a ZBOOT was paid while the proof of its mined identity was not saved yet", 0, chat.seam.zbootProposals.get())

            olderThenCurrentPass(vm, chat, current)
            assertTrue("the next complete pass must save the delivered identity", chat.store.getSentNostrBootPubkey(PEER) == current)
            vm.ensureNostrBootstrapSent(PEER, force = true)
            awaitIdle(vm)
            assertEquals(0, chat.seam.zbootProposals.get())
            assertEquals(0, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    @Test
    fun `control - a confirmed Reset after that history still pays its key exchange and its ZBOOT exactly once`() =
        runBlocking {
            val chat = restoredChat(consent = false)
            val vm = chat.viewModel()
            val current = ZbootSetup.currentIdentity(vm)
            olderThenCurrentPass(vm, chat, current)

            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            chat.reset.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("fixture sanity: the confirmed Reset paid its KEX", 1, chat.seam.submittedOf("KEX"))

            // The history is read again (the KEX changed the transaction list) before the contact answers.
            olderThenCurrentPass(vm, chat, current)
            assertTrue("a pass restored the marker a confirmed Reset had cleared", chat.store.getSentNostrBootPubkey(PEER) == null)
            assertTrue("a pass withdrew the allowance of a confirmed Reset", chat.store.isZbootQuoted(PEER))

            chat.kexAckFromContact(vm, E2EEncryption.generateKeyPair(), txId = "synthetic-zc773-reset-key-reply")
            awaitSettled(vm, chat.seam)
            assertEquals("the confirmed Reset's ZBOOT must still be paid, once", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the confirmed Reset pays exactly the two transactions it quoted", 2, chat.seam.submitted.size)

            olderThenCurrentPass(vm, chat, current)
            repeat(2) {
                chat.tick(vm)
                awaitIdle(vm)
            }
            assertEquals("a later pass or block paid on top of the Reset's quote", 2, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    @Test
    fun `control - a confirmed key-change reconnect after that history still pays exactly once`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            chat.owed.store.setSentNostrBootPubkey(PEER, null)
            val vm = chat.viewModel()
            val current = ZbootSetup.currentIdentity(vm)
            olderThenCurrentPass(vm, chat.owed, current)

            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitSettled(vm, chat.seam)
            assertEquals("the confirmed reconnect's KEX is paid once", 1, chat.seam.submittedOf("KEX"))
            assertEquals("the confirmed reconnect's ZBOOT must still be paid, once", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the confirmed reconnect pays exactly the two transactions it quoted", 2, chat.seam.submitted.size)

            olderThenCurrentPass(vm, chat.owed, current)
            repeat(2) {
                chat.owed.tick(vm)
                awaitIdle(vm)
            }
            assertEquals("a later pass or block paid on top of the reconnect's quote", 2, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    @Test
    fun `control - the Reconnect tap after that history still re-sends our identity once`() =
        runBlocking {
            val chat = restoredChat(consent = true)
            val vm = chat.viewModel()
            val current = ZbootSetup.currentIdentity(vm)
            olderThenCurrentPass(vm, chat, current)

            vm.reSendOurIdentity(PEER)
            awaitIdle(vm)
            assertEquals("Reconnect must re-send our identity: the user asked for it", 1, chat.seam.submittedOf("ZBOOT"))

            vm.reSendOurIdentity(PEER)
            awaitIdle(vm)
            olderThenCurrentPass(vm, chat, current)
            chat.tick(vm)
            awaitIdle(vm)
            assertEquals("Reconnect inside the hour, a pass or a block paid a second ZBOOT", 1, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    @Test
    fun `control - with only an older identity mined our current identity is still paid, once`() =
        runBlocking {
            val chat = restoredChat(consent = true)
            val vm = chat.viewModel()
            val current = ZbootSetup.currentIdentity(vm)

            ZbootSetup.historyPass(vm, PEER, listOf(ZbootSetup.boot(ZbootSetup.EARLIER_IDENTITY)))
            AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)
            assertTrue(
                "the restore rule records the earlier delivery",
                chat.store.getSentNostrBootPubkey(PEER) == ZbootSetup.EARLIER_IDENTITY,
            )

            vm.ensureNostrBootstrapSent(PEER, force = true)
            awaitIdle(vm)
            assertEquals("our current identity was never delivered and must be paid", 1, chat.seam.submittedOf("ZBOOT"))
            assertTrue("the paid identity must be the delivered one", chat.store.getSentNostrBootPubkey(PEER) == current)

            vm.ensureNostrBootstrapSent(PEER, force = true)
            awaitIdle(vm)
            assertEquals("a second Call tap paid the same identity again", 1, chat.seam.submitted.size)
            ZbootSetup.kill(vm)
        }

    /** One complete pass over an EARLIER and then our CURRENT mined ZBOOT to [PEER]; the block feed is set again after it. */
    private suspend fun olderThenCurrentPass(
        vm: ChatViewModel,
        chat: ResetOwedChat,
        current: String,
    ) {
        ZbootSetup.historyPass(vm, PEER, listOf(ZbootSetup.boot(ZbootSetup.EARLIER_IDENTITY), ZbootSetup.boot(current)))
        AutoSpendVm.setField(vm, "synchronizerProvider", chat.heights.provider)
    }

    /** A Shielded chat after a restore: keys both ways, no relay key, nothing recorded as delivered, nothing quoted. */
    private fun restoredChat(consent: Boolean): ResetOwedChat =
        ResetOwedChat(PEER, consent = consent).also { chat ->
            chat.store.setSentNostrBootPubkey(PEER, null)
            assertEquals(null, chat.reset.peerRelayKey)
        }

    /** Join the sends a confirm or a key reply started until no new proposal appears. */
    private suspend fun awaitSettled(
        vm: ChatViewModel,
        seam: ResetKexSeam,
    ) {
        withTimeout(HandshakeHarness.TIMEOUT_MS) {
            var seen = -1
            while (seen != seam.proposals()) {
                seen = seam.proposals()
                awaitIdle(vm)
                awaitIdle(vm)
            }
        }
    }

    /** [delegate], except that a call [refused] names reports "not saved" and changes nothing. */
    private fun refusing(
        delegate: ZchatPreferences,
        refused: (name: String, args: Array<Any?>) -> Boolean,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when {
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.get(0)
                method.name == "toString" -> "Zc773RefusingStore"
                args != null && refused(method.name, args) -> false
                else ->
                    try {
                        method.invoke(delegate, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private companion object {
        const val PEER = "usyntheticzc773minedcurrent1"
    }
}
