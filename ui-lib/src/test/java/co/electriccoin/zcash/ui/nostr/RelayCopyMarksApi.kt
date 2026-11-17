package co.electriccoin.zcash.ui.nostr

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC-420 (fix-all 2.13.0, train T6b; ZC-420-SPEC §3.3, §4.2): the relay-copy marks, reached by name so the RED tests
 * compile before they exist. Every call unwraps InvocationTargetException.
 *
 * - `RelayCopyMarks` (interface): `get(slot)`, `put(slot, value): Boolean` (a commit), `remove(slot)`, `slots()`.
 * - `RelayCopySlots` (object): `identity(peer)`, `groupKey(groupId, member)`, `groupInvite(groupId, member)`,
 *   `groupMessage(groupId, member, epoch, seq)`, `ofGroup(kind, groupId)`.
 * - `NostrChatBridge`: `registerRelayCopyMarks(marks)`, `unregisterRelayCopyMarks()`, `beginRelayCopy(slot, extra)`,
 *   `noteRelayCopy(slot, result, extra)`, `unconfirmedRelayCopies(prefix)`, `relayCopyExtra(slot)`,
 *   `forgetRelayCopy(slot)` and the suspend `repairRelayCopy(slot, recipientPubkeyHex, rebuild)`.
 *
 * A mark's value is "<rumorId>|<markedAtMs>|<extra>"; the rumor id is empty for a mark written before its publish.
 */
internal object RelayCopyMarksApi {
    private const val PKG = "co.electriccoin.zcash.ui.nostr."

    private fun marksInterface(): Class<*>? = runCatching { Class.forName(PKG + "RelayCopyMarks") }.getOrNull()

    private fun bridgeMethod(
        name: String,
        params: Int,
    ): Method? = NostrChatBridge::class.java.methods.firstOrNull { it.name == name && it.parameterCount == params }

    /** True once the bridge has the repair (the fix). */
    fun available(): Boolean = marksInterface() != null && bridgeMethod("repairRelayCopy", 4) != null

    private fun call(
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            bridgeMethod(name, args.size)
                ?: throw AssertionError("NostrChatBridge.$name/${args.size} does not exist (ZC-420)")
        return try {
            method.invoke(NostrChatBridge, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /**
     * An in-memory mark store behind the `RelayCopyMarks` interface. [failPuts] makes every put answer false and store
     * nothing (a commit that did not reach the disk); [slotsThreads] records the thread of each `slots()` call.
     */
    class MemoryMarks {
        val map: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())

        @Volatile var failPuts = false
        val puts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val removes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val slotsThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

        /** Called inside put, before the value is stored (a test can change the store at that moment). */
        @Volatile var onPut: ((slot: String, value: String) -> Unit)? = null

        val proxy: Any by lazy {
            val iface = marksInterface() ?: throw AssertionError("RelayCopyMarks does not exist (ZC-420)")
            Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { p, method, args ->
                val a = args ?: emptyArray()
                when (method.name) {
                    "get" -> map[a[0] as String]
                    "put" -> {
                        val slot = a[0] as String
                        val value = a[1] as String
                        onPut?.invoke(slot, value)
                        if (failPuts) {
                            false
                        } else {
                            puts += slot
                            map[slot] = value
                            true
                        }
                    }
                    "remove" -> {
                        removes += a[0] as String
                        map.remove(a[0] as String)
                        Unit
                    }
                    "slots" -> {
                        slotsThreads += Thread.currentThread()
                        synchronized(map) { map.keys.toSet() }
                    }
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a.getOrNull(0)
                    "toString" -> "MemoryRelayCopyMarks"
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
        }

        fun rumorId(slot: String): String? = map[slot]?.substringBefore('|')

        fun markedAtMs(slot: String): Long? = map[slot]?.split('|')?.getOrNull(1)?.toLongOrNull()

        fun extra(slot: String): String? = map[slot]?.let { v -> v.indexOf('|', v.indexOf('|') + 1).let { v.substring(it + 1) } }

        /** Moves the mark of [slot] back in time by [ms] (time passes, e.g. across a restart). */
        fun age(
            slot: String,
            ms: Long,
        ) {
            val value = map[slot] ?: throw AssertionError("fixture: no mark for the slot")
            val first = value.indexOf('|')
            val second = value.indexOf('|', first + 1)
            val at = value.substring(first + 1, second).toLong()
            map[slot] = value.substring(0, first + 1) + (at - ms) + value.substring(second)
        }
    }

    fun register(marks: MemoryMarks) {
        call("registerRelayCopyMarks", marks.proxy)
    }

    /** No-op when the bridge has no marks yet (RED), so a tear-down never fails for that reason. */
    fun unregister() {
        if (bridgeMethod("unregisterRelayCopyMarks", 0) != null) call("unregisterRelayCopyMarks")
    }

    fun begin(
        slot: String,
        extra: String,
    ) {
        call("beginRelayCopy", slot, extra)
    }

    fun note(
        slot: String,
        result: NostrChatBridge.PublishResult?,
        extra: String,
    ) {
        call("noteRelayCopy", slot, result, extra)
    }

    @Suppress("UNCHECKED_CAST")
    fun unconfirmed(prefix: String): List<String> = call("unconfirmedRelayCopies", prefix) as List<String>

    fun extraOf(slot: String): String? = call("relayCopyExtra", slot) as String?

    fun forget(slot: String) {
        call("forgetRelayCopy", slot)
    }

    /** The repair; answers the RelayRepair value's name (CONFIRMED, KEPT_UNCONFIRMED, RESENT_UNCONFIRMED, NOT_SENT, NOT_MARKED). */
    suspend fun repair(
        slot: String,
        recipientPubkeyHex: String,
        rebuild: suspend () -> String?,
    ): String {
        val method = bridgeMethod("repairRelayCopy", 4) ?: throw AssertionError("NostrChatBridge.repairRelayCopy does not exist (ZC-420)")
        val answer =
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                try {
                    method.invoke(NostrChatBridge, slot, recipientPubkeyHex, rebuild, continuation)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        return answer.toString()
    }

    /** `RelayCopySlots` by name. */
    object Slots {
        private fun call(
            name: String,
            vararg args: Any?,
        ): String {
            val cls =
                runCatching { Class.forName(PKG + "RelayCopySlots") }.getOrNull()
                    ?: throw AssertionError("RelayCopySlots does not exist (ZC-420)")
            val instance = cls.getField("INSTANCE").get(null)
            val method =
                cls.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                    ?: throw AssertionError("RelayCopySlots.$name/${args.size} does not exist (ZC-420)")
            return try {
                method.invoke(instance, *args) as String
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        fun identity(peer: String): String = call("identity", peer)

        fun groupKey(
            groupId: String,
            member: String,
        ): String = call("groupKey", groupId, member)

        fun groupInvite(
            groupId: String,
            member: String,
        ): String = call("groupInvite", groupId, member)

        fun groupMessage(
            groupId: String,
            member: String,
            epoch: Int,
            seq: Long,
        ): String = call("groupMessage", groupId, member, epoch, seq)

        fun ofGroup(
            kind: String,
            groupId: String,
        ): String = call("ofGroup", kind, groupId)
    }
}
