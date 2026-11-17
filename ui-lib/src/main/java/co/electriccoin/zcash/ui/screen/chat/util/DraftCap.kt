package co.electriccoin.zcash.ui.screen.chat.util

/**
 * The one cap on a chat message's text: the composer, the stored draft (a text shared in from another app lands
 * there) and the send all use it. It keeps a paste or a share from turning one tap on Send into a transaction of
 * hundreds of memo outputs (the protocol itself allows 1,000), each paying a dust output and a ZIP 317 action.
 */
internal const val MAX_MESSAGE_INPUT_CHARS = 5000

/** [text] cut to [MAX_MESSAGE_INPUT_CHARS] characters, the way the composer trims a paste (SHARED-TEXT-CAP). */
internal fun capDraft(text: String): String = text.take(MAX_MESSAGE_INPUT_CHARS)

/**
 * ZC-135: whether [stored] (the chat's saved draft) is longer than the composer allows, so the composer shows it cut.
 * The composer then says so once (chat_composer_trimmed), and keeps the longer saved draft until the user changes it.
 */
internal fun draftWasCut(stored: String?): Boolean = stored != null && stored.length > MAX_MESSAGE_INPUT_CHARS

/**
 * ZC-135: whether the composer's [current] text is saved over [stored]: when it differs, except when it is only the
 * cut the composer made of a longer saved draft (the autosave used to write that cut over the draft before the user
 * had touched it).
 */
internal fun draftNeedsSaving(
    current: String,
    stored: String?,
): Boolean {
    val saved = stored ?: ""
    if (current == saved) return false
    return !(draftWasCut(stored) && current == capDraft(saved))
}
