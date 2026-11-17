package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessage
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Y
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.time.Instant

/**
 * ZC-493 (high) — a group message none of whose copies ever reached the chain is drawn with the sent tick, for ever.
 *
 * You send a group message while the phone is offline and confirm the price. The wallet stores a transaction for each
 * paid copy, the submit never reaches the network, and about an hour later the transaction expires: never mined,
 * nothing paid, nobody received the message. The group screen shows it as sent all the same, because three writers
 * store the row of an expired copy like any other (the "grps-" row written when the send ends, the live path's
 * "<txid>" row, the history scan's "<txid>_<seq>" row), nothing ever sets GroupMessage.isFailed, and the history merge
 * never deletes. A group has no "Not sent" row to fall back on, so that false tick is the only record of the text.
 *
 * Wanted: an own group message none of whose own chain copies the wallet reports live (mined, or still pending) and at
 * least one reported expired and unmined (SendTransaction.Failed, no mined height) is shown as FAILED (the "!" the
 * group screen already draws for isFailed) WITH its text. Nothing is hidden, deleted or stored for it: a late mine
 * brings the tick back, and every stored row stays as it is (see [ExpiredGroupCopyMarkKeepsEveryStoredRowTest]).
 * No payment, Retry or Resend is involved: this is the status shown for a send, nothing else.
 *
 * Real ChatViewModel and GroupViewModel over [AnnouncePhone]; the chain is an [OpenGroupChain]. All addresses, keys
 * and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiredGroupMessageIsNotShownAsSentTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val h = ExpiredGroupHarness(scheduler)

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        // The next test class in this JVM starts from a scan that found no own copy of any message in this group.
        runBlocking { h.forgetGroup() }
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `an own message whose only copy expired unmined is drawn as failed with its text`() =
        runBlocking {
            val phone = h.phone()
            val own = h.own(phone)
            h.storeAsTheSendDoes(phone, h.ownRow(phone))
            assertTrue("fixture: the send stored its grps- row", phone.storedMessageIds(GID).single().startsWith("grps-"))

            val (_, shown) = h.open(phone, listOf(OpenGroupChain.failed(800, own)))

            val row = h.only(shown)
            assertTrue("a group message whose only chain copy expired and was never mined is shown as sent", row.isFailed)
            assertFalse(row.isPending)
            assertNull(row.blockHeight)
            assertEquals("the failed message lost its text on screen", ExpiredGroupHarness.TEXT, row.displayText)
        }

    @Test
    fun `the row the live path stored for the expired copy is marked too and stays marked after the next live pass`() =
        runBlocking {
            val phone = h.phone()
            val own = h.own(phone)
            val chat = h.chat(phone)
            h.livePass(chat, own)
            val liveRowId = AnnounceDriver.txId(800).txIdString()
            assertEquals("fixture: the live path stored its row", listOf(liveRowId), phone.storedMessageIds(GID))

            val (vm, shown) = h.open(phone, listOf(OpenGroupChain.failed(800, own)))
            assertTrue("the live path's row of an expired, never-mined copy is shown as sent", h.only(shown).isFailed)

            h.livePass(chat, own)
            val again = h.reloadWithoutScan(vm)
            assertTrue(
                "after the next live pass stored its row again the expired message is shown as sent",
                h.only(again).isFailed,
            )
            assertEquals(ExpiredGroupHarness.TEXT, h.only(again).displayText)
            assertTrue("the text is no longer stored", h.storedTexts(phone).contains(ExpiredGroupHarness.TEXT))
        }

    @Test
    fun `a row stored by an earlier scan is marked once its copy expires and the stored rows are unchanged`() =
        runBlocking {
            val phone = h.phone()
            val own = h.own(phone)

            val (_, first) = h.open(phone, listOf(OpenGroupChain.pending(800, own)))
            assertFalse("fixture: a copy that may still land is not failed", h.only(first).isFailed)
            val idBefore = h.only(first).id
            val storedBefore = phone.messages[GID]

            val (_, second) = h.open(phone, listOf(OpenGroupChain.failed(800, own)))

            val row = h.only(second)
            assertEquals("the scan replaced the row", idBefore, row.id)
            assertTrue("a row stored while its copy was pending is still shown as sent after the copy expired", row.isFailed)
            assertEquals(ExpiredGroupHarness.TEXT, row.displayText)
            assertEquals("the mark was written to the store", storedBefore, phone.messages[GID])
        }

    @Test
    fun `a late mine brings the message back as sent with its block height`() =
        runBlocking {
            val phone = h.phone()
            val own = h.own(phone)

            val (_, first) = h.open(phone, listOf(OpenGroupChain.failed(800, own)))
            assertTrue("a group message whose only chain copy expired and was never mined is shown as sent", h.only(first).isFailed)

            val (_, second) = h.open(phone, listOf(OpenGroupChain.sent(800, own, MINED)))
            val row = h.only(second)
            assertFalse("a message whose transaction was mined after all is still shown as failed", row.isFailed)
            assertEquals(MINED, row.blockHeight)
        }

    @Test
    fun `control - a stored block height wins over the mark when the screen reloads without a scan`() =
        runBlocking {
            val phone = h.phone()
            val own = h.own(phone)
            val chat = h.chat(phone)
            val (vm, _) = h.open(phone, listOf(OpenGroupChain.failed(800, own)))

            // The live path sees the same transaction mined and stores its height; the open screen reloads from the store.
            h.livePass(chat, own, minedHeight = MINED)
            val storedHeights = OpenGroup.rows(phone.messages, GID).map { it.optLong("blockHeight") }
            assertTrue("fixture: a stored row of the message has the mined height", storedHeights.contains(MINED))
            val shown = h.reloadWithoutScan(vm)

            assertFalse("a message with a mined copy in the store is shown as failed", h.only(shown).isFailed)
        }

    @Test
    fun `control - one mined copy among expired copies keeps the message sent`() =
        runBlocking {
            val phone = h.phone()
            val own = h.own(phone)

            val (_, shown) = h.open(phone, listOf(OpenGroupChain.failed(800, own), OpenGroupChain.sent(801, own, MINED + 1)))

            assertFalse("a message one of whose copies was mined is shown as failed", h.only(shown).isFailed)
        }

    @Test
    fun `control - an unexpired pending copy keeps the message unmarked`() =
        runBlocking {
            val phone = h.phone()
            val own = h.own(phone)

            val (_, alone) = h.open(phone, listOf(OpenGroupChain.pending(800, own)))
            assertFalse("a message whose only copy may still land is shown as failed", h.only(alone).isFailed)

            val (_, mixed) = h.open(phone, listOf(OpenGroupChain.failed(800, own), OpenGroupChain.pending(801, own)))
            assertFalse("a message with a copy that may still land is shown as failed", h.only(mixed).isFailed)
        }

    @Test
    fun `control - a message with no chain copy is never marked`() =
        runBlocking {
            val phone = h.phone()
            // Message 1 went free over the relays: its grps- row is its only trace.
            h.storeAsTheSendDoes(phone, h.ownRow(phone))

            val (_, emptyChain) = h.open(phone, emptyList())
            assertFalse("a message with no chain copy is shown as failed", h.only(emptyChain).isFailed)

            val other = h.own(phone, text = "another own message", seq = 2)
            val (_, otherExpired) = h.open(phone, listOf(OpenGroupChain.failed(800, other)))
            assertFalse(
                "a message with no chain copy is shown as failed because ANOTHER own message expired",
                h.only(otherExpired).isFailed,
            )
        }

    @Test
    fun `control - another member's message with the same seq is not marked`() =
        runBlocking {
            val phone = h.phone(extraRows = listOf(h.member(Y)))
            val own = h.own(phone)
            val chat = h.chat(phone)
            AnnounceDriver.receive(
                chat,
                AnnounceDriver.gm(phone, Y, "from Y with the same seq", epoch = 1, seq = 1),
                Instant.ofEpochMilli(T),
                AnnounceDriver.txId(700),
            )

            val (_, shown) = h.open(phone, listOf(OpenGroupChain.failed(800, own)))

            val fromY = shown.messages.single { it.decryptedContent == "from Y with the same seq" }
            assertFalse("another member's message is shown as failed because our own message with the same seq expired", fromY.isFailed)
        }

    @Test
    fun `a failed row keeps the text on screen`() {
        val failed =
            GroupMessage(
                id = "synthetic-failed-row",
                groupId = GID,
                txId = null,
                seq = 1,
                epoch = 1,
                senderAddress = HandshakeHarness.SELF,
                encryptedContent = null,
                decryptedContent = ExpiredGroupHarness.TEXT,
                nonce = null,
                timestamp = Instant.ofEpochMilli(T),
                isFailed = true,
            )
        assertEquals("a failed group message shows a placeholder, not its text", ExpiredGroupHarness.TEXT, failed.displayText)
    }

    private companion object {
        const val MINED = 3_000_900L
    }
}

/**
 * ZC-493 test support — a member's phone ([AnnouncePhone], group [GID]) with one own group message, the three writers
 * of its stored row (the send's "grps-" row, the live path, the history scan) and the group screen that reads them.
 */
internal class ExpiredGroupHarness(
    private val scheduler: TestCoroutineScheduler,
) {
    fun phone(extraRows: List<GroupMember> = emptyList()): AnnouncePhone {
        val phone = AnnouncePhone()
        AnnounceDriver.memberPhone(phone, extraRows = extraRows)
        return phone
    }

    fun member(address: String) = GroupMember(address = address, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE)

    /** The memo every copy of our own message carries. */
    fun own(
        phone: AnnouncePhone,
        text: String = TEXT,
        seq: Long = 1,
        epoch: Int = 1,
    ): String = AnnounceDriver.gm(phone, phone.self, text, epoch = epoch, seq = seq)

    /** Our own message as the send path holds it when the fan-out ends. */
    fun ownRow(
        phone: AnnouncePhone,
        text: String = TEXT,
        seq: Long = 1,
        epoch: Int = 1,
    ) = GroupMessage(
        id = "pending_$seq",
        groupId = GID,
        txId = null,
        seq = seq,
        epoch = epoch,
        senderAddress = phone.self,
        encryptedContent = null,
        decryptedContent = text,
        nonce = null,
        timestamp = Instant.ofEpochMilli(T + seq),
        isPending = true,
    )

    suspend fun chat(phone: AnnouncePhone): ChatViewModel = AnnounceDriver.chatVm(phone, HandshakeSeam(phone.self))

    private fun fixture() = GroupPaidOnceFixture().also { it.scheduler = scheduler }

    /** The real storeOwnGroupMessage (private), which the send calls when its fan-out ends. */
    suspend fun storeAsTheSendDoes(
        phone: AnnouncePhone,
        row: GroupMessage,
    ) {
        val vm = AnnounceDriver.groupVm(phone, fixture())
        val method = GroupViewModel::class.java.getDeclaredMethod("storeOwnGroupMessage", GroupMessage::class.java)
        method.isAccessible = true
        unwrapped { method.invoke(vm, row) }
    }

    /** One live pass of ChatViewModel over our own transaction [n] carrying [memo]. */
    fun livePass(
        chat: ChatViewModel,
        memo: String,
        n: Int = 800,
        minedHeight: Long? = null,
    ) = AnnounceDriver.receive(chat, memo, OpenGroupChain.blockTime(n), AnnounceDriver.txId(n), ownSend = true, minedHeight = minedHeight)

    /** A new group screen over [phone] whose wallet lists [chain]: first load, history scan, reload. */
    suspend fun open(
        phone: AnnouncePhone,
        chain: List<Pair<Transaction, String>>,
    ): Pair<GroupViewModel, GroupConversation> {
        val f = fixture()
        val vm = AnnounceDriver.groupVm(phone, f)
        GroupViewModel::class.java
            .getDeclaredField("transactionRepository")
            .apply { isAccessible = true }
            .set(vm, OpenGroupChain(chain))
        val conversation =
            AnnounceDriver.loadedConversation(vm, f) ?: throw AssertionError("the group screen is not showing the group")
        return vm to conversation
    }

    /** The open screen reloads from the store, as it does after a store write (refreshGroupDetail, private): no scan. */
    fun reloadWithoutScan(vm: GroupViewModel): GroupConversation {
        val method = GroupViewModel::class.java.getDeclaredMethod("refreshGroupDetail", String::class.java)
        method.isAccessible = true
        unwrapped { method.invoke(vm, GID) }
        return (vm.groupDetailState.value as? GroupDetailState.Success)?.conversation
            ?: throw AssertionError("the group screen is not showing the group")
    }

    /** The one shown row with [text]. */
    fun only(
        conversation: GroupConversation,
        text: String = TEXT,
    ): GroupMessage {
        val rows = conversation.messages.filter { it.decryptedContent == text }
        assertEquals("the message is not shown exactly once: ${conversation.messages.map { it.id.take(12) }}", 1, rows.size)
        return rows.single()
    }

    fun storedTexts(phone: AnnouncePhone): List<String> = OpenGroup.rows(phone.messages, GID).map { it.optString("decrypted") }

    /** The stored row of [GID] with [text] (the raw JSON object), or null. */
    fun storedRow(
        phone: AnnouncePhone,
        text: String,
    ): JSONObject? = OpenGroup.rows(phone.messages, GID).firstOrNull { it.optString("decrypted") == text }

    /** A scan of [GID] over a wallet with no transaction, so no state of this class reaches the next one. */
    suspend fun forgetGroup() {
        val vm = AnnounceDriver.groupVm(phone(), fixture())
        OpenGroupChain.rescan(vm, emptyList(), GID)
    }

    private fun unwrapped(block: () -> Unit) {
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    companion object {
        const val TEXT = "T6-G synthetic group text"
    }
}
