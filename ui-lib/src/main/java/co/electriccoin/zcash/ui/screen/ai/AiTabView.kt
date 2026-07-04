package co.electriccoin.zcash.ui.screen.ai

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.mutableStateListOf
import co.electriccoin.zcash.ui.screen.chat.view.ChatColors
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.screen.chat.view.chatColors

@Composable
fun AiTabView(
    state: AiState,
    onSelectModel: (String) -> Unit,
    onSend: (String) -> Unit,
    onGenerateImage: (String) -> Unit,
    onSelectMode: (AiMode) -> Unit,
    onTopupClick: () -> Unit,
    onTopupHistoryClick: () -> Unit = {},
    onDismissError: () -> Unit,
    // History / gallery actions (persistent, user-controlled).
    onClearChat: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onShowHistory: (Boolean) -> Unit = {},
    onOpenConversation: (String) -> Unit = {},
    onDeleteConversation: (String) -> Unit = {},
    onDeleteImage: (String) -> Unit = {},
    onClearAllImages: () -> Unit = {},
    onRenameConversation: (String, String) -> Unit = { _, _ -> },
    onSetRetention: (Int) -> Unit = {},
    onRetry: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onStop: () -> Unit = {},
    onRefreshBalance: () -> Unit = {},
    onRefreshModels: () -> Unit = {},
    loadImageBitmap: (String) -> android.graphics.Bitmap? = { null },
    // "Send via ZCHAT": caches the bitmap, arms PendingShareStore, and opens the in-app SharePicker.
    // Wired in AndroidAiTab (needs the NavigationRouter). No-op default keeps previews working.
    onSendViaZchat: (Bitmap) -> Unit = {},
    modifier: Modifier = Modifier,
    onChatsTab: () -> Unit = {},
    onWalletTab: () -> Unit = {},
    onMoreTab: () -> Unit = {},
) {
    // NOTE: the AI tab is intentionally NOT FLAG_SECURE. AI prompts/responses already leave the device
    // to Venice (not E2E, per the disclaimer below), and users want to screenshot/share generated
    // images — so screenshotting is allowed here. The real E2E chat screens stay screenshot-protected.
    val cc = chatColors()
    var prompt by remember { mutableStateOf("") }
    var modelMenuOpen by remember { mutableStateOf(false) }
    // B16: which provider groups are expanded in the model picker. Reset when the menu closes (below).
    val expandedProviders = remember { mutableStateListOf<String>() }
    // Destructive-action confirmations (image delete is irreversible — bytes leave filesDir).
    var pendingDeleteImageId by remember { mutableStateOf<String?>(null) }
    var confirmClearAllImages by remember { mutableStateOf(false) }
    // Tapping a gallery image opens it full-screen (where it can be saved/shared).
    var fullscreenImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Live elapsed-seconds counter while a request is in flight (perceived progress + Stop).
    var elapsedSec by remember { mutableStateOf(0) }
    LaunchedEffect(state.sending) {
        if (state.sending) {
            elapsedSec = 0
            while (true) { kotlinx.coroutines.delay(1000); elapsedSec++ }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cc.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            // Lift content (incl. the composer + Send button) above the soft keyboard — without this
            // the Send button sat behind the IME and was untappable.
            .imePadding(),
    ) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        // ── Header: balance + top-up CTA ────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Shielded AI",
                    color = cc.primary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (state.balanceStale) {
                    // The last balance fetch failed — don't present a possibly-stale number as fact.
                    if (state.balanceRefreshing) {
                        // Show the tap registered and a fetch is in flight (no-op on repeat taps in the VM).
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = cc.warning, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Refreshing…", color = cc.warning, fontSize = 13.sp)
                        }
                    } else {
                        Text(
                            text = "⚠ Balance unknown — tap to refresh",
                            color = cc.warning,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable(onClick = onRefreshBalance),
                        )
                    }
                } else {
                    Text(
                        text = "Balance: $%.4f USD".format(state.balanceUsd),
                        color = cc.textSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
            // Top-up as a filled, glowing accent button (was a plain text link) — the primary
            // monetization CTA should read as a tappable button, matching the Nightwire button style.
            // "Top-up history" sits right under it: top-up payments are filtered out of the chat
            // list (AI_TOPUP_MEMO_PREFIX), so this is where users check that a deposit credited.
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .shadow(10.dp, RoundedCornerShape(10.dp), ambientColor = cc.accentPrimaryGlow, spotColor = cc.accentPrimaryGlow)
                        .clip(RoundedCornerShape(10.dp))
                        .background(cc.primary)
                        .clickable(onClick = onTopupClick)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text("⚡ Top up", color = cc.textOnAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Top-up history",
                    color = cc.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onTopupHistoryClick)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Privacy notice ──────────────────────────────────────────────
        Text(
            text = "Prompts go to Venice.ai (not E2E). Do not paste seeds or private keys.",
            // textSecondary (#9AA3B8 = 7.78:1 on BgBase) not textTertiary (4.47:1, sub-AA): this is a
            // security disclaimer and must be clearly legible.
            color = cc.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // ── Low-balance warning bar ─────────────────────────────────────
        if (state.lowBalance) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(cc.warning.copy(alpha = 0.15f))
                    .clickable(onClick = onTopupClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Low balance (${AiApiClient.usd(state.balanceUsd)}) — tap to top up",
                    color = cc.warning,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Mode toggle: Chat / Image ───────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip(label = "Chat", selected = state.mode == AiMode.Chat, onClick = { onSelectMode(AiMode.Chat) }, modifier = Modifier.weight(1f), cc = cc)
            ModeChip(label = "Image", selected = state.mode == AiMode.Image, onClick = { onSelectMode(AiMode.Image) }, modifier = Modifier.weight(1f), cc = cc)
        }

        // ── History toolbar (previous chats / new / clear) ──────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.mode == AiMode.Chat) {
                TextButton(onClick = { onShowHistory(true) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("☰ Previous chats", color = cc.primary, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                if (state.chatTurns.isNotEmpty()) {
                    TextButton(onClick = onClearChat, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Clear", color = cc.textSecondary, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = onNewChat, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("＋ New", color = cc.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("${state.images.size} image(s) saved", color = cc.textTertiary, fontSize = 11.sp)
                Spacer(modifier = Modifier.weight(1f))
                if (state.images.isNotEmpty()) {
                    TextButton(onClick = { confirmClearAllImages = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Clear all", color = cc.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }

        // ── Model picker ────────────────────────────────────────────────
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(cc.bgInput)
                    // Whole row opens the picker (not just the small arrow). Both set open=true (never
                    // toggle) so the row tap + icon tap can't cancel each other out.
                    .clickable { modelMenuOpen = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Model",
                        color = cc.textSecondary, // 6.38:1 on bgInput; textTertiary was 3.66:1 (sub-AA)
                        fontSize = 11.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // While the model list is still loading (no selection yet), show a spinner next to
                        // the "Loading…" label so it reads as in-progress, not stuck.
                        if (state.selectedModel == null && state.models.isEmpty()) {
                            CircularProgressIndicator(color = cc.primary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = state.selectedModel ?: "Loading…",
                            color = cc.textPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // Price of the currently-selected model, so the cost per request is visible
                        // without opening the picker.
                        val selPrice = state.models.firstOrNull { it.id == state.selectedModel }?.priceLabelShort().orEmpty()
                        if (selPrice.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = selPrice, color = cc.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                IconButton(onClick = { modelMenuOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Choose model",
                        tint = cc.primary,
                    )
                }
            }
            DropdownMenu(
                expanded = modelMenuOpen,
                onDismissRequest = { modelMenuOpen = false; expandedProviders.clear() },
                modifier = Modifier.background(cc.bgElevated),
            ) {
                // Filter by mode using the server's authoritative flags, and only offer USABLE
                // (priced) models so a selection can never hit the backend's "not configured" 400.
                val filtered = state.models.filter { m ->
                    m.priced && (if (state.mode == AiMode.Image) m.isImage else !m.isImage)
                }
                if (filtered.isEmpty()) {
                    // Make the empty state actionable — tapping it retries the model fetch instead of
                    // leaving a dead, non-interactive "No models available" row.
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("No models available", color = cc.textTertiary, fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Retry", color = cc.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        onClick = {
                            modelMenuOpen = false
                            onRefreshModels()
                        },
                    )
                }
                // B16: GROUP the (~120 otherwise-flat) models by provider. Singletons render inline;
                // multi-model providers get an expandable header (tap toggles, never dismisses the menu).
                // Uncensored-first within each group; the selected model's group auto-expands. No .take()
                // cap (it silently hid ~29 tail models). Selection is still keyed by model id.
                val selProvider = filtered.firstOrNull { it.id == state.selectedModel }?.provider
                LaunchedEffect(modelMenuOpen, selProvider) {
                    if (modelMenuOpen) selProvider?.let { if (it !in expandedProviders) expandedProviders.add(it) }
                }
                val groups = filtered.groupBy { it.provider }.toList().sortedWith(
                    compareByDescending<Pair<String, List<VeniceModel>>> { (name, _) -> name == selProvider }
                        .thenByDescending { (_, ms) -> ms.any { it.uncensored } }
                        .thenBy { (name, _) -> name }
                )
                groups.forEach { (provider, groupModels) ->
                    val sorted = groupModels.sortedByDescending { it.uncensored }
                    if (sorted.size == 1) {
                        ModelPickerRow(sorted.first(), cc, indent = false) {
                            onSelectModel(sorted.first().id); modelMenuOpen = false
                        }
                    } else {
                        val isExpanded = provider in expandedProviders
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                                        tint = cc.textSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("$provider  (${sorted.size})", color = cc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    if (sorted.any { it.uncensored }) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(cc.primary))
                                    }
                                }
                            },
                            onClick = {
                                // Toggle expansion only — do NOT close the menu.
                                if (provider in expandedProviders) expandedProviders.remove(provider) else expandedProviders.add(provider)
                            },
                        )
                        if (isExpanded) {
                            sorted.forEach { m ->
                                ModelPickerRow(m, cc, indent = true) {
                                    onSelectModel(m.id); modelMenuOpen = false
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Reply pane (scrollable) ─────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(cc.surface)
                .padding(12.dp),
        ) {
            val ctx = LocalContext.current
            val isEmpty = if (state.mode == AiMode.Chat) state.chatTurns.isEmpty() else state.images.isEmpty()
            if (isEmpty && !state.sending && state.error == null) {
                // Centered placeholder when there's no history yet for this mode.
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (state.mode == AiMode.Image) "Generate an image" else "Ask anything",
                        color = cc.textTertiary,
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (state.mode == AiMode.Image) {
                            "Pick an UNCENSORED model for NSFW. Images are kept until you delete them."
                        } else {
                            "Multi-turn — your chat is saved until you clear it."
                        },
                        color = cc.textTertiary,
                        fontSize = 11.sp,
                    )
                }
            } else {
                // History is never auto-cleared; it scrolls and persists across launches.
                val replyScroll = rememberScrollState()
                // Auto-scroll the chat to the newest message when a turn is added / reply arrives.
                LaunchedEffect(state.chatTurns.size, state.sending, state.mode) {
                    if (state.mode == AiMode.Chat) replyScroll.animateScrollTo(replyScroll.maxValue)
                }
                Column(modifier = Modifier.fillMaxSize().verticalScroll(replyScroll)) {
                    if (state.error != null) {
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Text("Error", color = cc.error, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(state.error, color = cc.textSecondary, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            if (state.outOfCredit) {
                                TextButton(onClick = onTopupClick) { Text("Top up now", color = cc.primary) }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Offer Retry so a failed models/network load can recover in-place
                                    // instead of forcing an app restart.
                                    TextButton(onClick = onRefreshModels) { Text("Retry", color = cc.primary) }
                                    TextButton(onClick = onDismissError) { Text("Dismiss", color = cc.primary) }
                                }
                            }
                        }
                    }
                    if (state.mode == AiMode.Chat) {
                        state.chatTurns.forEach { turn ->
                            val isUser = turn.role == AiChatTurn.ROLE_USER
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = when {
                                        turn.failed -> "You · not sent"
                                        isUser -> "You"
                                        else -> "Assistant · external AI"
                                    },
                                    color = if (turn.failed) cc.error else cc.textTertiary,
                                    fontSize = 11.sp,
                                )
                                if (turn.failed) {
                                    // Grey + disable while a retry/send is already in flight (the VM guards
                                    // double-send; this gives the matching visual feedback Regenerate has).
                                    TextButton(
                                        onClick = onRetry,
                                        enabled = !state.sending,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            "Retry",
                                            color = if (state.sending) cc.textTertiary else cc.primary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                } else if (!isUser) {
                                    // Reply actions — 44dp touch targets (was 28dp, below the 48dp guideline).
                                    val isLastAssistant = turn === state.chatTurns.lastOrNull { it.role == AiChatTurn.ROLE_ASSISTANT }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { copyToClipboard(ctx, turn.content) }, modifier = Modifier.size(44.dp)) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy reply", tint = cc.primary, modifier = Modifier.size(18.dp))
                                        }
                                        if (isLastAssistant && !state.sending) {
                                            IconButton(onClick = onRegenerate, modifier = Modifier.size(44.dp)) {
                                                Icon(Icons.Default.Refresh, contentDescription = "Regenerate", tint = cc.primary, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        IconButton(onClick = { shareAiText(ctx, turn.content) }, modifier = Modifier.size(44.dp)) {
                                            Icon(Icons.Default.Share, contentDescription = "Share reply", tint = cc.primary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                            if (isUser) {
                                // Failed user turns render greyed so they never look silently "sent".
                                Text(turn.content, color = if (turn.failed) cc.textTertiary else cc.textPrimary, fontSize = 14.sp)
                            } else {
                                // Render assistant replies as Markdown (code blocks, bold, lists) instead of raw text.
                                MarkdownText(
                                    text = turn.content,
                                    textColor = cc.textPrimary,
                                    codeColor = cc.primary,
                                    codeBackground = cc.bgInput,
                                    accent = cc.primary,
                                    onCopyCode = { code -> copyToClipboard(ctx, code) },
                                )
                            }
                        }
                        if (state.sending) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(color = cc.primary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Thinking… ${elapsedSec}s", color = cc.textSecondary, fontSize = 13.sp)
                                Spacer(modifier = Modifier.weight(1f))
                                TextButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                    Text("Stop", color = cc.error, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        } else if (state.lastChargedUsd > 0.0 || state.lastCompletionTokens > 0) {
                            // Spend-loop footer: shows the cost of the latest exchange in this conversation.
                            val tok = if (state.lastPromptTokens + state.lastCompletionTokens > 0) {
                                " · ${state.lastPromptTokens}→${state.lastCompletionTokens} tok"
                            } else ""
                            Text(
                                text = "${state.lastChatModel ?: ""}$tok · charged ${AiApiClient.usd(state.lastChargedUsd)} · balance ${AiApiClient.usd(state.balanceUsd)}",
                                color = cc.textTertiary,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    } else {
                        if (state.sending) {
                            Row(modifier = Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = cc.primary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Generating image… ${elapsedSec}s", color = cc.textSecondary, fontSize = 13.sp)
                            }
                        }
                        // Gallery, newest first. NOTE: decodes each saved bitmap; fine for a normal
                        // gallery, a LazyColumn would be the next step if a user accrues hundreds.
                        state.images.forEach { item ->
                            val bmp = remember(item.id) { loadImageBitmap(item.id) }
                            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = if (item.chargedUsd > 0.0) "${item.model} · ${AiApiClient.usd(item.chargedUsd)}" else item.model,
                                        color = cc.textTertiary, fontSize = 11.sp, modifier = Modifier.weight(1f),
                                    )
                                    if (bmp != null) {
                                        // PRIMARY: Send via ZCHAT (in-app SharePicker).
                                        TextButton(
                                            onClick = { onSendViaZchat(bmp) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send,
                                                contentDescription = null,
                                                tint = cc.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Send via ZCHAT", color = cc.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        // SECONDARY: generic OS share, now a 44dp tap target with a bigger glyph.
                                        IconButton(onClick = { shareAiImage(ctx, bmp) }, modifier = Modifier.size(44.dp)) {
                                            Icon(Icons.Default.Share, contentDescription = "Share image", tint = cc.primary, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                    TextButton(onClick = { pendingDeleteImageId = item.id }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                        Text("Delete", color = cc.textSecondary, fontSize = 11.sp)
                                    }
                                }
                                if (item.prompt.isNotEmpty()) {
                                    Text(item.prompt, color = cc.textPrimary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Generated image — tap to open full screen",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f))
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { fullscreenImage = bmp },
                                        contentScale = ContentScale.Fit,
                                    )
                                    Text("Tap to view full screen · save or share inside", color = cc.textTertiary, fontSize = 10.sp)
                                } else if (item.url != null) {
                                    Text(item.url, color = cc.primary, fontSize = 12.sp)
                                } else {
                                    Text("Image unavailable", color = cc.textTertiary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Previous-chats overlay ──────────────────────────────────────
        if (state.showHistory) {
            Dialog(onDismissRequest = { onShowHistory(false) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(cc.bgElevated)
                        .padding(16.dp),
                ) {
                    var query by remember { mutableStateOf("") }
                    var renamingId by remember { mutableStateOf<String?>(null) }
                    var renameText by remember { mutableStateOf("") }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Previous chats", color = cc.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onNewChat, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("＋ New chat", color = cc.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Auto-delete retention selector (privacy): chats + images older than this are purged.
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-delete:", color = cc.textSecondary, fontSize = 11.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        listOf(0 to "Off", 1 to "1d", 7 to "7d", 30 to "30d").forEach { (d, label) ->
                            val sel = state.retentionDays == d
                            Text(
                                text = label,
                                color = if (sel) cc.textOnAccent else cc.primary,
                                fontSize = 11.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) cc.primary else Color.Transparent)
                                    .clickable { onSetRetention(d) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (state.conversations.isEmpty()) {
                        Text("No saved chats yet.", color = cc.textTertiary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                    } else {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search chats…", color = cc.textTertiary, fontSize = 13.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = cc.bgInput,
                                unfocusedContainerColor = cc.bgInput,
                                focusedTextColor = cc.textPrimary,
                                unfocusedTextColor = cc.textPrimary,
                                cursorColor = cc.primary,
                                focusedBorderColor = cc.borderActive,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val filtered = state.conversations.filter { it.title.contains(query.trim(), ignoreCase = true) }
                        if (filtered.isEmpty() && query.trim().isNotEmpty()) {
                            // The conversations.isEmpty() empty-state above doesn't cover a non-empty list
                            // that filters down to nothing — say so instead of showing a blank list.
                            Text(
                                text = "No chats match “${query.trim()}”.",
                                color = cc.textTertiary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            items(filtered, key = { it.id }) { conv ->
                                if (renamingId == conv.id) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedTextField(
                                            value = renameText,
                                            onValueChange = { renameText = it },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            shape = RoundedCornerShape(8.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = cc.bgInput,
                                                unfocusedContainerColor = cc.bgInput,
                                                focusedTextColor = cc.textPrimary,
                                                unfocusedTextColor = cc.textPrimary,
                                                cursorColor = cc.primary,
                                                focusedBorderColor = cc.borderActive,
                                                unfocusedBorderColor = Color.Transparent,
                                            ),
                                        )
                                        TextButton(
                                            onClick = { onRenameConversation(conv.id, renameText); renamingId = null },
                                            enabled = renameText.trim().isNotBlank(),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                "Save",
                                                color = if (renameText.trim().isNotBlank()) cc.primary else cc.textTertiary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                        TextButton(onClick = { renamingId = null }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                            Text("Cancel", color = cc.textSecondary, fontSize = 12.sp)
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenConversation(conv.id) }
                                            .padding(vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                conv.title,
                                                color = if (conv.id == state.currentConversationId) cc.primary else cc.textPrimary,
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text("${conv.turns.size} message(s)", color = cc.textTertiary, fontSize = 10.sp)
                                        }
                                        TextButton(onClick = { renamingId = conv.id; renameText = conv.title }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                            Text("Rename", color = cc.textSecondary, fontSize = 11.sp)
                                        }
                                        TextButton(onClick = { onDeleteConversation(conv.id) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                            Text("Delete", color = cc.textSecondary, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Full-screen image viewer (tap a gallery image to open) ──────
        fullscreenImage?.let { fbmp ->
            val fsCtx = LocalContext.current
            Dialog(
                onDismissRequest = { fullscreenImage = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.96f))
                        .clickable { fullscreenImage = null }, // tap backdrop to close
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // PRIMARY action: Send via ZCHAT (routes into the in-app SharePicker).
                            androidx.compose.material3.Button(
                                onClick = { onSendViaZchat(fbmp) },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = cc.primary,
                                    contentColor = cc.textOnAccent,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Send via ZCHAT", fontWeight = FontWeight.Bold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // SECONDARY: generic OS share — now a larger 48dp tap target with a
                                // bigger glyph (user reported it was too small).
                                IconButton(
                                    onClick = { shareAiImage(fsCtx, fbmp) },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "Share image",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { fullscreenImage = null },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Image(
                            bitmap = fbmp.asImageBitmap(),
                            contentDescription = "Generated image — tap to save to your gallery",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val ok = saveBitmapToGallery(fsCtx, fbmp)
                                    Toast.makeText(fsCtx, if (ok) "Saved to Pictures/ZCHAT" else "Save failed", Toast.LENGTH_SHORT).show()
                                },
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Tap image to save to your gallery", color = Color.White, fontSize = 13.sp)
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // ── Destructive-action confirmations ────────────────────────────
        pendingDeleteImageId?.let { id ->
            ConfirmDialog(
                title = "Delete this image?",
                body = "This permanently removes the generated image from this device. It can't be undone.",
                confirmLabel = "Delete",
                cc = cc,
                onConfirm = { onDeleteImage(id); pendingDeleteImageId = null },
                onDismiss = { pendingDeleteImageId = null },
            )
        }
        if (confirmClearAllImages) {
            ConfirmDialog(
                title = "Delete all ${state.images.size} images?",
                body = "This permanently removes every generated image from this device. It can't be undone.",
                confirmLabel = "Clear all",
                cc = cc,
                onConfirm = { onClearAllImages(); confirmClearAllImages = false },
                onDismiss = { confirmClearAllImages = false },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Composer ────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        // When no model is selected yet (models still loading on first launch) the field is
                        // disabled — say why instead of leaving an unexplained dead composer.
                        text = when {
                            state.selectedModel == null && state.models.isEmpty() -> "Loading models…"
                            state.selectedModel == null -> "Select a model to start"
                            state.mode == AiMode.Image -> "Describe an image…"
                            else -> "Message AI…"
                        },
                        color = cc.textTertiary,
                    )
                },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = cc.bgInput,
                    unfocusedContainerColor = cc.bgInput,
                    focusedTextColor = cc.textPrimary,
                    unfocusedTextColor = cc.textPrimary,
                    cursorColor = cc.primary,
                    focusedBorderColor = cc.borderActive,
                    unfocusedBorderColor = if (cc.isLight) cc.borderDefault else Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val p = prompt.trim()
                    if (p.isNotEmpty()) {
                        if (state.mode == AiMode.Image) onGenerateImage(p) else onSend(p)
                        prompt = ""
                    }
                }),
                enabled = !state.sending && state.selectedModel != null,
            )
            Spacer(modifier = Modifier.size(8.dp))
            val sendEnabled = !state.sending && prompt.isNotBlank() && state.selectedModel != null
            IconButton(
                onClick = {
                    val p = prompt.trim()
                    if (p.isNotEmpty()) {
                        if (state.mode == AiMode.Image) onGenerateImage(p) else onSend(p)
                        prompt = ""
                    }
                },
                enabled = sendEnabled,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    // Background must track the SAME condition as `enabled` (incl. selectedModel != null)
                    // so the button never LOOKS tappable while actually disabled.
                    .background(if (sendEnabled) cc.primary else cc.bgHover),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (sendEnabled) cc.textOnAccent else cc.textTertiary,
                )
            }
        }
    }  // close inner Column(weight=1f, padding=16)

    co.electriccoin.zcash.ui.screen.chat.view.components.NightwireBottomNav(
        items = listOf(
            co.electriccoin.zcash.ui.screen.chat.view.components.BottomNavItem(
                label = "Chats",
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Message,
                        contentDescription = "Chats",
                        tint = cc.textTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                selected = false,
                onClick = onChatsTab,
            ),
            co.electriccoin.zcash.ui.screen.chat.view.components.BottomNavItem(
                label = "Wallet",
                icon = {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = "Wallet",
                        tint = cc.textTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                selected = false,
                onClick = onWalletTab,
            ),
            co.electriccoin.zcash.ui.screen.chat.view.components.BottomNavItem(
                label = "AI",
                icon = {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "AI",
                        tint = cc.primary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                selected = true,
                onClick = { /* already on AI */ },
            ),
            co.electriccoin.zcash.ui.screen.chat.view.components.BottomNavItem(
                label = "More",
                icon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "More",
                        tint = cc.textTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                selected = false,
                onClick = onMoreTab,
            ),
        )
    )
    }  // close outer Column
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cc: co.electriccoin.zcash.ui.screen.chat.view.ChatColors,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) cc.primary else cc.bgInput)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) cc.textOnAccent else cc.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Small confirm/cancel dialog for destructive actions (image delete, clear-all). Matches the
 * surrounding Dialog-based overlays already used in this screen.
 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    cc: co.electriccoin.zcash.ui.screen.chat.view.ChatColors,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cc.bgElevated)
                .padding(20.dp),
        ) {
            Text(title, color = cc.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(body, color = cc.textSecondary, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = cc.textSecondary, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onConfirm) {
                    Text(confirmLabel, color = cc.error, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Save a generated image bitmap to the device's Pictures/ZCHAT folder via MediaStore.
 * Works on Android 10+ without any storage permission (scoped storage).
 */
private fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap): Boolean {
    val filename = "zchat-ai-${System.currentTimeMillis()}.png"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZCHAT")
    }
    return runCatching {
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: return@runCatching false
        true
    }.getOrDefault(false)
}

/**
 * Share an AI-generated text reply via Android's system Share sheet.
 *
 * The user can pick:
 *   - ZCHAT (routes the text to a chat — uses Android's intent handling)
 *   - Any other messaging app
 *   - Copy to clipboard
 *
 * For routing to a new contact / shielded address, the user opens the destination chat
 * (or creates a new chat) within ZCHAT and pastes the shared text. Direct in-app picking
 * of a chat ships behind the existing ChatListView contact-picker when wired up by Koin.
 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("ZCHAT AI", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun shareAiText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "Shared from ZCHAT AI")
    }
    context.startActivity(Intent.createChooser(intent, "Share AI reply").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Share an AI-generated image. Writes the bitmap to the app's external cache, exposes it
 * via FileProvider, and launches Android's Share sheet with the content URI. Same picker
 * lets the user route into ZCHAT, any other gallery/messaging app, or copy.
 */
private fun shareAiImage(context: Context, bitmap: Bitmap) {
    runCatching {
        val cacheDir = java.io.File(context.cacheDir, "ai-share").apply { mkdirs() }
        // Use a stable-ish filename so re-shares don't accumulate cruft (cache cleanup is OS-driven).
        val file = java.io.File(cacheDir, "zchat-ai-share.png")
        java.io.FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val authority = getShareFileProviderAuthority(context)
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Shared from ZCHAT AI")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "Could not share: ${it.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Same FileProvider authority pattern used elsewhere in the app. Build variants append
 * `.testnet`, `.foss`, `.debug` to the base package — keep this in sync with manifests.
 */
private fun getShareFileProviderAuthority(context: Context): String {
    val pkg = context.packageName
    return "$pkg.provider"
}

/**
 * "Send via ZCHAT" for an AI-generated image. Writes the bitmap to a UNIQUE cache file (so concurrent /
 * repeated sends never clobber each other), arms it in [co.electriccoin.zcash.ui.screen.chat.share
 * .PendingShareStore], and returns the file for the caller to navigate to the SharePicker. Returns null
 * on write failure (caller should toast). Runs disk I/O — call off the main thread.
 */
internal fun cacheAiImageForZchatSend(context: Context, bitmap: Bitmap): java.io.File? = runCatching {
    val dir = java.io.File(context.cacheDir, "share-inbox").apply { mkdirs() }
    val file = java.io.File(dir, "ai_send_${System.currentTimeMillis()}_${System.nanoTime()}.png")
    java.io.FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    file
}.getOrNull()

/**
 * B16: one model row in the grouped model picker. Shows the friendly [VeniceModel.name] (raw id in the
 * subtitle), an "UNCENSORED" chip, context/vision + price. [indent] left-pads rows shown under a provider
 * header so the grouping reads clearly. Selection is keyed by model id via [onClick].
 */
@androidx.compose.runtime.Composable
private fun ModelPickerRow(
    m: VeniceModel,
    colors: ChatColors,
    indent: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = if (indent) Modifier.padding(start = 16.dp) else Modifier
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (m.uncensored) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "UNCENSORED",
                            color = colors.textOnAccent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.primary)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                val ctxOrType = if (m.isImage) {
                    "image model"
                } else {
                    val ctxLabel = if (m.contextTokens >= 1_000_000) {
                        "${m.contextTokens / 1_000_000}M context"
                    } else if (m.contextTokens >= 1000) {
                        "${m.contextTokens / 1000}k context"
                    } else "${m.contextTokens} context"
                    if (m.supportsVision) "$ctxLabel · vision" else ctxLabel
                }
                val price = m.priceLabel()
                // Include the raw id so it stays discoverable now that the title shows the friendly name.
                val sub = buildString {
                    append(ctxOrType)
                    if (price.isNotEmpty()) append(" · $price")
                    if (m.name != m.id) append("  ·  ${m.id}")
                }
                Text(text = sub, color = colors.textTertiary, fontSize = 10.sp)
            }
        },
        onClick = onClick,
    )
}
