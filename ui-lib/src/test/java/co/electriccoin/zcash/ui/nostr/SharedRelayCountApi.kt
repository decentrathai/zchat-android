package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.reflect.InvocationTargetException
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC-393 / ZC-397 (fix-all 2.13.0, train T5b; t5b-add SPECS §2.3, §2.6): the shared-relay count and the kept-wrap resend,
 * reached by name so the RED tests compile before they exist. Every call unwraps InvocationTargetException.
 *
 * - `NostrInboxManager.SendResult.readableAcks`: the relays that took the wrap and serve us (any of the three);
 *   `acks` becomes the relays every ZChat version reads (ZChat's relay, nos.lol).
 * - `NostrInboxManager.resendKept(rumorId, recipientPubkeyHex): SendResult?` (suspend): the kept wrap again, or null.
 * - `NostrRelayPool.publishCounted(json, tag): PublishCount(readable, shared)` and `republishKept(tag): PublishCount?`.
 * - `NostrRelayPool.Companion.sharedReadRelays(urls)`: the relays of a list that every ZChat version reads.
 */
internal object SharedRelayCountApi {
    /** Calls the suspend function [name] of [target] with [args] (the continuation is added here). */
    suspend fun callSuspend(
        target: Any,
        name: String,
        vararg args: Any?,
    ): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method =
                target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size + 1 }
                    ?: throw AssertionError("${target.javaClass.simpleName}.$name/${args.size} does not exist (ZC-393)")
            try {
                method.invoke(target, *args, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

    /** A SendResult's (or PublishResult's) readableAcks. */
    fun readableAcks(result: Any): Int {
        val getter =
            result.javaClass.methods.firstOrNull { it.name == "getReadableAcks" && it.parameterCount == 0 }
                ?: throw AssertionError("${result.javaClass.simpleName} has no readableAcks (ZC-393): acks is the only count")
        return getter.invoke(result) as Int
    }

    suspend fun resendKept(
        inbox: NostrInboxManager,
        rumorId: String,
        recipientPubkeyHex: String,
    ): NostrInboxManager.SendResult? = callSuspend(inbox, "resendKept", rumorId, recipientPubkeyHex) as NostrInboxManager.SendResult?

    /** The relays of [urls] that every ZChat version reads, as the pool counts them. */
    @Suppress("UNCHECKED_CAST")
    fun sharedReadRelays(urls: List<String>): Set<String> {
        val companion = NostrRelayPool::class.java.getField("Companion").get(null)
        val method =
            companion.javaClass.methods.firstOrNull { it.name == "sharedReadRelays" && it.parameterCount == 1 }
                ?: throw AssertionError("NostrRelayPool.sharedReadRelays does not exist (ZC-393)")
        return try {
            method.invoke(companion, urls) as Set<String>
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    fun identity(): NOSTRIdentity = NOSTRIdentity.fromSeed(ByteArray(64).also { SecureRandom().nextBytes(it) })

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** An inbox on [pool] started as [identity], with a collector that keeps every DM it hands over. */
    class Inbox(
        val pool: NostrRelayPool,
        val identity: NOSTRIdentity,
        scope: CoroutineScope,
    ) {
        val manager = NostrInboxManager(pool)
        val received: MutableList<NostrInboxManager.InboundDm> = CopyOnWriteArrayList()
        private val collector: Job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                manager.inbound.collect { dm ->
                    received += dm
                    manager.onDispatched()
                }
            }

        init {
            manager.start(identity)
        }

        fun stop() {
            collector.cancel()
            runCatching { manager.stop() }
        }

        /** How many DMs with [text] arrived. */
        fun count(text: String): Int = received.count { it.content == text }
    }
}
