package co.electriccoin.zcash.ui.screen.chat.view

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus

// User-facing privacy and delivery text that used to sit inside composables and view models, kept here so
// a JVM test (UserFacingPrivacyClaimsTest) can pin it against what the transports really do.

/**
 * Mode label used in the chat's system notes ("You switched this chat to …"). VAULT displays as "Shielded".
 *
 * This is the pinned English wording (UserFacingPrivacyClaimsTest: Open goes through three relays, not
 * one). The app renders [modeNoteLabelRes] instead, whose English values are byte-for-byte these — a
 * rule SpanishChatSurfacesAndMoreTextTest keeps.
 */
internal fun modeNoteLabel(mode: ConversationMode): String =
    when (mode) {
        ConversationMode.VAULT -> "Shielded (on-chain)"
        ConversationMode.TUNNEL -> "Tunnel (a secure handshake, then free via relays)"
        ConversationMode.OPEN -> "Open (free, via relays)"
    }

/** The same label as a string resource, so the system notes reach a Spanish phone in Spanish. */
internal fun modeNoteLabelRes(mode: ConversationMode): Int =
    when (mode) {
        ConversationMode.VAULT -> R.string.chat_mode_label_vault
        ConversationMode.TUNNEL -> R.string.chat_mode_label_tunnel
        ConversationMode.OPEN -> R.string.chat_mode_label_open
    }

/** Screen-reader text of a message's delivery tick; the app shows chat_a11y_status_* (same English). */
internal fun messageStatusDescription(status: MessageStatus): String? =
    when (status) {
        MessageStatus.SENT -> "Sent to the network"
        MessageStatus.CONFIRMED -> "On the blockchain"
        else -> null
    }

/**
 * Chip on an incoming message in an encrypted chat that was not signed with the contact's key. The app shows the
 * resource chat_unverified_sender (same English, and Spanish); this copy is kept for the privacy-claims check.
 */
internal const val UNVERIFIED_SENDER_CHIP = "Sender not verified: this message wasn't signed with your contact's secure key."

/** Picker hint when Open is greyed out because the contact's chat key is not stored yet. */
internal const val OPEN_NEEDS_KEY_HINT =
    "Needs your contact's chat key first: scan or paste their ZCHAT contact code, or switch to Tunnel to " +
        "exchange it (costs ZEC; a contact on an older ZChat can't finish it until they update). Then Open " +
        "becomes available."

/** Body of the "Rotate your NOSTR key?" confirm dialog. */
internal const val ROTATION_CONFIRM_BODY =
    "This generates a fresh messaging key and announces it to each of your relay contacts. " +
        "The announcement is signed by your existing identity, so your contacts' apps adopt " +
        "the new key automatically and the chat continues — no re-verification needed. It's " +
        "sent free over the relay; a contact who's offline is re-synced on-chain when you next " +
        "reach them. The new key activates immediately for both sending and receiving.\n\n" +
        "Rotation hasn't been tested with contacts on older ZChat versions. If a contact hasn't updated, wait."

/** Body of the onboarding "What is ZEC?" dialog. */
internal const val WHAT_IS_ZEC_BODY =
    "ZEC is the currency of the Zcash blockchain — the technology that powers ZCHAT.\n\n" +
        "You can chat for FREE through Nostr relays, which can't read your messages — no ZEC required. " +
        "ZEC is only needed for Shielded messages, which travel inside Zcash transactions (the most " +
        "private mode) and cost at least 0.00017 ZEC each.\n\n" +
        "To add ZEC, a friend can send you some, or you can buy a small amount on a crypto " +
        "exchange or swap in-app."

/**
 * (title, description) rows of More → What ZCHAT Protects.
 *
 * This is the pinned English (UserFacingPrivacyClaimsTest). The dialog renders [SECURITY_DIALOG_ROWS] and
 * security_dialog_limitations instead, whose English values are byte for byte these (SecurityDialogResourcesTest).
 */
internal val WHAT_ZCHAT_PROTECTS_ITEMS: List<Pair<String, String>> =
    listOf(
        // Round 8 D9 (DECISIONS_NIGHT §3/§4): a Shielded chat's first messages to a new contact carry no ZChat layer
        // (ChatViewModel.appLayerE2EPending), so the double encryption starts once the contact has answered the key
        // exchange. Ironwood is a value pool of the Orchard protocol and SDK 3.0.0 proves it with Halo 2.
        "Double encryption" to
            "AES-256-GCM inside Zcash shielded transactions (Halo 2 zk-SNARKs), once your contact has answered the " +
            "key exchange. Attacker must break BOTH independently.",
        "Per-message keys" to
            "Symmetric ratchet derives a unique key per message, so compromising one message key does not reveal others within the same session.",
        "Replay protection" to
            "Counter-based nonces + seen-counter tracking prevent message replay within each app session.",
        "No metadata on-chain for Shielded" to
            "Zcash shielded transactions hide sender, receiver, and amount, so Shielded chats can't be traced from the blockchain.",
        // SR-D (ZC-109): the check is the ⋮ menu's "Verify safety number" entry (chat_menu_verify_safety_number).
        "Safety Number" to
            "Open ⋮ → Verify safety number in a Shielded or Tunnel chat to check that nobody is intercepting it. " +
            "Compare the numbers with your contact.",
        // SR-D (ZC-109): what a Shielded user still shows the relays (the always-on inbox; ZBOOT and key rotation, a
        // ZMODE into Shielded, call setup; SR-B1: no avatar; D3-2: no claims; SR-A: no attachment link). RELAY-HEALTH's
        // sentence stays last (RelaySwapDisclosureTest).
        "Relays" to
            "Open and Tunnel messages are end-to-end encrypted and pass through three Nostr relays, one of which " +
            "ZChat runs. Relays can't read them, but they can see your chat key, your IP address, when you're " +
            "online, and which chat keys you send to. ZChat keeps a relay inbox open for Tunnel, Open and calls, so " +
            "relays see this even if all your chats are Shielded. In a Shielded chat, what you send through the " +
            "relays is limited to your chat key setup, a mode change and call setup; your profile photo is not sent " +
            "to Shielded contacts. What your contact sends from Tunnel or Open, and group messages, still use the " +
            "relays. Relays may keep logs. If one of the two other relays stops working, ZChat replaces it with " +
            "another public relay from a list built into the app; that relay can then see the same things.",
        // SR-D (ZC-109): ZC-249 sends file transfers through Tor when it is on; SR-A puts a Shielded chat's link on-chain.
        "Photos, files and voice notes" to
            "They are encrypted, then uploaded to public file hosts (such as nostr.download), which see your chat " +
            "key, size and time, and your IP address unless Tor is on. In a Shielded chat their link goes inside a " +
            "Shielded transaction; in Tunnel and Open it goes through the relays.",
        // SR-D (ZC-109, TURN-CREDS §7.1): signalling over the relays; ICE with Cloudflare's STUN and ZChat's TURN
        // (VoiceCallManager.buildIceServers, policy ALL); the media never goes through Tor.
        "Calls" to
            "Calls are set up over the Nostr relays in every chat mode, Shielded included. Cloudflare's STUN server " +
            "and ZChat's call relay server can see your IP address in every call. The call itself is encrypted " +
            "(DTLS-SRTP) and goes directly between the phones when it can, so your contact sees your IP address " +
            "too; otherwise it passes through ZChat's call relay server, which then carries only encrypted audio " +
            "and video. The call itself never uses Tor.",
        "Upgraded network" to
            "ZChat tells your contacts, inside encrypted messages, that your app supports the upgraded Zcash network.",
        "Fees" to
            "A short Shielded message costs at least 0.00017 ZEC: the Zcash network fee, a 0.00001 ZEC ZChat fee, " +
            "and 0.00001 ZEC delivered with the message. ZChat's fee wallet sees when fees arrive and how many, " +
            "not who sent them. The server your wallet syncs from sees your IP address when you send.",
        // SR-D (ZC-109): routeIncomingBoot adopts Tunnel only as the responder; handleModeControl follows Tunnel or Open.
        "Automatic mode changes" to
            "A Shielded chat you never set by hand may switch to Tunnel when the contact's secure handshake arrives " +
            "(the side that started the handshake stays Shielded), and a contact's phone may follow your switch to " +
            "Tunnel or Open; you can change either in Conversation mode.",
    )

/** "Known limitations" text of More → What ZCHAT Protects. */
internal const val WHAT_ZCHAT_PROTECTS_LIMITATIONS =
    "- Extra Security (Post-Quantum) mixes in a shared key as additional entropy, not a post-quantum KEM\n" +
        "- Tor Protection (Advanced settings) covers sending transactions and fetching transaction data through the " +
        "wallet's server, photo and file transfers, swaps and exchange rates; relays, calls, the AI tab and the " +
        "update check don't use Tor.\n" +
        "- Keys stored in encrypted prefs, not hardware-backed Keystore\n" +
        "- Multi-device with same seed will desync ratchet counters\n" +
        "- No forward secrecy: session keys re-derive from stored root, so device-state compromise can expose past messages"

/** Round 8 D9 (DECISIONS_NIGHT §4): the (title, description) resources of [WHAT_ZCHAT_PROTECTS_ITEMS], in order. */
internal val SECURITY_DIALOG_ROWS: List<Pair<Int, Int>> =
    listOf(
        R.string.security_dialog_item_1_title to R.string.security_dialog_item_1_body,
        R.string.security_dialog_item_2_title to R.string.security_dialog_item_2_body,
        R.string.security_dialog_item_3_title to R.string.security_dialog_item_3_body,
        R.string.security_dialog_item_4_title to R.string.security_dialog_item_4_body,
        R.string.security_dialog_item_5_title to R.string.security_dialog_item_5_body,
        R.string.security_dialog_item_6_title to R.string.security_dialog_item_6_body,
        R.string.security_dialog_item_7_title to R.string.security_dialog_item_7_body,
        R.string.security_dialog_item_8_title to R.string.security_dialog_item_8_body,
        R.string.security_dialog_item_9_title to R.string.security_dialog_item_9_body,
        R.string.security_dialog_item_10_title to R.string.security_dialog_item_10_body,
        R.string.security_dialog_item_11_title to R.string.security_dialog_item_11_body,
    )

/** The ⋮ menu's encryption row: its title, subtitle and toast resources. */
internal data class E2EMenuRow(
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    @StringRes val toast: Int,
)

/**
 * Round 8 D9 (DECISIONS_NIGHT §3): the ⋮ menu's encryption row for a chat. While ZChat's own end-to-end layer is
 * pending (a Shielded chat whose contact has not answered the key exchange; ChatViewModel.appLayerE2EPending, the
 * predicate the send path uses), it says the messages are protected by Zcash's shielded encryption only, and its
 * toast repeats the subtitle. Otherwise it is the end-to-end row.
 */
internal fun e2eMenuRow(appLayerE2EPending: Boolean): E2EMenuRow =
    if (appLayerE2EPending) {
        E2EMenuRow(
            title = R.string.chat_menu_e2e_pending_title,
            subtitle = R.string.chat_menu_e2e_pending_subtitle,
            toast = R.string.chat_menu_e2e_pending_subtitle,
        )
    } else {
        E2EMenuRow(
            title = R.string.chat_menu_e2e_title,
            subtitle = R.string.chat_menu_e2e_subtitle,
            toast = R.string.chat_menu_e2e_toast,
        )
    }

/**
 * ZC-1471: the header lock of a chat. Every ZChat mode is encrypted (Open and Tunnel: NIP-17 gift wrap; Shielded: the
 * ratchet, or Zcash's shielded memo encryption while the contact has not answered the key exchange), so there is no
 * "open" state: an open lock would be a false claim. PENDING is the amber lock of [e2eMenuRow]'s pending row.
 */
enum class E2ELockState { CLOSED, PENDING }

/** ZC-1471: the lock for a chat, from the same predicate the send path and [e2eMenuRow] use. */
internal fun e2eLockState(appLayerE2EPending: Boolean): E2ELockState =
    if (appLayerE2EPending) E2ELockState.PENDING else E2ELockState.CLOSED

/** ZC-1471, REVISION 3 (ZC-1485/1486): the lock's readable label, "Encryption: closed" or "Encryption: pending". */
@StringRes
internal fun e2eLockContentDescription(state: E2ELockState): Int =
    when (state) {
        E2ELockState.CLOSED -> R.string.chat_a11y_encryption_closed
        E2ELockState.PENDING -> R.string.chat_a11y_encryption_pending
    }
