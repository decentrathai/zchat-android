package co.electriccoin.zcash.ui.screen.chat.model

/**
 * TX-2: which words the R2-2 (a) chat-request banner uses. Text only: the hold, Accept and what Accept pays do not
 * depend on it.
 */
object ChatRequestBanner {
    /**
     * True when we have written to this address in this chat: a message we sent that is not a local-only row (a
     * system note, an /ai prompt, a call-log entry). A stranger cannot make us send one, so the banner may say the
     * key exchange arrived "in this chat" instead of "New contact". It says "we write to this address", not "this key
     * exchange came from them": a KEX's sender address is claimed and self-signed, so the text names no sender.
     */
    fun isKnownChat(messages: List<ChatMessage>): Boolean =
        messages.any { it.isOutgoing && !it.isSystemNote && !it.isAiMessage && it.callLog == null }

    /**
     * ZC-822 (W-52): a saved contact's first key waits for Accept too, and its chat is "this chat" also before we have
     * written in it ([savedContact]: the address is in the address book). Still text only, and it still names no sender.
     */
    fun isKnownChat(
        messages: List<ChatMessage>,
        savedContact: Boolean,
    ): Boolean = savedContact || isKnownChat(messages)
}
