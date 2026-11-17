package co.electriccoin.zcash.ui.common.model

/**
 * K0 (fix-all 2.13.0) — which Keystone features this version offers. A flag, not code removal: 2.13.1 (F6, F7, F13,
 * D2) brings the Keystone signing flows back, upstream merges stay clean, and the navigation destinations stay
 * registered so a restored back stack never crashes.
 *
 * A Keystone account connected earlier keeps the upstream Wallet-tab paths: view, receive, Send and Shield, signed on
 * the device (with the firmware floor, KeystoneFirmwareGate).
 */
object KeystoneAvailability {
    /** Connect Keystone and the hardware-wallet promo. 2.13.0: closed; 2.13.1 re-opens with F6. */
    const val NEW_CONNECTIONS = false

    /**
     * Any chat send, chat payment or payment-request payment signed with a Keystone account. 2.13.0: closed at the
     * send chokepoint (CreateChunkedMessageProposalUseCase); 2.13.1 F6/F7 replace the refusal with the signing flow.
     */
    const val CHAT_SIGNING = false
}

/** K0-1 — thrown where a Keystone account would be imported while [KeystoneAvailability.NEW_CONNECTIONS] is off. */
class KeystoneConnectionsClosedException : IllegalStateException("Connecting a new Keystone is not available in this version")
