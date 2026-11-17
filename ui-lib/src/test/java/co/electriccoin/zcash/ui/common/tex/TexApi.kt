package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.datasource.StoredTransaction
import co.electriccoin.zcash.ui.common.model.SubmitResult
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * F3 (fix-all 2.13.0, train T3): the TEX checker's API as the F3 tests reach it, by name, so the tests compile before
 * the package `co.electriccoin.zcash.ui.common.tex` exists (the RED commit). Every call fails with
 * ClassNotFoundException / NoSuchMethodException until the fix adds it.
 *
 * The fakes ([FakeTexWallet], [FakeTexTor]) are plain classes; [monitor] hands them to the product as its port
 * interfaces through java.lang.reflect.Proxy, as ScriptedSynchronizer does for the SDK's Synchronizer.
 * Every txid and address here is synthetic.
 */
internal object TexApi {
    private const val PKG = "co.electriccoin.zcash.ui.common.tex"

    fun cls(name: String): Class<*> = Class.forName("$PKG.$name")

    fun enumValue(
        className: String,
        name: String,
    ): Any = cls(className).enumConstants!!.first { (it as Enum<*>).name == name }

    /** The one public constructor of [className] with [args.size] parameters. */
    fun construct(
        className: String,
        vararg args: Any?,
    ): Any {
        val constructor =
            cls(className).constructors.firstOrNull { it.parameterCount == args.size }
                ?: throw NoSuchMethodException("$className(${args.size} args)")
        return constructor.newInstance(*args)
    }

    /** A top-level function of file [fileClass] (e.g. "TexModelKt") named [name] with [args.size] parameters. */
    fun static(
        fileClass: String,
        name: String,
        vararg args: Any?,
    ): Any? = staticMethod(fileClass, name, args.size).invoke(null, *args)

    private fun staticMethod(
        fileClass: String,
        name: String,
        count: Int,
    ): Method =
        cls(fileClass).methods.firstOrNull { it.name == name && it.parameterCount == count }
            ?: throw NoSuchMethodException("$fileClass.$name/$count")

    /** The Kotlin property [name] of [target] (its getter, or an `is` getter). */
    fun prop(
        target: Any,
        name: String,
    ): Any? {
        val getter =
            target.javaClass.methods.firstOrNull {
                it.parameterCount == 0 &&
                    (it.name == "get" + name.replaceFirstChar(Char::uppercase) || it.name == name)
            } ?: throw NoSuchMethodException("${target.javaClass.simpleName}.$name")
        return getter.invoke(target)
    }

    fun call(
        target: Any,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw NoSuchMethodException("${target.javaClass.simpleName}.$name/${args.size}")
        return method.invoke(target, *args)
    }

    /** Calls the suspend member [name] of [target]. */
    suspend fun callSuspend(
        target: Any,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size + 1 }
                ?: throw NoSuchMethodException("${target.javaClass.simpleName}.$name/${args.size}+continuation")
        return suspendCoroutineUninterceptedOrReturn { continuation -> method.invoke(target, *args, continuation) }
    }

    /** Calls the suspend top-level function [name] of [fileClass]. */
    suspend fun callStaticSuspend(
        fileClass: String,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method = staticMethod(fileClass, name, args.size + 1)
        return suspendCoroutineUninterceptedOrReturn { continuation -> method.invoke(null, *args, continuation) }
    }

    // ----------------------------------------------------------------------------------------------- model

    fun output(
        address: String,
        isOwn: Boolean,
        kind: String,
    ): Any = construct("TexOutput", address, isOwn, enumValue("TexAddressKind", kind))

    @Suppress("LongParameterList")
    fun facts(
        txId: String,
        rawIdHex: String = txId.reversedHex(),
        isSent: Boolean = true,
        isShielding: Boolean = false,
        mined: Long? = null,
        expiry: Long? = null,
        outputs: List<Any> = emptyList(),
    ): Any = construct("TexTxFacts", txId, rawIdHex, isSent, isShielding, mined, expiry, outputs)

    @Suppress("UNCHECKED_CAST")
    fun pairs(transactions: List<Any>): List<Any> = static("TexModelKt", "pairTexSends", transactions) as List<Any>

    fun state(status: Any): String = (prop(status, "state") as Enum<*>).name

    @Suppress("LongParameterList")
    fun derive(
        leg1Mined: Boolean,
        leg2Mined: Boolean,
        expiry: Long,
        scannedTip: Long?,
        networkTip: Long? = null,
        leg1NotSentProven: Boolean = false,
    ): String {
        val input = construct("TexStateInput", leg1Mined, leg2Mined, expiry, scannedTip, networkTip, leg1NotSentProven)
        return (static("TexStateDeriverKt", "deriveTexSendState", input) as Enum<*>).name
    }

    fun notSentProof(): Any = construct("TexNotSentProof", TexRulesView.REORG_MARGIN)

    fun observe(
        proof: Any,
        key: String,
        tip: Long?,
        expiry: Long,
    ): Boolean = call(proof, "observe", key, tip, expiry) as Boolean

    fun sendAgainAllowed(stateName: String?): Boolean =
        static(
            "TexModelKt",
            "texSendAgainAllowed",
            stateName?.let { enumValue("TexState", it) },
        ) as Boolean

    fun recordKey(address: String): String = static("TexModelKt", "texRecordKey", address) as String

    // ----------------------------------------------------------------------------------------------- shield

    fun shieldInput(
        txIdHex: String?,
        pool: Int,
        value: Long,
    ): Any = construct("TexShieldInput", txIdHex, pool, value)

    @Suppress("LongParameterList")
    fun shieldShape(
        inputs: List<Any>,
        fee: Long,
        steps: Int = 1,
        transactionCount: Int = 1,
        paymentCount: Int = 0,
        totalFeeRequired: Long = fee,
    ): Any = construct("TexShieldShape", steps, transactionCount, inputs, paymentCount, fee, totalFeeRequired)

    fun matchShield(
        shape: Any,
        leg1RawIdHexes: Collection<String>,
    ): String? = static("TexShieldKt", "matchTexShield", shape, leg1RawIdHexes) as String?

    fun readShieldShape(
        bytes: ByteArray,
        transactionCount: Int,
        totalFeeRequired: Long,
    ): Any? = static("TexShieldKt", "readTexShieldShape", bytes, transactionCount, totalFeeRequired)

    fun shieldPlan(shape: Any): Any = construct("TexShieldPlan", shape, null)

    // ----------------------------------------------------------------------------------------------- store

    fun memoryStore(): Any = construct("InMemoryTexRecoveryStore")

    fun storedRecord(
        store: Any,
        key: String,
    ): Any? = call(store, "get", key)

    @Suppress("UNCHECKED_CAST")
    fun storeWrites(store: Any): List<Any> = prop(store, "writes") as List<Any>

    fun encode(record: Any): String = static("TexRecoveryStoreKt", "encodeTexRecord", record) as String

    fun decode(
        key: String,
        text: String,
    ): Any = static("TexRecoveryStoreKt", "decodeTexRecord", key, text)!!

    // ----------------------------------------------------------------------------------------------- monitor

    /** A TexSendMonitor over the fakes. [notices] receives every TexStatus the notice was posted for. */
    fun monitor(
        wallet: FakeTexWallet,
        tor: FakeTexTor,
        idle: () -> Boolean,
        store: Any = memoryStore(),
        notices: MutableList<Any> = Collections.synchronizedList(mutableListOf()),
        clock: () -> Long = { 0L },
    ): Any {
        val walletPort = proxy("TexWallet") { name, args -> wallet.answer(name, args) }
        val torPort = proxy("TexTor") { name, _ -> tor.answer(name) }
        val idlePort = proxy("TexIdle") { _, _ -> idle() }
        val noticePort =
            proxy("TexNotice") { _, args ->
                notices += args[0]!!
                Unit
            }
        val constructor =
            cls("TexSendMonitor").constructors.firstOrNull { it.parameterCount == MONITOR_PARAMETERS }
                ?: throw NoSuchMethodException("TexSendMonitor($MONITOR_PARAMETERS args)")
        val clockFunction: () -> Long = clock
        return constructor.newInstance(walletPort, torPort, idlePort, store, noticePort, clockFunction)
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun pass(monitor: Any): List<Any> = callSuspend(monitor, "pass") as List<Any>

    /** The status of the TEX send whose leg 2 is [leg2TxId] in [statuses]. */
    fun statusOf(
        statuses: List<Any>,
        leg2TxId: String,
    ): Any = statuses.first { prop(it, "leg2TxId") == leg2TxId }

    private fun proxy(
        interfaceName: String,
        answer: (String, Array<out Any?>) -> Any?,
    ): Any {
        val type = cls(interfaceName)
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "Fake$interfaceName"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> answer(method.name, args.orEmpty())
            }
        }
    }

    private const val MONITOR_PARAMETERS = 6
}

/** The rule numbers as F3 fixes them (TexRules). */
internal object TexRulesView {
    const val REORG_MARGIN = 10L
    const val CHECK_MARGIN = 2L
    const val SHIELD_THRESHOLD_ZAT = 20_000L
    const val MAX_LOOKUPS_PER_PROCESS = 20
    const val MAX_SHIELD_ATTEMPTS = 3
    const val UNKNOWN_EXPIRY_BOUND = 50L
}

/** The transparent pool in proposal.proto. */
internal const val TRANSPARENT_POOL = 1

/** A synthetic txid: 32 bytes counting up from [seed], as 64 hex characters (never the same read backwards). */
internal fun txid(seed: Int): String = (0 until TXID_HEX / 2).joinToString("") { "%02x".format((seed + it) and BYTE) }

/** [this] hex string's bytes in reverse order. */
internal fun String.reversedHex(): String = chunked(2).reversed().joinToString("")

private const val TXID_HEX = 64
private const val BYTE = 0xFF

/**
 * The wallet as a TEX test scripts it. [transactions] are TexTxFacts built with [TexApi.facts]; every call the monitor
 * makes is recorded in [calls].
 */
internal class FakeTexWallet {
    var tip: Long? = null
    var transactions: List<Any> = emptyList()

    /** What lookup answers; every address asked is in [lookups]. */
    var lookupAnswer: (String) -> Boolean = { false }
    val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** What proposeShield answers (a TexShieldPlan or null); [proposeCount] counts the calls. */
    var shieldPlan: () -> Any? = { null }
    var proposeCount = 0

    /** The ids and expiry the "created" shield is stored with, handed to onStored. */
    var shieldCreated: List<StoredTransaction> = listOf(StoredTransaction(txId = txid(0xA0), expiryHeight = 1_100))

    /** Every shield submitted (its plan). */
    val submitted: MutableList<Any> = Collections.synchronizedList(mutableListOf())

    /** Runs when a shield is handed over, before its onStored (a test reads the store here). */
    var atSubmit: () -> Unit = {}

    /** Runs after onStored, before the "submit" answers (a test reads the store here). */
    var afterStored: () -> Unit = {}

    /** Every leg handed to the server again (its txId). */
    val resubmitted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Suppress("UNCHECKED_CAST")
    fun answer(
        name: String,
        args: Array<out Any?>,
    ): Any? {
        calls += name
        return when (name) {
            "scannedTip" -> tip
            "transactions" -> transactions
            "lookup" -> {
                val address = args[0] as String
                lookups += address
                lookupAnswer(address)
            }
            "proposeShield" -> {
                proposeCount++
                shieldPlan()
            }
            "submitShield" -> {
                submitted += args[0]!!
                atSubmit()
                // args[1] is F2's onStored (a suspend lambda); args[2] the continuation. The monitor's record write
                // does not suspend, so it runs here, before the "submit".
                val onStored = args[1] as Function2<Any?, Continuation<Any?>, Any?>
                onStored.invoke(shieldCreated, args[2] as Continuation<Any?>)
                afterStored()
                SubmitResult.Success(shieldCreated.map { it.txId })
            }
            "resubmit" -> {
                resubmitted += TexApi.prop(args[0]!!, "txId") as String
                true
            }
            else -> throw UnsupportedOperationException("unexpected TexWallet call: $name")
        }
    }
}

/** The Tor switch as a TEX test scripts it. */
internal class FakeTexTor {
    var on = false
    var failing = false
    var explicitlyOff = false
    var turnOnFails = false
    var turnOns = 0

    fun answer(name: String): Any? =
        when (name) {
            "isOn" -> on
            "isFailing" -> failing
            "isExplicitlyOff" -> explicitlyOff
            "turnOn" -> {
                turnOns++
                if (turnOnFails) throw IllegalStateException("fake: Tor could not be turned on")
                on = true
                Unit
            }
            else -> throw UnsupportedOperationException("unexpected TexTor call: $name")
        }
}
