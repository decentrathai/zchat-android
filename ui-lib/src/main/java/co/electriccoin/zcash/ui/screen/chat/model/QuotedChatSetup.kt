package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZC-1272 — what the one price question of a chat covered, leg by leg (zatoshi, the whole transaction), kept on disk from
 * Pay until each leg is paid. A null leg was not in the question and is never paid under it. Pay never writes the standing
 * handshake consent: this record is for one contact only.
 *
 * ZC-1348: [account] is the account that answered the question (GetDefaultUnifiedAddressUseCase.accountKeyOf). The store
 * outlives an account switch and a restore, and no other account's wallet pays under this answer. Every writer names it.
 */
data class QuotedChatSetup(
    val kex: Long? = null,
    val message: Long? = null,
    val zboot: Long? = null,
    val account: String? = null,
) {
    val isEmpty: Boolean get() = kex == null && message == null && zboot == null

    fun encode(): String =
        (listOf(kex, message, zboot).map { it?.toString().orEmpty() } + listOfNotNull(account)).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "|"
        private const val LEGS = 3

        /** Null for nothing stored or for anything that does not read as three legs and an account: nothing is covered. */
        fun decode(stored: String?): QuotedChatSetup? {
            val parts = stored?.split(SEPARATOR)?.takeIf { it.size == LEGS || it.size == LEGS + 1 } ?: return null
            val legs = parts.take(LEGS).map { part -> if (part.isEmpty()) null else part.toLongOrNull() ?: return null }
            return QuotedChatSetup(legs[0], legs[1], legs[2], parts.getOrNull(LEGS)?.takeIf { it.isNotEmpty() }).takeUnless { it.isEmpty }
        }
    }
}
