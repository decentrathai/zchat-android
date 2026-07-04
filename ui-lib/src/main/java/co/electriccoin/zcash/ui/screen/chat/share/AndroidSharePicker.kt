package co.electriccoin.zcash.ui.screen.chat.share

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.screen.chat.ChatDetail
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.view.ZchatAvatar
import co.electriccoin.zcash.ui.screen.chat.view.ZchatAvatarRef
import co.electriccoin.zcash.ui.screen.chat.view.chatColors
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ChatViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * In-app Share picker. The payload is already staged in [PendingShareStore.pending] (an OS share, an AI
 * "Send via ZCHAT", or a chat-file forward). Here the user picks a direct recipient; on pick we:
 *  - text  → land it as a composer DRAFT on that chat (never auto-sent) and open the chat;
 *  - images → arm the delivery in [PendingShareStore] and open the chat, which sends each via the
 *             existing handlePickedImage path.
 *
 * Recipients are the EXISTING direct conversations + saved contacts (both use the handlePickedImage /
 * saveDraft peer-address API). Group image-send has no equivalent per-image API, so groups are omitted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidSharePicker() {
    val cc = chatColors()
    val context = LocalContext.current
    val navigationRouter = koinInject<NavigationRouter>()
    val contactBook = koinInject<ContactBook>()
    val viewModel = koinViewModel<ChatViewModel>()
    val chatListState by viewModel.chatListState.collectAsStateWithLifecycle()

    // Snapshot the pending share once on entry. If it's already gone (process death / double-open), bail
    // straight back rather than showing an empty picker that would send nothing.
    val share = remember { PendingShareStore.pending.value }
    androidx.compose.runtime.LaunchedEffect(share) {
        if (share == null) {
            Toast.makeText(context, "Nothing to share.", Toast.LENGTH_SHORT).show()
            navigationRouter.back()
        }
    }
    if (share == null) return

    var query by remember { mutableStateOf("") }

    // Build the recipient list: direct conversations first (most likely target), then any saved contacts
    // that don't already have a conversation. De-dup by address.
    val recipients = remember(chatListState, query) {
        val fromConversations = (chatListState as? ChatListState.Success)?.conversations
            ?.map { RecipientRow(address = it.peerAddress, name = it.contactName) }
            .orEmpty()
        val convAddresses = fromConversations.map { it.address }.toHashSet()
        val fromContacts = contactBook.getAllContacts()
            .filter { it.address !in convAddresses }
            .map { RecipientRow(address = it.address, name = it.name) }
        (fromConversations + fromContacts)
            .distinctBy { it.address }
            .filter { row ->
                if (query.isBlank()) true
                else (row.name?.contains(query, ignoreCase = true) == true) ||
                    row.address.contains(query, ignoreCase = true)
            }
    }

    val headerLabel = when (share) {
        is PendingShareStore.PendingShare.Text -> "Send text to…"
        is PendingShareStore.PendingShare.Images ->
            if (share.files.size > 1) "Send ${share.files.size} images to…" else "Send image to…"
    }

    fun deliverTo(address: String) {
        when (share) {
            is PendingShareStore.PendingShare.Text -> {
                // Land as a DRAFT — the chat opens with the text pre-filled, user reviews then taps Send.
                viewModel.saveDraft(address, share.text)
                PendingShareStore.clearPending()
                navigationRouter.replace(ChatDetail(address))
            }
            is PendingShareStore.PendingShare.Images -> {
                // Arm the images for the target chat; AndroidChatDetail consumes + sends them sequentially.
                PendingShareStore.armImages(address, share.files)
                PendingShareStore.clearPending()
                navigationRouter.replace(ChatDetail(address))
            }
        }
    }

    Scaffold(
        containerColor = cc.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        headerLabel,
                        fontWeight = FontWeight.Bold,
                        fontFamily = RajdhaniFontFamily,
                        color = cc.textPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        PendingShareStore.clearPending()
                        navigationRouter.back()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cc.textPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cc.surface),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Search contacts", color = cc.textSecondary) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = cc.bgInput,
                    unfocusedContainerColor = cc.bgInput,
                    focusedTextColor = cc.textPrimary,
                    unfocusedTextColor = cc.textPrimary,
                    cursorColor = cc.primary,
                    focusedBorderColor = cc.borderActive,
                    unfocusedBorderColor = cc.borderDefault,
                ),
            )

            if (recipients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = cc.textTertiary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (query.isBlank()) "No chats or contacts yet" else "No matches",
                            fontSize = 15.sp,
                            color = cc.textSecondary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Start a chat first, then share here.",
                            fontSize = 13.sp,
                            color = cc.textTertiary,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(recipients, key = { it.address }) { row ->
                        RecipientItem(row = row, onClick = { deliverTo(row.address) })
                    }
                }
            }
        }
    }
}

private data class RecipientRow(val address: String, val name: String?)

@Composable
private fun RecipientItem(row: RecipientRow, onClick: () -> Unit) {
    val cc = chatColors()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cc.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZchatAvatar(
                ref = ZchatAvatarRef.Contact(row.address),
                displayName = row.name,
                size = 40.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name ?: "${row.address.take(16)}…${row.address.takeLast(12)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cc.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.name != null) {
                    Text(
                        text = "${row.address.take(16)}…${row.address.takeLast(12)}",
                        fontSize = 13.sp,
                        color = cc.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
