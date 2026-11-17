package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionRecipient
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepositoryImpl
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * R2-5 (b) — SDK 3.0.0 transactions must never create a chat that is not a contact's.
 *
 * SDK 3.0.0 lists EVERY output of a sent transaction as a recipient (v_tx_outputs, ordered by txid only, so the
 * outputs of one transaction come in any order): the contact, the platform-fee output, and the change back to
 * this wallet (to_account_uuid set; with or without an address). The chat list resolves the peer of an outgoing
 * message from the first recipient (TransactionRepositoryImpl.getRecipient) and then from all recipients minus
 * the CURRENT fee address (ChatViewModel.resolveOutgoingPeerAddress). A message sent before 9 Sep paid its fee to
 * the PREVIOUS fee address (replaced in c1275ecbb), which nothing filters.
 *
 * Wanted: a chat message shows up only in its contact's chat, in any output order; no chat is created for the
 * wallet's own address, for either fee address, or for a no-memo move of old balance to this wallet.
 *
 * Driven end to end: the real TransactionRepositoryImpl over a fake SDK synchronizer builds each transaction's
 * recipient and recipient list, and the real convertToConversations routes it. The contact and own addresses are
 * synthetic; the two fee addresses are the real ones the app shipped with (the previous one is public: it was in
 * every APK up to 2.13.0).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhantomChatRowsTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticphantompeer0000001"
        const val OWN = "usyntheticphantomownaddress1"
        const val MINED_AT = 3_000_100L

        /** The platform-fee address every chat message paid until 9 Sep 2026 (mainnet builds up to c1275ecbb). */
        const val PREVIOUS_FEE =
            "u1pm2ju3zua63jtww3zexpahpqlgcu35qqq9hv7689n5luz3pkuefwyk27f4" +
                "t2r8wf3up8cajkvtelhmnlja4sqk58s6qjavlyf5xv5s2qck6yuc4muee4g8" +
                "6zn8h4uzvdp9q3px2f6clxd46fvcllsphyndl7tvkjzwal68eccq7p4w53"

        val CURRENT_FEE: String = ZMSGConstants.PLATFORM_FEE_ADDRESS

        // The SDK builds this from the wallet database; its constructor is internal to the SDK.
        val OWN_ACCOUNT: AccountUuid =
            AccountUuid::class.java
                .getDeclaredConstructor(ByteArray::class.java)
                .apply { isAccessible = true }
                .newInstance(ByteArray(16) { 7 })
    }

    /** One output of a sent transaction as SDK 3.0.0 lists it. */
    private class Out(
        val address: String?,
        val ownAccount: Boolean = false,
    )

    private val peerOut = Out(PEER)
    private val currentFeeOut = Out(CURRENT_FEE)
    private val previousFeeOut = Out(PREVIOUS_FEE)
    private val changeToOwnAddress = Out(OWN, ownAccount = true)
    private val changeWithoutAddress = Out(null, ownAccount = true)

    private class Tx(
        val number: Int,
        val memo: String?,
        val outputs: List<Out>,
    )

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
    fun `a chat message with fee and change outputs in any order shows only in its contact's chat`() =
        runBlocking {
            val shapes =
                permutations(listOf(peerOut, currentFeeOut, changeToOwnAddress)) +
                    permutations(listOf(peerOut, currentFeeOut, changeWithoutAddress))
            val txs = shapes.mapIndexed { i, outputs -> Tx(i + 1, message("synthetic hello ${i + 1}"), outputs) }

            val conversations = scan(txs)

            assertEquals("chats created for a non-contact: ${labels(conversations)}", listOf("contact"), chatsOf(conversations))
            assertEquals("every message is in the contact's chat", txs.size, conversations.single().messages.size)
        }

    @Test
    fun `a message sent before 9 Sep whose fee went to the previous fee address shows only in its contact's chat`() =
        runBlocking {
            val shapes =
                permutations(listOf(peerOut, previousFeeOut)) +
                    permutations(listOf(peerOut, previousFeeOut, changeToOwnAddress))
            val txs = shapes.mapIndexed { i, outputs -> Tx(i + 1, message("synthetic old hello ${i + 1}"), outputs) }

            val conversations = scan(txs)

            assertEquals("chats created for a non-contact: ${labels(conversations)}", listOf("contact"), chatsOf(conversations))
            assertEquals(txs.size, conversations.single().messages.size)
        }

    @Test
    fun `a no-memo move of old balance to this wallet creates no chat`() =
        runBlocking {
            val txs =
                listOf(
                    Tx(1, null, listOf(changeToOwnAddress)),
                    Tx(2, null, listOf(changeToOwnAddress, changeWithoutAddress)),
                    Tx(3, "", listOf(changeWithoutAddress, changeToOwnAddress)),
                )

            val conversations = scan(txs)

            assertEquals("chats created for a non-contact: ${labels(conversations)}", emptyList<String>(), chatsOf(conversations))
        }

    @Test
    fun `the wallet history names the contact as the recipient of a chat message, in any output order`() =
        runBlocking {
            val shapes =
                permutations(listOf(peerOut, currentFeeOut, changeToOwnAddress)) +
                    permutations(listOf(peerOut, previousFeeOut, changeWithoutAddress))
            val txs = shapes.mapIndexed { i, outputs -> Tx(i + 1, message("synthetic history ${i + 1}"), outputs) }
            val repository = repository(txs)

            val recipients = txs.map { recipientOf(repository, overviewOf(it)) }

            assertEquals("the history recipient of each chat message", txs.map { "contact" }, recipients.map { label(it) })
        }

    private fun message(text: String) = ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, text)

    private suspend fun scan(txs: List<Tx>): List<Conversation> {
        val prefs = PaymentPrefs(PEER)
        val vm = paymentViewModel(prefs, PaymentSeam(PEER))
        val repository = repository(txs)
        val transactions: List<Transaction> =
            txs.map { tx ->
                val overview = overviewOf(tx)
                SendTransaction.Success(
                    id = overview.txId,
                    amount = Zatoshi(50_000),
                    timestamp = Instant.ofEpochSecond(1_790_000_000L + tx.number),
                    memoCount = if (tx.memo.isNullOrEmpty()) 0 else 1,
                    fee = Zatoshi(10_000),
                    transactionOutputs = emptyList(),
                    overview = overview,
                    recipient = recipientOf(repository, overview)?.let { unified(it) },
                )
            }
        return ScanPassHarness.scan(
            vm = vm,
            prefs = ScanPassHarness.prefsWith(prefs.proxy, ScanPassHarness.scanDefaults),
            contactBook = ScanPassHarness.noContacts(),
            repository = repository,
            synchronizer = synchronizer(txs),
            transactions = transactions,
        )
    }

    private fun overviewOf(tx: Tx): TransactionOverview =
        ScanPassHarness.overview(
            id = TransactionId.new("%064x".format(tx.number)),
            minedHeight = MINED_AT + tx.number,
            blockTime = Instant.ofEpochSecond(1_790_000_000L + tx.number),
            sent = true,
        )

    private fun synchronizer(txs: List<Tx>): Synchronizer {
        val byTxId = txs.associateBy { TransactionId.new("%064x".format(it.number)).txIdString() }
        return Proxy.newProxyInstance(
            Synchronizer::class.java.classLoader,
            arrayOf(Synchronizer::class.java),
        ) { p, method, args ->
            fun txOf() = byTxId.getValue((args!![0] as TransactionOverview).txId.txIdString())
            when {
                method.name == "getRecipients" && args?.getOrNull(0) is TransactionOverview ->
                    flowOf(
                        *txOf()
                            .outputs
                            .map { TransactionRecipient(it.address, if (it.ownAccount) OWN_ACCOUNT else null) }
                            .toTypedArray()
                    )
                method.name == "getMemos" -> flowOf(*listOfNotNull(txOf().memo).toTypedArray())
                method.name == "validateAddress" -> AddressType.Unified
                method.name == "getFullyScannedHeight" || method.name == "getNetworkHeight" ->
                    MutableStateFlow(BlockHeight.new(ScanPassHarness.SCANNED))
                method.name == "hashCode" -> System.identityHashCode(p)
                method.name == "equals" -> p === args?.get(0)
                method.name == "toString" -> "PhantomRowsSynchronizer"
                else -> error("unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer
    }

    private fun repository(txs: List<Tx>): TransactionRepositoryImpl =
        TransactionRepositoryImpl(noAccounts(), ScanPassHarness.synchronizerProvider(synchronizer(txs)))

    private fun noAccounts(): AccountDataSource =
        Proxy.newProxyInstance(AccountDataSource::class.java.classLoader, arrayOf(AccountDataSource::class.java)) { p, method, args ->
            when (method.name) {
                "getSelectedAccount", "getAllAccounts", "getZashiAccount" ->
                    if (args.isNullOrEmpty()) MutableStateFlow(null) else error("not used")
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "NoAccounts"
                else -> error("unexpected AccountDataSource.${method.name}")
            }
        } as AccountDataSource

    /** TransactionRepositoryImpl's own recipient pick for the wallet history (private getRecipient). */
    private suspend fun recipientOf(
        repository: TransactionRepositoryImpl,
        overview: TransactionOverview,
    ): String? {
        val method =
            TransactionRepositoryImpl::class.java.declaredMethods
                .single { it.name == "getRecipient" }
                .apply { isAccessible = true }
        val address =
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<WalletAddress?> { continuation ->
                try {
                    method.invoke(repository, overview, continuation)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        return address?.address
    }

    private fun unified(address: String): WalletAddress =
        WalletAddress.Unified::class.java.declaredConstructors
            .single { it.parameterCount == 2 }
            .apply { isAccessible = true }
            .newInstance(address, null) as WalletAddress

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }
        }

    private fun label(address: String?): String =
        when (address) {
            null -> "none"
            PEER -> "contact"
            OWN -> "own address"
            CURRENT_FEE -> "current fee address"
            PREVIOUS_FEE -> "previous fee address"
            else -> "other (${address.length} chars)"
        }

    /** Chats by label, never by address (the failure output must not print a full address). */
    private fun chatsOf(conversations: List<Conversation>) = conversations.map { label(it.peerAddress) }

    private fun labels(conversations: List<Conversation>) = conversations.map { "${label(it.peerAddress)}: ${it.messages.size} rows" }
}
