package co.electriccoin.zcash.ui.screen.chat.model

import androidx.annotation.StringRes
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import java.math.BigDecimal
import java.util.Locale

/**
 * P2h — the send notices ChatViewModel reports (as English text in [SendMessageState]) for sends whose
 * outcome is not a plain "sent": submitted but unconfirmed, possibly already sent, or refused before a retry.
 * The chat screen shows them in the phone's language through [localizedResource].
 */
object ChatSendCopy {
    // SR-2 — submitted, but not yet delivered or confirmed.
    const val MESSAGE_UNCONFIRMED = "Message submitted — not confirmed yet"
    const val PAYMENT_REQUEST_UNCONFIRMED = "Payment request submitted — not confirmed yet"
    const val PAYMENT_UNCONFIRMED = "Payment submitted — not confirmed yet, check your transactions"
    const val REACTION_UNCONFIRMED = "Reaction submitted — not confirmed yet"

    // P2a — a Retry that is refused.
    const val RETRY_MODE_CHANGED = "Keep the original chat mode and accept its message cost before retrying."
    const val RETRY_STORAGE_FAILED =
        "Could not finish recording this retry. Your message is kept; check its status before trying again."
    const val RETRY_ALREADY_CLAIMED = "This message is already being handled in another chat screen."

    // SR-3 — a free (Nostr) payment request no relay took.
    const val PAYMENT_REQUEST_NOT_CONNECTED =
        "Payment request not sent — not connected. Tap Retry once the chat reconnects."

    // P2h — a send held until the next block because the wallet's funds are still confirming.
    const val QUEUED_WAITING_FOR_BLOCK =
        "Not sent yet — waiting for your funds to confirm. It will try again after the next block."

    // R2-7 (c) — why a failed message was not sent, shown above its Retry (ChatMessage.failureReason).
    const val FAILED_UNKNOWN = "Not sent. The reason was not recorded. Tap Retry to try again."
    const val FAILED_APP_CLOSED = "Not sent — ZChat was closed before this message went out. Tap Retry to send it."
    const val FAILED_FUNDS_CONFIRMING =
        "Not sent — your ZEC was still confirming after several blocks. Tap Retry once your balance shows it as spendable."

    // ZC-1340 — the queue gave up after this row's run paid its setup step: the setup went out, the message did not.
    const val FAILED_FUNDS_CONFIRMING_SETUP_PAID =
        "Not sent — a setup step for this chat went out, but this message did not because your ZEC was still confirming. " +
            "Tap Retry once your balance shows it as spendable."

    // ZC-1909 — this row's run paid its setup step (the key exchange), and the wallet truly cannot pay the message now,
    // not even once its confirming ZEC confirms. Never "Nothing was paid" and never "Add ZEC" after a paid setup.
    const val FAILED_SHORT_SETUP_PAID =
        "Not sent — the setup for this chat went out, but this message did not: your wallet does not have enough " +
            "spendable ZEC for it now. Tap Retry once your balance covers it."

    // ZC-1338 — Accept was tapped while the funds confirm: nothing paid yet, and the answer goes by itself. ZC-1363: a toast,
    // one sentence under 80 characters; the chat's waiting line (chat_accept_waits_for_funds_banner) says the rest.
    const val KEX_ANSWER_WAITS_FOR_FUNDS = "Nothing paid yet; your Accept goes by itself once your ZEC confirms."

    // ZC-1276 — a message whose build passed its time limit before any submit started.
    const val FAILED_BUILD_TOO_LONG =
        "Not sent — this message took too long to prepare, and nothing was paid for it. Tap Retry to try again."

    // ZC-1310 — the same limit, after this message's run paid its key exchange: the setup went out, the message did not.
    const val FAILED_BUILD_TOO_LONG_KEX_PAID =
        "Not sent — the setup for this chat went out, but this message took too long to prepare and was not sent. " +
            "Tap Retry to try again."

    // ZC-1326 — a stored first message that names no account that accepted its price: no account pays it.
    const val FAILED_NO_ACCEPTING_ACCOUNT =
        "Not sent — ZChat can't tell which account accepted this message's price, so no account paid it. Tap Retry to try again."

    // ZC-1395 — a queued first message whose price another account accepted: the account selected now paid nothing.
    const val FAILED_OTHER_ACCOUNT_ACCEPTED =
        "Not sent — another account accepted this message's price. Nothing was paid. Tap Retry to try again."

    // ZC-1290 — a message still not out when its total limit passed, counted from its first start across every retry.
    const val FAILED_WAITED_TOO_LONG = "Not sent — this message waited too long to go out. Tap Retry to try again."

    // ZC-1271 — a first message that waited for its funds and would now cost more than the total the user accepted.
    const val FAILED_ABOVE_ACCEPTED_PRICE =
        "Not sent — it would now cost more than the price you accepted. Tap Retry to send it at the current price."

    // ZC-1235 — a message whose owed KEX is above its price; the figure is in the question asked with it (OWN_SETUP_TRUE_PRICE).
    const val FAILED_KEX_ABOVE_PRICE =
        "Not sent — this chat's setup message costs more than the usual price right now. Nothing was paid. Tap Retry to try again."
    const val FAILED_NO_RELAY = "Not sent — no relay accepted it. Check your internet connection, then tap Retry."

    // ZC-393 — only a public relay (relay.primal.net or a catalog fallback) took it: not on a relay every ZChat version
    // reads (ZChat's relay, nos.lol), so a 2.12.4 contact cannot get it from there. Its Retry re-sends the kept copy.
    const val FAILED_ONLY_PUBLIC_RELAY =
        "Not confirmed — only a public relay took it, and contacts on ZChat 2.12.4 or older don't read that relay. Tap Retry."
    const val FAILED_NOT_CONNECTED = "Not sent — the free connection to this contact was not ready. Tap Retry in a moment."

    // ZC-421 — a payment request is sent again only as the same request (one rumor id for as long as its row exists): its
    // relay key changed, it is 29 days old, the chat is Shielded now, or a test build's row has no second. Nothing is sent.
    const val PAYMENT_REQUEST_RESEND_REFUSED =
        "This request can't be sent again as the same request, and a second one could be paid twice. If your contact did " +
            "not get it, make a new request."

    // SR-B2 — a Tunnel message queued before the free connection was ready, whose chat the user then switched to
    // Shielded: it was never published or paid. Its Retry sends it as a new Shielded message (a paid on-chain send).
    const val FAILED_SWITCHED_TO_SHIELDED =
        "Not sent — this chat switched to Shielded before the free connection was ready, so it was not sent through the " +
            "relays. Tap Retry to send it as a Shielded message instead: that is a paid on-chain send, like every Shielded " +
            "message."

    // M2 (fix-all 2.13.0) — a first message to a contact we hold no key for, refused before anything is paid: the wallet's
    // notes cannot pay both of its transactions (our key exchange, then the message), or that could not be checked.
    // At the send the typed text goes back to the box; the _ROW texts stand above Retry on a queued or retried row.
    const val FIRST_MESSAGE_PLAN_UNFUNDABLE =
        "Not sent: your wallet can't pay for both transactions of a first message (the key exchange, then the " +
            "message). Nothing was paid and your text is still in the box. Add ZEC and try again."
    const val FIRST_MESSAGE_PLAN_UNFUNDABLE_ROW =
        "Not sent: your wallet can't pay for both transactions of a first message. Nothing was paid. Add ZEC, then " +
            "tap Retry."
    const val FIRST_MESSAGE_PLAN_UNCHECKED =
        "Not sent: ZChat couldn't check that your wallet can pay for both transactions. Nothing was paid and your " +
            "text is still in the box. Try again in a moment."
    const val FIRST_MESSAGE_PLAN_UNCHECKED_ROW =
        "Not sent: ZChat couldn't check that your wallet can pay for both transactions. Nothing was paid. Tap Retry " +
            "in a moment."

    // F4 (fix-all 2.13.0, train T4) — a send the chat's history pass proved never reached the chain, over two passes:
    // its transaction expired unmined (the row had its id), or nothing of the attempt can still be mined (no id).
    const val FAILED_EXPIRED_NOT_SENT =
        "Not sent — its transaction expired on the network without being mined, so nothing was paid for it. " +
            "Tap Retry to send it again."
    const val FAILED_NOT_ON_CHAIN =
        "Not sent — it never reached the Zcash blockchain, so nothing was paid for it. Tap Retry to send it again."

    // R4-6 (12) — the recorder and photo fallbacks the chat view model reports. Constants so
    // ChatSendCopy can name them and a Spanish phone reads them in Spanish.
    const val VOICE_MESSAGE_FAILED = "Voice message failed — please try again."
    const val PHOTO_SEND_FAILED = "Couldn’t send the photo. Please try again."

    // R9-4 — a failed file now keeps its bubble, whose reason said "photo".
    const val FILE_SEND_FAILED = "Couldn’t send the file. Please try again."

    // SR-C2 — a photo, file or voice note in a chat with no end-to-end key. The notice itself is a dialog
    // (SendMessageState.AttachmentNeedsKeyExchange); these are the row reason (the typed refusal's backstop, and a Retry
    // that finds no key), a wallet still reading its chat history (a restore, or just after launch), and a Retry that
    // has no copy to send. None of them says "try again": trying again cannot help until the key exists or the
    // attachment is picked again.
    const val ATTACHMENT_NEEDS_KEY_EXCHANGE =
        "Not sent — photos, files and voice notes need a key exchange with this contact first. Nothing was uploaded or paid."
    const val ATTACHMENT_STILL_READING_HISTORY =
        "Not sent — ZChat is still reading your chat history, and photos, files and voice notes can't be sent to this " +
            "contact while it reads. Nothing was uploaded or paid."
    const val ATTACHMENT_RETRY_COPY_GONE =
        "This can't be sent again from here. Pick the photo or file again, or record the voice note again, to send it."

    // SR-C1 (KI-37) — the row reason after "Cancel" on the warning for a contact on ZChat 2.12 or older. Its Retry
    // shows the warning again.
    const val ATTACHMENT_LEGACY_CONTACT_CANCELLED =
        "Not sent — you cancelled it because this contact may be on an older ZChat that can't open it. " +
            "Nothing was uploaded or paid."

    // SR-A (fix-all 2.13.0) — the row reasons of a Shielded photo, file or voice note whose link goes on-chain: the
    // transaction built at send time was not the quoted one (nothing paid), the wallet cannot pay it, its ZEC is still
    // confirming, or the exact price could not be worked out (nothing uploaded or paid).
    const val ATTACHMENT_PRICE_CHANGED =
        "Not sent — the cost changed before it went out. Nothing was paid. Tap Retry to see the new price."

    // T5b X-06 — the same refusal in a send that paid its owed key exchange first: one transaction was paid, the
    // attachment's was not. Never "Nothing was paid".
    const val ATTACHMENT_PRICE_CHANGED_KEX_PAID =
        "Not sent — the cost changed before it went out. Your wallet paid for the key exchange that goes first, but " +
            "not for this photo, file or voice note. Tap Retry to see the new price."

    // T5b X-04 — the chat was switched to Shielded while the attachment was uploading: its link is not sent without the
    // exact price question. Retry asks it.
    const val ATTACHMENT_SWITCHED_TO_SHIELDED =
        "Not sent — this chat was switched to Shielded while this was uploading, and a Shielded send has a price. " +
            "Nothing was paid. Tap Retry to see the price."
    const val ATTACHMENT_NO_FUNDS_ROW = "Not sent — not enough ZEC for a Shielded send. Nothing was paid. Add ZEC, then tap Retry."
    const val ATTACHMENT_STILL_CONFIRMING =
        "Not sent — your ZEC is still confirming. Nothing was paid. Tap Retry once it has confirmed, or switch this chat " +
            "to Tunnel or Open, where photos, files and voice notes are free."
    const val ATTACHMENT_PRICE_UNAVAILABLE =
        "Not sent — ZChat couldn't work out the exact price of this Shielded send, so nothing was uploaded or paid. " +
            "Tap Retry in a moment."

    // R4-6 (12) — the body of the Stranded funds dialog (SendMessageState.NeedsIronwoodMigration.message).
    const val IRONWOOD_FIRST_FUNDS_CONFIRMING =
        "Your first Ironwood funds have arrived and are still confirming. Sending now would draw " +
            "on the sealed Orchard pool and publish the amount — wait for the confirmation, " +
            "then try again."
    const val ORCHARD_POOL_SEALED =
        "Your funds are in the Orchard pool, which Zcash sealed in July 2026. Messages can no " +
            "longer be sent from it, and trying would publish the amount on-chain."

    // C4 (B2 review item 6) — what a payment-request fulfil reports. They were English on a Spanish phone.
    const val PAYMENT_MAY_HAVE_BEEN_SENT = "Payment may have been sent — check your transactions before paying again"
    const val PAYMENT_ALREADY_BEING_PAID = "This request is already being paid — check your transactions before paying again"
    const val PAYMENT_SENT = "Payment sent"

    // C4 — a fulfil that failed and proved nothing was sent for it (settleFulfilFailure); was "Failed to fulfill
    // payment request".
    const val PAYMENT_NOT_SENT = "Payment not sent — nothing left your wallet. You can try again."

    // D-6 (fix-all 2.13.0) — a payment (Send Payment, Welcome ZEC, a request's Pay) whose proposal was not the exact
    // price its confirm showed: refused before the spend auth, nothing paid; opening the confirm again shows the new price.
    const val PAYMENT_PRICE_CHANGED =
        "Not sent — the price changed before it went out. Nothing was paid. Open it again to see the new price."

    // ZC-601 — New Chat could not get the exact price of its first message from the wallet: nothing is asked, built or
    // paid. The words are the payment sheet's (payment_quote_unavailable).
    const val PRICE_QUOTE_UNAVAILABLE =
        "ZChat couldn't work out the exact price, so nothing can be paid yet. Try again in a moment."

    // Round 8 P3 (PHONE_TRIAGE F1) — Pay in a chat that has no real address (keyed "unknown", by a sender hash or by a
    // conversation id). Refused before anything is built; the request bubble shows the same text instead of Pay.
    const val PAYMENT_SENDER_UNKNOWN = "Can't pay: the sender's address is unknown."

    // Round 8 D4 (round 7c C5) — shown once per process instead of the automatic-setup consent question while a Keystone
    // account is selected: the send chokepoint refuses every automatic send on it (ZC-102), so nothing was sent or paid.
    // K0 (2.13.0): it names where to switch (the Wallet-tab account chip); the old "Switch to your ZChat account" could
    // not be followed, because no screen offered a switch.
    const val KEYSTONE_AUTOMATIC_SETUP =
        "A Keystone account is selected. ZChat can't sign chat setup payments with it in this version, so nothing was " +
            "sent or paid. Tap the account name in the Wallet tab and choose your ZChat account to finish setting up " +
            "this chat."

    // K0-2 (2.13.0) — any chat send, chat payment or payment-request payment refused at the send chokepoint because a
    // Keystone account is selected (KeystoneAvailability.CHAT_SIGNING is off). Refused before anything is built.
    const val KEYSTONE_CHATS_CLOSED =
        "A Keystone account is selected. In this version ZChat can't sign chat messages or chat payments with " +
            "Keystone, so nothing was sent or paid. Tap the account name in the Wallet tab and choose your ZChat " +
            "account to chat."

    // Round 8 D7 (sweep #13) — a status broadcast that did not reach every contact (SendMessageState.StatusPartial).
    // A format string (%1$d reached, %2$d total): shown only through its resource with the counts, never pieced together.
    const val STATUS_PARTIAL = "Your status reached %1\$d of %2\$d contacts. Set it again later to reach the rest."

    // ZC-283 — a status set with "tell my contacts" while an earlier broadcast is still running: the text is saved,
    // nothing is asked, sent or paid.
    const val STATUS_BROADCAST_BUSY =
        "Your status is still being sent to your contacts. Nothing more was sent or paid; set it again once that " +
            "finishes."

    // Round 8 D8 (REVIEW_7C F3) — a group memo refused on a Keystone account (ZC-102: an automatic send can never be
    // signed there). The group banner shows it instead of "check your balance".
    // ZC-897 — an invite held because the contact's first key exchange waits for this phone's Accept.
    const val GROUP_INVITE_WAITS_FOR_ACCEPT =
        "Not sent: this contact's chat request waits for you to accept it. Tap Accept in their chat, then Resend in " +
            "Group Settings. Nothing was paid."

    // ZC-757 (review round 2) — a rebuilt group's first new key, before a history pass read the newest block time.
    const val REBUILT_KEY_WAITS_FOR_HISTORY =
        "Not changed yet: ZCHAT is still reading this group's history from the blockchain. Nothing was sent and nothing " +
            "was paid. Try again once it finishes."

    const val GROUP_KEYSTONE_REFUSED =
        "Group messages can't be sent from a Keystone account in this version. Nothing was sent or paid."

    // Round 8 D8 (F31) — the timer and mode controls the history settlement holds. A control shows only as a toast with
    // no Retry button, so these never point to Retry. The timer can be set again once the reading finishes; re-picking
    // the mode that is already selected sends nothing, so the mode text does not ask for that.
    const val CONTROL_STILL_READING_HISTORY =
        "ZCHAT is still reading this chat's history from the blockchain. Nothing was sent and nothing was paid. " +
            "Once it finishes, set it again."
    const val MODE_STILL_READING_HISTORY =
        "Mode changed on this device only. ZCHAT is still reading this chat's history from the blockchain, so your " +
            "contact wasn't told. Nothing was sent and nothing was paid."

    // Round 8 D8 (F33) — a 1:1 message or reply refused before the submit because the encrypted session is not ready.
    // It was English only and promised "It'll send once the encrypted connection re-establishes"; nothing re-sends it.
    // The failed row keeps its Retry.
    const val SECURE_SESSION_NOT_READY_MESSAGE =
        "Secure session isn't ready yet — message not sent (it won't be sent unencrypted). Tap Retry once the secure " +
            "connection is back."
    const val SECURE_SESSION_NOT_READY_REPLY =
        "Secure session isn't ready yet — reply not sent (it won't be sent unencrypted). Tap Retry once the secure " +
            "connection is back."

    // Round 8 D8 (smaller issue 2) — the chat notices that were English literals in ChatViewModel. Same English.
    // QA2 Q2-43: an on-chain send is handed to the network and still confirming, so it says no more than that.
    const val MESSAGE_SENT = "Sent to the Zcash network — confirming"
    const val PAYMENT_REQUEST_SENT = "Payment request sent"
    const val REACTION_SENT = "Reaction sent"
    const val REACTION_FAILED = "Failed to send reaction"
    const val REACTION_NOT_CONNECTED = "Reaction not sent — this chat isn't connected yet"

    // Both follow a send whose failure proved nothing was sent (the queue only holds those); the row is
    // FAILED_FUNDS_CONFIRMING with its Retry. The retry count is ChatViewModel.MAX_QUEUE_RETRIES (a test keeps them equal).
    const val QUEUE_TIMED_OUT = "Message send timed out waiting for block confirmation"
    const val QUEUE_RETRIES_EXHAUSTED = "Message failed after 4 retries"
    const val RECONNECTED_DETAILS_RESENT = "Reconnected — re-sent your details (allow ~2 min)"
    const val KEY_RESENT = "Re-sent your key — allow ~2 min to reconnect"
    const val RECONNECT_FAILED = "Couldn't reconnect — please try again."
    const val INCORRECT_ANSWER = "Incorrect answer"

    // The disappearing timer. No retry re-sends a timer, so a relay failure says to set it again (it used to promise
    // "it'll sync when you're both online").
    const val TIMER_NOT_DELIVERED =
        "Timer set on this device only — couldn't reach your contact right now, so their timer didn't change. " +
            "Set it again once you're both online."
    const val TIMER_NEEDS_KEY_EXCHANGE = "Finish the encrypted key exchange first, then set the timer."
    const val TIMER_SYNC_FAILED = "Couldn't sync the disappearing-message timer — try again."

    // Round 8 D10 (DECISIONS_NIGHT §6) — a paid chat step paused because the phone's store could not save its record:
    // an automatic-payments consent that was not saved, or a KEXACK whose sent-marker was not saved. Nothing was paid.
    const val STORAGE_PAUSED_PAID_STEP =
        "ZChat couldn't save its records on this phone, so it paused a paid chat step to make sure you are never " +
            "charged twice. Free up some space or restart your phone, then open the chat again."

    // ZC-791 — a member removal whose roster and record could not be saved AFTER the new key went to the other
    // members. Those messages are sent (the on-chain ones paid); the member is still in the group on this phone and
    // was not told. Removing the member again is a new action with its own price question. Never "charged twice".
    const val GROUP_REMOVAL_NOT_SAVED =
        "ZChat couldn't save this removal on this phone, so the member is still in the group here and wasn't told. " +
            "New-key messages already sent to the other members stay sent, and their fees stay paid. Free up space or " +
            "restart your phone, then remove the member again; ZChat asks before it pays anything."

    // ZC-517: a Join whose reply cannot fit one memo returns to waiting after its free relay attempt failed.
    const val GROUP_JOIN_REPLY_RELAY_ONLY_NOT_SENT =
        "Not sent: your “I joined” reply can only go over the relays, and it couldn’t go over them now. Nothing was " +
            "paid. Tap Join to try again."

    // The conversation mode. A relay failure arms the free retry (retryPendingModeSyncs); an on-chain one does not,
    // and re-picking the selected mode sends nothing, so those two say only that the contact was not told.
    const val MODE_NOT_DELIVERED =
        "Mode changed on this device — couldn't reach your contact right now; it'll sync automatically once you're both online."
    const val MODE_RELAY_UNREACHABLE =
        "Mode changed on this device — couldn't notify your contact (relay unreachable); it'll sync automatically when " +
            "you're both online."
    const val MODE_NEEDS_KEY_EXCHANGE =
        "Mode changed on this device only — the encrypted key exchange isn't finished, so your contact wasn't told."
    const val MODE_SYNC_FAILED = "Mode changed on this device only — couldn't tell your contact."

    // T3-extra — rule (E), "never pay one of our own addresses": a chat send the chokepoint refused, a mode change in a
    // chat with one of our own addresses (kept on this device; no retry is armed for it) and a timer there (not set).
    const val OWN_ADDRESS_NOT_PAID =
        "Not sent — this chat is with one of your own addresses, and ZCHAT never pays to send to your own " +
            "address. Nothing was paid."
    const val MODE_OWN_ADDRESS_NOT_PAID =
        "Mode changed on this device only — this chat is with one of your own addresses, and ZCHAT never pays " +
            "to send the change there. Nothing was paid."
    const val TIMER_OWN_ADDRESS_NOT_PAID =
        "Timer not changed — this chat is with one of your own addresses, and ZCHAT never pays to send the " +
            "timer there. Nothing was paid."

    // ZC-1107: a paid Tunnel switch with a contact whose newest relay rumor has no Ironwood tag (ZChat 2.12 or older):
    // our key exchange would go to a receiver that contact cannot see, so the switch is refused before any question.
    const val MODE_TUNNEL_CONTACT_NEEDS_UPDATE =
        "Not switched to Tunnel — the last message this contact sent over the relay came from ZChat 2.12 or " +
            "older, which can't finish a Tunnel handshake. If they have updated, ask them to switch to Tunnel " +
            "from their side, or to send you any message over the relay first. Nothing was paid."

    // An on-chain-only feature tapped in a free chat (it named the feature and the mode in an English template).
    const val ON_CHAIN_FEATURE_ONLY =
        "This works only in Shielded (on-chain) chats. This chat stays free — nothing was sent or charged."

    // ZC-880 (T11): a free Open action completed, with its on-chain step skipped.
    const val OPEN_NO_ONCHAIN_STEP =
        "This is an Open chat, so ZChat paid nothing and sent nothing on-chain. The rest of this step needs a Tunnel or " +
            "Shielded chat; you can switch the mode in this chat's menu."

    // The key-changed gate. The warning banner has no OK button: its × accepts the new key (dismissE2EKeyChanged). ZC-250:
    // this short cue points to the banner, which stays open with what accepting costs (chat_key_changed_send_refused).
    const val KEY_CHANGED_VERIFY =
        "Not sent: this contact's security key changed. See the red warning at the top of the chat."

    // ZC-1792 F1: a responder whose answer (KEXACK) to the contact's key exchange is not paid yet seals nothing: the contact
    // could never open it (the root moves once the answer is mined). Refused before any question; nothing built or paid.
    const val ACCEPT_FIRST_NOT_SENT =
        "Not sent, and nothing was paid. Your contact started a key exchange in this chat and it is waiting for your " +
            "Accept. A Shielded message sent before you accept could not be opened by your contact. Tap Accept first " +
            "(ZChat shows the price), then send again."

    // ZC-1843 (QA2 k1b2): the same refusal at the seal after this run paid the chat's key exchange (the contact's key exchange
    // arrived while ours was being paid): the setup went out, the message did not. Never "nothing was paid".
    const val ACCEPT_FIRST_NOT_SENT_SETUP_PAID =
        "Not sent. The setup for this chat went out, but this message did not: your contact started a key exchange in " +
            "this chat and it is waiting for your Accept. A Shielded message sent before you accept could not be opened " +
            "by your contact. Tap Accept first (ZChat shows the price), then tap Retry."

    // ZC-1844 (QA2 k1b2): the user accepted the contact's new key and our own key exchange answers it (sent within the hour,
    // or owed as the reconnect): no Accept can help (the answer is ANSWERED_BY_OUR_KEX), so the refusal names what waits.
    // ZC-1860 (QA2 pa3): the contact's answer does not reopen sending: the gate holds while our key exchange's paid-setup
    // cooldown runs (HANDSHAKE_RESEND_COOLDOWN_MS, an hour), and after it one more answer may be paid (ZC-1849): the
    // Accept-first gate asks for the Accept, or retryKexAckOnce pays it under the handshake consent (ZC-1867).
    const val KEY_EXCHANGE_FINISHING_NOT_SENT =
        "Not sent, and this message was not paid for. You accepted this contact's new key and your own key exchange for " +
            "it is sent or still waiting to go. Shielded messages in this chat wait until it finishes, up to an hour after " +
            "it is sent, even if their answer arrives sooner. After that, tap Accept when ZChat asks (it shows the price; if " +
            "you allowed handshake transactions it may pay this one answer by itself), then send again."

    // ZC-624 (T1): Pay on a key-change reconnect whose warning is already gone; nothing was written or paid.
    const val KEY_CHANGE_RECONNECT_NOT_NEEDED =
        "The security-key warning for this chat is already gone, so there is nothing left to reconnect. This tap changed " +
            "nothing and paid nothing."

    // ZC-545 — the accept of a contact's new key (ChatViewModel.acceptRefusedKey) and "Not their key". An accept names
    // the whole key and our own key the dialog showed; when either is no longer that key, or the remembered key is gone,
    // nothing is changed, asked or paid and this is shown (chat_key_changed_changed_again).
    const val KEY_CHANGE_CHANGED_AGAIN =
        "The key you chose is no longer waiting for your decision (it was dismissed, our key changed or the warning " +
            "cleared), so nothing was changed. Review the warning and decide again."

    // The phone's store did not confirm a write before anything was wiped: nothing changed (chat_key_accept_not_saved).
    const val KEY_ACCEPT_NOT_SAVED =
        "ZChat couldn't save the new key on this phone, so nothing was changed. Free up some space or restart your " +
            "phone, then try again."

    // An accept that did not reach its last write (or "Not their key" after one): the warning stays and the user accepts
    // again (chat_key_changed_interrupted, also shown in the dialog).
    const val KEY_ACCEPT_INTERRUPTED =
        "An earlier attempt to accept this key was interrupted before it finished. Accept it again to finish, or reset " +
            "encryption from the menu."

    // A locked message the block-aware retry proved was not sent (a send that may have gone out shows
    // SubmitAttemptProof.MAY_HAVE_BEEN_SENT instead). It used to promise "it'll go through once that settles".
    const val LOCKED_MESSAGE_NOT_SENT =
        "Couldn't send the locked message — nothing was sent. A previous on-chain message may still be confirming, " +
            "or you may need a little more ZEC. Wait a moment and send it again, or add ZEC."

    // The error mapper's fallbacks (toZchatUserMessage) for the chat's sends.
    const val SCHEDULED_FAILED = "Failed to send scheduled message"
    const val BLOCK_LOCKED_FAILED = "Failed to send block-locked message"
    const val PAYMENT_LOCKED_FAILED = "Failed to send payment-locked message"
    const val CONDITIONAL_FAILED = "Failed to send conditional message"
    const val UNLOCK_FAILED = "Failed to unlock message"
    const val ANSWER_FAILED = "Failed to submit answer"
    const val PAYMENT_FAILED = "Failed to send payment"
    const val PAYMENT_REQUEST_FAILED = "Failed to send payment request"
    const val MESSAGE_FAILED = "Failed to send message"
    const val REPLY_FAILED = "Failed to send reply"
    const val INSUFFICIENT_BALANCE_SHORT = "Insufficient balance. Please add ZEC to your wallet to send messages."

    // R2-7 (c) — why a send needs Ironwood funds (SendMessageState.NeedsOrchardShielding.message).
    const val SHIELD_NEEDED =
        "ZCHAT sends messages only from Ironwood, the Zcash shielded pool new funds go to. Shield your funds to continue."

    /**
     * R2-7 (c) — the line shown above Retry on a failed outgoing message: the reason recorded when it failed,
     * or [FAILED_UNKNOWN] for a row that has none (an older build, or a path that records none). Null when
     * the message has not failed, or when it may already have been sent (that row shows its own notice).
     */
    fun failureReasonFor(message: ChatMessage): String? =
        when {
            !message.isOutgoing || message.status != MessageStatus.FAILED -> null
            message.sendMayHaveGoneOut -> null
            else -> message.failureReason?.takeIf { it.isNotBlank() } ?: FAILED_UNKNOWN
        }

    /**
     * C4 (HONOR check 3b) — the body of the "Delete message?" dialog for [message]. A FAILED outgoing row whose
     * submit never started and that holds no stored transaction id was provably never handed to the SDK: nothing
     * about it is on the Zcash blockchain, so it is not told that its transaction stays there.
     *
     * Round 8 D8 (smaller issue 2): two more rows are not (or may not be) on the chain. A Tunnel/Open message sent or
     * received over the free relays (no transaction id; its id is the relay's "nmsg-" / "nostr-" id) has no Zcash
     * transaction at all. A FAILED row whose submit started (a submit time or stored transaction ids) and that has no
     * confirmed transaction may have been sent, and may not. Every other row keeps the blockchain wording.
     */
    @StringRes
    fun deleteConfirmBodyFor(message: ChatMessage): Int =
        when {
            // ZC-1344: a row still waiting for its funds has no transaction that could stay on the blockchain.
            // ZC-1944: only while no submit of it started and it holds no transaction id (the live note wait builds it again).
            message.isOutgoing &&
                message.waitsForFunds &&
                message.txId == null &&
                message.submitStartedAtMillis == null &&
                message.submittedTxIds.isEmpty() -> R.string.chat_delete_message_body_not_sent
            // ZC-1354: nor a row waiting for a secure connection: nothing of it was sent, and Delete takes its queued payload too.
            message.isOutgoing &&
                message.txId == null &&
                message.id.startsWith("tunnel-wait-") -> R.string.chat_delete_message_body_not_sent
            // F4: a row the history pass proved never reached the chain. With a transaction id it was made and expired
            // unmined (so "no Zcash transaction was made" would be false); without one, it never reached the chain.
            message.isOutgoing &&
                message.status == MessageStatus.FAILED &&
                message.txId == null &&
                message.submitStartedAtMillis == null &&
                message.submittedTxIds.isEmpty() &&
                message.supersededTxIds.isNotEmpty() -> R.string.chat_delete_message_body_expired
            message.isOutgoing &&
                message.status == MessageStatus.FAILED &&
                message.txId == null &&
                message.submitStartedAtMillis == null &&
                message.submittedTxIds.isEmpty() &&
                message.notSentProvenAtHeight != null -> R.string.chat_delete_message_body_not_on_chain
            message.isOutgoing &&
                message.status == MessageStatus.FAILED &&
                message.txId == null &&
                message.submitStartedAtMillis == null &&
                message.submittedTxIds.isEmpty() -> R.string.chat_delete_message_body_not_sent
            message.txId == null && message.isRelayMessage() && message.status != MessageStatus.FAILED ->
                R.string.chat_delete_message_body_relay
            message.isOutgoing && message.status == MessageStatus.FAILED && message.txId == null ->
                R.string.chat_delete_message_body_maybe_sent
            else -> R.string.chat_delete_message_body
        }

    /** A message that went over the free relays: its id is the relay's rumor id ("nmsg-…") or event id ("nostr-…"). */
    private fun ChatMessage.isRelayMessage(): Boolean = id.startsWith("nmsg-") || id.startsWith("nostr-")

    private val resources: Map<String, Int> by lazy {
        mapOf(
            SubmitAttemptProof.MAY_HAVE_BEEN_SENT to R.string.chat_send_may_have_been_sent,
            SubmitAttemptProof.ROW_NOTICE to R.string.chat_send_row_may_have_been_sent,
            MESSAGE_UNCONFIRMED to R.string.chat_send_message_unconfirmed,
            PAYMENT_REQUEST_UNCONFIRMED to R.string.chat_send_payment_request_unconfirmed,
            PAYMENT_UNCONFIRMED to R.string.chat_send_payment_unconfirmed,
            REACTION_UNCONFIRMED to R.string.chat_send_reaction_unconfirmed,
            RETRY_MODE_CHANGED to R.string.chat_send_retry_mode_changed,
            RETRY_STORAGE_FAILED to R.string.chat_send_retry_storage_failed,
            RETRY_ALREADY_CLAIMED to R.string.chat_send_retry_already_claimed,
            PAYMENT_REQUEST_NOT_CONNECTED to R.string.chat_send_payment_request_not_connected,
            QUEUED_WAITING_FOR_BLOCK to R.string.chat_send_waiting_for_block,
            co.electriccoin.zcash.ui.common.model.SaplingCrossingException.SAPLING_CROSSING_MESSAGE to
                R.string.chat_send_sapling_crossing,
            co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase.INSUFFICIENT_BALANCE_MESSAGE to
                R.string.chat_send_insufficient_balance,
            FAILED_UNKNOWN to R.string.chat_send_failed_unknown,
            FAILED_APP_CLOSED to R.string.chat_send_failed_app_closed,
            FAILED_FUNDS_CONFIRMING to R.string.chat_send_failed_funds_confirming,
            FAILED_FUNDS_CONFIRMING_SETUP_PAID to R.string.chat_send_failed_funds_confirming_setup_paid,
            FAILED_SHORT_SETUP_PAID to R.string.chat_send_failed_short_setup_paid,
            KEX_ANSWER_WAITS_FOR_FUNDS to R.string.chat_send_kex_answer_waits_for_funds,
            FAILED_BUILD_TOO_LONG to R.string.chat_send_failed_build_too_long,
            FAILED_BUILD_TOO_LONG_KEX_PAID to R.string.chat_send_failed_build_too_long_kex_paid,
            FAILED_NO_ACCEPTING_ACCOUNT to R.string.chat_send_failed_no_accepting_account,
            FAILED_OTHER_ACCOUNT_ACCEPTED to R.string.chat_send_failed_other_account_accepted,
            FAILED_WAITED_TOO_LONG to R.string.chat_send_failed_waited_too_long,
            FAILED_ABOVE_ACCEPTED_PRICE to R.string.chat_send_failed_above_accepted_price,
            FAILED_KEX_ABOVE_PRICE to R.string.chat_send_failed_kex_above_price,
            FAILED_NO_RELAY to R.string.chat_send_failed_no_relay,
            FAILED_ONLY_PUBLIC_RELAY to R.string.chat_send_failed_only_public_relay,
            PAYMENT_REQUEST_RESEND_REFUSED to R.string.chat_send_request_resend_refused,
            // M2: the refusals of a first message whose two transactions cannot be paid, or could not be checked.
            FIRST_MESSAGE_PLAN_UNFUNDABLE to R.string.chat_first_message_plan_unfundable,
            FIRST_MESSAGE_PLAN_UNFUNDABLE_ROW to R.string.chat_first_message_plan_unfundable_row,
            FIRST_MESSAGE_PLAN_UNCHECKED to R.string.chat_first_message_plan_unchecked,
            FIRST_MESSAGE_PLAN_UNCHECKED_ROW to R.string.chat_first_message_plan_unchecked_row,
            FAILED_NOT_CONNECTED to R.string.chat_send_failed_not_connected,
            FAILED_SWITCHED_TO_SHIELDED to R.string.chat_send_failed_switched_to_shielded,
            FAILED_EXPIRED_NOT_SENT to R.string.chat_send_failed_expired_not_sent,
            FAILED_NOT_ON_CHAIN to R.string.chat_send_failed_not_on_chain,
            // C4 (B2 review item 6): the payment-request fulfil notices.
            PAYMENT_MAY_HAVE_BEEN_SENT to R.string.chat_send_payment_may_have_been_sent,
            PAYMENT_ALREADY_BEING_PAID to R.string.chat_send_payment_already_being_paid,
            PAYMENT_SENT to R.string.chat_send_payment_sent,
            PAYMENT_NOT_SENT to R.string.chat_send_payment_not_sent,
            // D-6: a payment whose proposal was not its confirm's exact price.
            PAYMENT_PRICE_CHANGED to R.string.chat_payment_price_changed,
            // Round 8 P3: Pay in a chat with no real address (the request bubble shows the same resource).
            PAYMENT_SENDER_UNKNOWN to R.string.chat_request_cannot_pay,
            // C4 (HONOR check 3b): a cancelled or failed PIN / biometric check before a value send.
            co.electriccoin.zcash.ui.screen.chat.util.SPEND_AUTH_NOT_COMPLETED_MESSAGE to
                R.string.chat_send_spend_auth_not_completed,
            // Round 2 report 4 item 16: every reason the chat error mapper (toZchatUserMessage) can show, and
            // the Shield Your Funds message, so a Spanish phone never shows an English refusal.
            co.electriccoin.zcash.ui.screen.chat.util.AMOUNT_TOO_SMALL_MESSAGE to R.string.chat_send_amount_too_small,
            co.electriccoin.zcash.ui.screen.chat.util.MEMO_TOO_LONG_MESSAGE to R.string.chat_send_memo_too_long,
            co.electriccoin.zcash.ui.screen.chat.util.NETWORK_HICCUP_MESSAGE to R.string.chat_send_network_hiccup,
            co.electriccoin.zcash.ui.common.model.NetworkUpgradeRequiredException.MESSAGE to R.string.update_required_refusal,
            co.electriccoin.zcash.ui.common.model.OrchardSealedException.ORCHARD_SEALED_MESSAGE to
                R.string.chat_send_orchard_sealed,
            co.electriccoin.zcash.ui.common.model.OrchardSealedException.IRONWOOD_CONFIRMING_MESSAGE to
                R.string.chat_send_ironwood_confirming,
            co.electriccoin.zcash.ui.common.model.PublicCrossingException.PUBLIC_CROSSING_MESSAGE to
                R.string.chat_send_public_crossing,
            co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase.INSUFFICIENT_PAYMENT_MESSAGE to
                R.string.chat_send_insufficient_payment,
            co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase.PENDING_BALANCE_WAIT_MESSAGE to
                R.string.chat_send_pending_balance_wait,
            SHIELD_NEEDED to R.string.chat_send_shield_needed,
            // Round 8 D4: the Keystone explanation shown instead of the automatic-setup question.
            KEYSTONE_AUTOMATIC_SETUP to R.string.chat_send_keystone_automatic_setup,
            // K0-2: every chat send refused on a Keystone account.
            KEYSTONE_CHATS_CLOSED to R.string.chat_send_keystone_chats_closed,
            // Round 8 D7: the status broadcast that did not reach every contact (format string, two counts).
            STATUS_PARTIAL to R.string.chat_send_status_partial,
            // ZC-283: a status set while a broadcast is still running.
            STATUS_BROADCAST_BUSY to R.string.chat_send_status_busy,
            // Round 8 D8 (F3, F31, F33, smaller issue 2): the group Keystone refusal, the held controls, the secure
            // session refusal, and every notice ChatViewModel reported as an English literal.
            GROUP_KEYSTONE_REFUSED to R.string.chat_send_group_keystone_refused,
            GROUP_INVITE_WAITS_FOR_ACCEPT to R.string.chat_send_group_invite_waits_for_accept,
            REBUILT_KEY_WAITS_FOR_HISTORY to R.string.chat_send_rebuilt_key_waits_for_history,
            CONTROL_STILL_READING_HISTORY to R.string.chat_send_control_still_reading_history,
            MODE_STILL_READING_HISTORY to R.string.chat_send_mode_still_reading_history,
            SECURE_SESSION_NOT_READY_MESSAGE to R.string.chat_send_secure_session_not_ready_message,
            SECURE_SESSION_NOT_READY_REPLY to R.string.chat_send_secure_session_not_ready_reply,
            MESSAGE_SENT to R.string.chat_send_message_sent,
            PAYMENT_REQUEST_SENT to R.string.chat_send_payment_request_sent,
            REACTION_SENT to R.string.chat_send_reaction_sent,
            REACTION_FAILED to R.string.chat_send_reaction_failed,
            REACTION_NOT_CONNECTED to R.string.chat_send_reaction_not_connected,
            QUEUE_TIMED_OUT to R.string.chat_send_queue_timed_out,
            QUEUE_RETRIES_EXHAUSTED to R.string.chat_send_queue_retries_exhausted,
            RECONNECTED_DETAILS_RESENT to R.string.chat_send_reconnected_details_resent,
            KEY_RESENT to R.string.chat_send_key_resent,
            RECONNECT_FAILED to R.string.chat_send_reconnect_failed,
            INCORRECT_ANSWER to R.string.chat_send_incorrect_answer,
            TIMER_NOT_DELIVERED to R.string.chat_send_timer_not_delivered,
            TIMER_NEEDS_KEY_EXCHANGE to R.string.chat_send_timer_needs_key_exchange,
            TIMER_SYNC_FAILED to R.string.chat_send_timer_sync_failed,
            // Round 8 D10 (DECISIONS_NIGHT §6): a paid chat step the phone's store paused.
            STORAGE_PAUSED_PAID_STEP to R.string.chat_storage_paused_paid_step,
            GROUP_REMOVAL_NOT_SAVED to R.string.group_removal_not_saved,
            GROUP_JOIN_REPLY_RELAY_ONLY_NOT_SENT to R.string.group_join_reply_relay_only_not_sent,
            MODE_NOT_DELIVERED to R.string.chat_send_mode_not_delivered,
            MODE_RELAY_UNREACHABLE to R.string.chat_send_mode_relay_unreachable,
            MODE_NEEDS_KEY_EXCHANGE to R.string.chat_send_mode_needs_key_exchange,
            MODE_SYNC_FAILED to R.string.chat_send_mode_sync_failed,
            // T3-extra: the three own-address notices.
            OWN_ADDRESS_NOT_PAID to R.string.chat_send_own_address_not_paid,
            // ZC-601: New Chat's first message has no exact price yet.
            PRICE_QUOTE_UNAVAILABLE to R.string.payment_quote_unavailable,
            MODE_OWN_ADDRESS_NOT_PAID to R.string.chat_send_mode_own_address_not_paid,
            MODE_TUNNEL_CONTACT_NEEDS_UPDATE to R.string.chat_send_mode_tunnel_contact_needs_update,
            TIMER_OWN_ADDRESS_NOT_PAID to R.string.chat_send_timer_own_address_not_paid,
            ON_CHAIN_FEATURE_ONLY to R.string.chat_send_on_chain_feature_only,
            OPEN_NO_ONCHAIN_STEP to R.string.chat_open_no_onchain_step,
            KEY_CHANGED_VERIFY to R.string.chat_send_key_changed_verify,
            ACCEPT_FIRST_NOT_SENT to R.string.chat_send_accept_first_not_sent,
            ACCEPT_FIRST_NOT_SENT_SETUP_PAID to R.string.chat_send_accept_first_setup_paid,
            KEY_EXCHANGE_FINISHING_NOT_SENT to R.string.chat_send_key_exchange_finishing_not_sent,
            KEY_CHANGE_RECONNECT_NOT_NEEDED to R.string.chat_key_change_reconnect_not_needed,
            // ZC-545: the accept of a contact's new key.
            KEY_CHANGE_CHANGED_AGAIN to R.string.chat_key_changed_changed_again,
            KEY_ACCEPT_NOT_SAVED to R.string.chat_key_accept_not_saved,
            KEY_ACCEPT_INTERRUPTED to R.string.chat_key_changed_interrupted,
            LOCKED_MESSAGE_NOT_SENT to R.string.chat_send_locked_message_not_sent,
            SCHEDULED_FAILED to R.string.chat_send_scheduled_failed,
            BLOCK_LOCKED_FAILED to R.string.chat_send_block_locked_failed,
            PAYMENT_LOCKED_FAILED to R.string.chat_send_payment_locked_failed,
            CONDITIONAL_FAILED to R.string.chat_send_conditional_failed,
            UNLOCK_FAILED to R.string.chat_send_unlock_failed,
            ANSWER_FAILED to R.string.chat_send_answer_failed,
            PAYMENT_FAILED to R.string.chat_send_payment_failed,
            PAYMENT_REQUEST_FAILED to R.string.chat_send_payment_request_failed,
            MESSAGE_FAILED to R.string.chat_send_message_failed,
            REPLY_FAILED to R.string.chat_send_reply_failed,
            INSUFFICIENT_BALANCE_SHORT to R.string.chat_send_insufficient_balance_short,
            // R4-6 (12): the Stranded funds dialog body, the recorder and photo fallbacks.
            IRONWOOD_FIRST_FUNDS_CONFIRMING to R.string.chat_stranded_first_funds_confirming,
            ORCHARD_POOL_SEALED to R.string.chat_stranded_orchard_sealed,
            VOICE_MESSAGE_FAILED to R.string.chat_voice_message_failed,
            PHOTO_SEND_FAILED to R.string.chat_photo_send_failed,
            FILE_SEND_FAILED to R.string.chat_file_send_failed,
            // SR-C2: the attachment refusals of a chat with no end-to-end key.
            ATTACHMENT_NEEDS_KEY_EXCHANGE to R.string.chat_attachment_needs_kex_row,
            ATTACHMENT_STILL_READING_HISTORY to R.string.chat_attachment_still_reading_history,
            ATTACHMENT_RETRY_COPY_GONE to R.string.chat_attachment_retry_copy_gone,
            // SR-C1: the cancelled warning for a contact on an older ZChat.
            ATTACHMENT_LEGACY_CONTACT_CANCELLED to R.string.chat_attachment_legacy_row,
            // SR-A: a Shielded attachment's link goes on-chain at an exact price.
            ATTACHMENT_PRICE_CHANGED to R.string.chat_attachment_price_changed,
            ATTACHMENT_PRICE_CHANGED_KEX_PAID to R.string.chat_attachment_price_changed_kex_paid,
            ATTACHMENT_SWITCHED_TO_SHIELDED to R.string.chat_attachment_switched_to_shielded,
            ATTACHMENT_NO_FUNDS_ROW to R.string.chat_attachment_no_funds_row,
            ATTACHMENT_STILL_CONFIRMING to R.string.chat_attachment_still_confirming,
            ATTACHMENT_PRICE_UNAVAILABLE to R.string.chat_attachment_price_unavailable,
            // R4-6 (11): the fail-closed refusal for a proposal ZChat could not read.
            co.electriccoin.zcash.ui.common.model.UnreadableProposalException.UNREADABLE_PROPOSAL_MESSAGE to
                R.string.chat_send_unreadable_proposal,
            // Round 7 (Spanish review): a send the history settlement holds was still English on a Spanish phone.
            co.electriccoin.zcash.ui.screen.chat.crypto.RestoreKexPolicy.STILL_READING_HISTORY to
                R.string.chat_send_still_reading_history,
        )
    }

    /**
     * The string resource that shows [text] in the phone's language, or null to show [text] as it is. The
     * English resource is the same text (res/ui/common/values/chat_send_strings.xml; a test keeps them equal).
     */
    @StringRes
    fun localizedResource(text: String): Int? = resources[text]

    /**
     * ZC-1230 — an Accept refused for funds: the exact figure the build needs (never the 0.00017 the wallet appears to
     * cover). English here, as every notice in [SendMessageState]; the screen shows R.string.chat_send_kex_answer_shortfall
     * with the same figure in the phone's format ([kexAnswerShortfallNeed]). Holds none of the words the error mapper keys on.
     */
    fun kexAnswerShortfall(need: Zatoshi): String =
        "$KEX_ANSWER_SHORTFALL_START${zecDisplay(need, Locale.ROOT)}$KEX_ANSWER_SHORTFALL_END"

    /** ZC-1230 — the figure inside a [kexAnswerShortfall] text; null for any other text. */
    fun kexAnswerShortfallNeed(text: String): Zatoshi? =
        text
            .takeIf { it.startsWith(KEX_ANSWER_SHORTFALL_START) && it.endsWith(KEX_ANSWER_SHORTFALL_END) }
            ?.removePrefix(KEX_ANSWER_SHORTFALL_START)
            ?.removeSuffix(KEX_ANSWER_SHORTFALL_END)
            ?.toBigDecimalOrNull()
            ?.let { zec -> runCatching { Zatoshi(zec.multiply(ZATOSHI_PER_ZEC).longValueExact()) }.getOrNull() }

    /**
     * ZC-1417 — a Shielded message the wallet cannot pay now, with the SDK's figures: [have] (what can be spent now) and
     * [need] (what this message needs; a change below the marginal fee raises it above the floor). The English is
     * chat_send_insufficient_balance_have_need with Locale.ROOT figures; the screen shows that resource with the same
     * figures in the phone's format ([messageShortfallFigures]). Holds none of the words the error mapper keys on.
     */
    fun messageShortfall(
        have: Zatoshi,
        need: Zatoshi,
    ): String =
        MESSAGE_SHORTFALL_START + zecDisplay(have, Locale.ROOT) + MESSAGE_SHORTFALL_MID + zecDisplay(need, Locale.ROOT) +
            MESSAGE_SHORTFALL_END

    /** ZC-1417 — the (have, need) figures inside a [messageShortfall] text; null for any other text. */
    fun messageShortfallFigures(text: String): Pair<Zatoshi, Zatoshi>? {
        if (!text.startsWith(MESSAGE_SHORTFALL_START) || !text.endsWith(MESSAGE_SHORTFALL_END)) return null
        val figures = text.removePrefix(MESSAGE_SHORTFALL_START).removeSuffix(MESSAGE_SHORTFALL_END).split(MESSAGE_SHORTFALL_MID)
        if (figures.size != 2) return null
        val zat = figures.map { zatoshiOf(it) ?: return null }
        return zat[0] to zat[1]
    }

    private fun zatoshiOf(figure: String): Zatoshi? =
        figure
            .replace(",", "")
            .toBigDecimalOrNull()
            ?.let { zec -> runCatching { Zatoshi(zec.multiply(ZATOSHI_PER_ZEC).longValueExact()) }.getOrNull() }

    private const val MESSAGE_SHORTFALL_START = "Not enough ZEC that can be spent now: you have "
    private const val MESSAGE_SHORTFALL_MID = " ZEC and this Shielded message needs "
    private const val MESSAGE_SHORTFALL_END =
        " ZEC. Nothing was paid. Add ZEC and try again. Open chats are free, but only with a contact whose chat key you " +
            "have (⋮ → Conversation mode)."

    // ZC-1363: a toast, under 80 characters.
    private const val KEX_ANSWER_SHORTFALL_START = "Nothing was paid: it needs "
    private const val KEX_ANSWER_SHORTFALL_END = " ZEC spendable. Add ZEC or wait."
    private val ZATOSHI_PER_ZEC = BigDecimal(100_000_000L)
}
