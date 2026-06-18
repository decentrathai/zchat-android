package co.electriccoin.zcash.ui.screen.chat.view

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode

/**
 * One-shot onboarding dialog that explains the three privacy modes the first time a
 * user opens the app. Survives recompositions because it's keyed on
 * [ZchatPreferences.hasSeenModeIntro].
 */
@Composable
fun ConversationModeIntroDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) { Text("Got it") }
        },
        title = {
            Text("Choose how each chat travels", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                ModeIntroRow(
                    icon = Icons.Default.Shield,
                    title = "Vault",
                    body = "Every message is wrapped in a Zcash shielded transaction with our " +
                        "forward-secret ratchet on top. Maximum metadata privacy. Costs ~0.00001 " +
                        "ZEC per message. No voice/video.",
                )
                Spacer(Modifier.height(12.dp))
                ModeIntroRow(
                    icon = Icons.Default.Lock,
                    title = "Tunnel",
                    body = "First message is a shielded handshake that hands the recipient your " +
                        "NOSTR pubkey. Replies and voice calls flow through encrypted NOSTR DMs — " +
                        "free and instant after the one-time bootstrap.",
                )
                Spacer(Modifier.height(12.dp))
                ModeIntroRow(
                    icon = Icons.Default.LockOpen,
                    title = "Open",
                    body = "Free, instant encrypted NOSTR DMs — but ONLY when you already have the " +
                        "peer's NOSTR key (npub QR / paste). If you only scanned their Zcash address, " +
                        "there's no key to go free over NOSTR, so the first message is sent as a plain " +
                        "on-chain shielded memo (costs ZEC). Voice calls allowed.",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "All three modes are end-to-end encrypted. The mode is picked per " +
                        "conversation and can be changed anytime in the chat header.",
                    fontSize = 13.sp,
                    color = NightwireColors.TextSecondary,
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
    val (title, body) = when (mode) {
        ConversationMode.OPEN ->
            "Open mode uses a public relay" to
                "Your messages stay end-to-end encrypted, but they travel over a public NOSTR relay. " +
                "Gift-wrapping hides who you're talking to, yet delivery depends on that relay staying " +
                "online. Keep this connection with people you trust, and rotate your key from time to " +
                "time for stronger forward privacy."
        ConversationMode.TUNNEL ->
            "Tunnel mode: paid handshake, then a private relay" to
                "A one-time on-chain key exchange (a small ZEC fee) sets up the channel; after that, " +
                "messages and calls flow free over a private NOSTR relay. Same relay transport as Open, " +
                "but bound to a key only you and your contact share. Rotate your key periodically for " +
                "stronger forward privacy."
        ConversationMode.VAULT -> return // never shown for Vault
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text("Got it") } },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(body, fontSize = 13.sp, color = NightwireColors.TextSecondary) },
    )
}

@Composable
private fun ModeIntroRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NightwireColors.AccentPrimary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(text = body, fontSize = 13.sp, color = NightwireColors.TextSecondary)
        }
    }
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
    var selected by remember { mutableStateOf(current) }
    val openBlocked = { m: ConversationMode -> m == ConversationMode.OPEN && !allowOpen }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onPick(selected); onDismiss() },
                enabled = !openBlocked(selected),
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Conversation mode") },
        text = {
            Column {
                ConversationMode.entries.forEach { mode ->
                    val disabled = openBlocked(mode)
                    val (oneLiner, _) = modeBlurb(mode)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (disabled) Modifier else Modifier.clickable { selected = mode })
                            .alpha(if (disabled) 0.45f else 1f)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(
                            selected = selected == mode,
                            onClick = { selected = mode },
                            enabled = !disabled,
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(text = mode.label(), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (disabled) {
                                    "Needs the peer's NOSTR key first — switch to Tunnel to exchange it, then Open becomes available."
                                } else oneLiner,
                                fontSize = 13.sp,
                                color = NightwireColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Single-line title for the radio button labels. */
fun ConversationMode.label(): String = when (this) {
    ConversationMode.VAULT -> "Vault"
    ConversationMode.TUNNEL -> "Tunnel"
    ConversationMode.OPEN -> "Open"
}

private fun modeBlurb(mode: ConversationMode): Pair<String, String> = when (mode) {
    ConversationMode.VAULT -> "Every message on-chain. Slow + costs ZEC. Max metadata privacy." to "vault"
    ConversationMode.TUNNEL -> "One shielded handshake, then free NOSTR DMs + voice." to "tunnel"
    ConversationMode.OPEN -> "Free NOSTR DMs when you hold the peer's NOSTR key — otherwise an on-chain memo. Voice OK." to "open"
}
