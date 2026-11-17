package co.electriccoin.zcash.ui.screen.chat.model

/** ZC-1461: the two views of My code. */
enum class MyCodeTab {
    /** Free chat code: the zchat: contact code with our relay (Nostr) key; a friend who scans it chats free in Open. */
    CHAT,

    /** Shielded address: the plain address any Zcash wallet can pay (and, inside it, the transparent address). */
    ADDRESS,
}

/**
 * ZC-1461: which My code tabs exist and which one opens first. The free chat code exists only once our relay key is
 * derived ([supportsOpen]); it then comes first, since a newcomer wants to be reachable for a free chat.
 */
object MyCodeTabs {
    fun available(supportsOpen: Boolean): List<MyCodeTab> =
        if (supportsOpen) listOf(MyCodeTab.CHAT, MyCodeTab.ADDRESS) else listOf(MyCodeTab.ADDRESS)

    fun defaultTab(supportsOpen: Boolean): MyCodeTab = available(supportsOpen).first()
}
