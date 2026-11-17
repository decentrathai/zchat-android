package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZC-1464 — the relay-only tokens that tell a chat request's sender it was declined, and that the decline was undone:
 * `ZRCPT|DECLINED|<hash>` and `ZRCPT|RESTORED|<hash>`, where `<hash>` is [ZMSGProtocol.generateAddressHash] of the
 * decliner's own address (empty when it is not known yet). The receiver never reads the hash: who declined is the
 * seal-authenticated sender of the relay rumor, never a field of the text.
 *
 * compat-2124: the `ZRCPT|` prefix ([ZMSGConstants.Prefixes.RECEIPT]) is dropped silently by 2.12.4's relay reader
 * (v2.12.4 ChatViewModel.kt:692-694) and skipped on chain as a read receipt, so an older phone shows nothing. The tokens
 * are only ever published over the relays; an on-chain copy (a plaintext memo anyone who knows the address can send) is
 * never acted on.
 */
object RequestDecisionToken {
    enum class Kind { DECLINED, RESTORED }

    private const val DECLINED_PREFIX = ZMSGConstants.Prefixes.RECEIPT + "DECLINED|"
    private const val RESTORED_PREFIX = ZMSGConstants.Prefixes.RECEIPT + "RESTORED|"

    fun declined(ownAddress: String?): String = DECLINED_PREFIX + hashOf(ownAddress)

    fun restored(ownAddress: String?): String = RESTORED_PREFIX + hashOf(ownAddress)

    /** The token in [text], or null unless it is exactly one of the two tokens (prefix, then one field with no '|'). */
    fun parse(text: String): Kind? {
        val kind =
            when {
                text.startsWith(DECLINED_PREFIX) -> Kind.DECLINED
                text.startsWith(RESTORED_PREFIX) -> Kind.RESTORED
                else -> return null
            }
        val rest = text.substring(if (kind == Kind.DECLINED) DECLINED_PREFIX.length else RESTORED_PREFIX.length)
        return kind.takeIf { '|' !in rest }
    }

    private fun hashOf(address: String?): String = address?.takeIf { it.isNotBlank() }?.let(ZMSGProtocol::generateAddressHash).orEmpty()
}
