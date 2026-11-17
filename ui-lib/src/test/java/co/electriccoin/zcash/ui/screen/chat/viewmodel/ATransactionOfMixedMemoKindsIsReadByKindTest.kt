package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.kexWithRelayFields
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.relayKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ClaimMarkChat.Companion.v3Boot
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.OLD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC-1279 (compat-2124; ZMSG_PROTOCOL_SPEC): the history pass joins all of a transaction's memos into one text
 * (`memos.joinToString("\n").trim()`, or reassembleChunks when any is chunked), so a transaction that holds different kinds
 * is read as one garbled message: its KEX, KEXACK and ZBOOT never reach their handlers. No sender makes such a transaction
 * yet; the list here is synthetic, as a one-transaction first contact would write it, and Orchard shuffles outputs, so every
 * order must work.
 *
 * Rule: each memo is classified by its prefix. Every memo one kind: today's join, byte for byte (the controls; the memos and
 * the texts below are literal so they stay frozen against this code, in the format of v2.12.4's
 * ChatViewModel.kt:3524-3527 and 3588-3591, and these controls are green before and after the fix). Mixed: KEX, KEXACK and
 * ZBOOT memos go to their handlers and the rest is the message row, each once. A ZBOOT read before its KEX still ends adopted;
 * one pass later is fine. The hidden-row path reads its memos the same way.
 *
 * Seam for the fix, reached by name so this compiles before it exists:
 * `ZMSGProtocol.splitMemosByKind(memos: List<String>): List<List<String>>`, the units to read as one transaction each read
 * today: a single unit equal to the input when every memo is one kind; otherwise one unit per KEX, KEXACK or ZBOOT memo and
 * one unit with all the other memos in their order.
 *
 * Real pass (convertToConversations) over [HandshakePassFixture]. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ATransactionOfMixedMemoKindsIsReadByKindTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val peer = SyntheticUnifiedAddress.mainnet(seed = 127901)

    private companion object {
        const val CONV = "ZMIX0001"
        const val TEXT = "synthetic words of a first contact"
        const val RELAY_SEED = 91
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun message(): String = ZMSGProtocol.createV4InitMessage(CONV, peer, TEXT)

    private fun answerFrom(
        keys: E2EKeyPair,
        convId: String,
    ): String = ZMSGProtocol.createV4KEXAckMessage(convId, peer, E2EEncryption.createKEXAckPayload(peer, keys.publicKey, keys.privateKey))

    private fun kexFrom(
        keys: E2EKeyPair,
        convId: String,
    ): String = ZMSGProtocol.createV4KEXMessage(convId, peer, E2EEncryption.createKEXPayload(peer, keys.publicKey, keys.privateKey))

    // ── mixed, first contact ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a first contact whose message comes first still offers its key, shows its row and adopts its relay identity`() =
        runBlocking { firstContact("mixed-mkb") { m, k, b -> listOf(m, k, b) } }

    @Test
    fun `a first contact whose key exchange comes first still offers its key, shows its row and adopts its relay identity`() =
        runBlocking { firstContact("mixed-kmb") { m, k, b -> listOf(k, m, b) } }

    @Test
    fun `a first contact whose ZBOOT comes before its key exchange still ends adopted`() =
        runBlocking { firstContact("mixed-bkm") { m, k, b -> listOf(b, k, m) } }

    @Test
    fun `a first contact in the other output orders reads the same`() =
        runBlocking {
            firstContact("mixed-mbk") { m, k, b -> listOf(m, b, k) }
            freshProcess()
            firstContact("mixed-kbm") { m, k, b -> listOf(k, b, m) }
            freshProcess()
            firstContact("mixed-bmk") { m, k, b -> listOf(b, m, k) }
        }

    private fun freshProcess() {
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    private suspend fun firstContact(
        label: String,
        order: (String, String, String) -> List<String>,
    ) {
        val f = HandshakePassFixture(scheduler, peer)
        val maps = ClaimMarkMaps(f.prefs.e2ePeerKey, f.prefs.askedKey)
        f.extra = { method, args -> maps.handle(method, args) }
        f.open()
        val stranger = E2EEncryption.generateKeyPair()
        val memos =
            order(
                message(),
                kexWithRelayFields(peer, stranger, CONV, relayKey(RELAY_SEED - 1)),
                v3Boot(stranger, relayKey(RELAY_SEED), convId = CONV),
            )
        f.received(label, "unused placeholder", minedAt = NEW + 10)
        f.memosOf(mapOf(label to memos))
        f.pass()

        assertEquals("$label: the key exchange did not reach its handler", stranger.publicKey, f.prefs.awaitingAccept[peer])
        assertEquals("$label: something was paid before Accept", 0, f.seam.proposalRequests.get())
        assertTrue("$label: the held key exchange bound a relay key", maps.binds.isEmpty())
        assertRowOnce(f, label)

        f.vm.acceptInboundKexRequest(peer)
        f.idle()
        f.pass()
        assertEquals("$label: the ZBOOT never ended adopted", relayKey(RELAY_SEED), maps.nostr[peer])
        assertRowOnce(f, label)
    }

    private suspend fun assertRowOnce(
        f: HandshakePassFixture,
        label: String,
    ) {
        val rows = f.rows().flatMap { it.messages }.map { it.text }
        assertEquals("$label: the message row, once, among $rows", 1, rows.count { it == TEXT })
        assertTrue("$label: a raw memo is on screen", rows.none { "ZMSG|" in it || "ZBOOT|" in it || "|KEX|" in it })
    }

    // ── mixed, an answer to a chat we started ───────────────────────────────────────────────────────────────

    @Test
    fun `an answer in one transaction with a ZBOOT and a message stores the key and adopts the relay identity, in any order`() =
        runBlocking {
            val orders =
                listOf<Pair<String, (String, String, String) -> List<String>>>(
                    "amb" to { a, m, b -> listOf(a, m, b) },
                    "mab" to { a, m, b -> listOf(m, a, b) },
                    "bam" to { a, m, b -> listOf(b, a, m) },
                )
            for ((name, order) in orders) {
                freshProcess()
                val label = "ack-$name"
                val f = HandshakePassFixture(scheduler, peer)
                val maps = ClaimMarkMaps(f.prefs.e2ePeerKey, f.prefs.askedKey)
                f.extra = { method, args -> maps.handle(method, args) }
                val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(peer)
                f.open()
                val keys = E2EEncryption.generateKeyPair()
                f.ownKex("$label-own", peer, own, minedAt = NEW + 5)
                f.received(label, "unused placeholder", minedAt = NEW + 10)
                f.memosOf(
                    mapOf(
                        label to
                            order(
                                answerFrom(keys, own),
                                ZMSGProtocol.createV4ReplyMessage(own, peer, TEXT),
                                v3Boot(keys, relayKey(RELAY_SEED), convId = own),
                            ),
                    ),
                )
                f.pass(times = 3)
                assertEquals("$label: the answer did not reach its handler", keys.publicKey, f.prefs.e2ePeerKey[peer])
                assertEquals("$label: the ZBOOT never ended adopted", relayKey(RELAY_SEED), maps.nostr[peer])
            }
        }

    // ── mixed, our own send ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `our own transaction of a key exchange and a message shows the message row`() =
        runBlocking {
            for ((name, flip) in listOf("km" to false, "mk" to true)) {
                freshProcess()
                val label = "own-$name"
                val f = HandshakePassFixture(scheduler, peer)
                val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(peer)
                f.open()
                val ours = E2EEncryption.generateKeyPair()
                f.prefs.ourKeyStore[peer] = ours.publicKey to ours.privateKey
                val kex =
                    ZMSGProtocol.createV4KEXMessage(
                        own,
                        HandshakePassFixture.SELF,
                        E2EEncryption.createKEXPayload(HandshakePassFixture.SELF, ours.publicKey, ours.privateKey),
                    )
                val line = ZMSGProtocol.createV4ReplyMessage(own, HandshakePassFixture.SELF, TEXT)
                f.sent(label, "unused placeholder", peer, minedAt = NEW + 10)
                f.memosOf(mapOf(label to if (flip) listOf(line, kex) else listOf(kex, line)))
                f.pass()
                assertRowOnce(f, label)
            }
        }

    // ── mixed, the hidden row ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a hidden old transaction of a message and an answer still counts as an old answer, so its repeat waits for Accept`() =
        runBlocking {
            // As AnswerNobodyAskedForChangesNothingTest (t11a), with the hidden answer in a transaction beside a message.
            val label = "hidden-mixed"
            val f = HandshakePassFixture(scheduler, peer)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(peer)
            f.open()
            val stranger = E2EEncryption.generateKeyPair()
            val answer = answerFrom(stranger, "ZTBA1111")
            f.received("$label-ack", answer, minedAt = OLD + 10)
            f.ownKex("$label-own", peer, own, minedAt = OLD + 20)
            f.pass()
            assertEquals("fixture sanity: the old answer's key is stored", stranger.publicKey, f.prefs.e2ePeerKey[peer])

            freshProcess()
            f.hidden += "$label-ack"
            f.open()
            f.memosOf(mapOf("$label-ack" to listOf(message(), answer)))
            f.received("$label-kex", kexFrom(stranger, "ZTBA2222"), minedAt = NEW + 12)
            f.pass()

            assertEquals("an answer was paid to a stranger before Accept", 0, f.seam.proposalRequests.get())
            assertEquals("the request must wait for Accept", stranger.publicKey, f.prefs.awaitingAccept[peer])
        }

    // ── every memo one kind: today's reading, byte for byte ─────────────────────────────────────────────────

    private val frozenInit get() = "ZMSG|v4|$CONV|INIT|$peer|$TEXT"
    private val frozenChunks
        get() = listOf("ZMSG|v4c|1/2|$CONV|INIT|$peer|first half, ", "ZMSG|v4c|2/2|CONT|second half")
    private val frozenPlain = listOf("  plain line one", "plain line two  ")

    @Test
    fun `control - frozen memos are still in today's format`() {
        assertEquals("the INIT memo's format moved", frozenInit, ZMSGProtocol.createV4InitMessage(CONV, peer, TEXT))
    }

    @Test
    fun `control - one message memo reads as today`() = runBlocking { readsAs("one", listOf(frozenInit), TEXT) }

    @Test
    fun `control - chunks in any order read as today`() =
        runBlocking {
            readsAs("chunks", frozenChunks, "first half, second half")
            freshProcess()
            readsAs("chunks-flipped", frozenChunks.reversed(), "first half, second half")
        }

    @Test
    fun `control - memos with no ZMSG prefix are joined as today`() =
        runBlocking { readsAs("plain", frozenPlain, "plain line one\nplain line two") }

    @Test
    fun `control - a key exchange alone waits for Accept as today`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            f.open()
            val stranger = E2EEncryption.generateKeyPair()
            f.received("kex-alone", kexFrom(stranger, CONV), minedAt = NEW + 10)
            f.pass()
            assertEquals(stranger.publicKey, f.prefs.awaitingAccept[peer])
        }

    @Test
    fun `control - a key exchange and its ZBOOT in two transactions end adopted, as the mixed ones must`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            val maps = ClaimMarkMaps(f.prefs.e2ePeerKey, f.prefs.askedKey)
            f.extra = { method, args -> maps.handle(method, args) }
            f.open()
            val stranger = E2EEncryption.generateKeyPair()
            f.received("two-kex", kexWithRelayFields(peer, stranger, CONV, relayKey(RELAY_SEED - 1)), minedAt = NEW + 10)
            f.received("two-boot", v3Boot(stranger, relayKey(RELAY_SEED), convId = CONV), minedAt = NEW + 11)
            f.received("two-line", message(), minedAt = NEW + 12)
            f.pass()
            assertEquals(stranger.publicKey, f.prefs.awaitingAccept[peer])
            f.vm.acceptInboundKexRequest(peer)
            f.idle()
            f.pass()
            assertEquals(relayKey(RELAY_SEED), maps.nostr[peer])
            assertRowOnce(f, "two transactions")
        }

    @Test
    fun `control - an answer and its ZBOOT in two transactions end adopted, as the mixed ones must`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            val maps = ClaimMarkMaps(f.prefs.e2ePeerKey, f.prefs.askedKey)
            f.extra = { method, args -> maps.handle(method, args) }
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(peer)
            f.open()
            val keys = E2EEncryption.generateKeyPair()
            f.ownKex("two-own", peer, own, minedAt = NEW + 5)
            f.received("two-ack", answerFrom(keys, own), minedAt = NEW + 10)
            f.received("two-boot", v3Boot(keys, relayKey(RELAY_SEED), convId = own), minedAt = NEW + 11)
            f.pass(times = 3)
            assertEquals(keys.publicKey, f.prefs.e2ePeerKey[peer])
            assertEquals(relayKey(RELAY_SEED), maps.nostr[peer])
        }

    @Test
    fun `control - our own message alone shows its row as today`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(peer)
            f.open()
            f.sent("own-alone", ZMSGProtocol.createV4ReplyMessage(own, HandshakePassFixture.SELF, TEXT), peer, minedAt = NEW + 10)
            f.pass()
            assertRowOnce(f, "own-alone")
        }

    private suspend fun readsAs(
        label: String,
        memos: List<String>,
        text: String,
    ) {
        val f = HandshakePassFixture(scheduler, peer)
        f.open()
        f.received(label, "unused placeholder", minedAt = NEW + 10)
        f.memosOf(mapOf(label to memos))
        f.pass()
        val rows = f.rows().flatMap { it.messages }.map { it.text }
        assertEquals("$label: today's reading moved, among $rows", 1, rows.count { it == text })
    }

    // ── the seam ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `splitMemosByKind keeps every memo of one kind as one unit, unchanged`() {
        val stranger = E2EEncryption.generateKeyPair()
        val boot = v3Boot(stranger, relayKey(RELAY_SEED), convId = CONV)
        val kex = kexFrom(stranger, CONV)
        val oneKind =
            listOf(
                listOf(frozenInit),
                frozenChunks,
                frozenChunks.reversed(),
                frozenPlain,
                listOf(kex),
                listOf(boot),
                listOf(boot, boot),
            )
        for (memos in oneKind) {
            assertEquals("one kind must stay one unit, in its order", listOf(memos), split(memos))
        }
    }

    @Test
    fun `splitMemosByKind gives each handshake memo its own unit and keeps the rest together, in any order`() {
        val stranger = E2EEncryption.generateKeyPair()
        val boot = v3Boot(stranger, relayKey(RELAY_SEED), convId = CONV)
        val kex = kexFrom(stranger, CONV)
        val ack = answerFrom(stranger, CONV)
        assertEquals(setOf(listOf(frozenInit), listOf(kex), listOf(boot)), split(listOf(frozenInit, kex, boot)).toSet())
        assertEquals(setOf(listOf(frozenInit), listOf(kex), listOf(boot)), split(listOf(boot, frozenInit, kex)).toSet())
        assertEquals(setOf(listOf(frozenInit), listOf(ack)), split(listOf(ack, frozenInit)).toSet())
        // The chunks stay one unit between the handshake memos, in their order.
        assertEquals(setOf(frozenChunks, listOf(kex)), split(listOf(frozenChunks[0], kex, frozenChunks[1])).toSet())
    }

    @Suppress("UNCHECKED_CAST")
    private fun split(memos: List<String>): List<List<String>> {
        val method =
            ZMSGProtocol::class.java.methods.firstOrNull { it.name == "splitMemosByKind" }
                ?: throw AssertionError("ZMSGProtocol.splitMemosByKind does not exist: mixed memos have no reader")
        return method.invoke(ZMSGProtocol, memos) as List<List<String>>
    }

    // ── support ─────────────────────────────────────────────────────────────────────────────────────────────

    /** From here the repository answers the labelled transactions with these memos, in this order, and the rest as before. */
    private fun HandshakePassFixture.memosOf(byLabel: Map<String, List<String>>) {
        val field = ChatViewModel::class.java.getDeclaredField("transactionRepository").apply { isAccessible = true }
        val inner = field.get(vm)
        val byTx = byLabel.mapKeys { txid(it.key) }
        val wrapped =
            Proxy.newProxyInstance(
                TransactionRepository::class.java.classLoader,
                arrayOf(TransactionRepository::class.java),
            ) { _, method, args ->
                val mixed = (args?.firstOrNull() as? Transaction)?.let { byTx[it.id.txIdString()] }
                if (method.name == "getMemos" && mixed != null) {
                    mixed
                } else {
                    try {
                        method.invoke(inner, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
        PaymentHarness.setField(vm, "transactionRepository", wrapped)
    }

    /** One more real pass over the fixture's list, and what it lists. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun HandshakePassFixture.rows(): List<Conversation> {
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        val txs =
            (
                HandshakePassFixture::class.java
                    .getDeclaredField("txs")
                    .apply { isAccessible = true }
                    .get(this) as List<Transaction>
            ).sortedBy { it.timestamp ?: Instant.MIN }
        val listed =
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                try {
                    val pending = PaymentHarness.pendingMessages(vm)
                    method.invoke(vm, txs, HandshakePassFixture.SELF, emptySet<String>(), pending, emptySet<String>(), true, continuation)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as List<Conversation>
        idle()
        return listed
    }
}
