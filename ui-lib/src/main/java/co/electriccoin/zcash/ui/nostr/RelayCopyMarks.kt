package co.electriccoin.zcash.ui.nostr

/**
 * ZC-420 — the marks of free-first relay copies that a readable relay took and neither ZChat's relay nor nos.lol (the
 * relays every ZChat version reads) took. Local to 2.13.0: the service keeps them in the encrypted held-reply store
 * ("relaycopy:<slot>", ZchatPreferences), and [NostrChatBridge] reads and writes them through this interface. A value is
 * "<rumorId>|<markedAtMs>|<extra>": the rumor id is empty for a mark written before its publish, and the extra may hold
 * "|" (a value is split at its first two "|" only).
 */
interface RelayCopyMarks {
    /** The mark of [slot], or null. */
    fun get(slot: String): String?

    /** Writes the mark with commit(); false when it did not reach the disk. */
    fun put(
        slot: String,
        value: String,
    ): Boolean

    /** Removes the mark with apply(): a lost removal costs one more free copy at most. */
    fun remove(slot: String)

    /** Every marked slot. */
    fun slots(): Set<String>
}

/**
 * ZC-420 — the slots of the marks, in clear text (they are stored encrypted). A group id and an address hold no "|".
 * The member of a group slot is the address string the send site holds; the epoch and the sequence number of a message
 * slot are its last two fields.
 */
object RelayCopySlots {
    const val IDENTITY = "zb"
    const val GROUP_KEY = "gk"
    const val GROUP_INVITE = "gi"
    const val GROUP_MESSAGE = "gm"

    // ZC-420-G R-1 (sealed route): the sealed removal notice to a removed member with no key exchange; never a key.
    const val GROUP_REMOVAL = "gr"

    /** Our relay identity (a ZBOOT or a rotation announce) to [peer]; the extra is the relay key it carries. */
    fun identity(peer: String): String = "$IDENTITY|$peer"

    /** The contact of an identity slot, or null for another kind. */
    fun peerOf(slot: String): String? = slot.takeIf { it.startsWith("$IDENTITY|") }?.substring(IDENTITY.length + 1)

    fun groupKey(
        groupId: String,
        member: String,
    ): String = "$GROUP_KEY|$groupId|$member"

    fun groupInvite(
        groupId: String,
        member: String,
    ): String = "$GROUP_INVITE|$groupId|$member"

    /** The sealed removal notice to [member] (the row it went to); the extra is "<key number>|<removed address>". */
    fun groupRemoval(
        groupId: String,
        member: String,
    ): String = "$GROUP_REMOVAL|$groupId|$member"

    fun groupMessage(
        groupId: String,
        member: String,
        epoch: Int,
        seq: Long,
    ): String = "$GROUP_MESSAGE|$groupId|$member|$epoch|$seq"

    /** The prefix every slot of [kind] in [groupId] starts with (what a group's repair lists by). */
    fun ofGroup(
        kind: String,
        groupId: String,
    ): String = "$kind|$groupId|"

    /** The kind of [slot] ("zb", "gk", "gi", "gm", "gr"): what a log line may name (a slot holds an address or a group id). */
    fun kindOf(slot: String): String = slot.substringBefore('|')
}

/** ZC-420 — what [NostrChatBridge.repairRelayCopy] did. */
enum class RelayRepair {
    /** A relay every version reads holds the copy now: the mark is gone. */
    CONFIRMED,

    /** Nothing new was made: the kept wrap is still not on a shared relay, a repair of the slot runs, or a newer copy owns it. */
    KEPT_UNCONFIRMED,

    /** One rebuilt copy went out and only a public relay took it: the mark names it. */
    RESENT_UNCONFIRMED,

    /** Nothing was sent (no rebuild, or no relay took it): the mark stays. */
    NOT_SENT,

    /** No mark for the slot, or no mark store. */
    NOT_MARKED,
}

/** ZC-420 — one mark's value. */
internal data class RelayCopyMark(
    val rumorId: String,
    val markedAtMs: Long,
    val extra: String,
) {
    fun encode(): String = "$rumorId|$markedAtMs|$extra"

    companion object {
        /** Splits at the first two "|" only (the extra may hold more); null for a value that is not a mark. */
        fun decode(value: String?): RelayCopyMark? {
            if (value == null) return null
            val first = value.indexOf('|')
            if (first < 0) return null
            val second = value.indexOf('|', first + 1)
            if (second < 0) return null
            val at = value.substring(first + 1, second).toLongOrNull() ?: return null
            return RelayCopyMark(value.substring(0, first), at, value.substring(second + 1))
        }
    }
}
