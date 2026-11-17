package co.electriccoin.zcash.ui.screen.chat.view

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ModeIntroCopy
import co.electriccoin.zcash.ui.screen.chat.model.ModeIntroItem
import co.electriccoin.zcash.ui.screen.chat.model.PendingModeSwitch

/**
 * The Shielded row of [ConversationModeIntroDialog]; a JVM test pins it. SR-D (ZC-109): the dialog shows
 * chat_mode_intro_shielded_body, whose English is exactly this. Calls are placed in Shielded chats once the relay key is
 * known, and SR-A puts an attachment's link inside a transaction.
 */
internal const val MODE_INTRO_SHIELDED_BODY =
    "Every message is wrapped in a Zcash shielded transaction with our " +
        "per-message-key ratchet on top. The most private of the three modes. A short Shielded message costs " +
        "at least 0.00017 ZEC: the Zcash network fee, a 0.00001 ZEC ZChat fee, and 0.00001 ZEC delivered with " +
        "the message. ZChat's fee wallet sees when fees arrive and how many, not who sent them. The server your " +
        "wallet syncs from sees your IP address when you send. Photo, file and voice-note links travel inside a " +
        "shielded transaction too; the files themselves go, encrypted, to public file hosts. Once your contact's " +
        "relay key is known, calls are set up over the relays, as in Tunnel and Open."

/**
 * The Tunnel row of [ConversationModeIntroDialog]; a JVM test pins it.
 *
 * ZC-632: the count is what a switch to Tunnel can make on-chain in a chat with no finished handshake (the mode
 * change, our key exchange, and our relay identity when it cannot go over the relays: two or three; ChatViewModel's
 * mode-switch plan; QA2 Q2-29 / ZC-983 says both and that the price is shown first), and 0.00017 ZEC is the least one
 * such transaction costs (onChainSendMinimumCost). NewChatTunnelTextsCountEveryOnChainPaymentTest takes both from the
 * code. ZC-1482: the dialog shows chat_mode_intro_tunnel_body, whose English is exactly this.
 */
internal const val MODE_INTRO_TUNNEL_BODY =
    "Tunnel: a secure handshake, then free messages through the same Nostr relays as Open. If you never " +
        "finished a handshake with this contact, switching to Tunnel costs two or three on-chain payments, each at " +
        "least 0.00017 ZEC; the exact number is shown before you pay, and both of you need ZChat 2.13 or newer."

/**
 * The Open row of [ConversationModeIntroDialog]; a JVM test pins it. ZC-1482: the dialog shows chat_mode_intro_open_body,
 * whose English is exactly this.
 */
internal const val MODE_INTRO_OPEN_BODY =
    "Free, end-to-end encrypted messages through three Nostr relays, one run by ZChat and two by other " +
        "people. Relays can't read them. But each relay can see your chat key (one key for all your chats), " +
        "your IP address, when you're online, and which chat keys you send to. Needs the contact's ZCHAT code " +
        "(scan their QR or paste their zchat: link), which carries their chat key; with only a bare Zcash " +
        "address, use Tunnel instead. Voice calls allowed."

/**
 * One-shot onboarding dialog that explains the three privacy modes the first time a
 * user opens the app. Survives recompositions because it's keyed on
 * [ZchatPreferences.hasSeenModeIntro].
 */
@Composable
fun ConversationModeIntroDialog(onDismiss: () -> Unit) {
    val cc = chatColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.chat_mode_note_got_it)) }
        },
        title = {
            // ZC-1482: every text of this dialog is a resource with Spanish; the mode names come from label().
            Text(stringResource(R.string.chat_mode_intro_title), fontWeight = FontWeight.Bold)
        },
        text = {
            // ZC-412: the text slot is height-limited; at large fonts its content scrolls instead of being cut.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // ZC-1457: three short rows, collapsed; a tap opens a row's long text.
                Text(stringResource(R.string.chat_mode_intro_short_hint), fontSize = 13.sp, color = cc.textSecondary)
                ModeIntroCopy.rows.forEach { item ->
                    Spacer(Modifier.height(12.dp))
                    ModeIntroRow(item)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.chat_mode_intro_footer),
                    fontSize = 13.sp,
                    color = cc.textSecondary,
                )
            }
        },
    )
}

/**
 * One-time security note shown when a conversation is switched to a NOSTR transport (OPEN/TUNNEL).
 * VAULT never triggers this. Gated per (peer, mode) via ZchatPreferences.hasSeenModeSecurityNote so it
 * fires once on the mode change and never on plain re-opens (#178 Part A).
 */
@Composable
fun ModeSecurityNoteDialog(mode: ConversationMode, onDismiss: () -> Unit) {
    val (titleRes, bodyRes) = modeSecurityNoteRes(mode) ?: return // never shown for Vault
    val cc = chatColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.chat_mode_note_got_it)) } },
        title = { Text(stringResource(titleRes), fontWeight = FontWeight.Bold) },
        text = {
            // ZC-412: at 360 dp and font 1.3 the Open note was cut before "To go back" and a swipe did nothing.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(bodyRes), fontSize = 13.sp, color = cc.textSecondary)
            }
        },
    )
}

/**
 * T3b-2 (train T3 finding 9): the (title, body) resources [ModeSecurityNoteDialog] shows, in the phone's language.
 * Their English values are exactly [modeSecurityNoteCopy] (SpanishT3LeftoversTest), so the pinned copy is what an
 * English phone shows.
 */
internal fun modeSecurityNoteRes(mode: ConversationMode): Pair<Int, Int>? =
    when (mode) {
        ConversationMode.OPEN -> R.string.chat_mode_note_open_title to R.string.chat_mode_note_open_body
        ConversationMode.TUNNEL -> R.string.chat_mode_note_tunnel_title to R.string.chat_mode_note_tunnel_body
        ConversationMode.VAULT -> null
    }

/**
 * The English (title, body) of [ModeSecurityNoteDialog] for [mode], or null for Vault (never shown). Kept out of the
 * composable so a JVM test can pin the copy against what the transport really does; the dialog shows
 * [modeSecurityNoteRes], whose English values are exactly this (T3b-2).
 */
internal fun modeSecurityNoteCopy(mode: ConversationMode): Pair<String, String>? =
    when (mode) {
        ConversationMode.OPEN ->
            "Open mode uses public relays" to OPEN_DESCRIPTION
        ConversationMode.TUNNEL ->
            "Tunnel mode: a paid handshake, then public relays" to
                "What it costs: the handshake only. " + MODE_INTRO_TUNNEL_BODY + "\n\n" +
                "What relays can see: not your messages. They can see your chat key, your IP address, when you're " +
                "online, and which chat keys you send to. Calls are set up the same way.\n\n" +
                "How to go back: tap the mode under the contact's name and pick another mode."
        ConversationMode.VAULT -> null
    }

/** The Open description (FINAL_PLAN 2.4, COPY_DECK OP1-OP9), shown when a chat is switched to Open. */
internal const val OPEN_DESCRIPTION =
    "What it costs: nothing. Open: free, end-to-end encrypted messages through three Nostr relays, one run by ZChat " +
        "and two by other people. If one of those two stops working, ZChat switches to another public relay from a " +
        "list built into the app. Shielded messages you send, and the links to their photos, files and voice notes, " +
        "travel inside Zcash transactions, not through the relays.\n\n" +
        "What relays can see: not your messages. Relays can't read them. But each relay can see your chat key (one " +
        "key for all your chats), your IP address, when you're online, and which chat keys you send to. Together " +
        "that can show who you talk to and when. ZChat's relay deletes messages after 30 days; other relays may keep " +
        "them longer. ZChat drops free messages older than 30 days, so your contact should open ZChat within about 3 " +
        "weeks.\n\n" +
        "How to go back: tap the mode under the contact's name → Shielded. The relays still carry your chat key " +
        "setup, call setup, a switch of this chat's mode, and messages from a contact who uses Tunnel or Open. Your " +
        "contact's phone may switch its side of this chat to Open too, and older ZChat versions don't explain relays."

/**
 * Accept/keep prompt shown to the RECEIVER when a peer moves the chat to a PAID mode (Vault). Spells
 * out the cost so the choice is informed — the receiver either matches the paid mode or keeps their
 * current mode. Each side sends in its own mode, so declining still lets them read the peer's messages.
 */
@Composable
fun ModeSwitchRequestDialog(
    request: PendingModeSwitch,
    onAccept: () -> Unit,
    onKeep: () -> Unit,
) {
    val cc = chatColors()
    val newLabel = request.newMode.label()
    val curLabel = request.currentMode.label()
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.chat_mode_switch_request_title, newLabel), fontWeight = FontWeight.Bold) },
        text = {
            // ZC-412: scrolls at large fonts instead of being cut.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.chat_mode_switch_request_body, request.peerName, newLabel, curLabel),
                    fontSize = 13.sp,
                    color = cc.textSecondary,
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept) { Text(stringResource(R.string.chat_mode_switch_request_accept, newLabel)) }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.chat_mode_switch_request_keep, curLabel)) }
        },
    )
}

/**
 * The English body of [ModeSwitchRequestDialog], kept so a JVM test can pin the promise it makes. SR-D (ZC-109): the
 * dialog shows chat_mode_switch_request_body, which formats to exactly this. Calls stay (owner: keep them), and the
 * question is asked only for a switch to Shielded (handleModeControl), whose short message costs at least 0.00017 ZEC.
 */
internal fun modeSwitchRequestBody(
    peerName: String,
    newLabel: String,
    curLabel: String,
): String =
    "$peerName moved this chat to $newLabel.\n\n" +
        "In $newLabel, every message you send is a shielded Zcash transaction: each costs at least 0.00017 ZEC " +
        "and confirms on-chain. Calls are still set up over the relays.\n\n" +
        "Match it, or keep this chat on $curLabel? Each side sends in its own mode, so you can " +
        "stay on $curLabel and still read their messages, as long as you're both on ZChat 2.13 or newer. On " +
        "older versions, $newLabel messages don't show until they update."

/**
 * ZC-1457: one mode of the intro, collapsed to its name and a one-line subtitle. A tap shows the long text; the state
 * is saved, so a rotation keeps it.
 */
@Composable
private fun ModeIntroRow(item: ModeIntroItem) {
    val cc = chatColors()
    var expanded by rememberSaveable(item.mode) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = modeIntroIcon(item.mode),
            contentDescription = null,
            tint = cc.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.mode.label(), fontWeight = FontWeight.SemiBold)
            Text(text = stringResource(item.subtitleRes), fontSize = 13.sp, color = cc.textSecondary)
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(text = stringResource(item.bodyRes), fontSize = 13.sp, color = cc.textSecondary)
            }
        }
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = cc.textSecondary,
        )
    }
}

/** ZC-1457: the intro's icon per mode, the header mode chip's (never an open lock: every mode is encrypted). */
private fun modeIntroIcon(mode: ConversationMode): androidx.compose.ui.graphics.vector.ImageVector =
    when (mode) {
        ConversationMode.VAULT -> Icons.Default.Shield
        ConversationMode.TUNNEL -> Icons.Default.SyncAlt
        ConversationMode.OPEN -> Icons.Default.Public
    }

/**
 * Per-conversation mode picker. Renders three radio options + a brief one-liner each.
 */
@Composable
fun ConversationModePickerDialog(
    current: ConversationMode,
    onPick: (ConversationMode) -> Unit,
    onDismiss: () -> Unit,
    // OPEN delivers over NOSTR and REQUIRES the peer's NOSTR key. Until a handshake has exchanged it,
    // an OPEN send has nowhere to go and fails. Pass false to disable OPEN until the key exists; the
    // user is steered to Tunnel, which performs the on-chain handshake and then runs free over NOSTR.
    allowOpen: Boolean = true,
) {
    val cc = chatColors()
    var selected by remember { mutableStateOf(current) }
    val openBlocked = { m: ConversationMode -> m == ConversationMode.OPEN && !allowOpen }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onPick(selected); onDismiss() },
                enabled = !openBlocked(selected),
            ) { Text(stringResource(R.string.chat_mode_picker_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_mode_picker_cancel)) }
        },
        title = { Text(stringResource(R.string.chat_mode_picker_title)) },
        text = {
            // ZC-412: scrolls at large fonts instead of cutting the last option.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // QA2 Q2-92 (ZC-1665): one radio group; each row is one focus target that says "selected".
                Column(Modifier.selectableGroup()) {
                    ConversationMode.entries.forEach { mode ->
                        val disabled = openBlocked(mode)
                        // R6-3: the one-liner in the phone's language; the English resource is modeBlurb()'s text.
                        val oneLiner = stringResource(modeBlurbRes(mode))
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selected == mode,
                                        enabled = !disabled,
                                        role = Role.RadioButton,
                                        onClick = { selected = mode },
                                    ).alpha(if (disabled) 0.45f else 1f)
                                    .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(
                                selected = selected == mode,
                                onClick = null,
                                enabled = !disabled,
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(text = mode.label(), fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (disabled) stringResource(R.string.chat_mode_picker_open_needs_key) else oneLiner,
                                    fontSize = 13.sp,
                                    color = cc.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

/** Single-line title for the radio button labels. NOTE: VAULT's DISPLAY name is "Shielded"
 *  everywhere user-facing — the enum name stays VAULT (wire format + stored prefs depend on it). */
fun ConversationMode.label(): String = when (this) {
    ConversationMode.VAULT -> "Shielded"
    ConversationMode.TUNNEL -> "Tunnel"
    ConversationMode.OPEN -> "Open"
}

/**
 * R6-3 — the picker's one-liner for [mode] as a string resource. The English values are byte-for-byte
 * [modeBlurb]'s, which the privacy-claim tests pin; SpanishChatMenusGroupsAndComposerTextTest keeps them equal.
 */
@StringRes
internal fun modeBlurbRes(mode: ConversationMode): Int =
    when (mode) {
        ConversationMode.VAULT -> R.string.chat_mode_picker_desc_vault
        ConversationMode.TUNNEL -> R.string.chat_mode_picker_desc_tunnel
        ConversationMode.OPEN -> R.string.chat_mode_picker_desc_open
    }

/** The English one-liners the privacy-claim tests pin. Since R6-3 the picker shows [modeBlurbRes]. */
internal fun modeBlurb(mode: ConversationMode): Pair<String, String> =
    when (mode) {
        ConversationMode.VAULT -> "Every message on-chain, at least 0.00017 ZEC each. The most private mode." to "vault"
        // QA2 Q2-29 (ZC-983): the code's count, 2–3, as New Chat says. Q2-51 (ZC-1593): what costs no ZEC.
        ConversationMode.TUNNEL -> "2–3 on-chain payments, then free messages + voice through Nostr relays." to "tunnel"
        ConversationMode.OPEN ->
            "Free through Nostr relays from message #1 — texts and calls cost no ZEC. Needs the contact's chat " +
                "key." to "open"
    }
