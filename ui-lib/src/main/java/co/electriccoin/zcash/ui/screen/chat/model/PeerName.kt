package co.electriccoin.zcash.ui.screen.chat.model

/**
 * GROUP-LABEL — how a screen names a peer: the order of the 1-to-1 chat header (the nickname typed in that header,
 * then the saved contact name), then a group roster row's snapshot of the contact name, else the short address.
 *
 * Nicknames and contact names are looked up by the canonical form of an address on BOTH sides ([keyed] keys every
 * stored entry by [canonical], and [name] looks the address up by [canonical]), so a contact saved under the address a
 * member was invited under also names the address the member accepted with, and the other way round.
 *
 * Every name here is local (typed by the user or the built-in team contact); nothing a peer sends is used.
 */
object PeerName {
    private const val SHORT_ADDRESS_MAX = 20
    private const val SHORT_HEAD = 8
    private const val SHORT_TAIL = 6
    private const val SHORT_FORM_MIN = 4

    /** First 8 + "..." + last 6 for an address longer than 20 characters (as getDisplayName), else the whole address. */
    fun shortAddress(address: String): String =
        if (address.length > SHORT_ADDRESS_MAX) {
            "${address.take(SHORT_HEAD)}...${address.takeLast(SHORT_TAIL)}"
        } else {
            address
        }

    /**
     * [entries] (address to name) keyed by [canonical] of the address; blank names are left out. When two entries
     * reach the same canonical address, the one stored under that canonical address itself wins, else the first.
     */
    fun keyed(
        entries: Iterable<Pair<String, String>>,
        canonical: (String) -> String,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val direct = HashSet<String>()
        for ((address, name) in entries) {
            if (name.isBlank()) continue
            val key = canonical(address)
            val isDirect = address.trim().lowercase() == key
            if (key !in out || (isDirect && key !in direct)) {
                out[key] = name
                if (isDirect) direct += key
            }
        }
        return out
    }

    /**
     * The name of [address]: its nickname, else its contact name (both maps pre-keyed by [keyed] with the same
     * [canonical]), else [rosterNickname]; null when none is set or all are blank.
     */
    fun name(
        address: String,
        nicknames: Map<String, String>,
        contacts: Map<String, String>,
        canonical: (String) -> String,
        rosterNickname: String? = null,
    ): String? {
        val key = canonical(address)
        return nicknames[key]?.takeIf { it.isNotBlank() }
            ?: contacts[key]?.takeIf { it.isNotBlank() }
            ?: rosterNickname?.takeIf { it.isNotBlank() }
    }

    /** [name], else [shortAddress]. */
    fun label(
        address: String,
        nicknames: Map<String, String>,
        contacts: Map<String, String>,
        canonical: (String) -> String,
        rosterNickname: String? = null,
    ): String = name(address, nicknames, contacts, canonical, rosterNickname) ?: shortAddress(address)

    /**
     * QA2 Q2-60 (W-137): the stored [name] of [address], trimmed, or null when it is blank or is only the address
     * itself: the whole address (any case) or a short form of it (a head of the address of at least 4 characters,
     * "..." or "…", a tail of at least 4, as 8...6 or 10…6). The pickers then show the short address once and the
     * avatar its hash initials, never "U1".
     */
    fun realName(
        name: String?,
        address: String,
    ): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val full = address.trim()
        if (full.isNotEmpty() && trimmed.equals(full, ignoreCase = true)) return null
        val parts = trimmed.split("...", "\u2026")
        val isShortForm =
            parts.size == 2 &&
                parts[0].length >= SHORT_FORM_MIN &&
                parts[1].length >= SHORT_FORM_MIN &&
                full.startsWith(parts[0], ignoreCase = true) &&
                full.endsWith(parts[1], ignoreCase = true)
        return if (isShortForm) null else trimmed
    }
}
