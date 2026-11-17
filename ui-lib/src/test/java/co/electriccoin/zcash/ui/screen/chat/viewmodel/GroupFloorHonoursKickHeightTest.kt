package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.X
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Z
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * Round 9 R9-3 (REVIEW_R8 F2 spec item 3; left open by 3bcbc11ab: "a message mined before the kick but first seen after
 * it is still dropped by the floor gate (the kick's mined height is not stored)").
 *
 * After a removal, both floor gates (the live path, ChatViewModel.processGroupMsg, and the history rescan,
 * GroupViewModel.loadGroupMessagesFromHistory) drop every unsigned message that decrypts only under a pre-removal key.
 * They have no height test, so a member's message MINED BEFORE the kick but first seen after it (the phone was offline
 * across the kick, or one scan pass processed the kick first) is dropped: group data loss on the removal path. Members
 * introduced by the member announce hold no key with each other, so all their messages are unsigned.
 *
 * Wanted: the removal floor remembers the removal's height (an on-chain kick: its mined height; a kick over the relays:
 * the height this phone had scanned to when it acted on it; the creator's own kick: the height its phone had scanned to
 * when it removed the member), and an unsigned pre-removal message is kept only when its own mined height is known and
 * below that height. For a message under key epoch e, the removals that count are those that rotated past e (the
 * earliest of them bounds it).
 *
 * (a) rescan and (b) live path: mined before the kick, seen after it, stored.
 * (c) controls, D4 kept: mined after the kick, in the kick's block, unmined, above the scanned height of a relay kick,
 *     under a key an earlier removal already retired, with no known kick height (a floor stored by an older build, or a
 *     relay kick acted on with no scanned height), or sent over the relays by the removed member: still dropped.
 * (d) the creator's own removal (GroupViewModel.rotateAndNotify) records the height its phone had scanned to.
 *
 * Real ChatViewModel and GroupViewModel over [AnnouncePhone] / [AnnouncerPhone]. All addresses, keys and ids are
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupFloorHonoursKickHeightTest {
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

    // ------------------------------------------------------------------ (a) the history rescan

    @Test
    fun `rescan - a message mined before an on-chain kick but first seen after it is listed and stored`() =
        runBlocking {
            val (phone, _) = memberAfterOnChainKick()
            val fromY = AnnounceDriver.gm(phone, Y, "mined before the kick", epoch = 1, seq = 1)

            rescan(phone, listOf(received(10, fromY, KICK_HEIGHT - 10), ownAfter(phone)))

            assertTrue(
                "the rescan dropped Y's message mined at ${KICK_HEIGHT - 10}, before the kick at $KICK_HEIGHT: ${storedTexts(phone)}",
                storedTexts(phone).contains("mined before the kick"),
            )
            assertTrue(
                "the group screen does not list it",
                listedTexts(phone).contains("mined before the kick"),
            )
        }

    @Test
    fun `rescan - a message mined before a relay kick's scanned height is listed and stored`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone, extraRows = listOf(row(Y), row(Z)))
            val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(phone.self))
            scannedTo(chat, scanned = SCANNED, network = SCANNED + 50)
            relayKick(chat, phone, admin, Z, newEpoch = 2)
            assertEquals("fixture sanity: the relay kick raised the floor", 2, phone.floors[GID])

            val fromY = AnnounceDriver.gm(phone, Y, "mined before the relay kick", epoch = 1, seq = 1)
            rescan(phone, listOf(received(11, fromY, SCANNED - 10), ownAfter(phone)))

            assertTrue(
                "the rescan dropped Y's message mined below the height the phone had scanned to when it took the kick",
                storedTexts(phone).contains("mined before the relay kick"),
            )
        }

    // ------------------------------------------------------------------ (b) the live path

    @Test
    fun `live - a message mined before an on-chain kick but delivered after it is stored`() =
        runBlocking {
            val (phone, chat) = memberAfterOnChainKick()
            val fromY = AnnounceDriver.gm(phone, Y, "late delivery of an early message", epoch = 1, seq = 2)

            AnnounceDriver.receive(chat, fromY, blockTime(20), AnnounceDriver.txId(20), minedHeight = KICK_HEIGHT - 1)

            assertTrue(
                "the live path dropped Y's message mined at ${KICK_HEIGHT - 1}, before the kick at $KICK_HEIGHT",
                storedTexts(phone).contains("late delivery of an early message"),
            )
        }

    @Test
    fun `live - a message mined before a relay kick's scanned height is stored, one above it is dropped`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone, extraRows = listOf(row(Y), row(Z)))
            val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(phone.self))
            scannedTo(chat, scanned = SCANNED, network = SCANNED + 50)
            relayKick(chat, phone, admin, Z, newEpoch = 2)

            val before = AnnounceDriver.gm(phone, Y, "below the scanned height", epoch = 1, seq = 3)
            val above = AnnounceDriver.gm(phone, Y, "above the scanned height, below the network tip", epoch = 1, seq = 4)
            AnnounceDriver.receive(chat, before, blockTime(21), AnnounceDriver.txId(21), minedHeight = SCANNED - 1)
            AnnounceDriver.receive(chat, above, blockTime(22), AnnounceDriver.txId(22), minedHeight = SCANNED + 10)

            assertTrue(
                "the live path dropped Y's message mined below the height the phone had scanned to when it took the relay kick",
                storedTexts(phone).contains("below the scanned height"),
            )
            assertFalse(
                "a message mined above the scanned height (the relay kick counts from what the phone had scanned, not the network tip) was kept",
                storedTexts(phone).contains("above the scanned height, below the network tip"),
            )
        }

    // ------------------------------------------------------------------ (c) controls: D4 kept

    @Test
    fun `control - mined after the kick, in the kick's block, or unmined, the message is still dropped`() =
        runBlocking {
            val (phone, chat) = memberAfterOnChainKick()
            val after = AnnounceDriver.gm(phone, Y, "mined after the kick", epoch = 1, seq = 5)
            val same = AnnounceDriver.gm(phone, Y, "mined in the kick's block", epoch = 1, seq = 6)
            val unmined = AnnounceDriver.gm(phone, Y, "not mined", epoch = 1, seq = 7)

            AnnounceDriver.receive(chat, after, blockTime(30), AnnounceDriver.txId(30), minedHeight = KICK_HEIGHT + 1)
            AnnounceDriver.receive(chat, same, blockTime(31), AnnounceDriver.txId(31), minedHeight = KICK_HEIGHT)
            AnnounceDriver.receive(chat, unmined, null, AnnounceDriver.txId(32), minedHeight = null)
            rescan(
                phone,
                listOf(
                    received(30, after, KICK_HEIGHT + 1),
                    received(31, same, KICK_HEIGHT),
                    received(32, unmined, null),
                    ownAfter(phone),
                ),
            )

            val stored = storedTexts(phone)
            assertTrue("fixture sanity: the rescan ran", stored.contains(OWN_TEXT))
            assertFalse("a message mined after the kick was kept", stored.contains("mined after the kick"))
            assertFalse("a message mined in the kick's own block was kept", stored.contains("mined in the kick's block"))
            assertFalse("an unmined message was kept", stored.contains("not mined"))
        }

    @Test
    fun `control - a key an earlier removal retired stays gated by that removal's height`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone, extraRows = listOf(row(Y), row(Z), row(X)))
            val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(phone.self))
            // Z is removed at K1 (key 1 -> 2), then X at K2 (key 2 -> 3). Z still holds key 1, X holds keys 1 and 2.
            onChainKick(chat, phone, admin, Z, newEpoch = 2, tx = 40, height = KICK_HEIGHT)
            onChainKick(chat, phone, admin, X, newEpoch = 3, tx = 41, height = KICK_HEIGHT + 100)
            assertEquals("fixture sanity: both removals raised the floor", 3, phone.floors[GID])

            val underKey1 = AnnounceDriver.gm(phone, Y, "key 1, between the removals", epoch = 1, seq = 8)
            val underKey2 = AnnounceDriver.gm(phone, Y, "key 2, between the removals", epoch = 2, seq = 9)
            AnnounceDriver.receive(chat, underKey1, blockTime(42), AnnounceDriver.txId(42), minedHeight = KICK_HEIGHT + 50)
            AnnounceDriver.receive(chat, underKey2, blockTime(43), AnnounceDriver.txId(43), minedHeight = KICK_HEIGHT + 50)

            assertFalse(
                "a key-1 message mined after Z's removal was kept (Z holds key 1)",
                storedTexts(phone).contains("key 1, between the removals"),
            )
            assertTrue(
                "a key-2 message mined before X's removal was dropped (only X, removed later, held key 2)",
                storedTexts(phone).contains("key 2, between the removals"),
            )
        }

    @Test
    fun `control - a floor with no known removal height drops as before`() =
        runBlocking {
            // A floor stored by an older build: the epoch only.
            val phone = AnnouncePhone()
            AnnounceDriver.memberPhone(phone, extraRows = listOf(row(Y), row(Z)))
            val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(phone.self))
            phone.keys["$GID|2"] = AnnounceDriver.newGroupKey()
            phone.epochs[GID] = 2
            phone.floors[GID] = 2
            val old = AnnounceDriver.gm(phone, Y, "under an older build's floor", epoch = 1, seq = 10)
            AnnounceDriver.receive(chat, old, blockTime(50), AnnounceDriver.txId(50), minedHeight = 2_000_000L)
            rescan(phone, listOf(received(50, old, 2_000_000L), ownAfter(phone)))
            assertFalse(
                "a floor with no removal height kept an unsigned pre-removal message",
                storedTexts(phone).contains("under an older build's floor")
            )

            // A relay kick acted on while the phone knew no scanned height.
            val phone2 = AnnouncePhone()
            val admin2 = AnnounceDriver.memberPhone(phone2, extraRows = listOf(row(Y), row(Z)))
            val chat2 = AnnounceDriver.chatVm(phone2, HandshakeSeam(phone2.self))
            relayKick(chat2, phone2, admin2, Z, newEpoch = 2)
            assertEquals("fixture sanity: the relay kick raised the floor", 2, phone2.floors[GID])
            val early = AnnounceDriver.gm(phone2, Y, "no scanned height at the kick", epoch = 1, seq = 11)
            AnnounceDriver.receive(chat2, early, blockTime(51), AnnounceDriver.txId(51), minedHeight = 2_000_000L)
            assertFalse(
                "a relay kick with no scanned height let an unsigned pre-removal message through",
                storedTexts(phone2).contains("no scanned height at the kick")
            )
        }

    @Test
    fun `control - the removed member's message over the relays is still dropped`() =
        runBlocking {
            val (phone, chat) = memberAfterOnChainKick()
            val forged = AnnounceDriver.gm(phone, Y, "sealed by the removed member", epoch = 1, seq = 12)
            AnnounceDriver.receive(chat, forged, blockTime(60), sender = Z, eventId = "synthetic-r93-relay-60")
            assertFalse(
                "a relay message sealed by the removed member was kept",
                storedTexts(phone).contains("sealed by the removed member")
            )
        }

    // ------------------------------------------------------------------ (d) the creator's own removal

    @Test
    fun `creator - its own removal counts from the height its phone had scanned to`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(MA3.A to MemberStatus.ACTIVE, MA3.B to MemberStatus.ACTIVE, MA3.X to MemberStatus.ACTIVE))
            listOf(MA3.A, MA3.B, MA3.X).forEach {
                MA3.memberPhone(it, admin)
                admin.base.nostrKeys[it] = MA3.NPUB.getValue(it)
            }
            net.register()
            val f = GroupPaidOnceFixture().apply { scheduler = this@GroupFloorHonoursKickHeightTest.scheduler }
            val vm = MA3.groupVm(admin, f, AnnounceSeam())
            GroupViewModel::class.java
                .getDeclaredField("synchronizerProvider")
                .apply { isAccessible = true }
                .set(vm, provider(scanned = SCANNED, network = SCANNED + 50))
            MA3.load(vm, f)

            f.awaitNewJobs(vm) { vm.kickMember(MA3.GID, MA3.X) }
            if (vm.paidActionConfirmation.value != null) MA3.confirm(vm, f)
            assertEquals("fixture sanity: the creator's removal raised its floor", 2, admin.base.floors[MA3.GID])

            val early = creatorGm(admin, MA3.B, "B before the creator removed X", seq = 1)
            val late = creatorGm(admin, MA3.B, "B above the creator's scanned height", seq = 2)
            val f2 = GroupPaidOnceFixture().apply { scheduler = this@GroupFloorHonoursKickHeightTest.scheduler }
            val rescanVm = MA3.groupVm(admin, f2, AnnounceSeam())
            rescanWith(
                rescanVm,
                MA3.GID,
                listOf(
                    received(70, early, SCANNED - 10),
                    received(71, late, SCANNED + 10),
                    sentTx(72, creatorGm(admin, MA3.C, OWN_TEXT, seq = 3, epoch = 2), SCANNED + 20),
                ),
            )

            val stored = texts(admin.base.messages[MA3.GID])
            assertTrue("fixture sanity: the rescan ran", stored.contains(OWN_TEXT))
            assertTrue(
                "the creator's rescan dropped B's message mined before the creator removed X: $stored",
                stored.contains("B before the creator removed X"),
            )
            assertFalse(
                "the creator's rescan kept B's unsigned key-1 message mined above the height it had scanned to at the removal",
                stored.contains("B above the creator's scanned height"),
            )
        }

    // ------------------------------------------------------------------ harness

    private fun row(address: String) =
        GroupMember(
            address = address,
            joinedAt = Instant.ofEpochMilli(AnnounceDriver.T),
            status = MemberStatus.ACTIVE
        )

    private fun blockTime(n: Int): Instant = Instant.ofEpochMilli(AnnounceDriver.T + 600_000L + n * 1_000L)

    /** A member's phone (Y and Z in the group) that took Z's removal from the chain scan, mined at [KICK_HEIGHT]. */
    private suspend fun memberAfterOnChainKick(): Pair<AnnouncePhone, ChatViewModel> {
        val phone = AnnouncePhone()
        val admin = AnnounceDriver.memberPhone(phone, extraRows = listOf(row(Y), row(Z)))
        val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(phone.self))
        onChainKick(chat, phone, admin, Z, newEpoch = 2, tx = 1, height = KICK_HEIGHT)
        assertEquals("fixture sanity: the removal raised the floor", 2, phone.floors[GID])
        return phone to chat
    }

    private fun onChainKick(
        chat: ChatViewModel,
        phone: AnnouncePhone,
        admin: co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair,
        kicked: String,
        newEpoch: Int,
        tx: Int,
        height: Long,
    ) = AnnounceDriver.receive(
        chat,
        AnnounceDriver.kick(phone, admin, kicked, newEpoch = newEpoch, newKey = AnnounceDriver.newGroupKey()),
        blockTime(tx),
        AnnounceDriver.txId(tx),
        minedHeight = height,
    )

    private fun relayKick(
        chat: ChatViewModel,
        phone: AnnouncePhone,
        admin: co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair,
        kicked: String,
        newEpoch: Int,
    ) = AnnounceDriver.receive(
        chat,
        AnnounceDriver.kick(phone, admin, kicked, newEpoch = newEpoch, newKey = AnnounceDriver.newGroupKey()),
        blockTime(0),
        sender = AnnounceDriver.ADMIN,
        eventId = "synthetic-r93-kick-$newEpoch",
    )

    /** The ChatViewModel's chain heights: scanned to [scanned], the network tip at [network]. */
    private fun scannedTo(
        chat: ChatViewModel,
        scanned: Long,
        network: Long,
    ) = AutoSpendVm.setField(chat, "synchronizerProvider", provider(scanned, network))

    private fun provider(
        scanned: Long,
        network: Long,
    ): SynchronizerProvider {
        val synchronizer =
            Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, method, args ->
                when (method.name) {
                    "getFullyScannedHeight" -> MutableStateFlow<BlockHeight?>(BlockHeight.new(scanned))
                    "getNetworkHeight" -> MutableStateFlow<BlockHeight?>(BlockHeight.new(network))
                    "toString" -> "R93Synchronizer"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
                }
            } as Synchronizer
        return object : SynchronizerProvider {
            override val error = MutableStateFlow<SynchronizerError?>(null)
            override val synchronizer = MutableStateFlow<Synchronizer?>(synchronizer)

            // Nothing here waits for a block: a wait would hang, as in GroupPaidOnceFixture.
            override suspend fun getSynchronizer(): Synchronizer = awaitCancellation()

            override fun resetSynchronizer() = Unit
        }
    }

    /** A GROUP_MSG in the MA3 group under key [epoch] (the creator's copy of it), unsigned. */
    private fun creatorGm(
        admin: AnnouncerPhone,
        sender: String,
        text: String,
        seq: Long,
        epoch: Int = 1,
    ): String =
        ZMSGGroupProtocol.createGroupMsgMessage(
            MA3.GID,
            seq,
            epoch,
            sender,
            text,
            ZMSGGroupProtocol.decodeGroupKey(admin.base.keys.getValue("${MA3.GID}|$epoch")),
            null,
        )

    private fun ownAfter(phone: AnnouncePhone): Pair<Transaction, String> =
        sentTx(90, AnnounceDriver.gm(phone, phone.self, OWN_TEXT, epoch = phone.epochs.getValue(GID), seq = 90), KICK_HEIGHT + 500)

    private fun texts(json: String?): List<String> =
        json
            ?.let { JSONArray(it).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).optString("decrypted") } } }
            .orEmpty()

    private fun storedTexts(phone: AnnouncePhone): List<String> = texts(phone.messages[GID])

    /** What a new group screen lists for [GID] on [phone]. */
    private suspend fun listedTexts(phone: AnnouncePhone): List<String> {
        val f = GroupPaidOnceFixture().apply { scheduler = this@GroupFloorHonoursKickHeightTest.scheduler }
        val vm = AnnounceDriver.groupVm(phone, f)
        GroupViewModel::class.java
            .getDeclaredField("transactionRepository")
            .apply { isAccessible = true }
            .set(vm, ChainOf(emptyList()))
        return AnnounceDriver
            .loadedConversation(vm, f)
            ?.messages
            ?.mapNotNull { it.decryptedContent }
            .orEmpty()
    }

    private fun received(
        n: Int,
        memo: String,
        height: Long?,
    ): Pair<Transaction, String> {
        val id = AnnounceDriver.txId(n)
        val time = blockTime(n)
        return ReceiveTransaction.Success(
            id = id,
            amount = Zatoshi(1_000),
            timestamp = time,
            memoCount = 1,
            transactionOutputs = emptyList(),
            overview = ScanPassHarness.overview(id, height, time, sent = false, amount = 1_000),
            recipient = null,
        ) to memo
    }

    private fun sentTx(
        n: Int,
        memo: String,
        height: Long,
    ): Pair<Transaction, String> {
        val id = AnnounceDriver.txId(n)
        val time = blockTime(n)
        return SendTransaction.Success(
            id = id,
            amount = Zatoshi(1_000),
            timestamp = time,
            memoCount = 1,
            fee = Zatoshi(15_000),
            transactionOutputs = emptyList(),
            overview = ScanPassHarness.overview(id, height, time, sent = true, amount = 1_000),
            recipient = null,
        ) to memo
    }

    /** The group screen's history rescan over [txs] on [phone]. */
    private suspend fun rescan(
        phone: AnnouncePhone,
        txs: List<Pair<Transaction, String>>,
    ) {
        val f = GroupPaidOnceFixture().apply { scheduler = this@GroupFloorHonoursKickHeightTest.scheduler }
        rescanWith(AnnounceDriver.groupVm(phone, f), GID, txs)
    }

    private suspend fun rescanWith(
        vm: GroupViewModel,
        groupId: String,
        txs: List<Pair<Transaction, String>>,
    ) {
        GroupViewModel::class.java
            .getDeclaredField("transactionRepository")
            .apply { isAccessible = true }
            .set(vm, ChainOf(txs))
        vm.loadGroupMessagesFromHistory(groupId)
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

    private companion object {
        const val KICK_HEIGHT = 3_000_100L
        const val SCANNED = 3_000_300L
        const val OWN_TEXT = "ours after the removal"
    }
}
