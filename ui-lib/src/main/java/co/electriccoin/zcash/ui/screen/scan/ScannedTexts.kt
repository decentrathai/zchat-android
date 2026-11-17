package co.electriccoin.zcash.ui.screen.scan

import co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode

/**
 * QR-7: which of the texts found in one picture the scanner uses. It picks only when they cannot name two different
 * people, and never guesses a recipient.
 *
 * - No text: [Pick.None].
 * - One distinct text: [Pick.One] with it, in every flow.
 * - New Chat ([ScanFlow.ZCHAT]): when every text reads as a contact ([ZchatContactCode.parse]) and they name one
 *   address with at most one relay key, the text that carries the key wins (an Invite screenshot shows the bare
 *   address and the code), else the first. Two addresses, two keys, or a text that is not a contact: [Pick.Ambiguous].
 * - Every other flow, and the generic scanner (`flow` null: Quantum Shield and the generic address scan): two or more
 *   distinct texts are [Pick.Ambiguous]. A money flow never chooses between texts.
 *
 * Nothing here logs: a text can be a seed backup.
 */
object ScannedTexts {
    sealed interface Pick {
        data class One(
            val text: String
        ) : Pick

        data object None : Pick

        data object Ambiguous : Pick
    }

    fun pick(
        flow: ScanFlow?,
        texts: List<String>
    ): Pick {
        val distinct = texts.distinct()
        return when {
            distinct.isEmpty() -> Pick.None
            distinct.size == 1 -> Pick.One(distinct.single())
            flow == ScanFlow.ZCHAT -> pickOneContact(distinct)
            else -> Pick.Ambiguous
        }
    }

    private fun pickOneContact(texts: List<String>): Pick {
        val contacts = texts.map { text -> text to (ZchatContactCode.parse(text) ?: return Pick.Ambiguous) }
        val addresses = contacts.map { it.second.zcashAddress }.distinct()
        val keys = contacts.mapNotNull { it.second.nostrPubkeyHex }.distinct()
        if (addresses.size != 1 || keys.size > 1) return Pick.Ambiguous
        val withKey = contacts.firstOrNull { it.second.nostrPubkeyHex != null }
        return Pick.One((withKey ?: contacts.first()).first)
    }
}
