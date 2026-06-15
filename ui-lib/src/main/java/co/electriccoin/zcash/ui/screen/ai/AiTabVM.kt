package co.electriccoin.zcash.ui.screen.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI tab: balance, model picker, prompt composer, and a PERSISTENT history of chat conversations
 * and generated images.
 *
 * History model (see [AiHistory]): chat is multi-turn and grouped into [AiConversation]s ("previous
 * chats"); images accumulate in a gallery. Both persist across launches via [AiPreferences] /
 * [AiImageStore] and are removed only by explicit user action (clear / delete) — never auto-discarded.
 *
 * Token storage: persisted in EncryptedSharedPreferences via [AiPreferences]. On first use the VM
 * calls /ai/auth/register and saves the bearer + userId.
 */
class AiTabVM(
    private val prefs: AiPreferences,
    private val imageStore: AiImageStore,
    private val client: AiApiClient = AiApiClient(),
    private val walletPubkeyResolver: (suspend () -> String?)? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(AiState())
    val state: StateFlow<AiState> = _state.asStateFlow()

    // In-flight chat coroutine, so the user can Stop a long generation.
    private var chatJob: Job? = null

    init {
        // Load persisted history off the main thread: the FIRST AiPreferences access builds
        // EncryptedSharedPreferences/Tink (~241ms of Keystore + disk reads), which on the main thread
        // froze the AI tab on open (StrictMode DiskReadViolation). Reading it on Dispatchers.IO keeps
        // the open instant; history then appears as soon as it loads.
        // Sanitize any conversation whose last turn is an un-answered USER turn (the app died mid-send)
        // by marking it failed — so it never shows as a silently-"sent" message with no reply.
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                Triple(
                    parseConversations(prefs.getConversationsJson()).map(::sanitizeTrailingPending),
                    parseImages(prefs.getImagesJson()),
                    prefs.getRetentionDays(),
                )
            }
            _state.update {
                it.copy(conversations = loaded.first, images = loaded.second, retentionDays = loaded.third)
            }
            purgeExpired() // apply the auto-delete window on launch
        }
        bootstrap()
    }

    /** Delete chats/images older than the retention window (no-op when retention is Off). */
    private fun purgeExpired() {
        val days = _state.value.retentionDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        _state.update { st ->
            val keptC = st.conversations.filter { it.updatedAt >= cutoff }
            val keptI = st.images.filter { it.ts >= cutoff }
            if (keptC.size != st.conversations.size) prefs.setConversationsJson(keptC.conversationsToJson())
            if (keptI.size != st.images.size) {
                st.images.filter { it.ts < cutoff }.forEach { imageStore.delete(it.id) }
                prefs.setImagesJson(keptI.imagesToJson())
            }
            val curGone = st.currentConversationId != null && keptC.none { it.id == st.currentConversationId }
            st.copy(
                conversations = keptC,
                images = keptI,
                currentConversationId = if (curGone) null else st.currentConversationId,
                chatTurns = if (curGone) emptyList() else st.chatTurns,
            )
        }
    }

    /** Set the auto-delete window (days; 0 = keep forever) and purge immediately. */
    fun setRetention(days: Int) {
        prefs.setRetentionDays(days)
        _state.update { it.copy(retentionDays = days) }
        purgeExpired()
    }

    /** Rename a saved conversation (overrides the auto-derived title). */
    fun renameConversation(id: String, title: String) {
        val t = title.trim().ifEmpty { return }.take(60)
        _state.update { st ->
            val updated = st.conversations.map { if (it.id == id) it.copy(title = t) else it }
            prefs.setConversationsJson(updated.conversationsToJson())
            st.copy(conversations = updated)
        }
    }

    private fun bootstrap() {
        viewModelScope.launch {
            // Read off the main thread — this may be the first AiPreferences access and would otherwise
            // build EncryptedSharedPreferences/Tink synchronously on main (StrictMode DiskReadViolation).
            val (saved, savedUser) = withContext(Dispatchers.IO) { prefs.getToken() to prefs.getUserId() }
            val token = if (saved != null && savedUser != null) {
                _state.update { it.copy(token = saved, userId = savedUser) }
                saved
            } else {
                val walletPubkey = walletPubkeyResolver?.invoke()
                when (val r = client.register(walletPubkey)) {
                    is RegisterResult.Success -> {
                        prefs.saveCredentials(r.token, r.userId)
                        _state.update {
                            it.copy(
                                token = r.token,
                                userId = r.userId,
                                balanceUsd = r.balanceMicroUsd / 1_000_000.0,
                            )
                        }
                        r.token
                    }
                    is RegisterResult.Failure -> {
                        _state.update { it.copy(error = "Could not initialize AI: ${r.error}") }
                        return@launch
                    }
                }
            }
            launch { refreshBalance(token) }
            launch { loadModels(token) }
        }
    }

    private suspend fun refreshBalance(token: String) {
        when (val r = client.balance(token)) {
            is BalanceResult.Success -> _state.update {
                // A successful top-up shows up here as a positive balance — self-dismiss the stale
                // "Out of credit — Top up now" error so the user isn't told they're broke after paying.
                val cleared = r.balanceUsd > 0.0 && it.outOfCredit
                it.copy(
                    balanceUsd = r.balanceUsd,
                    balanceStale = false,
                    outOfCredit = if (cleared) false else it.outOfCredit,
                    error = if (cleared) null else it.error,
                )
            }
            // Don't silently keep showing a stale/zero balance the user might spend against — flag it.
            is BalanceResult.Failure -> _state.update { it.copy(balanceStale = true) }
        }
    }

    /** Manual balance refresh (tapped from the "balance unknown" chip). */
    fun refreshBalanceNow() {
        val s = _state.value
        val token = s.token ?: return
        // Ignore repeat taps while a refresh is already in flight (the chip shows "Refreshing…").
        if (s.balanceRefreshing) return
        _state.update { it.copy(balanceRefreshing = true) }
        viewModelScope.launch {
            try {
                refreshBalance(token)
            } finally {
                _state.update { it.copy(balanceRefreshing = false) }
            }
        }
    }

    /** Mark a conversation's trailing un-answered user turn as failed (interrupted send). */
    private fun sanitizeTrailingPending(c: AiConversation): AiConversation {
        val last = c.turns.lastOrNull() ?: return c
        if (last.role == AiChatTurn.ROLE_USER && !last.failed) {
            return c.copy(turns = c.turns.dropLast(1) + last.copy(failed = true))
        }
        return c
    }

    private suspend fun loadModels(token: String) {
        when (val r = client.listModels(token)) {
            is ModelsResult.Success -> _state.update {
                val text = r.models.filter { m -> !m.isImage && m.priced }
                val image = r.models.filter { m -> m.isImage && m.priced }
                val chatDefault = text.firstOrNull { it.id == prefs.getSelectedChatModel() }?.id
                    ?: text.firstOrNull { it.id == DEFAULT_MODEL_ID }?.id
                    ?: text.firstOrNull()?.id
                val imageDefault = image.firstOrNull { it.id == prefs.getSelectedImageModel() }?.id
                    ?: image.firstOrNull()?.id
                it.copy(models = r.models, selectedChatModel = chatDefault, selectedImageModel = imageDefault)
            }
            is ModelsResult.Failure -> _state.update { it.copy(error = "Could not load models: ${r.error}") }
        }
    }

    /**
     * Re-fetch the model list after a load failure (the error banner / empty dropdown "Retry").
     * If we never got a token, re-run the full bootstrap (register + balance + models) instead so a
     * failed first launch can recover without restarting the app.
     */
    fun refreshModels() {
        val token = _state.value.token
        if (token == null) {
            bootstrap()
            return
        }
        _state.update { it.copy(error = null) }
        viewModelScope.launch { loadModels(token) }
    }

    /** Selects a model for the CURRENT mode and persists it independently of the other mode. */
    fun selectModel(modelId: String) {
        if (_state.value.mode == AiMode.Image) {
            _state.update { it.copy(selectedImageModel = modelId) }
            prefs.setSelectedImageModel(modelId)
        } else {
            _state.update { it.copy(selectedChatModel = modelId) }
            prefs.setSelectedChatModel(modelId)
        }
    }

    // ── Chat ─────────────────────────────────────────────────────────────────────────────────────

    fun send(prompt: String) {
        val s = _state.value
        val token = s.token ?: return
        val model = s.selectedChatModel ?: return
        if (prompt.isBlank() || s.sending) return

        // Append the user's turn immediately (optimistic) and send the FULL transcript as context.
        val now = System.currentTimeMillis()
        val userTurn = AiChatTurn(AiChatTurn.ROLE_USER, prompt.trim(), now)
        val convId = s.currentConversationId ?: AiConversation.newId()
        dispatchChat(convId, model, s.chatTurns + userTurn)
    }

    /**
     * Regenerate the last assistant reply: drop trailing assistant turn(s) and re-answer the last
     * user message (so the user can get a different response without retyping or paying for a no-op).
     */
    fun regenerate() {
        val s = _state.value
        if (s.sending) return
        val model = s.selectedChatModel ?: return
        val convId = s.currentConversationId ?: return
        val lastUserIdx = s.chatTurns.indexOfLast { it.role == AiChatTurn.ROLE_USER && !it.failed }
        if (lastUserIdx < 0) return
        dispatchChat(convId, model, s.chatTurns.subList(0, lastUserIdx + 1).toList())
    }

    /** Shared chat dispatch: [turns] must end with the user message to answer. Used by send + regenerate. */
    private fun dispatchChat(convId: String, model: String, turns: List<AiChatTurn>) {
        val token = _state.value.token ?: return
        _state.update { it.copy(sending = true, error = null, currentConversationId = convId, chatTurns = turns) }
        persistConversation(convId, model, turns)
        // Bound cost + context: only the most recent MAX_CONTEXT_TURNS are sent to the model, even
        // though the full transcript is shown and persisted. Stops a long chat from inflating every
        // future charge (and from blowing the model's context window).
        val sent = if (turns.size > MAX_CONTEXT_TURNS) turns.takeLast(MAX_CONTEXT_TURNS) else turns
        chatJob = viewModelScope.launch {
            when (val r = client.chat(token, model, sent)) {
                is ChatResult.Success -> {
                    val assistantTurn = AiChatTurn(AiChatTurn.ROLE_ASSISTANT, r.reply, System.currentTimeMillis())
                    val finalTurns = turns + assistantTurn
                    _state.update {
                        // Same fallback as the image path: if the backend returned no valid balance
                        // (-1 = unknown/sanitized), recompute from the CURRENT state minus the charge
                        // instead of trusting a stale/garbage value — and never clobber a balance that
                        // changed concurrently. (Previously this assigned r.balanceAfterUsd directly,
                        // which on a missing field defaulted to 0.0 and falsely read as out-of-credit.)
                        val newBalance =
                            if (r.balanceAfterUsd >= 0.0) r.balanceAfterUsd
                            else (it.balanceUsd - r.chargedUsd).coerceAtLeast(0.0)
                        it.copy(
                            sending = false,
                            chatTurns = finalTurns,
                            lastChargedUsd = r.chargedUsd,
                            lastPromptTokens = r.promptTokens,
                            lastCompletionTokens = r.completionTokens,
                            lastChatModel = model,
                            balanceUsd = newBalance,
                            balanceStale = false,
                        )
                    }
                    persistConversation(convId, model, finalTurns)
                }
                // On failure the user turn must NOT look silently "sent" — mark it failed (greyed + Retry).
                is ChatResult.OutOfCredit -> failSend(convId, model, turns, r.error, outOfCredit = true)
                is ChatResult.Failure -> failSend(convId, model, turns, r.error, outOfCredit = false)
            }
        }
    }

    /** Stop an in-flight generation: cancel the request and mark the pending turn so it can be retried. */
    fun stopGeneration() {
        val s = _state.value
        if (!s.sending) return
        chatJob?.cancel()
        chatJob = null
        val convId = s.currentConversationId
        val model = s.selectedChatModel
        if (convId != null && model != null && s.chatTurns.lastOrNull()?.role == AiChatTurn.ROLE_USER) {
            failSend(convId, model, s.chatTurns, "Stopped — tap Retry to resend.", outOfCredit = false)
        } else {
            _state.update { it.copy(sending = false) }
        }
    }

    private fun failSend(convId: String, model: String, turnsWithUser: List<AiChatTurn>, error: String, outOfCredit: Boolean) {
        val failedTurns = turnsWithUser.dropLast(1) + turnsWithUser.last().copy(failed = true)
        _state.update { it.copy(sending = false, chatTurns = failedTurns, outOfCredit = outOfCredit, error = error) }
        persistConversation(convId, model, failedTurns)
    }

    /** Retry the last failed user turn: drop it and re-send its content (re-runs against the transcript). */
    fun retryFailed() {
        val s = _state.value
        val lastFailed = s.chatTurns.lastOrNull { it.role == AiChatTurn.ROLE_USER && it.failed } ?: return
        if (s.sending) return
        _state.update { it.copy(chatTurns = it.chatTurns.filterNot { t -> t === lastFailed || (t.failed && t.ts == lastFailed.ts) }) }
        send(lastFailed.content)
    }

    /** Insert/replace this conversation in the saved list (newest first) and persist. */
    private fun persistConversation(convId: String, model: String, turns: List<AiChatTurn>) {
        if (turns.isEmpty()) return
        val title = AiConversation.titleFrom(turns.first { it.role == AiChatTurn.ROLE_USER }.content)
        val updated = AiConversation(convId, title, model, turns, System.currentTimeMillis())
        _state.update { st ->
            val others = st.conversations.filterNot { it.id == convId }
            val merged = (listOf(updated) + others).sortedByDescending { it.updatedAt }
            prefs.setConversationsJson(merged.conversationsToJson())
            st.copy(conversations = merged)
        }
    }

    /** Start a fresh conversation (the prior one is already saved in [AiState.conversations]). */
    fun newChat() {
        _state.update {
            it.copy(
                currentConversationId = null, chatTurns = emptyList(), error = null, outOfCredit = false, showHistory = false,
                lastChargedUsd = 0.0, lastPromptTokens = 0, lastCompletionTokens = 0, lastChatModel = null,
            )
        }
    }

    /** Re-open a saved conversation from the previous-chats list. */
    fun openConversation(id: String) {
        val conv = _state.value.conversations.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(
                currentConversationId = conv.id, chatTurns = conv.turns, mode = AiMode.Chat, showHistory = false, error = null, outOfCredit = false,
                lastChargedUsd = 0.0, lastPromptTokens = 0, lastCompletionTokens = 0, lastChatModel = null,
            )
        }
    }

    fun deleteConversation(id: String) {
        _state.update { st ->
            val remaining = st.conversations.filterNot { it.id == id }
            prefs.setConversationsJson(remaining.conversationsToJson())
            val clearingCurrent = st.currentConversationId == id
            st.copy(
                conversations = remaining,
                currentConversationId = if (clearingCurrent) null else st.currentConversationId,
                chatTurns = if (clearingCurrent) emptyList() else st.chatTurns,
            )
        }
    }

    /** Clear the CURRENT chat transcript (removes it from the saved list too). */
    fun clearCurrentChat() {
        val id = _state.value.currentConversationId
        if (id != null) deleteConversation(id)
        _state.update { it.copy(currentConversationId = null, chatTurns = emptyList(), error = null, outOfCredit = false) }
    }

    fun setShowHistory(show: Boolean) {
        _state.update { it.copy(showHistory = show) }
    }

    // ── Image ──────────────────────────────────────────────────────────────────────────────────

    fun generateImage(prompt: String) {
        val s = _state.value
        val token = s.token ?: return
        val model = s.selectedImageModel
        if (model == null) {
            _state.update { it.copy(error = "Pick an image model first.") }
            return
        }
        if (prompt.isBlank() || s.sending) return
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            when (val r = client.image(token, model, prompt)) {
                is ImageResult.Success -> {
                    val id = AiImageItem.newId()
                    // Persist the bytes to app-private storage so the gallery survives restarts.
                    if (r.b64Json != null) imageStore.save(id, r.b64Json)
                    val item = AiImageItem(id, prompt.trim(), model, r.imageUrl, System.currentTimeMillis(), r.chargedUsd)
                    _state.update {
                        // Compute the fallback balance from the CURRENT state (it.balanceUsd), not a
                        // snapshot taken before the async call — otherwise a balance that changed while
                        // the image was generating (e.g. a concurrent generation) gets clobbered.
                        val newBalance =
                            if (r.balanceAfterUsd >= 0.0) r.balanceAfterUsd
                            else (it.balanceUsd - r.chargedUsd).coerceAtLeast(0.0)
                        val gallery = listOf(item) + it.images // newest first
                        prefs.setImagesJson(gallery.imagesToJson())
                        it.copy(sending = false, images = gallery, lastChargedUsd = r.chargedUsd, balanceUsd = newBalance, balanceStale = false)
                    }
                }
                is ImageResult.OutOfCredit -> _state.update { it.copy(sending = false, outOfCredit = true, error = r.error) }
                is ImageResult.Failure -> _state.update { it.copy(sending = false, error = r.error) }
            }
        }
    }

    /** Load the persisted bytes for a gallery image (decoded off the saved file). */
    fun loadImageBitmap(id: String) = imageStore.loadBitmap(id)

    fun deleteImage(id: String) {
        imageStore.delete(id)
        _state.update {
            val remaining = it.images.filterNot { im -> im.id == id }
            prefs.setImagesJson(remaining.imagesToJson())
            it.copy(images = remaining)
        }
    }

    fun clearAllImages() {
        imageStore.clearAll()
        prefs.setImagesJson(emptyList<AiImageItem>().imagesToJson())
        _state.update { it.copy(images = emptyList()) }
    }

    // ── Misc ───────────────────────────────────────────────────────────────────────────────────

    fun clearError() {
        _state.update { it.copy(error = null, outOfCredit = false) }
    }

    fun loadTopupAddress(onResult: (TopupAddressResult) -> Unit) {
        val token = _state.value.token ?: return onResult(TopupAddressResult.Failure("Not registered"))
        viewModelScope.launch {
            onResult(client.topupAddress(token))
        }
    }

    /** Switch Chat↔Image without discarding either history. */
    fun setMode(mode: AiMode) {
        _state.update { it.copy(mode = mode, error = null, outOfCredit = false) }
    }

    companion object {
        const val DEFAULT_MODEL_ID = "venice-uncensored-1-2"
        /** Max recent turns replayed as context per request (bounds cost + context-window use). */
        const val MAX_CONTEXT_TURNS = 20
    }
}

data class AiState(
    val userId: String? = null,
    val token: String? = null,
    val balanceUsd: Double = 0.0,
    val models: List<VeniceModel> = emptyList(),
    val selectedChatModel: String? = null,
    val selectedImageModel: String? = null,
    val mode: AiMode = AiMode.Chat,
    val sending: Boolean = false,
    // Persistent history (kept until the user clears).
    val chatTurns: List<AiChatTurn> = emptyList(),
    val currentConversationId: String? = null,
    val conversations: List<AiConversation> = emptyList(),
    val images: List<AiImageItem> = emptyList(),
    val showHistory: Boolean = false,
    val retentionDays: Int = 0,
    val lastChargedUsd: Double = 0.0,
    // Cost of the most recent chat exchange (for the inline "spend loop" footer).
    val lastPromptTokens: Int = 0,
    val lastCompletionTokens: Int = 0,
    val lastChatModel: String? = null,
    // True when a balance fetch failed → the displayed balance may be stale (show a warning chip).
    val balanceStale: Boolean = false,
    // True while a manual balance refresh is in flight (show a spinner; ignore repeat taps).
    val balanceRefreshing: Boolean = false,
    val outOfCredit: Boolean = false,
    val error: String? = null,
) {
    /** Below this balance we surface a low-credit warning bar. */
    val lowBalance: Boolean get() = balanceUsd in 0.0..0.05 && !balanceStale
    /** The model selected for the current mode (what the picker + composer act on). */
    val selectedModel: String? get() = if (mode == AiMode.Image) selectedImageModel else selectedChatModel
}

enum class AiMode { Chat, Image }
