package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Z
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Round 8 R8-fixup F2 (REVIEW_R8 F2, device D-F2) — group history loses messages.
 *
 * (a) Our own group message that went out free over the relays lives only in the screen's in-memory pending list: the
 *     history rescan re-derives on-chain rows only, so once the screen's ViewModel is gone the message is gone.
 * (b) After any removal the rescan drops every pre-removal message with no verified author signature, then REWRITES
 *     the store with what it re-derived. Members introduced by the member announce hold no key with each other, so
 *     all their messages are unsigned: rows the live path had accepted and shown are deleted.
 * (b') Round 8 P6 tells the removed member about its own removal, and processGroupKick raises the removal floor
 *     before the self branch, so the removed phone drops its own copy of the others' history too.
 *
 * Wanted: the history merge never deletes a stored row (it only adds what it re-derives and supersedes a row by the
 * same (sender, seq, epoch)); our own relay-delivered message is stored; a phone that was itself removed does not raise
 * its floor. Control: a message the live path never stored, decrypting only under a pre-removal key and unsigned, is
 * still not added by the rescan (the D4 injection gate).
 *
 * Real ChatViewModel and GroupViewModel over [AnnouncePhone] / [AnnouncerPhone]. All addresses, keys and ids are
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupHistoryNeverDeletesTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

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
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `our own message sent free over the relays is still listed by a new group screen`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(MA3.B to MemberStatus.ACTIVE))
            admin.base.nostrKeys[MA3.B] = MA3.NPUB.getValue(MA3.B)
            net.register()
            val f = GroupPaidOnceFixture().apply { scheduler = this@GroupHistoryNeverDeletesTest.scheduler }
            val seam = AnnounceSeam()
            val first = MA3.groupVm(admin, f, seam)
            net.clear()

            f.awaitNewJobs(first) { first.sendGroupMessage(MA3.GID, "sent over the relays") }
            if (first.paidActionConfirmation.value != null) MA3.confirm(first, f)
            assertEquals("fixture sanity: the message went to B over the relays", 1, net.ofType(GroupMessageType.GROUP_MSG).size)
            assertEquals("fixture sanity: nothing was paid", 0, seam.proposals.size)

            val f2 = GroupPaidOnceFixture().apply { scheduler = this@GroupHistoryNeverDeletesTest.scheduler }
            val second = MA3.groupVm(admin, f2, AnnounceSeam())
            val conversation = MA3.load(second, f2)
            assertTrue(
                "our own relay-sent message is gone once the screen that sent it is gone: " +
                    "${conversation?.messages?.map { it.decryptedContent }}",
                conversation?.messages?.any { it.decryptedContent == "sent over the relays" } == true,
            )
        }

    @Test
    fun `a member's unsigned message shown before a removal survives the history rescan`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone, extraRows = listOf(row(Y), row(Z)))
            val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(phone.self))

            val fromY = AnnounceDriver.gm(phone, Y, "from Y before the removal", epoch = 1, seq = 1)
            AnnounceDriver.receive(chat, fromY, Instant.ofEpochMilli(AnnounceDriver.T), AnnounceDriver.txId(700))
            assertTrue("fixture sanity: the live path stored Y's message", storedTexts(phone).contains("from Y before the removal"))

            AnnounceDriver.receive(
                chat,
                AnnounceDriver.kick(phone, admin, Z, newEpoch = 2, newKey = AnnounceDriver.newGroupKey()),
                Instant.ofEpochMilli(AnnounceDriver.T + 120_000),
                AnnounceDriver.txId(701),
            )
            assertEquals("fixture sanity: the removal raised the floor", 2, phone.floors[GID])

            val own = AnnounceDriver.gm(phone, phone.self, "ours after the removal", epoch = 2, seq = 1)
            rescan(phone, listOf(received(700, fromY), sent(702, own)))

            assertTrue(
                "the rescan deleted Y's message the screen had shown: ${storedTexts(phone)}",
                storedTexts(phone).contains("from Y before the removal"),
            )
            assertTrue("fixture sanity: the rescan ran and stored our own message", storedTexts(phone).contains("ours after the removal"))
        }

    @Test
    fun `the removed member keeps the history it had and does not raise its floor`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone, extraRows = listOf(row(Y)))
            val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(phone.self))

            val fromY = AnnounceDriver.gm(phone, Y, "from Y to the removed member", epoch = 1, seq = 1)
            AnnounceDriver.receive(chat, fromY, Instant.ofEpochMilli(AnnounceDriver.T), AnnounceDriver.txId(710))
            assertTrue("fixture sanity: stored", storedTexts(phone).contains("from Y to the removed member"))

            AnnounceDriver.receive(
                chat,
                AnnounceDriver.kick(phone, admin, phone.self, newEpoch = 2, newKey = AnnounceDriver.newGroupKey()),
                Instant.ofEpochMilli(AnnounceDriver.T + 120_000),
                AnnounceDriver.txId(711),
            )
            assertTrue("fixture sanity: the removal reached this phone", phone.info(GID).removedByAdmin)
            assertEquals("the removed phone raised its own removal floor", 0, phone.floors[GID] ?: 0)

            val own = AnnounceDriver.gm(phone, phone.self, "ours before the removal", epoch = 1, seq = 1)
            rescan(phone, listOf(received(710, fromY), sent(712, own)))

            assertTrue(
                "the removed phone lost Y's message from before its removal: ${storedTexts(phone)}",
                storedTexts(phone).contains("from Y to the removed member"),
            )
        }

    /** Control, green before and after: the rescan still never ADDS an unsigned pre-removal-key message it never had. */
    @Test
    fun `control - an unsigned message under a pre-removal key that was never shown is not added by the rescan`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone, extraRows = listOf(row(Y), row(Z)))
            val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(phone.self))
            AnnounceDriver.receive(
                chat,
                AnnounceDriver.kick(phone, admin, Z, newEpoch = 2, newKey = AnnounceDriver.newGroupKey()),
                Instant.ofEpochMilli(AnnounceDriver.T),
                AnnounceDriver.txId(720),
            )
            val forged = AnnounceDriver.gm(phone, Y, "forged with the old key", epoch = 1, seq = 9)
            val own = AnnounceDriver.gm(phone, phone.self, "ours", epoch = 2, seq = 1)

            rescan(phone, listOf(received(721, forged), sent(722, own)))

            assertTrue("fixture sanity: the rescan ran", storedTexts(phone).contains("ours"))
            assertTrue(
                "the rescan added an unsigned message that decrypts only under a pre-removal key",
                !storedTexts(phone).contains("forged with the old key"),
            )
        }

    // --------------------------------------------------------------- harness

    private fun row(address: String) =
        co.electriccoin.zcash.ui.screen.chat.model.GroupMember(
            address = address,
            joinedAt = Instant.ofEpochMilli(AnnounceDriver.T),
            status = MemberStatus.ACTIVE,
        )

    private fun storedTexts(phone: AnnouncePhone): List<String> =
        phone.messages[GID]
            ?.let { json -> JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("decrypted") } } }
            .orEmpty()

    private fun received(
        n: Int,
        memo: String
    ): Pair<Transaction, String> {
        val id = AnnounceDriver.txId(n)
        val time = Instant.ofEpochMilli(AnnounceDriver.T + n)
        return ReceiveTransaction.Success(
            id = id,
            amount = Zatoshi(1_000),
            timestamp = time,
            memoCount = 1,
            transactionOutputs = emptyList(),
            overview = ScanPassHarness.overview(id, 3_000_000L + n, time, sent = false, amount = 1_000),
            recipient = null,
        ) to memo
    }

    private fun sent(
        n: Int,
        memo: String
    ): Pair<Transaction, String> {
        val id = AnnounceDriver.txId(n)
        val time = Instant.ofEpochMilli(AnnounceDriver.T + n)
        return SendTransaction.Success(
            id = id,
            amount = Zatoshi(1_000),
            timestamp = time,
            memoCount = 1,
            fee = Zatoshi(15_000),
            transactionOutputs = emptyList(),
            overview = ScanPassHarness.overview(id, 3_000_000L + n, time, sent = true, amount = 1_000),
            recipient = null,
        ) to memo
    }

    /** The group screen's history rescan over [txs] on [phone]. */
    private suspend fun rescan(
        phone: AnnouncePhone,
        txs: List<Pair<Transaction, String>>,
    ) {
        val f = GroupPaidOnceFixture().apply { scheduler = this@GroupHistoryNeverDeletesTest.scheduler }
        val vm = AnnounceDriver.groupVm(phone, f)
        GroupViewModel::class.java
            .getDeclaredField("transactionRepository")
            .apply { isAccessible = true }
            .set(vm, ChainOf(txs))
        vm.loadGroupMessagesFromHistory(GID)
    }

    private class ChainOf(
        private val txs: List<Pair<Transaction, String>>,
    ) : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)

        override suspend fun getMemos(transaction: Transaction): List<String> = listOf(txs.first { it.first.id == transaction.id }.second)

        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

        override suspend fun getTransactions(): List<Transaction> = txs.map { it.first }

        override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()
    }
}
