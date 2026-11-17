package co.electriccoin.zcash.ui.screen.ai

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.screen.chat.datasource.OwnChatIdentityStore
import co.electriccoin.zcash.ui.screen.deletewallet.AI_CHECK_LIMIT_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/** AI-KEY-T (spec 5.4): the one line on step 1 of the Destroy PIN set-up. */
enum class AiDestroyPinLine {
    UNREADABLE,
    CANNOT,
    NOT_YET,
    SAFE,
    NONE,
}

/** AI-KEY-T (spec 5.3): which of the five warnings a Reset shows. */
enum class AiResetLine {
    UNREADABLE,
    KEPT,
    KEPT_PARKED,
    LINKED,
    PLAIN,
}

/**
 * AI-KEY-S (owner W-52, W-53): the one place that decides about the AI access key. One object for the process, one
 * request at a time.
 *
 * - No saved key: the wallet signs in with its sign-in key ([AiSignInKey]) and gets the key of its own account.
 * - A saved key without a verdict: key/state is asked once. The key is this wallet's (linked), or it is linked now, or
 *   it is another wallet's: then it is dropped when its account holds no paid credit and parked (kept, never used
 *   again) otherwise. A chat or a picture waits for that answer ([keyForCharge]).
 * - A refused key is replaced only after the new one arrived, and at most once a minute.
 *
 * One comparison per account id, and it is written down before the proof leaves. A failed registration after a match
 * keeps the matched candidate and tries that one alone again: a later digest is checked against it and nothing else,
 * so a server cannot test another guess (ZC-1178). The fingerprint itself never leaves the phone: the proof signs it.
 *
 * THE FENCE: every operation notes the owner tag and the wallet-scope counter at its start and only starts with a
 * saved wallet. Everything that follows a network answer checks all three again, and every write goes through
 * [AiPreferences.writeIfStillThisWallet], which reads the tag and the counter under the wallet-scope lock.
 *
 * Round 3: every AI request of the tab and the /ai shortcut goes through [request]: its [Ticket] is looked at before each
 * send and before its answer counts, so a refusal of wallet A's request is never resent on B's key.
 *
 * AI-KEY-P: [mayOverwrite] is the ONE rule for replacing the saved pair (spec 4.5). A claim and a ticket registration are
 * sent only by [lookForOldCredit] and [tryToLink], each on the user's tap. A parked key is used for nothing else.
 *
 * No key, signature, one-time number, access key or account id is logged; this class writes no log at all.
 */
class AiAccountAccess(
    private val prefs: AiPreferences,
    private val seed: suspend () -> ByteArray?,
    private val candidates: suspend () -> List<suspend () -> String>,
    private val ownerTag: () -> String?,
    private val scopeCounter: () -> Long,
    private val walletSaved: suspend () -> Boolean,
    private val clock: () -> Long,
    private val send: suspend (String, String, String?, String?) -> Pair<Int, String>,
) {
    private val oneAtATime = Mutex()

    /** This wallet's key id, per owner tag, in memory only. */
    private var keyIdOfTag: Pair<String?, String>? = null

    /** When this process last got an answer to a sign-in after a refusal. */
    private var lastRefusalMillis: Long? = null

    /** The AI tab and the /ai shortcut open (spec 4.3, 4.4, 4.5). */
    suspend fun open(): Pair<String?, String?> =
        operation { fence ->
            val decided = settled(fence)
            if (decided.key == null) return@operation null to decided.sentence
            decided.key to (decided.sentence.takeIf { decided.chargeable } ?: owedNotice() ?: standingNotice())
        }

    /** The key for a chat or a picture: nothing is handed out before key/state answered (spec 4.2, M9). */
    suspend fun keyForCharge(): Pair<String?, String?> =
        operation { fence ->
            val decided = settled(fence)
            (decided.key.takeIf { decided.chargeable }) to decided.sentence
        }

    /**
     * ZC-1205: a sentence reached the user. An owed notice saying the same no longer is owed, and this is the only thing
     * that clears one: a path that shows nothing (the foreground, the /ai shortcut) never calls this, so its notice waits
     * in ai_link_state for the next open.
     */
    suspend fun noticeShown(sentence: String) {
        operation { fence ->
            val link = Link.parse(prefs.getLinkStateJson())
            if (link?.owed != null && owedText(link) == sentence) write(fence) { putLink(link.copy(owed = null)) }
            null to null
        }
    }

    /** The fence as it stands now: noted before a request, it binds the answer to that request ([Ticket]). No network. */
    suspend fun scopeNow(): Fence = withContext(Dispatchers.IO) { Fence(ownerTag(), scopeCounter()) }

    /** ZC-1238: [scopeNow] for a tap: the same two reads as [stillThisWallet], and not suspending. */
    fun fenceHere(): Fence = Fence(ownerTag(), scopeCounter())

    /** True while the wallet and the saved key are still the ones [ticket]'s request went out for. No network. */
    suspend fun stillCurrent(ticket: Ticket): Boolean =
        withContext(Dispatchers.IO) { holds(ticket.fence) && prefs.getToken() == ticket.key }

    /** True while the wallet is still the one [fence] was noted for. Cheap and not suspending: for a stream's every chunk. */
    fun stillThisWallet(fence: Fence): Boolean = fence.isCurrent()

    /**
     * Round 3: ONE AI request. [first] is looked at right before the send. A refusal goes to [refuse] only while the wallet
     * is the same, and is repeated once with the key it gives, looked at again right before that send. Null: the wallet
     * changed, nothing may be written, and the caller only ends what it started. [told] hears what [refuse] answered.
     */
    suspend fun <R> request(
        first: Ticket,
        isRefused: (R) -> Boolean,
        refuse: suspend (Ticket) -> Pair<String?, String?>?,
        told: suspend (String?, String?) -> Unit,
        call: suspend (String) -> R,
    ): Answered<R>? {
        if (!stillCurrent(first)) return null
        var used = first
        var result = call(first.key)
        if (isRefused(result)) {
            if (!sameWallet(first)) return null
            val (fresh, sentence) = refuse(first) ?: return null
            if (!sameWallet(first)) return null
            told(fresh, sentence)
            if (fresh != null && fresh != first.key) {
                used = first.forKey(fresh)
                if (!stillCurrent(used)) return null
                result = call(fresh)
            }
        }
        if (!sameWallet(used)) return null
        return Answered(result, used, stillCurrent(used))
    }

    private suspend fun sameWallet(ticket: Ticket): Boolean = withContext(Dispatchers.IO) { holds(ticket.fence) }

    /** The key for every other request. No network. */
    suspend fun keyForUse(): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            if (walletSaved()) keyNotOwingASignIn() to null else null to null
        }

    /** The saved key, unless a sign-in is owed for its account (ZC-1207): that key goes out on no call meanwhile. */
    private fun keyNotOwingASignIn(): String? =
        prefs.getToken()?.takeUnless { linkOf(prefs.getUserId().orEmpty())?.signInOwed == true }

    /**
     * ZChat came to the foreground (spec 4.3): one call a day while the saved key has no verdict, and none otherwise.
     * Parked keys are not looked at. Only the foreground observer of the application calls this.
     */
    suspend fun onForeground(): Pair<String?, String?> =
        operation { fence ->
            val token = prefs.getToken() ?: return@operation null to null
            val id = prefs.getUserId().orEmpty()
            val link = linkOf(id) ?: Link(id)
            if (link.signInOwed) return@operation linkGone(fence, token, link).let { it.key to it.sentence }
            val today = clock() / DAY_MILLIS
            if (link.verdict != null || link.day == today) return@operation token to null
            if (!write(fence) { putLink(link.copy(day = today)) }) return@operation STOPPED
            val decided = decide(fence, token, tap = false)
            decided.key to decided.sentence
        }

    /** What /ai/balance said (spec 4.3, change 5): only call this when the answer carried the field. */
    suspend fun onBalance(
        signInKeyId: String?,
        paid: Boolean?,
    ): Pair<String?, String?> = operation { fence -> balanceSaid(fence, signInKeyId, paid) }

    /** The same for the request of [source]: an answer for another key or across a wallet change says nothing (GPT 3). */
    suspend fun onBalance(
        source: Ticket,
        signInKeyId: String?,
        paid: Boolean?,
    ): Pair<String?, String?> =
        operation { fence ->
            if (source.fence.isCurrent() && prefs.getToken() == source.key) balanceSaid(fence, signInKeyId, paid) else null to null
        }

    private suspend fun balanceSaid(
        fence: Fence,
        signInKeyId: String?,
        paid: Boolean?,
    ): Pair<String?, String?> {
        val token = prefs.getToken() ?: return null to null
        val id = prefs.getUserId().orEmpty()
        val link = linkOf(id) ?: Link(id)
        val paidNow = if (link.paid == PAID_YES) PAID_YES else paid?.let { if (it) PAID_YES else PAID_NO } ?: link.paid
        val own = ownKeyId() ?: return token to null
        return when {
            signInKeyId == own -> {
                val now =
                    link.copy(
                        verdict = LINKED,
                        paid = paidNow,
                        compared = false,
                        matched = null,
                        twoPaid = false,
                        signInOwed = false,
                    )
                if (now != link && !write(fence) { putLink(now) }) return STOPPED
                token to null
            }
            // Round 3: the account names another wallet's key: it is not ours, as on key/state, and is parked unless unpaid.
            link.verdict == LINKED && signInKeyId != null -> notThisWallet(fence, token, id, paidNow).let { it.key to it.sentence }
            link.verdict == LINKED -> linkGone(fence, token, link.copy(paid = paidNow)).let { it.key to it.sentence }
            else -> {
                if (paidNow != link.paid && !write(fence) { putLink(link.copy(paid = paidNow)) }) return STOPPED
                token to null
            }
        }
    }

    /** The server answered 401 to [accessKey] (spec 4.5). The caller repeats its call once with a new key. */
    suspend fun onRefused(accessKey: String): Pair<String?, String?> = onRefused(accessKey, scopeNow())

    /**
     * The same for a request sent under [asked]: across a change of wallet the refusal says nothing and no key is answered.
     * In the same wallet, a key another path has replaced meanwhile is answered for the one repeat.
     */
    suspend fun onRefused(
        accessKey: String,
        asked: Fence,
    ): Pair<String?, String?> =
        operation { fence ->
            if (!asked.isCurrent()) return@operation STOPPED
            val token = prefs.getToken() ?: return@operation null to null
            if (token != accessKey) return@operation keyNotOwingASignIn() to null
            val decided = refused(fence)
            decided.key to decided.sentence
        }

    /** The tap on "Link again" (change 9): one comparison more, for an account that lost its link. Changes nothing else. */
    suspend fun linkAgain(): Pair<String?, String?> =
        operation { fence ->
            val token = prefs.getToken() ?: return@operation null to null
            if (linkOf(prefs.getUserId().orEmpty())?.verdict != OWN) return@operation token to null
            val decided = decide(fence, token, tap = true)
            val linked = linkOf(prefs.getUserId().orEmpty())?.verdict == LINKED
            decided.key to (decided.sentence ?: text(if (linked) LINK_AGAIN_DONE else LINK_AGAIN_NOT_DONE))
        }

    /** The key to ask for a top-up address (spec 4.5): only a linked account gets one. No network. */
    suspend fun keyForTopup(): Pair<String?, String?> =
        operation {
            val token = prefs.getToken()
            if (token == null || linkOf(prefs.getUserId().orEmpty())?.verdict != LINKED) {
                return@operation null to text(TOPUP_NOT_LINKED)
            }
            token to null
        }

    /** The server handed out an address (M1): the stored paid is yes at once, and stays yes. */
    suspend fun onTopupAddress(): Pair<String?, String?> =
        operation { fence ->
            // No request's fence to compare: its own was noted just now and cannot see a record that went before that.
            if (fence.tag == null) STOPPED else markPaid(fence)
        }

    /**
     * The same for the request of [asked] (items 15, 21): the address belongs to the account that asked. After a wipe, a
     * change of wallet or a change of the pair it is not shown and nothing is marked. ZC-1317: no owner tag yet is no
     * change of wallet: any save of a wallet writes a tag first, so [asked] sees it, and a wipe raises the counter.
     */
    suspend fun onTopupAddress(asked: Ticket): Pair<String?, String?> =
        operation { fence ->
            if (asked.fence.isCurrent() && prefs.getToken() == asked.key) markPaid(fence) else STOPPED
        }

    private suspend fun markPaid(fence: Fence): Pair<String?, String?> {
        val token = prefs.getToken() ?: return STOPPED
        val id = prefs.getUserId().orEmpty()
        val link = linkOf(id) ?: Link(id)
        return if (write(fence) { putLink(link.copy(paid = PAID_YES)) }) token to null else STOPPED
    }

    /**
     * A top-up address refused with a 401 on [asked]'s key. Only when the pair is still the one this request used does the
     * refusal replace it; the caller repeats with the key answered, on a ticket of the same fence. A pair changed outside
     * this request answers no key: the address is not asked for on an account nobody tapped for.
     */
    suspend fun onTopupRefused(asked: Ticket): Pair<String?, String?> =
        operation { fence ->
            if (!asked.fence.isCurrent()) return@operation STOPPED
            if (prefs.getToken() != asked.key) return@operation null to null
            refused(fence).let { it.key to it.sentence }
        }

    /** The tap on "Look for AI credit from before 2.13" (spec 3.5, 4.5): one claim for the one fingerprint given. */
    suspend fun lookForOldCredit(fingerprint: String): Pair<String?, String?> = operation { fence -> claimFor(fence, fingerprint) }

    /** The same, for a fingerprint read since [asked] was noted: read across a change of wallet it is never sent (item 16). */
    suspend fun lookForOldCredit(
        fingerprint: String,
        asked: Fence,
    ): Pair<String?, String?> = operation { fence -> if (asked.isCurrent()) claimFor(fence, fingerprint) else STOPPED }

    private suspend fun claimFor(
        fence: Fence,
        fingerprint: String,
    ): Pair<String?, String?> {
        val wallet = fingerprint.lowercase()
        val own = ownKeyId()
        return if (own == null || !FINGERPRINT_FORM.matches(wallet)) {
            prefs.getToken() to text(NOT_READY)
        } else {
            claim(fence, own, wallet)
        }
    }

    /** The tap on "Try to link" for the parked key of [accountId] (spec 3.6, 4.5). */
    suspend fun tryToLink(accountId: String): Pair<String?, String?> =
        operation { fence ->
            val parked = prefs.parkedToken(accountId) ?: return@operation prefs.getToken() to null
            val own = ownKeyId() ?: return@operation prefs.getToken() to text(NOT_READY)
            linkParked(fence, own, parked, accountId)
        }

    /** The account ids of the parked keys, for one button each. No key leaves this class. */
    suspend fun parkedAccounts(): List<String> = withContext(Dispatchers.IO) { prefs.parkedUserIds() }

    /**
     * ZC-1180: the code the user sends for the operator's check: the first 20 hex of the sign-in key id. Read on a tap's
     * answer, kept in no store, no network.
     */
    suspend fun shortKeyCode(): String? =
        withContext(Dispatchers.IO) {
            oneAtATime.withLock { if (walletSaved()) ownKeyId()?.take(SHORT_KEY_CHARS) else null }
        }

    /**
     * Spec 5.4: the first row that fits, from the store alone. No call, no file made, and it does not throw. A store that
     * does not answer in [AI_CHECK_LIMIT_MS] is [AiDestroyPinLine.UNREADABLE]: the buttons' busy mark waits for this too.
     */
    suspend fun destroyPinLine(): AiDestroyPinLine =
        withinTheLimit(AI_CHECK_LIMIT_MS, AiDestroyPinLine.UNREADABLE) {
            readingTheStore(AiDestroyPinLine.UNREADABLE) {
                val token = if (prefs.isThere()) prefs.getToken() else null
                val link = token?.let { linkOf(prefs.getUserId().orEmpty()) }
                when {
                    !prefs.isThere() -> AiDestroyPinLine.NONE
                    prefs.hasParkedKeys() || link?.verdict == OWN -> AiDestroyPinLine.CANNOT
                    token == null -> AiDestroyPinLine.NONE
                    link?.verdict == LINKED -> AiDestroyPinLine.SAFE
                    link?.paid == PAID_NO -> AiDestroyPinLine.NONE
                    else -> AiDestroyPinLine.NOT_YET
                }
            }
        }

    /**
     * Spec 5.3: the first warning that fits, from the store alone; what it calls kept is what the Reset keeps. A store that
     * does not answer in [AI_CHECK_LIMIT_MS] is [AiResetLine.UNREADABLE].
     */
    suspend fun resetLine(): AiResetLine = lineWithin(AI_CHECK_LIMIT_MS)

    private suspend fun lineWithin(limitMillis: Long): AiResetLine =
        withinTheLimit(limitMillis, AiResetLine.UNREADABLE) {
            readingTheStore(AiResetLine.UNREADABLE) {
                val kept = prefs.entriesKeptAtReset()
                when {
                    AiPreferences.ACCESS_KEY_ENTRIES.first() in kept -> AiResetLine.KEPT
                    kept.isNotEmpty() -> AiResetLine.KEPT_PARKED
                    prefs.isThere() && prefs.getToken() != null && savedVerdict() == LINKED -> AiResetLine.LINKED
                    else -> AiResetLine.PLAIN
                }
            }
        }

    /**
     * What a Reset screen does when it opens, all of it inside one [AI_CHECK_LIMIT_MS]: [show] the stored line, the one
     * re-check, [show] the line after it. A first read that used the time up stands. The last read has time set aside, so
     * the screen knows the stored state when the limit falls.
     */
    suspend fun resetScreenCheck(show: (AiResetLine) -> Unit) {
        val started = TimeSource.Monotonic.markNow()
        show(resetLine())
        val left = AI_CHECK_LIMIT_MS - started.elapsedNow().inWholeMilliseconds
        if (left <= LAST_READ_MILLIS) return
        recheckWithin(left - LAST_READ_MILLIS)
        show(lineWithin(LAST_READ_MILLIS))
    }

    /**
     * The Reset screen opened (spec 4.3, 5.3): no verdict, the linking try; linked, one balance call; anything else, no
     * call. A parked key is never used, and with no saved key no account is opened. At most [AI_CHECK_LIMIT_MS]: at the
     * limit a running call is cancelled and an answer that still comes is dropped, even when the server linked the key:
     * the key stays kept and the next event asks again.
     */
    suspend fun recheckForResetScreen() = recheckWithin(AI_CHECK_LIMIT_MS)

    private suspend fun recheckWithin(limitMillis: Long) =
        withinTheLimit(limitMillis, Unit) {
            val verdict =
                readingTheStore(NO_SAVED_KEY) {
                    if (!prefs.isThere() || prefs.getToken() == null) NO_SAVED_KEY else savedVerdict().orEmpty()
                }
            when (verdict) {
                LINKED -> checkOnce()
                "" -> linkOnce()
            }
        }

    /**
     * The tap on "Link now" (spec 5.4): spec 4.4 for a key with no verdict; with a sign-in owed the sign-in comes first and
     * the saved key is not sent. True when the account is linked afterwards. At most [AI_CHECK_LIMIT_MS]; then false.
     */
    suspend fun linkNow(): Boolean = withinTheLimit(AI_CHECK_LIMIT_MS, false) { linkOnce() }

    private suspend fun linkOnce(): Boolean =
        readingTheStore(false) {
            val hasKey = prefs.isThere() && prefs.getToken() != null
            if (hasKey) {
                operation { fence ->
                    val token = prefs.getToken() ?: return@operation null to null
                    settled(fence)
                    token to null
                }
            }
            hasKey && savedVerdict() == LINKED
        }

    /**
     * The tap on "Check now" (spec 4.3, change 5): ONE balance call; its answer goes where every balance answer goes. At
     * most [AI_CHECK_LIMIT_MS].
     */
    suspend fun checkNow() = withinTheLimit(AI_CHECK_LIMIT_MS, Unit) { checkOnce() }

    private suspend fun checkOnce() = readingTheStore(Unit) { askBalanceOnce() }

    private suspend fun askBalanceOnce() {
        if (!walletSaved() || !prefs.isThere()) return
        val token = keyNotOwingASignIn() ?: return
        val fence = Fence(ownerTag(), scopeCounter())
        val reply = ask("GET", BALANCE, token, null)
        if (reply.status != OK) return
        val answer = json(reply.body)?.takeIf { it.has(FIELD_KEY_ID) } ?: return
        if (!holds(fence) || prefs.getToken() != token) return
        onBalance(
            fence.forKey(token),
            if (answer.isNull(FIELD_KEY_ID)) null else answer.optString(FIELD_KEY_ID),
            answer.opt(FIELD_PAID) as? Boolean,
        )
    }

    private fun savedVerdict(): String? = linkOf(prefs.getUserId().orEmpty())?.verdict

    /** Off the main thread; a store that cannot be read gives [unreadable] and never an exception. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> readingTheStore(
        unreadable: T,
        read: suspend () -> T,
    ): T =
        withContext(Dispatchers.IO) {
            try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unreadable
            }
        }

    /**
     * Runs [block] on a thread of its own and waits for it for at most [limitMillis], in real time: a read that blocks and
     * ignores interrupts cannot hold the screen. At the limit the answer is [late], [block] is cancelled, the [CheckLimit] it
     * carries stops what it would still send or write, and the one-at-a-time lock it may hold is let go: later AI work does
     * not wait for its socket.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T : Any> withinTheLimit(
        limitMillis: Long,
        late: T,
        block: suspend () -> T,
    ): T {
        val answer = CompletableDeferred<T>()
        val limit = CheckLimit(oneAtATime)
        val running = Job()
        val thread =
            Thread(
                {
                    try {
                        answer.complete(runBlocking(running + limit) { block() })
                    } catch (t: Throwable) {
                        answer.completeExceptionally(t)
                    }
                },
                "zchat-ai-reset-check"
            ).apply { isDaemon = true }
        try {
            thread.start()
            return withContext(Dispatchers.IO) { withTimeoutOrNull(limitMillis) { answer.await() } } ?: late
        } catch (e: OutOfMemoryError) {
            return late
        } finally {
            limit.end()
            running.cancel()
        }
    }

    /** The limit of the [withinTheLimit] a call runs in; calls outside one carry none. */
    private class CheckLimit(
        private val lock: Mutex,
    ) : AbstractCoroutineContextElement(CheckLimit) {
        @Volatile
        var over = false
            private set

        @Synchronized
        fun end() {
            over = true
            letGo()
        }

        /** Whoever comes first, the end of the operation or the limit, unlocks; this is the owner the lock was taken with. */
        @Synchronized
        fun letGo() {
            if (lock.holdsLock(this)) lock.unlock(this)
        }

        companion object Key : CoroutineContext.Key<CheckLimit>
    }

    private suspend fun operation(block: suspend (Fence) -> Pair<String?, String?>): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            val limit = currentCoroutineContext()[CheckLimit]
            oneAtATime.lock(limit)
            try {
                stopIfOverTheLimit()
                if (!walletSaved()) return@withContext STOPPED
                val fence = Fence(ownerTag(), scopeCounter())
                val answer = block(fence)
                // GPT 2: the last check before any answer leaves: no key crosses a wipe or a change of wallet.
                if (holds(fence)) answer else STOPPED
            } finally {
                if (limit == null) oneAtATime.unlock() else limit.letGo()
            }
        }

    private suspend fun stopIfOverTheLimit() {
        if (currentCoroutineContext()[CheckLimit]?.over == true) throw CancellationException("The check is over its limit")
    }

    private suspend fun holds(fence: Fence): Boolean = walletSaved() && fence.isCurrent()

    private fun Fence.isCurrent(): Boolean = ownerTag() == tag && scopeCounter() == counter

    private suspend fun write(
        fence: Fence,
        change: AiPreferences.Editing.() -> Unit,
    ): Boolean {
        val limit = currentCoroutineContext()[CheckLimit]
        return walletSaved() && prefs.writeIfStillThisWallet({ limit?.over != true && fence.isCurrent() }, change)
    }

    /** What the saved key is good for now, asking the server only when that is not known. */
    private suspend fun settled(fence: Fence): Decided {
        val token = prefs.getToken() ?: return getKey(fence)
        val link = linkOf(prefs.getUserId().orEmpty())
        return when {
            link?.signInOwed == true -> linkGone(fence, token, link)
            link?.verdict != null -> Decided(token, null, true)
            else -> decide(fence, token, tap = false)
        }
    }

    /** Spec 4.5, first bullet: no saved key. */
    private suspend fun getKey(fence: Fence): Decided =
        when (val answer = signIn(fence, create = true)) {
            is SignedIn -> saved(fence, answer.token, answer.userId, Replacing.NOT_OURS, notice = null)
            is NoSignIn -> Decided(null, answer.sentence, false)
            UnknownKey -> Decided(null, text("ai_signin_refused"), false)
        }

    /**
     * Spec 4.5, the rule for every replacement (M1, M2): true when the saved pair of [oldId] may be overwritten. In every
     * other case it is parked: an access key to credit is never lost. Paid unknown counts as paid. ZC-1250: a refusal too,
     * since a 401 may not be our server's; a key that really is dead retires later, on its "Try to link". A refused pair
     * that was [wasLinked] is overwritten whatever its paid: the recovery phrase reopens that account (two phones).
     */
    private fun mayOverwrite(
        because: Replacing,
        oldId: String,
        newId: String?,
        paid: String,
        wasLinked: Boolean = false,
    ): Boolean =
        oldId == newId ||
            when (because) {
                Replacing.REFUSED -> paid == PAID_NO || wasLinked
                Replacing.KEY_IN_USE -> paid == PAID_NO
                Replacing.NOT_OURS -> paid == PAID_NO
                Replacing.TAP -> false
            }

    /**
     * The saved pair becomes this one, linked. A parked key of the same account is retired (change 2), with its
     * sentence; the pair it replaces follows [mayOverwrite]. [notice] is the resource key of what the user is told: it
     * stays owed in ai_link_state until it is shown (ZC-1205). A tap's answer is shown at once and owes nothing.
     */
    @Suppress("LongParameterList")
    private suspend fun saved(
        fence: Fence,
        token: String,
        userId: String,
        because: Replacing,
        notice: String?,
        oldPaid: String? = null,
    ): Decided {
        val paid = paidOfAccount(userId)
        val carried = Link.parse(prefs.getLinkStateJson())?.owed
        val oldToken = prefs.getToken()
        val oldId = prefs.getUserId().orEmpty()
        val paidBefore = oldPaid ?: linkOf(oldId)?.paid ?: PAID_UNKNOWN
        val wasLinked = linkOf(oldId)?.verdict == LINKED
        val wasParked = userId in prefs.parkedUserIds()
        val told = if (wasParked) PARKED_OPENED else notice
        val owed = if (because == Replacing.TAP) carried else told ?: carried
        val written =
            write(fence) {
                retire(userId)
                if (oldToken != null && !mayOverwrite(because, oldId, userId, paidBefore, wasLinked)) park(oldToken, oldId, paidBefore)
                putPair(token, userId)
                putLink(Link(userId, verdict = LINKED, paid = paid, owed = owed))
            }
        if (!written) return Decided(null, text(NOT_READY), false)
        return Decided(token, told?.let(::text), true)
    }

    /** Item 20: what is known of the account: its own record, or what its parked key kept. Yes outlives everything (M1). */
    private fun paidOfAccount(userId: String): String {
        val known = listOfNotNull(linkOf(userId)?.paid, prefs.parkedPaid(userId))
        return if (PAID_YES in known) PAID_YES else known.firstOrNull() ?: PAID_UNKNOWN
    }

    /** Spec 4.5, a refused access key. The saved pair is replaced only after the new one arrived. */
    private suspend fun refused(fence: Fence): Decided {
        val now = clock()
        val last = lastRefusalMillis
        if (last != null && now - last < REFUSAL_PAUSE_MILLIS) return Decided(null, text("ai_signin_elsewhere"), false)
        val answer = signIn(fence, create = true)
        // ZC-1209: a sign-in that never got an answer opened nothing on any phone, and is no reason to wait.
        if (answer !is NoSignIn || answer.answered) lastRefusalMillis = now
        return when (answer) {
            is SignedIn ->
                saved(
                    fence,
                    answer.token,
                    answer.userId,
                    Replacing.REFUSED,
                    "ai_notice_new_account_after_refusal".takeIf { answer.created },
                )
            is NoSignIn -> Decided(null, answer.sentence, false)
            UnknownKey -> Decided(null, text("ai_signin_refused"), false)
        }
    }

    /**
     * Spec 4.3, a once-linked account that shows no key: the verdict goes and nothing is compared; the wallet's key
     * signs in without opening an account. An account that comes back becomes the saved one by the rule of 4.5, and
     * [link]'s pair is parked unless it is known to hold no paid credit. ZC-1207: until the sign-in is answered it stays
     * owed in ai_link_state, and the next event signs in first; the key has no verdict and is handed to no call meanwhile.
     */
    private suspend fun linkGone(
        fence: Fence,
        token: String,
        link: Link,
    ): Decided {
        val owing = link.copy(verdict = null, compared = true, matched = null, signInOwed = true)
        if (owing != link && !write(fence) { putLink(owing) }) return Decided(null, text(NOT_READY), false)
        return when (val answer = signIn(fence, create = false)) {
            is SignedIn -> saved(fence, answer.token, answer.userId, Replacing.NOT_OURS, notice = null, oldPaid = owing.paid)
            UnknownKey -> {
                val own = owing.copy(verdict = OWN, signInOwed = false)
                if (write(fence) { putLink(own) }) Decided(token, text(LINK_LOST), true) else Decided(null, text(NOT_READY), false)
            }
            is NoSignIn -> Decided(null, answer.sentence, false)
        }
    }

    /** Spec 4.4. [tap] is the user's "Link again": it may compare once more, and it never gives a key up. */
    private suspend fun decide(
        fence: Fence,
        token: String,
        tap: Boolean,
    ): Decided {
        val own = ownKeyId() ?: return Decided(token, text(NOT_READY), false)
        val reply = ask("GET", KEY_STATE, token, null)
        if (!holds(fence)) return Decided(null, text(NOT_READY), false)
        return when {
            // GPT 1: only an old server, which has no key/state, stays chargeable; no answer is no verdict and no charge.
            reply.status == NOT_FOUND -> Decided(token, null, true)
            AiApiClient.refusesAccessKey(reply.status, reply.body) -> refused(fence)
            reply.status != OK -> Decided(token, reply.readable(), false)
            else -> judge(fence, token, own, reply.body, tap)
        }
    }

    private suspend fun judge(
        fence: Fence,
        token: String,
        own: String,
        body: String,
        tap: Boolean,
    ): Decided {
        val state = json(body)?.takeIf { it.has(FIELD_KEY_ID) } ?: return Decided(token, text(SERVICE), false)
        // GPT 5: paid is only ever a real JSON Boolean; anything else is not known, and a key of unknown paid is parked.
        val answered =
            when (state.opt(FIELD_PAID)) {
                true -> PAID_YES
                false -> PAID_NO
                else -> PAID_UNKNOWN
            }
        val id = prefs.getUserId().orEmpty()
        val stored = linkOf(id) ?: Link(id)
        // M1: the app's own record of a handed-out address outlives what a later answer says.
        val paid = if (stored.paid == PAID_YES) PAID_YES else answered
        val link = stored.copy(paid = paid)
        val onAccount = if (state.isNull(FIELD_KEY_ID)) null else state.optString(FIELD_KEY_ID)
        val check = state.optJSONObject(FIELD_WALLET_CHECK)
        return when {
            onAccount == own -> verdictLinked(fence, token, link)
            onAccount != null -> if (tap) Decided(token, null, true) else notThisWallet(fence, token, id, paid)
            check == null -> register(fence, token, own, link, PROOF_NONE, NO_WALLET, tap)
            else -> compare(fence, token, own, link, check, tap)
        }
    }

    private suspend fun compare(
        fence: Fence,
        token: String,
        own: String,
        link: Link,
        check: JSONObject,
        tap: Boolean,
    ): Decided {
        val digest = Digest.of(check) ?: return Decided(token, text(SERVICE), false)
        if (link.compared && !tap) {
            // ZC-1178: the one comparison is used up. Only the candidate it found is tried again; nothing else is read.
            val matched = link.matched?.takeIf { digest.fits(it) } ?: return Decided(token, null, true)
            return register(fence, token, own, link, PROOF_FINGERPRINT, matched, tap = false)
        }
        val read = readCandidates() ?: return Decided(token, text(NOT_READY), false)
        val found = read.firstOrNull { digest.fits(it) }
        return when {
            found != null -> {
                val noted = link.copy(compared = true, matched = found)
                if (!write(fence) { putLink(noted) }) return Decided(null, text(NOT_READY), false)
                register(fence, token, own, noted, PROOF_FINGERPRINT, found, tap)
            }
            tap -> Decided(token, null, true)
            else -> notThisWallet(fence, token, link.accountId, link.paid)
        }
    }

    /**
     * Null when nothing could be read at all, or the wallet lists nothing yet: that is no answer about whose the key is. An
     * entry that throws is skipped.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun readCandidates(): List<String>? {
        val entries =
            try {
                candidates()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
        val read =
            entries.mapNotNull { entry ->
                try {
                    entry().lowercase().takeIf { FINGERPRINT_FORM.matches(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        return read.ifEmpty { null }
    }

    @Suppress("LongParameterList")
    private suspend fun register(
        fence: Fence,
        token: String,
        own: String,
        link: Link,
        proofName: String,
        wallet: String,
        tap: Boolean,
    ): Decided {
        repeat(TRIES) {
            val question = question(fence)
            // GPT 2: no question, no verdict and no charge; a moved fence ends in the last check of [operation].
            if (question !is Question) return Decided(token, (question as NoQuestion).sentence, false)
            val proof =
                try {
                    proof(PURPOSE_REGISTER, question, own, link.accountId, wallet, create = false)
                } catch (e: IllegalArgumentException) {
                    return Decided(token, text(SERVICE), false)
                } ?: return Decided(token, text(NOT_READY), false)
            val body =
                JSONObject()
                    .put(FIELD_CHALLENGE, question.id)
                    .put(FIELD_PUBLIC_KEY, proof.publicKey)
                    .put(FIELD_SIGNATURE, proof.signature)
                    .put(FIELD_WALLET_PROOF, proofName)
                    .toString()
            val reply = ask("POST", REGISTER, token, body)
            if (!holds(fence)) return Decided(null, text(NOT_READY), false)
            val code = reply.errorCode()
            when {
                reply.status == OK -> return verdictLinked(fence, token, link)
                reply.status == BAD_REQUEST && code == CHALLENGE_EXPIRED -> Unit
                reply.status == CONFLICT && code == ACCOUNT_HAS_OTHER_KEY ->
                    return if (tap) {
                        Decided(token, null, true)
                    } else {
                        notThisWallet(fence, token, link.accountId, link.paid)
                    }
                reply.status == CONFLICT && code == KEY_IN_USE -> return keyInUse(fence, token, link)
                reply.status == CONFLICT && code == KEY_HAS_PAID_ACCOUNT -> return twoPaidAccounts(fence, token, link)
                code != BAD_SIGNATURE && AiApiClient.refusesAccessKey(reply.status, reply.body) -> return refused(fence)
                // GPT 1: as for key/state, no answer is no verdict and no charge.
                reply.isDown || reply.status >= SERVER_ERRORS -> return Decided(token, reply.readable(), false)
                // Anything else, changed_meanwhile included: no verdict, nothing said, the next event tries again.
                else -> return Decided(token, null, true)
            }
        }
        return Decided(token, null, true)
    }

    /** Spec 4.4 item 5: the key sits on this wallet's own trial account, which takes over from the saved one. */
    private suspend fun keyInUse(
        fence: Fence,
        token: String,
        link: Link,
    ): Decided =
        when (val answer = signIn(fence, create = false)) {
            is SignedIn ->
                saved(
                    fence,
                    answer.token,
                    answer.userId,
                    Replacing.KEY_IN_USE,
                    "ai_notice_trial_account_left",
                    oldPaid = link.paid,
                )
            is NoSignIn -> Decided(token, answer.sentence, false)
            UnknownKey -> Decided(token, null, true)
        }

    /** Spec 4.4 item 5, 5.6 (M10): the saved pair stays and stays in use; the operator joins the two accounts. */
    private suspend fun twoPaidAccounts(
        fence: Fence,
        token: String,
        link: Link,
    ): Decided {
        val own = link.copy(verdict = OWN, compared = true, matched = null, twoPaid = true)
        return if (write(fence) { putLink(own) }) Decided(token, text(TWO_PAID), true) else Decided(null, text(NOT_READY), false)
    }

    private suspend fun claim(
        fence: Fence,
        own: String,
        wallet: String,
    ): Pair<String?, String?> {
        repeat(TRIES) {
            val question = question(fence)
            if (question !is Question) return prefs.getToken() to (question as NoQuestion).sentence
            val proof =
                try {
                    proof(PURPOSE_CLAIM, question, own, NO_WALLET, wallet, create = false)
                } catch (e: IllegalArgumentException) {
                    return prefs.getToken() to text(SERVICE)
                } ?: return prefs.getToken() to text(NOT_READY)
            val body =
                JSONObject()
                    .put(FIELD_CHALLENGE, question.id)
                    .put(FIELD_PUBLIC_KEY, proof.publicKey)
                    .put(FIELD_SIGNATURE, proof.signature)
                    .put(FIELD_WALLET_PUBKEY, wallet)
                    .toString()
            if (!holds(fence)) return STOPPED
            val reply = ask("POST", CLAIM, null, body)
            if (!holds(fence)) return STOPPED
            val code = reply.errorCode()
            when {
                reply.status == OK -> return claimed(fence, reply.body)
                reply.status == BAD_REQUEST && code == CHALLENGE_EXPIRED -> Unit
                reply.status == CONFLICT && code == NEEDS_OWNER_CHECK -> return prefs.getToken() to text("ai_old_credit_needs_check")
                reply.status == CONFLICT && code == KEY_HAS_PAID_ACCOUNT -> return prefs.getToken() to text("ai_old_credit_two_accounts")
                reply.status == CONFLICT && code == CHANGED_MEANWHILE -> return prefs.getToken() to null
                else -> return prefs.getToken() to refusal(reply)
            }
        }
        return prefs.getToken() to text("ai_signin_refused")
    }

    private suspend fun claimed(
        fence: Fence,
        body: String,
    ): Pair<String?, String?> {
        val answer = json(body)?.takeIf { it.has(FIELD_FOUND) } ?: return prefs.getToken() to text(SERVICE)
        if (!answer.optBoolean(FIELD_FOUND)) return prefs.getToken() to text("ai_old_credit_none")
        val token = answer.optString(FIELD_TOKEN)
        val userId = answer.optString(FIELD_USER_ID)
        if (token.isEmpty() || userId.isEmpty()) return prefs.getToken() to text(SERVICE)
        val decided = saved(fence, token, userId, Replacing.TAP, "ai_old_credit_opened")
        return decided.key to decided.sentence
    }

    private suspend fun linkParked(
        fence: Fence,
        own: String,
        parked: String,
        accountId: String,
    ): Pair<String?, String?> {
        repeat(TRIES) {
            val question = question(fence)
            if (question !is Question) return prefs.getToken() to (question as NoQuestion).sentence
            val proof =
                try {
                    proof(PURPOSE_REGISTER, question, own, accountId, PROOF_TICKET, create = false)
                } catch (e: IllegalArgumentException) {
                    return prefs.getToken() to text(SERVICE)
                } ?: return prefs.getToken() to text(NOT_READY)
            val body =
                JSONObject()
                    .put(FIELD_CHALLENGE, question.id)
                    .put(FIELD_PUBLIC_KEY, proof.publicKey)
                    .put(FIELD_SIGNATURE, proof.signature)
                    .put(FIELD_WALLET_PROOF, PROOF_TICKET)
                    .toString()
            val reply = ask("POST", REGISTER, parked, body)
            if (!holds(fence)) return STOPPED
            val code = reply.errorCode()
            when {
                reply.status == OK -> {
                    val decided = saved(fence, parked, accountId, Replacing.TAP, notice = null)
                    return decided.key to decided.sentence
                }
                reply.status == BAD_REQUEST && code == CHALLENGE_EXPIRED -> Unit
                reply.refusesAccessKeyByName() ->
                    return if (write(fence) { retireKey(parked) }) prefs.getToken() to text(PARKED_DEAD) else STOPPED
                // ZC-1185: a tap before the operator's check is answered bad_signature, and the key stays.
                reply.status == UNAUTHORIZED && code == BAD_SIGNATURE -> return prefs.getToken() to text(PARKED_NOTICE)
                reply.status == CONFLICT && code == KEY_HAS_PAID_ACCOUNT -> return prefs.getToken() to text("ai_old_credit_two_accounts")
                reply.status == CONFLICT && code == CHANGED_MEANWHILE -> return prefs.getToken() to null
                reply.status == CONFLICT -> return prefs.getToken() to text(PARKED_NOTICE)
                else -> return prefs.getToken() to refusal(reply)
            }
        }
        return prefs.getToken() to text("ai_signin_refused")
    }

    private fun refusal(reply: Reply): String {
        val code = reply.errorCode()
        return when {
            reply.isDown -> reply.readable()
            reply.status == NOT_FOUND -> text("ai_signin_server_not_ready")
            reply.status == TOO_MANY && code == SIGNUP_LIMIT -> text("ai_signup_limit")
            reply.status == TOO_MANY -> text("ai_signin_too_many")
            reply.status in CLIENT_ERRORS && !AiApiClient.isServiceOutage(reply.body) -> text("ai_signin_refused")
            else -> reply.readable()
        }
    }

    private suspend fun verdictLinked(
        fence: Fence,
        token: String,
        link: Link,
    ): Decided {
        val linked = link.copy(verdict = LINKED, compared = false, matched = null)
        return if (write(fence) { putLink(linked) }) Decided(token, null, true) else Decided(null, text(NOT_READY), false)
    }

    /** Spec 4.4, "not this wallet's": dropped only when the account is known to hold no paid credit; else parked. */
    private suspend fun notThisWallet(
        fence: Fence,
        token: String,
        id: String,
        paid: String,
    ): Decided {
        val written =
            write(fence) {
                if (!mayOverwrite(Replacing.NOT_OURS, id, null, paid)) park(token, id, paid)
                removePair()
                // ZC-1205: with no pair yet the notice waits under no account, for the sign-in that gets the key.
                if (paid == PAID_NO) putLink(Link(NO_ACCOUNT, owed = "ai_notice_other_wallet_dropped")) else removeLink()
            }
        if (!written) return Decided(null, text(NOT_READY), false)
        return getKey(fence)
    }

    /** ZC-1205: the notice owed, as long as nobody said it was shown ([noticeShown]). */
    private fun owedNotice(): String? = Link.parse(prefs.getLinkStateJson())?.let(::owedText)

    /** Null for a key this version has no sentence for: a store written by a later version says nothing here. */
    private fun owedText(link: Link): String? = link.owed?.let { TEXTS[it] }

    /** What stays true while the store is as it is: the link was lost, or a key is parked. */
    private fun standingNotice(): String? =
        when {
            linkOf(prefs.getUserId().orEmpty())?.verdict == OWN ->
                text(if (linkOf(prefs.getUserId().orEmpty())?.twoPaid == true) TWO_PAID else LINK_LOST)
            prefs.hasParkedKeys() -> text(PARKED_NOTICE)
            else -> null
        }

    private suspend fun signIn(
        fence: Fence,
        create: Boolean,
    ): SignInAnswer {
        val own = ownKeyId() ?: return NoSignIn(text(NOT_READY))
        repeat(TRIES) {
            val question = question(fence)
            if (question !is Question) return NoSignIn((question as NoQuestion).sentence)
            val proof =
                try {
                    proof(PURPOSE_SIGNIN, question, own, NO_WALLET, NO_WALLET, create)
                } catch (e: IllegalArgumentException) {
                    return NoSignIn(text(SERVICE))
                } ?: return NoSignIn(text(NOT_READY))
            val body =
                JSONObject()
                    .put(FIELD_CHALLENGE, question.id)
                    .put(FIELD_PUBLIC_KEY, proof.publicKey)
                    .put(FIELD_SIGNATURE, proof.signature)
                    .put(FIELD_CREATE, create)
                    .toString()
            val reply = ask("POST", SIGN_IN, null, body)
            if (!holds(fence)) return NoSignIn(text(NOT_READY))
            val answered = !reply.isDown && reply.status < SERVER_ERRORS
            val code = reply.errorCode()
            when {
                reply.isDown -> return NoSignIn(reply.readable())
                reply.status == OK -> return signedIn(reply.body)
                reply.status == BAD_REQUEST && code == CHALLENGE_EXPIRED -> Unit
                // ZC-1209: the account changed under the question: one more try with a new one.
                reply.status == CONFLICT && code == CHANGED_MEANWHILE -> Unit
                reply.status == CONFLICT && code == UNKNOWN_KEY -> return UnknownKey
                reply.status == NOT_FOUND -> return NoSignIn(text("ai_signin_server_not_ready"), answered)
                reply.status == TOO_MANY && code == SIGNUP_LIMIT -> return NoSignIn(text("ai_signup_limit"), answered)
                reply.status == TOO_MANY -> return NoSignIn(text("ai_signin_too_many"), answered)
                reply.status in CLIENT_ERRORS && !AiApiClient.isServiceOutage(reply.body) ->
                    return NoSignIn(text("ai_signin_refused"), answered)
                else -> return NoSignIn(reply.readable(), answered)
            }
        }
        return NoSignIn(text("ai_signin_refused"), answered = true)
    }

    private fun signedIn(body: String): SignInAnswer {
        val answer = json(body) ?: return NoSignIn(text(SERVICE))
        val token = answer.optString(FIELD_TOKEN)
        val userId = answer.optString(FIELD_USER_ID)
        if (token.isEmpty() || userId.isEmpty()) return NoSignIn(text(SERVICE))
        return SignedIn(userId, token, answer.optBoolean(FIELD_CREATED, false))
    }

    private suspend fun question(fence: Fence): QuestionAnswer {
        val reply = ask("POST", CHALLENGE, null, "{}")
        if (!holds(fence)) return NoQuestion(text(NOT_READY))
        return when {
            reply.isDown -> NoQuestion(reply.readable())
            reply.status == NOT_FOUND -> NoQuestion(text("ai_signin_server_not_ready"))
            reply.status == TOO_MANY -> NoQuestion(text("ai_signin_too_many"))
            reply.status != OK -> NoQuestion(reply.readable())
            else -> {
                val answer = json(reply.body)
                val id = answer?.optString(FIELD_CHALLENGE).orEmpty()
                val nonce = answer?.optString(FIELD_NONCE).orEmpty()
                val expires = answer?.opt(FIELD_EXPIRES_AT)
                // A JSON integer, its digits copied into the message; a string or a date is no answer.
                if (id.isEmpty() || nonce.isEmpty() || (expires !is Int && expires !is Long)) {
                    NoQuestion(text(SERVICE))
                } else {
                    Question(id, nonce, (expires as Number).toLong())
                }
            }
        }
    }

    /** Null without a usable seed. Throws IllegalArgumentException for a value outside the message's form. */
    @Suppress("LongParameterList")
    private suspend fun proof(
        purpose: String,
        question: Question,
        own: String,
        account: String,
        wallet: String,
        create: Boolean,
    ): Proof? =
        withSeed { bytes ->
            val pair = AiSignInKey.derive(bytes)
            Proof(
                publicKey = pair.publicKey,
                signature =
                    AiSignInProof.sign(
                        pair.privateKey,
                        purpose,
                        question.nonce,
                        question.expires,
                        own,
                        account,
                        wallet,
                        create,
                    ),
            )
        }

    private suspend fun ownKeyId(): String? {
        val tag = ownerTag()
        keyIdOfTag?.takeIf { it.first == tag }?.let { return it.second }
        val id = withSeed { bytes -> AiSignInKey.keyId(AiSignInKey.derive(bytes).publicKey) } ?: return null
        keyIdOfTag = tag to id
        return id
    }

    /** The seed is only ever the 64 bytes of the phrase, never its entropy, and it is wiped whatever happens. */
    private suspend fun <T> withSeed(block: (ByteArray) -> T): T? {
        val bytes = seed() ?: return null
        try {
            return if (bytes.size == SEED_BYTES) block(bytes) else null
        } finally {
            bytes.fill(0)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun ask(
        method: String,
        path: String,
        accessKey: String?,
        body: String?,
    ): Reply {
        stopIfOverTheLimit()
        val reply =
            try {
                val (status, text) = send(method, path, accessKey, body)
                Reply(status, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Reply(DOWN, "", AiApiClient.readableTransportFailure(e))
            }
        // A call that blocked past its check's limit: the answer is dropped here, before anything acts on it.
        stopIfOverTheLimit()
        return reply
    }

    private fun linkOf(id: String): Link? = Link.parse(prefs.getLinkStateJson())?.takeIf { it.accountId == id }

    private fun text(key: String): String = if (key == SERVICE) AiApiClient.SERVICE_UNAVAILABLE_MESSAGE else TEXTS.getValue(key)

    /** Why the saved pair is being replaced: the cases of spec 4.5 ([mayOverwrite]). */
    private enum class Replacing {
        REFUSED,
        KEY_IN_USE,
        NOT_OURS,
        TAP,
    }

    /** The wallet a request belongs to, noted before the request. */
    class Fence internal constructor(
        internal val tag: String?,
        internal val counter: Long,
    ) {
        /** The request on [key] under this fence. */
        fun forKey(key: String): Ticket = Ticket(key, this)

        override fun toString(): String = "Fence(redacted)"
    }

    /** One request: the key it went out on and the fence noted before it. The answer counts only while both still hold. */
    class Ticket internal constructor(
        internal val key: String,
        internal val fence: Fence,
    ) {
        /** The same request repeated on [other] (a refusal gave a new key): the fence of the first stays. */
        fun forKey(other: String): Ticket = Ticket(other, fence)

        override fun toString(): String = "Ticket(redacted)"
    }

    /**
     * What [request] got, for a wallet that is still the one asked for. [ticket]: the request that got it. [keyStillSaved]:
     * false when another path replaced the key meanwhile: an answer that was paid for is still the user's, but nothing in
     * it may be taken for the saved account's (a balance, a list).
     */
    class Answered<R> internal constructor(
        val result: R,
        val ticket: Ticket,
        val keyStillSaved: Boolean,
    )

    /** What a step ends in. [chargeable]: the key may carry a chat or a picture (spec 4.2). */
    private class Decided(
        val key: String?,
        val sentence: String?,
        val chargeable: Boolean,
    )

    private class Reply(
        val status: Int,
        val body: String,
        private val transportSentence: String? = null,
    ) {
        val isDown: Boolean get() = status == DOWN

        fun readable(): String = transportSentence ?: AiApiClient.readableHttpFailure(status, body)

        /** The server's refusals are {error: <sentence>, code: <code>}; a bare {error: <code>} is read too. */
        fun errorCode(): String {
            val answer = json(body) ?: return ""
            return answer.optString("code").ifEmpty { answer.optString("error") }
        }

        /**
         * The server itself says this access key is no good: the code bad_token, what register answers, and nothing else.
         * Only this retires a parked key: no code, a bare error, another code or a page in front of the server say nothing
         * about the key, and the key may be the only way to paid credit (items 18, 23).
         */
        fun refusesAccessKeyByName(): Boolean =
            AiApiClient.refusesAccessKey(status, body) && json(body)?.optString("code") == BAD_TOKEN
    }

    private sealed interface QuestionAnswer

    private class Question(
        val id: String,
        val nonce: String,
        val expires: Long,
    ) : QuestionAnswer

    private class NoQuestion(
        val sentence: String,
    ) : QuestionAnswer

    private class Proof(
        val publicKey: String,
        val signature: String,
    ) {
        override fun toString(): String = "Proof(redacted)"
    }

    private sealed interface SignInAnswer

    private class SignedIn(
        val userId: String,
        val token: String,
        val created: Boolean,
    ) : SignInAnswer {
        override fun toString(): String = "SignedIn(redacted)"
    }

    /** [answered]: the server said this itself; no answer or an outage is no sign-in at all. */
    private class NoSignIn(
        val sentence: String,
        val answered: Boolean = false,
    ) : SignInAnswer

    private data object UnknownKey : SignInAnswer

    /** The salted digest of key/state (spec 3.2). Null when it cannot be read: that is no comparison at all. */
    private class Digest(
        private val salt: ByteArray,
        private val check: String,
    ) {
        fun fits(fingerprint: String): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            val made = digest.digest(fingerprint.toByteArray(Charsets.US_ASCII))
            return made.joinToString("") { "%02x".format(it) } == check
        }

        companion object {
            fun of(check: JSONObject): Digest? {
                val value = check.optString("check").lowercase()
                val salt =
                    try {
                        Base64.getUrlDecoder().decode(check.optString("salt"))
                    } catch (e: IllegalArgumentException) {
                        return null
                    }
                return if (salt.isEmpty() || value.isEmpty()) null else Digest(salt, value)
            }
        }
    }

    /**
     * ai_link_state (spec 4.2), plus the used-up comparison and its candidate (M5, ZC-1178), the resource key of a notice
     * not yet shown (ZC-1205) and a sign-in owed after a lost link (ZC-1207).
     */
    private data class Link(
        val accountId: String,
        val verdict: String? = null,
        val paid: String = PAID_UNKNOWN,
        val day: Long? = null,
        val compared: Boolean = false,
        val matched: String? = null,
        val twoPaid: Boolean = false,
        val owed: String? = null,
        val signInOwed: Boolean = false,
    ) {
        fun toJson(): String =
            JSONObject()
                .put("accountId", accountId)
                .put("paid", paid)
                .apply {
                    verdict?.let { put("verdict", it) }
                    day?.let { put("day", it) }
                    if (compared) put("compared", true)
                    matched?.let { put("matched", it) }
                    if (twoPaid) put("twoPaid", true)
                    owed?.let { put("owed", it) }
                    if (signInOwed) put("signInOwed", true)
                }.toString()

        override fun toString(): String = "Link(redacted)"

        companion object {
            fun parse(text: String?): Link? {
                val state = text?.let { json(it) } ?: return null
                val id = state.optString("accountId")
                if (!state.has("accountId")) return null
                return Link(
                    accountId = id,
                    verdict = state.optString("verdict").ifEmpty { null },
                    paid = state.optString("paid").ifEmpty { PAID_UNKNOWN },
                    day = if (state.has("day")) state.optLong("day") else null,
                    compared = state.optBoolean("compared", false),
                    matched = state.optString("matched").ifEmpty { null },
                    twoPaid = state.optBoolean("twoPaid", false),
                    owed = state.optString("owed").ifEmpty { null },
                    signInOwed = state.optBoolean("signInOwed", false),
                )
            }
        }
    }

    private fun AiPreferences.Editing.putLink(link: Link) = putLinkStateJson(link.toJson())

    companion object {
        /**
         * The production seed read, as the chat keys read it: a fresh array of the phrase's 64-byte BIP-39 seed each
         * time, or null when no wallet is saved or anything goes wrong. [AiAccountAccess] wipes it.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun seedReader(provider: PersistableWalletProvider): suspend () -> ByteArray? =
            {
                try {
                    provider.getPersistableWallet()?.let { wallet ->
                        Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    null
                }
            }

        /**
         * The candidates of spec 4.4 item 4, read only: for every wallet account the fingerprint of its saved chat
         * identity and of its current address. An account that answers with an error is skipped by the comparison.
         * A wallet that does not answer in time throws: that is no answer about whose a key is.
         */
        fun candidateReader(
            accounts: AccountDataSource,
            synchronizers: SynchronizerProvider,
            identities: () -> OwnChatIdentityStore,
        ): suspend () -> List<suspend () -> String> =
            {
                val ready =
                    withTimeoutOrNull(WALLET_ANSWER_MILLIS) { accounts.getAllAccounts() to synchronizers.getSynchronizer() }
                        ?: error("The wallet did not answer in time")
                val (all, synchronizer) = ready
                all.flatMap { account ->
                    val saved = identities().pinnedIdentity(GetDefaultUnifiedAddressUseCase.accountKeyOf(account))
                    listOfNotNull(
                        saved?.let { identity -> suspend { WalletPubkey.fingerprintOf(identity) } },
                        suspend { WalletPubkey.fingerprintOf(synchronizer.getUnifiedAddress(account.sdkAccount)) },
                    )
                }
            }

        private val STOPPED: Pair<String?, String?> get() = null to AiApiClient.SIGN_IN_TEXTS.getValue(NOT_READY)

        private val TEXTS: Map<String, String>
            get() = AiApiClient.SIGN_IN_TEXTS + AiApiClient.OTHER_ACCOUNT_TEXTS + AiApiClient.LINK_AGAIN_TEXTS

        private fun json(text: String): JSONObject? =
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                null
            }

        private const val KEY_STATE = "/api/v1/ai/auth/key/state"
        private const val BALANCE = "/api/v1/ai/balance"
        private const val CHALLENGE = "/api/v1/ai/auth/key/challenge"
        private const val REGISTER = "/api/v1/ai/auth/key/register"
        private const val SIGN_IN = "/api/v1/ai/auth/key/signin"
        private const val CLAIM = "/api/v1/ai/auth/key/claim"

        private const val NOT_READY = "ai_signin_wallet_not_ready"
        private const val LINK_LOST = "ai_notice_link_lost"
        private const val PARKED_NOTICE = "ai_notice_key_parked"
        private const val PARKED_DEAD = "ai_notice_parked_key_dead"
        private const val PARKED_OPENED = "ai_notice_parked_key_opened"
        private const val TWO_PAID = "ai_notice_two_paid_accounts"
        private const val TOPUP_NOT_LINKED = "ai_topup_not_linked"
        private const val LINK_AGAIN_DONE = "ai_link_again_done"
        private const val LINK_AGAIN_NOT_DONE = "ai_link_again_not_done"
        private const val SERVICE = "service"

        private const val LINKED = "linked"
        private const val OWN = "own"
        private const val PAID_YES = "yes"
        private const val PAID_NO = "no"
        private const val PAID_UNKNOWN = "unknown"

        private const val PURPOSE_REGISTER = "register"
        private const val PURPOSE_SIGNIN = "signin"
        private const val PURPOSE_CLAIM = "claim"
        private const val PROOF_NONE = "none"
        private const val PROOF_FINGERPRINT = "fingerprint"
        private const val PROOF_TICKET = "ticket"
        private const val NO_WALLET = "-"
        private const val NO_ACCOUNT = ""
        private const val NO_SAVED_KEY = "no saved key"

        private const val FIELD_KEY_ID = "signInKeyId"
        private const val FIELD_PAID = "paid"
        private const val FIELD_WALLET_CHECK = "walletCheck"
        private const val FIELD_CHALLENGE = "challengeId"
        private const val FIELD_NONCE = "nonce"
        private const val FIELD_EXPIRES_AT = "expiresAt"
        private const val FIELD_PUBLIC_KEY = "publicKey"
        private const val FIELD_SIGNATURE = "signature"
        private const val FIELD_WALLET_PROOF = "walletProof"
        private const val FIELD_CREATE = "create"
        private const val FIELD_CREATED = "created"
        private const val FIELD_TOKEN = "token"
        private const val FIELD_USER_ID = "userId"
        private const val FIELD_FOUND = "found"
        private const val FIELD_WALLET_PUBKEY = "walletPubkey"

        private const val CHALLENGE_EXPIRED = "challenge_expired"
        private const val ACCOUNT_HAS_OTHER_KEY = "account_has_other_key"
        private const val BAD_SIGNATURE = "bad_signature"
        private const val BAD_TOKEN = "bad_token"
        private const val KEY_IN_USE = "key_in_use"
        private const val KEY_HAS_PAID_ACCOUNT = "key_has_paid_account"
        private const val NEEDS_OWNER_CHECK = "needs_owner_check"
        private const val CHANGED_MEANWHILE = "changed_meanwhile"
        private const val UNKNOWN_KEY = "unknown_key"
        private const val SIGNUP_LIMIT = "signup_limit"

        private const val DOWN = -1
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val TOO_MANY = 429
        private val CLIENT_ERRORS = 400..499
        private const val SERVER_ERRORS = 500

        private const val TRIES = 2
        private const val SEED_BYTES = 64
        private const val SHORT_KEY_CHARS = 20
        private const val DAY_MILLIS = 86_400_000L
        private const val REFUSAL_PAUSE_MILLIS = 60_000L
        private const val WALLET_ANSWER_MILLIS = 20_000L
        private const val LAST_READ_MILLIS = 1_500L
        private val FINGERPRINT_FORM = Regex("[0-9a-f]{64}")
    }
}
