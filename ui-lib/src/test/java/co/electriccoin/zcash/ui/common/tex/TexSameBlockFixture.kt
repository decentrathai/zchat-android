package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.ui.common.tex.TexFixture.CHANGE
import co.electriccoin.zcash.ui.common.tex.TexFixture.EXCHANGE_TEX
import co.electriccoin.zcash.ui.common.tex.TexFixture.EXPIRY
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Proxy

/**
 * T3-fixup (G-F3-1, G-F3-2): two payments proposed in the same block share an expiry height, so the F3 pairing by
 * expiry alone cannot tell their legs apart. Here: a second TEX send ([LEG1B], [LEG2B], [EPHEMERAL_B]) and a plain
 * transparent payment ([PLAIN_T]) at the fixture's [EXPIRY], and leg 2's stored raw transaction, which names the
 * outpoint of leg 1 it spends. Every txid, address and raw byte here is synthetic.
 *
 * The new API is reached by name through [TexApi], so this file compiles before it exists.
 */
internal object TexSameBlockFixture {
    const val EPHEMERAL_B = "t1FixtureEphemeralAddressNotReal0002"
    const val PLAIN_T_ADDRESS = "t1FixturePlainPayeeAddressNotReal01"

    val LEG1B: String = txid(0x61)
    val LEG2B: String = txid(0x62)
    val PLAIN_T: String = txid(0x71)

    /**
     * A transparent-only transaction as the SDK stores it, in hex, in tx format [version] (4, 5 or 6): one input
     * spending output 0 of the transaction whose id bytes are [spentRawIdHex] (null: an input of an unrelated
     * transaction), one output.
     */
    fun rawSpending(
        spentRawIdHex: String?,
        version: Int = 5,
    ): String {
        val vin = "01" + (spentRawIdHex ?: txid(0x7E).reversedHex()) + "00000000" + "00" + "ffffffff"
        val vout = "01" + "e093040000000000" + "19" + "76a914" + "ab".repeat(20) + "88ac"
        val lockAndExpiry = "00000000" + "e8030000"
        return when (version) {
            // v4: header, version group; the lock time and expiry follow the outputs; then an empty Sapling and Sprout.
            4 -> "04000080" + "85202f89" + vin + vout + lockAndExpiry + "0000000000000000" + "00" + "00" + "00"
            // v5: header, version group, branch id, lock time, expiry; then an empty Sapling and Orchard bundle.
            5 -> "050000800a27a726b4d0d6c2" + lockAndExpiry + vin + vout + "000000"
            // v6 (NU6.3): as v5 with its own version group, plus an empty Ironwood bundle.
            6 -> "0600008098b684d8" + "b4d0d6c2" + lockAndExpiry + vin + vout + "00000000"
            else -> error("no fixture for tx format $version")
        }
    }

    /** A TexTxFacts that carries leg 2's raw transaction (the fix's eighth constructor parameter). */
    @Suppress("LongParameterList")
    fun factsWithRaw(
        txId: String,
        mined: Long?,
        expiry: Long = EXPIRY,
        outputs: List<Any>,
        rawTxHex: String?,
    ): Any = TexApi.construct("TexTxFacts", txId, txId.reversedHex(), true, false, mined, expiry, outputs, rawTxHex)

    fun leg1b(mined: Long? = EXPIRY - 10): Any =
        TexApi.facts(
            txId = LEG1B,
            mined = mined,
            expiry = EXPIRY,
            outputs =
                listOf(
                    TexApi.output(EPHEMERAL_B, isOwn = true, kind = "TRANSPARENT"),
                    TexApi.output(CHANGE, isOwn = true, kind = "OTHER"),
                ),
        )

    fun leg2b(mined: Long? = null): Any =
        TexApi.facts(
            txId = LEG2B,
            mined = mined,
            expiry = EXPIRY,
            outputs = listOf(TexApi.output(EXCHANGE_TEX, isOwn = false, kind = "TEX")),
        )

    /** Leg 2 of the fixture's first send, with its raw transaction spending [spends] (a leg 1's rawIdHex). */
    fun leg2WithRaw(
        txId: String,
        spends: String?,
        mined: Long? = null,
        version: Int = 5,
    ): Any =
        factsWithRaw(
            txId = txId,
            mined = mined,
            outputs = listOf(TexApi.output(EXCHANGE_TEX, isOwn = false, kind = "TEX")),
            rawTxHex = rawSpending(spends, version),
        )

    /** A plain payment to a transparent address, proposed in the same block (same expiry). */
    fun plainTransparent(mined: Long? = null): Any =
        TexApi.facts(
            txId = PLAIN_T,
            mined = mined,
            expiry = EXPIRY,
            outputs = listOf(TexApi.output(PLAIN_T_ADDRESS, isOwn = false, kind = "TRANSPARENT")),
        )

    fun plainTransparentWithRaw(mined: Long? = null): Any =
        factsWithRaw(
            txId = PLAIN_T,
            mined = mined,
            outputs = listOf(TexApi.output(PLAIN_T_ADDRESS, isOwn = false, kind = "TRANSPARENT")),
            rawTxHex = rawSpending(null),
        )

    /** The TEX send status [txId] belongs to, as the transaction detail finds it (forTransaction), or null. */
    @Suppress("UNCHECKED_CAST")
    fun statusFor(
        statuses: List<Any>,
        txId: String,
    ): Any? = TexApi.static("TexSendMonitorKt", "forTransaction", statuses, txId)

    /** What the monitor's last completed pass knew (the value of its `check` flow), or null before one completed. */
    fun check(monitor: Any): Any? = (TexApi.prop(monitor, "check") as StateFlow<*>).value

    /** Whether the transaction detail of [txId] may offer Send again, given the monitor's [check]. */
    fun sendAgainAllowed(
        txId: String,
        check: Any?,
    ): Boolean = TexApi.static("TexModelKt", "texSendAgainAllowed", txId, check) as Boolean

    /**
     * A TexSendMonitor whose wallet answers through [wallet] except that `transactions` throws while [failing] says
     * so (a pass that never completes).
     */
    fun monitorWithFailingReads(
        wallet: FakeTexWallet,
        failing: () -> Boolean,
    ): Any {
        val walletPort =
            proxy("TexWallet") { name, args ->
                if (name == "transactions" && failing()) throw IllegalStateException("fake: the wallet cannot be read")
                wallet.answer(name, args)
            }
        val tor = FakeTexTor().apply { on = true }
        val torPort = proxy("TexTor") { name, _ -> tor.answer(name) }
        val idlePort = proxy("TexIdle") { _, _ -> true }
        val noticePort = proxy("TexNotice") { _, _ -> Unit }
        val constructor = TexApi.cls("TexSendMonitor").constructors.first { it.parameterCount == MONITOR_PARAMETERS }
        val clock: () -> Long = { 0L }
        return constructor.newInstance(walletPort, torPort, idlePort, TexApi.memoryStore(), noticePort, clock)
    }

    private fun proxy(
        interfaceName: String,
        answer: (String, Array<out Any?>) -> Any?,
    ): Any {
        val type = TexApi.cls(interfaceName)
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
