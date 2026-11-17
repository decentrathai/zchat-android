package co.electriccoin.zcash.ui.nostr

import java.util.concurrent.atomic.AtomicLong

/**
 * T5 fixup (fix-all 2.13.0, train T5; REVIEW_CROSS.md findings 7 and 8): [RelayHealthPolicy] by name on a fake clock,
 * for the fixup's policy tests. The same calls RelayHealthPolicyTest makes through its private driver; that class is
 * not changed. Synthetic urls.
 */
internal class HealthPolicyDriver(
    catalog: List<String> = CATALOG,
) {
    val clock = AtomicLong(T0)
    private val raw: Any = RelayHealthApi.policy(DEFAULTS, RelayHealthApi.config(catalog, clock))

    val now: Long get() = clock.get()

    fun at(t: Long): HealthPolicyDriver {
        clock.set(t)
        return this
    }

    private fun signal(
        name: String,
        url: String,
    ) {
        RelayHealthApi.call(raw, name, url, now)
    }

    fun connectOk(url: String) = signal("onConnectOk", url)

    fun connectFail(url: String) = signal("onConnectFail", url)

    fun disconnected(url: String) = signal("onDisconnected", url)

    fun reqSent(url: String) = signal("onReqSent", url)

    fun eose(url: String) = signal("onEose", url)

    fun event(url: String) = signal("onEvent", url)

    fun closed(
        url: String,
        reason: String,
    ): Boolean = RelayHealthApi.call(raw, "onClosed", url, reason, now) as Boolean

    fun ok(
        url: String,
        ok: Boolean,
        reason: String?,
    ) {
        RelayHealthApi.call(raw, "onOk", url, ok, reason, now)
    }

    /** A relay that opened and answered a REQ with an EOSE just now. */
    fun up(vararg urls: String) =
        urls.forEach {
            connectOk(it)
            reqSent(it)
            eose(it)
        }

    /** A fresh REQ → EOSE on each of [urls] (the watchdog's re-subscribe on a live socket). */
    fun fresh(vararg urls: String) =
        urls.forEach {
            reqSent(it)
            eose(it)
        }

    fun state(url: String): String = RelayHealthApi.call(raw, "state", url, now).toString()

    fun tick(): Any = checkNotNull(RelayHealthApi.call(raw, "tick", now))

    fun probe(
        url: String,
        ok: Boolean,
    ): Boolean = RelayHealthApi.call(raw, "onProbeResult", url, ok, now) as Boolean

    fun commit(
        slot: Int,
        url: String,
    ): Any = checkNotNull(RelayHealthApi.call(raw, "commitSwap", slot, url, now))

    @Suppress("UNCHECKED_CAST")
    fun active(): List<String> = RelayHealthApi.call(raw, "activeUrls") as List<String>

    companion object {
        const val OWN = "wss://own.test"
        const val P = "wss://primal.test"
        const val N = "wss://noslol.test"
        const val M = "wss://mom.test"
        const val S = "wss://social.test"
        val DEFAULTS = listOf(OWN, P, N)
        val CATALOG = listOf(P, N, M, S)
        const val T0 = 1_790_000_000_000L

        fun kind(decision: Any): String = decision.javaClass.simpleName

        fun isReplace(
            decision: Any,
            slot: Int,
            old: String,
            candidate: String,
        ): Boolean =
            kind(decision) == "Replace" &&
                RelayHealthApi.prop(decision, "slot") == slot &&
                RelayHealthApi.prop(decision, "old") == old &&
                RelayHealthApi.prop(decision, "candidate") == candidate

        fun isSwapOrProbe(decision: Any): Boolean = kind(decision) == "Replace" || kind(decision) == "ProbeCompat"
    }
}
