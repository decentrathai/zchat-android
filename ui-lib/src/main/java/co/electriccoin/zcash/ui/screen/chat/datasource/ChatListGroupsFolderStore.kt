package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences

/**
 * ZC-1104 (W-79): whether the chat list's Groups row is open. Collapsed until the user opens it; the choice is kept
 * across screens and restarts. [prefs] is the main ZCHAT preferences (ZchatPreferences.chatListUiPrefs), so a Reset
 * or a Destroy clears it with the rest and the row starts collapsed again.
 */
class ChatListGroupsFolderStore(
    private val prefs: SharedPreferences
) {
    fun isExpanded(): Boolean = prefs.getBoolean(KEY_EXPANDED, false)

    fun setExpanded(expanded: Boolean) {
        prefs.edit().putBoolean(KEY_EXPANDED, expanded).apply()
    }

    private companion object {
        const val KEY_EXPANDED = "chat_list_groups_folder_expanded"
    }
}
