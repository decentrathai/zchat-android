package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
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
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 8 D5 (sweep #14, #15 / M-CHT-02) — the on-chain conversation-mode control (ZMODE), the twin of the timer
 * control that R2-5 (c) fixed (DisappearingMessagesIntegrityTest).
 *
 * #14: a Shielded chat that switches mode with no relay channel sends the ZMODE on chain as a bare E2E1 memo
 * (createChunkedMessageProposal(rawMemo = true), no conversation id). A bare memo names no conversation, so the
 * contact's device cannot pick the ratchet to decrypt it with: the change is paid for and never syncs, and the
 * free relay retry is disarmed right after. Wanted: the ZMODE goes in the v4 envelope that names the conversation.
 *
 * #15: the on-chain receive gate for ZMODE lacks the key-changed check that the on-chain ZEXP gate and the NOSTR
 * ZMODE gate have. Wanted: an encrypted ZMODE from a contact whose key change is unaccepted changes nothing.
 *
 * All addresses, keys and texts are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnChainModeChangeIntegrityTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val CONV = "ZMODECNV"
        const val MINED_AT = 3_000_100L
        val CONTACT: String = SyntheticUnifiedAddress.mainnet(5201)
        val ME: String = SyntheticUnifiedAddress.mainnet(5202)
        val ROOT = ByteArray(32) { (it * 13 + 5).toByte() }
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
    fun `the contact's mode change is sent in a form the contact's conversation can be found from`() =
        runBlocking {
            val sent = contactSendsModeChange(ConversationMode.TUNNEL)
            val parsed = ZMSGProtocol.parseMemo(sent.memos.joinToString("\n").trim(), NoOpAddressCache.proxy)

            assertEquals("a bare encrypted memo names no conversation, so no receiver can decrypt it", CONV, parsed.conversationId)
            assertFalse(
                "the memo must not be the bare E2E1 blob (rawMemo = true)",
                sent.memos.single().startsWith("E2E1"),
            )
        }

    @Test
    fun `the contact's encrypted mode change, as their app sends it, reaches the mode handler`() =
        runBlocking {
            val sent = contactSendsModeChange(ConversationMode.TUNNEL)
            val me = Receiver()

            me.scan(listOf(incoming(1, sent.memos)))

            assertEquals("the contact's encrypted mode change was not applied", listOf(CONTACT), me.modeChanges())
        }

    @Test
    fun `sending the mode change on chain keeps the own-plaintext cache and clears the relay retry only after the send`() =
        runBlocking {
            val sent = contactSendsModeChange(ConversationMode.TUNNEL)

            assertTrue("our own control's plaintext must be cached for the rescan", sent.cachedOwnPlaintext)
            assertEquals("the relay retry is cleared once the on-chain send went out", listOf("clearPendingModeSync"), sent.retryCalls)
        }

    @Test
    fun `an encrypted mode change from a contact whose key change is unaccepted changes nothing`() =
        runBlocking {
            val contact = E2EMessageProcessor(ROOT, CONV, isLower = true, store = InMemoryRatchetStateStore())
            val control = ZMSGProtocol.createModeChange(ConversationMode.TUNNEL, System.currentTimeMillis(), CONTACT)
            val memos = ZMSGProtocol.createChunkedV4ReplyMessages(CONV, CONTACT, contact.encryptOutgoing(control))
            val me = Receiver(keyChangeUnaccepted = true)

            me.scan(listOf(incoming(1, memos)))

            assertEquals("a key-changed contact changed the mode (M-CHT-02)", emptyList<String>(), me.modeChanges())
        }

    /** Control (passes before and after): the same attributable, encrypted mode change from the contact is handled. */
    @Test
    fun `control - an attributable encrypted mode change from the contact is handled`() =
        runBlocking {
            val contact = E2EMessageProcessor(ROOT, CONV, isLower = true, store = InMemoryRatchetStateStore())
            val control = ZMSGProtocol.createModeChange(ConversationMode.TUNNEL, System.currentTimeMillis(), CONTACT)
            val memos = ZMSGProtocol.createChunkedV4ReplyMessages(CONV, CONTACT, contact.encryptOutgoing(control))
            val me = Receiver()

            me.scan(listOf(incoming(1, memos)))

            assertEquals(listOf(CONTACT), me.modeChanges())
        }

    /** Control (passes before and after): a plaintext mode change changes nothing, also when it names the contact. */
    @Test
    fun `control - a plaintext mode change changes nothing`() =
        runBlocking {
            val plaintext = ZMSGProtocol.createModeChange(ConversationMode.TUNNEL, System.currentTimeMillis(), CONTACT)
            val me = Receiver()

            me.scan(
                listOf(
                    incoming(1, ZMSGProtocol.createChunkedV4ReplyMessages(CONV, CONTACT, plaintext)),
                    incoming(2, listOf(plaintext)),
                ),
            )

            assertEquals(emptyList<String>(), me.modeChanges())
        }

    // ---------------------------------------------------------------- fixture

    private class SentModeChange(
        val memos: List<String>,
        val cachedOwnPlaintext: Boolean,
        val retryCalls: List<String>,
    )

    /** The contact's app leaves a Shielded chat with no relay channel: the memo(s) its real send puts on chain. */
    private suspend fun contactSendsModeChange(newMode: ConversationMode): SentModeChange {
        val seam = PaymentSeam(ME)
        val vm = paymentViewModel(PaymentPrefs(ME), seam)
        val retryCalls = Collections.synchronizedList(mutableListOf<String>())
        val cachedPlaintext = Collections.synchronizedList(mutableListOf<String>())
        val prefs =
            ScanPrefs(
                mapOf(
                    "getConversationMode" to { _ -> ConversationMode.VAULT },
                    "getOrCreateConversationId" to { _ -> CONV to false },
                    // R2-6 (c): the per-message call (an existing conversation whose INIT went out).
                    "getOrCreateConversationIdForMessage" to { _ -> CONV to false },
                    "ownConversationId" to { _ -> CONV },
                    "ownConversationIdForStart" to { _ -> CONV },
                    "getConversationId" to { _ -> CONV },
                    // No relay channel: the change can only go on chain.
                    "getPeerNostrPubkey" to { _ -> null },
                    // Keyed contact, no key-recovery gate in play (as PaymentPrefs has it).
                    "getE2EOurPublicKey" to { _ -> "synthetic-our-public-key" },
                    "hasAcknowledgedMessageCost" to { _ -> true },
                    "putDecryptedText" to { a -> cachedPlaintext += a[1] as String },
                    "setPendingModeSync" to { _ -> retryCalls += "setPendingModeSync" },
                    "clearPendingModeSync" to { _ -> retryCalls += "clearPendingModeSync" },
                ),
            )
        PaymentHarness.setField(vm, "zchatPreferences", prefs.proxy)
        PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(CONTACT))
        val memoryCache = ConcurrentHashMap<String, String>()
        PaymentHarness.setField(vm, "decryptedTextCache", memoryCache)
        processors(vm)["$ME:$CONV"] = E2EMessageProcessor(ROOT, CONV, isLower = true, store = InMemoryRatchetStateStore())

        vm.changeConversationMode(ME, newMode, costConfirmed = true)
        awaitIdle(vm)

        val uri = seam.proposalUris.single()
        val memos =
            Regex("[?&]memo(?:\\.\\d+)?=([^&]+)")
                .findAll(uri)
                .map {
                    String(
                        java.util.Base64
                            .getUrlDecoder()
                            .decode(it.groupValues[1]),
                        Charsets.UTF_8
                    )
                }.toList()
                .also { assertTrue("fixture: the contact's app sent the mode change on chain", it.isNotEmpty()) }
        val cached = (memoryCache.values + cachedPlaintext).any { ZMSGProtocol.isModeChange(it) }
        return SentModeChange(memos, cached, retryCalls.toList())
    }

    @Suppress("UNCHECKED_CAST")
    private fun processors(vm: ChatViewModel): MutableMap<String, Any> =
        ChatViewModel::class.java
            .getDeclaredField("messageProcessors")
            .apply { isAccessible = true }
            .get(vm) as MutableMap<String, Any>

    /** This device: [ME], with [CONTACT] a known, keyed contact on [CONV]. Records every mode change handled. */
    private inner class Receiver(
        private val keyChangeUnaccepted: Boolean = false,
    ) {
        private val modeCalls = Collections.synchronizedList(mutableListOf<String>())
        lateinit var vm: ChatViewModel

        private val prefs =
            ScanPrefs(
                mapOf(
                    "getPeerByConversationId" to { a -> if (a[0] == CONV) CONTACT else null },
                    "getConversationId" to { a -> if (a[0] == CONTACT) CONV else null },
                    "getAllConversationMappings" to { _ -> mapOf(CONV to CONTACT) },
                    "isE2EKeyChanged" to { a -> keyChangeUnaccepted && a[0] == CONTACT },
                    // The first thing the mode handler does; false ends it there (no note, no mode write).
                    "advancePeerModeChangeSince" to { a ->
                        modeCalls += a[0] as String
                        false
                    },
                    "getDisplayName" to { _ -> "Synthetic Contact" },
                    "getOrPutMessageExpiryAnchorMillis" to { a -> a[1] },
                    "getOrPutPendingTxFirstSeenMillis" to { a -> a[1] },
                    "getConversationMode" to { _ -> ConversationMode.VAULT },
                ),
            )

        fun modeChanges(): List<String> = modeCalls.toList()

        private suspend fun ensureVm() {
            if (::vm.isInitialized) return
            vm = paymentViewModel(PaymentPrefs(CONTACT), PaymentSeam(CONTACT))
            PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(ME))
            PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            PaymentHarness.setField(vm, "decryptFailedPeers", ConcurrentHashMap.newKeySet<String>())
            PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
            PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
            processors(vm)["$CONTACT:$CONV"] = E2EMessageProcessor(ROOT, CONV, isLower = false, store = InMemoryRatchetStateStore())
        }

        suspend fun scan(txs: List<ScanTx>) {
            ensureVm()
            ScanPassHarness.scan(
                vm = vm,
                prefs = prefs.proxy,
                contactBook = ScanPassHarness.noContacts(),
                repository = repository(txs),
                synchronizer = synchronizer(),
                transactions = txs.map { it.tx },
                userAddress = ME,
                hiddenMsgIds = emptySet(),
            )
        }
    }

    private class ScanTx(
        val tx: co.electriccoin.zcash.ui.common.repository.Transaction,
        val memos: List<String>,
    )

    private fun incoming(
        n: Int,
        memos: List<String>,
    ): ScanTx {
        val id = TransactionId.new("%064x".format(n))
        val at = Instant.now().minusSeconds(60)
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

    private fun repository(txs: List<ScanTx>): TransactionRepository {
        val byTxId = txs.associateBy { it.tx.id.txIdString() }
        return Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            fun txOf() = byTxId[(args!![0] as co.electriccoin.zcash.ui.common.repository.Transaction).id.txIdString()]
            when (method.name) {
                "getMemos" -> txOf()?.memos ?: emptyList<String>()
                "getAllRecipientAddresses" -> listOf(CONTACT)
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "ModeChangeRepository"
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
                "toString" -> "ModeChangeSynchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer
}
