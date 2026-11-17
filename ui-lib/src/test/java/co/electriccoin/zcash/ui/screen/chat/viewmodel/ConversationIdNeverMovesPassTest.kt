package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ConversationIdStoreFixture
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * FC-ID (ZC-892; FIRST-CONTACT-SPEC revision 4a, 2.6, 5.3a, 6.2a: the pass cases) — no inbound memo moves a
 * conversation id, and the own-id record is written from this wallet's own sends at a restore.
 *
 * The real history pass (convertToConversations) runs over transactions the test builds; the conversation-id calls go
 * to the REAL store functions over an in-memory file ([ConversationIdStoreFixture]), every other store call is
 * answered leniently and the contact-key writes are recorded.
 *
 * Today: (c) an answer under the id this phone made for M, signed for X, moves the id to X before the handler runs
 * (convertToConversationsInPass "zchatPreferences.setConversationMapping(kexConvId, kexSender)"); (d) a key exchange,
 * or a plain message naming X, does the same; (g) an own message restores its id with no own-id record; (n) an own
 * mined key exchange restores nothing ("SKIP KEX"). Guards: (g) a contact's id, (o) an own answer echoed under a
 * stranger's id, (p) an id that came in first or a key exchange whose recipient cannot be read never get a record.
 * All addresses, keys, ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationIdNeverMovesPassTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private companion object {
        val ME = SyntheticUnifiedAddress.mainnet(seed = 89201)
        val M = SyntheticUnifiedAddress.mainnet(seed = 89202)
        val X = SyntheticUnifiedAddress.mainnet(seed = 89203)
        val A = SyntheticUnifiedAddress.mainnet(seed = 89204)
        val S = SyntheticUnifiedAddress.mainnet(seed = 89205)
    }

    // ---- (c) the forged proof ------------------------------------------------------------------------------------

    @Test
    fun `(c) an answer under the id made for M that claims X never moves the id and stores nothing at X`() =
        runBlocking {
            val pass = FcIdPass(ME, saved = setOf(X))
            val (idC, _) = pass.ids.store.getOrCreateConversationIdForMessage(M)
            val mKeys = E2EEncryption.generateKeyPair()
            val forged = ZMSGProtocol.createV4KEXAckMessage(idC, X, E2EEncryption.createKEXAckPayload(X, mKeys.publicKey, mKeys.privateKey))
            pass.received("fcid-c-ack", forged, at = 100)

            pass.run()

            assertEquals("a received answer moved the id this phone made for M to the address it claims", M, pass.ids.conv(idC))
            assertTrue("a contact key was stored at X: ${pass.keyWrites}", pass.keyWrites.none { it == X })
            assertNull("X was given an id by the forged answer", pass.ids.peer(X))
        }

    // ---- (d) the same with a key exchange, and with a plain message ------------------------------------------------

    @Test
    fun `(d) a key exchange under the id made for M that claims X never moves the id`() =
        runBlocking {
            val pass = FcIdPass(ME, saved = setOf(X))
            val (idC, _) = pass.ids.store.getOrCreateConversationIdForMessage(M)
            val keys = E2EEncryption.generateKeyPair()
            val kex = ZMSGProtocol.createV4KEXMessage(idC, X, E2EEncryption.createKEXPayload(X, keys.publicKey, keys.privateKey))
            pass.received("fcid-d-kex", kex, at = 100)

            pass.run()

            assertEquals("a received key exchange moved the id this phone made for M", M, pass.ids.conv(idC))
            assertTrue("a contact key was stored at X: ${pass.keyWrites}", pass.keyWrites.none { it == X })
        }

    @Test
    fun `(d) a plain message under the id made for M that names X as its sender never moves the id`() =
        runBlocking {
            val pass = FcIdPass(ME, saved = setOf(X))
            val (idC, _) = pass.ids.store.getOrCreateConversationIdForMessage(M)
            pass.received("fcid-d-msg", ZMSGProtocol.createV4InitMessage(idC, X, "synthetic text naming X"), at = 100)

            pass.run()

            assertEquals("a plain message moved the id this phone made for M to the address it names", M, pass.ids.conv(idC))
        }

    // ---- (g) the own-id record from an own message at a restore -----------------------------------------------------

    @Test
    fun `(g) a restore records the id of this wallet's own first message, and not the id of a contact's`() =
        runBlocking {
            val pass = FcIdPass(ME)
            val c1 = "GGGG1111"
            val c2 = "GGGG2222"
            pass.sent("fcid-g-own-first", ZMSGProtocol.createV4InitMessage(c1, ME, "synthetic own first"), to = A, at = 100)
            pass.received("fcid-g-a-reply", ZMSGProtocol.createV4ReplyMessage(c1, A, "synthetic reply"), at = 200)
            pass.received("fcid-g-s-first", ZMSGProtocol.createV4InitMessage(c2, S, "synthetic contact first"), at = 300)
            pass.sent("fcid-g-own-reply", ZMSGProtocol.createV4ReplyMessage(c2, ME, "synthetic own reply"), to = S, at = 400)

            pass.run()

            assertEquals("the id of this wallet's own first message got no own-id record", A, pass.ids.own(c1))
            assertNull("the id a contact chose was recorded as own", pass.ids.own(c2))
            assertEquals(S, pass.ids.conv(c2))
        }

    // ---- (n) a restore with a lone key exchange ---------------------------------------------------------------------

    @Test
    fun `(n) a restore records the id of this wallet's own mined key exchange when no message used it`() =
        runBlocking {
            val pass = FcIdPass(ME)
            val idC = "NNNN1111"
            val ours = E2EEncryption.generateKeyPair()
            pass.sent(
                "fcid-n-own-kex",
                ZMSGProtocol.createV4KEXMessage(idC, ME, E2EEncryption.createKEXPayload(ME, ours.publicKey, ours.privateKey)),
                to = A,
                at = 100,
            )

            pass.run()

            assertEquals("the own mined key exchange left its id with no own-id record", A, pass.ids.own(idC))
            assertEquals(A, pass.ids.conv(idC))
            assertTrue(
                "conv:, peer: and own: were not written in one commit: ${pass.ids.file.commits}",
                pass.ids.file.commits
                    .any { it.containsAll(listOf("conv:$idC", "own:$idC")) },
            )
        }

    // ---- (o) only an echoed answer ----------------------------------------------------------------------------------

    @Test
    fun `(o) guard - this wallet's own answer under a stranger's id never makes it own, in any order`() =
        runBlocking {
            for (order in listOf("kex-before", "kex-after", "no-kex")) {
                val pass = FcIdPass(ME)
                val c2 = "OOOO2222"
                val ours = E2EEncryption.generateKeyPair()
                val theirs = E2EEncryption.generateKeyPair()
                val ownAck =
                    ZMSGProtocol.createV4KEXAckMessage(c2, ME, E2EEncryption.createKEXAckPayload(ME, ours.publicKey, ours.privateKey))
                val theirKex =
                    ZMSGProtocol.createV4KEXMessage(c2, S, E2EEncryption.createKEXPayload(S, theirs.publicKey, theirs.privateKey))
                if (order == "kex-before") pass.received("fcid-o-kex-$order", theirKex, at = 100)
                pass.sent("fcid-o-ack-$order", ownAck, to = S, at = 200)
                if (order == "kex-after") pass.received("fcid-o-kex-$order", theirKex, at = 300)

                pass.run()

                assertNull("($order) an own answer recorded the stranger's id as own", pass.ids.own(c2))
                if (order == "no-kex") assertNull("($order) an own answer wrote a conv: entry", pass.ids.conv(c2))
            }
        }

    // ---- (p) the safe side ------------------------------------------------------------------------------------------

    @Test
    fun `(p) guard - an id that came in before the own key exchange, or a key exchange with no readable recipient, gets no record`() =
        runBlocking {
            val came = FcIdPass(ME)
            val idC = "PPPP1111"
            val ours = E2EEncryption.generateKeyPair()
            val ownKex = ZMSGProtocol.createV4KEXMessage(idC, ME, E2EEncryption.createKEXPayload(ME, ours.publicKey, ours.privateKey))
            came.received("fcid-p-in", ZMSGProtocol.createV4InitMessage(idC, A, "synthetic first from A"), at = 100)
            came.sent("fcid-p-kex", ownKex, to = A, at = 200)
            came.run()
            assertNull("an id that came in first was recorded as own", came.ids.own(idC))

            val unreadable = FcIdPass(ME)
            unreadable.sent("fcid-p-kex-unreadable", ownKex, to = null, at = 100)
            unreadable.run()
            assertNull("a key exchange with no readable recipient wrote an own-id record", unreadable.ids.own(idC))
        }
}

private const val FCID_MINED = 3_000_100L

/** A synthetic 32-byte txid (hex) made from [label]. */
private fun syntheticTxid(label: String): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(label.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** The store calls the pass harness hands to the real conversation-id functions (present or new with FC-ID). */
private val CONV_ID_CALLS =
    setOf(
        "getConversationId",
        "setConversationId",
        "getOrCreateConversationId",
        "getOrCreateConversationIdForMessage",
        "needsConversationInit",
        "markConversationInitSubmitted",
        "getPeerByConversationId",
        "setConversationMapping",
        "getAllConversationMappings",
        "getAllPeerToConvIdMappings",
        "removeConversationMapping",
        "ownConversationId",
        "isOwnConversationId",
        "ownConversationIdForStart",
        "restoreOwnConversationId",
    )

/**
 * FC-ID test support: one real history pass (convertToConversations) over transactions a test builds, with the
 * conversation-id calls answered by the REAL store functions ([ConversationIdStoreFixture]), the contact-key writes
 * recorded and every other store call answered leniently. [me] is this wallet's address.
 */
internal class FcIdPass(
    private val me: String,
    private val saved: Set<String> = emptySet(),
) {
    val ids = ConversationIdStoreFixture()
    val keyWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val txs = mutableListOf<Transaction>()
    private val memos = ConcurrentHashMap<String, String>()
    private val recipients = ConcurrentHashMap<String, String>()
    private val unreadableRecipients: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun received(
        txid: String,
        memo: String,
        at: Long,
    ) {
        val id = TransactionId.new(syntheticTxid(txid))
        val time = Instant.ofEpochSecond(1_700_000_000L + at)
        memos[id.txIdString()] = memo
        txs +=
            ReceiveTransaction.Success(
                id = id,
                amount = Zatoshi(1_000),
                timestamp = time,
                memoCount = 1,
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, FCID_MINED + at, time, sent = false, amount = 1_000),
                recipient = null,
            )
    }

    fun sent(
        txid: String,
        memo: String,
        to: String?,
        at: Long,
    ) {
        val id = TransactionId.new(syntheticTxid(txid))
        val time = Instant.ofEpochSecond(1_700_000_000L + at)
        memos[id.txIdString()] = memo
        if (to != null) recipients[id.txIdString()] = to else unreadableRecipients += id.txIdString()
        txs +=
            SendTransaction.Success(
                id = id,
                amount = Zatoshi(1_000),
                timestamp = time,
                memoCount = 1,
                fee = Zatoshi(10_000),
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, FCID_MINED + at, time, sent = true, amount = 1_000),
                recipient = null,
            )
    }

    suspend fun run(): List<Conversation> {
        val vm = paymentViewModel(PaymentPrefs(me), PaymentSeam(me))
        PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(me))
        PaymentHarness.setField(vm, "addressCache", FcIdMapCache())
        PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        PaymentHarness.setField(vm, "decryptFailedPeers", ConcurrentHashMap.newKeySet<String>())
        PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
        PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
        PaymentHarness.setField(vm, "unlockedMessages", MutableStateFlow<Map<String, String>>(emptyMap()))
        val conversations =
            ScanPassHarness.scan(
                vm = vm,
                prefs = prefs(),
                contactBook = contacts(),
                repository = repository(),
                synchronizer = synchronizer(),
                transactions = txs.toList(),
                userAddress = me,
            )
        awaitIdle(vm)
        return conversations
    }

    private fun prefs(): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            val a = args ?: emptyArray()
            when (method.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a.getOrNull(0)
                "toString" -> "FcIdPassPrefs"
                in CONV_ID_CALLS ->
                    try {
                        method.invoke(ids.store, *a)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                "isSelfAddress" -> a[0] == me
                "setE2EPeerPublicKey" -> {
                    keyWrites += a[0] as String
                    null
                }
                // ZC-634: the one write of a contact key since part B; recorded as the key write it is.
                "storePeerKey" -> {
                    keyWrites += a[0] as String
                    true
                }
                "getOrPutPendingTxFirstSeenMillis", "getOrPutMessageExpiryAnchorMillis" -> a[1]
                else -> fcIdEmptyValue(method.returnType)
            }
        } as ZchatPreferences

    private fun contacts(): ContactBook =
        Proxy.newProxyInstance(ContactBook::class.java.classLoader, arrayOf(ContactBook::class.java)) { p, method, args ->
            when (method.name) {
                "hasContact" -> args!![0] in saved
                "getContact" -> null
                "getAllContacts" -> emptyList<Any>()
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "FcIdContacts"
                else -> fcIdEmptyValue(method.returnType)
            }
        } as ContactBook

    private fun repository(): TransactionRepository =
        Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getMemos" -> listOfNotNull(memos[(args!![0] as Transaction).id.txIdString()])
                "getAllRecipientAddresses" -> {
                    val txid = (args!![0] as Transaction).id.txIdString()
                    if (txid in unreadableRecipients) error("synthetic: recipients cannot be read")
                    listOfNotNull(recipients[txid])
                }
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "FcIdRepository"
                else -> error("unexpected TransactionRepository.${method.name}")
            }
        } as TransactionRepository

    private fun synchronizer(): Synchronizer =
        Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, method, args ->
            when (method.name) {
                "getFullyScannedHeight", "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(ScanPassHarness.SCANNED))
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "FcIdSynchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer
}

private fun fcIdEmptyValue(type: Class<*>): Any? =
    when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        List::class.java -> emptyList<Any>()
        Set::class.java -> emptySet<Any>()
        Map::class.java -> emptyMap<Any, Any>()
        else -> null
    }

/** An in-memory hash cache. */
private class FcIdMapCache : AddressCache {
    private val byHash = ConcurrentHashMap<String, String>()
    private val partners = ConcurrentHashMap.newKeySet<String>()

    override fun cacheAddress(
        hash: String,
        address: String,
    ) {
        byHash[hash] = address
    }

    override fun cacheAddressValidated(
        hash: String,
        address: String,
    ) {
        byHash[hash] = address
    }

    override fun getAddress(hash: String): String? = byHash[hash]

    override fun hasAddress(hash: String): Boolean = byHash.containsKey(hash)

    override fun getAllCachedAddresses(): Map<String, String> = byHash.toMap()

    override fun addConversationPartner(address: String) {
        partners += address
    }

    override fun getConversationPartners(): Set<String> = partners.toSet()

    override fun isConversationPartner(address: String): Boolean = address in partners

    override fun findConversationPartnerByHash(hash: String): String? = byHash[hash]?.takeIf { it in partners }
}
