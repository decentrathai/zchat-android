package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldStatus

/** ZC-1479: a status line the chat body still shows, because it asks something of the user or names a cost. */
enum class ChatStatusLine {
    /** ZC-1275: an unfinished Tunnel setup goes on-chain and is paid. */
    TUNNEL_SETUP,

    /** Stranded Orchard funds whose way out (Ironwood) is already confirming: wait. */
    IRONWOOD_CONFIRMING,

    /** Value only in sealed Orchard: nothing can be sent to a peer on-chain until it moves. */
    STRANDED,

    /** Transparent (or legacy Sapling-only) value that should be shielded before a Shielded send. */
    NEEDS_SHIELDING,
}

/**
 * ZC-1479 + the in-chat part of ZC-1472 (owner, W-122 answer 3): the chat body shows only lines that need the user. A
 * healthy chat shows none in any mode: "shielded on-chain" and "end-to-end encrypted over NOSTR" are the header lock's
 * job now, and Extra Security in every status is the header chip's ([qsStatus] never adds a body line; its KEYS_DIFFER
 * and DOWNGRADED warnings are separate alerts). The wallet lines describe the Shielded (on-chain) transport only, as
 * the privacy card always did.
 */
object ChatStatusLines {
    @Suppress("UNUSED_PARAMETER")
    fun show(
        mode: ConversationMode,
        privacyStatus: PrivacyStatus,
        qsStatus: QuantumShieldStatus,
        tunnelHandshakeComplete: Boolean,
    ): List<ChatStatusLine> =
        buildList {
            if (TunnelSetupChip.shows(mode, tunnelHandshakeComplete)) add(ChatStatusLine.TUNNEL_SETUP)
            if (!mode.isNostrTransport) {
                when {
                    privacyStatus.isOrchardStranded && privacyStatus.isIronwoodConfirming -> add(ChatStatusLine.IRONWOOD_CONFIRMING)
                    privacyStatus.isOrchardStranded -> add(ChatStatusLine.STRANDED)
                    !privacyStatus.isFullyShielded -> add(ChatStatusLine.NEEDS_SHIELDING)
                }
            }
        }
}
