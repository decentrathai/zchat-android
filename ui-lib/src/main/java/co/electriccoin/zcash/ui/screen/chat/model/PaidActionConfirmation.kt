package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.Ciphertext
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase

/**
 * R2-2 (b) — a tap that pays for on-chain chat transactions the user may not expect: a control message in a
 * Shielded chat, a reaction, a re-KEX, the key exchange before a first message, a status broadcast, a group
 * control fanned out to every member. Each asks first with [PaidActionConfirmation] and pays nothing until the
 * user confirms.
 */
enum class PaidAction {
    DISAPPEARING_TIMER,
    MODE_CHANGE,
    KEY_CHANGE_RECONNECT,
    REACTION,
    FIRST_MESSAGE,
    STATUS_BROADCAST,
    PAYMENT_REQUEST_RETRY,
    GROUP_CREATE,
    GROUP_ADD_MEMBER,
    GROUP_RESEND_INVITE,
    GROUP_REMOVE_MEMBER,
    GROUP_ROTATE_KEY,
    GROUP_KEY_SYNC,
    GROUP_LEAVE,
    GROUP_MESSAGE,

    /** Round 8 D5 — Reset encryption: our KEX, then our ZBOOT once the contact's new key lands. */
    RESET_ENCRYPTION,

    /**
     * Round 8 MA3 (member announce, design §6.3) — "Introduce them": one on-chain memo per (member, other member) pair
     * whose recipient can't be reached over the relays. Counted in pairs, never covered by a standing consent.
     */
    GROUP_INTRODUCE,

    /**
     * R8-fixup F1 — an on-chain message in an existing chat whose text needs more than one memo: one transaction whose
     * network fee grows with every memo (a 5,000-character message is about 14 memos).
     */
    LONG_MESSAGE,

    /**
     * Round 9 R9-1 (REVIEW_7C F10, device LEDGER row 5) — a payment request in a Shielded chat: one on-chain transaction
     * carrying the request memo ([paymentRequestSendCost]). A request in a Tunnel or Open chat goes over the relays, free,
     * and is never asked about. A Retry of a failed request keeps [PAYMENT_REQUEST_RETRY].
     */
    PAYMENT_REQUEST,

    /**
     * SR-A (fix-all 2.13.0, owner W-16 "Link on-chain always") — a photo, file or voice note in a Shielded chat: its link
     * and key go inside one Shielded transaction (two when our key exchange is still owed), never over the relays. Asked
     * with the exact price before anything is uploaded.
     */
    SHIELDED_ATTACHMENT,

    /**
     * ZC-283 — the same status set again after a broadcast that did not reach everyone: one on-chain memo per Shielded
     * contact it has not reached yet. A contact that has it, or may have it, is not counted and not paid again.
     */
    STATUS_BROADCAST_REST,

    /** SR-A S-A10 — the same, sent again: the earlier try was not paid, and this sends it once more. */
    SHIELDED_ATTACHMENT_RETRY,

    /**
     * ZC-545 — accepting a contact's new key in a Shielded chat when the key came in a key exchange the contact started:
     * our key exchange, one on-chain message, so their phone holds our key. (A Tunnel or Open accept asks
     * [KEY_CHANGE_RECONNECT].)
     */
    KEY_CHANGE_ACCEPT,

    /**
     * ZC-601 — the first message New Chat sends to an address in Shielded mode: one on-chain transaction, asked with
     * its exact price before anything is built, stored or paid.
     */
    NEW_CHAT_FIRST_MESSAGE,

    /**
     * ZC-601 — the same in any other mode that puts New Chat's first message on-chain (Tunnel): the text adds that the
     * Tunnel handshake comes after it and is not part of this price.
     */
    NEW_CHAT_FIRST_MESSAGE_TUNNEL,

    /**
     * F8 (ZC-112) — the Retry of the "Key exchange not sent" notice: one setup message of the chat (our key exchange, its
     * reply or our relay identity) was proven never to have reached the chain, and the user asks to send it again. One
     * on-chain transaction, asked with its price; nothing is sent again without this answer.
     */
    HANDSHAKE_RETRY,

    /**
     * ZC-1233 — the answer to a contact's key exchange costs more than the price it was shown or allowed at. Asked with the
     * true price (exact): Pay pays that figure, Cancel pays nothing and the chat stays as it is.
     */
    KEX_ANSWER_TRUE_PRICE,

    /**
     * ZC-1235 — our own key exchange or relay identity message costs more than the price every text quotes. Asked with the
     * true price (exact): Pay pays that figure, Cancel pays nothing and the chat stays as it is.
     */
    OWN_SETUP_TRUE_PRICE,

    /**
     * ZC-1478 (W-122, M1) — the first on-chain message in a chat the contact switched to Shielded: one transaction, asked
     * with its price once; the answer clears the mark, so later messages ask only as any Shielded message does.
     */
    PEER_MOVED_TO_SHIELDED,
}

/**
 * What the confirmation shows: how many on-chain transactions [action] sends and the least they cost in all.
 * [totalCost] is a floor, not an estimate: every transaction's outputs plus the ZIP 317 fee of a one-input
 * send; a wallet whose balance sits in several notes pays a larger network fee, so the screen says "at least".
 */
data class PaidActionConfirmation(
    val action: PaidAction,
    val onChainMessages: Int,
    val totalCost: Zatoshi,
    /**
     * Round 10b R10B-1 (F-R9C-1) — a [PaidAction.FIRST_MESSAGE] whose count includes our ZBOOT, the on-chain transaction
     * that sends our relay identity after the contact's KEXACK (their relay key is unknown). The text says so.
     */
    val includesRelayIdentity: Boolean = false,
    /**
     * T3b-1 — a [PaidAction.MODE_CHANGE] whose count includes the chat setup the switch pays for (our KEX, our ZBOOT, one
     * of them sent only after the contact answers), not only the mode change itself. The text says so.
     */
    val includesChatSetup: Boolean = false,
    /**
     * SR-A — [totalCost] is the exact price the send will pay, not a floor: the question says "You pay exactly this" and
     * names [networkFee] (the SDK's price of the real transaction shape). The send refuses before the spend auth if the
     * proposal differs (ChatSendQuote).
     */
    val exact: Boolean = false,
    /** SR-A — the network fee inside an [exact] total (all transactions together); null for a floor. */
    val networkFee: Zatoshi? = null,
    /** SR-A — how many memos the attachment's one transaction carries (the question names more than one). */
    val memos: Int = 1,
    /**
     * T5b X-06 — a [PaidAction.SHIELDED_ATTACHMENT_RETRY] of a row whose earlier try paid its owed key exchange before
     * the link was refused: the text says the earlier try paid for the key exchange only, not "was not paid".
     */
    val keyExchangeAlreadyPaid: Boolean = false,
    /**
     * ZC-535 — a [PaidAction.KEY_CHANGE_RECONNECT], or (ZC-545) a [PaidAction.KEY_CHANGE_ACCEPT], with a contact the app
     * knows to be on ZChat 2.12.4 or older (its
     * newest relay rumor carried no Ironwood capability tag): the text adds that they won't see these on-chain messages
     * until they update. The count and the total are the same. The count is the messages due now: our key exchange and
     * our relay identity, each left out when it already went out in the last hour.
     */
    val contactIsOlder: Boolean = false,
    /**
     * ZC-1272 — this question covers a whole chat: [setupLegs] secure setup transactions (our key exchange, our relay
     * identity) of at most [setupLegCost] each and the message at [messageLegCost]. The screen names each figure. Null on
     * every question that covers one thing.
     */
    val setupLegs: Int = 0,
    val setupLegCost: Zatoshi? = null,
    val messageLegCost: Zatoshi? = null,
    /** ZC-1306 — the funds are still confirming: Pay saves the message, which goes out by itself once they can pay it. */
    val waitsForFunds: Boolean = false,
) {
    /** The five-field shape the confirmations had before SR-A (a test builds one by its parameter count). */
    constructor(
        action: PaidAction,
        onChainMessages: Int,
        totalCost: Zatoshi,
        includesRelayIdentity: Boolean,
        includesChatSetup: Boolean,
    ) : this(action, onChainMessages, totalCost, includesRelayIdentity, includesChatSetup, exact = false)

    /** SR-A S-A3 — the ZChat fee inside an [exact] total: one platform-fee output per transaction. */
    val zchatFee: Zatoshi
        get() = Zatoshi(CreateChunkedMessageProposalUseCase.PLATFORM_FEE_MIN_ZATOSHI * onChainMessages)

    /** SR-A S-A3 — what is delivered to the contact inside an [exact] total: the rest after both fees. */
    val delivered: Zatoshi
        get() = Zatoshi((totalCost.value - (networkFee?.value ?: 0L) - zchatFee.value).coerceAtLeast(0L))
}

/** The least one on-chain chat transaction of [memoCount] memo outputs costs (outputs, platform fee, network fee). */
fun onChainSendMinimumCost(
    memoCount: Int = 1,
    amountPerOutput: Zatoshi = CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT,
): Zatoshi = CreateChunkedMessageProposalUseCase.minimumRequiredSpendable(memoCount, amountPerOutput)

/**
 * Round 9 R9-1 — what sending a payment request costs in a chat of [mode]: in a Shielded chat the one one-memo on-chain
 * transaction the request goes out in (the dust output, the platform fee and the network fee; the figure the send's own
 * question quotes), null in a Tunnel or Open chat, where the request goes over the relays and is never sent on-chain.
 */
fun paymentRequestSendCost(mode: ConversationMode): Zatoshi? =
    if (mode == ConversationMode.VAULT) onChainSendMinimumCost() else null

/** R8-fixup F1: the AES-GCM tag the ratchet appends to every ciphertext (E2ERatchet, 128 bits). */
const val RATCHET_GCM_TAG_BYTES = 16

/**
 * R8-fixup F1 / Round 10a F-R9P-1 — how many memos the on-chain builder packs [text] into: the ratchet's ciphertext when
 * [encrypted] (both E2E keys held; an E2E1 wire of exactly the same length: AES-GCM adds a 16-byte tag, the header is
 * fixed), the text itself otherwise; sized as the INIT when [isInit], else as a reply; [self] is our own address. The one
 * rule both the send's cost question and the composer's cost row use, so the row can never show less than the send asks.
 */
fun onChainMemoCountFor(
    text: String,
    encrypted: Boolean,
    isInit: Boolean,
    self: String?,
): Int {
    val encryptedWire =
        CiphertextWireFormat.serialize(Ciphertext(0, 0L, ByteArray(text.toByteArray(Charsets.UTF_8).size + RATCHET_GCM_TAG_BYTES)))
    // The encrypted wire is the longer one and the INIT's first memo the smallest: if that fits one memo, any send does.
    if (ZMSGProtocol.calculateV4ChunkCount(encryptedWire, true, self) <= 1) return 1
    return ZMSGProtocol.calculateV4ChunkCount(if (encrypted) encryptedWire else text, isInit, self)
}

/** A confirmation for [onChainMessages] one-memo transactions (every control, reaction and handshake). */
fun paidActionConfirmation(
    action: PaidAction,
    onChainMessages: Int,
): PaidActionConfirmation =
    PaidActionConfirmation(action, onChainMessages, Zatoshi(onChainSendMinimumCost().value * onChainMessages))

/**
 * R2-2 (c) — the least one group message costs for [members] members: one one-memo transaction each (members
 * reachable over Nostr are free, so this is what the message costs when none of them is).
 */
fun groupMessageCostFloor(members: Int): Zatoshi = Zatoshi(onChainSendMinimumCost().value * members.coerceAtLeast(1))

/**
 * R5-1 — what one message to a group costs on-chain RIGHT NOW. [recipients] is how many members the
 * message goes to; [onChainMessages] is how many of those have no free relay route and are therefore paid
 * for; [totalCost] is [onChainMessages] times one memo output, and is zero when nobody is paid for.
 *
 * Both counts come from the send path's own recipient list, so the composer can never show a number the
 * send then disagrees with.
 */
data class GroupMessageCost(
    val recipients: Int,
    val onChainMessages: Int,
    val totalCost: Zatoshi,
    /**
     * Round 8 P5 (PHONE_TRIAGE F5a) — ACTIVE members the message is NOT sent to because they can't read it yet (owed
     * the current key, with no secure session to send it under). Not in [recipients], never paid for.
     */
    val cannotRead: Int = 0,
    /**
     * Round 8 P7 (PHONE_TRIAGE F4) — true when this phone is not the group's admin. A member's roster holds only the
     * members it has heard from (a compact invite carries no roster), so [recipients] may be fewer than the group, and
     * the members this phone has not heard from yet do not get the message. The cost line and the header say so.
     */
    val knownOnly: Boolean = false,
)

/**
 * R5-1 — the least [onChainMessages] group-message memos cost. Unlike [groupMessageCostFloor] this does
 * NOT round up to one: a message every member can be reached for free costs nothing, and quoting 0.00017
 * ZEC for it would be a lie. A group memo is sent rawMemo (one memo output, whatever the text), so it is
 * exactly [onChainSendMinimumCost] each — the same sizer the send itself uses.
 */
fun groupMessageOnChainCost(onChainMessages: Int): Zatoshi =
    Zatoshi(onChainSendMinimumCost().value * onChainMessages.coerceAtLeast(0))
