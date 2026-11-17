package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.kexMemoFrom
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * KI-24 fixup, G-KI24-2 (train T4 cross review): the processed-KEX height floor never passes a key offer the history
 * pass put off.
 *
 * During a restore (or a first sync of a seed) RestoreKexPolicy puts off every mined inbound key offer (KEX) until a
 * complete, stable pass has read our own history; a put-off offer is not marked processed, so a later pass must still
 * handle it. Inbound KEXACKs are never put off: each is marked as the pass reads it. At 1ca5f2e3c the store raises its
 * height floor whenever its 500-entry cap evicts, to the highest evicted mined height (clamped only to the fully
 * scanned height, which a restore's pass already covers). With 500 or more KEXACKs mined above a put-off KEX, the floor
 * rises past it: the stable pass then finds it "processed" by the floor (hasProcessedKexTx), does not count it as
 * waiting, and handleKEXMessage returns before storing the contact's key or sending the KEXACK. Nothing lowers the floor,
 * so messaging with that contact never starts.
 *
 * Wanted: the pass tells the store about the offers it put off, and the floor stays below the lowest of them until the
 * offer is handled; entries the floor may not cover stay in the store instead of being evicted uncovered.
 *
 * Runs the real convertToConversations ([ScanPassHarness]; the history scan is not complete, as during a restore) over
 * one inbound KEX, with the real ZchatPreferencesImpl processed-KEX store ([realPreferences]), then marks 501 KEXACKs
 * mined above it on that store as the same restore's passes do. Every key, txid and address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexFloorWaitsForAPutOffOfferTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        val PEER: String = SyntheticUnifiedAddress.mainnet(2402)
        val ME: String = SyntheticUnifiedAddress.mainnet(2403)
        const val OFFER_HEIGHT = 3_000_100L
        val OFFER_TXID: String = "%064x".format(0x2402)
        val KEX_STORE_CALLS = setOf("hasProcessedKexTx", "markKexTxProcessed", "limitKexFloor")
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
    fun `a KEX put off during a restore is still unprocessed after 501 KEXACKs mined above it are marked`() =
        runBlocking {
            val (store, _) = realPreferences()
            val offer = kexMemoFrom(PEER, E2EEncryption.generateKeyPair())

            val vm = scanPassViewModel()
            ScanPassHarness.scan(
                vm = vm,
                prefs = kexStoreOver(store),
                contactBook = ScanPassHarness.noContacts(),
                repository = repository(OFFER_TXID, offer),
                synchronizer = synchronizer(),
                transactions = listOf(incoming(OFFER_TXID, OFFER_HEIGHT)),
                userAddress = ME,
            )
            awaitIdle(vm)
            assertFalse("fixture: the pass put the offer off (not marked)", store.hasProcessedKexTx(OFFER_TXID))

            // The same restore's KEXACKs, mined above the offer, each marked as its pass reads it.
            for (i in 1..501) store.markKexTxProcessed("synthetic-g2-kexack-%04d".format(i), OFFER_HEIGHT + i, ScanPassHarness.SCANNED)

            assertFalse(
                "the height floor rose past a KEX the pass put off: the stable pass would take it as processed and never " +
                    "store the contact's key or answer it",
                store.hasProcessedKexTx(OFFER_TXID, OFFER_HEIGHT),
            )
            assertTrue(
                "the first KEXACK still counts as processed (kept, or under the floor)",
                store.hasProcessedKexTx("synthetic-g2-kexack-0001", OFFER_HEIGHT + 1),
            )
        }

    /** Control, GREEN before and after: with no offer waiting, 501 KEXACKs still leave the first one processed. */
    @Test
    fun `control - with no offer put off the evicted KEXACK stays processed`() {
        val (store, _) = realPreferences()
        for (i in 1..501) store.markKexTxProcessed("synthetic-g2c-kexack-%04d".format(i), OFFER_HEIGHT + i, ScanPassHarness.SCANNED)

        assertTrue(store.hasProcessedKexTx("synthetic-g2c-kexack-0001", OFFER_HEIGHT + 1))
        assertFalse(store.hasProcessedKexTx("synthetic-g2c-never-seen", ScanPassHarness.SCANNED + 1))
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The pass's preferences: the processed-KEX store's calls go to the real store (with the pass's report of the
     * offers it put off, which the fix adds under the name limitKexFloor); every other call gets [ScanPrefs]'s empty
     * answers, as in the other scan-pass tests.
     */
    private fun kexStoreOver(store: ZchatPreferences): ZchatPreferences {
        val rest =
            ScanPrefs(
                mapOf(
                    "getOrPutPendingTxFirstSeenMillis" to { a -> a[1] },
                    "getOrPutMessageExpiryAnchorMillis" to { a -> a[1] },
                ),
            ).proxy
        return Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            val target = if (method.name in KEX_STORE_CALLS) store else rest
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ZchatPreferences
    }

    private suspend fun scanPassViewModel(): ChatViewModel {
        val vm = paymentViewModel(PaymentPrefs(PEER), PaymentSeam(PEER))
        PaymentHarness.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(ME))
        PaymentHarness.setField(vm, "addressCache", MapCache())
        PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        PaymentHarness.setField(vm, "decryptFailedPeers", ConcurrentHashMap.newKeySet<String>())
        PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
        PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
        PaymentHarness.setField(vm, "unlockedMessages", MutableStateFlow<Map<String, String>>(emptyMap()))
        return vm
    }

    private fun incoming(
        txId: String,
        minedHeight: Long,
    ): Transaction {
        val id = TransactionId.new(txId)
        val at = Instant.now().minusSeconds(600L)
        return ReceiveTransaction.Success(
            id = id,
            amount = Zatoshi(1_000),
            timestamp = at,
            memoCount = 1,
            transactionOutputs = emptyList(),
            overview = ScanPassHarness.overview(id, minedHeight, at, sent = false, amount = 1_000),
            recipient = null,
        )
    }

    private fun repository(
        txId: String,
        memo: String,
    ): TransactionRepository =
        Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getMemos" -> if ((args!![0] as Transaction).id.txIdString() == txId) listOf(memo) else emptyList<String>()
                "getAllRecipientAddresses" -> emptyList<String>()
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "KexFloorRepository"
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
                "toString" -> "KexFloorSynchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer

    /** An in-memory hash cache. */
    private class MapCache : AddressCache {
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

        override fun findConversationPartnerByHash(hash: String): String? = byHash[hash]?.takeIf { it in partners }
    }
}
