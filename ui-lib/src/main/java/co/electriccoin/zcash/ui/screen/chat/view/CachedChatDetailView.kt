package co.electriccoin.zcash.ui.screen.chat.view

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import java.time.ZoneId

/**
 * ZC-946 part 2 (PERF-SPEC revision 2 section 5): a chat drawn from the last finished pass (CachedSnapshotMask) while
 * this screen's own pass runs. Read-only: no composer, no menu, no Retry, Pay, download, call, mode or timer. The
 * header and every row whose state is not known yet say "Checking…"; a mined row keeps its tick. The full chat screen
 * replaces it as soon as the pass ends.
 */
@Composable
fun CachedChatDetailView(
    conversation: Conversation,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = chatColors()
    val checking = stringResource(R.string.chat_cached_checking)
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back_navigation_content_description),
                    tint = colors.textPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contactDisplayName(conversation.displayName),
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = checking, color = colors.textSecondary, fontSize = 12.sp)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(conversation.messages.asReversed(), key = { it.id }) { message ->
                CachedBubble(message, checking)
            }
        }
    }
}

@Composable
private fun CachedBubble(
    message: ChatMessage,
    checking: String,
) {
    val colors = chatColors()
    val onLightSent = message.isOutgoing && colors.isLight
    val textColor = if (onLightSent) colors.textOnAccent else colors.textPrimary
    val timeColor = if (onLightSent) colors.textOnAccent.copy(alpha = 0.8f) else colors.textSecondary
    // QA2 Q2-47: the time follows the phone's 12/24-hour setting.
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val locale = rememberDesiredFormatLocale()
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        contentAlignment = if (message.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (message.isOutgoing) colors.outgoingBubble else colors.incomingBubble)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text = if (message.bodyChecking) checking else message.text, color = textColor, fontSize = 15.sp)
            Row(modifier = Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = clockTime(message.timestamp.atZone(ZoneId.systemDefault()), locale, is24Hour),
                    color = timeColor,
                    fontSize = 11.sp,
                )
                if (message.isOutgoing) {
                    Spacer(modifier = Modifier.width(4.dp))
                    val mined =
                        !message.statusChecking &&
                            (message.status == MessageStatus.READ || message.status == MessageStatus.CONFIRMED)
                    if (mined) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = null,
                            tint = timeColor,
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        Text(text = checking, color = timeColor, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
