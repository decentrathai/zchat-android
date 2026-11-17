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
import co.electriccoin.zcash.ui.common.model.ZcashReceivers
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepositoryImpl
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.random.Random

/**
 * ZC-1082 test support: a wallet's own sends as the wallet library lists them after a restore (each output named by the
 * single receiver its note was paid to), run through the REAL TransactionRepositoryImpl (its recipient pick) and the REAL
 * history pass (convertToConversations), as PhantomChatRowsTest does for R2-5 (b). [known] are the addresses the user's
 * contact book holds. Every contact address is synthetic (random receiver bytes in a well-formed Unified Address); the
 * fee addresses are the two the app paid.
 */
internal class RestoredSendScan(
    private val peer: String,
    private val known: List<String> = emptyList(),
) {
    /** One output of a sent transaction as SDK 3.0.0 lists it. */
    class Out(
        val address: String?,
        val ownAccount: Boolean = false,
    )

    class Tx(
        val number: Int,
        val memo: String?,
        val outputs: List<Out>,
    )

    suspend fun scan(txs: List<Tx>): List<Conversation> {
        val prefs = PaymentPrefs(peer)
        val vm = paymentViewModel(prefs, PaymentSeam(peer))
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
            prefs = ScanPassHarness.prefsWith(prefs.proxy, ScanPassHarness.scanDefaults + storeDefaults),
            contactBook = contactBook(),
            repository = repository,
            synchronizer = synchronizer(txs),
            transactions = transactions,
        )
    }

    /** TransactionRepositoryImpl's own recipient pick for the wallet history (its private getRecipient) for [tx]. */
    suspend fun historyRecipient(
        txs: List<Tx>,
        tx: Tx,
    ): String? = recipientOf(repository(txs), overviewOf(tx))

    private fun overviewOf(tx: Tx): TransactionOverview =
        ScanPassHarness.overview(
            id = TransactionId.new("%064x".format(tx.number)),
            minedHeight = MINED_AT + tx.number,
            blockTime = Instant.ofEpochSecond(1_790_000_000L + tx.number),
            sent = true,
        )

    private fun contactBook(): ContactBook =
        Proxy.newProxyInstance(ContactBook::class.java.classLoader, arrayOf(ContactBook::class.java)) { p, method, args ->
            when (method.name) {
                "getAllContacts" -> known.map { Contact(it, "Known contact") }
                "getContact" -> (args!![0] as String).takeIf { it in known }?.let { Contact(it, "Known contact") }
                "hasContact" -> args!![0] in known
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "RestoredSendContacts"
                else -> error("unexpected ContactBook.${method.name}")
            }
        } as ContactBook

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
                method.name == "toString" -> "RestoredSendSynchronizer"
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

    companion object {
        const val MINED_AT = 3_000_100L

        /** What the store answers that PaymentPrefs does not: no chats, no nicknames, no own address here, no aliases. */
        private val storeDefaults: Map<String, (Array<Any?>) -> Any?> =
            mapOf(
                "getAllConversationMappings" to { _ -> emptyMap<String, String>() },
                "getAllPeerToConvIdMappings" to { _ -> emptyMap<String, String>() },
                "getAllContactAddresses" to { _ -> emptySet<String>() },
                "isSelfAddress" to { _ -> false },
                "resolvePeerAddress" to { a -> a[0] },
            )

        /** The platform-fee address every chat message paid until 9 Sep 2026 (mainnet builds up to c1275ecbb). */
        val PREVIOUS_FEE: String = ZMSGConstants.PREVIOUS_PLATFORM_FEE_ADDRESSES.single()

        val CURRENT_FEE: String = ZMSGConstants.PLATFORM_FEE_ADDRESS

        val OWN_ACCOUNT: AccountUuid =
            AccountUuid::class.java
                .getDeclaredConstructor(ByteArray::class.java)
                .apply { isAccessible = true }
                .newInstance(ByteArray(16) { 7 })

        private fun hexByte(
            hex: String,
            index: Int,
        ): Byte = hex.substring(2 * index, 2 * index + 2).toInt(16).toByte()

        /** Raw receivers (typecode to bytes) of a synthetic contact: a Sapling and an Orchard receiver, random in [seed]. */
        fun receiversOf(seed: Int): List<Pair<Int, ByteArray>> {
            val random = Random(seed)
            return listOf(2 to random.nextBytes(43), 3 to random.nextBytes(43))
        }

        /** A synthetic contact's full Unified Address (both receivers). */
        fun contact(seed: Int): String = ZcashReceivers.unifiedAddress("u", receiversOf(seed))!!

        /** The Unified Address made of [address]'s receiver of [typecode] only: how a restored wallet names a send to it. */
        fun receiverForm(
            address: String,
            typecode: Int = 3,
        ): String {
            val receiver = ZcashReceivers.of(address)!!.single { it.typecode == typecode }
            val bytes = ByteArray(receiver.hex.length / 2) { hexByte(receiver.hex, it) }
            return ZcashReceivers.unifiedAddress("u", listOf(typecode to bytes))!!
        }

        /** A Unified Address made of [a]'s receiver of [typecodeA] and [b]'s receiver of [typecodeB] (a forged mix). */
        fun mix(
            a: String,
            typecodeA: Int,
            b: String,
            typecodeB: Int,
        ): String {
            fun bytesOf(
                address: String,
                typecode: Int,
            ): ByteArray {
                val hex = ZcashReceivers.of(address)!!.single { it.typecode == typecode }.hex
                return ByteArray(hex.length / 2) { hexByte(hex, it) }
            }
            val items = listOf(typecodeA to bytesOf(a, typecodeA), typecodeB to bytesOf(b, typecodeB)).sortedBy { it.first }
            return ZcashReceivers.unifiedAddress("u", items)!!
        }
    }
}
