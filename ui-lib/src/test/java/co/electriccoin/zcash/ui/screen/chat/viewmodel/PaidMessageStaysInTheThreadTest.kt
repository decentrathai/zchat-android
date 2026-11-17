package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.SentTx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Round-4 report §5A — a Shielded message the user SENT AND PAID FOR left the conversation after the app was
 * restarted, while the chat-list preview and the wallet still showed it.
 *
 * The thread and the preview are built from the same Conversation, but in two different orders: the preview is
 * the newest row BY TIME, the thread is sorted by the block height each row has — or, for a row with no block,
 * by one bucket "just above the tip", taken as the highest mined height ANYWHERE IN THE CONVERSATION. In a chat
 * whose history is relay messages and local notes (no block height at all), the first on-chain message ever paid
 * for raises that tip itself, so every older row is re-anchored ABOVE it in one step: the paid message is thrown
 * to the very top of the thread, days before the history it follows, and the thread visibly ends on the last
 * relay message of the previous day. Nothing is deleted — the money, the preview and the wallet row are intact —
 * but the conversation no longer contains what the user paid for where they can find it.
 *
 * Wanted: a paid, sent message is at the END of its conversation after a restart, and the end of the thread is
 * the row the chat-list preview names. A send still waiting for a block keeps sitting at the end too (that is
 * what the tip bucket is for), which the control below pins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaidMessageStaysInTheThreadTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticthreadorderpeer001"
        const val MINED_AT = 3_492_539L
        const val PAID_TEXT = "R5-PAID-001"
        const val RELAY_TEXT = "R5-RELAY-001"
        const val NOTE_TEXT = "You switched this chat to Shielded (on-chain)."
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val NEXT_RELAY_ID = "nostr-out-r7-relay-before-next-block"
        const val NEXT_RELAY_TEXT = "R7-RELAY-BEFORE-NEXT-BLOCK"
        const val NEXT_BLOCK_TEXT = "R7-NEXT-BLOCK-001"
        const val PENDING_OWN_TEXT = "R7C-PENDING-OWN-U"
        const val LATER_MINED_TEXT = "R7C-LATER-MINED-M"
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
    fun `a paid message is the last row of its conversation after a restart`() =
        runBlocking {
            val now = Instant.now()
            val prefs = PaymentPrefs(PEER)
            seedRelayHistory(prefs, now)
            val vm = reopen(prefs)

            val conversations =
                PendingReconcileHarness.reconcileConversations(
                    vm,
                    prefs,
                    listOf(paidSend(sentAt = now, minedHeight = MINED_AT)),
                )

            val conversation = conversations.single { it.peerAddress == PEER }
            val texts = conversation.messages.map { it.text }
            assertTrue("fixture sanity: the paid message was parsed into the conversation", texts.contains(PAID_TEXT))
            assertTrue("fixture sanity: the relay history is in the conversation", texts.contains(RELAY_TEXT))
            assertEquals(
                "the message the user paid for must be at the end of the conversation, not before its history: $texts",
                PAID_TEXT,
                texts.last(),
            )
        }

    @Test
    fun `the end of the thread is the message the chat list preview shows`() =
        runBlocking {
            val now = Instant.now()
            val prefs = PaymentPrefs(PEER)
            seedRelayHistory(prefs, now)
            val vm = reopen(prefs)

            val conversations =
                PendingReconcileHarness.reconcileConversations(
                    vm,
                    prefs,
                    listOf(paidSend(sentAt = now, minedHeight = MINED_AT)),
                )

            val conversation = conversations.single { it.peerAddress == PEER }
            assertEquals(
                "the chat-list preview and the end of the thread must be the same message",
                conversation.lastMessage?.id,
                conversation.messages.last().id,
            )
        }

    @Test
    fun `control - a send still waiting for a block stays at the end of the thread`() =
        runBlocking {
            val now = Instant.now()
            val prefs = PaymentPrefs(PEER)
            seedRelayHistory(prefs, now)
            // A conversation that already has an on-chain message, so the newest send is the un-mined one.
            val vm = reopen(prefs)

            val conversations =
                PendingReconcileHarness.reconcileConversations(
                    vm,
                    prefs,
                    listOf(
                        paidSend(sentAt = now.minusMillis(DAY_MS), minedHeight = MINED_AT),
                        SentTx(
                            2,
                            PEER,
                            ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, "R5-WAITING-001"),
                            null,
                            blockTime = now,
                        ),
                    ),
                )

            val conversation = conversations.single { it.peerAddress == PEER }
            val texts = conversation.messages.map { it.text }
            assertEquals(
                "a send that has not been mined yet belongs at the end, where its block will put it: $texts",
                "R5-WAITING-001",
                texts.last(),
            )
        }

    /**
     * Round 7 (THREAD-SORT-RELAY-VS-NEXT-BLOCK: GPT "paid-message sorting" note, REVIEW_QUESTIONS 20, B-2): a relay
     * row R sent after the last mined row (height P) is anchored to P+1. The next on-chain message M, sent after R,
     * is mined at exactly P+1 and has a real position in its block. R was sent first, so R must be shown first:
     * the position in a block orders two mined rows, not a mined row against one that has no block.
     */
    @Test
    fun `older relay row stays above the next block's mined row`() =
        runBlocking {
            val now = Instant.now()
            val prefs = PaymentPrefs(PEER)
            seedRelayRow(prefs, NEXT_RELAY_ID, NEXT_RELAY_TEXT, now.minusMillis(DAY_MS))
            val vm = reopen(prefs)

            val conversations =
                PendingReconcileHarness.reconcileConversations(
                    vm,
                    prefs,
                    listOf(
                        paidSend(sentAt = now.minusMillis(2 * DAY_MS), minedHeight = MINED_AT, txIndex = 3L),
                        onChainSend(4, NEXT_BLOCK_TEXT, sentAt = now, minedHeight = MINED_AT + 1, txIndex = 0L),
                    ),
                )

            val texts = conversations.single { it.peerAddress == PEER }.messages.map { it.text }
            assertTrue(
                "fixture sanity: all three rows are in the thread: $texts",
                texts.containsAll(listOf(PAID_TEXT, NEXT_RELAY_TEXT, NEXT_BLOCK_TEXT)),
            )
            assertEquals(
                "the relay row was sent before the next block's message, so it must be shown before it: $texts",
                listOf(PAID_TEXT, NEXT_RELAY_TEXT, NEXT_BLOCK_TEXT),
                texts.filter { it in setOf(PAID_TEXT, NEXT_RELAY_TEXT, NEXT_BLOCK_TEXT) },
            )
        }

    @Test
    fun `control - a relay row sent after the next block's message stays below it`() =
        runBlocking {
            val now = Instant.now()
            val prefs = PaymentPrefs(PEER)
            seedRelayRow(prefs, NEXT_RELAY_ID, NEXT_RELAY_TEXT, now.plusMillis(60_000L))
            val vm = reopen(prefs)

            val conversations =
                PendingReconcileHarness.reconcileConversations(
                    vm,
                    prefs,
                    listOf(
                        paidSend(sentAt = now.minusMillis(2 * DAY_MS), minedHeight = MINED_AT, txIndex = 3L),
                        onChainSend(4, NEXT_BLOCK_TEXT, sentAt = now, minedHeight = MINED_AT + 1, txIndex = 0L),
                    ),
                )

            val texts = conversations.single { it.peerAddress == PEER }.messages.map { it.text }
            assertEquals(
                "a relay row sent after the mined message belongs below it: $texts",
                listOf(PAID_TEXT, NEXT_BLOCK_TEXT, NEXT_RELAY_TEXT),
                texts.filter { it in setOf(PAID_TEXT, NEXT_RELAY_TEXT, NEXT_BLOCK_TEXT) },
            )
        }

    @Test
    fun `control - two mined rows in one block keep their order in the block`() =
        runBlocking {
            val now = Instant.now()
            val prefs = PaymentPrefs(PEER)
            val vm = reopen(prefs)

            val conversations =
                PendingReconcileHarness.reconcileConversations(
                    vm,
                    prefs,
                    listOf(
                        // Written the other way round in time, so only the position in the block can order them.
                        onChainSend(
                            5,
                            "R7-SAME-BLOCK-SECOND",
                            sentAt = now.minusMillis(1_000L),
                            minedHeight = MINED_AT,
                            txIndex = 1L,
                        ),
                        onChainSend(6, "R7-SAME-BLOCK-FIRST", sentAt = now, minedHeight = MINED_AT, txIndex = 0L),
                    ),
                )

            val texts = conversations.single { it.peerAddress == PEER }.messages.map { it.text }
            assertEquals(
                "two mined rows in the same block are ordered by their position in it: $texts",
                listOf("R7-SAME-BLOCK-FIRST", "R7-SAME-BLOCK-SECOND"),
                texts.filter { it.startsWith("R7-SAME-BLOCK-") },
            )
        }

    /**
     * Round 7c (R6-4 review finding 19) — CONTROL of a chosen display trade-off, not a fix. A pending own send U
     * is bucketed at the tip of its own time + 1 (T+1). A message M sent after U and mined at T+1 shares that
     * bucket, and U (no block) sorts above it: the order they were sent in. Once U mines at T+2, U's key is T+2
     * and U moves below M, once. The comment at the sort in ChatViewModel says so; this pins both halves so a
     * change to the order is a deliberate one.
     */
    @Test
    fun `control - a pending own send is above a later next-block send, and below it once mined at tip+2`() =
        runBlocking {
            val now = Instant.now()
            val uSentAt = now.minusMillis(60_000L)
            val earlier = paidSend(sentAt = now.minusMillis(2 * DAY_MS), minedHeight = MINED_AT, txIndex = 3L)
            val later = onChainSend(8, LATER_MINED_TEXT, sentAt = now, minedHeight = MINED_AT + 1, txIndex = 0L)
            val rows = setOf(PAID_TEXT, PENDING_OWN_TEXT, LATER_MINED_TEXT)

            val pendingPrefs = PaymentPrefs(PEER)
            val pending =
                PendingReconcileHarness
                    .reconcileConversations(
                        reopen(pendingPrefs),
                        pendingPrefs,
                        listOf(
                            earlier,
                            SentTx(
                                7,
                                PEER,
                                ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, PENDING_OWN_TEXT),
                                null,
                                blockTime = uSentAt,
                            ),
                            later,
                        ),
                    ).single { it.peerAddress == PEER }
                    .messages
                    .map { it.text }
            assertEquals(
                "while U waits for a block it sits above M, which was sent after it: $pending",
                listOf(PAID_TEXT, PENDING_OWN_TEXT, LATER_MINED_TEXT),
                pending.filter { it in rows },
            )

            val minedPrefs = PaymentPrefs(PEER)
            val mined =
                PendingReconcileHarness
                    .reconcileConversations(
                        reopen(minedPrefs),
                        minedPrefs,
                        listOf(
                            earlier,
                            onChainSend(7, PENDING_OWN_TEXT, sentAt = uSentAt, minedHeight = MINED_AT + 2, txIndex = 0L),
                            later,
                        ),
                    ).single { it.peerAddress == PEER }
                    .messages
                    .map { it.text }
            assertEquals(
                "once U mines at T+2 it is below M, mined at T+1 (the chosen trade-off): $mined",
                listOf(PAID_TEXT, LATER_MINED_TEXT, PENDING_OWN_TEXT),
                mined.filter { it in rows },
            )
        }

    private fun seedRelayRow(
        prefs: PaymentPrefs,
        id: String,
        text: String,
        at: Instant,
    ) {
        prefs.pending[id] =
            ZchatPreferences.PendingMessageData(
                id = id,
                text = text,
                timestampMillis = at.toEpochMilli(),
                peerAddress = PEER,
                isPending = false,
                status = MessageStatus.SENT.name,
            )
    }

    private fun onChainSend(
        number: Int,
        text: String,
        sentAt: Instant,
        minedHeight: Long,
        txIndex: Long,
    ) = SentTx(
        number,
        PEER,
        ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, text),
        minedHeight,
        blockTime = sentAt,
        txIndex = txIndex,
    )

    /**
     * What an existing relay chat looks like on disk: rows that were delivered over the free relay and a local
     * mode-change note. None of them has, or will ever have, a block height.
     */
    private fun seedRelayHistory(
        prefs: PaymentPrefs,
        now: Instant,
    ) {
        val relayId = "nostr-out-r5-relay-001"
        prefs.pending[relayId] =
            ZchatPreferences.PendingMessageData(
                id = relayId,
                text = RELAY_TEXT,
                timestampMillis = now.toEpochMilli() - 4 * DAY_MS,
                peerAddress = PEER,
                isPending = false,
                status = MessageStatus.SENT.name,
            )
        val noteId = SysNotes.modeUpgradeNoteId(PEER, epoch = 1L)
        prefs.pending[noteId] =
            ZchatPreferences.PendingMessageData(
                id = noteId,
                text = NOTE_TEXT,
                timestampMillis = now.toEpochMilli() - 4 * DAY_MS + 60_000L,
                peerAddress = PEER,
                isPending = false,
                status = MessageStatus.SENT.name,
            )
    }

    private fun paidSend(
        sentAt: Instant,
        minedHeight: Long?,
        txIndex: Long? = null,
    ) = SentTx(
        1,
        PEER,
        ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, PAID_TEXT),
        minedHeight,
        blockTime = sentAt,
        txIndex = txIndex,
    )

    private suspend fun reopen(prefs: PaymentPrefs): ChatViewModel {
        val vm = paymentViewModel(prefs, PaymentSeam(PEER))
        PaymentHarness.loadPendingMessagesFromPrefs(vm)
        return vm
    }
}
