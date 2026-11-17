package co.electriccoin.zcash.ui.screen.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.datasource.EncryptedStoreUnavailableException
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
 * Token storage: persisted in EncryptedSharedPreferences via [AiPreferences]. AI-KEY-S: the key comes from
 * [AiAccountAccess], the one place that decides about it; this view model holds no sign-up of its own.
 *
 * ZC-733-A: every use of the AI store goes through ONE guard ([useStore]). When the store cannot be opened the state
 * says so ([AiState.storeUnavailable]) with one fixed message ([AI_STORE_UNAVAILABLE_MESSAGE]); the start's two reads
 * end there and NO sign-in is sent (a failed read is never taken for "no access key": a sign-in would ask the server
 * for a new key while the old one sits in the store that could not be opened); every function that reads or writes
 * the store does nothing; the message stays until Retry ([refreshModels]) opens the store.
 */
class AiTabVM(
    private val prefs: AiPreferences,
    private val imageStore: AiImageStore,
    private val client: AiApiClient = AiApiClient(),
    private val access: AiAccountAccess,
) : ViewModel() {
    private val _state = MutableStateFlow(AiState())
    val state: StateFlow<AiState> = _state.asStateFlow()

    // Top-up deposit history — kept OUT of AiState (its own sheet, loaded on demand when opened).
    private val _topupHistory = MutableStateFlow<TopupHistoryState>(TopupHistoryState.Loading)
    val topupHistory: StateFlow<TopupHistoryState> = _topupHistory.asStateFlow()

    // In-flight chat coroutine, so the user can Stop a long generation.
    private var chatJob: Job? = null

    // The claim of "Look for AI credit from before 2.13" in flight, while repeat taps are ignored.
    private var claimJob: Job? = null

    // ZC-1238: the wallet whose chats, pictures and turns the state holds; every chat and picture goes out under it.
    private var shownFence: AiAccountAccess.Fence? = null

    // ZC-1238: the store's lists of the wallet just come being read; nothing reads or writes them until they are in.
    private var followingWallet: Job? = null

    init {
        // Load persisted history off the main thread: the FIRST AiPreferences access builds
        // EncryptedSharedPreferences/Tink (~241ms of Keystore + disk reads), which on the main thread
        // froze the AI tab on open (StrictMode DiskReadViolation). Reading it on Dispatchers.IO keeps
        // the open instant; history then appears as soon as it loads.
        // Sanitize any conversation whose last turn is an un-answered USER turn (the app died mid-send)
        // by marking it failed — so it never shows as a silently-"sent" message with no reply.
        viewModelScope.launch { loadSaved() }
        bootstrap()
    }

    /** The saved chats, pictures and settings, read off the main thread through the guard; false when it cannot. */
    private suspend fun loadSaved(): Boolean {
        // ZC-1238: noted before the read: a change of wallet during it is seen at the next look. Retry of the store keeps it.
        if (shownFence == null) shownFence = access.scopeNow()
        val loaded =
            withContext(Dispatchers.IO) {
                imageStore.prepare()
                readSaved()
            } ?: return false
        showSaved(loaded)
        purgeExpired() // apply the auto-delete window on launch
        return true
    }

    private fun readSaved() =
        useStore { store ->
            Triple(
                parseConversations(store.getConversationsJson()).map(::sanitizeTrailingPending),
                parseImages(store.getImagesJson()),
                store.getRetentionDays(),
            ) to store.getImageAspect()
        }

    private fun showSaved(
        loaded: Pair<Triple<List<AiConversation>, List<AiImageItem>, Int>, String>,
        leftBehind: Set<String> = emptySet(),
    ) {
        val (history, aspect) = loaded
        _state.update {
            it.copy(
                conversations = history.first.filterNot { c -> c.id in leftBehind },
                images = history.second,
                retentionDays = history.third,
                imageAspect = if (IMAGE_ASPECTS.any { a -> a.first == aspect }) aspect else IMAGE_ASPECTS.first().first,
            )
        }
    }

    /**
     * ZC-1238: the look at the wallet every tap that sends or shows a turn makes first. On another wallet the tab holds
     * nothing of the one it left: [leaveWallet]. The answer is the read of the new wallet's lists, still running (a send
     * goes on after it), or null. A raised counter under the same tag, or a first tag on nobody's chats, is the same chats.
     */
    private fun followWallet(): Job? {
        val shown = shownFence
        if (shown == null || _state.value.sending || access.stillThisWallet(shown)) return followingWallet
        val now = access.fenceHere()
        shownFence = now
        return if (shown.tag == null || shown.tag == now.tag) followingWallet else leaveWallet(keptError(_state.value))
    }

    /** True when a request of the shown wallet ended on another one; the case of a raised counter alone is not this. */
    private fun walletLeft(): Boolean {
        val shownTag = shownFence?.tag ?: return false
        return shownTag != access.fenceHere().tag
    }

    /**
     * ZC-1238: the wallet changed: its transcript, lists and pending state go, and the store's lists of the wallet now
     * come in, with the composer locked meanwhile. A chat of the wallet left is never read back: dispatchChat saves a turn
     * before its request is fenced, so one could be in the new wallet's list. Writes nothing.
     */
    private fun leaveWallet(sentence: String?): Job {
        shownFence = access.fenceHere()
        val leftBehind =
            _state.value.conversations
                .map { it.id }
                .toSet()
        _state.update {
            it.copy(
                sending = true,
                streamingReply = null,
                chatTurns = emptyList(),
                currentConversationId = null,
                conversations = emptyList(),
                images = emptyList(),
                showHistory = false,
                error = sentence,
                outOfCredit = false,
                lastChargedUsd = 0.0,
                lastPromptTokens = 0,
                lastCompletionTokens = 0,
                lastChatModel = null,
            )
        }
        val job =
            viewModelScope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) { synchronized(ZchatPreferences.walletScopeLock) { readSaved() } }
                    if (loaded != null) showSaved(loaded, leftBehind)
                } finally {
                    followingWallet = null
                    _state.update { it.copy(sending = false) }
                }
                purgeExpired()
            }
        followingWallet = job
        return job
    }

    /**
     * ZC-733-A: the ONE guard every use of the AI store goes through. While the store is unavailable it does nothing
     * (null); an [EncryptedStoreUnavailableException] sets [AiState.storeUnavailable] and the fixed message, and
     * nothing escapes the view model's scope.
     */
    private inline fun <T> useStore(block: (AiPreferences) -> T): T? {
        if (_state.value.storeUnavailable) return null
        return try {
            block(prefs)
        } catch (e: EncryptedStoreUnavailableException) {
            _state.update { it.copy(storeUnavailable = true, error = AI_STORE_UNAVAILABLE_MESSAGE, outOfCredit = false) }
            null
        }
    }

    /** ZC-733-A: true while the AI store cannot be opened; a function that reads or writes it then returns at once. */
    private fun storeBlocked(): Boolean = _state.value.storeUnavailable || followingWallet != null

    override fun onCleared() {
        // Release the Ktor engine (threads + connection pool) when this tab's VM is destroyed. The
        // client is created per-VM (default arg), so nothing else references it. viewModelScope is
        // already cancelled by the time onCleared runs, so any in-flight request has been aborted.
        client.close()
        super.onCleared()
    }

    /** Delete chats/images older than the retention window (no-op when retention is Off). */
    private fun purgeExpired() {
        val days = _state.value.retentionDays
        if (days <= 0 || storeBlocked()) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        _state.update { st ->
            val keptC = st.conversations.filter { it.updatedAt >= cutoff }
            val keptI = st.images.filter { it.ts >= cutoff }
            if (keptC.size != st.conversations.size) useStore { it.setConversationsJson(keptC.conversationsToJson()) }
            if (keptI.size != st.images.size) {
                st.images.filter { it.ts < cutoff }.forEach { imageStore.delete(it.id) }
                useStore { it.setImagesJson(keptI.imagesToJson()) }
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
        useStore { it.setRetentionDays(days) } ?: return
        _state.update { it.copy(retentionDays = days) }
        purgeExpired()
    }

    /** Rename a saved conversation (overrides the auto-derived title). */
    fun renameConversation(id: String, title: String) {
        val t = title.trim().ifEmpty { return }.take(60)
        if (storeBlocked()) return
        _state.update { st ->
            val updated = st.conversations.map { if (it.id == id) it.copy(title = t) else it }
            useStore { it.setConversationsJson(updated.conversationsToJson()) } ?: return@update st
            st.copy(conversations = updated)
        }
    }

    private fun bootstrap() {
        viewModelScope.launch {
            // AI-KEY-S: AiAccountAccess reads the store off the main thread; a store that cannot be read ends the start
            // here (ZC-733-A): no sign-in, no balance, no models.
            val (token, sentence) = useStore { access.open() } ?: return@launch
            applyAccess(token, sentence, refreshIfMoved = false)
            if (token == null) return@launch
            launch {
                refreshBalanceWithRetry()
                resolveTrialStatusIfUnknown()
            }
            launch { loadModelsWithRetry() }
        }
    }

    /**
     * AI-KEY-S: what AiAccountAccess answered: a new key for the state, and a sentence, whole, for the card.
     * ZC-1206: on another account the old balance and trial status are gone, and that account's is asked for once, unless
     * the caller is about to ([refreshIfMoved]). True when the account changed.
     */
    private suspend fun applyAccess(
        key: String?,
        sentence: String?,
        refreshIfMoved: Boolean = true,
    ): Boolean {
        val userId = if (key != null) withContext(Dispatchers.IO) { useStore { it.getUserId() } } else null
        val parked = useStore { access.parkedAccounts() }
        val before = _state.value.userId
        val moved = userId != null && before != null && userId != before
        _state.update {
            val shown =
                it.copy(
                    token = key ?: it.token,
                    userId = userId ?: it.userId,
                    parkedAccounts = parked ?: it.parkedAccounts,
                    error = sentence ?: it.error,
                    outOfCredit = if (sentence != null) false else it.outOfCredit,
                )
            if (moved) shown.copy(balanceUsd = 0.0, balanceLoaded = false, balanceStale = false, onFreeTrial = null) else shown
        }
        if (sentence != null) useStore { access.noticeShown(sentence) }
        if (moved && refreshIfMoved) viewModelScope.launch { refreshBalance() }
        return moved
    }

    /**
     * ZC-1208: the key a call that charges nothing goes out on: the store's now, never the tab's copy, which another path
     * may have parked or replaced. Null when there is none: the call is not made.
     */
    private suspend fun currentKey(refreshIfMoved: Boolean = true): String? {
        val key = useStore { access.keyForUse() }?.first ?: return null
        if (key != _state.value.token) applyAccess(key, null, refreshIfMoved)
        return key
    }

    /** Round 3: the ticket of a call that charges nothing: the fence is noted before the key is read. */
    private suspend fun ticketForUse(refreshIfMoved: Boolean = true): AiAccountAccess.Ticket? {
        val asked = useStore { access.scopeNow() } ?: return null
        return currentKey(refreshIfMoved)?.let { asked.forKey(it) }
    }

    /**
     * AI-KEY-S (spec 4.5): a call the server refused (401) goes to AiAccountAccess, and runs once more with the new key
     * it gives (a refused call was never charged). No loop: the second answer is the answer. Round 3: all of it under
     * [asked] ([AiAccountAccess.request]); null means the wallet changed and this request writes nothing.
     */
    private suspend fun <R> ask(
        asked: AiAccountAccess.Ticket,
        isRefused: (R) -> Boolean,
        refreshIfMoved: Boolean = true,
        refuse: suspend (AiAccountAccess.Ticket) -> Pair<String?, String?>? = { used ->
            useStore { access.onRefused(used.key, used.fence) }
        },
        call: suspend (String) -> R,
    ): AiAccountAccess.Answered<R>? =
        useStore {
            access.request(asked, isRefused, refuse, { key, sentence -> applyAccess(key, sentence, refreshIfMoved) }, call)
        }

    /** AI-KEY-S: the tap on "Link again" (spec 4.3, change 9). */
    fun linkAgain() {
        viewModelScope.launch {
            val (key, sentence) = useStore { access.linkAgain() } ?: return@launch
            _state.update { it.copy(error = null) }
            applyAccess(key, sentence)
        }
    }

    /**
     * AI-KEY-P: the tap on "Look for AI credit from before 2.13". The fingerprint is read here, on the tap, and nothing
     * else in the tab sends one. Item 17: one claim at a time; a tap while it runs is ignored. Item 16: the fence is noted
     * before the read, so a fingerprint read across a change of wallet is never sent.
     */
    fun lookForOldCredit(fingerprint: suspend () -> String?) {
        if (claimJob?.isActive == true) return
        claimJob =
            viewModelScope.launch {
                val asked = useStore { access.scopeNow() } ?: return@launch
                val read = fingerprint()
                if (read == null) {
                    afterTap(_state.value.token, AiApiClient.SIGN_IN_TEXTS["ai_signin_wallet_not_ready"])
                    return@launch
                }
                val (key, sentence) = useStore { access.lookForOldCredit(read, asked) } ?: return@launch
                afterTap(key, sentence)
            }
    }

    /** AI-KEY-P: the tap on "Try to link" for the parked key of [accountId]. */
    fun tryToLink(accountId: String) {
        viewModelScope.launch {
            val (key, sentence) = useStore { access.tryToLink(accountId) } ?: return@launch
            afterTap(key, sentence)
        }
    }

    /**
     * What a tap answered. The two sentences that send the user to the operator's check come with the short key id
     * (ZC-1180); a new account shows no balance until its own has been read.
     */
    private suspend fun afterTap(
        key: String?,
        sentence: String?,
    ) {
        val before = _state.value.token
        val code =
            if (sentence == AiApiClient.OTHER_ACCOUNT_TEXTS["ai_old_credit_needs_check"] ||
                sentence == AiApiClient.SIGN_IN_TEXTS["ai_notice_key_parked"]
            ) {
                useStore { access.shortKeyCode() }
            } else {
                null
            }
        val changed = key != null && key != before
        _state.update {
            it.copy(
                error = null,
                signInKeyCode = code,
                balanceLoaded = if (changed) false else it.balanceLoaded,
            )
        }
        val moved = applyAccess(key, sentence)
        if (key != null && changed) {
            if (!moved) viewModelScope.launch { refreshBalance() }
            viewModelScope.launch { loadModels() }
        }
    }

    /**
     * Initial/critical balance fetch with a bounded backoff retry, so one transient failure at
     * cold-open (radio/DNS/TLS warmup racing the app's startup burst) doesn't latch
     * "Balance unknown" until the user taps refresh.
     */
    private suspend fun refreshBalanceWithRetry() {
        var ok = refreshBalance()
        for (backoffMs in RETRY_BACKOFF_MS) {
            if (ok) return
            delay(backoffMs)
            ok = refreshBalance()
        }
    }

    /**
     * Trial-status fallback when /ai/balance doesn't carry the per-user onFreeTrial flag yet:
     * a trial account is one with no credited deposit, which the top-up history exposes directly.
     * Failure leaves the status unknown (null) — the picker then shows no locks, and the backend's
     * send-time gate still protects the money path.
     */
    private suspend fun resolveTrialStatusIfUnknown() {
        if (_state.value.onFreeTrial != null) return
        val asked = ticketForUse() ?: return
        val answered = ask(asked, { it is TopupHistoryResult.Failure && it.refused }) { client.topupHistory(it) }
        when (val r = answered?.takeIf { it.keyStillSaved }?.result) {
            null -> Unit
            is TopupHistoryResult.Success -> _state.update { st ->
                if (st.onFreeTrial != null) st
                else st.copy(onFreeTrial = r.deposits.none { it.status == "credited" })
            }
            is TopupHistoryResult.Failure -> Unit
        }
    }

    /**
     * @return true on success (also updates state); false leaves [AiState.balanceStale] set. GPT 3: an answer belongs to
     * its request: for another key, or across a change of wallet, its figure is not shown and it says nothing of the link.
     */
    private suspend fun refreshBalance(): Boolean {
        val asked = ticketForUse(refreshIfMoved = false) ?: return false
        val answered =
            ask(asked, { it is BalanceResult.Failure && it.refused }, refreshIfMoved = false) { client.balance(it) }
                ?.takeIf { it.keyStillSaved }
                ?: return false
        val used = answered.ticket
        return when (val r = answered.result) {
            is BalanceResult.Success -> {
                _state.update {
                    // A successful top-up shows up here as a positive balance — self-dismiss the stale
                    // "Out of credit — Top up now" error so the user isn't told they're broke after paying.
                    val cleared = r.balanceUsd > 0.0 && it.outOfCredit
                    it.copy(
                        balanceUsd = r.balanceUsd,
                        balanceLoaded = true,
                        balanceStale = false,
                        onFreeTrial = r.onFreeTrial ?: it.onFreeTrial,
                        outOfCredit = if (cleared) false else it.outOfCredit,
                        error = if (cleared) null else it.error,
                    )
                }
                // AI-KEY-S (spec 4.3): the re-check of a link goes through this call. An old server says nothing about it.
                if (r.signInKeySent) {
                    useStore { access.onBalance(used, r.signInKeyId, r.paid) }?.let { (key, sentence) -> applyAccess(key, sentence) }
                }
                true
            }
            // Don't silently keep showing a stale/zero balance the user might spend against — flag it.
            is BalanceResult.Failure -> {
                _state.update { it.copy(balanceStale = true) }
                false
            }
        }
    }

    /** Manual balance refresh (tapped from the "balance unknown" chip). */
    fun refreshBalanceNow() {
        followWallet()
        val s = _state.value
        if (s.token == null) return
        // Ignore repeat taps while a refresh is already in flight (the chip shows "Refreshing…").
        if (s.balanceRefreshing) return
        _state.update { it.copy(balanceRefreshing = true) }
        viewModelScope.launch {
            try {
                refreshBalance()
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

    /** Initial models fetch with bounded backoff — only the FINAL failure latches the error banner. */
    private suspend fun loadModelsWithRetry() {
        var ok = loadModels(latchError = false)
        for ((i, backoffMs) in RETRY_BACKOFF_MS.withIndex()) {
            if (ok) return
            delay(backoffMs)
            ok = loadModels(latchError = i == RETRY_BACKOFF_MS.lastIndex)
        }
    }

    /** @return true on success. On failure the error banner is set only when [latchError]. */
    private suspend fun loadModels(latchError: Boolean = true): Boolean {
        val asked = ticketForUse() ?: return false
        val answered =
            ask(asked, { it is ModelsResult.Failure && it.refused }) { client.listModels(it) }
                ?.takeIf { it.keyStillSaved }
                ?: return false
        return when (val r = answered.result) {
            is ModelsResult.Success -> {
                _state.update {
                    val text = r.models.filter { m -> !m.isImage && m.priced }
                    val image = r.models.filter { m -> m.isImage && m.priced }
                    val savedChat = useStore { s -> s.getSelectedChatModel() }
                    val savedImage = useStore { s -> s.getSelectedImageModel() }
                    val chatDefault =
                        text.firstOrNull { it.id == savedChat }?.id
                            ?: text.firstOrNull { it.id == DEFAULT_MODEL_ID }?.id
                            ?: text.firstOrNull()?.id
                    val imageDefault =
                        image.firstOrNull { it.id == savedImage }?.id
                            ?: image.firstOrNull()?.id
                    it.copy(models = r.models, selectedChatModel = chatDefault, selectedImageModel = imageDefault)
                }
                true
            }
            is ModelsResult.Failure -> {
                if (latchError) _state.update { it.copy(error = "Could not load models: ${r.error}") }
                false
            }
        }
    }

    /**
     * Re-fetch the model list after a load failure (the error banner / empty dropdown "Retry").
     * If we never got a token, re-run the full bootstrap (sign-in + balance + models) instead so a
     * failed first launch can recover without restarting the app.
     */
    fun refreshModels() {
        // ZC-733-A: the card's Retry while the store cannot be opened asks the store once more, off the main thread.
        if (_state.value.storeUnavailable) {
            retryStore()
            return
        }
        if (_state.value.token == null) {
            bootstrap()
            return
        }
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            // ZC-1208: another path may have parked the tab's key and left none: then this is the start again.
            if (useStore { access.keyForUse() }?.first == null) bootstrap() else loadModels()
        }
    }

    /**
     * ZC-733-A: one more try of the AI store. When it opens, the field and the message go, the saved chats and pictures
     * are loaded and the start goes on with the saved key; when it does not, the message stays.
     */
    private fun retryStore() {
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { prefs.tryOpenAgain() }
            if (!opened) return@launch
            _state.update { it.copy(storeUnavailable = false, error = null) }
            if (loadSaved()) bootstrap()
        }
    }

    /** Selects a model for the CURRENT mode and persists it independently of the other mode. */
    fun selectModel(modelId: String) {
        // Switching models invalidates any prior send error (e.g. the trial-restriction 402): the
        // new model has different pricing/gating, so don't leave a stale banner with no Dismiss.
        if (storeBlocked()) return
        if (_state.value.mode == AiMode.Image) {
            _state.update { it.copy(selectedImageModel = modelId, error = null, outOfCredit = false) }
            useStore { it.setSelectedImageModel(modelId) }
        } else {
            _state.update { it.copy(selectedChatModel = modelId, error = null, outOfCredit = false) }
            useStore { it.setSelectedChatModel(modelId) }
        }
    }

    /** Sets the aspect ratio used for image generation (one of [IMAGE_ASPECTS] labels) and persists it. */
    fun setImageAspect(aspect: String) {
        if (IMAGE_ASPECTS.none { it.first == aspect }) return
        useStore { it.setImageAspect(aspect) } ?: return
        _state.update { it.copy(imageAspect = aspect) }
    }

    // ── Chat ─────────────────────────────────────────────────────────────────────────────────────

    fun send(prompt: String) {
        followWallet()?.let { reading ->
            viewModelScope.launch {
                reading.join()
                send(prompt)
            }
            return
        }
        if (storeBlocked()) return
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
        if (followWallet() != null) return
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
        _state.update {
            it.copy(sending = true, error = null, streamingReply = null, currentConversationId = convId, chatTurns = turns)
        }
        persistConversation(convId, model, turns)
        // Bound cost + context: only the most recent MAX_CONTEXT_TURNS are sent to the model, even
        // though the full transcript is shown and persisted. Stops a long chat from inflating every
        // future charge (and from blowing the model's context window).
        val sent = if (turns.size > MAX_CONTEXT_TURNS) turns.takeLast(MAX_CONTEXT_TURNS) else turns
        chatJob = viewModelScope.launch {
            // AI-KEY-S (spec 4.2, M9): no chat leaves on a key without an answer from key/state.
            // Round 3: the fence is noted before the key is read, and the request stays under it to its last write.
            // ZC-1238: the fence is the one these turns were read under, never the wallet's of this moment.
            val fence = shownFence ?: useStore { access.scopeNow() }
            val (charged, sentence) = useStore { access.keyForCharge() } ?: (null to AI_STORE_UNAVAILABLE_MESSAGE)
            if (walletLeft()) {
                leaveWallet(WALLET_CHANGED_MESSAGE)
                return@launch
            }
            if (charged == null || fence == null) {
                failSend(convId, model, turns, sentence ?: AiApiClient.SERVICE_UNAVAILABLE_MESSAGE, outOfCredit = false)
                return@launch
            }
            applyAccess(charged.takeUnless { it == token }, sentence)
            // Cancelled by the user (or VM teardown): stopGeneration() already committed the partial reply / marked the turn.
            val answered =
                ask(fence.forKey(charged), { it is ChatResult.Failure && it.refused }) { key ->
                    // onDelta runs on the IO dispatcher per chunk; StateFlow updates are thread-safe and
                    // Compose collects at frame rate, so per-delta updates stay cheap.
                    client.chat(key, model, sent, onDelta = { delta ->
                        if (access.stillThisWallet(fence)) {
                            _state.update { st -> st.copy(streamingReply = (st.streamingReply ?: "") + delta) }
                        }
                    })
                }
            // Belt-and-suspenders: if Stop raced the request completing, the stopped state wins.
            if (!isActive) return@launch
            if (answered == null) {
                // Round 3: the wallet changed: the composer comes back, and nothing is saved into the new wallet's store.
                // ZC-1238: the turn goes with its wallet. The counter alone moved: still this wallet's, and a tap retries it.
                if (walletLeft()) {
                    leaveWallet(WALLET_CHANGED_MESSAGE)
                } else {
                    val failed = turns.dropLast(1) + turns.last().copy(failed = true)
                    _state.update { it.copy(sending = false, streamingReply = null, chatTurns = failed, error = WALLET_CHANGED_MESSAGE) }
                }
                return@launch
            }
            when (val r = answered.result) {
                is ChatResult.Success -> {
                    // Round 3: a reply paid for on a key another path has replaced is still shown; its figures are not this key's.
                    val balanceAfter = if (answered.keyStillSaved) r.balanceAfterUsd else NO_FIGURE
                    val chargedHere = if (answered.keyStillSaved) r.chargedUsd else 0.0
                    // ZC-1409: a reply whose stream ended before [DONE] is kept, marked cut off (never a complete reply).
                    val assistantTurn = AiChatTurn(AiChatTurn.ROLE_ASSISTANT, r.reply, System.currentTimeMillis(), cutOff = r.cutOff)
                    val finalTurns = turns + assistantTurn
                    _state.update {
                        // Same fallback as the image path: if the backend returned no valid balance
                        // (-1 = unknown/sanitized), recompute from the CURRENT state minus the charge
                        // instead of trusting a stale/garbage value — and never clobber a balance that
                        // changed concurrently. (Previously this assigned r.balanceAfterUsd directly,
                        // which on a missing field defaulted to 0.0 and falsely read as out-of-credit.)
                        val newBalance = if (balanceAfter >= 0.0) balanceAfter else (it.balanceUsd - chargedHere).coerceAtLeast(0.0)
                        it.copy(
                            // ZC-957: the server's own figure is a loaded balance; a figure worked out here is not,
                            // and it never ends the warning of a failed balance call (only the server's figure does).
                            balanceLoaded = it.balanceLoaded || balanceAfter >= 0.0,
                            sending = false,
                            streamingReply = null,
                            chatTurns = finalTurns,
                            lastChargedUsd = r.chargedUsd,
                            lastPromptTokens = r.promptTokens,
                            lastCompletionTokens = r.completionTokens,
                            lastChatModel = model,
                            balanceUsd = newBalance,
                            balanceStale = if (balanceAfter >= 0.0) false else it.balanceStale,
                        )
                    }
                    persistConversation(convId, model, finalTurns)
                    // Streamed replies may arrive without zchat_meta (proxy-dependent) — fetch the
                    // authoritative balance so the header/charge footer don't drift.
                    if (balanceAfter < 0.0) launch { refreshBalance() }
                }
                // On failure the user turn must NOT look silently "sent" — mark it failed (greyed + Retry).
                is ChatResult.OutOfCredit -> failSend(convId, model, turns, r.error, outOfCredit = true)
                is ChatResult.Failure -> failSend(convId, model, turns, r.error, outOfCredit = false)
            }
        }
    }

    /** Stop an in-flight generation: cancel the request; keep any partially-streamed reply. */
    fun stopGeneration() {
        val s = _state.value
        if (!s.sending) return
        // Only a chat generation is cancellable here. If the in-flight work is an image generation
        // (chatJob is null or already completed), do nothing: clearing `sending` would re-enable the
        // composer under a still-running image request (a second charge) and could mis-mark an
        // unrelated chat turn as "Stopped".
        val job = chatJob
        if (job == null || !job.isActive) return
        job.cancel()
        chatJob = null
        // ZC-1238: nothing of the wallet left is saved into this one, and no sentence asks for a Retry.
        if (walletLeft()) {
            leaveWallet(WALLET_CHANGED_MESSAGE)
            return
        }
        val convId = s.currentConversationId
        val model = s.selectedChatModel
        val partial = s.streamingReply
        if (!partial.isNullOrBlank() && convId != null && model != null) {
            // Tokens already streamed are kept as a (truncated) assistant reply — matches every
            // major AI app; the user stopped because they had enough, not to discard it.
            val finalTurns = s.chatTurns + AiChatTurn(AiChatTurn.ROLE_ASSISTANT, partial, System.currentTimeMillis())
            _state.update { it.copy(sending = false, streamingReply = null, chatTurns = finalTurns) }
            persistConversation(convId, model, finalTurns)
        } else if (convId != null && model != null && s.chatTurns.lastOrNull()?.role == AiChatTurn.ROLE_USER) {
            failSend(convId, model, s.chatTurns, "Stopped — tap Retry to resend.", outOfCredit = false)
        } else {
            _state.update { it.copy(sending = false, streamingReply = null) }
        }
    }

    private fun failSend(convId: String, model: String, turnsWithUser: List<AiChatTurn>, error: String, outOfCredit: Boolean) {
        val failedTurns = turnsWithUser.dropLast(1) + turnsWithUser.last().copy(failed = true)
        _state.update {
            it.copy(sending = false, streamingReply = null, chatTurns = failedTurns, outOfCredit = outOfCredit, error = error)
        }
        persistConversation(convId, model, failedTurns)
    }

    /** Retry the last failed user turn: drop it and re-send its content (re-runs against the transcript). */
    fun retryFailed() {
        if (followWallet() != null) return
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
            useStore { it.setConversationsJson(merged.conversationsToJson()) }
            st.copy(conversations = merged)
        }
    }

    /** Start a fresh conversation (the prior one is already saved in [AiState.conversations]). */
    fun newChat() {
        _state.update {
            it.copy(
                currentConversationId = null,
                chatTurns = emptyList(),
                error = keptError(it),
                outOfCredit = false,
                showHistory = false,
                lastChargedUsd = 0.0,
                lastPromptTokens = 0,
                lastCompletionTokens = 0,
                lastChatModel = null,
            )
        }
    }

    /** Re-open a saved conversation from the previous-chats list. */
    fun openConversation(id: String) {
        followWallet()
        val conv = _state.value.conversations.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(
                currentConversationId = conv.id,
                chatTurns = conv.turns,
                mode = AiMode.Chat,
                showHistory = false,
                error = keptError(it),
                outOfCredit = false,
                lastChargedUsd = 0.0,
                lastPromptTokens = 0,
                lastCompletionTokens = 0,
                lastChatModel = null,
            )
        }
    }

    fun deleteConversation(id: String) {
        if (storeBlocked()) return
        _state.update { st ->
            val remaining = st.conversations.filterNot { it.id == id }
            useStore { it.setConversationsJson(remaining.conversationsToJson()) } ?: return@update st
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
        if (storeBlocked()) return
        val id = _state.value.currentConversationId
        if (id != null) deleteConversation(id)
        _state.update { it.copy(currentConversationId = null, chatTurns = emptyList(), error = null, outOfCredit = false) }
    }

    fun setShowHistory(show: Boolean) {
        _state.update { it.copy(showHistory = show) }
    }

    // ── Image ──────────────────────────────────────────────────────────────────────────────────

    fun generateImage(prompt: String) {
        followWallet()?.let { reading ->
            viewModelScope.launch {
                reading.join()
                generateImage(prompt)
            }
            return
        }
        if (storeBlocked()) return
        val s = _state.value
        val token = s.token ?: return
        val model = s.selectedImageModel
        if (model == null) {
            _state.update { it.copy(error = "Pick an image model first.") }
            return
        }
        if (prompt.isBlank() || s.sending) return
        _state.update { it.copy(sending = true, error = null) }
        // Map the selected aspect-ratio chip to the generation size (defaults to square).
        val size = IMAGE_ASPECTS.firstOrNull { it.first == s.imageAspect }?.second ?: IMAGE_ASPECTS.first().second
        viewModelScope.launch {
            // AI-KEY-S (spec 4.2, M9): no picture is asked for on a key without an answer from key/state.
            val fence = shownFence ?: useStore { access.scopeNow() }
            val (charged, sentence) = useStore { access.keyForCharge() } ?: (null to AI_STORE_UNAVAILABLE_MESSAGE)
            if (walletLeft()) {
                leaveWallet(WALLET_CHANGED_MESSAGE)
                return@launch
            }
            if (charged == null || fence == null) {
                val why = sentence ?: AiApiClient.SERVICE_UNAVAILABLE_MESSAGE
                _state.update { it.copy(sending = false, error = why) }
                return@launch
            }
            applyAccess(charged.takeUnless { it == token }, sentence)
            val answered =
                ask(fence.forKey(charged), { it is ImageResult.Failure && it.refused }) {
                    client.image(it, model, prompt, size = size)
                }
            if (answered == null) {
                if (walletLeft()) {
                    leaveWallet(WALLET_CHANGED_MESSAGE)
                } else {
                    _state.update { it.copy(sending = false, error = WALLET_CHANGED_MESSAGE) }
                }
                return@launch
            }
            when (val r = answered.result) {
                is ImageResult.Success -> {
                    // Round 3: as for a chat answer.
                    val balanceAfter = if (answered.keyStillSaved) r.balanceAfterUsd else NO_FIGURE
                    val chargedHere = if (answered.keyStillSaved) r.chargedUsd else 0.0
                    val id = AiImageItem.newId()
                    // Persist the bytes to app-private storage so the gallery survives restarts. The
                    // coroutine resumes on Dispatchers.Main after client.image, so the Base64 decode +
                    // file write (multi-MB) MUST run off the main thread to avoid an ANR / StrictMode hit.
                    if (r.b64Json != null) withContext(Dispatchers.IO) { imageStore.save(id, r.b64Json) }
                    val item = AiImageItem(id, prompt.trim(), model, r.imageUrl, System.currentTimeMillis(), r.chargedUsd)
                    _state.update {
                        // Compute the fallback balance from the CURRENT state (it.balanceUsd), not a
                        // snapshot taken before the async call — otherwise a balance that changed while
                        // the image was generating (e.g. a concurrent generation) gets clobbered.
                        val newBalance = if (balanceAfter >= 0.0) balanceAfter else (it.balanceUsd - chargedHere).coerceAtLeast(0.0)
                        val gallery = listOf(item) + it.images // newest first
                        useStore { store -> store.setImagesJson(gallery.imagesToJson()) }
                        it.copy(
                            sending = false,
                            images = gallery,
                            lastChargedUsd = r.chargedUsd,
                            balanceUsd = newBalance,
                            // ZC-957: as for a chat answer.
                            balanceStale = if (balanceAfter >= 0.0) false else it.balanceStale,
                            balanceLoaded = it.balanceLoaded || balanceAfter >= 0.0,
                        )
                    }
                    // ZC-957: as for a chat answer, an answer without the server's figure asks the server for it.
                    if (balanceAfter < 0.0) launch { refreshBalance() }
                }
                is ImageResult.OutOfCredit -> _state.update { it.copy(sending = false, outOfCredit = true, error = r.error) }
                is ImageResult.Failure -> _state.update { it.copy(sending = false, error = r.error) }
            }
        }
    }

    /** Round 3: what a request ended by a change of wallet says. */
    private fun walletChanged(): String = AiApiClient.SIGN_IN_TEXTS["ai_signin_wallet_not_ready"].orEmpty()

    /** Load the persisted bytes for a gallery image (decoded off the saved file). */
    fun loadImageBitmap(id: String) = imageStore.loadBitmap(id)

    fun deleteImage(id: String) {
        if (storeBlocked()) return
        imageStore.delete(id)
        _state.update {
            val remaining = it.images.filterNot { im -> im.id == id }
            useStore { store -> store.setImagesJson(remaining.imagesToJson()) }
            it.copy(images = remaining)
        }
    }

    fun clearAllImages() {
        if (storeBlocked()) return
        imageStore.clearAll()
        useStore { it.setImagesJson(emptyList<AiImageItem>().imagesToJson()) }
        _state.update { it.copy(images = emptyList()) }
    }

    // ── Misc ───────────────────────────────────────────────────────────────────────────────────

    fun clearError() {
        // ZC-733-A: the store's message stays until Retry opens the store.
        _state.update { it.copy(error = keptError(it), outOfCredit = false) }
    }

    /** ZC-733-A: the error line a "clear" leaves: the store's fixed message while the store cannot be opened. */
    private fun keptError(state: AiState): String? = if (state.storeUnavailable) state.error else null

    fun loadTopupAddress(onResult: (TopupAddressResult) -> Unit) {
        if (_state.value.token == null) return onResult(TopupAddressResult.Failure("Not registered"))
        viewModelScope.launch {
            // AI-KEY-P (spec 4.5): a top-up only for a linked account; a handed-out address is written down before it shows.
            // Items 15, 21: the fence is noted first, and the answer counts only for the request's own account.
            val asked = useStore { access.scopeNow() } ?: return@launch
            val (token, sentence) = useStore { access.keyForTopup() } ?: return@launch
            if (token == null) {
                onResult(TopupAddressResult.Failure(sentence.orEmpty()))
                return@launch
            }
            val answered =
                ask(
                    asked.forKey(token),
                    { it is TopupAddressResult.Failure && it.refused },
                    refuse = { used -> useStore { access.onTopupRefused(used) } },
                ) { key -> client.topupAddress(key) }
            val result = answered?.result
            val told = if (result is TopupAddressResult.Success) useStore { access.onTopupAddress(answered.ticket) }?.first else token
            onResult(if (result != null && told != null) result else TopupAddressResult.Failure(walletChanged()))
        }
    }

    /**
     * (Re)load the top-up deposit history. Called when the history sheet opens and from its
     * Retry button. Always refetches — deposit statuses change server-side as the watcher
     * confirms/credits them, so a cached list would show stale "pending" rows.
     */
    fun loadTopupHistory() {
        if (_state.value.token == null) {
            _topupHistory.value = TopupHistoryState.Error("Not registered with the AI backend yet.")
            return
        }
        _topupHistory.value = TopupHistoryState.Loading
        viewModelScope.launch {
            val asked = ticketForUse()
            if (asked == null) {
                _topupHistory.value = TopupHistoryState.Error("Not registered with the AI backend yet.")
                return@launch
            }
            val answered = ask(asked, { it is TopupHistoryResult.Failure && it.refused }) { client.topupHistory(it) }
            _topupHistory.value =
                when (val r = answered?.takeIf { it.keyStillSaved }?.result) {
                    null -> TopupHistoryState.Error(walletChanged())
                    is TopupHistoryResult.Success -> TopupHistoryState.Data(r.deposits)
                    is TopupHistoryResult.Failure -> TopupHistoryState.Error(r.error)
                }
        }
    }

    /** Switch Chat↔Image without discarding either history. */
    fun setMode(mode: AiMode) {
        _state.update { it.copy(mode = mode, error = keptError(it), outOfCredit = false) }
    }

    // In-flight post-top-up balance poll (bounded); re-armed on every payment intent.
    private var topupPollJob: Job? = null

    /**
     * Arm a bounded balance poll after a payment intent (tapping "Pay with this wallet" or closing
     * the top-up sheet), so a credited deposit shows up within seconds instead of requiring the
     * user to leave and re-enter the AI tab. Read-only GETs every [TOPUP_POLL_INTERVAL_MS] for at
     * most [TOPUP_POLL_WINDOW_MS]; stops early once the balance increases.
     */
    fun startTopupPoll() {
        if (_state.value.token == null) return
        topupPollJob?.cancel()
        topupPollJob = viewModelScope.launch {
            val baseline = _state.value.balanceUsd
            val deadline = System.currentTimeMillis() + TOPUP_POLL_WINDOW_MS
            while (System.currentTimeMillis() < deadline) {
                delay(TOPUP_POLL_INTERVAL_MS)
                refreshBalance()
                val st = _state.value
                if (st.balanceLoaded && st.balanceUsd > baseline) {
                    // Deposit credited. A credited deposit also ends the free trial's model locks.
                    _state.update { it.copy(onFreeTrial = false) }
                    break
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MODEL_ID = "venice-uncensored-1-2"

        /**
         * ZC-733-A: the fixed error line while the AI store cannot be opened; [localizeAiMessage] turns it into
         * ai_store_unavailable (the English text is this sentence).
         */
        const val AI_STORE_UNAVAILABLE_MESSAGE =
            "ZCHAT could not open the encrypted storage of your AI chats and settings. Nothing was deleted. " +
                "Close ZCHAT and open it again. If it keeps happening, restart your phone."

        /**
         * ZC-1238: a chat or a picture ended by a change of wallet. It may have been billed, so it invites no Retry;
         * [localizeAiMessage] turns it into ai_wallet_changed (the English text is this sentence).
         */
        const val WALLET_CHANGED_MESSAGE =
            "The wallet changed while this AI request was running. Its answer is not shown here, and the request may have " +
                "been charged to the other wallet's AI account."

        /** Max recent turns replayed as context per request (bounds cost + context-window use). */
        const val MAX_CONTEXT_TURNS = 20

        /** A balance answer's "no figure from the server" ([ChatResult.Success.balanceAfterUsd]). */
        private const val NO_FIGURE = -1.0

        /** Backoff schedule for the initial balance/models fetches (attempt, then wait, retry…). */
        private val RETRY_BACKOFF_MS = longArrayOf(1_000L, 3_000L, 8_000L)

        // Post-top-up balance poll: every 20s for up to 15 minutes.
        private const val TOPUP_POLL_INTERVAL_MS = 20_000L
        private const val TOPUP_POLL_WINDOW_MS = 15L * 60_000L

        /**
         * Aspect-ratio chips for image generation: label → Venice/OpenAI-compat size string.
         * The first entry is the default.
         */
        val IMAGE_ASPECTS: List<Pair<String, String>> = listOf(
            "1:1" to "1024x1024",
            "16:9" to "1280x720",
            "9:16" to "720x1280",
            "3:2" to "1152x768",
        )
    }
}

data class AiState(
    val userId: String? = null,
    val token: String? = null,
    // AI-KEY-P: the account ids of the parked keys, one "Try to link" each; never shown.
    val parkedAccounts: List<String> = emptyList(),
    // ZC-1180: the short key id to send for the operator's check, after an answer that asks for it.
    val signInKeyCode: String? = null,
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
    // True once a REAL balance has been fetched (register or /ai/balance). Until then balanceUsd
    // is just the 0.0 default and must not render as "$0.0000" or trigger the low-balance bar.
    val balanceLoaded: Boolean = false,
    // True while a manual balance refresh is in flight (show a spinner; ignore repeat taps).
    val balanceRefreshing: Boolean = false,
    // Partially-streamed assistant reply for the in-flight chat request (null = not streaming yet).
    val streamingReply: String? = null,
    // True = trial account (backend's cheap-model whitelist applies); null = unknown → no locks shown.
    val onFreeTrial: Boolean? = null,
    // Selected image aspect-ratio label (one of AiTabVM.IMAGE_ASPECTS).
    val imageAspect: String = "1:1",
    val outOfCredit: Boolean = false,
    val error: String? = null,
    // ZC-733-A: the AI store could not be opened: the tab shows the fixed message with Retry and no Dismiss.
    val storeUnavailable: Boolean = false,
) {
    /** Below this balance we surface a low-credit warning bar. */
    val lowBalance: Boolean get() = balanceLoaded && balanceUsd in 0.0..0.05 && !balanceStale

    /**
     * ZC-957: the line under the latest answer: the model, the tokens, what was charged, and the balance only when
     * it came from the server ([balanceLoaded]) and the last balance call did not fail. Until then [balanceUsd] is
     * a default or a figure worked out here, and the header says what is known (money-path rule 4).
     */
    val chargeFooter: String
        get() {
            val counted = lastPromptTokens + lastCompletionTokens > 0
            val tokens = if (counted) " · $lastPromptTokens→$lastCompletionTokens tok" else ""
            val balance = if (balanceLoaded && !balanceStale) " · balance ${AiApiClient.usd(balanceUsd)}" else ""
            return "${lastChatModel ?: ""}$tokens · charged ${AiApiClient.usd(lastChargedUsd)}$balance"
        }
    /** The model selected for the current mode (what the picker + composer act on). */
    val selectedModel: String? get() = if (mode == AiMode.Image) selectedImageModel else selectedChatModel
}

enum class AiMode { Chat, Image }

/** UI state for the top-up history sheet: loading / error / data (empty list = "No top-ups yet"). */
sealed class TopupHistoryState {
    data object Loading : TopupHistoryState()
    data class Error(val message: String) : TopupHistoryState()
    data class Data(val deposits: List<AiTopupEntry>) : TopupHistoryState()
}
