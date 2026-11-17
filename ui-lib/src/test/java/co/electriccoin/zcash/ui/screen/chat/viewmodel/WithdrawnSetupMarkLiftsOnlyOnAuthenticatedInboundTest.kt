package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1393 (medium; Fable review at c39a0f61c): ZC-1366 lifts the ZC-1352 withdrawn mark on any inbound on-chain message
 * the history pass adds (convertToConversations -> userStartsSetupAgain). A plaintext ZMSG memo (a v4 INIT) is not
 * authenticated: anyone can send one naming the contact's address (parseMemo's senderAddress), and its row is marked
 * authenticated = false. It lifts the mark of a chat the user deleted, and a later block may pay that chat's Tunnel ZBOOT.
 *
 * Chosen rule (orchestrator, ZC-1393): the mark lifts only on authenticated inbound: a relay rumor from the contact's bound
 * relay key, an on-chain KEX, or an authenticated (ratcheted) on-chain message. Pinned here: a plaintext on-chain memo
 * naming the contact leaves the mark; a relay text from the bound key still lifts it. Every address, key and text is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WithdrawnSetupMarkLiftsOnlyOnAuthenticatedInboundTest {
    private val sendScopes = SendHarnessScopes()
    private val tag = "synthetic-1393-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    /** [delegate] with a durable ZC-1352 mark in [withdrawn]. */
    private class MarkedStore(
        private val delegate: ZchatPreferences,
        val withdrawn: MutableSet<String>,
    ) {
        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "isAutomaticSetupWithdrawn" -> args!![0] in withdrawn
                    "setAutomaticSetupWithdrawn" -> {
                        if (args!![1] as Boolean) withdrawn += args[0] as String else withdrawn -= args[0] as String
                        true
                    }
                    else ->
                        try {
                            method.invoke(delegate, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
    }

    @Test
    fun `control - a relay text from the contact's bound relay key clears the withdrawn mark`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(13_931)
            val key = relayKey(1_393)
            val relay =
                RelayKeyStore().apply {
                    pubkeys[peer] = key
                    modes[peer] = ConversationMode.TUNNEL
                }
            val marked = MarkedStore(relay.proxy, ConcurrentHashMap.newKeySet<String>().apply { add(peer) })
            val vm = RelayKeyChatScreen.chatViewModel(relay)
            PaymentHarness.setField(vm, "zchatPreferences", marked.proxy)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            NostrChatBridge.dispatch(
                NostrInboxManager.InboundDm(
                    senderPubkeyHex = key,
                    content = "synthetic relay text the contact wrote first",
                    createdAtSec = System.currentTimeMillis() / 1000,
                    eventId = "$tag-relay",
                    rumorId = "rumor-$tag-relay",
                ),
                marked.proxy,
            )
            withTimeoutOrNull(WAIT_MS) { while (relay.rows.isEmpty()) delay(POLL_MS) }
            assertNotNull("fixture: the relay text was not stored", relay.rows["nmsg-rumor-$tag-relay"])
            withTimeoutOrNull(WAIT_MS) { while (peer in marked.withdrawn) delay(POLL_MS) }

            assertFalse("the contact wrote first over the relays and the withdrawn mark stayed", peer in marked.withdrawn)
        }

    @Test
    fun `a plaintext on-chain memo naming the contact leaves the withdrawn mark`() =
        runBlocking {
            val withdrawn = ConcurrentHashMap.newKeySet<String>().apply { add(PEER) }
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
                        "isAutomaticSetupWithdrawn" to { a -> a[0] in withdrawn },
                        "setAutomaticSetupWithdrawn" to { a ->
                            if (a[1] as Boolean) withdrawn += a[0] as String else withdrawn -= a[0] as String
                            true
                        },
                    ),
                )
            val tx = incoming(ZMSGProtocol.createV4InitMessage(CONV, PEER, ONCHAIN_TEXT))

            val conversations =
                ScanPassHarness.scan(
                    vm = vm,
                    prefs = prefs.proxy,
                    contactBook = contacts(),
                    repository = repository(tx.first, tx.second),
                    synchronizer = synchronizer(),
                    transactions = listOf(tx.first),
                    userAddress = ME,
                )
            assertTrue(
                "fixture: the on-chain message did not reach the contact's chat",
                conversations.any { c -> c.peerAddress == PEER && c.messages.any { it.text.contains(ONCHAIN_TEXT) } },
            )
            assertFalse(
                "fixture: the plaintext memo's row must read as unauthenticated",
                conversations
                    .first { it.peerAddress == PEER }
                    .messages
                    .first { it.text.contains(ONCHAIN_TEXT) }
                    .authenticated,
            )
            delay(SETTLE_MS)

            assertTrue(
                "an unauthenticated plaintext memo naming the contact lifted the withdrawn mark of a deleted chat",
                PEER in withdrawn,
            )
        }

    // ── on-chain helpers (as WithdrawnSetupMarkLiftsOnAnyInboundMessageTest) ──────────────────────────────────────────

    private fun incoming(memo: String): Pair<Transaction, String> {
        val id = TransactionId.new("%064x".format(TX_N))
        val at = Instant.now()
        return ReceiveTransaction.Success(
            id = id,
            amount = Zatoshi(1_000),
            timestamp = at,
            memoCount = 1,
            transactionOutputs = emptyList(),
            overview = ScanPassHarness.overview(id, MINED_AT, at, sent = false, amount = 1_000),
            recipient = null,
        ) to memo
    }

    private fun repository(
        tx: Transaction,
        memo: String,
    ): TransactionRepository =
        Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            fun ours() = (args!![0] as Transaction).id.txIdString() == tx.id.txIdString()
            when (method.name) {
                "getMemos" -> if (ours()) listOf(memo) else emptyList<String>()
                "getAllRecipientAddresses" -> emptyList<String>()
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "Zc1393Repository"
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
                "toString" -> "Zc1393Synchronizer"
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
                "toString" -> "Zc1393Contacts"
                else -> error("unexpected ContactBook.${method.name}")
            }
        } as ContactBook
    }

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

    private companion object {
        val PEER: String = SyntheticUnifiedAddress.mainnet(13_932)
        val ME: String = SyntheticUnifiedAddress.mainnet(13_933)
        const val CONV = "ZC1393CV"
        const val MINED_AT = 3_000_150L
        const val TX_N = 1_393
        const val ONCHAIN_TEXT = "synthetic plaintext words naming the contact"
        const val WAIT_MS = 2_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 300L
    }
}
