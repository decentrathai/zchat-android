package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptState

/**
 * ZC-1017 (decided under W-57, 15 Oct) — a paid key exchange that arrives in a chat this phone holds as Open.
 *
 * An Open chat never pays. Such a key exchange waits on the Accept hold (R2-2 (a)) and is offered as a Tunnel chat:
 * "Accept and pay" switches the chat to Tunnel first and then pays the one answer (a KEXACK) for the quoted amount;
 * "Keep Open" pays nothing, keeps the chat Open and the hold (so nothing pays later by itself). The phone remembers the
 * decision for that key in one record ([ZchatPreferences.getOpenChatKexOffer]): "kept|<key>" or "accepted|<key>".
 */
object OpenChatTunnelOffer {
    /** What the chat screen shows. */
    enum class Stage {
        /** No Tunnel offer (none held, a chat that is not Open, Keep Open, or the answer is confirmed). */
        NONE,

        /** The offer, with Accept and Keep Open. */
        OFFERED,

        /** Accepted: the answer is on its way and not confirmed yet; no buttons. */
        PAYING,
    }

    private const val KEPT = "kept"
    private const val ACCEPTED = "accepted"

    fun keptOpen(key: String): String = "$KEPT|$key"

    fun accepted(key: String): String = "$ACCEPTED|$key"

    /** True when [record] says Keep Open for [key]. */
    fun isKeptOpen(
        record: String?,
        key: String,
    ): Boolean = record == keptOpen(key)

    /** The key [record] says was accepted, or null. */
    fun acceptedKey(record: String?): String? = record?.takeIf { it.startsWith("$ACCEPTED|") }?.substringAfter('|')

    /**
     * The stage after Accept, from the answer's send-attempt entry ([entryState]; null: no entry), whether an answer to
     * this contact is being sent now ([inFlight]), and whether the answer's sent-marker names the accepted key ([acked]).
     * Mined: confirmed, no offer. Proven not sent: no offer (the "Key exchange not sent" notice and its Retry take over).
     * Any other entry, or an answer being sent: paying. No entry: confirmed and gone past the reorg depth when the marker
     * stands, else nothing was sent and the offer is shown again.
     */
    fun afterAccept(
        entryState: SendAttemptState?,
        inFlight: Boolean,
        acked: Boolean,
    ): Stage =
        when {
            entryState == SendAttemptState.MINED -> Stage.NONE
            entryState == SendAttemptState.NOT_SENT -> Stage.NONE
            entryState != null -> Stage.PAYING
            inFlight -> Stage.PAYING
            acked -> Stage.NONE
            else -> Stage.OFFERED
        }

    /**
     * Accept is enabled only when what can pay another person ([peerSendableZat]) covers the quote ([quoteZat]) by the
     * ZC-1417 rule ([spendableCovers]): exactly, or with a change the SDK accepts.
     */
    fun canAccept(
        peerSendableZat: Long,
        quoteZat: Long,
    ): Boolean = spendableCovers(peerSendableZat, quoteZat)

    /**
     * ZC-1814, ZC-1845 (QA2 k1b2) — the figure of the banner's not-enough line (chat_open_tunnel_offer_needs_funds): what the
     * wallet must reach to Accept ([spendableNeed]), the price or above it the price plus the marginal fee, in [locale]
     * ("0.00022" for an 18,000 wallet and the 17,000 price). Never the price alone while Accept is off for a wallet that
     * holds more.
     */
    fun needsFundsFigure(
        peerSendable: cash.z.ecc.android.sdk.model.Zatoshi,
        quote: cash.z.ecc.android.sdk.model.Zatoshi,
        locale: java.util.Locale,
    ): String = PaidActionCopy.zec(spendableNeed(peerSendable, quote), locale)
}
