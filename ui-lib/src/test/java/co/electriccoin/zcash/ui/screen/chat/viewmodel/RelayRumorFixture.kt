package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-421 (fix-all 2.13.0, train T6b; ZC-420-SPEC §14.5, §14.7): shared pieces of the ZC-421 view-model tests.
 *
 * - [RumorHook]: a fake of the rumor publisher NostrChatBridge.registerRumorPublisher(publish, rumorIdAt) installs. Its id
 *   depends on the payload, the contact's relay key and the rumor's second only (and on [RumorHook.salt], which stands
 *   for a rotated relay key), so the same three inputs answer the same id, as a real rumor does. The hook and its two
 *   functions do not exist at HEAD, so it is registered by name.
 * - [CheckedStore]: the shared [PaymentPrefs] behind one more proxy that answers getDisappearingTtl (the chat's timer),
 *   can refuse a checked write (commitPendingMessage), and logs every row write. Every other call goes to [PaymentPrefs],
 *   so its strict list of unexpected calls still holds.
 * - [RumorPair]: the two new row keys (relayRumorAtSec, relayRumorId) read and written through the row's own JSON, so the
 *   tests compile before the fields exist.
 *
 * Every key, id, text and address is synthetic.
 */
internal class RumorHook {
    data class Call(
        val payload: String,
        val key: String,
        val second: Long,
        val expected: String?,
        val storedAtCall: List<ZchatPreferences.PendingMessageData>,
    )

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    val idAsks: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    /** Stands for our relay key: another salt gives every rumor another id. */
    @Volatile var salt: String = ""

    @Volatile var acks: Int = 1

    @Volatile var readable: Int = 1

    /** The hook went away between the helper's check and its call: both functions answer null. */
    @Volatile var gone: Boolean = false

    /** While set, a publish never answers (the app is closed during it). */
    @Volatile var hold: CompletableDeferred<Unit>? = null

    /** The rows the store holds when the publisher is called. */
    @Volatile var store: () -> List<ZchatPreferences.PendingMessageData> = { emptyList() }

    fun idOf(
        payload: String,
        key: String,
        second: Long,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$salt|$key|$second|$payload".toByteArray())
        return "rid" + digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private val publish: suspend (String, String, Long, String?) -> NostrChatBridge.PublishResult? =
        { payload, key, second, expected ->
            calls += Call(payload, key, second, expected, store())
            if (gone) {
                null
            } else {
                val id = idOf(payload, key, second)
                if (expected != null && expected != id) {
                    null
                } else {
                    hold?.await()
                    NostrChatBridge.PublishResult(acks, id, readable)
                }
            }
        }

    private val rumorIdAt: (String, String, Long) -> String? = { payload, key, second ->
        idAsks += second
        if (gone) null else idOf(payload, key, second)
    }

    fun register() {
        val method =
            NostrChatBridge::class.java.methods.firstOrNull { it.name == "registerRumorPublisher" && it.parameterCount == 2 }
                ?: throw AssertionError("NostrChatBridge.registerRumorPublisher(publish, rumorIdAt) does not exist (ZC-421)")
        try {
            method.invoke(NostrChatBridge, publish, rumorIdAt)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    companion object {
        fun unregister() {
            NostrChatBridge::class.java.methods
                .firstOrNull { it.name == "unregisterRumorPublisher" && it.parameterCount == 0 }
                ?.invoke(NostrChatBridge)
        }
    }
}

/** [PaymentPrefs] with the chat's timer, a checked write that can be refused, and a log of every row write. */
internal class CheckedStore(
    val inner: PaymentPrefs,
    /** When set, the conversation id the store answers (the compose screen's store uses another one). */
    private val convId: String? = null,
) {
    @Volatile var ttl: ZchatPreferences.DisappearingTtl? = null
    val ttlReads = AtomicInteger()

    /** Each count refuses one checked write (commitPendingMessage throws, nothing is written). */
    val refuseCommits = AtomicInteger()

    /** Every row written (addPendingMessage and commitPendingMessage), in order. */
    val writes: MutableList<ZchatPreferences.PendingMessageData> = Collections.synchronizedList(mutableListOf())

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getOrCreateConversationId", "getOrCreateConversationIdForMessage" ->
                    if (convId != null) convId to false else forward(method, args)
                "getDisappearingTtl" -> {
                    ttlReads.incrementAndGet()
                    ttl
                }
                "commitPendingMessage" -> {
                    if (refuseCommits.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
                        throw IllegalStateException("synthetic: the pending row could not be written")
                    }
                    writes += args!![0] as ZchatPreferences.PendingMessageData
                    forward(method, args)
                }
                "addPendingMessage" -> {
                    writes += args!![0] as ZchatPreferences.PendingMessageData
                    forward(method, args)
                }
                else -> forward(method, args)
            }
        } as ZchatPreferences

    private fun forward(
        method: java.lang.reflect.Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(inner.proxy, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    val pending get() = inner.pending
}

/** The two row keys ZC-421 adds, through the row's JSON (absent before the fix: always null then). */
internal object RumorPair {
    private const val SEC = "relayRumorAtSec"
    private const val ID = "relayRumorId"

    fun secOf(row: ZchatPreferences.PendingMessageData?): Long? =
        row?.let { JSONObject(ZchatPreferences.PendingMessageData.toJson(it)) }?.let {
            if (it.has(SEC) && !it.isNull(SEC)) it.getLong(SEC) else null
        }

    fun idOf(row: ZchatPreferences.PendingMessageData?): String? =
        row?.let { JSONObject(ZchatPreferences.PendingMessageData.toJson(it)) }?.let {
            if (it.has(ID) && !it.isNull(ID)) it.getString(ID) else null
        }

    fun with(
        row: ZchatPreferences.PendingMessageData,
        sec: Long?,
        id: String?,
    ): ZchatPreferences.PendingMessageData {
        val json = JSONObject(ZchatPreferences.PendingMessageData.toJson(row))
        json.remove(SEC)
        json.remove(ID)
        if (sec != null) json.put(SEC, sec)
        if (id != null) json.put(ID, id)
        return ZchatPreferences.PendingMessageData.fromJson(json.toString())
    }
}

/**
 * How long a test waits so that a Retry falls in a later wall-clock second than the first try (a rumor made in the same
 * second is the same rumor). Longer than a second because this machine's wall clock steps back by up to 1.4 s (ZC-482).
 */
internal const val RETRY_IN_A_LATER_SECOND_MS = 2_500L

/** The text a payment request that cannot be sent again as the same request shows (ZC-421), reached by name. */
internal fun requestResendRefusedText(): String =
    runCatching {
        co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy::class.java
            .getField("PAYMENT_REQUEST_RESEND_REFUSED")
            .get(null) as String
    }.getOrElse { throw AssertionError("ChatSendCopy.PAYMENT_REQUEST_RESEND_REFUSED does not exist (ZC-421)") }
