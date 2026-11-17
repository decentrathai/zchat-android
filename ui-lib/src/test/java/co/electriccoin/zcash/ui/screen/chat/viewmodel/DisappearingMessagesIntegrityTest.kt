package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.crypto.DecryptedTextCacheKey
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.filesharing.FileDownloadCache
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * R2-5 (c) — disappearing messages (B17 / #187 / M-CHT-02 / M-CHT-31), through the real chat-history conversion.
 *
 * The timer control (ZEXP) changes how long a conversation keeps its messages, so only the contact may change it:
 *  - a plaintext ZEXP memo (anyone can pay to send one) and a ZEXP from an unknown sender change nothing;
 *  - an encrypted ZEXP from a known contact applies, exactly as the contact's app sends it (captured from the
 *    real setDisappearingTtl send path of a second ViewModel);
 *  - but not while that contact's key change is unaccepted (M-CHT-02): the NOSTR path already refuses it, the
 *    on-chain path must too.
 *
 * Expiry is one-way: after expiry, turning the timer off does not bring the text back, the stored text and the
 * expiry marker are removed, and a photo is erased. A reply quoting an expired message shows "Original message
 * expired", never the quoted text it embeds (M-CHT-31).
 *
 * All addresses, keys and texts are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisappearingMessagesIntegrityTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val CONV = "ZEXPCONV"
        const val TTL_SECONDS = 3_600L
        const val MINED_AT = 3_000_100L
        val CONTACT: String = SyntheticUnifiedAddress.mainnet(5101)
        val ME: String = SyntheticUnifiedAddress.mainnet(5102)
        val STRANGER: String = SyntheticUnifiedAddress.mainnet(5103)
        val ROOT = ByteArray(32) { (it * 11 + 3).toByte() }
        const val EXPIRED_NOTE = "Original message expired"
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

    // ---------------------------------------------------------------- the timer control (ZEXP)

    @Test
    fun `an encrypted timer change from a known contact applies`() =
        runBlocking {
            val memos = contactSendsTimer(TTL_SECONDS)
            val me = Receiver()

            me.scan(listOf(incoming(1, memos)))

            assertEquals("the contact's encrypted timer change was not applied", listOf(TTL_SECONDS), me.ttlChanges(CONTACT))
        }

    @Test
    fun `an encrypted timer change from a contact whose key change is unaccepted changes nothing`() =
        runBlocking {
            val memos = contactSendsTimer(TTL_SECONDS)
            val me = Receiver(keyChangeUnaccepted = true)

            me.scan(listOf(incoming(1, memos)))

            assertEquals("a key-changed contact changed the timer (M-CHT-02)", emptyList<Long>(), me.ttlChanges(CONTACT))
        }

    @Test
    fun `the contact's timer change is sent in a form the contact's conversation can be found from`() =
        runBlocking {
            val memos = contactSendsTimer(TTL_SECONDS)
            val parsed = ZMSGProtocol.parseMemo(memos.joinToString("\n").trim(), NoOpAddressCache.proxy)

            assertEquals("a bare encrypted memo names no conversation, so no receiver can decrypt it", CONV, parsed.conversationId)
        }

    @Test
    fun `an attributable encrypted timer change from a contact whose key change is unaccepted changes nothing`() =
        runBlocking {
            val contact = E2EMessageProcessor(ROOT, CONV, isLower = true, store = InMemoryRatchetStateStore())
            val control = ZMSGProtocol.createDisappearSetting(TTL_SECONDS, System.currentTimeMillis(), CONTACT)
            val memos = ZMSGProtocol.createChunkedV4ReplyMessages(CONV, CONTACT, contact.encryptOutgoing(control))
            val me = Receiver(keyChangeUnaccepted = true)

            me.scan(listOf(incoming(1, memos)))

            assertEquals("a key-changed contact changed the timer (M-CHT-02)", emptyList<Long>(), me.ttlChanges(CONTACT))
        }

    @Test
    fun `control - an attributable encrypted timer change from the contact applies`() =
        runBlocking {
            val contact = E2EMessageProcessor(ROOT, CONV, isLower = true, store = InMemoryRatchetStateStore())
            val control = ZMSGProtocol.createDisappearSetting(TTL_SECONDS, System.currentTimeMillis(), CONTACT)
            val memos = ZMSGProtocol.createChunkedV4ReplyMessages(CONV, CONTACT, contact.encryptOutgoing(control))
            val me = Receiver()

            me.scan(listOf(incoming(1, memos)))

            assertEquals(listOf(TTL_SECONDS), me.ttlChanges(CONTACT))
        }

    @Test
    fun `a plaintext timer change changes nothing, also when it names the contact`() =
        runBlocking {
            val plaintext = ZMSGProtocol.createDisappearSetting(TTL_SECONDS, System.currentTimeMillis(), CONTACT)
            val me = Receiver()

            me.scan(
                listOf(
                    incoming(1, ZMSGProtocol.createChunkedV4ReplyMessages(CONV, CONTACT, plaintext)),
                    incoming(2, listOf(plaintext)),
                ),
            )

            assertEquals(emptyList<String>(), me.allTtlChanges())
        }

    @Test
    fun `a timer change from an unknown sender changes nothing`() =
        runBlocking {
            val strangerConv = "STRANGE1"
            val strangerSender = E2EMessageProcessor(ByteArray(32) { 9 }, strangerConv, isLower = true, store = InMemoryRatchetStateStore())
            val control = ZMSGProtocol.createDisappearSetting(TTL_SECONDS, System.currentTimeMillis(), STRANGER)
            val encrypted = strangerSender.encryptOutgoing(control)
            val me = Receiver()

            me.scan(
                listOf(
                    incoming(1, ZMSGProtocol.createChunkedV4ReplyMessages(strangerConv, STRANGER, encrypted)),
                    incoming(2, ZMSGProtocol.createChunkedV4ReplyMessages(strangerConv, STRANGER, control)),
                ),
            )

            assertEquals(emptyList<String>(), me.allTtlChanges())
        }

    // ---------------------------------------------------------------- expiry is one-way

    @Test
    fun `after expiry turning the timer off does not bring the text back`() =
        runBlocking {
            val me = Receiver(ttl = ZchatPreferences.DisappearingTtl(60L, oneDayAgo()))
            val txs = listOf(outgoingText(1, "synthetic secret one", hoursAgo(2)), outgoingText(2, "synthetic secret two", hoursAgo(2)))

            val first = me.scan(txs)
            assertEquals("fixture: both messages expired", emptyList<String>(), rows(first).map { it.text })

            me.ttl = null // the user turns the timer off
            val second = me.scan(txs)

            assertEquals("expired text came back when the timer was turned off", emptyList<String>(), rows(second).map { it.text })
        }

    @Test
    fun `after expiry the stored text and the expiry marker are removed`() =
        runBlocking {
            val me = Receiver(ttl = ZchatPreferences.DisappearingTtl(60L, oneDayAgo()))
            val contact = E2EMessageProcessor(ROOT, CONV, isLower = true, store = InMemoryRatchetStateStore())
            val wire = contact.encryptOutgoing("synthetic encrypted secret")
            val tx = incoming(1, ZMSGProtocol.createChunkedV4ReplyMessages(CONV, CONTACT, wire), at = hoursAgo(2))
            val key = DecryptedTextCacheKey.of(CONV, wire)
            me.storedText[key] = "synthetic encrypted secret" // decrypted on an earlier pass, before it expired

            me.scan(listOf(tx))

            assertTrue("the expired message was not tombstoned", txId(1) in me.hidden)
            assertTrue("the expiry marker of the expired message stays", txId(1) in me.clearedAnchors)
            assertFalse("the decrypted text of an expired message stays on the device", me.storedText.containsKey(key))
            assertFalse("the decrypted text of an expired message stays in memory", key in me.memoryTextCache())
        }

    @Test
    fun `after expiry the photo is erased`() =
        runBlocking {
            val cache = FileDownloadCache(tempDir.newFolder("files"))
            val hash = "0123456789abcdef0123456789abcdef"
            cache.put(hash, ByteArray(64) { 1 })
            val me = Receiver(ttl = ZchatPreferences.DisappearingTtl(60L, oneDayAgo()), fileCache = cache)
            val photo = ZFILEMessage(hash, ZFILEType.JPEG, 64, "https://blossom.primal.net/synthetic", "synthetic-key", "")

            me.scan(listOf(outgoingText(1, photo.serialize(), hoursAgo(2))))
            awaitIdle(me.vm)
            HandshakeHarness.awaitTrue { cache.get(hash) == null }

            assertTrue(hash in me.viewedFiles)
        }

    @Test
    fun `a reply quoting an expired message shows that the original expired, not its text`() =
        runBlocking {
            val me = Receiver(ttl = ZchatPreferences.DisappearingTtl(TTL_SECONDS, oneDayAgo()))
            val quoted = outgoingText(1, "synthetic quoted secret", hoursAgo(2))
            val reply = outgoingText(2, me.tagReply(txId(1), "synthetic reply", "synthetic quoted secret"), minutesAgo(5))

            val first = me.scan(listOf(quoted, reply))
            me.ttl = null
            val second = me.scan(listOf(quoted, reply))

            for ((pass, conversations) in listOf("with the timer on" to first, "after turning it off" to second)) {
                val row = rows(conversations).single { it.id == txId(2) }
                assertEquals("fixture: the reply links the quoted message ($pass)", txId(1), row.replyToId)
                assertEquals("the reply shows the expired quote ($pass)", EXPIRED_NOTE, row.replyToPreview)
            }
        }

    // ---------------------------------------------------------------- fixture

    /** The contact's app sets the timer: the memo(s) its real setDisappearingTtl sends on chain. */
    private suspend fun contactSendsTimer(ttlSeconds: Long): List<String> {
        val seam = PaymentSeam(ME)
        val vm = paymentViewModel(PaymentPrefs(ME), seam)
        val prefs =
            ScanPrefs(
                mapOf(
                    "setDisappearingTtl" to { _ -> true },
                    "getConversationMode" to { _ -> co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT },
                    "getOrCreateConversationId" to { _ -> CONV to false },
                    // R2-6 (c): the per-message call (an existing conversation whose INIT went out).
                    "getOrCreateConversationIdForMessage" to { _ -> CONV to false },
                    "ownConversationId" to { _ -> CONV },
                    "ownConversationIdForStart" to { _ -> CONV },
                    "getConversationId" to { _ -> CONV },
                    // Keyed contact, no key-recovery gate in play (as PaymentPrefs has it).
                    "getE2EOurPublicKey" to { _ -> "synthetic-our-public-key" },
                    "hasAcknowledgedMessageCost" to { _ -> true },
                ),
            )
        PaymentHarness.setField(vm, "zchatPreferences", prefs.proxy)
        PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(CONTACT))
        PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        processors(vm)["$ME:$CONV"] = E2EMessageProcessor(ROOT, CONV, isLower = true, store = InMemoryRatchetStateStore())

        vm.setDisappearingTtl(ME, ttlSeconds, costConfirmed = true)
        awaitIdle(vm)

        val uri = seam.proposalUris.single()
        return Regex("[?&]memo(?:\\.\\d+)?=([^&]+)")
            .findAll(uri)
            .map {
                String(
                    java.util.Base64
                        .getUrlDecoder()
                        .decode(it.groupValues[1]),
                    Charsets.UTF_8
                )
            }.toList()
            .also { assertTrue("fixture: the contact's app sent the timer change", it.isNotEmpty()) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun processors(vm: ChatViewModel): MutableMap<String, Any> =
        ChatViewModel::class.java
            .getDeclaredField("messageProcessors")
            .apply { isAccessible = true }
            .get(vm) as MutableMap<String, Any>

    /** This device: [ME], with [CONTACT] a known, keyed contact on [CONV]. */
    private inner class Receiver(
        private val keyChangeUnaccepted: Boolean = false,
        var ttl: ZchatPreferences.DisappearingTtl? = null,
        private val fileCache: FileDownloadCache? = null,
    ) {
        val hidden: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val clearedAnchors: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val viewedFiles: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        /** The durable record of which messages expired (kept apart from ids the user hid, which can be unhidden). */
        val expired: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val storedText = ConcurrentHashMap<String, String>()
        private val ttlCalls = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        lateinit var vm: ChatViewModel

        private val prefs =
            ScanPrefs(
                mapOf(
                    "getPeerByConversationId" to { a -> if (a[0] == CONV) CONTACT else null },
                    "getConversationId" to { a -> if (a[0] == CONTACT) CONV else null },
                    "getAllConversationMappings" to { _ -> mapOf(CONV to CONTACT) },
                    "isE2EKeyChanged" to { a -> keyChangeUnaccepted && a[0] == CONTACT },
                    "setDisappearingTtl" to { a ->
                        ttlCalls += (a[0] as String) to (a[1] as Long)
                        true
                    },
                    "getDisappearingTtl" to { _ -> ttl },
                    "getDisplayName" to { _ -> "Synthetic Contact" },
                    "getDecryptedText" to { a -> storedText[a[0] as String] },
                    "putDecryptedText" to { a -> storedText[a[0] as String] = a[1] as String },
                    "removeDecryptedText" to { a -> storedText.remove(a[0] as String) },
                    "hideMessages" to { a ->
                        @Suppress("UNCHECKED_CAST")
                        hidden += a[0] as Set<String>
                    },
                    "clearMessageExpiryAnchor" to { a -> clearedAnchors += a[0] as String },
                    "markMessagesExpired" to { a ->
                        @Suppress("UNCHECKED_CAST")
                        expired += a[0] as Set<String>
                    },
                    "getExpiredMessageIds" to { _ -> expired.toSet() },
                    "getOrPutMessageExpiryAnchorMillis" to { a -> a[1] },
                    "getOrPutPendingTxFirstSeenMillis" to { a -> a[1] },
                    "isFileViewed" to { a -> a[0] in viewedFiles },
                    "markFileViewed" to { a -> viewedFiles += a[0] as String },
                    "getConversationMode" to { _ -> co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT },
                ),
            )

        fun ttlChanges(peer: String): List<Long> = ttlCalls.filter { it.first == peer }.map { it.second }

        fun allTtlChanges(): List<String> = ttlCalls.map { "${it.first.take(8)}…=${it.second}" }

        fun memoryTextCache(): Map<String, String> {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel::class.java
                .getDeclaredField(
                    "decryptedTextCache"
                ).apply { isAccessible = true }
                .get(vm) as Map<String, String>
        }

        suspend fun tagReply(
            replyToId: String,
            body: String,
            preview: String,
        ): String {
            ensureVm()
            return ChatViewModel::class.java
                .getDeclaredMethod("tagReply", String::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(vm, replyToId, body, preview) as String
        }

        private suspend fun ensureVm() {
            if (::vm.isInitialized) return
            vm = paymentViewModel(PaymentPrefs(CONTACT), PaymentSeam(CONTACT))
            PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(ME))
            PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            PaymentHarness.setField(vm, "decryptFailedPeers", ConcurrentHashMap.newKeySet<String>())
            PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
            PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
            fileCache?.let { PaymentHarness.setField(vm, "_fileCache", it) }
            processors(vm)["$CONTACT:$CONV"] = E2EMessageProcessor(ROOT, CONV, isLower = false, store = InMemoryRatchetStateStore())
        }

        suspend fun scan(txs: List<ScanTx>): List<Conversation> {
            ensureVm()
            return ScanPassHarness.scan(
                vm = vm,
                prefs = prefs.proxy,
                contactBook = ScanPassHarness.noContacts(),
                repository = repository(txs),
                synchronizer = synchronizer(),
                transactions = txs.map { it.tx },
                userAddress = ME,
                hiddenMsgIds = hidden.toSet(),
            )
        }
    }

    private class ScanTx(
        val tx: co.electriccoin.zcash.ui.common.repository.Transaction,
        val memos: List<String>,
    )

    private fun txId(n: Int): String = TransactionId.new("%064x".format(n)).txIdString()

    private fun incoming(
        n: Int,
        memos: List<String>,
        at: Instant = minutesAgo(1),
    ): ScanTx {
        val id = TransactionId.new("%064x".format(n))
        return ScanTx(
            ReceiveTransaction.Success(
                id = id,
                amount = Zatoshi(1_000),
                timestamp = at,
                memoCount = memos.size,
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, MINED_AT + n, at, sent = false, amount = 1_000),
                recipient = null,
            ),
            memos,
        )
    }

    private fun outgoingText(
        n: Int,
        text: String,
        at: Instant,
    ): ScanTx {
        val id = TransactionId.new("%064x".format(n))
        return ScanTx(
            SendTransaction.Success(
                id = id,
                amount = Zatoshi(50_000),
                timestamp = at,
                memoCount = 1,
                fee = Zatoshi(10_000),
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, MINED_AT + n, at, sent = true),
                recipient = null,
            ),
            listOf(ZMSGProtocol.createLegacyMessage(ME, text)),
        )
    }

    private fun repository(txs: List<ScanTx>): TransactionRepository {
        val byTxId = txs.associateBy { it.tx.id.txIdString() }
        return Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            fun txOf() = byTxId[(args!![0] as co.electriccoin.zcash.ui.common.repository.Transaction).id.txIdString()]
            when (method.name) {
                "getMemos" -> txOf()?.memos ?: emptyList<String>()
                // Outgoing texts in this test go to the contact.
                "getAllRecipientAddresses" -> listOf(CONTACT)
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "DisappearingRepository"
                else -> error("unexpected TransactionRepository.${method.name}")
            }
        } as TransactionRepository
    }

    private fun synchronizer(): Synchronizer =
        Proxy.newProxyInstance(
            Synchronizer::class.java.classLoader,
            arrayOf(Synchronizer::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getFullyScannedHeight", "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(ScanPassHarness.SCANNED))
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "DisappearingSynchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer

    private fun rows(conversations: List<Conversation>): List<ChatMessage> =
        conversations
            .flatMap {
                it.messages
            }.filterNot { it.isSystemNote }

    private fun oneDayAgo(): Long = System.currentTimeMillis() - 24 * 3_600_000L

    private fun hoursAgo(h: Long): Instant = Instant.now().minusSeconds(h * 3_600)

    private fun minutesAgo(m: Long): Instant = Instant.now().minusSeconds(m * 60)
}

/**
 * A preferences store answering from [answers] (by method name); anything else gets the type's empty value
 * (false, 0, empty collection, null) instead of a crash, and every call is recorded in [calls].
 */
internal class ScanPrefs(
    private val answers: Map<String, (Array<Any?>) -> Any?>,
) {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            val a = args ?: emptyArray()
            when (method.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a.getOrNull(0)
                "toString" -> "ScanPrefs"
                else -> {
                    calls += method.name
                    val answer = answers[method.name]
                    val value = if (answer != null) answer(a) else null
                    value ?: emptyValue(method.returnType)
                }
            }
        } as ZchatPreferences

    private fun emptyValue(type: Class<*>): Any? =
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
}
