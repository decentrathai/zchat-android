package co.electriccoin.zcash.ui.common.sendattempt

import java.lang.reflect.Method

/**
 * ZC-114 (fix-all 2.13.0, train T5b): the height-aware chain timing (ChainTiming, NetworkUpgradeHeights) as the ZC-114
 * tests reach it, by name, so they compile before it exists (the RED commit). Every call fails with
 * ClassNotFoundException / NoSuchMethodException until the fix adds it.
 *
 * Heights are synthetic: a test's NU7 activation height is a made-up number, never a real one (no NU7 height is set
 * anywhere yet). Tests that change [current] or [lastTip] put back what they found in their @After ([Saved]).
 */
internal object ChainTimingApi {
    private const val PKG = "co.electriccoin.zcash.ui.common.sendattempt"

    fun timing(): Class<*> = Class.forName("$PKG.ChainTiming")

    fun heightsClass(): Class<*> = Class.forName("$PKG.NetworkUpgradeHeights")

    private fun instance(): Any = checkNotNull(timing().getField("INSTANCE").get(null))

    /** NetworkUpgradeHeights(nu7 = [nu7]): the table with NU7 active from [nu7] on (null: no NU7 height). */
    fun heights(nu7: Long?): Any = heightsClass().getConstructor(Long::class.javaObjectType).newInstance(nu7)

    fun nu7Of(heights: Any): Long? = heightsClass().getMethod("getNu7").invoke(heights) as Long?

    /** A row of the table ChainTiming ships ("MAINNET", "TESTNET"). */
    fun row(name: String): Any {
        val getter = timing().methods.firstOrNull { it.name == "get$name" && it.parameterCount == 0 }
        return checkNotNull(getter?.invoke(instance()) ?: timing().getField(name).get(null))
    }

    /** ChainTiming.forNetwork(network). */
    fun forNetwork(network: Any): Any = call("forNetwork", network)!!

    /** A constant of ChainTiming, such as PRE_NU7_BLOCK_SECONDS. */
    fun constant(name: String): Long = (timing().getField(name).get(null) as Number).toLong()

    var current: Any
        get() = checkNotNull(timing().getMethod("getCurrent").invoke(instance()))
        set(value) {
            timing().getMethod("setCurrent", heightsClass()).invoke(instance(), value)
        }

    var lastTip: Long?
        get() = timing().getMethod("getLastTip").invoke(instance()) as Long?
        set(value) {
            timing().getMethod("setLastTip", Long::class.javaObjectType).invoke(instance(), value)
        }

    /** The ChainTiming function [name] with every parameter given (the table last). */
    fun call(
        name: String,
        vararg args: Any?,
    ): Any? = method(timing(), name, args.size).invoke(instance(), *args)

    /** SendAttemptRules.minutesUntilDead(expiry, tip, upgrades). */
    fun minutesUntilDead(
        expiry: Long,
        tip: Long,
        upgrades: Any,
    ): Int = method(SendAttemptRules::class.java, "minutesUntilDead", 3).invoke(SendAttemptRules, expiry, tip, upgrades) as Int

    /** ChainView(scannedTip, transactions, claimedElsewhere, upgrades): the chain read with the table it is judged by. */
    fun chainView(
        tip: Long?,
        transactions: List<OwnTransaction>,
        claimedElsewhere: Set<String>,
        upgrades: Any,
    ): ChainView {
        val heights = heightsClass()
        val constructor =
            ChainView::class.java.constructors.firstOrNull {
                it.parameterCount == CHAIN_VIEW_PARAMETERS && it.parameterTypes.last() == heights
            } ?: throw NoSuchMethodException("ChainView(scannedTip, transactions, claimedElsewhere, upgrades)")
        return constructor.newInstance(tip, transactions, claimedElsewhere, upgrades) as ChainView
    }

    private fun method(
        owner: Class<*>,
        name: String,
        count: Int,
    ): Method =
        owner.methods.firstOrNull { it.name == name && it.parameterCount == count && !it.isSynthetic }
            ?: throw NoSuchMethodException("${owner.simpleName}.$name/$count")

    /** What a test found in ChainTiming's process-wide state, put back by [restore] (nothing to put back at RED). */
    class Saved private constructor(
        private val current: Any?,
        private val lastTip: Long?,
    ) {
        fun restore() {
            current?.let {
                ChainTimingApi.current = it
                ChainTimingApi.lastTip = lastTip
            }
        }

        companion object {
            fun take(): Saved =
                runCatching { Saved(ChainTimingApi.current, ChainTimingApi.lastTip) }.getOrElse { Saved(null, null) }
        }
    }

    private const val CHAIN_VIEW_PARAMETERS = 4
}
