package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * RELAY-HEALTH (fix-all 2.13.0, train T5; SPEC.md §3, §5): the relay-health API, reached by name so the RED tests
 * compile before it exists. Every call unwraps InvocationTargetException, so an error thrown inside shows as itself.
 *
 * - `RelayCatalog` (object): `PUBLIC`, the compiled-in list of public relays, in order.
 * - `RelayHealthConfig` (no-argument constructor, `var` properties): the catalog, the clock (`nowMs`), the seen list's
 *   floor (`seenFloorSec`) and the §3.3 numbers.
 * - `RelayHealthPolicy(defaults, config, confirmOverrideMs)`: the pure rules of §3.2/§3.3 (signals, `state`, `tick`,
 *   `onProbeResult`, `commitSwap`, `floorSetUrls`).
 * - `RelayHealthDecision`: `Offline`, `Hold(reason)`, `Replace(slot, old, candidate)`, `ProbeCompat(slot, current)`.
 * - `RelaySetState(slot1, slot2, cooldownUntilMs, swapTimesMs, compatOutSinceMs)` and `RelaySetStore` (load/save).
 * - `NostrRelayPool(relayUrls, scope, sinceStore, health, relaySet)` with `activeUrls()`, `floorSetUrls()`,
 *   `healthTick(): Job?`, `resetRelaySetForDebug(): Job?` and `healthState(url)`.
 */
internal object RelayHealthApi {
    private const val PKG = "co.electriccoin.zcash.ui.nostr."

    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    const val DAY = 24 * HOUR

    fun cls(name: String): Class<*> = Class.forName(PKG + name)

    fun call(
        target: Any,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw NoSuchMethodException("${target.javaClass.simpleName}.$name/${args.size}")
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** A property of a Kotlin object by its getter (`slot1` -> getSlot1()). */
    fun prop(
        target: Any,
        name: String,
    ): Any? = call(target, "get" + name.replaceFirstChar { it.uppercase() })

    fun setProp(
        target: Any,
        name: String,
        value: Any?,
    ) {
        call(target, "set" + name.replaceFirstChar { it.uppercase() }, value)
    }

    fun catalog(): List<String> {
        val instance = cls("RelayCatalog").getField("INSTANCE").get(null)
        @Suppress("UNCHECKED_CAST")
        return prop(checkNotNull(instance), "PUBLIC") as List<String>
    }

    fun ownRelay(): String = NostrRelayPool::class.java.getField("OWN_RELAY").get(null) as String

    /** A config with [catalog], the fake [clock] and, for pool tests, no pause between two outbox re-publishes. */
    fun config(
        catalog: List<String>,
        clock: AtomicLong,
        seenFloorSec: () -> Long = { 0L },
    ): Any {
        val config = cls("RelayHealthConfig").getConstructor().newInstance()
        setProp(config, "catalog", catalog)
        setProp(config, "nowMs", { clock.get() })
        setProp(config, "seenFloorSec", seenFloorSec)
        setProp(config, "outboxGapMinMs", 0L)
        setProp(config, "outboxGapMaxMs", 0L)
        return config
    }

    fun policy(
        defaults: List<String>,
        config: Any,
        confirmOverrideMs: () -> Long? = { null },
    ): Any =
        try {
            cls("RelayHealthPolicy")
                .constructors
                .first { it.parameterCount == 3 }
                .newInstance(defaults, config, confirmOverrideMs)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    fun pool(
        urls: List<String>,
        scope: CoroutineScope,
        sinceStore: RelaySinceStore?,
        config: Any?,
        store: Any?,
    ): NostrRelayPool {
        val constructor =
            NostrRelayPool::class.java.constructors.firstOrNull { it.parameterCount == 5 }
                ?: throw NoSuchMethodException("NostrRelayPool(relayUrls, scope, sinceStore, health, relaySet)")
        return try {
            constructor.newInstance(urls, scope, sinceStore, config, store) as NostrRelayPool
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun activeUrls(pool: NostrRelayPool): List<String> = call(pool, "activeUrls") as List<String>

    @Suppress("UNCHECKED_CAST")
    fun floorSetUrls(pool: NostrRelayPool): List<String> = call(pool, "floorSetUrls") as List<String>

    /** Runs one health tick and waits for it (probe, swap, outbox) to finish. */
    suspend fun tick(pool: NostrRelayPool) {
        (call(pool, "healthTick") as Job?)?.join()
    }

    suspend fun resetRelaySet(pool: NostrRelayPool) {
        (call(pool, "resetRelaySetForDebug") as Job?)?.join()
    }

    fun healthState(
        pool: NostrRelayPool,
        url: String,
    ): String? = call(pool, "healthState", url)?.toString()

    fun relaySetState(
        slot1: String,
        slot2: String,
        cooldownUntilMs: Map<String, Long> = emptyMap(),
        swapTimesMs: List<Long> = emptyList(),
        compatOutSinceMs: Long? = null,
    ): Any =
        cls("RelaySetState")
            .constructors
            .first { it.parameterCount == 5 }
            .newInstance(slot1, slot2, cooldownUntilMs, swapTimesMs, compatOutSinceMs)

    /** A RelaySetStore that counts its writes. */
    class CountingSetStore(
        @Volatile var stored: Any? = null,
    ) {
        val saves = AtomicInteger()

        val proxy: Any by lazy {
            val iface = cls("RelaySetStore")
            Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { self, method, args ->
                when (method.name) {
                    "load" -> stored
                    "save" -> {
                        saves.incrementAndGet()
                        stored = args?.get(0)
                        null
                    }
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    "toString" -> "CountingSetStore"
                    else -> null
                }
            }
        }
    }

    /** A since store in memory, with the seed calls a swap makes (RelaySinceStore.seed, default no-op). */
    class MemorySinceStore : RelaySinceStore {
        val values = java.util.concurrent.ConcurrentHashMap<String, Long>()

        override fun sinceFor(relayUrl: String): Long? = values[relayUrl]

        override fun onEose(
            relayUrl: String,
            eoseWallSec: Long,
        ) = Unit
    }
}
