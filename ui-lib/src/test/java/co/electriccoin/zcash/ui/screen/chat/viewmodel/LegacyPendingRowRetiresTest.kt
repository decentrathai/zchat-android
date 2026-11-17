package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.SentTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.txId
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
import java.time.Instant

/**
 * P2a follow-up (review finding 6) — pending rows written by an older build (2.12.x / 2.13.0).
 *
 * Those rows carry no submitTracked flag, marker or transaction IDs; the old build persisted them just before
 * the broadcast. At 1a650c932 the loader rightly shows such a row as FAILED "may already have been sent",
 * but reconciliation now retires a "pending_" row only by exact transaction ID, which a legacy row can never
 * have. So every user who upgrades with a message still unconfirmed keeps a permanent FAILED duplicate next
 * to the delivered message. Before P2a the row was retired by its text once the transaction was mined.
 *
 * Wanted: a retire-only match. A legacy row is retired by a MINED outgoing transaction to the same contact with
 * the same text, seen no earlier than the row was written, each transaction used once. Retiring never
 * authorizes a payment, and until it happens Retry stays blocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyPendingRowRetiresTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticlegacyretire000001"
        const val OTHER_PEER = "usyntheticlegacyretireother1"
        const val MINED_AT = 3_000_100L
        const val TEXT = "synthetic legacy hello"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a legacy pending row is retired by its later mined transaction`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val legacyId = seedLegacyRow(prefs, "pending_111111111", Instant.now().minusSeconds(60))
            val vm = reopen(prefs)

            val rows = PendingReconcileHarness.reconcile(vm, prefs, listOf(minedCopy(1, sentAt = Instant.now())))

            assertEquals("fixture sanity: the mined message was parsed", 1, rows.count { it.id == txId(1) })
            assertFalse("an upgraded user's delivered message keeps a permanent FAILED duplicate", prefs.pending.containsKey(legacyId))
            assertTrue(rows.none { it.id == legacyId })
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `one mined transaction retires only one of two identical legacy rows`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val older = seedLegacyRow(prefs, "pending_222222221", Instant.now().minusSeconds(90))
            val newer = seedLegacyRow(prefs, "pending_222222222", Instant.now().minusSeconds(60))
            val vm = reopen(prefs)

            val rows = PendingReconcileHarness.reconcile(vm, prefs, listOf(minedCopy(1, sentAt = Instant.now())))

            assertEquals("each mined transaction may retire one row", 1, listOf(older, newer).count { prefs.pending.containsKey(it) })
            assertEquals(1, rows.count { it.id == older || it.id == newer })
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `control - a same-text transaction mined long before the legacy row was written never retires it`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val legacyId = seedLegacyRow(prefs, "pending_333333333", Instant.now().minusSeconds(60))
            val vm = reopen(prefs)

            val rows = PendingReconcileHarness.reconcile(vm, prefs, listOf(minedCopy(1, sentAt = Instant.now().minusSeconds(86_400))))

            assertTrue("an older identical message is not this row's delivery", prefs.pending.containsKey(legacyId))
            assertTrue(rows.any { it.id == legacyId })
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `control - a same-text transaction to another contact never retires the row and retiring never pays`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val legacyId = seedLegacyRow(prefs, "pending_444444444", Instant.now().minusSeconds(60))
            val vm = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(vm)

            PendingReconcileHarness.reconcile(
                vm,
                prefs,
                listOf(SentTx(1, OTHER_PEER, ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, TEXT), MINED_AT)),
            )
            assertTrue("a message to someone else is not this row's delivery", prefs.pending.containsKey(legacyId))

            vm.retryMessage(PEER, legacyId)
            awaitIdle(vm)
            assertEquals("an unretired legacy row must stay blocked from Retry", 0, seam.submits.get())
            val state = PaymentHarness.sendMessageState(vm)
            assertTrue(
                "Retry must say the message may already have been sent: $state",
                state is SendMessageState.Error && state.message.contains("may already have been sent", ignoreCase = true),
            )
            assertEquals(MessageStatus.FAILED, PaymentHarness.pendingMessages(vm).single { it.id == legacyId }.status)
        }

    private fun seedLegacyRow(
        prefs: PaymentPrefs,
        id: String,
        writtenAt: Instant,
    ): String {
        // Exactly what an older build persisted just before broadcasting: no submit tracking at all.
        prefs.pending[id] =
            ZchatPreferences.PendingMessageData(
                id = id,
                text = TEXT,
                timestampMillis = writtenAt.toEpochMilli(),
                peerAddress = PEER,
            )
        return id
    }

    private suspend fun reopen(prefs: PaymentPrefs): ChatViewModel {
        val vm = paymentViewModel(prefs, PaymentSeam(PEER))
        PaymentHarness.loadPendingMessagesFromPrefs(vm)
        assertTrue(
            "fixture sanity: a legacy row reloads as possibly sent",
            PaymentHarness.pendingMessages(vm).all { it.sendMayHaveGoneOut },
        )
        return vm
    }

    private fun minedCopy(
        n: Int,
        sentAt: Instant,
    ) = SentTx(n, PEER, ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, TEXT), MINED_AT, blockTime = sentAt)
}
