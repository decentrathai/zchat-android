package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 8 P3 (PHONE_TRIAGE F1) — a Shielded payment request lands in the "unknown" chat, and Pay there cannot pay.
 *
 * In a Shielded (VAULT) chat the request goes out as the bare memo `ZREQ|<zatoshi>|<hash of the sender>|<reason>`
 * (rawMemo). Its only sender identity is that 16-hex hash. `parseMemo` returns no sender, hash or conversation id
 * for it, and the history pass keeps only the amount and reason of `parsePaymentRequest`, dropping the hash (and the
 * address the hash cache may already know). The router then has nothing to route on and keys the request "unknown".
 * A time-locked message (`ZTL|`) keeps its hash, but routing on a hash alone only tries the conversation-partner list,
 * so a contact that is only in the contact book, or only in this pass's conversations, gets a chat keyed by the hash.
 *
 * Pay in such a chat hands the chat key ("unknown", or the hash) to fulfillPaymentRequest as the destination. Nothing
 * can be sent (the send chokepoint refuses a non-address), but the user gets a generic refusal and the button stays.
 *
 * Wanted:
 * - a ZREQ or ZTL whose sender hash names exactly one known contact (the hash cache, this pass's conversations, or the
 *   contact book) lands in that contact's chat, and nothing is keyed "unknown" or by the hash;
 * - it is still marked not verified (the memo is plaintext anyone can send), and Pay pays the chat's own peer;
 * - fulfillPaymentRequest refuses a destination that is not an address before it claims the request or builds
 *   anything, with its own notice ([CANNOT_PAY]).
 *
 * A two-contact hash collision cannot be built with the real 64-bit hash, so its control (never guess) is a pure
 * function test of the matching rule, added with the fix. Runs the real convertToConversations ([ScanPassHarness]).
 * All addresses, amounts and texts are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldedPaymentRequestRoutesToItsContactTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        val PEER: String = SyntheticUnifiedAddress.mainnet(5301)
        val OTHER: String = SyntheticUnifiedAddress.mainnet(5302)
        val ME: String = SyntheticUnifiedAddress.mainnet(5303)
        val STRANGER: String = SyntheticUnifiedAddress.mainnet(5304)
        const val REQUESTED = 10_000L
        const val REASON = "synthetic P3 request"
        const val LOCKED_TEXT = "synthetic P3 locked note"
        const val MINED_AT = 3_000_100L

        /** The notice for a Pay whose chat has no real address (ChatSendCopy.PAYMENT_SENDER_UNKNOWN, EN resource). */
        const val CANNOT_PAY = "Can't pay: the sender's address is unknown."
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

    // ── (1) the hash is cached ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a shielded request from an existing chat whose hash is cached lands in that chat`() =
        runBlocking {
            val cache = MapAddressCache().apply { cacheAddress(ZMSGProtocol.generateAddressHash(PEER), PEER) }
            val conversations =
                scan(
                    listOf(outgoing(1, PEER, "synthetic hello"), incoming(2, request(PEER))),
                    contacts = contacts(),
                    cache = cache,
                )

            assertRequestLandsIn(PEER, conversations)
        }

    // ── (2) the cache is empty ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a shielded request from a contact only in the contact book lands in that contact's chat`() =
        runBlocking {
            val conversations = scan(listOf(incoming(1, request(PEER))), contacts = contacts(PEER, OTHER))

            assertRequestLandsIn(PEER, conversations)
        }

    @Test
    fun `a shielded request from a contact only in this pass's chats lands in that chat`() =
        runBlocking {
            val conversations =
                scan(listOf(outgoing(1, PEER, "synthetic hello"), incoming(2, request(PEER))), contacts = contacts())

            assertRequestLandsIn(PEER, conversations)
        }

    @Test
    fun `a request naming another contact lands in that contact's chat, never in the first one`() =
        runBlocking {
            val conversations =
                scan(
                    listOf(outgoing(1, PEER, "synthetic hello"), incoming(2, request(OTHER))),
                    contacts = contacts(PEER, OTHER),
                )

            assertRequestLandsIn(OTHER, conversations)
            assertTrue(
                "the request named OTHER, but PEER's chat shows it",
                conversations.none { it.peerAddress == PEER && it.messages.any { m -> m.paymentRequest != null } },
            )
        }

    // ── (2b) the same for a time-locked message ──────────────────────────────────────────────────────────

    @Test
    fun `a time-locked message from a contact only in the contact book lands in that contact's chat`() =
        runBlocking {
            val conversations = scan(listOf(incoming(1, timeLock(PEER))), contacts = contacts(PEER, OTHER))

            assertLockedLandsIn(PEER, conversations)
        }

    @Test
    fun `a time-locked message from a contact only in this pass's chats lands in that chat`() =
        runBlocking {
            val conversations =
                scan(listOf(outgoing(1, PEER, "synthetic hello"), incoming(2, timeLock(PEER))), contacts = contacts())

            assertLockedLandsIn(PEER, conversations)
        }

    // ── a stranger (control), and one contact found twice ──────────────────────────────────────────────

    @Test
    fun `control - a request whose hash names no known contact is not put in any contact's chat`() =
        runBlocking {
            val conversations =
                scan(
                    listOf(outgoing(1, PEER, "synthetic hello"), incoming(2, request(STRANGER))),
                    contacts = contacts(PEER, OTHER),
                )

            val holder = conversations.single { c -> c.messages.any { it.paymentRequest != null } }
            assertFalse(
                "a request from nobody we know was put in a contact's chat: ${holder.peerAddress.take(10)}",
                holder.peerAddress == PEER || holder.peerAddress == OTHER || holder.peerAddress == STRANGER,
            )
            assertFalse(
                "a request from nobody we know sits in a chat whose key is a real address, so Pay would pay it",
                holder.peerAddress.startsWith("u1") && holder.peerAddress.length > 100,
            )
        }

    @Test
    fun `a contact in both the contact book and this pass's chats is one match, not two`() =
        runBlocking {
            val conversations =
                scan(
                    listOf(outgoing(1, PEER, "synthetic hello"), incoming(2, request(PEER))),
                    contacts = contacts(PEER, OTHER),
                )

            assertRequestLandsIn(PEER, conversations)
        }

    // ── (3) Pay never targets a non-address ─────────────────────────────────────────────────────────────

    @Test
    fun `Pay in a chat keyed unknown refuses before it claims or builds anything`() =
        runBlocking {
            assertPayRefused("unknown", "synthetic-p3-request-unknown")
        }

    @Test
    fun `Pay in a chat keyed by a sender hash refuses before it claims or builds anything`() =
        runBlocking {
            assertPayRefused(ZMSGProtocol.generateAddressHash(PEER), "synthetic-p3-request-hash")
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun assertPayRefused(
        chatKey: String,
        requestId: String,
    ) {
        val prefs = PaymentPrefs(chatKey, mode = ConversationMode.VAULT)
        val seam = PaymentSeam(PEER)
        val vm = paymentViewModel(prefs, seam)

        vm.fulfillPaymentRequest(chatKey, REQUESTED, requestId)

        assertTrue(
            "the refused Pay never reported an error",
            PaymentHarness.eventually { PaymentHarness.sendMessageState(vm) is SendMessageState.Error },
        )
        assertEquals(
            "Pay towards '${chatKey.take(10)}' must say the sender's address is unknown",
            SendMessageState.Error(CANNOT_PAY),
            PaymentHarness.sendMessageState(vm),
        )
        assertTrue("a proposal was built towards a non-address: ${seam.proposalUris.size}", seam.proposalUris.isEmpty())
        assertEquals("a payment was submitted towards a non-address", 0, seam.submits.get())
        assertTrue("the request was marked paid", requestId !in prefs.paid)
        assertTrue("a fulfil start was recorded", prefs.started.isEmpty())
        assertFalse("the request is still claimed as being paid", requestId in fulfilInFlight())
    }

    private fun assertRequestLandsIn(
        peer: String,
        conversations: List<Conversation>,
    ) {
        val hashKey = ZMSGProtocol.generateAddressHash(peer)
        assertTrue(
            "the request was keyed \"unknown\"",
            conversations.none { it.peerAddress == "unknown" },
        )
        assertTrue(
            "the request was keyed by the sender hash",
            conversations.none { it.peerAddress == hashKey },
        )
        val chat = conversations.firstOrNull { it.peerAddress == peer }
        assertNotNull("the request did not reach the contact's chat", chat)
        val request = chat!!.messages.singleOrNull { it.paymentRequest != null }
        assertNotNull("the contact's chat has no payment request", request)
        assertEquals(REQUESTED, request!!.paymentRequest!!.amountZatoshi)
        assertEquals(REASON, request.paymentRequest.reason)
        assertFalse("a plaintext request must stay marked not verified", request.authenticated)
    }

    private fun assertLockedLandsIn(
        peer: String,
        conversations: List<Conversation>,
    ) {
        val hashKey = ZMSGProtocol.generateAddressHash(peer)
        assertTrue("the locked message was keyed by the sender hash", conversations.none { it.peerAddress == hashKey })
        assertTrue("the locked message was keyed \"unknown\"", conversations.none { it.peerAddress == "unknown" })
        val chat = conversations.firstOrNull { it.peerAddress == peer }
        assertNotNull("the locked message did not reach the contact's chat", chat)
        val locked: ChatMessage? = chat!!.messages.singleOrNull { it.timeLock != null }
        assertNotNull("the contact's chat has no time-locked message", locked)
        assertFalse("a plaintext time-locked message must stay marked not verified", locked!!.authenticated)
    }

    private fun request(sender: String): String = ZMSGProtocol.createPaymentRequest(REQUESTED, sender, REASON)

    private fun timeLock(sender: String): String =
        ZMSGProtocol.createScheduledMessage(LOCKED_TEXT, sender, Instant.now().minusSeconds(3_600).epochSecond)

    private suspend fun scan(
        txs: List<ScanTx>,
        contacts: ContactBook,
        cache: AddressCache = MapAddressCache(),
    ): List<Conversation> {
        val vm = paymentViewModel(PaymentPrefs(PEER), PaymentSeam(PEER))
        PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(ME))
        PaymentHarness.setField(vm, "addressCache", cache)
        PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        PaymentHarness.setField(vm, "decryptFailedPeers", ConcurrentHashMap.newKeySet<String>())
        PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
        PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
        // The time-lock branch reads which locked messages a payment has unlocked (a field initialiser).
        PaymentHarness.setField(vm, "unlockedMessages", MutableStateFlow<Map<String, String>>(emptyMap()))
        val prefs =
            ScanPrefs(
                mapOf(
                    "getOrPutPendingTxFirstSeenMillis" to { a -> a[1] },
                    "getOrPutMessageExpiryAnchorMillis" to { a -> a[1] },
                    "getConversationMode" to { _ -> ConversationMode.VAULT },
                ),
            )
        return ScanPassHarness.scan(
            vm = vm,
            prefs = prefs.proxy,
            contactBook = contacts,
            repository = repository(txs),
            synchronizer = synchronizer(),
            transactions = txs.map { it.tx },
            userAddress = ME,
        )
    }

    private class ScanTx(
        val tx: Transaction,
        val memo: String,
        val recipient: String?,
    )

    private fun incoming(
        n: Int,
        memo: String,
    ): ScanTx {
        val id = TransactionId.new("%064x".format(n))
        val at = Instant.now().minusSeconds(600L - n)
        return ScanTx(
            ReceiveTransaction.Success(
                id = id,
                amount = Zatoshi(1_000),
                timestamp = at,
                memoCount = 1,
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, MINED_AT + n, at, sent = false, amount = 1_000),
                recipient = null,
            ),
            memo,
            recipient = null,
        )
    }

    private fun outgoing(
        n: Int,
        to: String,
        memo: String,
    ): ScanTx {
        val id = TransactionId.new("%064x".format(n))
        val at = Instant.now().minusSeconds(600L - n)
        return ScanTx(
            SendTransaction.Success(
                id = id,
                amount = Zatoshi(1_000),
                timestamp = at,
                memoCount = 1,
                fee = Zatoshi(10_000),
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, MINED_AT + n, at, sent = true, amount = 1_000),
                recipient = null,
            ),
            memo,
            recipient = to,
        )
    }

    private fun repository(txs: List<ScanTx>): TransactionRepository {
        val byTxId = txs.associateBy { it.tx.id.txIdString() }
        return Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            fun txOf() = byTxId[(args!![0] as Transaction).id.txIdString()]
            when (method.name) {
                "getMemos" -> txOf()?.let { listOf(it.memo) } ?: emptyList<String>()
                "getAllRecipientAddresses" -> listOfNotNull(txOf()?.recipient)
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "ShieldedRequestRepository"
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
                "toString" -> "ShieldedRequestSynchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer

    /** A contact book holding [addresses] (and nothing else). */
    private fun contacts(vararg addresses: String): ContactBook {
        val book = addresses.map { Contact(address = it, name = "Synthetic ${it.takeLast(4)}") }
        return Proxy.newProxyInstance(ContactBook::class.java.classLoader, arrayOf(ContactBook::class.java)) { p, method, args ->
            when (method.name) {
                "getContact" -> book.firstOrNull { it.address == args!![0] }
                "hasContact" -> book.any { it.address == args!![0] }
                "getAllContacts" -> book
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "ShieldedRequestContacts"
                else -> error("unexpected ContactBook.${method.name}")
            }
        } as ContactBook
    }

    @Suppress("UNCHECKED_CAST")
    private fun fulfilInFlight(): Set<String> {
        val companion = ChatViewModel::class.java.declaredFields.first { it.name == "fulfilInFlight" }
        companion.isAccessible = true
        return companion.get(null) as Set<String>
    }

    /**
     * An in-memory hash cache with the app's partner lookup (AddressCacheImpl.findConversationPartnerByHash): a
     * cached hash whose address is a partner, or a partner whose legacy (6-byte) hash starts the 8-byte hash.
     */
    private class MapAddressCache : AddressCache {
        private val byHash = ConcurrentHashMap<String, String>()
        private val partners = ConcurrentHashMap.newKeySet<String>()

        override fun cacheAddress(
            hash: String,
            address: String,
        ) {
            byHash.putIfAbsent(hash, address)
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

        override fun findConversationPartnerByHash(hash: String): String? {
            val direct = byHash[hash]
            if (direct != null && direct in partners) return direct
            if (hash.length == 16) {
                return partners.firstOrNull { hash.startsWith(ZMSGProtocol.generateLegacyAddressHash(it)) }
            }
            return null
        }
    }
}
