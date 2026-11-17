package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.model.ChatListState

/**
 * ZC-946 part 2 (PERF-SPEC revision 2 section 5): the last finished history pass of this process, with the tag its
 * collection read (account key, our address, wallet-scope generation), so a new chat screen or the list after Wallet
 * shows it at once (masked, through ChatViewModel's display flow) instead of waiting for its own pass. Memory only: Destroy
 * ends the process, Reset raises the generation (the snapshot is then never shown and never rewritten for the old one).
 */
object ConversationSnapshotCache {
    data class Tag(
        val accountKey: String,
        val userAddress: String,
        val generation: Long,
    )

    class Entry(
        val tag: Tag,
        val state: ChatListState.Success,
    )

    @Volatile private var entry: Entry? = null

    fun put(
        tag: Tag,
        state: ChatListState.Success,
    ) {
        entry = Entry(tag, state)
    }

    fun get(): Entry? = entry

    fun resetForTest() {
        entry = null
    }
}
