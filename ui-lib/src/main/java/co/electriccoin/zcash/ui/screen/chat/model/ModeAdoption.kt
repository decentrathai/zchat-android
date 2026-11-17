package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZC-1478 (owner decision W-122; FIX_PLAN REVISION 1; FABLE_PLAN_CRITIQUE M1, M2, M3) — what this phone does with a
 * contact's authenticated mode change (ZMODE), decided from facts only. Reverses money guard #187 ("never adopt
 * Shielded") within these bounds:
 *
 *  - W-122: a mode change applies on BOTH phones, any direction, also to Shielded, with no Accept/Keep prompt. A mode
 *    the user picked by hand no longer blocks a newer change from the contact.
 *  - M3: of two switches that crossed on the way, the newer one wins on both phones. A contact's switch older than our
 *    own last switch ([ownSince]) is not adopted; our mode is sent to the contact again (free, over the relays) so the
 *    contact's phone follows ours. Equal times (rare) go to the cheaper mode on both phones ([cost]).
 *  - M2: Shielded is adopted only from a contact whose relay rumors carry the Ironwood capability ([peerIronwoodCap]
 *    true: ZChat 2.13). A ZChat 2.12.4 phone cannot read Shielded messages, and a contact with no record may be one, so
 *    their switch leaves the mode as it is (with a note that names the version it needs).
 *  - A free mode (Tunnel, Open) is adopted only while we hold the contact's relay key (the free transport works from
 *    our side). While the contact's new key waits for the user, a free mode is still adopted (as before W-122: the
 *    control was authenticated by its caller), but Shielded is not.
 *
 * M1 (what an adoption may pay) is the caller's: an adopted mode never starts an automatic key exchange or ZBOOT, and
 * the first send in a chat the contact moved to Shielded asks with the price ([movesToPaid]).
 */
object ModeAdoption {
    sealed interface Decision {
        /** Follow the contact: the chat's mode becomes [mode] (not pinned; only the user pins). */
        data class Adopt(
            val mode: ConversationMode,
        ) : Decision

        /** Our own last switch is newer: keep our mode and send it to the contact again, free over the relays. */
        data object SendOursAgain : Decision

        /** Shielded from a contact not known to run ZChat 2.13: keep our mode, with the note that says why. */
        data object KeepNeeds213 : Decision

        /** No relay channel for a free mode, or Shielded while the contact's new key waits for the user: keep our mode. */
        data object KeepUnchanged : Decision

        /** The chat is in that mode already. */
        data object AlreadyInMode : Decision
    }

    /**
     * @param currentMode this chat's mode on this phone now.
     * @param newMode the mode the contact switched to.
     * @param ownSince when the user last switched this chat on this phone (the since our ZMODE carried); 0 = never or
     * before this was recorded.
     * @param peerSince the contact's switch time (already clamped and past the replay guard).
     * @param peerIronwoodCap the newest relay rumor's capability: true = 2.13, false = 2.12.4 or older, null = none on
     * record.
     * @param holdsPeerRelayKey whether we hold the contact's relay key.
     * @param keyChanged whether the contact's new key waits for the user.
     */
    fun decide(
        currentMode: ConversationMode,
        newMode: ConversationMode,
        ownSince: Long,
        peerSince: Long,
        peerIronwoodCap: Boolean?,
        holdsPeerRelayKey: Boolean,
        keyChanged: Boolean,
    ): Decision {
        if (newMode == currentMode) return Decision.AlreadyInMode
        val oursIsNewer = peerSince < ownSince || (peerSince == ownSince && cost(currentMode) < cost(newMode))
        if (oursIsNewer) return Decision.SendOursAgain
        return when (newMode) {
            ConversationMode.VAULT ->
                when {
                    keyChanged -> Decision.KeepUnchanged
                    peerIronwoodCap == true -> Decision.Adopt(newMode)
                    else -> Decision.KeepNeeds213
                }
            ConversationMode.TUNNEL, ConversationMode.OPEN ->
                if (holdsPeerRelayKey) Decision.Adopt(newMode) else Decision.KeepUnchanged
        }
    }

    /**
     * M1 — whether a chat the contact moved to [mode] can pay from now on: Shielded pays per message, and a Tunnel whose
     * key exchange is not finished pays for its setup. Such a chat starts no automatic key exchange or ZBOOT, and its
     * first on-chain send asks with the price, until the user answers a price question or picks a mode.
     * ZC-1507: a Tunnel whose own ZBOOT was never marked sent ([ourZbootSent] false) still has a paid setup leg too.
     */
    fun movesToPaid(
        mode: ConversationMode,
        exchangeComplete: Boolean,
        ourZbootSent: Boolean = true,
    ): Boolean = mode == ConversationMode.VAULT || (mode == ConversationMode.TUNNEL && (!exchangeComplete || !ourZbootSent))

    /** The tie-break order: Open (free), Tunnel (paid setup), Shielded (paid per message). */
    private fun cost(mode: ConversationMode): Int =
        when (mode) {
            ConversationMode.OPEN -> 0
            ConversationMode.TUNNEL -> 1
            ConversationMode.VAULT -> 2
        }
}
