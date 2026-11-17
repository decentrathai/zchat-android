package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1480 items 1 and 3 (OT213n B1a; owner findings 2 Nov 2026; lanes/L2_mode_switch.md; FIX_PLAN REVISION 1; S5).
 *
 * The owner's case: HONOR sent a Shielded message to a contact the Fold knew only over the relays (Open / Tunnel), and
 * the Fold filed it in a second chat, "Unknown sender", keyed by the sender hash. At 669cf19e1:
 *  - item 1: the on-chain router's hash-only tier (TIER 2h) looks for the hash only among this pass's on-chain chats and
 *    the contact book. A relay-only contact is in neither (no on-chain rows yet, accepting a request does not add it to
 *    the book), so a v4 REPLY under a conversation id the phone never mapped falls to TIER 3 and is keyed by its hash.
 *    Wanted: the candidates also hold the conversation peers and the peers with a bound relay key, still filtered by
 *    isResolvedToKnownPeer and matched only when exactly one fits (SenderHashMatch.unique);
 *  - item 3: the Open send wraps every message as a v4 INIT under an id from getOrCreateConversationId, which leaves no
 *    "INIT not sent" mark; so the first on-chain message after Open goes as a REPLY carrying only the id and the hash.
 *    Wanted: the first on-chain message after Open / Tunnel is the v4 INIT that carries our address.
 *
 * Item 1 runs the REAL history conversion (ScanPassHarness) over the real preferences store (in-memory files); the
 * contact's REPLY is built with the v2.12.4 builder ([Frozen2124], compat-2124 rule 3: a 2.12.4 or 2.13 sender writes the
 * same v4 REPLY). Item 3 runs the real send, mode switch and send again ([RealModeStorePhone], every call in the real
 * store). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldedMemoFromRelayContactLandsInItsChatTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        val ME: String = SyntheticUnifiedAddress.mainnet(14_801)
        val CONTACT: String = SyntheticUnifiedAddress.mainnet(14_802)
        val STRANGER: String = SyntheticUnifiedAddress.mainnet(14_803)
        const val CONTACT_RELAY_KEY = "8e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7"

        // The id the CONTACT's phone made for its chat with us (never seen by this phone).
        const val THEIR_CONV = "QXT7KM4R"
        const val MINED_AT = 3_000_100L
        const val TEXT = "synthetic shielded message from a relay contact"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
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

    // ── item 1: routing ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a Shielded REPLY from an Open contact this phone has a chat and a relay key for lands in that contact's chat`() =
        runBlocking {
            val store = RealModeStorePhone(CONTACT, ConversationMode.OPEN, relayKey = CONTACT_RELAY_KEY, allReal = true)
            store.real.getOrCreateConversationId(CONTACT) // our own Open INITs went out under our id

            val chats = scan(store, Frozen2124.createV4ReplyMessage(THEIR_CONV, CONTACT, TEXT))

            assertEquals(
                "the contact's Shielded message was filed under its hash in a second, unknown-sender chat (TIER 3)",
                CONTACT,
                chatHolding(chats, TEXT),
            )
        }

    @Test
    fun `a Shielded REPLY from a contact known only by its bound relay key lands in that contact's chat`() =
        runBlocking {
            // An accepted relay request: the contact's relay key is bound and the chat is Open; no conversation id yet.
            val store = RealModeStorePhone(CONTACT, ConversationMode.OPEN, relayKey = CONTACT_RELAY_KEY, allReal = true)

            val chats = scan(store, Frozen2124.createV4ReplyMessage(THEIR_CONV, CONTACT, TEXT))

            assertEquals("a contact with a bound relay key is a routing candidate too", CONTACT, chatHolding(chats, TEXT))
        }

    /** Guard (green before and after): a hash that names no contact this phone knows is never guessed. */
    @Test
    fun `guard - a Shielded REPLY whose hash names nobody this phone knows still goes to its own chat`() =
        runBlocking {
            val store = RealModeStorePhone(CONTACT, ConversationMode.OPEN, relayKey = CONTACT_RELAY_KEY, allReal = true)
            store.real.getOrCreateConversationId(CONTACT)

            val chats = scan(store, Frozen2124.createV4ReplyMessage(THEIR_CONV, STRANGER, TEXT))

            val key = chatHolding(chats, TEXT)
            assertTrue("a stranger's message was filed under a known contact: $key", key != CONTACT)
        }

    // ── item 3: the first on-chain message after Open is the INIT ───────────────────────────────────────────────────

    @Test
    fun `the first Shielded message after an Open chat is the v4 INIT that carries our address`() =
        runBlocking {
            val phone = RealModeStorePhone(CONTACT, ConversationMode.OPEN, relayKey = CONTACT_RELAY_KEY, allReal = true)
            phone.relaysUp()
            val vm = phone.viewModel()

            vm.sendMessage(CONTACT, "synthetic free message")
            awaitIdle(vm)
            val openWire = phone.published.map { it.first }.firstOrNull { !ZMSGProtocol.isModeChange(it) }
            assertNotNull("fixture: the Open message went over the relays", openWire)
            assertTrue("fixture: an Open message is a v4 INIT on the relays", openWire!!.startsWith("ZMSG|v4|"))

            vm.switchConversationMode(CONTACT, ConversationMode.VAULT)
            awaitIdle(vm)
            assertEquals("fixture: the chat is Shielded now", ConversationMode.VAULT, phone.mode())

            vm.sendMessage(CONTACT, "synthetic first shielded message")
            awaitIdle(vm)
            if (HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation) vm.confirmPaidAction()
            awaitIdle(vm)

            val memos = phone.seam.proposalUris.flatMap(::memosOf)
            val message =
                memos
                    .map { ZMSGProtocol.parseMemo(it, NoOpAddressCache.proxy) }
                    .firstOrNull { it.message.contains("synthetic first shielded message") }
            assertNotNull("fixture: the first Shielded message went on chain; memos ${memos.map { it.take(14) }}", message)
            assertEquals(
                "the first on-chain message after Open went as a REPLY with no address, so the contact cannot place it",
                HandshakeHarness.SELF,
                message!!.senderAddress,
            )
        }

    // ---------------------------------------------------------------- fixture

    /** The conversation key the chat holding [text] is filed under. */
    private fun chatHolding(
        chats: List<co.electriccoin.zcash.ui.screen.chat.model.Conversation>,
        text: String,
    ): String? = chats.firstOrNull { c -> c.messages.any { it.text.contains(text) } }?.peerAddress

    private suspend fun scan(
        phone: RealModeStorePhone,
        memo: String,
    ): List<co.electriccoin.zcash.ui.screen.chat.model.Conversation> {
        val vm = paymentViewModel(PaymentPrefs(CONTACT), PaymentSeam(CONTACT))
        PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(ME))
        PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        PaymentHarness.setField(vm, "decryptFailedPeers", ConcurrentHashMap.newKeySet<String>())
        PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
        PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
        val tx = incoming(1)
        return ScanPassHarness.scan(
            vm = vm,
            prefs = ScanPassHarness.prefsWith(phone.store, mapOf("getOrPutPendingTxFirstSeenMillis" to { a -> a[1] })),
            contactBook = EmptyContacts,
            repository = repository(mapOf(tx.id.txIdString() to listOf(memo))),
            synchronizer = synchronizer(),
            transactions = listOf(tx),
            userAddress = ME,
            hiddenMsgIds = emptySet(),
        )
    }

    private fun incoming(n: Int): Transaction {
        val id = TransactionId.new("%064x".format(14_800 + n))
        val at = Instant.now().minusSeconds(60)
        return ReceiveTransaction.Success(
            id = id,
            amount = Zatoshi(1_000),
            timestamp = at,
            memoCount = 1,
            transactionOutputs = emptyList(),
            overview = ScanPassHarness.overview(id, MINED_AT + n, at, sent = false, amount = 1_000),
            recipient = null,
        )
    }

    private fun repository(memosByTx: Map<String, List<String>>): TransactionRepository =
        Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getMemos" -> memosByTx[(args!![0] as Transaction).id.txIdString()] ?: emptyList<String>()
                "getAllRecipientAddresses" -> listOf(ME)
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "ZC1480Repository"
                else -> error("unexpected TransactionRepository.${method.name}")
            }
        } as TransactionRepository

    private fun synchronizer(): Synchronizer =
        Proxy.newProxyInstance(
            Synchronizer::class.java.classLoader,
            arrayOf(Synchronizer::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getFullyScannedHeight", "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(ScanPassHarness.SCANNED))
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "ZC1480Synchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer

    private val base64Url: java.util.Base64.Decoder = java.util.Base64.getUrlDecoder()

    /** The memos of one ZIP-321 request the use case built. */
    private fun memosOf(uri: String): List<String> =
        Regex("[?&]memo(?:\\.\\d+)?=([^&]+)")
            .findAll(uri)
            .map { String(base64Url.decode(it.groupValues[1]), Charsets.UTF_8) }
            .toList()

    /**
     * The v4 REPLY as ZChat 2.12.4 builds it, copied from v2.12.4 (commit a1fb4136f): ZMSGProtocol.createV4ReplyMessage
     * (lines 202-206), generateAddressHash (lines 144-149), validateConvId (lines 128-131, CONV_ID_LENGTH 8) and
     * ZMSGConstants.Prefixes.V4 = "ZMSG|v4|" (ZMSGConstants.kt line 44).
     */
    private object Frozen2124 {
        private const val PREFIX_V4 = "ZMSG|v4|"

        private fun generateAddressHash(address: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(address.toByteArray())
            return hashBytes.take(8).joinToString("") { "%02x".format(it) }
        }

        fun createV4ReplyMessage(
            convId: String,
            senderAddress: String,
            message: String,
        ): String {
            require(convId.length == 8) { "Invalid convId length: ${convId.length}, expected 8" }
            val hash = generateAddressHash(senderAddress)
            return "$PREFIX_V4$convId|$hash|$message"
        }
    }
}
