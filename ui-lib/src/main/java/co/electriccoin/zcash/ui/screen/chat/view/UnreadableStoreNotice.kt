package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R

/**
 * ZC-733-B (store-opener design, revision 2a, section 3.6 item 6): the lines a screen shows while the contact book
 * ([namesUnreadable]) or the picture index ([picturesUnreadable]) cannot be read, names first. Nothing when both read.
 */
fun unreadableStoreLines(
    namesUnreadable: Boolean,
    picturesUnreadable: Boolean,
): List<Int> =
    listOfNotNull(
        R.string.chat_list_names_unreadable.takeIf { namesUnreadable },
        R.string.chat_list_pictures_unreadable.takeIf { picturesUnreadable },
    )

/**
 * ZC-733-B: says that saved contact names (or profile pictures) cannot be read, where they would show: the chat
 * list's banner column, and above the saved contacts of New Chat, the group member pickers and the share picker
 * (names only there). Shows nothing while both can be read.
 */
@Composable
fun UnreadableStoreNotice(
    namesUnreadable: Boolean,
    picturesUnreadable: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val lines = unreadableStoreLines(namesUnreadable, picturesUnreadable)
    if (lines.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        lines.forEach { line ->
            Text(text = stringResource(line), fontSize = 13.sp, color = chatColors().textSecondary)
        }
    }
}
