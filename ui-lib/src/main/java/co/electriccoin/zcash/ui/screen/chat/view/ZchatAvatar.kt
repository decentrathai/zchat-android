package co.electriccoin.zcash.ui.screen.chat.view

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.filesharing.ImageUploadPrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * What a [ZchatAvatar] renders: a peer contact (keyed by address), a group (keyed by groupId), or
 * the user's own (self) avatar.
 */
sealed interface ZchatAvatarRef {
    data class Contact(val address: String) : ZchatAvatarRef

    /** [groupId] is null for a group that doesn't exist yet (e.g. the Create Group header). */
    data class Group(val groupId: String?) : ZchatAvatarRef

    data object Self : ZchatAvatarRef
}

/**
 * THE avatar composable for every chat surface (Phase 1: local-only images).
 *
 * Shows the locally stored image from [AvatarStore] when one exists, else the pre-existing
 * placeholder style for that kind:
 *  - contact → initials on the deterministic per-address accent ([avatarColorForAddress]),
 *    falling back to an address-derived hex pair (never the old all-identical "U1");
 *  - group → the cyan→green gradient circle with the Groups glyph;
 *  - self → a Person glyph on the primary accent.
 *
 * Observes [AvatarStore.version] so any set/remove recomposes every visible avatar.
 *
 * @param displayName pass a REAL name only (nickname/contact name) — pass null when the caller only
 *   has an address so the placeholder uses the address-derived pair instead of "U1...".
 * @param solid selected-state styling: solid accent background + on-accent content (used by the
 *   compose-recipient and group-member pickers).
 */
@Composable
fun ZchatAvatar(
    ref: ZchatAvatarRef,
    displayName: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
) {
    val store = koinInject<AvatarStore>()
    val version by store.version.collectAsState()

    // Decode off the main thread; keyed on the ref AND the store version so edits refresh in place.
    val image by produceState<ImageBitmap?>(initialValue = null, ref, version) {
        value = withContext(Dispatchers.IO) {
            val bytes = when (ref) {
                is ZchatAvatarRef.Contact -> store.getContactAvatar(ref.address)
                is ZchatAvatarRef.Group -> ref.groupId?.let { store.getGroupAvatar(it) }
                is ZchatAvatarRef.Self -> store.getSelfAvatar()
            }
            bytes?.let { data ->
                runCatching { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }
                    .getOrNull()
            }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = displayName ?: "Avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            when (ref) {
                is ZchatAvatarRef.Contact -> ContactPlaceholder(ref.address, displayName, size, solid)
                is ZchatAvatarRef.Group -> GroupPlaceholder(size)
                is ZchatAvatarRef.Self -> SelfPlaceholder(size)
            }
        }
    }
}

@Composable
private fun ContactPlaceholder(
    address: String,
    displayName: String?,
    size: Dp,
    solid: Boolean,
) {
    val accent = avatarColorForAddress(address)
    val initials = remember(address, displayName) { avatarInitials(address, displayName) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (solid) accent else accent.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * INITIALS_FONT_FRACTION).sp,
            color = if (solid) chatColors().textOnAccent else accent,
        )
    }
}

@Composable
private fun GroupPlaceholder(size: Dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF00D9FF),
                        Color(0xFF00E676),
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Groups,
            contentDescription = "Group",
            tint = Color.White,
            modifier = Modifier.size(size * ICON_FRACTION),
        )
    }
}

@Composable
private fun SelfPlaceholder(size: Dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(chatColors().primary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "My avatar",
            tint = chatColors().primary,
            modifier = Modifier.size(size * ICON_FRACTION),
        )
    }
}

/**
 * Initials for the placeholder. A real name yields the first letters of up to two words (single
 * word → its first two characters). Without a name: every unified address starts with "u1", so
 * derive a deterministic 2-char hex pair from the address hashCode instead — unique per peer,
 * stable across re-renders (this is the ChatListView fix for the all-"U1" fallback, applied
 * uniformly).
 */
internal fun avatarInitials(address: String, displayName: String?): String {
    val name = displayName?.trim().orEmpty()
    if (name.isNotEmpty()) {
        val words = name.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        return if (words.size >= 2) {
            words.take(2).joinToString("") { it.first().uppercaseChar().toString() }
        } else {
            name.take(2).uppercase()
        }
    }
    val h = address.hashCode().toLong() and 0xFFFFL
    return "%04X".format(h).substring(0, 2)
}

/**
 * Reads + pre-downscales a picked image URI on IO, reusing the OOM-safe file-sharing prep (bounds
 * decode + [co.electriccoin.zcash.ui.screen.chat.filesharing.BitmapSampling] sampling). The result
 * feeds an `AvatarStore.set*Avatar`, which performs the final 256x256 crop/compress. Returns null
 * when the source is unreadable or oversized.
 */
suspend fun loadAvatarBytesFromUri(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        ImageUploadPrep.prepare(
            contentResolver = context.contentResolver,
            uri = uri,
            targetEdgePx = AvatarStore.AVATAR_SIZE_PX * 2,
        )
    }

/**
 * Shared photo chooser used by the self-avatar (chat list top bar) and the admin-only group-avatar
 * pencil. The contact photo actions live inline in the existing nickname/edit-contact dialog instead
 * (ChatDetailView).
 *
 * Two distinct states driven by [hasPhoto]:
 *  - hasPhoto = false → "Set up photo" (there is nothing to change or remove yet), with a Cancel.
 *  - hasPhoto = true  → "Change photo" + "Remove photo".
 * The old dialog always offered Change/Remove even with no photo set, which read as if a photo
 * existed when it didn't.
 */
@Composable
fun AvatarPhotoDialog(
    title: String,
    hasPhoto: Boolean,
    onDismiss: () -> Unit,
    onPickNew: () -> Unit,
    onRemove: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPhoto) title else "Set up photo") },
        text = {
            Text(
                text = if (hasPhoto) {
                    "The photo is stored only on this device."
                } else {
                    "Add a photo — it's stored only on this device."
                },
                fontSize = 13.sp,
                color = chatColors().textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onPickNew()
            }) { Text(if (hasPhoto) "Change photo" else "Choose photo") }
        },
        dismissButton = {
            if (hasPhoto) {
                TextButton(onClick = {
                    onDismiss()
                    onRemove()
                }) { Text("Remove photo", color = chatColors().error) }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private const val INITIALS_FONT_FRACTION = 0.38f
private const val ICON_FRACTION = 0.5f
private val WHITESPACE_REGEX = Regex("\\s+")
