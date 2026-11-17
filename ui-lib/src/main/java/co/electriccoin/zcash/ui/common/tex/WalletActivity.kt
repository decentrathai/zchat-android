package co.electriccoin.zcash.ui.common.tex

import java.util.concurrent.atomic.AtomicInteger

/**
 * F3: what is in progress in this process right now, as the automatic TEX recovery must know it. Turning Tor on
 * restarts the wallet, and the recovery's shield is a send of its own, so both wait until nothing else is running
 * (owner, 25 Sep: no send, no private-move step, no call; PLAN_V2 also: no Keystone PCZT open).
 *
 * Counters, not flags: two sends at once are two entries. [track] always leaves, also on a throw or a cancel.
 */
object WalletActivity {
    enum class Kind {
        /** A send between the start of its create and its settle (ProposalDataSourceImpl's one submit path). */
        SEND,

        /** A step of the private Orchard move (D1) that is building or broadcasting. */
        PRIVATE_MOVE,
    }

    private val counts = Kind.entries.associateWith { AtomicInteger(0) }

    fun enter(kind: Kind) {
        counts.getValue(kind).incrementAndGet()
    }

    fun leave(kind: Kind) {
        counts.getValue(kind).updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun inProgress(kind: Kind): Int = counts.getValue(kind).get()

    /**
     * RESET-PAY (register ZC-886; RESET-LEFTOVERS-SPEC 4.9 RS-10): the gate a Reset ZCHAT closes before it begins. The
     * one submit path enters its counter and then reads this; [closeForReset] sets it and then reads the counters.
     * Each side writes first and reads second, so either the Reset sees the send or the send sees the Reset.
     */
    @Volatile
    var closing: Boolean = false
        private set

    /** Closes the gate, then answers whether no send and no private-move step is in progress (the Reset may go on). */
    fun closeForReset(): Boolean {
        closing = true
        return inProgress(Kind.SEND) == 0 && inProgress(Kind.PRIVATE_MOVE) == 0
    }

    /** Opens the gate again: a Reset that did not start. One that started leaves it closed until the process ends. */
    fun reopen() {
        closing = false
    }

    /** Runs [block] counted as [kind]. */
    inline fun <T> track(
        kind: Kind,
        block: () -> T
    ): T {
        enter(kind)
        try {
            return block()
        } finally {
            leave(kind)
        }
    }
}

/**
 * The idle rule of the automatic recovery: no send and no private-move step in progress ([WalletActivity]), no live
 * call, and no Keystone PCZT open. Each part is read at the moment of asking.
 */
class TexIdleRule(
    private val callLive: () -> Boolean,
    private val keystonePcztOpen: () -> Boolean,
) : TexIdle {
    override fun isIdle(): Boolean =
        WalletActivity.inProgress(WalletActivity.Kind.SEND) == 0 &&
            WalletActivity.inProgress(WalletActivity.Kind.PRIVATE_MOVE) == 0 &&
            !callLive() &&
            !keystonePcztOpen()
}
