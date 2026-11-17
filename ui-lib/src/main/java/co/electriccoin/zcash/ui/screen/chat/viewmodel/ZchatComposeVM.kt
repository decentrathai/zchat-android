package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.design.component.ZashiNumberTextFieldParser
import kotlinx.coroutines.Job
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.KeystoneAvailability
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeRequiredException
import co.electriccoin.zcash.ui.common.model.SendMaxResult
import co.electriccoin.zcash.ui.common.model.SendMaxUnavailable
import co.electriccoin.zcash.ui.common.model.ShortfallReason
import co.electriccoin.zcash.ui.common.model.Zip317
import co.electriccoin.zcash.ui.common.model.orchardSealedRefusal
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.screen.chat.datasource.NostrBindOutcome
import co.electriccoin.zcash.ui.screen.chat.datasource.NostrBindResult
import co.electriccoin.zcash.ui.screen.chat.datasource.NostrPeerBinding
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.bindPeerNostrKey
import co.electriccoin.zcash.ui.screen.chat.model.ContactCodeNotice
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmation
import co.electriccoin.zcash.ui.screen.chat.model.QuotedChatSetup
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.ChatDetail
import co.electriccoin.zcash.ui.screen.chat.ZchatReceive
import co.electriccoin.zcash.ui.screen.chat.model.sendAllHandshakeReserve
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatSendAllQuote
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatSendQuote
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.usecase.KeystoneChatSendRefusedException
import co.electriccoin.zcash.ui.screen.chat.usecase.OwnAddressChatSendRefusedException
import co.electriccoin.zcash.ui.screen.chat.usecase.SendAllAmountChangedException
import co.electriccoin.zcash.ui.screen.chat.usecase.SendQuoteChangedException
import co.electriccoin.zcash.ui.screen.chat.usecase.memoFailureProvesNothingSent
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class ZchatComposeVM(
    private val contactBook: ContactBook,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getDefaultUnifiedAddress: GetDefaultUnifiedAddressUseCase,
    private val createChunkedMessageProposal: CreateChunkedMessageProposalUseCase,
    private val navigationRouter: NavigationRouter,
    private val prefillZchat: PrefillZchatUseCase,
    private val transactionRepository: TransactionRepository,
    private val zchatPreferences: ZchatPreferences,
    private val validateAddress: ValidateAddressUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<ZchatComposeState>(ZchatComposeState.Loading)
    val state: StateFlow<ZchatComposeState> = _state.asStateFlow()

    // Disclaimer dialog state
    private val _showCostDisclaimer = MutableStateFlow(false)
    val showCostDisclaimer: StateFlow<Boolean> = _showCostDisclaimer.asStateFlow()

    private var recipientAddress = ""
    private var message = ""
    private var selectedContact: Contact? = null
    private var userAddress: String? = null
    private var showAddContactDialog = false

    // ZC-733-B: the last Add contact of the dialog was refused (the saved names cannot be read).
    private var contactNotSaved = false
    private var contactName = ""

    // Amount settings
    private var selectedAmount: MessageAmount = MessageAmount.MINIMAL
    private var customAmountZatoshi: Long = 1000L
    private var customAmountText: String = ""
    private var showAmountDialog = false

    // C-1: what can reach another person — Ironwood available. Sealed Orchard (ZIP 258) and confirming
    // ZEC are in the account's spendableShieldedBalance, but they cannot fund a peer send, so Send All budgets
    // from this one, and (Round 9 R9-1, REVIEW_7C F11) it is the "Available" figure the screen shows.
    private var peerSpendableZatoshi: Long = 0L

    // Every wallet figure a Send All price depends on; any change re-prices it.
    private var balanceFingerprint: List<Long> = emptyList()

    // C-1/C-2/C-8/C-9: the priced Send All. [sendAllKey] is what it was priced for, [sendAllResult] the
    // SDK's answer (null while pricing), [sendAllNotice] the "Amount updated" line after a send-time
    // mismatch. The figure on screen is the figure doSendMessage proposes, or nothing is sent.
    private var sendAllKey: SendAllKey? = null
    private var sendAllResult: SendMaxResult? = null
    private var sendAllJob: Job? = null
    private var sendAllNotice: String? = null

    // ZC-781: the reason a Send All could not even be priced for [sendAllKey] (the update refusal). Shown as the note.
    private var sendAllRefusal: String? = null

    // D2 (REVIEW_7C F11): a send whose submit started may have gone out. From then on the screen shows only the
    // MayHaveBeenSent notice: nothing redraws the form, and Send does nothing.
    private var sendMayHaveGoneOut = false

    // D2 (review re-check): a send is in flight from the tap until it fails or the chat opens. updateState used to
    // rebuild the form with isSending = false, so a wallet update during the send re-enabled Send and passed the
    // double-tap guard below: one more tap started a second paid send.
    private var sendInFlight = false

    // ZC-601: the exact price question New Chat asks before it pays (shown by AndroidZchatCompose as the
    // PaidActionConfirmDialog). [pricedSend] is what the open question priced: Pay sends exactly that or nothing, and
    // Cancel drops it. Nothing is built, stored or submitted while a question is open.
    private val _paidActionConfirmation = MutableStateFlow<PaidActionConfirmation?>(null)
    val paidActionConfirmation: StateFlow<PaidActionConfirmation?> = _paidActionConfirmation.asStateFlow()

    @Volatile private var pricedSend: PricedSend? = null

    // ZC-601: from the tap until the question, a refusal or Pay the form works out the price; it never says "Sending"
    // before Pay.
    @Volatile private var askingPrice = false

    // Whether the current recipient has no conversation id yet (the send then packs a v4 INIT), read
    // once per recipient instead of on every keystroke; dropped whenever a send may have created one.
    private var newConversationFor: Pair<String, Boolean>? = null

    // Round 8 P4 (F6): whether the current recipient already has a chat, read once per recipient for the form. The
    // send reads it again from the store.
    private var existingChatFor: Pair<String, Boolean>? = null

    /**
     * ZC-601 — the one New Chat transaction a question priced: [text] to [recipient] in [mode] at [amountPerOutput],
     * with the SDK's price of it ([quote]); [sendAllQuote] when the amount is a priced Send All.
     */
    private data class PricedSend(
        val recipient: String,
        val text: String,
        val mode: ConversationMode,
        val amountPerOutput: Long,
        val quote: ChatSendQuote,
        val sendAllQuote: ChatSendAllQuote?,
        // ZC-1272: the setup legs this question covers (0 in Shielded with money ready, and for a Send All), at most
        // [setupLeg] each; [quote] stays the message's own transaction.
        val setupLegs: Int = 0,
        val setupLeg: Zatoshi? = null,
        val coversKex: Boolean = false,
        val coversZboot: Boolean = false,
        // ZC-1306: the funds are confirming, so Pay saves the row at [acceptedTotal] and a block sends it from the chat.
        val waitsForFunds: Boolean = false,
    ) {
        val acceptedTotal: Zatoshi
            get() = Zatoshi(quote.total.value + setupLegs * (setupLeg?.value ?: 0L))
    }

    /** What a Send All price is valid for. The message text is not part of it, only its chunk count. */
    private data class SendAllKey(
        val recipient: String,
        val chunkCount: Int,
        val keeps: Zatoshi,
        val balance: List<Long>,
    )

    // Conversation transport mode the user picks before the first message is sent.
    // Defaults to the most-private option (VAULT). Persisted per-peer in doSendMessage
    // BEFORE the conversation is created, so getOrCreateConversationId sees the chosen mode.
    private var selectedMode: ConversationMode = NEW_CHAT_DEFAULT_MODE

    // Track addresses we've ever sent outgoing messages to
    // This is used to determine if we need INIT format (include full address) or hash format
    private val sentToAddresses = MutableStateFlow<Set<String>>(emptySet())

    // #244: the synchronous isValidZcashAddress() is prefix/length only — a corrupted-but-length-valid
    // address passed it, so Send enabled and only the SDK rejected it at proposal time. We additionally
    // run the authoritative SDK validateAddress() (real Bech32m checksum) async and DISABLE Send when
    // it returns Invalid. Send entry FAILS CLOSED: onSendClick blocks a flagged address outright, and
    // doSendMessage awaits any in-flight check before dispatching — so a tap racing the async check
    // can never send to a checksum-invalid address. Only a validation ERROR/timeout stays fail-open
    // (flag left false), so a transient validation hiccup can never block a legitimate send.
    private var addressChecksumInvalid = false
    private var addressValidationJob: Job? = null

    // M1: why the last pasted or scanned contact code bound nothing (shown under the recipient field); cleared by the
    // next recipient change.
    private var codeNotice: ContactCodeNotice? = null

    companion object {
        // Minimal platform fee (same as MINIMAL tier: 1000 zatoshi = 0.00001 ZEC)
        private const val PLATFORM_FEE_MIN_ZATOSHI = 1000L

        // MED-017: the network fee is NOT a constant. It was a flat 30,000 buffer here (and a flat
        // 10,000 "estimate" for display), on the premise that "real shielded tx fees are ~10,000-20,000".
        // That premise only holds for small action counts: a chunked message is a MULTI-output tx whose
        // ZIP-317 fee is 5,000 * (chunkCount + 2) — it overruns a flat 30,000 at chunkCount >= 5, while
        // continuation dust was already being scaled by chunkCount. The fee estimate for a fixed amount
        // comes from the Zip317 MODEL (see feeEstimateZatoshi); Send All no longer estimates at all and
        // quotes the fee of the SDK proposal it priced (C-2).

        // Fallback default for a NEW chat when the peer's NOSTR key is NOT yet known (the picker stays
        // visible so Vault/Open remain one tap away). Tunnel is the right starting point in that case:
        // E2E, instant, supports calls, and after the one-time ZBOOT handshake messages are free over
        // NOSTR — vs Vault which costs an on-chain tx per message. When the peer's NOSTR key IS known,
        // syncModeForRecipient prefers OPEN (free from message #1, no handshake). NOTE: this is the
        // COMPOSE picker default only; ConversationMode.DEFAULT (VAULT) is deliberately left unchanged
        // because it also governs how inbound messages are interpreted.
        private val NEW_CHAT_DEFAULT_MODE = co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL

        /** Zatoshi per ZEC, as a decimal scale. */
        private const val ZEC_DECIMALS = 8

        /** M1: how much of another chat's address the "already belongs to" notice shows, when it has no name. */
        private const val SHORT_HEAD = 10
        private const val SHORT_TAIL = 6

        /**
         * Parse a user-typed custom ZEC amount into zatoshi. Pure so it is unit-testable off the VM.
         * [locale] determines the decimal separator (',' in de/fr, '.' in en) — the parse MUST honor it
         * or a comma-decimal device silently collapses "2,5" to 0.
         */
        internal fun parseCustomAmountZatoshi(amountStr: String, locale: java.util.Locale): Long {
            // Route through the SAME parser the main Send screen uses (AmountState.kt): normalizeInput
            // resolves the locale's decimal/grouping separators, toBigDecimalOrNull validates + parses
            // with full BigDecimal precision. An invalid/blank amount yields 0.
            val normalized = ZashiNumberTextFieldParser.normalizeInput(amountStr, locale)
            val zec = ZashiNumberTextFieldParser.toBigDecimalOrNull(normalized, locale) ?: return 0L
            return runCatching { zec.convertZecToZatoshi().value }.getOrDefault(0L).coerceAtLeast(0L)
        }
    }

    init {
        loadInitialState()
        observeScannedAddress()
        loadSentToAddresses()
        observeBalance()
    }

    /**
     * Load addresses we've sent to from transaction history.
     * This determines if they already have our address from a previous INIT message.
     */
    private fun loadSentToAddresses() {
        viewModelScope.launch {
            transactionRepository.transactions.collectLatest { transactions ->
                val sentTo = mutableSetOf<String>()
                transactions?.forEach { tx ->
                    if (tx is SendTransaction) {
                        tx.recipient?.address?.let { sentTo.add(it) }
                    }
                }
                sentToAddresses.value = sentTo
                // Refresh UI state when sent addresses change
                updateState()
            }
        }
    }

    private fun observeBalance() {
        viewModelScope.launch {
            getSelectedWalletAccount.observe().collectLatest { account ->
                peerSpendableZatoshi = account?.peerSpendableBalance?.value ?: 0L
                balanceFingerprint =
                    account?.let {
                        val ironwood = it.unified.ironwood
                        val sapling = it.sapling?.balance?.available ?: Zatoshi(0)
                        listOf(
                            ironwood.available.value,
                            ironwood.changePending.value,
                            ironwood.valuePending.value,
                            it.unified.balance.available.value,
                            sapling.value,
                        )
                    } ?: emptyList()
                updateState()
            }
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            try {
                // Use default unified address for consistency after wallet restore
                // IMPORTANT: Do NOT use fallback to account.unified.address - it may be a different diversified address
                userAddress = getDefaultUnifiedAddress()
                // Check for prefilled address from QR scan
                prefillZchat.consume()?.let { scannedAddress ->
                    recipientAddress = scannedAddress
                    selectedContact = contactBook.getContact(scannedAddress)
                    syncModeForRecipient()
                }
                updateState()
            } catch (e: Exception) {
                // Never surface a raw framework exception on the compose screen — map to ZCHAT copy.
                _state.value = errorState(e.toZchatUserMessage("Couldn't open New Chat. Please try again."))
            }
        }
    }

    /**
     * Build a recoverable Error state. The terminal Error screen otherwise stranded the user with
     * no way back and no retry — here we expose the existing back navigation plus a Retry that
     * re-runs [loadInitialState].
     */
    private fun errorState(message: String) =
        ZchatComposeState.Error(
            message = message,
            onBack = { navigationRouter.back() },
            onRetry = {
                _state.value = ZchatComposeState.Loading
                loadInitialState()
            }
        )

    private fun observeScannedAddress() {
        viewModelScope.launch {
            prefillZchat.scannedAddress.collectLatest { address ->
                if (address != null) {
                    codeNotice = null
                    recipientAddress = address
                    selectedContact = contactBook.getContact(address)
                    syncModeForRecipient()
                    prefillZchat.clear()
                    updateState()
                }
            }
        }
        // M1: a scanned code whose messaging key already belongs to another chat bound nothing and set no recipient.
        viewModelScope.launch {
            prefillZchat.keyBoundElsewhere.collectLatest { other ->
                if (other != null) {
                    codeNotice = keyAlreadyBoundNotice(other)
                    prefillZchat.clearKeyBoundElsewhere()
                    updateState()
                }
            }
        }
    }

    private fun updateState() {
        // D2: a balance change, a finished price or a scanned address must not bring back a form that sends again.
        if (sendMayHaveGoneOut) return
        val contacts = contactBook.getAllContacts()
        // Treat the user's OWN address as not-sendable so the send button is disabled — a self-send
        // would spawn a sender==recipient conversation and corrupt threading. Non-wedging: this just
        // gates the button (no terminal Error state). userAddress may be null before it loads.
        // [13]: reject ANY of the user's OWN addresses, not just the single default userAddress — isSelfAddress compares
        // by address hash so it also catches diversified receiving addresses (mirrors ChatViewModel/NostrChatBridge).
        // Belt-and-suspenders keep the == default clause. G3 8c (groups SPEC §3.3): the screen says the address is this
        // wallet's (one of its accounts), not that it is invalid; Send stays off either way.
        // ZC-602: the send door's own answer too (an address an account of this wallet holds now), so the form and the
        // door never disagree.
        val isOwnAddress = isOwnRecipient()
        val isValid = isValidZcashAddress(recipientAddress) &&
            !addressChecksumInvalid && // #244: SDK validateAddress said the address is malformed
            !isOwnAddress
        // B2 (Grok H1): the same question the send asks — the conversation store, not transaction history. A
        // contact paid from the wallet's Send screen (or whose id was lost to a restore) is in the history but
        // is still sent the INIT; pricing it by history showed a REPLY's chunk count and cost. No address yet (or
        // an unsendable one): nothing is stored to ask, and a first message is the INIT.
        val isFirstMessage = !isValid || isNewConversation()
        // Count with the v4 sizer (330/462) that matches the ACTUAL v4 builders the proposal packs —
        // the v3 calculateChunkCount (340/470) undercounts, understating cost and Send-All chunking.
        val chunkCount = if (message.isNotEmpty()) {
            // Pass our own address: an INIT first chunk embeds it inline, so the chunk count (and the
            // cost derived from it) depends on the address length. Without it the count would use the
            // legacy fixed budget and could disagree with what the on-chain builder actually packs.
            ZMSGProtocol.calculateV4ChunkCount(message, isFirstMessage, userAddress)
        } else 1

        // Send All is priced by the SDK for exactly this recipient, chunk count and handshake reserve;
        // (re)start that pricing before reading its result below.
        refreshSendAll(isValid)
        val isSendAll = selectedAmount == MessageAmount.SEND_ALL
        val sendAllQuote = if (isSendAll) currentSendAllQuote() else null

        // Calculate amounts with the SINGLE cost source of truth — the use case's totalCost, which
        // matches buildZip321Uri's first-output-only model: the amount rides output 0 ONCE, each
        // continuation output carries only DUST for its extra memo chunk, plus exactly one minimal
        // platform fee. The old `amountPerOutput * chunkCount` display overstated multi-chunk cost
        // by ~N× versus what actually leaves the wallet. A Send All is shown for the chunk count it
        // was priced (and will be sent) with.
        val amountPerOutput = getEffectiveAmountZatoshi()
        val totalAmount =
            CreateChunkedMessageProposalUseCase
                .totalCost(sendAllQuote?.chunkCount ?: chunkCount, Zatoshi(amountPerOutput))
                .value
        val isZero = amountPerOutput == 0L

        // For Send All: the recipient receives every output EXCEPT the platform fee — the full
        // amount on output 0 plus the dust continuations (i.e. totalCost minus the platform fee).
        val sendAllRecipientAmount = if (isSendAll) {
            totalAmount -
                CreateChunkedMessageProposalUseCase.defaultPlatformFee(Zatoshi(amountPerOutput)).value
        } else {
            0L
        }

        _state.value = ZchatComposeState.Ready(
            contacts = contacts,
            recipientAddress = recipientAddress,
            message = message,
            isValidAddress = isValid,
            isOwnAddress = isOwnAddress,
            isSending = sendInFlight && !askingPrice,
            isAskingPrice = askingPrice,
            selectedContact = selectedContact,
            showAddContactDialog = showAddContactDialog,
            contactName = contactName,
            contactNamesUnreadable = contactBook.unreadable.value,
            contactNotSaved = contactNotSaved,
            maxMessageLength = ZMSGProtocol.getMaxChunkedMessageLength(isFirstMessage, 10),
            chunkCount = chunkCount,
            messageCost = if (sendAllQuote != null) formatZatoshiExact(totalAmount) else formatZatoshi(totalAmount),
            // Amount settings
            selectedAmount = selectedAmount,
            customAmountZatoshi = customAmountZatoshi,
            showAmountDialog = showAmountDialog,
            // A Send All shows the exact zatoshi it will send and the SDK's own fee (no "~"): the old
            // 5-decimal rounding let the recipient get more than the figure on screen (C-9).
            totalAmountDisplay =
                if (sendAllQuote != null) formatZatoshiExact(totalAmount) else formatZatoshi(totalAmount),
            // QA2 Q2-91 (ZC-1664): the same total less the one ZChat fee output (defaultPlatformFee), which the card
            // names apart.
            amountDisplay =
                (totalAmount - CreateChunkedMessageProposalUseCase.defaultPlatformFee(Zatoshi(amountPerOutput)).value)
                    .let { if (sendAllQuote != null) formatZatoshiExact(it) else formatZatoshi(it) },
            // QA2 Q2-84 (ZC-1660): only a typed amount can pass the balance; the amount itself is read as before.
            isAmountOverBalance = selectedAmount == MessageAmount.CUSTOM && customAmountZatoshi > peerSpendableZatoshi,
            feeDisplay =
                if (sendAllQuote != null) {
                    formatZatoshiExact(sendAllQuote.fee.value)
                } else {
                    "~${formatZatoshi(feeEstimateZatoshi(chunkCount))}"
                },
            isZeroAmount = isZero,
            // Always surface the spendable balance — including "0 ZEC" — so a user with an empty
            // wallet sees WHY sends / Send All fail instead of a blank line. formatZatoshi already
            // renders 0L as "0 ZEC". Round 9 R9-1 (REVIEW_7C F11): "Available" is what a send to another person can
            // use (Ironwood available); sealed Orchard and confirming ZEC are left out, and the zero warning follows it.
            availableBalanceDisplay = formatZatoshi(peerSpendableZatoshi),
            spendableBalanceZatoshi = peerSpendableZatoshi,
            customAmountText = customAmountText,
            sendAllAmountDisplay =
                if (sendAllQuote != null && sendAllRecipientAmount > 0) {
                    listOf(
                        "Recipient gets: ${formatZatoshiExact(sendAllRecipientAmount)}",
                        "Platform fee: ${formatZatoshiExact(PLATFORM_FEE_MIN_ZATOSHI)}",
                        "Network fee: ${formatZatoshiExact(sendAllQuote.fee.value)}",
                        keptForHandshakeLine(sendAllQuote),
                    ).filter { it.isNotEmpty() }.joinToString("\n")
                } else {
                    ""
                },
            sendAllNote = sendAllNoteText(isSendAll, isValid, sendAllQuote),
            selectedMode = selectedMode,
            // OPEN (free NOSTR from message #1) is only offerable once we hold the peer's NOSTR key.
            openAvailable = isPeerNostrKeyKnown(),
            // When the free-OPEN path will be taken, the send costs nothing on-chain — the cost/amount
            // UI must say "Free" instead of a ZEC amount so the user isn't misled.
            isFreeOpenSend = isFreeOpenSend(),
            opensExistingChat = isValid && opensExistingChat(),
            // Callbacks
            onRecipientChange = { onRecipientChange(it) },
            onMessageChange = { onMessageChange(it) },
            onContactSelect = { onContactSelect(it) },
            onSendClick = { onSendClick() },
            onScanQrClick = { onScanQrClick() },
            onMyCodeClick = { navigationRouter.forward(ZchatReceive) },
            onBack = { navigationRouter.back() },
            onAddContact = { addr, name -> onAddContact(addr, name) },
            onShowAddContactDialog = { showAddContactDialog() },
            onDismissAddContactDialog = { dismissAddContactDialog() },
            onContactNameChange = { onContactNameChange(it) },
            onShowAmountDialog = { showAmountDialog() },
            onDismissAmountDialog = { dismissAmountDialog() },
            onAmountSelect = { onAmountSelect(it) },
            onCustomAmountChange = { onCustomAmountChange(it) },
            onModeSelect = { onModeSelect(it) },
            codeNotice = codeNotice,
            onOpenExistingChat = { navigationRouter.replace(ChatDetail(it)) },
        )
    }

    private fun getEffectiveAmountZatoshi(): Long {
        val raw = when (selectedAmount) {
            MessageAmount.CUSTOM -> customAmountZatoshi
            // Never a live-balance formula: only the SDK-priced quote, or 0 (Send disabled) until there is one.
            MessageAmount.SEND_ALL -> currentSendAllQuote()?.amount?.value ?: 0L
            else -> selectedAmount.zatoshi
        }
        // #bug-zero-send — an on-chain per-output amount of 0 is REJECTED by org.zecdev.zip321
        // (AmountTooSmall) and used to leak a raw exception to the UI. Coerce every NON-free-OPEN
        // on-chain send up to the dust minimum AT THE SOURCE so the displayed amount and the actual
        // charge agree (this same value feeds both updateState's cost display and doSendMessage's
        // proposal). Truly-free messaging routes over NOSTR (free-OPEN), never a 0-value tx.
        // EXCLUSIONS (must NOT be bumped):
        //  - SEND_ALL: a tiny-balance "Send All" must fail cleanly as InsufficientFunds, not be
        //    padded above the wallet's spendable balance.
        //  - free-OPEN: never touches the chain, so its amount is irrelevant (and left as-is).
        return if (selectedAmount == MessageAmount.SEND_ALL || isFreeOpenSend()) {
            raw
        } else {
            raw.coerceAtLeast(CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT.value)
        }
    }

    /**
     * ZIP-317 fee ESTIMATE for a chunked chat send of a fixed amount, shown as "~" next to it. Outputs =
     * [chunkCount] memo outputs + 1 platform-fee output + 1 change output. It ignores input notes, so it
     * is a floor, never a reserve: Send All does not use it (C-2) and quotes the SDK proposal's own fee.
     */
    private fun feeEstimateZatoshi(chunkCount: Int): Long = Zip317.conventionalFeeZatoshi(chunkCount + 2)

    /**
     * The memo outputs this send will really carry: the SAME isNew (no conversation id stored yet) and
     * sender address doSendMessage packs with, so the priced shape is the sent shape.
     */
    private fun sendAllChunkCount(): Int =
        if (message.isEmpty()) {
            1
        } else {
            ZMSGProtocol.calculateV4ChunkCount(message, isNewConversation(), userAddress)
        }

    // R2-6 (c): "new" means the next message must be the INIT: no conversation id yet, or one whose INIT was
    // never submitted (a refused first send). Reading only "no id" priced a Send All for a REPLY after a refusal.
    private fun isNewConversation(): Boolean =
        newConversationFor?.takeIf { it.first == recipientAddress }?.second
            ?: zchatPreferences.needsConversationInit(recipientAddress).also {
                newConversationFor = recipientAddress to it
            }

    /** C-8: the handshake cost this Send All must leave behind (zero for Vault / free Open / known key). */
    private fun sendAllKeeps(): Zatoshi =
        sendAllHandshakeReserve(
            mode = selectedMode,
            isFreeOpenSend = isFreeOpenSend(),
            isKeyExchangeComplete = zchatPreferences.isE2EKeyExchangeComplete(recipientAddress),
        )

    /**
     * Price the Send All for what the screen holds now — once per distinct recipient, chunk count, kept
     * amount and balance (C-1, C-2, C-8). Each keystroke that leaves the chunk count alone reuses the
     * price. The probes run in the use case; nothing here is stored or submitted. A result is applied
     * only while it is still the price being waited for.
     */
    private fun refreshSendAll(isValidRecipient: Boolean) {
        if (selectedAmount != MessageAmount.SEND_ALL || isFreeOpenSend() || !isValidRecipient || opensExistingChat()) {
            sendAllJob?.cancel()
            sendAllKey = null
            sendAllResult = null
            sendAllRefusal = null
            return
        }
        val key = SendAllKey(recipientAddress, sendAllChunkCount(), sendAllKeeps(), balanceFingerprint)
        if (key == sendAllKey) return
        sendAllJob?.cancel()
        sendAllKey = key
        sendAllResult = null
        sendAllRefusal = null
        sendAllJob =
            viewModelScope.launch {
                // ZC-781: the update refusal is thrown ahead of the pricing. Uncaught here it closed the app; it
                // means Send All is unavailable, with the update sentence as the reason. Nothing was priced or paid.
                var refusal: String? = null
                val result =
                    try {
                        createChunkedMessageProposal.priceSendAll(key.recipient, key.chunkCount, key.keeps)
                    } catch (e: NetworkUpgradeRequiredException) {
                        refusal = NetworkUpgradeRequiredException.MESSAGE
                        SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice)
                    }
                if (sendAllKey == key) {
                    sendAllResult = result
                    sendAllRefusal = refusal
                    updateState()
                }
            }
    }

    /** The priced Send All, or null while there is none (not selected, pricing, or unavailable). */
    private fun currentSendAllQuote(): ChatSendAllQuote? {
        val key = sendAllKey
        val max = sendAllResult as? SendMaxResult.Max
        return if (key == null || max == null) {
            null
        } else {
            ChatSendAllQuote(
                recipient = key.recipient,
                chunkCount = key.chunkCount,
                keeps = key.keeps,
                amount = max.amount,
                fee = max.fee,
            )
        }
    }

    /** C-8 (owner decision): say how much Send All left behind for the secure handshake. */
    private fun keptForHandshakeLine(quote: ChatSendAllQuote): String =
        if (quote.keeps > Zatoshi(0)) "Kept for the secure handshake: ${formatZatoshiExact(quote.keeps.value)}" else ""

    /** The one-line Send All status shown on the amount card. */
    private fun sendAllNoteText(
        isSendAll: Boolean,
        isValidRecipient: Boolean,
        quote: ChatSendAllQuote?,
    ): String =
        when {
            !isSendAll || isFreeOpenSend() -> ""
            !isValidRecipient -> "Enter the recipient first: the network fee depends on it."
            quote != null ->
                listOf(sendAllNotice.orEmpty(), keptForHandshakeLine(quote))
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
            else ->
                when (val result = sendAllResult) {
                    null -> "Working out the most you can send…"
                    is SendMaxResult.Max -> ""
                    is SendMaxResult.Unavailable -> sendAllRefusal ?: sendAllUnavailableText(result.reason)
                }
        }

    /** Why Send All cannot fill anything, with the number that explains it. Never a 0 fill. */
    private fun sendAllUnavailableText(reason: SendMaxUnavailable): String =
        when (reason) {
            is SendMaxUnavailable.StillConfirming ->
                "${formatZatoshiExact(reason.pending.value)} is still confirming. Send All works once it can " +
                    "be spent."
            is SendMaxUnavailable.OrchardSealed ->
                "${formatZatoshiExact(reason.orchard.value)} is sealed in Orchard and can only move to your " +
                    "own wallet."
            is SendMaxUnavailable.BelowFee -> {
                val keepsSome = (sendAllKey?.keeps ?: Zatoshi(0)) > Zatoshi(0)
                "Not enough to Send All: ${formatZatoshiExact(reason.have.value)} can go to others, and this " +
                    "send needs ${formatZatoshiExact(reason.fee.value)} before any amount" +
                    if (keepsSome) " (fees and the secure handshake)." else " in fees."
            }
            is SendMaxUnavailable.SaplingPresent ->
                "Send All uses only Ironwood, which is too small here; the ${formatZatoshiExact(reason.sapling.value)} " +
                    "in the Sapling pool isn't included. Pick another amount."
            SendMaxUnavailable.TexUnsupported -> "Send All isn't available for this address. Pick another amount."
            SendMaxUnavailable.CouldNotPrice -> "Couldn't work out an exact Send All. Pick another amount."
        }

    /**
     * C-9: the Send All on screen went stale by the time Send was tapped. Nothing was sent. Price it again
     * and ask for a new tap — never send a different amount on the user's behalf.
     */
    private fun onSendAllAmountChanged() {
        sendAllJob?.cancel()
        sendAllKey = null
        sendAllResult = null
        // The refused send created the conversation id; its INIT stays marked not sent (R2-6 c), so this re-read
        // still prices the INIT. Re-read anyway: the store, not this cache, says what the next send is.
        newConversationFor = null
        sendAllNotice = CreateChunkedMessageProposalUseCase.SEND_ALL_AMOUNT_UPDATED_MESSAGE
        updateState()
    }

    private fun onRecipientChange(address: String) {
        // Trim whitespace/newlines first — Zcash addresses never contain spaces, and a pasted
        // address commonly carries a trailing newline or stray space that would otherwise fail
        // validation and leave the user staring at a valid-looking address that won't send.
        val trimmed = address.trim()
        sendAllNotice = null
        codeNotice = null
        // Allow PASTING a ZCHAT contact code (zchat:c1?z=…&n=…&r=…) — the text form of the contact QR —
        // straight into the recipient field. Mirrors the camera scan: extract the Zcash address and,
        // when the code carries it, store the peer's NOSTR key so OPEN (free from message #1) becomes
        // available. Then continue with the bare address so the rest of the screen is unchanged.
        if (trimmed.startsWith("${co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode.SCHEME}:")) {
            val code = co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode.parse(trimmed)
            if (code != null && isValidZcashAddress(code.zcashAddress)) {
                // M1: a code that carries a messaging key binds it only to an address that passes the address check,
                // and only when no other address holds that key. Otherwise nothing is saved, the field keeps the text
                // as given (so a code typed in parts can still be finished), and a notice says why; the recipient is
                // not set, so a code cut on the way or a contact's second address never starts a new (paid) chat.
                val outcome =
                    when {
                        code.nostrPubkeyHex != null && !NostrPeerBinding.isBindable(code.zcashAddress) ->
                            NostrBindOutcome(NostrBindResult.INVALID_ADDRESS)
                        code.supportsOpen -> storePeerNostrFromCode(code)
                        else -> null
                    }
                val notice =
                    when (outcome?.result) {
                        NostrBindResult.INVALID_ADDRESS -> ContactCodeNotice.AddressInvalid
                        NostrBindResult.KEY_BOUND_ELSEWHERE -> outcome.otherPeer?.let { keyAlreadyBoundNotice(it) }
                        else -> null
                    }
                if (notice != null) {
                    codeNotice = notice
                    recipientAddress = trimmed
                    selectedContact = null
                    syncModeForRecipient()
                    updateState()
                    return
                }
                recipientAddress = code.zcashAddress
                selectedContact = contactBook.getContact(code.zcashAddress)
                syncModeForRecipient()
                updateState()
                return
            }
        }
        recipientAddress = trimmed
        selectedContact = contactBook.getContact(trimmed)
        syncModeForRecipient()
        updateState()
    }

    /**
     * Persist the peer's NOSTR identity from a pasted/scanned contact code, with the same key-change
     * guard the scan path and the other bind sites use: never silently overwrite a DIFFERENT existing
     * key (MITM/impersonation) — flag the key-changed banner + clear verification instead. M1: through
     * bindPeerNostrKey, which also refuses a key another address holds, our own key or address, and an
     * address that fails the address check.
     */
    private fun storePeerNostrFromCode(code: co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode): NostrBindOutcome? {
        val pub = code.nostrPubkeyHex ?: return null
        // The user's own paste: it may take the key from a cut copy of an address (M1 fixup; a KEX may not, T4b2-G1).
        return zchatPreferences.bindPeerNostrKey(code.zcashAddress, pub, code.relayUrl, mayTakeFromBrokenHolder = true)
    }

    /** M1: the "already belongs to" notice for [other]: the contact's name, or the address shortened (10…6). */
    private fun keyAlreadyBoundNotice(other: String): ContactCodeNotice.KeyAlreadyBound {
        val name =
            contactBook.getContact(other)?.name?.takeIf { it.isNotBlank() }
                ?: zchatPreferences.getNickname(other)?.takeIf { it.isNotBlank() }
                ?: "${other.take(SHORT_HEAD)}…${other.takeLast(SHORT_TAIL)}"
        return ContactCodeNotice.KeyAlreadyBound(existingPeer = other, displayName = name)
    }

    /**
     * Keep the in-flight [selectedMode] aligned with whatever is already persisted for the
     * current recipient. If the peer has a stored mode (e.g. set earlier from the chat
     * overflow picker), show that; otherwise fall back to the default (VAULT). This guarantees
     * the compose picker and the post-creation overflow picker read the same single value.
     */
    private fun syncModeForRecipient() {
        // Round 8 P4 (F6): a recipient that already has a chat keeps that chat's mode (Shielded when none was stored).
        // The new-chat default below is for new chats only; nothing is written, and the picker is hidden.
        existingChatFor = null
        if (opensExistingChat()) {
            selectedMode = zchatPreferences.getConversationModeOrNull(recipientAddress) ?: ConversationMode.DEFAULT
            revalidateAddress()
            return
        }
        // Respect a mode the user already chose for this peer; getConversationModeOrNull returns null
        // ONLY when truly unset, so an existing Vault/Tunnel/Open choice is always preserved.
        val stored = if (isValidZcashAddress(recipientAddress)) {
            zchatPreferences.getConversationModeOrNull(recipientAddress)
        } else {
            null
        }
        // Smart default for a NEW chat (no explicit mode yet): when we ALREADY hold the peer's NOSTR
        // key (scanned / pasted from their ZCHAT contact code), OPEN is the brilliant cold-start — a
        // free, instant, end-to-end-encrypted NOSTR DM from message #1 with NO on-chain handshake.
        // Without the key OPEN has nowhere to route, so fall back to Tunnel (one on-chain bootstrap,
        // then free NOSTR). The picker stays visible so Vault/Tunnel remain one tap away.
        val newChatDefault = if (isPeerNostrKeyKnown()) ConversationMode.OPEN else NEW_CHAT_DEFAULT_MODE
        // ZC-1462: a stored mode the user never chose (not explicit) does not beat Open once the peer's relay key is known
        // (a scan or paste of their code just bound it): the chat starts free. The user's own choice is kept.
        val keepStored =
            stored != null &&
                (!isPeerNostrKeyKnown() || zchatPreferences.isConversationModeExplicit(recipientAddress))
        val resolved = if (keepStored) stored else newChatDefault
        // OPEN delivers a free NIP-17 gift-wrapped NOSTR DM from message #1 — but ONLY when we already
        // hold the peer's NOSTR key (scanned from their ZCHAT contact QR; persisted by ScanZashiAddressVM).
        // Without that key OPEN has nowhere to route, so coerce it to TUNNEL (one on-chain bootstrap, then
        // free NOSTR). When the key IS known, OPEN is honored so the very first message goes out free.
        selectedMode = if (resolved == ConversationMode.OPEN && !isPeerNostrKeyKnown()) {
            ConversationMode.TUNNEL
        } else {
            resolved
        }
        revalidateAddress()
    }

    /**
     * #244: authoritative async address validation. Cancels any prior in-flight check, then (only when
     * the fast prefix/length check passes) asks the SDK to validate. Sets [addressChecksumInvalid] true
     * ONLY when the SDK explicitly returns Invalid; on any exception/timeout it leaves it false
     * (fail-open). Stale results (recipient changed mid-flight) are discarded. Send entry fails
     * CLOSED: onSendClick blocks on the flag and doSendMessage joins [addressValidationJob] before
     * dispatching, so a tap can never race an in-flight check onto the chain.
     */
    private fun revalidateAddress() {
        val addr = recipientAddress
        addressValidationJob?.cancel()
        if (!isValidZcashAddress(addr)) {
            addressChecksumInvalid = false
            return
        }
        addressValidationJob = viewModelScope.launch {
            val invalid = runCatching { validateAddress(addr) is AddressType.Invalid }.getOrDefault(false)
            if (recipientAddress == addr && addressChecksumInvalid != invalid) {
                addressChecksumInvalid = invalid
                updateState()
            }
        }
    }

    /** True iff we hold the peer's NOSTR pubkey for the current recipient — the precondition for a
     *  free OPEN send from message #1. Set when the user scans the peer's ZCHAT contact QR. */
    private fun isPeerNostrKeyKnown(): Boolean =
        isValidZcashAddress(recipientAddress) && zchatPreferences.getPeerNostrPubkey(recipientAddress) != null

    /** A free OPEN send is taken when OPEN is selected AND we hold the peer's NOSTR key. Such a send
     *  travels over NOSTR as a v4 INIT and NEVER touches the chain — so it must skip the ZEC cost
     *  disclaimer and the on-chain proposal path. */
    private fun isFreeOpenSend(): Boolean =
        selectedMode == ConversationMode.OPEN && isPeerNostrKeyKnown()

    /** Round 8 P4 (F6): the current recipient already has a chat, so Send opens it instead of sending from here. */
    private fun opensExistingChat(): Boolean =
        isValidZcashAddress(recipientAddress) &&
            (
                existingChatFor?.takeIf { it.first == recipientAddress }?.second
                    ?: zchatPreferences.hasExistingChat(recipientAddress).also { existingChatFor = recipientAddress to it }
            )

    private fun onModeSelect(mode: ConversationMode) {
        // Round 8 P4: an existing chat's mode is changed in that chat, never from New Chat (the picker is hidden).
        if (opensExistingChat()) return
        selectedMode = mode
        sendAllNotice = null
        // Persist immediately so the choice survives process death / recomposition and is
        // already in place if the conversation is created. doSendMessage re-asserts it too.
        if (isValidZcashAddress(recipientAddress)) {
            zchatPreferences.setConversationMode(recipientAddress, mode)
            // Pin this as the user's explicit choice so a peer's ZMODE can't silently auto-adopt over
            // it (handleModeControl only auto-adopts when !isConversationModeExplicit). Only here —
            // NOT at doSendMessage's default fallback, which must leave never-chosen chats adoptable.
            zchatPreferences.setConversationModeExplicit(recipientAddress)
        }
        updateState()
    }

    private fun onMessageChange(newMessage: String) {
        message = newMessage
        sendAllNotice = null
        updateState()
    }

    private fun onContactSelect(contact: Contact) {
        sendAllNotice = null
        codeNotice = null
        selectedContact = contact
        recipientAddress = contact.address
        syncModeForRecipient()
        updateState()
    }

    private fun onScanQrClick() {
        navigationRouter.forward(ScanArgs(flow = ScanFlow.ZCHAT, isScanZip321Enabled = false))
    }

    private fun showAddContactDialog() {
        showAddContactDialog = true
        contactName = ""
        updateState()
    }

    private fun dismissAddContactDialog() {
        showAddContactDialog = false
        contactNotSaved = false
        contactName = ""
        updateState()
    }

    private fun onContactNameChange(name: String) {
        contactName = name
        updateState()
    }

    private fun onAddContact(address: String, name: String) {
        if (name.isNotBlank() && isValidZcashAddress(address)) {
            // ZC-733-B: refused while the saved names cannot be read; the dialog stays open and says so.
            val saved =
                contactBook.tryAddContact(
                    Contact(
                        address = address,
                        name = name.trim(),
                        addedAt = Instant.now()
                    )
                )
            if (!saved) {
                contactNotSaved = true
                updateState()
                return
            }
            contactNotSaved = false
            showAddContactDialog = false
            contactName = ""
            selectedContact = contactBook.getContact(address)
            updateState()
        }
    }

    private fun showAmountDialog() {
        showAmountDialog = true
        updateState()
    }

    private fun dismissAmountDialog() {
        showAmountDialog = false
        updateState()
    }

    private fun onAmountSelect(amount: MessageAmount) {
        selectedAmount = amount
        sendAllNotice = null
        if (amount != MessageAmount.CUSTOM && amount != MessageAmount.SEND_ALL) {
            showAmountDialog = false
        }
        updateState()
    }

    private fun onCustomAmountChange(amountStr: String) {
        // Store raw text to prevent text field glitching from round-trip conversion
        customAmountText = amountStr
        // F1: the field hands over its settled text, canonical with '.' as the decimal point and never grouped (the
        // field has read the phone's marks and asked about an ambiguous paste). It is parsed with Locale.ROOT: the
        // phone's grouping rule would read a canonical "1.500" (1.5) as 1500 on a Spanish phone. Precision is
        // preserved through BigDecimal; an invalid/blank/negative amount yields 0.
        customAmountZatoshi = parseCustomAmountZatoshi(amountStr, java.util.Locale.ROOT)
        updateState()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun onSendClick() {
        if (sendMayHaveGoneOut || !isValidZcashAddress(recipientAddress)) return
        // ZC-601: a price question is open; only Pay or Cancel answers it.
        if (pricedSend != null) return

        // #244 FAIL-CLOSED at send entry: the async SDK checksum check only disables the button
        // reactively, so a tap can land before recomposition. Never dispatch to an address the SDK
        // flagged Invalid (doSendMessage additionally awaits an in-flight check).
        if (addressChecksumInvalid) return

        // Round 8 P4 (F6): a recipient with a chat is handed to that chat; nothing is spent here, so no cost notice,
        // and an empty message just opens the chat. This reads the per-recipient answer the form shows; doSendMessage
        // asks the store again, so a chat that came to exist while this screen was open is handed over too.
        if (opensExistingChat()) {
            doSendMessage()
            return
        }
        if (message.isBlank()) return

        // ZC-1272: a paid send here always meets its price question, so the one-time cost notice never stands on top of it;
        // its Pay marks the notice as read.
        doSendMessage()
    }

    /**
     * Called when user acknowledges the message cost disclaimer.
     */
    fun acknowledgeCostDisclaimer() {
        zchatPreferences.setAcknowledgedMessageCost()
        _showCostDisclaimer.value = false
        doSendMessage()
    }

    /**
     * Called when user dismisses the disclaimer without acknowledging.
     */
    fun dismissCostDisclaimer() {
        _showCostDisclaimer.value = false
    }

    /** ZC-601 — Pay on the price question: send exactly what it priced, or nothing. */
    fun confirmPaidAction() {
        val priced = pricedSend ?: return
        pricedSend = null
        _paidActionConfirmation.value = null
        doSendMessage(priced)
    }

    /** ZC-601 — Cancel, Back or a tap outside the price question: nothing was built, stored or paid; the form stays. */
    fun dismissPaidAction() {
        pricedSend = null
        _paidActionConfirmation.value = null
    }

    /** One of this wallet's own addresses: the self registry, the default address, or the send door's own answer. */
    private fun isOwnRecipient(): Boolean =
        recipientAddress.isNotEmpty() &&
            (
                zchatPreferences.isSelfAddress(recipientAddress) ||
                    (userAddress != null && recipientAddress == userAddress) ||
                    createChunkedMessageProposal.refusesAsOwnAddress(recipientAddress)
            )

    /** ZC-601 — the tap's work ended (a question, a refusal, a hand-off or a failure): Send may be tapped again. */
    private fun endFlight() {
        sendInFlight = false
        askingPrice = false
    }

    /** ZC-601 (R4) — whether the form still holds exactly what [priced] priced: recipient, text, mode and amount. */
    private fun holdsWhatWasPriced(priced: PricedSend): Boolean =
        priced.recipient == recipientAddress &&
            priced.text == message &&
            priced.mode == selectedMode &&
            !isFreeOpenSend() &&
            if (selectedAmount == MessageAmount.SEND_ALL) {
                priced.sendAllQuote != null && priced.sendAllQuote == currentSendAllQuote()
            } else {
                priced.sendAllQuote == null && priced.amountPerOutput == getEffectiveAmountZatoshi()
            }

    /**
     * ZC-601 (R2, R3) — the exact price of the one transaction this New Chat send would be, or null when the wallet
     * gives none (the probe failed, or its proposal needs Orchard inputs or more than one transaction). Nothing is
     * built, stored or submitted: [CreateChunkedMessageProposalUseCase.priceMessage] probes with placeholder memos. The
     * door's refusals (Keystone, own address, update required, sealed Orchard) and a shortfall are thrown with the texts
     * the send itself gives. A Send All is not probed again: its priced quote is the price.
     */
    private suspend fun priceNewChatSend(
        recipient: String,
        senderAddress: String,
        sendAllQuote: ChatSendAllQuote?,
    ): PricedSend? {
        val text = message
        val mode = selectedMode
        val account = getSelectedWalletAccount()
        // As ChatViewModel.quotePayment: the Keystone refusal and the sealed-Orchard refusal come before anything is
        // probed (a probe on an Orchard-only wallet would price a public crossing). priceMessage then refuses an own
        // address and the update-required state before its probe; a Send All in that state has no quote to get here.
        if (!KeystoneAvailability.CHAT_SIGNING && account is KeystoneAccount) throw KeystoneChatSendRefusedException()
        orchardSealedRefusal(account.unified)?.let { throw it }
        val memos = ZMSGProtocol.calculateV4ChunkCount(text, zchatPreferences.needsConversationInit(recipient), senderAddress)
        val amount: Zatoshi
        val fee: Zatoshi
        if (sendAllQuote != null) {
            if (sendAllQuote.recipient != recipient || memos != sendAllQuote.chunkCount) throw SendAllAmountChangedException()
            amount = sendAllQuote.amount
            fee = sendAllQuote.fee
        } else {
            amount = Zatoshi(getEffectiveAmountZatoshi())
            val probe =
                try {
                    createChunkedMessageProposal.priceMessage(recipient, memos, amount)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val refusal =
                        e is KeystoneChatSendRefusedException ||
                            e is OwnAddressChatSendRefusedException ||
                            e is NetworkUpgradeRequiredException
                    if (refusal) throw e
                    return null
                }
            when (probe) {
                is FeeProbe.Short -> {
                    // ZC-1948: the wallet's margin (only the message leg is counted here, not its setup legs).
                    val reason =
                        ShortfallReason.classifyWalletSend(
                            account = account,
                            need = CreateChunkedMessageProposalUseCase.minimumRequiredSpendable(memos, amount),
                        )
                    // ZC-1306: funds still confirming are a wait, as in the chat: asked at the formula figure of every leg.
                    if (reason is ShortfallReason.StillConfirming) return priceWhileConfirming(recipient, text, mode, memos, amount)
                    throw InsufficientFundsException(
                        message =
                            CreateChunkedMessageProposalUseCase.shortfallNoticeFor(
                                reason = reason,
                                isValueTransfer = amount > CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT,
                            ),
                    )
                }
                is FeeProbe.Built -> {
                    if (probe.usesOrchardInputs || probe.transactionCount != 1) return null
                    fee = probe.fee
                }
            }
        }
        // ZC-1272: a Tunnel chat's setup is in this one question. A Send All keeps its own figure and its kept reserve.
        val kexDue = sendAllQuote == null && mode != ConversationMode.VAULT && setupKexDue(recipient)
        val zbootDue = sendAllQuote == null && mode != ConversationMode.VAULT && setupZbootDue(recipient)
        val outputs = CreateChunkedMessageProposalUseCase.totalCost(1, CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT)
        // A one-memo message has the shape of a setup leg, so its fee is that leg's; any other message says nothing about it.
        val setupLeg =
            if (memos == 1 && outputs.value + fee.value > onChainSendMinimumCost().value) {
                Zatoshi(outputs.value + fee.value)
            } else {
                onChainSendMinimumCost()
            }
        return PricedSend(
            recipient = recipient,
            text = text,
            mode = mode,
            amountPerOutput = amount.value,
            quote =
                ChatSendQuote(
                    recipient = recipient,
                    chunkCount = memos,
                    amountPerOutput = amount,
                    total = Zatoshi(CreateChunkedMessageProposalUseCase.totalCost(memos, amount).value + fee.value),
                    fee = fee,
                ),
            sendAllQuote = sendAllQuote,
            setupLegs = (if (kexDue) 1 else 0) + (if (zbootDue) 1 else 0),
            setupLeg = setupLeg.takeIf { kexDue || zbootDue },
            coversKex = kexDue,
            coversZboot = zbootDue,
        )
    }

    /** ZC-1272: our key exchange is still to go, as the chat's bootstrap reads it. */
    private fun setupKexDue(recipient: String): Boolean =
        !zchatPreferences.isE2EKeyExchangeComplete(recipient) && !zchatPreferences.isOwnBootSent(recipient)

    /** ZC-1272: our relay identity can only go on-chain, as ChatViewModel.firstMessageQuote reads it. */
    private fun setupZbootDue(recipient: String): Boolean =
        zchatPreferences.getSentNostrBootPubkey(recipient) == null &&
            (
                zchatPreferences.getPeerNostrPubkey(recipient) == null ||
                    !co.electriccoin.zcash.ui.nostr.NostrChatBridge
                        .isOutboundReady()
            )

    /**
     * ZC-1306 — the price of a first message whose funds still confirm, as the chat quotes one: the send will run from the
     * chat ([ChatViewModel.resumeFundsWaitRows]), our key exchange, then the message, then our relay identity when it can
     * only go on-chain. No probe can price a wallet that is short, so each leg is the formula figure, and each is held to it.
     */
    private fun priceWhileConfirming(
        recipient: String,
        text: String,
        mode: ConversationMode,
        memos: Int,
        amount: Zatoshi,
    ): PricedSend {
        val messageLeg = onChainSendMinimumCost(memos, amount)
        val outputs = CreateChunkedMessageProposalUseCase.totalCost(memos, amount)
        val zbootDue = setupZbootDue(recipient)
        return PricedSend(
            recipient = recipient,
            text = text,
            mode = mode,
            amountPerOutput = amount.value,
            quote =
                ChatSendQuote(
                    recipient = recipient,
                    chunkCount = memos,
                    amountPerOutput = amount,
                    total = messageLeg,
                    fee = Zatoshi(messageLeg.value - outputs.value),
                ),
            sendAllQuote = null,
            setupLegs = if (zbootDue) 2 else 1,
            setupLeg = onChainSendMinimumCost(),
            waitsForFunds = true,
        )
    }

    /** ZC-601 (R1), ZC-1272 — open the one question for [priced]: every leg it covers, with its figure. */
    private fun askThePrice(priced: PricedSend) {
        pricedSend = priced
        val outputs = CreateChunkedMessageProposalUseCase.totalCost(1, CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT)
        val setupFees = priced.setupLegs * ((priced.setupLeg?.value ?: 0L) - outputs.value)
        _paidActionConfirmation.value =
            PaidActionConfirmation(
                action =
                    when {
                        priced.mode != ConversationMode.VAULT -> PaidAction.NEW_CHAT_FIRST_MESSAGE_TUNNEL
                        priced.waitsForFunds -> PaidAction.FIRST_MESSAGE
                        else -> PaidAction.NEW_CHAT_FIRST_MESSAGE
                    },
                onChainMessages = 1 + priced.setupLegs,
                totalCost = priced.acceptedTotal,
                includesRelayIdentity = priced.coversZboot || (priced.waitsForFunds && priced.setupLegs == 2),
                includesChatSetup = priced.setupLegs > 0,
                exact = !priced.waitsForFunds,
                networkFee = Zatoshi(priced.quote.fee.value + setupFees),
                memos = priced.quote.chunkCount,
                setupLegs = priced.setupLegs,
                setupLegCost = priced.setupLeg,
                messageLegCost = priced.quote.total.takeIf { priced.setupLeg != null },
                waitsForFunds = priced.waitsForFunds,
            )
    }

    /**
     * ZC-1306 — Pay while the funds confirm: the row, with the total the user accepted, and nothing else. No key, no mark, no
     * proposal: a block sends it from the chat, within that total ([ChatViewModel.resumeFundsWaitRows]).
     */
    private suspend fun saveWaitingFirstMessage(paid: PricedSend) {
        val accountKey = GetDefaultUnifiedAddressUseCase.accountKeyOf(getSelectedWalletAccount())
        zchatPreferences.setConversationMode(paid.recipient, paid.mode)
        zchatPreferences.addPendingMessage(
            ZchatPreferences.PendingMessageData(
                id = "pending_${System.nanoTime()}",
                text = paid.text,
                timestampMillis = Instant.now().toEpochMilli(),
                peerAddress = paid.recipient,
                status =
                    co.electriccoin.zcash.ui.screen.chat.model.MessageStatus.SENDING.name,
                sendAmountZatoshi = paid.amountPerOutput,
                submitTracked = true,
                acceptedPriceZatoshi = paid.acceptedTotal.value,
                acceptedAccountKey = accountKey,
                acceptedMode = paid.mode.name.takeIf { paid.mode != ConversationMode.VAULT },
            ),
        )
    }

    /**
     * ZC-1272 — before the message may go out, the legs Pay covered go on disk, so the chat pays them with no second
     * question, also after a restart. A write the store refuses covers nothing: those legs ask for themselves later.
     */
    private suspend fun recordCoveredSetup(paid: PricedSend) {
        val leg = paid.setupLeg?.value ?: return
        val setup =
            QuotedChatSetup(
                kex = leg.takeIf { paid.coversKex },
                zboot = leg.takeIf { paid.coversZboot },
                // ZC-1348: whose answer it is; no other account pays these legs.
                account = GetDefaultUnifiedAddressUseCase.accountKeyOf(getSelectedWalletAccount()),
            )
        // ZC-1390: the allowance names the same account (an earlier question's account on it would hold this one back).
        if (zchatPreferences.setQuotedChatSetup(paid.recipient, setup.encode()) &&
            paid.coversZboot &&
            zchatPreferences.setZbootQuotedAccount(paid.recipient, setup.account)
        ) {
            zchatPreferences.setZbootQuoted(paid.recipient, true)
        }
    }

    /** ZC-1272 — nothing of the message went out: the question it answered covers nothing any more. */
    private fun withdrawCoveredSetup(paid: PricedSend) {
        if (paid.setupLeg == null) return
        zchatPreferences.setQuotedChatSetup(paid.recipient, null)
        if (paid.coversZboot) zchatPreferences.setZbootQuoted(paid.recipient, false)
    }

    /**
     * Internal function to actually send the message.
     *
     * ZC-601: a send that would build a transaction runs twice. Without [paid] (the tap) it stops after today's checks,
     * takes the exact price and opens the question: nothing is built, stored or submitted. With [paid] (Pay on that
     * question) it sends what was priced through the one chokepoint call below, which refuses any other transaction.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun doSendMessage(paid: PricedSend? = null) {
        viewModelScope.launch {
            // D2 (REVIEW_7C F11): set when the use case is about to ask the SDK to store and broadcast this send.
            var submitStarted = false
            var isValueTransfer = false
            // ZC-1272: the question whose legs this run put on disk, until the message's submit started.
            var paidSend: PricedSend? = null
            var coveredMessagePeer: String? = null
            try {
                // Update state to show sending
                val currentState = _state.value as? ZchatComposeState.Ready ?: return@launch

                // Re-entry guard (parity with GroupViewModel's SF-3 in-flight guard): a double-tap on
                // Send can dispatch two clicks before the isSending=true recomposition disables the
                // button. viewModelScope is Main.immediate, so the first tap has already set
                // isSending=true synchronously by the time the second runs — bail here so the second tap
                // can't build + directSubmit a SECOND on-chain proposal (double charge).
                if (currentState.isSending || sendInFlight) return@launch
                // ZC-601: a question is open; a second tap neither prices nor asks again.
                if (paid == null && pricedSend != null) return@launch

                // Defense-in-depth: never send to your OWN address. The send button is already
                // disabled for this case (isValidAddress=false in updateState), so just no-op —
                // we must NOT set a terminal Error state here, which would wedge the compose screen
                // with no input field and no way back.
                if (isOwnRecipient()) {
                    askingPrice = false
                    _state.value = currentState.copy(isSending = false, isAskingPrice = false)
                    return@launch
                }
                sendInFlight = true
                // ZC-601 (R6): before Pay the form works out the price; "Sending" starts at Pay (or with a free send).
                askingPrice = paid == null && !isFreeOpenSend() && !opensExistingChat()
                _state.value = currentState.copy(isSending = !askingPrice, isAskingPrice = askingPrice)

                // #244 FAIL-CLOSED: if the authoritative SDK checksum validation is still in flight,
                // await it — and abort if it flags the address Invalid. Without this, a send tapped
                // during the in-flight window dispatched to a corrupted-but-length-valid address.
                // Same non-wedging policy as the self-send guard above: updateState re-renders with
                // the Send button disabled (isValidAddress=false) instead of a terminal Error.
                addressValidationJob?.join()
                if (addressChecksumInvalid) {
                    endFlight()
                    updateState()
                    return@launch
                }

                // Round 8 P4 (PHONE_TRIAGE F6): this screen has no ratchet. For a recipient that already has a chat,
                // a message sent from here went out as a plaintext memo outside the end-to-end session (bypassing
                // the chat's refusal while E2E is on without keys, and paying on-chain for a Tunnel chat). Hand the
                // text to that chat as its draft and open it: no proposal is built and no mode is written.
                val recipient = recipientAddress
                if (withContext(Dispatchers.IO) { zchatPreferences.hasExistingChat(recipient) }) {
                    handOffToExistingChat(recipient)
                    return@launch
                }
                // onSendClick skipped the empty-message check on the form's answer that a chat exists. The store now says
                // otherwise: send nothing, and let the next tap take the new-chat path.
                if (message.isBlank()) {
                    existingChatFor = null
                    endFlight()
                    updateState()
                    return@launch
                }

                val senderAddress = userAddress ?: throw IllegalStateException("User address not available")

                // ZC-601 (R4): Pay sends what was asked about. A form that holds anything else by now (another
                // recipient, text, mode or amount) sends nothing and shows again; the next tap asks its own price.
                if (paid != null && !holdsWhatWasPriced(paid)) {
                    endFlight()
                    if (selectedAmount == MessageAmount.SEND_ALL) onSendAllAmountChanged() else updateState()
                    return@launch
                }

                // FREE OPEN path (peer NOSTR key known): send the FIRST message as a v4 INIT over NOSTR
                // instead of an on-chain memo. The INIT carries our address so the recipient can place us
                // in their Message Requests inbox and reply. This NEVER spends ZEC. We persist the row +
                // publish synchronously (awaited) BEFORE navigating so the publish isn't cancelled when
                // this ViewModel is cleared on navigation.
                if (isFreeOpenSend()) {
                    // T1b-1 §2 (K0 §4): the chat is closed on every transport while a Keystone account is selected. The
                    // Open INIT carries the chat address, so it is refused like an on-chain send (the Keystone text).
                    val selectedAccount = withContext(Dispatchers.IO) { getSelectedWalletAccount() }
                    if (!KeystoneAvailability.CHAT_SIGNING && selectedAccount is KeystoneAccount) {
                        throw KeystoneChatSendRefusedException()
                    }
                    sendFirstOpenMessageOverNostr(senderAddress, recipientAddress, message)
                    navigationRouter.replace(ChatDetail(recipientAddress))
                    return@launch
                }

                // C-9: a Send All sends exactly the quote on screen, or nothing. It used to recompute the
                // amount from the live balance here, so the recipient could get more than "Recipient gets"
                // said. No quote (still pricing, or unavailable) means there is nothing to send yet.
                sendAllNotice = null
                val sendAllQuote = if (selectedAmount == MessageAmount.SEND_ALL) currentSendAllQuote() else null
                if (selectedAmount == MessageAmount.SEND_ALL && sendAllQuote == null) {
                    endFlight()
                    onSendAllAmountChanged()
                    return@launch
                }

                // ZC-601 (R1, R2): the tap ends here, before the first write. Take the SDK's price of exactly this
                // transaction and ask; only Pay comes back (with [paid]) and goes on to the lines below.
                if (paid == null) {
                    val priced = withContext(Dispatchers.IO) { priceNewChatSend(recipient, senderAddress, sendAllQuote) }
                    if (priced == null) {
                        // R3: no exact price, so no question and nothing sent.
                        endFlight()
                        _state.value = errorState(ChatSendCopy.PRICE_QUOTE_UNAVAILABLE)
                        return@launch
                    }
                    // The tap's work ends before the question opens, so Pay never meets a flight still marked as
                    // running. The form moved while the price was worked out: that price is for a send it no
                    // longer holds, and nothing is asked.
                    endFlight()
                    if (holdsWhatWasPriced(priced)) askThePrice(priced)
                    updateState()
                    return@launch
                }
                val text = paid.text
                // ZC-1272: the user accepted a price for what the form still holds: the cost notice has nothing to add.
                zchatPreferences.setAcknowledgedMessageCost()
                if (paid.waitsForFunds) {
                    withContext(Dispatchers.IO) { saveWaitingFirstMessage(paid) }
                    endFlight()
                    navigationRouter.replace(ChatDetail(recipient))
                    return@launch
                }
                paidSend = paid

                // The work below — SharedPreferences reads/writes and proposal creation/submit — was
                // running on the main thread (viewModelScope.launch defaults to Main.immediate),
                // tripping StrictMode DiskRead/DiskWrite violations (observed ~20x per send on device)
                // and janking the send. Move it off the main thread; the StateFlow updates above are
                // thread-safe to set from any thread, and the navigation below stays on Main.
                withContext(Dispatchers.IO) {
                    // C-9: the quote was priced for this recipient and this many memo outputs. Check both
                    // BEFORE anything is persisted, so a stale Send All leaves no conversation behind.
                    val chunksNow =
                        ZMSGProtocol.calculateV4ChunkCount(text, zchatPreferences.needsConversationInit(recipient), senderAddress)
                    if (sendAllQuote != null &&
                        (sendAllQuote.recipient != recipient || chunksNow != sendAllQuote.chunkCount)
                    ) {
                        throw SendAllAmountChangedException()
                    }
                    // ZC-601: the same for the price the user agreed to (the INIT may have gone out meanwhile, which
                    // changes the memo count). The door would refuse it too, but only after the writes below.
                    if (chunksNow != paid.quote.chunkCount) throw SendQuoteChangedException()
                    // ZC-1334: a block pays a covered key exchange; not before this message's own submit has started.
                    coveredMessagePeer = paid.recipient.also(ChatViewModel.coveredMessagesBuilding::add)
                    // ZC-1352: the user sends again: an earlier deleted first message withdraws nothing of this one.
                    zchatPreferences.setAutomaticSetupWithdrawn(paid.recipient, false)
                    recordCoveredSetup(paid)

                    // Persist the chosen transport mode for this peer BEFORE the conversation is
                    // created, so the conversation comes into existence in the selected mode and the
                    // message router (and the post-creation overflow picker) read the same value.
                    // Defaults to VAULT when the user never touched the selector.
                    zchatPreferences.setConversationMode(recipient, paid.mode)

                    // ZMSG v4 Protocol: Use conversation IDs for reliable threading.
                    // getOrCreateConversationIdForMessage is atomic at the SharedPreferences level,
                    // safe across all VMs/services without needing a per-VM mutex.
                    // isNew tells us if this is the first message (INIT format needed). R2-6 (c): it stays true
                    // until an INIT's submit has started, so a first send refused before submission (Orchard-only
                    // wallet, shortfall, Send All short) is sent again as the INIT, never as a REPLY.
                    val (convId, isNew) = zchatPreferences.getOrCreateConversationIdForMessage(recipient)

                    // A Send All sends exactly its SDK-priced quote (its chunk count was re-checked above);
                    // every other tier sends the dust-coerced per-output amount. ZC-601: either way it is the amount
                    // the question priced.
                    val amountPerOutput = paid.amountPerOutput
                    isValueTransfer = amountPerOutput > CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT.value
                    // Platform fee is always minimal — never the full send amount (the use-case clamps
                    // it too, but keep the caller honest so cost display and charge agree).
                    val platformFee = Zatoshi(PLATFORM_FEE_MIN_ZATOSHI)

                    // Create the proposal using chunked message use case with direct submit
                    createChunkedMessageProposal(
                        destinationAddress = recipient,
                        senderAddress = senderAddress,
                        message = text,
                        isFirstMessage = isNew,
                        amountPerOutput = Zatoshi(amountPerOutput),
                        platformFeeAmount = platformFee,
                        directSubmit = true,
                        skipNavigation = true,
                        conversationId = convId,
                        // The use case re-proposes output 0 and refuses to submit unless the proposal is
                        // the one quoted (same fee, memo count, Ironwood only, one transaction).
                        sendAllQuote = sendAllQuote,
                        // ZC-601: the price the user agreed to. The use case refuses, before the spend auth and the
                        // submit, any proposal that is not that transaction (SendQuoteChangedException).
                        sendQuote = paid.quote,
                        // R2-6 (c): from here the INIT may go out; it must never be sent a second time. D2: from here
                        // any send may go out, so the catch below must not offer it again.
                        onSubmitStarting = {
                            submitStarted = true
                            ChatViewModel.coveredMessagesBuilding.remove(paid.recipient)
                            if (isNew) zchatPreferences.markConversationInitSubmitted(recipient, convId)
                        },
                    )
                }

                // Navigate to the chat conversation that was just started (back on Main).
                navigationRouter.replace(ChatDetail(recipient))

            } catch (e: Exception) {
                // ZC-1334: nothing of the message went out, a closed screen included: no block may pay what the question covered.
                if (!submitStarted) paidSend?.let(::withdrawCoveredSetup)
                coveredMessagePeer?.let(ChatViewModel.coveredMessagesBuilding::remove)
                // The screen closed (our own scope was cancelled): there is nothing to show. Any other
                // CancellationException, such as a timeout inside the send, is judged below like every failure.
                if (e is CancellationException) ensureActive()
                // The send ended here; every state set below may offer Send (or Retry) again.
                endFlight()
                // D2 (REVIEW_7C F11): the submit started and this failure does not prove that nothing was stored
                // (a stored-then-rejected Failure with a txid reaches here unchanged since C4). The SDK may still
                // broadcast it, so never re-price it and never offer a Retry that builds the same send again.
                if (submitStarted && !memoFailureProvesNothingSent(e, submitStarted = true)) {
                    onSendMayHaveGoneOut(isValueTransfer)
                    return@launch
                }
                // C-9: the Send All quote went stale before anything was authorised or submitted. Re-price
                // and ask for a new tap instead of the terminal Error screen.
                if (e is SendAllAmountChangedException) {
                    onSendAllAmountChanged()
                    return@launch
                }
                // ZC-601 (R4): the transaction built at Pay was not the one the question priced. The door refused it
                // before the spend auth: nothing was signed, stored or paid. The form stays; the price is taken
                // again and the question comes back with the new figure (a Send All: its "Amount updated" path).
                if (e is SendQuoteChangedException) {
                    if (selectedAmount == MessageAmount.SEND_ALL) {
                        onSendAllAmountChanged()
                    } else {
                        newConversationFor = null
                        askingPrice = true
                        updateState()
                        doSendMessage()
                    }
                    return@launch
                }
                // C-9: the same for a Send All the SDK now refuses as short: nothing was sent, and the new
                // figure (or the reason it cannot fill) shows on the card.
                if (selectedAmount == MessageAmount.SEND_ALL &&
                    CreateChunkedMessageProposalUseCase.isInsufficientFundsError(e)
                ) {
                    onSendAllAmountChanged()
                    return@launch
                }
                // The 0-ZEC AmountTooSmall wrapper (and any other SDK failure) is mapped to ZCHAT
                // copy here — the raw "…TransactionProposalNotCreatedException: AmountTooSmall(value=0)"
                // must never reach the full-screen Error state.
                _state.value = errorState(e.toZchatUserMessage("Couldn't send your message. Please try again."))
            }
        }
    }

    /**
     * Round 8 P4 (F6): put the typed text into [peer]'s chat draft (next to a draft already there) and open that chat,
     * where its own send path encrypts or refuses. Nothing is sent from here.
     */
    private suspend fun handOffToExistingChat(peer: String) {
        val typed = message
        if (typed.isNotBlank()) {
            withContext(Dispatchers.IO) {
                zchatPreferences.setDraft(peer, mergedHandOffDraft(zchatPreferences.getDraft(peer), typed))
            }
        }
        endFlight()
        navigationRouter.replace(ChatDetail(peer))
    }

    /**
     * D2 (REVIEW_7C F11): a send whose submit started may have gone out. Clear the message and the amount (no custom
     * amount, no Send All quote) so no tap can build the same send again, and show the "may already have been sent"
     * notice, worded for a payment when the send carried value. Its only action closes the screen: no Retry, no
     * re-price.
     */
    private fun onSendMayHaveGoneOut(isValueTransfer: Boolean) {
        sendMayHaveGoneOut = true
        message = ""
        selectedAmount = MessageAmount.MINIMAL
        customAmountZatoshi = MessageAmount.MINIMAL.zatoshi
        customAmountText = ""
        showAmountDialog = false
        sendAllJob?.cancel()
        sendAllKey = null
        sendAllResult = null
        sendAllNotice = null
        newConversationFor = null
        _showCostDisclaimer.value = false
        _state.value =
            ZchatComposeState.MayHaveBeenSent(
                message =
                    if (isValueTransfer) {
                        ChatSendCopy.PAYMENT_MAY_HAVE_BEEN_SENT
                    } else {
                        SubmitAttemptProof.MAY_HAVE_BEEN_SENT
                    },
                onClose = { navigationRouter.back() },
            )
    }

    /**
     * Send the FIRST message of an OPEN conversation over NOSTR as a ZMSG v4 INIT.
     *
     * Why a v4 INIT (not raw text): the recipient has never met us, so a gift-wrap from our NOSTR pubkey
     * is "unknown sender" on their side (NostrChatBridge.dispatch drops unknown-pubkey DMs). The INIT
     * carries our Zcash ADDRESS, which is exactly what the recipient needs to (a) recognise who we are and
     * (b) reply over NOSTR. Their dispatch routes an unknown-pubkey INIT into a Message Requests inbox; on
     * accept they bind our pubkey→address and our subsequent (raw) OPEN messages flow normally.
     *
     * Money-safety: this path is GUARANTEED free — it only ever publishes to a relay. If the relay
     * publish fails (service not started / no acks) the row is marked FAILED and the user can retry; we
     * NEVER fall back to an on-chain charge for an OPEN message.
     */
    private suspend fun sendFirstOpenMessageOverNostr(
        senderAddress: String,
        recipient: String,
        text: String
    ) {
        val peerPub = zchatPreferences.getPeerNostrPubkey(recipient) ?: return
        // Lock the conversation to OPEN so the chat continues free over NOSTR after this first message.
        zchatPreferences.setConversationMode(recipient, ConversationMode.OPEN)
        // #250-r4: anchor the rotation index this peer can verify. We carry our Zcash address in the v4
        // INIT and they reply to our CURRENT NOSTR pubkey, so on first contact they hold our current
        // index. Seed knownIdx only when unset (-1 default) so future inbound rotation never regresses,
        // and so a later announce is never signed with an index the peer never held (rotation recovery).
        if (zchatPreferences.getPeerKnownOurRotationIndex(recipient) < 0) {
            zchatPreferences.setPeerKnownOurRotationIndex(recipient, zchatPreferences.getNostrRotationIndex())
        }
        val (convId, _) = zchatPreferences.getOrCreateConversationId(recipient)
        val wire = ZMSGProtocol.createV4InitMessage(convId, senderAddress, text)

        val localId = "nostr-out-${System.nanoTime()}"
        val nowMs = Instant.now().toEpochMilli()
        val sendingName = co.electriccoin.zcash.ui.screen.chat.model.MessageStatus.SENDING.name
        // ZC-421 (Z13): the row holds the second and the id of the rumor about to be published from its first write, so a
        // Retry after a restart (retryMessage) makes that same rumor again; no rumor publisher: no pair, today's path.
        val rumorSec = nowMs / 1000
        val rumorId =
            if (co.electriccoin.zcash.ui.nostr.NostrChatBridge
                    .canPublishRumorAt()
            ) {
                runCatching {
                    co.electriccoin.zcash.ui.nostr.NostrChatBridge
                        .rumorIdAt(wire, peerPub, rumorSec)
                }.getOrNull()
            } else {
                null
            }
        // Optimistic persisted row so the message is visible the instant ChatDetail opens. txId stays
        // null (no ledger entry), mirroring the in-chat NOSTR send persistence.
        val sendingRow =
            ZchatPreferences.PendingMessageData(
                id = localId,
                text = text,
                timestampMillis = nowMs,
                peerAddress = recipient,
                isOutgoing = true,
                isPending = true,
                status = sendingName,
                relayRumorAtSec = rumorSec.takeIf { rumorId != null },
                relayRumorId = rumorId,
            )
        zchatPreferences.addPendingMessage(sendingRow)
        // ZC-421: the rumor goes out once the checked write confirmed its pair (else nothing is published); the plain
        // publisher only when there is no rumor publisher (the pair is taken off the row first if it went away).
        var keepPair = false
        var rumorTried = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (rumorId != null) {
                    zchatPreferences.commitPendingMessage(sendingRow)
                    rumorTried = true
                    val remade =
                        co.electriccoin.zcash.ui.nostr.NostrChatBridge
                            .publishRumorAt(wire, peerPub, rumorSec, expectedRumorId = rumorId)
                    if (remade != null) {
                        keepPair =
                            co.electriccoin.zcash.ui.nostr.NostrChatBridge
                                .relayTookIt(remade)
                        return@runCatching remade
                    }
                    rumorTried = false
                    zchatPreferences.commitPendingMessage(sendingRow.copy(relayRumorAtSec = null, relayRumorId = null))
                }
                co.electriccoin.zcash.ui.nostr.NostrChatBridge
                    .publish(wire, peerPub)
            }.getOrNull()
        }
        // A rumor publish that failed may have reached a relay: its pair stays, so a Retry makes it again. (A checked write
        // that failed published nothing: no pair.)
        if (result == null && rumorTried) keepPair = true
        val acks = result?.acks ?: 0
        val finalStatus = if (acks > 0) {
            co.electriccoin.zcash.ui.screen.chat.model.MessageStatus.SENT
        } else {
            co.electriccoin.zcash.ui.screen.chat.model.MessageStatus.FAILED
        }
        // Re-key to the STABLE shared rumor id so a reaction/reply correlates across both devices
        // (matches publishNostrAndRenderLocal). Falls back to the local id for legacy/no-id publishes.
        val finalId = result?.messageId?.takeIf { it.isNotEmpty() }?.let { "nmsg-$it" } ?: localId
        if (finalId != localId) zchatPreferences.removePendingMessage(localId)
        zchatPreferences.addPendingMessage(
            ZchatPreferences.PendingMessageData(
                id = finalId,
                text = text,
                timestampMillis = nowMs,
                peerAddress = recipient,
                isOutgoing = true,
                isPending = false,
                status = finalStatus.name,
                // ZC-421: the pair stays only when a relay took the rumor (a copy may be out); otherwise a Retry is a new
                // message at the current time, as before.
                relayRumorAtSec = rumorSec.takeIf { keepPair && rumorId != null },
                relayRumorId = rumorId.takeIf { keepPair },
            )
        )
        // M1: hand the final row to every ChatViewModel already alive, as every other writer does. The chat list read the
        // store once, at its start, so the new chat did not show there until a reply came or the app restarted.
        co.electriccoin.zcash.ui.nostr.NostrChatBridge.emitLocalMessage(
            co.electriccoin.zcash.ui.screen.chat.model.ChatMessage(
                id = finalId,
                txId = null,
                text = text,
                timestamp = Instant.ofEpochMilli(nowMs),
                isOutgoing = true,
                peerAddress = recipient,
                isPending = false,
                status = finalStatus,
            )
        )
    }

    fun setScannedAddress(address: String) {
        recipientAddress = address
        selectedContact = contactBook.getContact(address)
        syncModeForRecipient()
        updateState()
    }

    private fun isValidZcashAddress(address: String): Boolean {
        // Unified address: starts with "u1" and length > 100
        // Sapling address: starts with "zs" and length > 70
        return when {
            address.startsWith("u1") && address.length > 100 -> true
            address.startsWith("zs") && address.length > 70 -> true
            else -> false
        }
    }

    /** Exact zatoshi as plain ZEC ("0.00427002 ZEC"): a priced Send All figure is never rounded. */
    private fun formatZatoshiExact(zatoshi: Long): String =
        if (zatoshi == 0L) {
            "0 ZEC"
        } else {
            "${java.math.BigDecimal.valueOf(zatoshi, ZEC_DECIMALS).stripTrailingZeros().toPlainString()} ZEC"
        }

    private fun formatZatoshi(zatoshi: Long): String {
        val zec = zatoshi / 100_000_000.0
        return if (zatoshi == 0L) {
            "0 ZEC"
        } else {
            String.format("%.5f ZEC", zec)
        }
    }
}
