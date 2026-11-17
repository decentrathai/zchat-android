package co.electriccoin.zcash.ui.screen.chat.model

import java.security.MessageDigest

/**
 * ZC-283 (fix-all 2.13.0, train T5b) — what each contact got of one status broadcast, kept on disk so the same status
 * is never paid for twice.
 *
 * [statusHash] names the status text and [peers] the contacts, both by a SHA-256 hash only: no address and no text is
 * written. A contact is [Outcome.MAYBE] from right before its memo is handed to the SDK (R4-1) and stays so unless the
 * send is proven: [Outcome.SENT] when it went out, removed when nothing was sent. A contact in [peers] is never sent
 * this status again. Pure: no clock, no store.
 */
data class StatusBroadcastRecord(
    val statusHash: String,
    val peers: Map<String, Outcome> = emptyMap(),
) {
    enum class Outcome(
        internal val code: Char,
    ) {
        /** The memo was sent (on-chain) or a relay took it. */
        SENT('S'),

        /** The memo was handed to the SDK and nothing proves it was not stored: it may still be mined. */
        MAYBE('M'),
    }

    /** This record with [peerHash] recorded as [outcome]. */
    fun with(
        peerHash: String,
        outcome: Outcome,
    ): StatusBroadcastRecord = if (peers[peerHash] == outcome) this else copy(peers = peers + (peerHash to outcome))

    /** This record with nothing recorded for [peerHash]. */
    fun without(peerHash: String): StatusBroadcastRecord = if (peerHash in peers) copy(peers = peers - peerHash) else this

    /** How many of [peerHashes] are recorded as [outcome]. */
    fun count(
        peerHashes: Collection<String>,
        outcome: Outcome,
    ): Int = peerHashes.count { peers[it] == outcome }

    /** `{"v":1,"s":"<status hash>","p":{"<peer hash>":"S"|"M",…}}`, peers in hash order. */
    fun encode(): String =
        peers
            .toSortedMap()
            .entries
            .joinToString(separator = ",", prefix = "{\"v\":1,\"s\":\"$statusHash\",\"p\":{", postfix = "}}") {
                "\"${it.key}\":\"${it.value.code}\""
            }

    companion object {
        private const val HASH_HEX_LENGTH = 32
        private const val HASH = "[0-9a-f]{$HASH_HEX_LENGTH}"
        private const val PEER = "\"($HASH)\":\"([SM])\""
        private val RECORD = Regex("""\{"v":1,"s":"($HASH)","p":\{((?:$PEER(?:,$PEER)*)?)\}\}""")
        private val PEER_ENTRY = Regex(PEER)

        /** The hash a record names the (already truncated) status [text] by. */
        fun statusHash(text: String): String = sha256Hex(text)

        /** The hash a record names a contact by; [canonicalAddress] is the contact's resolved address. */
        fun peerHash(canonicalAddress: String): String = sha256Hex(canonicalAddress)

        private fun sha256Hex(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(HASH_HEX_LENGTH)

        /** The record [raw] holds, or null when it is not exactly what [encode] writes (strict: nothing is guessed). */
        fun decode(raw: String): StatusBroadcastRecord? {
            val match = RECORD.matchEntire(raw) ?: return null
            val entries = PEER_ENTRY.findAll(match.groupValues[2]).toList()
            val peers =
                entries.associate { entry ->
                    entry.groupValues[1] to if (entry.groupValues[2] == "S") Outcome.SENT else Outcome.MAYBE
                }
            // A contact named twice is not something encode() writes.
            return if (peers.size == entries.size) StatusBroadcastRecord(match.groupValues[1], peers) else null
        }

        /**
         * The record a broadcast of [status] starts from, given the stored value [raw].
         *
         * - Nothing stored, or a readable record of another text: a new broadcast, nobody recorded.
         * - A readable record of this text: that record.
         * - An unreadable value: it stands for a broadcast of [previousStatus] (the status saved before this one was set)
         *   that may have reached every one of [currentPeerHashes]. Setting that same text again sends nothing (doubt is
         *   never a second payment); any other text is a new broadcast.
         */
        fun startingFrom(
            raw: String?,
            status: String,
            previousStatus: String?,
            currentPeerHashes: Collection<String>,
        ): StatusBroadcastRecord {
            val hash = statusHash(status)
            if (raw == null) return StatusBroadcastRecord(hash)
            val stored = decode(raw)
            return when {
                stored != null -> if (stored.statusHash == hash) stored else StatusBroadcastRecord(hash)
                previousStatus == status -> StatusBroadcastRecord(hash, currentPeerHashes.associateWith { Outcome.MAYBE })
                else -> StatusBroadcastRecord(hash)
            }
        }
    }
}
