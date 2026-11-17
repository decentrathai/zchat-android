package co.electriccoin.zcash.ui.common.tex

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.sendattempt.ChainView
import co.electriccoin.zcash.ui.common.sendattempt.OwnTransaction
import co.electriccoin.zcash.ui.common.sendattempt.SendAttempt
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptProof
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptResolver
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptVerdict
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** F3: the wallet as the TEX checker reads and moves it (the ZCHAT account only: a Keystone cannot send TEX). */
interface TexWallet {
    /** The wallet's fully scanned height, or null when it is not known. */
    suspend fun scannedTip(): Long?

    /** The account's transactions with their outputs. */
    suspend fun transactions(): List<TexTxFacts>

    /**
     * Asks the server over Tor for unspent outputs at [ephemeralAddress] and stores what it finds. True only when it
     * found some (SDK fetchUtxosByAddress); false for "none" AND for a failure, so false never proves anything.
     */
    suspend fun lookup(ephemeralAddress: String): Boolean

    /** Builds a shielding proposal of the account's transparent ZEC (threshold 20,000 zat); null when none is due. */
    suspend fun proposeShield(): TexShieldPlan?

    /** Creates, records ([onStored], F2's one submit path) and submits [plan]. */
    suspend fun submitShield(
        plan: TexShieldPlan,
        onStored: OnTransactionsStored,
    ): SubmitResult

    /** Hands an unmined, unexpired TEX leg to the server again. True when it was handed over. */
    suspend fun resubmit(leg: TexTxFacts): Boolean
}

/** F3: the Tor switch as the recovery uses it. Turning it on restarts the wallet; it then stays on. */
interface TexTor {
    suspend fun isOn(): Boolean

    /** Tor is on but could not start (the SDK's TOR_NOT_AVAILABLE): lookups cannot run. */
    suspend fun isFailing(): Boolean

    /** The stored choice is "off" (not merely never chosen). */
    suspend fun isExplicitlyOff(): Boolean

    /** Throws when Tor could not be turned on. */
    suspend fun turnOn()
}

/** F3: nothing else is in progress (see [TexIdleRule]). */
fun interface TexIdle {
    fun isIdle(): Boolean
}

/** F3: the notice after a recovery (a system notification, when the phone allows one). */
fun interface TexNotice {
    fun shielded(status: TexStatus)
}

/**
 * F3 (fix-all 2.13.0, train T3): the TEX checker and its fully automatic recovery (owner, 25 Sep).
 *
 * Stateless over the SDK database: every pass reads the account's transactions, pairs the TEX sends
 * ([pairTexSends]) and derives each one's state ([deriveTexSendState]) from the scanned tip read around the pass
 * ([readWithScannedTip]). Nobody is asked anything:
 *  - a leg that is unmined and unexpired is handed to the server once per process (a leg left AwaitingPlan by a kill
 *    between create and submit would otherwise never go out; correction 3: TEX legs only);
 *  - when leg 1 is mined and leg 2 expired, the ephemeral address is looked up. That needs Tor: if Tor is off it is
 *    turned on, but only while [idle] (turning it on restarts the wallet), with backoff when it fails;
 *  - when the lookup finds the ZEC, it is shielded back at once (while [idle]), only if the shield spends nothing but
 *    that ZEC ([matchTexShield]), under a durable record ([TexRecoveryStore]) written before the create and again
 *    through onStored before the submit. A second shield needs the first proven expired (two passes, 10 blocks)
 *    AND a fresh lookup that finds the ZEC still unspent; at most [TexRules.MAX_SHIELD_ATTEMPTS];
 *  - when the shield is mined, the notice is posted once, and Home and the transaction detail keep saying it until
 *    it is read.
 * A lookup that finds nothing proves nothing (the SDK answers false for a failure too): the send stays CHECKING,
 * with backoff, at most [TexRules.MAX_LOOKUPS_PER_PROCESS] lookups per process.
 *
 * Debug builds only ([FaultSeams]): TEX_FORCE_STUCK stops the once-per-process resubmit, so a leg kept
 * AwaitingPlan stays unsent and the send gets stuck; TOR_BOOTSTRAP_FAILS makes turning Tor on fail.
 *
 * F5 (fix-all 2.13.0, train T5): the same pass, from the same read, also judges every plain send of the ZCHAT account
 * (sent, not a shielding, not a TEX leg or unmatched candidate) with F4's rules ([judgePlainSends]) and publishes the
 * verdicts in [check] and in the process-wide [latestCheck] (the Wallet Send progress screen reads it). It never sends
 * anything for a plain send; "Send again" is the user's own new send, used at most once on a send proven never mined
 * ([markSendAgainUsed]).
 */
@Suppress("TooManyFunctions")
class TexSendMonitor(
    private val wallet: TexWallet,
    private val tor: TexTor,
    private val idle: TexIdle,
    private val store: TexRecoveryStore,
    private val notice: TexNotice,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableStatuses = MutableStateFlow<List<TexStatus>>(emptyList())

    /** The TEX sends of the last pass, with their state. */
    val statuses: StateFlow<List<TexStatus>> = mutableStatuses.asStateFlow()

    private val mutableCheck = MutableStateFlow<TexCheck?>(null)

    /**
     * T3-fixup (G-F3-1): what the last completed pass knew, or null before one completed. The transaction detail
     * decides "Send again" by it ([texSendAgainAllowed]); a pass that throws leaves the last one in place.
     */
    val check: StateFlow<TexCheck?> = mutableCheck.asStateFlow()

    private val passLock = Mutex()
    private val leg1Proof = TexNotSentProof()
    private val shieldProof = TexNotSentProof()
    private val lookupBackoff = mutableMapOf<String, Backoff>()
    private var torBackoff = Backoff()
    private var lookups = 0
    private val resubmitted = mutableSetOf<String>()

    /** Sends whose ZEC a lookup found in this process (shown as RETURNED while the next lookup waits). */
    private val foundOnce = mutableSetOf<String>()

    /** How often in this process a found send's shield came back empty (below the threshold). */
    private val belowThreshold = mutableMapOf<String, Int>()
    private val started = AtomicBoolean(false)

    /** F5: sends marked "Send again used" in this process (kept even when the store could not write the mark). */
    private val sendAgainUsedHere = mutableSetOf<String>()

    /**
     * Runs the passes for the life of the process (SyncForegroundService starts it). It does not follow the service's
     * own scope: a service that is destroyed and created again must not leave the checker stopped.
     */
    fun startForProcess() {
        start(CoroutineScope(Dispatchers.IO + SupervisorJob()))
        checkerRunning = true
    }

    /** Runs a pass every [TexRules.PASS_INTERVAL_MS] in [scope]; a second call does nothing. */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                try {
                    pass()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Twig.error(e) { "TEX check: the pass failed; the next one runs as usual" }
                }
                delay(TexRules.PASS_INTERVAL_MS)
            }
        }
    }

    /** One look at the wallet: derives every TEX send's state and does what is due. */
    suspend fun pass(): List<TexStatus> = passLock.withLock { runPass() }

    /** The user read the notice of the send named [key]; Home stops showing it. */
    suspend fun acknowledgeNotice(key: String) {
        passLock.withLock {
            val record = store.get(key) ?: return@withLock
            if (!record.noticeShown) store.put(record.copy(noticeShown = true))
            mutableStatuses.value =
                mutableStatuses.value.map { if (it.key == key) it.copy(noticeShown = true) else it }
            mutableCheck.value =
                mutableCheck.value?.let { check ->
                    check.copy(statuses = check.statuses.map { if (it.key == key) it.copy(noticeShown = true) else it })
                }
        }
    }

    /**
     * F5: the user tapped "Send again" on the plain send [txId], proven never mined. Its hash is written to the store
     * and hidden at once in [check] and [latestCheck]; a failed write is logged, and the mark still holds for this
     * process. Nothing is sent here: the user's own new send follows, with its own quote.
     *
     * ZC-400: answers true only for the first caller for that send (its hash not marked in this process, not in the
     * store and not in the published check), so of two quick taps only one pre-fills a Send form. The mark is
     * recorded the same way whatever the answer.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun markSendAgainUsed(txId: String): Boolean =
        passLock.withLock {
            val hash = sendAgainUsedHash(txId)
            val first = hash !in sendAgainUsedHashes() && mutableCheck.value?.sendAgainUsed?.contains(hash) != true
            sendAgainUsedHere += hash
            try {
                store.markSendAgainUsed(hash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.warn { "Send again: the used mark could not be written (${e.javaClass.simpleName}); it holds until a restart" }
            }
            mutableCheck.value?.let { publish(it.copy(sendAgainUsed = it.sendAgainUsed + hash)) }
            first
        }

    private fun publish(check: TexCheck) {
        mutableCheck.value = check
        mutableLatestCheck.value = check
    }

    private suspend fun runPass(): List<TexStatus> {
        val read = readWithScannedTip(wallet::scannedTip) { wallet.transactions() }
        val tip = read.tip
        val transactions = read.value
        val byTxId = transactions.associateBy { it.txId }
        val sends = findTexSends(transactions)
        val pairs = sends.pairs
        val torOn = tor.isOn()
        val torByZchat = store.torTurnedOnByZchat()
        val context =
            PassContext(
                tip = tip,
                byTxId = byTxId,
                torOn = torOn,
                torByZchat = torByZchat,
                torStartFailed = torOn && tor.isFailing(),
                // ZCHAT turned Tor on once and the user turned it off again: ZCHAT does not overrule that.
                torOffByUser = !torOn && torByZchat && tor.isExplicitlyOff(),
            )

        if (torOn) torBackoff = Backoff()
        val statuses = pairs.map { pair -> evaluate(pair, context) }.toMutableList()
        if (context.torNeeded && !torOn && !context.torOffByUser) turnTorOnIfIdle()
        if (context.found.isNotEmpty()) shieldFound(context, statuses)

        // Tor could not be started: still checking, with backoff, honestly said (never "send again").
        // T3b-2 (T3 F3 check 5): the count holds only failures since the last successful start, and Tor the user
        // turned off is said as off (with how to turn it back on), never as "keeps trying": ZCHAT does not try then.
        val torFailing = !torOn && torBackoff.failures > 0
        val result =
            statuses.map {
                if (torFailing && it.state == TexState.NEEDS_TOR && !it.torOffByUser) {
                    it.copy(state = TexState.CHECKING, torFailing = true)
                } else {
                    it
                }
            }
        // T3-fixup (G-F3-2): a send whose legs cannot be told apart is said, and nothing is done for it.
        val all = result + sends.unmatched.map { unmatchedStatus(it, torByZchat) }
        if (sends.unmatched.isNotEmpty()) {
            Twig.warn { "TEX check: ${sends.unmatched.size} transaction(s) may be legs of a send that cannot be matched" }
        }
        mutableStatuses.value = all
        val plain = judgePlainSends(transactions, all, tip)
        publish(
            TexCheck(
                statuses = all,
                seenTxIds = transactions.mapTo(HashSet()) { it.txId },
                minedSends = plain.mined,
                plainSends = plain.verdicts,
                sendAgainUsed = sendAgainUsedHashes(),
            ),
        )
        return all
    }

    private class PlainSends(
        val mined: Set<String>,
        val verdicts: Map<String, PlainSendVerdict>,
    )

    /**
     * F5: every plain send of this read (sent, not a shielding, in no TEX status: neither a paired leg nor an
     * unmatched candidate), judged by F4's rules from the same read and scanned tip. Mined: [PlainSends.mined]. Unmined:
     * SendAttemptResolver on its own id; NOT_SENT counts only when SendAttemptProof saw it on an earlier pass at a
     * strictly lower tip, and reads as waiting until then. No tip: waiting with nothing known ("checking").
     */
    private fun judgePlainSends(
        transactions: List<TexTxFacts>,
        statuses: List<TexStatus>,
        tip: Long?,
    ): PlainSends {
        val texIds = statuses.flatMapTo(HashSet()) { listOf(it.leg1TxId, it.leg2TxId) }
        val mined = HashSet<String>()
        val verdicts = HashMap<String, PlainSendVerdict>()
        for (tx in transactions) {
            if (!tx.isSent || tx.isShielding || tx.txId in texIds) continue
            val key = SendAttemptProof.key(PLAIN_SEND_KEY_PREFIX + tx.txId, null)
            if (tx.minedHeight != null) {
                mined += tx.txId
                SendAttemptProof.clear(key)
                continue
            }
            val verdict =
                SendAttemptResolver.resolve(
                    SendAttempt(
                        peerAddress = "",
                        text = "",
                        amountZatoshi = 0L,
                        rowTimeMillis = 0L,
                        markerMillis = null,
                        attemptHeight = null,
                        txIds = listOf(tx.txId),
                        expiries = emptyMap(),
                        legacy = false,
                        anchorHeight = null,
                        otherProcessSameAccount = false,
                    ),
                    ChainView(
                        scannedTip = tip,
                        transactions =
                            listOf(
                                OwnTransaction(
                                    txId = tx.txId,
                                    isSent = true,
                                    minedHeight = null,
                                    expiryHeight = tx.expiryHeight,
                                    valueZatoshi = 0L,
                                    recipients = null,
                                    readableText = null,
                                    blockTimeMillis = null,
                                ),
                            ),
                    ),
                )
            val proven = SendAttemptProof.observe(key, verdict, tip)
            verdicts[tx.txId] =
                when {
                    verdict == SendAttemptVerdict.NotSent && proven -> PlainSendVerdict.NotSent
                    verdict is SendAttemptVerdict.Waiting -> PlainSendVerdict.Waiting(verdict.expiryHeight, verdict.tip)
                    // Not proven yet (a first pass past the margin) reads as waiting for its own expiry.
                    verdict == SendAttemptVerdict.NotSent -> PlainSendVerdict.Waiting(tx.expiryHeight?.takeIf { it >= 1 }, tip)
                    // Unknown (no scanned tip): nothing is known yet.
                    else -> PlainSendVerdict.Waiting(null, null)
                }
        }
        return PlainSends(mined, verdicts)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun sendAgainUsedHashes(): Set<String> {
        val stored =
            try {
                store.sendAgainUsedHashes()
            } catch (e: Exception) {
                Twig.warn { "Send again: the used marks could not be read (${e.javaClass.simpleName})" }
                emptySet()
            }
        return stored + sendAgainUsedHere
    }

    private fun unmatchedStatus(
        tx: TexTxFacts,
        torByZchat: Boolean,
    ) = TexStatus(
        key = texRecordKey("unmatched:${tx.txId}"),
        leg1TxId = tx.txId,
        leg2TxId = tx.txId,
        state = TexState.NEEDS_SUPPORT,
        torTurnedOnByZchat = torByZchat,
        unmatched = true,
    )

    private class PassContext(
        val tip: Long?,
        val byTxId: Map<String, TexTxFacts>,
        val torOn: Boolean,
        val torByZchat: Boolean,
        val torStartFailed: Boolean,
        val torOffByUser: Boolean,
    ) {
        var torNeeded = false

        /** The sends whose ZEC a lookup found unspent in THIS pass (the only proof a shield may be built on). */
        val found = mutableListOf<TexPair>()
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    private suspend fun evaluate(
        pair: TexPair,
        context: PassContext,
    ): TexStatus {
        val key = pair.key
        val tip = context.tip
        val leg1Mined = pair.leg1.minedHeight != null
        val leg2Mined = pair.leg2.minedHeight != null
        resubmitOnce(pair, tip)

        val leg1Proven =
            if (!leg1Mined && !leg2Mined) {
                leg1Proof.observe(key, tip, pair.expiryHeight)
            } else {
                leg1Proof.forget(key)
                false
            }
        val sendState =
            deriveTexSendState(
                TexStateInput(
                    leg1Mined = leg1Mined,
                    leg2Mined = leg2Mined,
                    expiryHeight = pair.expiryHeight,
                    scannedTip = tip,
                    leg1NotSentProven = leg1Proven,
                ),
            )
        val record = store.get(key)

        fun status(
            state: TexState,
            recordNow: TexRecoveryRecord? = record,
        ) = TexStatus(
            key = key,
            leg1TxId = pair.leg1.txId,
            leg2TxId = pair.leg2.txId,
            state = state,
            foundZat = recordNow?.foundZat,
            feeZat = recordNow?.feeZat,
            torTurnedOnByZchat = context.torByZchat,
            noticeShown = recordNow?.noticeShown ?: false,
        )
        if (sendState != TexState.CHECKING) return status(sendState)

        // Recovery: leg 1 is mined and leg 2 can no longer be.
        if (record != null && record.shieldStarted) {
            val shieldMined = record.shieldTxIds.any { context.byTxId[it]?.minedHeight != null }
            if (shieldMined) {
                val shielded = status(TexState.SHIELDED)
                postNoticeOnce(record, shielded)
                return shielded
            }
            val bound = checkNotNull(record.shieldBound)
            val expired = shieldProof.observe("$key#${record.attempt}", tip, bound)
            if (!expired) return status(TexState.SHIELDING)
            if (record.attempt >= TexRules.MAX_SHIELD_ATTEMPTS) return status(TexState.NEEDS_SUPPORT)
            Twig.warn { "TEX check ${key.take(KEY_LOG)}: shield attempt ${record.attempt} is proven expired" }
        }
        if (record != null && !record.shieldStarted && (record.attempt >= TexRules.MAX_SHIELD_ATTEMPTS || record.needsSupport)) {
            return status(TexState.NEEDS_SUPPORT)
        }

        if (!context.torOn) {
            context.torNeeded = true
            return status(TexState.NEEDS_TOR).copy(torOffByUser = context.torOffByUser)
        }
        if (context.torStartFailed) return status(TexState.CHECKING).copy(torFailing = true)

        if (!lookupAllowed(key)) return status(if (key in foundOnce) TexState.RETURNED else TexState.CHECKING)
        lookups++
        val found =
            try {
                wallet.lookup(pair.ephemeralAddress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.warn { "TEX check ${key.take(KEY_LOG)}: the lookup failed (${e.javaClass.simpleName})" }
                false
            }
        // Found or not, the next lookup of this address waits (a found send whose shield must wait for an idle
        // moment is looked up again later, never every pass).
        lookupBackoff[key] = lookupBackoff[key].orEmpty().next(clock())
        if (!found) return status(if (key in foundOnce) TexState.RETURNED else TexState.CHECKING)
        foundOnce += key
        context.found += pair
        return status(TexState.RETURNED)
    }

    /** Correction 3: a TEX leg that is unmined and unexpired goes to the server once per process. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun resubmitOnce(
        pair: TexPair,
        tip: Long?,
    ) {
        if (tip == null) return
        if (FaultSeams.isArmed(FaultSeam.TEX_FORCE_STUCK)) {
            Twig.warn { "Debug fault seam TEX_FORCE_STUCK: TEX legs are not handed to the server again" }
            return
        }
        val legs =
            when {
                pair.leg2.minedHeight != null -> emptyList()
                pair.leg1.minedHeight != null -> listOf(pair.leg2)
                else -> listOf(pair.leg1, pair.leg2)
            }
        for (leg in legs) {
            val expiry = leg.expiryHeight ?: continue
            if (tip >= expiry || !resubmitted.add(leg.txId)) continue
            try {
                wallet.resubmit(leg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.warn { "TEX check: handing a leg to the server again failed (${e.javaClass.simpleName})" }
            }
        }
    }

    private fun lookupAllowed(key: String): Boolean {
        if (lookups >= TexRules.MAX_LOOKUPS_PER_PROCESS) return false
        val backoff = lookupBackoff[key] ?: return true
        return clock() >= backoff.nextAt
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun turnTorOnIfIdle() {
        if (!idle.isIdle()) {
            Twig.info { "TEX check: Tor is needed; waiting until nothing else is in progress" }
            return
        }
        if (clock() < torBackoff.nextAt) return
        if (FaultSeams.isArmed(FaultSeam.TOR_BOOTSTRAP_FAILS)) {
            Twig.warn { "Debug fault seam TOR_BOOTSTRAP_FAILS: turning Tor on fails" }
            torBackoff = torBackoff.next(clock())
            return
        }
        try {
            tor.turnOn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Twig.warn { "TEX check: Tor could not be turned on (${e.javaClass.simpleName})" }
            torBackoff = torBackoff.next(clock())
            return
        }
        // T3b-2: a successful start clears the old failures (the pass that turned Tor on read it off, and the user may
        // turn it off again before any pass reads it on).
        torBackoff = Backoff()
        try {
            store.markTorTurnedOnByZchat()
        } catch (e: Exception) {
            Twig.warn { "TEX check: the Tor mark could not be written (${e.javaClass.simpleName})" }
        }
        Twig.info { "TEX check: Tor is on; the wallet restarts with it" }
    }

    /** Builds, checks, records and submits ONE shield of ZEC found in this pass. */
    @Suppress("TooGenericExceptionCaught", "ReturnCount", "LongMethod")
    private suspend fun shieldFound(
        context: PassContext,
        statuses: MutableList<TexStatus>,
    ) {
        fun update(
            pair: TexPair,
            change: (TexStatus) -> TexStatus
        ) {
            val index = statuses.indexOfFirst { it.key == pair.key }
            if (index >= 0) statuses[index] = change(statuses[index])
        }
        val tip = context.tip ?: return
        if (!idle.isIdle()) {
            Twig.info { "TEX check: ZEC found; the shield waits until nothing else is in progress" }
            return
        }
        val plan =
            try {
                wallet.proposeShield()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.warn { "TEX check: the shield could not be proposed (${e.javaClass.simpleName})" }
                return
            }
        if (plan == null) {
            // The lookup found ZEC, but nothing reaches the 20,000 zat threshold: too little to move after the fee.
            // Said after two such passes (a single empty answer may be the wallet catching up), and kept on record.
            context.found.forEach { pair ->
                val count = (belowThreshold[pair.key] ?: 0) + 1
                belowThreshold[pair.key] = count
                if (count >= BELOW_THRESHOLD_PASSES) {
                    markNeedsSupport(pair.key)
                    update(pair) { it.copy(state = TexState.NEEDS_SUPPORT) }
                }
            }
            return
        }
        val matched = matchTexShield(plan.shape, context.found.map { it.leg1.rawIdHex })
        val pair = context.found.firstOrNull { it.leg1.rawIdHex == matched }
        if (pair == null) {
            Twig.warn { "TEX check: the shield would spend other ZEC than what was found; nothing is built" }
            context.found.forEach { found -> update(found) { it.copy(waitingForOtherFunds = true) } }
            return
        }
        val before = store.get(pair.key)
        if (before != null && before.shieldStarted) {
            // Only reached after evaluate proved the earlier shield expired; the check is repeated here on purpose.
            val bound = checkNotNull(before.shieldBound)
            if (tip < bound + TexRules.reorgMargin(bound)) return
        }
        val attempt = (before?.attempt ?: 0) + 1
        if (attempt > TexRules.MAX_SHIELD_ATTEMPTS) {
            update(pair) { it.copy(state = TexState.NEEDS_SUPPORT) }
            return
        }
        val intent =
            TexRecoveryRecord(
                key = pair.key,
                attempt = attempt,
                createStartTip = tip,
                foundZat = plan.shape.inputTotal,
                feeZat = plan.shape.fee,
            )
        try {
            store.put(intent)
        } catch (e: Exception) {
            Twig.error(e) { "TEX check: the shield's record could not be written; nothing is built" }
            return
        }
        Twig.info { "TEX check ${pair.key.take(KEY_LOG)}: shield attempt $attempt of ${plan.shape.inputTotal} zat" }
        val result =
            try {
                wallet.submitShield(plan) { stored ->
                    store.put(
                        intent.copy(
                            shieldTxIds = stored.map { it.txId },
                            shieldExpiry = stored.mapNotNull { it.expiryHeight }.maxOrNull(),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Twig.error(e) { "TEX check: the shield's submit threw; its record stands" }
                null
            }
        Twig.info { "TEX check ${pair.key.take(KEY_LOG)}: shield result ${result?.javaClass?.simpleName}" }
        update(pair) {
            it.copy(state = TexState.SHIELDING, foundZat = intent.foundZat, feeZat = intent.feeZat)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun markNeedsSupport(key: String) {
        val record = store.get(key) ?: TexRecoveryRecord(key = key)
        if (record.shieldStarted) return
        try {
            store.put(record.copy(needsSupport = true))
        } catch (e: Exception) {
            Twig.warn { "TEX check: the support mark could not be written (${e.javaClass.simpleName})" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun postNoticeOnce(
        record: TexRecoveryRecord,
        status: TexStatus,
    ) {
        if (record.notified) return
        try {
            store.put(record.copy(notified = true))
            notice.shielded(status)
        } catch (e: Exception) {
            Twig.warn { "TEX check: the notice could not be posted (${e.javaClass.simpleName})" }
        }
    }

    /** A doubling wait, from [TexRules.FIRST_BACKOFF_MS] up to [TexRules.MAX_BACKOFF_MS]. */
    private data class Backoff(
        val failures: Int = 0,
        val nextAt: Long = 0,
    ) {
        fun next(now: Long): Backoff {
            val failed = failures + 1
            val wait = (TexRules.FIRST_BACKOFF_MS shl (failed - 1).coerceAtMost(MAX_SHIFT)).coerceAtMost(TexRules.MAX_BACKOFF_MS)
            return Backoff(failures = failed, nextAt = now + wait)
        }
    }

    private fun Backoff?.orEmpty(): Backoff = this ?: Backoff()

    companion object {
        private const val KEY_LOG = 8
        private const val MAX_SHIFT = 10
        private const val BELOW_THRESHOLD_PASSES = 2

        /** F5: the SendAttemptProof key of a plain Wallet send is "wallet:<txid>". */
        private const val PLAIN_SEND_KEY_PREFIX = "wallet:"

        private val mutableLatestCheck = MutableStateFlow<TexCheck?>(null)

        /**
         * F5: the check of the last completed pass of this process's checker (one per process, a Koin single), or null
         * before one completed. Process-wide like F4's SendAttemptProof, so the Wallet Send progress screen reads it
         * without a new constructor parameter.
         */
        val latestCheck: StateFlow<TexCheck?> = mutableLatestCheck.asStateFlow()

        /**
         * ZC-417: true once this process's checker runs its passes ([startForProcess], which the app calls at start;
         * no test calls it). While it is true the Wallet Send progress screen waits a little for the first pass that
         * judges a new send, instead of showing the submit's old error first. Nothing else sets it.
         */
        @Volatile
        var checkerRunning: Boolean = false
            private set
    }
}

/** The status of the TEX send [txId] belongs to (as leg 1 or leg 2), or null. */
fun List<TexStatus>.forTransaction(txId: String): TexStatus? = firstOrNull { it.leg1TxId == txId || it.leg2TxId == txId }
