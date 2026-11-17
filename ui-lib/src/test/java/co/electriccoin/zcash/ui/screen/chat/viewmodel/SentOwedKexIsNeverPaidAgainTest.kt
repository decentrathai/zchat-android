package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.HandshakePaidRecord
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.lang.reflect.Modifier

/**
 * ZC-626 (high; found by the ZC-535 lane, closed in train T6a batch ZC-536) — a key exchange that was sent is paid a
 * second time, with no tap, when clearing its "owed" mark was not saved.
 *
 * A confirmed key-change reconnect or Reset records its key exchange (KEX) as owed, sends it, and clears the mark. When
 * that clear is not saved (a storage write failure; the app only logs it), the mark and its reason stay. The per-block
 * retry then settles the mark without paying only while the KEX's paid record is inside its hour. After the hour it pays
 * the same KEX again: 17,000 zatoshi above the confirmed quote, automatically. Since ZC-535 a complete exchange no longer
 * settles a reconnect's mark; a Reset (and a first message) had the same exposure before.
 *
 * Must hold: a KEX that was submitted is never paid again for the same key on the same confirm, however old its paid
 * record is and whatever the store did; the stale mark is cleared as soon as the store saves again. A new confirmed
 * reconnect after the hour is a new confirm and pays its KEX once more, exactly as quoted.
 *
 * Drives the real ViewModel through the real use case and repository ([KeyChangeReconnectChat], [ResetOwedChat]). Every
 * address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SentOwedKexIsNeverPaidAgainTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc626sentkex00001"
        const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L
        val KEX_SLOT = HandshakePaidRecord.slot(AutomaticSetupKind.KEX, PEER)
        val ZBOOT_SLOT = HandshakePaidRecord.slot(AutomaticSetupKind.ZBOOT, PEER)

        fun resetZbootProcessState() {
            for (name in listOf("nostrBootInFlight", "unsentZbootQuotes", "unsavedZbootWithdrawals", "reconnectsInFlight")) {
                val field =
                    ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) }
                        ?: continue
                field.isAccessible = true
                (field.get(null) as? MutableCollection<*>)?.clear()
                (field.get(null) as? MutableMap<*, *>)?.clear()
            }
        }
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        resetZbootProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        resetZbootProcessState()
    }

    @Test
    fun `a reconnect's sent KEX whose owed mark was not cleared is not paid again after the hour`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            chat.owed.refusedClears += "setKexOwed"
            val vm = chat.viewModel()
            confirmReconnect(chat, vm)
            assertTrue("fixture sanity: clearing the owed mark was not saved", PEER in chat.owed.kexOwed)
            ageBeyondTheHour(chat.owed, KEX_SLOT)

            repeat(3) {
                chat.owed.tick(vm)
                awaitSettled(vm, chat.seam)
            }

            assertEquals(
                "the same key exchange was paid again by a block, with no tap: 17,000 zatoshi above the confirmed quote",
                1,
                chat.seam.submittedOf("KEX"),
            )
            assertEquals("a second KEX proposal was built", 1, chat.seam.kexProposals.get())
            assertEquals("the reconnect quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
        }

    @Test
    fun `a reconnect's sent KEX whose owed mark was not cleared is not paid again by the next process`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            chat.owed.refusedClears += "setKexOwed"
            val first = chat.viewModel()
            confirmReconnect(chat, first)
            ResetOwedChat.kill(first)
            resetZbootProcessState()
            ageBeyondTheHour(chat.owed, KEX_SLOT)

            val next = chat.viewModel()
            repeat(2) {
                chat.owed.tick(next)
                awaitSettled(next, chat.seam)
            }
            chat.owed.openChat(next)
            awaitIdle(next)

            assertEquals(
                "the same key exchange was paid again after a restart, with no tap",
                1,
                chat.seam.submittedOf("KEX"),
            )
            assertEquals("the reconnect quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
        }

    @Test
    fun `a Reset's sent KEX whose owed mark was not cleared is not paid again after the hour`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false)
            chat.refusedClears += "setKexOwed"
            val vm = chat.viewModel()
            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            chat.reset.assertAskedFirst(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("fixture sanity: the Reset's KEX went", 1, chat.seam.submittedOf("KEX"))
            assertTrue("fixture sanity: clearing the owed mark was not saved", PEER in chat.kexOwed)
            ageBeyondTheHour(chat, KEX_SLOT)

            repeat(3) {
                chat.tick(vm)
                awaitSettled(vm, chat.seam)
            }

            assertEquals(
                "the same key exchange was paid again by a block, with no tap: 17,000 zatoshi above the confirmed quote",
                1,
                chat.seam.submittedOf("KEX"),
            )
            assertEquals("a second KEX proposal was built", 1, chat.seam.kexProposals.get())
        }

    @Test
    fun `the stale mark of a sent KEX is cleared by the next block once the store saves again`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            chat.owed.refusedClears += "setKexOwed"
            val vm = chat.viewModel()
            confirmReconnect(chat, vm)
            ageBeyondTheHour(chat.owed, KEX_SLOT)
            chat.owed.refusedClears.clear()

            chat.owed.tick(vm)
            awaitSettled(vm, chat.seam)

            assertEquals("the same key exchange was paid again by a block", 1, chat.seam.submittedOf("KEX"))
            assertFalse("the stale owed mark of a sent KEX was not cleared once the store saved again", PEER in chat.owed.kexOwed)
            assertEquals("the reason outlived the mark", null, chat.owed.kexOwedReason[PEER])
        }

    /**
     * A new confirm is a new consent: after the hour it pays its two legs again, exactly once each (that half holds
     * before the fix too), and later blocks never pay that second KEX again (that half does not).
     */
    @Test
    fun `a new confirmed reconnect after the hour pays its KEX exactly once more although the old mark was never cleared`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            chat.owed.refusedClears += "setKexOwed"
            val vm = chat.viewModel()
            confirmReconnect(chat, vm)
            ageBeyondTheHour(chat.owed, KEX_SLOT)
            ageBeyondTheHour(chat.owed, ZBOOT_SLOT)

            // The banner comes up again (the contact's relay key changed once more) and the user confirms again.
            chat.keyChanged = true
            chat.relayKey = KeyChangeReconnectChat.OLD_RELAY
            vm.resetSendState()
            vm.dismissE2EKeyChanged(PEER)
            awaitIdle(vm)
            chat.assertAsked(vm, 2)
            vm.confirmPaidAction()
            awaitSettled(vm, chat.seam)

            assertEquals("the second confirmed reconnect did not pay its KEX exactly once", 2, chat.seam.submittedOf("KEX"))
            assertEquals("the second confirmed reconnect did not pay its ZBOOT exactly once", 2, chat.seam.submittedOf("ZBOOT"))
            assertEquals("two confirmed reconnects quoted 2 on-chain transactions each", 4, chat.seam.submitted.size)

            // And that second KEX is not paid a third time by later blocks.
            ageBeyondTheHour(chat.owed, KEX_SLOT)
            repeat(2) {
                chat.owed.tick(vm)
                awaitSettled(vm, chat.seam)
            }
            assertEquals("a block paid the key exchange again after the second reconnect", 2, chat.seam.submittedOf("KEX"))
        }

    /** Control, green before and after: with the mark cleared as usual nothing is owed and no block pays anything. */
    @Test
    fun `control - a sent KEX whose mark was cleared is not paid again after the hour`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            val vm = chat.viewModel()
            confirmReconnect(chat, vm)
            assertFalse("fixture sanity: the owed mark was cleared", PEER in chat.owed.kexOwed)
            ageBeyondTheHour(chat.owed, KEX_SLOT)

            repeat(2) {
                chat.owed.tick(vm)
                awaitSettled(vm, chat.seam)
            }
            assertEquals(1, chat.seam.submittedOf("KEX"))
            assertEquals(2, chat.seam.submitted.size)
        }

    /** A confirmed reconnect with a complete exchange: its KEX and then its ZBOOT are paid, 2 on-chain. */
    private suspend fun confirmReconnect(
        chat: KeyChangeReconnectChat,
        vm: ChatViewModel,
    ) {
        vm.dismissE2EKeyChanged(PEER)
        awaitIdle(vm)
        chat.assertAsked(vm, 2)
        vm.confirmPaidAction()
        awaitSettled(vm, chat.seam)
        assertEquals("fixture sanity: the reconnect's KEX went", 1, chat.seam.submittedOf("KEX"))
        assertEquals("fixture sanity: the reconnect's ZBOOT went", 1, chat.seam.submittedOf("ZBOOT"))
    }

    /** Everything the last tap or block started has finished: the screen's jobs, the process-wide sends, their follow-ups. */
    private suspend fun awaitSettled(
        vm: ChatViewModel,
        seam: ResetKexSeam,
    ) {
        var seen = -1
        while (seen != seam.proposals()) {
            seen = seam.proposals()
            awaitIdle(vm)
            awaitIdle(vm)
        }
    }

    /** The paid record in [slot] as if it had been written two hours ago (same identity). */
    private fun ageBeyondTheHour(
        chat: ResetOwedChat,
        slot: String,
    ) {
        val record = requireNotNull(chat.paidRecords[slot]) { "fixture sanity: no paid record in $slot" }
        val identity = record.substringBeforeLast('|')
        chat.paidRecords[slot] = HandshakePaidRecord.encode(identity, System.currentTimeMillis() - TWO_HOURS_MS)
    }
}
