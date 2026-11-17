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
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageAuthentication
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 10b R10B-2 (F-R9C-3) — the history pass marks a chat's rows mined before its key exchange completed.
 *
 * Runs the real convertToConversations ([ScanPassHarness]) over a Shielded chat with PEER: PEER's plaintext first
 * message, our own KEXACK a few blocks later (mined, so the session exists from its height on), then (ZC-1910) PEER's
 * first ratcheted message and a plaintext reply from PEER above it. Wanted: the first message is pre-session (no "Sender not verified" chip), the later plaintext
 * reply is not (it keeps the chip), and a chat whose earliest mined KEXACK is the contact's, signed with a key we do not
 * hold for them, keeps every row as before. Written with the fix as the pass-level check of MessageAuthenticationChipTest.
 *
 * All addresses, keys, heights and texts are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreSessionMessagesMarkedByScanTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        val PEER: String = SyntheticUnifiedAddress.mainnet(5401)
        val ME: String = SyntheticUnifiedAddress.mainnet(5403)
        const val CONV = "R10B2CNV"
        const val MINED_AT = 3_495_570L
        const val FIRST_TEXT = "synthetic R10B-2 first message"
        const val LATER_TEXT = "synthetic R10B-2 later plaintext"

        /** ZC-1943: blocks after our KEXACK, beyond any expiry window plus scan margin (40 or 120 blocks plus a margin). */
        const val FAR_BEYOND_THE_WINDOW = 500

        /** ZC-1910: a ratcheted (E2E1) body; it fails to open here and still counts as the contact's first E2E1 message. */
        val RATCHETED_BODY: String =
            co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat.serialize(
                co.electriccoin.zcash.ui.screen.chat.crypto.ratchet
                    .Ciphertext(direction = 0x00, counter = 3L, bytes = ByteArray(48) { it.toByte() }),
            )
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
    fun `our own mined KEXACK makes the earlier plaintext rows pre-session and leaves the later ones`() =
        runBlocking {
            val ours = E2EEncryption.generateKeyPair()
            val kexAck =
                ZMSGProtocol.createV4KEXAckMessage(
                    CONV,
                    ME,
                    E2EEncryption.createKEXAckPayload(ME, ours.publicKey, ours.privateKey)
                )
            val rows =
                peerRows(
                    scan(
                        listOf(
                            incoming(6, ZMSGProtocol.createV4InitMessage(CONV, PEER, FIRST_TEXT)),
                            outgoing(11, kexAck),
                            // ZC-1910: PEER's first ratcheted (E2E1) message; a plaintext one mined after it is the downgrade.
                            incoming(13, ZMSGProtocol.createV4ReplyMessage(CONV, PEER, RATCHETED_BODY)),
                            incoming(15, ZMSGProtocol.createV4ReplyMessage(CONV, PEER, LATER_TEXT)),
                        ),
                        peerKey = null,
                    ),
                )

            val first = rows.single { it.text.contains(FIRST_TEXT) }
            val later = rows.single { it.text.contains(LATER_TEXT) }
            assertFalse("fixture: the first message is plaintext", first.authenticated)
            assertFalse("fixture: the later message is plaintext", later.authenticated)
            assertTrue("the first message, mined before our KEXACK, is not pre-session", first.preSession)
            assertFalse("a plaintext message mined after the session existed lost its chip", later.preSession)
            assertNull(chip(first))
            assertEquals("SENDER", chip(later)?.name)
        }

    /**
     * ZC-1943 (GPT and Fable reviews of 4efd9a54f, 16 Nov): a plaintext "from PEER" mined long after our KEXACK, while PEER
     * has sent no ratcheted message, cannot be a message PEER built before it had our KEXACK (that one is mined within the
     * transaction expiry window after the session). It keeps "Sender not verified": a forged memo is never unmarked for good.
     */
    @Test
    fun `a plaintext mined far beyond the session with no ratcheted message before it keeps the chip`() =
        runBlocking {
            val ours = E2EEncryption.generateKeyPair()
            val kexAck =
                ZMSGProtocol.createV4KEXAckMessage(
                    CONV,
                    ME,
                    E2EEncryption.createKEXAckPayload(ME, ours.publicKey, ours.privateKey)
                )
            val rows =
                peerRows(
                    scan(
                        listOf(
                            incoming(6, ZMSGProtocol.createV4InitMessage(CONV, PEER, FIRST_TEXT)),
                            outgoing(11, kexAck),
                            incoming(11 + FAR_BEYOND_THE_WINDOW, ZMSGProtocol.createV4ReplyMessage(CONV, PEER, LATER_TEXT)),
                        ),
                        peerKey = null,
                    ),
                )

            val later = rows.single { it.text.contains(LATER_TEXT) }
            assertFalse("fixture: the later message is plaintext", later.authenticated)
            assertEquals("a plaintext far beyond the session lost its chip", "SENDER", chip(later)?.name)
        }

    /**
     * ZC-1943 reopened (Fable re-review of f58e91ef2): an E2E1-prefixed memo that the ratchet cannot open (anyone can mine
     * one) mined after a far plaintext must not unmark it: the window cap holds before the first ratcheted row too.
     */
    @Test
    fun `a far plaintext keeps the chip although an undecryptable E2E1 memo follows it`() =
        runBlocking {
            val ours = E2EEncryption.generateKeyPair()
            val kexAck =
                ZMSGProtocol.createV4KEXAckMessage(
                    CONV,
                    ME,
                    E2EEncryption.createKEXAckPayload(ME, ours.publicKey, ours.privateKey)
                )
            val rows =
                peerRows(
                    scan(
                        listOf(
                            incoming(6, ZMSGProtocol.createV4InitMessage(CONV, PEER, FIRST_TEXT)),
                            outgoing(11, kexAck),
                            incoming(11 + FAR_BEYOND_THE_WINDOW, ZMSGProtocol.createV4ReplyMessage(CONV, PEER, LATER_TEXT)),
                            incoming(11 + FAR_BEYOND_THE_WINDOW + 50, ZMSGProtocol.createV4ReplyMessage(CONV, PEER, RATCHETED_BODY)),
                        ),
                        peerKey = null,
                    ),
                )

            val later = rows.single { it.text.contains(LATER_TEXT) }
            assertEquals("a far plaintext lost its chip to a later E2E1-prefixed memo", "SENDER", chip(later)?.name)
        }

    @Test
    fun `an earliest KEXACK signed with a key we do not hold marks nothing`() =
        runBlocking {
            val stranger = E2EEncryption.generateKeyPair()
            val held = E2EEncryption.generateKeyPair()
            val forged =
                ZMSGProtocol.createV4KEXAckMessage(
                    CONV,
                    PEER,
                    E2EEncryption.createKEXAckPayload(PEER, stranger.publicKey, stranger.privateKey)
                )
            val rows =
                peerRows(
                    scan(
                        listOf(
                            incoming(6, ZMSGProtocol.createV4InitMessage(CONV, PEER, FIRST_TEXT)),
                            incoming(11, forged),
                            incoming(15, ZMSGProtocol.createV4ReplyMessage(CONV, PEER, LATER_TEXT)),
                        ),
                        peerKey = held.publicKey,
                    ),
                )

            assertTrue("fixture: no row reached PEER's chat", rows.isNotEmpty())
            assertTrue("a KEXACK signed with a key we do not hold marked a row pre-session", rows.none { it.preSession })
        }

    @Test
    fun `with no mined KEXACK nothing is pre-session`() =
        runBlocking {
            val rows =
                peerRows(
                    scan(
                        listOf(
                            incoming(6, ZMSGProtocol.createV4InitMessage(CONV, PEER, FIRST_TEXT)),
                            incoming(15, ZMSGProtocol.createV4ReplyMessage(CONV, PEER, LATER_TEXT)),
                        ),
                        peerKey = null,
                    ),
                )

            assertTrue("fixture: no row reached PEER's chat", rows.isNotEmpty())
            assertTrue(rows.none { it.preSession })
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private fun chip(row: ChatMessage) =
        MessageAuthentication.unverifiedChip(
            isOutgoing = row.isOutgoing,
            authenticated = row.authenticated,
            isE2EReady = true,
            preSession = row.preSession,
            isPaymentRequest = row.paymentRequest != null,
        )

    private fun peerRows(conversations: List<co.electriccoin.zcash.ui.screen.chat.model.Conversation>): List<ChatMessage> {
        val chat = conversations.firstOrNull { it.peerAddress == PEER }
        assertNotNull("PEER's chat is missing: ${conversations.map { it.peerAddress.take(10) }}", chat)
        return chat!!.messages.filter { !it.isOutgoing }
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
            recipient = PEER,
        )
    }

    private suspend fun scan(
        txs: List<ScanTx>,
        peerKey: String?,
    ): List<co.electriccoin.zcash.ui.screen.chat.model.Conversation> {
        val vm = paymentViewModel(PaymentPrefs(PEER), PaymentSeam(PEER))
        PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(ME))
        PaymentHarness.setField(vm, "addressCache", MapCache())
        PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        PaymentHarness.setField(vm, "decryptFailedPeers", ConcurrentHashMap.newKeySet<String>())
        PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
        PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
        PaymentHarness.setField(vm, "unlockedMessages", MutableStateFlow<Map<String, String>>(emptyMap()))
        val prefs =
            ScanPrefs(
                mapOf(
                    "getOrPutPendingTxFirstSeenMillis" to { a -> a[1] },
                    "getOrPutMessageExpiryAnchorMillis" to { a -> a[1] },
                    "getConversationMode" to { _ -> ConversationMode.VAULT },
                    "getPeerByConversationId" to { a -> if (a[0] == CONV) PEER else null },
                    "getConversationId" to { a -> if (a[0] == PEER) CONV else null },
                    "getE2EPeerPublicKey" to { a -> if (a[0] == PEER) peerKey else null },
                ),
            )
        return ScanPassHarness.scan(
            vm = vm,
            prefs = prefs.proxy,
            contactBook = contacts(),
            repository = repository(txs),
            synchronizer = synchronizer(),
            transactions = txs.map { it.tx },
            userAddress = ME,
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
                "toString" -> "PreSessionRepository"
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
                "toString" -> "PreSessionSynchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer

    private fun contacts(): ContactBook {
        val book = listOf(Contact(address = PEER, name = "Synthetic peer"))
        return Proxy.newProxyInstance(ContactBook::class.java.classLoader, arrayOf(ContactBook::class.java)) { p, method, args ->
            when (method.name) {
                "getContact" -> book.firstOrNull { it.address == args!![0] }
                "hasContact" -> book.any { it.address == args!![0] }
                "getAllContacts" -> book
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "PreSessionContacts"
                else -> error("unexpected ContactBook.${method.name}")
            }
        } as ContactBook
    }

    /** An in-memory hash cache (PEER's hash known, PEER a conversation partner). */
    private class MapCache : AddressCache {
        private val byHash = ConcurrentHashMap<String, String>().apply { put(ZMSGProtocol.generateAddressHash(PEER), PEER) }
        private val partners = ConcurrentHashMap.newKeySet<String>().apply { add(PEER) }

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

        override fun findConversationPartnerByHash(hash: String): String? = byHash[hash]?.takeIf { it in partners }
    }
}
