package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import co.electriccoin.zcash.global.MainProcess
import co.electriccoin.zcash.spackle.process.ProcessNameCompat
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import co.electriccoin.zcash.ui.screen.ai.AiPreferences
import java.io.File
import java.security.KeyStoreException

private const val TAG = "ZchatPreferences"

/** Plain file counting, per store name, the launches in a row whose open failed with a temporary error. */
internal const val ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME = "zchat_encrypted_store_open_failures"

/**
 * Marker in the file name of a recoverable copy kept before an unreadable store is recreated:
 * "<store>__unreadable_<epochMillis>". The copy holds the store's raw entries — the Tink keyset and
 * ciphertext exactly as they were on disk, never plaintext — so it can be restored if the key comes back.
 */
internal const val UNREADABLE_STORE_COPY_MARKER = "__unreadable_"

/** Launches in a row a temporary error may stop before it is treated as permanent (no endless crash loop). */
internal const val MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR = 3

/**
 * F10 (fix-all 2.13.0, chat copy #16): how long each pause of a restore holds the kept copy back, counted from the
 * failed restore. The first pause offers it again at the next launch whose store opens at once (as before); the second
 * not before a day later. The failed restore after the last pause PARKS the copy ([RESTORE_PARKED_PREFIX]): it is not
 * offered by itself any more, and More → Advanced Settings → Restore old chats offers it again
 * ([KeptChatStoreCopies.retryHeld]). Under a Keystore that fails on and off, an episode therefore offers the copy at
 * most four times (the first offer, the extra try, one after each pause), spread over at least a day.
 */
internal val PAUSE_REOFFER_DELAYS_MS = longArrayOf(0L, 24L * 60 * 60 * 1000)

/** Waits between the in-launch attempts to open a store (a flaky Keystore usually answers on a retry). */
private val OPEN_RETRY_DELAYS_MS = longArrayOf(50L, 150L)

private const val KEYSTORE_OUT_SEAM_MESSAGE = "debug fault seam KEYSTORE_OUT: the Keystore did not answer"
private const val KEYSTORE_OUT_OTHER_SEAM_MESSAGE = "debug fault seam KEYSTORE_OUT_OTHER_STORES: the Keystore did not answer"

/**
 * ZC-733-A: whether the debug seam [FaultSeam.KEYSTORE_OUT_OTHER_STORES], armed with [value], fails the open of [name]:
 * 1 the contact book, 2 the picture index, 3 the AI store, any other value all three. The chat stores never.
 */
private fun otherStoreSeamFails(
    name: String,
    value: Long?,
): Boolean {
    val stores = listOf(ContactBookImpl.PREFS_NAME, AvatarStore.PREFS_NAME, AiPreferences.PREFS_NAME)
    return when (value) {
        1L -> name == stores[0]
        2L -> name == stores[1]
        3L -> name == stores[2]
        else -> name in stores
    }
}

/**
 * Opens one named EncryptedSharedPreferences store for [ZchatPreferencesImpl].
 *
 * [open] builds the Keystore master key and the encrypted store (it throws when either fails).
 * [plainPrefs] returns the raw, PLAIN SharedPreferences view of a file name — for an encrypted
 * store that view holds only the Tink keyset and ciphertext entries.
 *
 * P2c KR-1 — a store holding chat keys is never erased by a phone-security error:
 *  - every failure is retried inside the launch first;
 *  - a failure that still looks temporary stops the launch with the data untouched on disk; only
 *    when it has stopped [MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR] launches in a row is it treated
 *    as permanent, so a broken Keystore cannot lock the user out of the app forever;
 *  - a permanent failure (the keyset no longer decrypts or parses — e.g. the files arrived on a new
 *    phone without their Keystore key) recreates the store, but only after an exact copy of every
 *    entry was committed to a separate "__unreadable_" file. If that copy fails, nothing is cleared.
 *
 * Every way it gives up throws [EncryptedStoreUnavailableException], which the start-up check and the start-up
 * guard turn into the start-up safety screen with Try again (R2-3) instead of ZCHAT stopping with no message.
 *
 * R3-3: only a start the user sees ([userVisible], the start-up check behind the safety screen) counts a launch or
 * recreates a store. A background start (boot, a scheduled job) that cannot open a store stops with the data
 * untouched and nothing counted, so it can never bring the recreate closer without the user seeing the safety screen.
 * After a recreate the start-up screen offers the kept copy back ([KeptChatStoreCopies]).
 *
 * ZC-707: a counted launch is a PROCESS in which the start-up check, or an act of the user after it, met the failure.
 * A store is counted at most once per process ([StoreOpenLaunch.countOnce], in [env]): the chat storage is asked for
 * again within one process (Try again, the 15-minute alarm, an incoming message), and every ask used to add one, so a
 * single outage could use up all [MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR] counts behind one safety screen. And a
 * store this process has already opened is never counted, copied, cleared or recreated by a later open in the same
 * process, whatever the error says: the object handed out earlier is still in use, and it would go on writing
 * entries locked with keys that are no longer in the file. A lost key of a store NOT opened before in this process is
 * still acted on at once. The default [env] is new per call (every call counts, nothing was opened before); the app
 * passes [appStoreOpenEnv].
 *
 * R4-5 / R5-3: a copy the user put back that cannot be read either ends the offer, so the same question is not
 * asked at every launch — after one more try when nothing ever said a key was lost ([answerFailedRestore]).
 * B5: when nothing ever said a key was lost, the offer is only paused ([RESTORE_PAUSED_PREFIX]) — never ended —
 * and comes back once the phone's security system answers again.
 *
 * C6 (R6-4 review finding 13): every kept copy, and every pause, is tagged with the wallet that owned the chats when
 * it was made ([copyOwner], by default the chat owner record — [chatOwnerOf]), so it is offered only to that wallet
 * ([KeptChatStoreCopies]). A store that belongs to no live wallet (a set-aside bundle) passes null: no tag.
 *
 * ZC-705: no NEW file is kept with its key material in the clear, and nothing that exists is rewritten. The encryption
 * library itself falls back to an unencrypted key set when its Keystore check fails while it makes a new file, and
 * returns normally; so after each of the two opens below the two key entries are read ([KeysetGuard]). A file that held
 * nothing before the call and now has an unprotected entry is put back as it was (removed, or emptied when it was
 * there and empty) and the try fails, so the retries and the count of failed starts run as for any temporary error.
 * In a process that is not known to be the main one ([StoreOpenEnv.mayChangeFiles]) nothing is removed or written:
 * the store is kept as made and counted. A file that existed before the call (also one 2.12.4 wrote with an
 * unprotected entry) is never removed, emptied or rewritten, whatever its key entries are: it is counted once per
 * process by its kind and used as before. ONE EXCEPTION, the three-start rule: when a chat store's call reaches the
 * limit of [MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR] starts in a row BY ITS COUNT and the store made after it comes out
 * unprotected again, it is kept, counted and logged, and the other new chat stores of that process are then kept at
 * their first such try ([StoreOpenEnv.keepPlainAfterLimit], set by the chat stores' createEncryptedPrefs only). Why: a
 * Keystore that makes keys and cannot load them falls back at every try, and without the rule such a phone would stay
 * on the Try-again screen for ever with a wallet it could open before the update. Every file is opened through
 * [openEncryptedFile], which opens a file that exists by key name and makes no Keystore key for it; the wallet SDK's
 * own opener still makes the master-key call under the same key name at every wallet start.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun openEncryptedStore(
    name: String,
    open: () -> SharedPreferences,
    plainPrefs: (String) -> SharedPreferences,
    nowMillis: () -> Long = System::currentTimeMillis,
    sleep: (Long) -> Unit = Thread::sleep,
    userVisible: Boolean = true,
    copyOwner: () -> String? = { chatOwnerOf(plainPrefs) },
    env: StoreOpenEnv = StoreOpenEnv(),
): SharedPreferences {
    // F0 debug seam (F10's on/off Keystore phone check): every open fails as the phone's Keystore not answering
    // would. Always "not armed" in a release build (FaultSeams).
    val openStore: () -> SharedPreferences = {
        if (FaultSeams.isArmed(FaultSeam.KEYSTORE_OUT)) throw KeyStoreException(KEYSTORE_OUT_SEAM_MESSAGE)
        // ZC-733-A: the contact book, the picture index or the AI store (its value names which); never a chat store.
        if (FaultSeams.isArmed(FaultSeam.KEYSTORE_OUT_OTHER_STORES) &&
            otherStoreSeamFails(name, FaultSeams.valueOf(FaultSeam.KEYSTORE_OUT_OTHER_STORES))
        ) {
            throw KeyStoreException(KEYSTORE_OUT_OTHER_SEAM_MESSAGE)
        }
        open()
    }

    // ZC-705 (spec 3.4.1): the guard on the two key entries, around both opens. [afterLimitByCount]: this is the store
    // made after the limit of failed starts was reached by the count in this call (the try the one exception keeps).
    fun openGuarded(afterLimitByCount: Boolean): SharedPreferences {
        val wasNew = plainPrefs(name).all.isEmpty()
        val fileWasThere = env.storeFileExists?.invoke(name) ?: true
        val store = openStore()
        if (!KeysetGuard.hasPlainEntry(plainPrefs(name))) return store
        val kind = KeysetGuard.kindOf(name)
        when {
            !wasNew -> {
                if (env.launch.notePlainKeyset(name, kind)) guardError(env.launch, KeysetGuard.keptAsItIsLine(kind))
            }
            env.keepPlainAfterLimit && (afterLimitByCount || env.launch.keptAfterLimit()) -> {
                env.launch.notePlainKeyset(name, kind)
                guardError(env.launch, KeysetGuard.keptAfterLimitLine(kind))
                env.launch.noteKeptAfterLimit()
            }
            !env.mayChangeFiles -> {
                env.launch.notePlainKeyset(name, kind)
                guardError(env.launch, KeysetGuard.notMainProcessLine(kind))
            }
            else -> {
                if (!undoNewStoreFile(name, fileWasThere, plainPrefs, env)) {
                    guardError(env.launch, KeysetGuard.notUndoneLine(kind))
                }
                throw KeysetNotWrappedException("a new file of $kind came out with an unprotected key entry")
            }
        }
        return store
    }
    val failures = plainPrefs(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)
    var last: Exception? = null
    for (attempt in 0..OPEN_RETRY_DELAYS_MS.size) {
        try {
            val store = openGuarded(afterLimitByCount = false)
            // R4-5: it opened, so a copy the user put back is readable after all; the marks that decide go.
            // R5-3: only when a copy WAS put back. An emptied store recreated after a failure opens too (the
            // Keystore answers again and it gets a fresh keyset), and that says nothing about the old keyset.
            // Clearing the marks there lost the "the key is gone" screen and the one extra restore alike.
            // D6 (REVIEW_7C F18): the restore marks are the owning wallet's ([ownerMarkKeys]). The owner is read only
            // when some restore waits for its verdict; otherwise nothing below depends on it.
            val owner = if (failures.all.keys.any { it.startsWith(RESTORE_TRIED_PREFIX) }) copyOwner() else null
            val tried = ownerMarkKeys(RESTORE_TRIED_PREFIX, owner, name)
            val decided = tried.any { failures.getBoolean(it, false) }
            // F10: the held copies of this store whose hold is over, and whether a paused one is still held.
            val held = liftHeldCopies(name, failures.all, decided, owner, nowMillis)
            // B5: the phone's security system answered at the first tries of a launch, which is what a paused
            // restore waits for, so the kept copy is offered again (once: a failed restore of it pauses again).
            // F10: the pause's explanation stays while its copy is still held and the user has not read it yet, so
            // the paused screen is said before ZCHAT opens without the copy; its copy is held by its own marks.
            val pauseGroupEnds =
                decided || !held.pausedStillHeld || failures.getBoolean(RESTORE_PAUSE_EXPLAINED_PREFIX + name, false)
            val stale =
                (
                    listOf(name) +
                        (
                            tried + ownerMarkKeys(RESTORE_RETRIED_PREFIX, owner, name) + (RESTORE_FAILED_PREFIX + name) +
                                ownerMarkKeys(RESTORE_PAUSES_PREFIX, owner, name)
                        ).takeIf { decided }
                            .orEmpty() +
                        listOf(
                            RESTORE_PAUSED_PREFIX + name,
                            RESTORE_PAUSED_OWNER_PREFIX + name,
                            RESTORE_PAUSE_EXPLAINED_PREFIX + name,
                        ).takeIf { pauseGroupEnds }
                            .orEmpty() +
                        held.released.flatMap { heldCopyMarkKeys(it) }
                ).filter { failures.contains(it) }
            if (stale.isNotEmpty()) {
                val editor = failures.edit()
                stale.forEach { editor.remove(it) }
                editor.commit()
            }
            env.launch.markOpened(name)
            return store
        } catch (e: Exception) {
            last = e
            Log.w(TAG, "Encrypted store $name did not open (attempt ${attempt + 1}, ${e.javaClass.simpleName})")
            if (isPermanentKeyLoss(e)) break
            if (attempt < OPEN_RETRY_DELAYS_MS.size) sleep(OPEN_RETRY_DELAYS_MS[attempt])
        }
    }
    val cause = checkNotNull(last)

    // ZC-707: never behind a live object. No count, no copy, no clear, no recreate.
    if (env.launch.wasOpened(name)) {
        throw EncryptedStoreUnavailableException(
            "Encrypted storage $name was opened earlier in this process and cannot be opened again now; " +
                "its data is kept.",
            cause
        )
    }

    if (!userVisible) {
        throw EncryptedStoreUnavailableException(
            "Encrypted storage $name could not be opened by a background start; its data is kept and nothing was " +
                "counted (a start the user sees decides).",
            cause
        )
    }

    if (env.treatFailureAsFinal && !isPermanentKeyLoss(cause)) {
        // ZC-733-B: a change of wallet hands the store over: no count, no stop; an exact copy first, as for a lost key.
        Log.w(TAG, "Encrypted store $name did not open at a change of wallet; it is set aside with a copy")
    } else if (!isPermanentKeyLoss(cause)) {
        // ZC-707: one count per process. Phone check S1 counts this line by its fixed words.
        if (!env.launch.countOnce(name)) {
            Log.w(TAG, "Encrypted store $name did not open; already counted in this start, nothing is written")
            throw EncryptedStoreUnavailableException(
                "Encrypted storage $name is temporarily unavailable; this start was already counted; " +
                    "its data is kept.",
                cause
            )
        }
        val launchesStopped = failures.getInt(name, 0) + 1
        failures.edit().putInt(name, launchesStopped).commit()
        if (launchesStopped < MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR) {
            throw EncryptedStoreUnavailableException(
                "Encrypted storage $name is temporarily unavailable ($launchesStopped launch(es) in a row); " +
                    "its data is kept for the next launch.",
                cause
            )
        }
        Log.e(TAG, "Encrypted store $name failed $launchesStopped launches in a row, treating it as unreadable", cause)
    } else {
        Log.e(TAG, "Encrypted store $name can no longer be decrypted (${cause.javaClass.simpleName})", cause)
    }

    val owner = copyOwner()
    // F10 (review break 9): one reading of the clock names the copy and stamps its hold.
    val now = nowMillis()
    val keptCopy = keepCopyThenClear(name, plainPrefs, now, cause, owner, env.copyNames)
    if (ownerMarkKeys(RESTORE_TRIED_PREFIX, owner, name).any { failures.getBoolean(it, false) }) {
        answerFailedRestore(name, failures, keptCopy, cause, owner, now)
    }
    try {
        // ZC-705: the one try the three-start rule keeps unprotected, and only when the limit was reached by the count.
        val store = openGuarded(afterLimitByCount = !isPermanentKeyLoss(cause) && !env.treatFailureAsFinal)
        failures.edit().remove(name).commit()
        env.launch.markOpened(name)
        return store
    } catch (e: Exception) {
        throw EncryptedStoreUnavailableException(
            "CRITICAL: Cannot create encrypted storage for $name. " +
                "E2E keys cannot be stored safely. Device may not support Android Keystore.",
            e
        )
    }
}

/**
 * The user put a kept copy of [name] back and it could not be read either. R4-5 called the key gone for good at
 * once: offering the same copy at every launch asked a question that could never be answered, and kept one more
 * copy file each time.
 *
 * R5-3 (round-4 report section 5C): that verdict is right for a keyset that does not decrypt, and wrong for a
 * phone whose security system simply did not answer — a state that ends by itself, and one that a restore plus
 * [MAX_LAUNCHES_STOPPED_BY_TEMPORARY_ERROR] launches in a row walked straight into. Such a failure now buys one
 * more round: the copy just kept is left unanswered, so the start-up screen offers it back one more time, and
 * [RESTORE_RETRIED_PREFIX] records that this was the extra one. Fails that round too and the verdict stands, so
 * the loop R4-5 closed is still closed. The extra round is given back only when a put-back copy really opened.
 *
 * B5 (Grok Q10, GPT Q10, C-2.3): "the key is gone for good" is said only when the phone said so — the keyset does
 * not decrypt or parse ([isPermanentKeyLoss]). A failure that only says the phone's security system did not
 * answer, after the extra round is spent, PAUSES the restore instead ([RESTORE_PAUSED_PREFIX]): the copy just kept
 * stays unanswered, the start-up screen stops offering it while the pause lasts (so the loop R4-5 closed stays
 * closed), and the first launch whose open succeeds at once lifts the pause, so the copy is offered one more time.
 *
 * No file is deleted either way: every kept copy stays on the phone, the verdict only stops offering it.
 *
 * C6 (R6-4 review finding 13): the pause is tagged with [owner], the wallet whose chats it holds back
 * ([RESTORE_PAUSED_OWNER_PREFIX]); another wallet is neither told about it nor held back by it.
 *
 * D6 (REVIEW_7C F18): the restore_tried and restore_retried marks are [owner]'s too ([ownerMarkKeys]), so a wallet
 * never spends, nor inherits, another wallet's extra restore. An old per-store mark is read as [owner]'s and
 * rewritten under [owner]'s key here.
 *
 * F10 (fix-all 2.13.0, chat copy #16): the pauses of an episode are counted ([RESTORE_PAUSES_PREFIX], [owner]'s), and
 * each one holds the copy just kept by its own marks ([RESTORE_HELD_PREFIX], stamped [now]) until
 * [PAUSE_REOFFER_DELAYS_MS] has passed. The failed restore after the last pause parks that copy instead
 * ([RESTORE_PARKED_PREFIX]): it stays unanswered and is never called gone, it is not offered by itself any more, and
 * the start-up screen says so once. The hold is the copy's, never the store's, so chats a new outage sets aside
 * meanwhile are offered as usual. A restore that opens ends the episode (the count and the holds of the store go).
 */
@Suppress("LongParameterList")
private fun answerFailedRestore(
    name: String,
    failures: SharedPreferences,
    keptCopy: String?,
    cause: Exception,
    owner: String?,
    now: Long,
) {
    val editor = failures.edit()
    val tried = ownerMarkKeys(RESTORE_TRIED_PREFIX, owner, name)
    val retried = ownerMarkKeys(RESTORE_RETRIED_PREFIX, owner, name)
    if (keptCopy != null && !isPermanentKeyLoss(cause) && retried.none { failures.getBoolean(it, false) }) {
        Log.w(
            TAG,
            "Encrypted store $name could not be read after its kept copy was put back " +
                "(${cause.javaClass.simpleName}); offering it once more"
        )
        tried.forEach { editor.remove(it) }
        retried.drop(1).forEach { editor.remove(it) }
        editor.putBoolean(retried.first(), true).commit()
        return
    }
    if (!isPermanentKeyLoss(cause)) {
        tried.forEach { editor.remove(it) }
        if (keptCopy != null) {
            val pauses = ownerMarkKeys(RESTORE_PAUSES_PREFIX, owner, name)
            val pause = (pauses.mapNotNull { failures.all[it] as? Int }.maxOrNull() ?: 0) + 1
            pauses.drop(1).forEach { editor.remove(it) }
            editor.putInt(pauses.first(), pause)
            editor.putLong(RESTORE_HELD_PREFIX + keptCopy, now)
            if (pause <= PAUSE_REOFFER_DELAYS_MS.size) {
                Log.w(
                    TAG,
                    "Encrypted store $name could not be read after its kept copy was put back " +
                        "(${cause.javaClass.simpleName}); the copy is kept and offered again later (pause $pause)"
                )
                editor.putLong(RESTORE_OFFER_AFTER_PREFIX + keptCopy, now + PAUSE_REOFFER_DELAYS_MS[pause - 1])
                editor.putBoolean(RESTORE_PAUSED_PREFIX + name, true).remove(RESTORE_PAUSE_EXPLAINED_PREFIX + name)
                if (owner != null) {
                    editor.putString(RESTORE_PAUSED_OWNER_PREFIX + name, owner)
                } else {
                    editor.remove(RESTORE_PAUSED_OWNER_PREFIX + name)
                }
            } else {
                Log.w(
                    TAG,
                    "Encrypted store $name could not be read after its kept copy was put back " +
                        "(${cause.javaClass.simpleName}); the copy is kept and no longer offered by itself (parked)"
                )
                editor
                    .remove(RESTORE_OFFER_AFTER_PREFIX + keptCopy)
                    .putBoolean(RESTORE_PARKED_PREFIX + keptCopy, true)
                    .remove(RESTORE_PARK_EXPLAINED_PREFIX + keptCopy)
            }
        }
        editor.commit()
        return
    }
    Log.e(TAG, "Encrypted store $name could not be read after its kept copy was put back; the key is gone for good")
    if (keptCopy != null) editor.putBoolean(HANDLED_COPY_PREFIX + keptCopy, true)
    (tried + retried + ownerMarkKeys(RESTORE_PAUSES_PREFIX, owner, name)).forEach { editor.remove(it) }
    editor.putBoolean(RESTORE_FAILED_PREFIX + name, true).commit()
}

/**
 * An encrypted chat store could not be opened in this launch (R2-3). Its data is still on disk; the start-up
 * safety screen asks the user to try again instead of the app stopping with no message.
 */
open class EncryptedStoreUnavailableException(
    message: String,
    cause: Throwable
) : IllegalStateException(message, cause)

/**
 * ZC-733-A: the contact book, the picture index or the AI store could not be opened ([LazyEncryptedStore.get],
 * [LazyEncryptedStore.tryAgain]); its cause is the opener's error. No use of these stores stops the start, and the
 * app's last-resort handler does not take this for the chat stores' Try-again screen, whose text would be false here.
 */
class OtherStoreUnavailableException(
    message: String,
    cause: Throwable
) : EncryptedStoreUnavailableException(message, cause)

/** True for a failure no retry can fix: the stored keyset no longer decrypts or no longer parses. */
internal fun isPermanentKeyLoss(e: Throwable): Boolean {
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth < 10) {
        if (t is javax.crypto.BadPaddingException) return true // incl. AEADBadTagException: wrong key
        if (t is java.io.CharConversionException) return true // keyset pref is not valid hex
        if (t.javaClass.simpleName in PERMANENT_FAILURE_CLASS_NAMES) return true
        t = t.cause
        depth++
    }
    return false
}

// Matched by simple name: the Tink protobuf class is shaded and the Keystore class is Android-only.
private val PERMANENT_FAILURE_CLASS_NAMES = setOf("InvalidProtocolBufferException", "KeyPermanentlyInvalidatedException")

/** F10: the held copies of one store a launch whose store opened at once lets go, and whether a paused one stays held. */
private class HeldCopies(
    val released: List<String>,
    val pausedStillHeld: Boolean,
)

/**
 * F10 (fix-all 2.13.0, chat copy #16): the store [name] opened at the first tries of a launch. A paused copy of it is
 * let go (offered again) once its wait is over ([RESTORE_OFFER_AFTER_PREFIX]), or when the phone's clock reads earlier
 * than the pause itself (a clock set back never holds a copy for ever), or when it has no wait recorded. A parked copy
 * is never let go here. When [decided] (a copy the user put back opened), the episode is over: every copy of [owner]
 * held for the store is let go, parked ones too. The clock is read only when some copy of the store is held.
 */
private fun liftHeldCopies(
    name: String,
    all: Map<String, *>,
    decided: Boolean,
    owner: String?,
    nowMillis: () -> Long,
): HeldCopies {
    val copies = heldCopiesOf(all, name)
    if (copies.isEmpty()) return HeldCopies(emptyList(), pausedStillHeld = false)
    val now = nowMillis()
    val released = mutableListOf<String>()
    var pausedStillHeld = false
    for (copy in copies) {
        val parked = all[RESTORE_PARKED_PREFIX + copy] == true
        val over =
            if (decided) {
                isOwnedBy(all[COPY_OWNER_PREFIX + copy], owner)
            } else {
                !parked && pauseIsOver(all, copy, now)
            }
        when {
            over -> released += copy
            !parked -> pausedStillHeld = true
        }
    }
    return HeldCopies(released, pausedStillHeld)
}

private fun pauseIsOver(
    all: Map<String, *>,
    copy: String,
    now: Long,
): Boolean {
    val heldAt = all[RESTORE_HELD_PREFIX + copy] as? Long
    val offerAfter = all[RESTORE_OFFER_AFTER_PREFIX + copy] as? Long
    return heldAt == null || offerAfter == null || now >= offerAfter || now < heldAt
}

/** F10: the kept copies of [store] that hold a [RESTORE_HELD_PREFIX] mark, whatever wallet they belong to. */
private fun heldCopiesOf(
    all: Map<String, *>,
    store: String,
): List<String> =
    all.keys
        .filter { it.startsWith(RESTORE_HELD_PREFIX) }
        .map { it.removePrefix(RESTORE_HELD_PREFIX) }
        .filter { it.substringBefore(UNREADABLE_STORE_COPY_MARKER) == store && it.contains(UNREADABLE_STORE_COPY_MARKER) }

/** F10: every mark that holds [copy] back (a pause or a park). */
private fun heldCopyMarkKeys(copy: String): List<String> = HELD_MARK_PREFIXES.map { it + copy }

/** F10: the prefixes of the marks that hold a kept copy back (ZC-733-B: also what a wipe keeps for a spared copy). */
private val HELD_MARK_PREFIXES =
    listOf(
        RESTORE_HELD_PREFIX,
        RESTORE_OFFER_AFTER_PREFIX,
        RESTORE_PARKED_PREFIX,
        RESTORE_PARK_EXPLAINED_PREFIX,
    )

/** C6: whether something tagged with [tag] belongs to [owner]; untagged counts as the current wallet's. */
private fun isOwnedBy(
    tag: Any?,
    owner: String?,
): Boolean = tag !is String || tag.isEmpty() || tag == owner

/**
 * Keeps an exact copy of [name] and empties it. Returns the copy's file name, or null when there was nothing to copy.
 *
 * C6 (R6-4 review finding 13): the copy is tagged with [owner] ([COPY_OWNER_PREFIX]) before it is written, so there
 * is never a copy on disk without the tag of the wallet whose chats it holds.
 *
 * ZC-709: copies of the same chats no longer pile up. A store that holds nothing of the user's (no entry, or only its
 * own key entries) is emptied with no copy. When [copyNames] lists a kept copy of this store whose raw entries equal
 * the store's, entry for entry, and that belongs to the same wallet (both untagged counts as the same), that copy is
 * used again: one commit to the marks file makes it unanswered (its handled, declined and hold marks go), and only
 * when that commit is on disk is the store emptied. Otherwise a new copy is written, as before. Nothing here ever
 * deletes or empties a copy.
 */
@Suppress("LongParameterList")
private fun keepCopyThenClear(
    name: String,
    plainPrefs: (String) -> SharedPreferences,
    now: Long,
    cause: Exception,
    owner: String?,
    copyNames: (() -> Collection<String>)?,
): String? {
    val raw = plainPrefs(name)
    val entries = raw.all
    if (holdsNoChats(entries)) {
        raw.edit().clear().commit()
        return null
    }
    val again = copyNames?.let { names -> sameChatsCopy(name, entries, owner, names(), plainPrefs) }
    if (again != null) {
        val marks = plainPrefs(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)
        val editor = marks.edit().remove(HANDLED_COPY_PREFIX + again).remove(DISMISSED_COPY_PREFIX + again)
        heldCopyMarkKeys(again).forEach { editor.remove(it) }
        if (editor.commit()) {
            Log.w(TAG, "Kept copy of unreadable store $name holds the same entries; it is used again before recreating it")
            raw.edit().clear().commit()
            return again
        }
        Log.w(TAG, "The marks of the same kept copy of $name could not be written; keeping a new copy")
    }
    var copyName: String? = null
    if (entries.isNotEmpty()) {
        copyName = "$name$UNREADABLE_STORE_COPY_MARKER$now"
        if (!tagCopyOwner(plainPrefs(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME), copyName, owner)) {
            throw EncryptedStoreUnavailableException(
                "Encrypted storage $name is unreadable and the owner of its copy could not be written; nothing was cleared.",
                cause
            )
        }
        val copy = plainPrefs(copyName)
        val editor = copy.edit().clear()
        entries.forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, (value as Set<String>).toMutableSet())
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                null -> Unit
                else -> editor.putString(key, value.toString())
            }
        }
        if (!editor.commit() || copy.all.size != entries.filterValues { it != null }.size) {
            throw EncryptedStoreUnavailableException(
                "Encrypted storage $name is unreadable and its copy could not be written; nothing was cleared.",
                cause
            )
        }
        Log.w(TAG, "Kept a copy of unreadable store $name (${entries.size} entries) before recreating it")
    }
    raw.edit().clear().commit()
    return copyName
}

/**
 * ZC-1031: an unreadable store that belongs to nobody is set aside as a kept copy with no owner mark (the AI store: it
 * may hold the only key to AI credit and may become readable later), then emptied; the caller erases its file. Throws,
 * having cleared nothing, when the copy cannot be written. Returns the copy's name, or null when it held nothing.
 */
internal fun setAsideUnreadableStore(
    name: String,
    plainPrefs: (String) -> SharedPreferences,
    cause: Exception,
): String? = keepCopyThenClear(name, plainPrefs, System.currentTimeMillis(), cause, owner = null, copyNames = null)

/**
 * ZC-709: the kept copy of [store] among [copyNames] that holds exactly [entries] and belongs to [owner] (a copy with no
 * owner mark, or an empty one, matches only when there is no owner), the oldest first; or null.
 */
private fun sameChatsCopy(
    store: String,
    entries: Map<String, *>,
    owner: String?,
    copyNames: Collection<String>,
    plainPrefs: (String) -> SharedPreferences,
): String? {
    val marks = plainPrefs(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME).all
    val wallet = owner?.takeIf { it.isNotEmpty() }
    return copyNames
        .filter { it.contains(UNREADABLE_STORE_COPY_MARKER) && it.substringBefore(UNREADABLE_STORE_COPY_MARKER) == store }
        .filter { (marks[COPY_OWNER_PREFIX + it] as? String)?.takeIf { tag -> tag.isNotEmpty() } == wallet }
        .sortedBy { it.substringAfterLast(UNREADABLE_STORE_COPY_MARKER).toLongOrNull() ?: 0L }
        .firstOrNull { plainPrefs(it).all == entries }
}

/** File names (without ".xml") of the recoverable copies among a shared_prefs directory listing. */
internal fun unreadableStoreCopyNames(fileNames: Collection<String>): List<String> =
    fileNames
        .filter { it.endsWith(".xml") }
        .map { it.removeSuffix(".xml") }
        .filter { it.contains(UNREADABLE_STORE_COPY_MARKER) }

/**
 * R3-3: whether this process opens the chat stores for a start the user sees. The start-up check behind the
 * safety screen sets it just before it opens them; until then (a boot receiver, a scheduled job, a service) a store
 * that cannot be opened is left untouched and not counted (see [openEncryptedStore]).
 */
object ChatStoreStart {
    @Volatile
    var userVisible: Boolean = false

    /** ZC-707: what this process remembers about its opens. The opener's only memory that lives once per process. */
    val launch = StoreOpenLaunch()
}

/**
 * ZC-707: what one process remembers about its opens of encrypted stores ([openEncryptedStore]): which stores it
 * has counted as a failed start, and which it has opened.
 */
class StoreOpenLaunch {
    private val lock = Any()
    private val counted = mutableSetOf<String>()
    private val opened = mutableSetOf<String>()

    /** ZC-705: the stores met with an unprotected key entry in this process, each once, with its kind. */
    private val plainKeysets = mutableMapOf<String, String>()

    /** ZC-705: the one exception kept a new chat store in this process (the three-start rule). */
    private var keptPlainAfterLimit = false

    /** ZC-705: the guard's last error-level lines in this process (a kind, never a file name with a tag). */
    private val guardLines = ArrayDeque<String>()

    /** True the first time it is asked for [store] in this process: this failed start may be counted. */
    fun countOnce(store: String): Boolean = synchronized(lock) { counted.add(store) }

    fun markOpened(store: String) {
        synchronized(lock) { opened.add(store) }
    }

    /** True when an open of [store] succeeded in this process: an object for it may be in use. */
    fun wasOpened(store: String): Boolean = synchronized(lock) { store in opened }

    /** ZC-705: counts [store] as met with an unprotected key entry; true the first time in this process. */
    fun notePlainKeyset(
        store: String,
        kind: String,
    ): Boolean =
        synchronized(lock) {
            if (store in plainKeysets) {
                false
            } else {
                plainKeysets[store] = kind
                true
            }
        }

    /** ZC-705: how many stores of [kind] were met with an unprotected key entry in this process. */
    fun plainKeysetCount(kind: String): Int = synchronized(lock) { plainKeysets.values.count { it == kind } }

    /** ZC-705: the one exception kept a new chat store unprotected; the other new chat stores follow in this process. */
    fun noteKeptAfterLimit() {
        synchronized(lock) { keptPlainAfterLimit = true }
    }

    fun keptAfterLimit(): Boolean = synchronized(lock) { keptPlainAfterLimit }

    /** ZC-705: keeps one of the guard's error-level lines (also logged). */
    fun noteGuardLine(line: String) {
        synchronized(lock) {
            guardLines.addLast(line)
            while (guardLines.size > MAX_GUARD_LINES) guardLines.removeFirst()
        }
    }

    /** ZC-705: the guard's error-level lines of this process, oldest first. */
    fun guardLines(): List<String> = synchronized(lock) { guardLines.toList() }

    /** A new process, for tests only. Nothing in the app calls it. */
    internal fun forgetAll() {
        synchronized(lock) {
            counted.clear()
            opened.clear()
            plainKeysets.clear()
            keptPlainAfterLimit = false
            guardLines.clear()
        }
    }

    private companion object {
        const val MAX_GUARD_LINES = 32
    }
}

/**
 * ZC-707: what an open of an encrypted store is told about the process it runs in. Every field has a default that
 * means "as before": a [launch] of its own, so a call with the default counts every time and knows no earlier open.
 */
class StoreOpenEnv {
    var launch: StoreOpenLaunch = StoreOpenLaunch()

    /**
     * ZC-705: the one exception (the three-start rule) may keep a new store with an unprotected key entry. Set by the
     * chat stores' createEncryptedPrefs only; never the recovery-phrase store, a set-aside bundle, the contact book,
     * the picture index or the AI store.
     */
    var keepPlainAfterLimit: Boolean = false

    /** ZC-705: the guard may remove or empty a new file it refused (true only in the main process, in the app). */
    var mayChangeFiles: Boolean = true

    /** ZC-705: removes a store's file; null: a JVM file is a map, and "put back" empties it. */
    var removeStoreFile: ((String) -> Boolean)? = null

    /** ZC-705: whether a store's file (or its .bak twin) is there; null: unknown, taken as there. */
    var storeFileExists: ((String) -> Boolean)? = null

    /**
     * ZC-709: the names of the kept copies on the phone, so a set-aside can use a copy of the same chats again; null:
     * every set-aside writes a new copy, as before.
     */
    var copyNames: (() -> Collection<String>)? = null

    /**
     * ZC-733-B: this open is a change of wallet (the user's own act), whose store must be handed over: a temporary error
     * does not count and does not stop, it goes on to the copy step as a lost key does (an exact copy first, then an
     * empty store). Set by LazyEncryptedStore.forWalletChange only.
     */
    var treatFailureAsFinal: Boolean = false
}

/**
 * ZC-707: the environment of the app's own opens. The one place it is built.
 *
 * ZC-705: the guard may change files only when [processName] gave a name and ZC-706's [MainProcess.isMain] says it is
 * the main process; a name that could not be read counts as NOT main here (for a remove the safe side is the other one
 * than for the app start). [keepPlainAfterLimit] is set true by the chat stores' createEncryptedPrefs only.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun appStoreOpenEnv(
    context: Context,
    processName: (Context) -> String? = ::readProcessName,
    keepPlainAfterLimit: Boolean = false,
): StoreOpenEnv {
    val process =
        try {
            processName(context)
        } catch (e: Exception) {
            null
        }
    return StoreOpenEnv().apply {
        launch = ChatStoreStart.launch
        this.keepPlainAfterLimit = keepPlainAfterLimit
        mayChangeFiles = process != null && MainProcess.isMain(process, context.packageName)
        removeStoreFile = { name -> context.deleteSharedPreferences(name) }
        storeFileExists = { name -> storeFileIsThere(context, name) }
        copyNames = { appCopyNames(context) }
    }
}

/** ZC-705: the process's name, or null when it cannot be read (ProcessNameCompat can throw on Android 8.1). */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun readProcessName(context: Context): String? =
    try {
        ProcessNameCompat.getProcessName(context)
    } catch (e: Exception) {
        null
    }

/** ZC-709: the kept copies of the app's shared_prefs folder (an empty list when it cannot be read). */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun appCopyNames(context: Context): List<String> =
    try {
        unreadableStoreCopyNames(File(context.applicationInfo.dataDir, "shared_prefs").list()?.toList().orEmpty())
    } catch (e: Exception) {
        emptyList()
    }

/** ZC-705: whether `<name>.xml` or its `.xml.bak` twin is in the app's shared_prefs folder; true when it cannot tell. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun storeFileIsThere(
    context: Context,
    name: String,
): Boolean =
    try {
        val folder = File(context.applicationInfo.dataDir, "shared_prefs")
        File(folder, "$name.xml").exists() || File(folder, "$name.xml.bak").exists()
    } catch (e: Exception) {
        true
    }

/**
 * ZC-705 (should 7): the three library calls behind [openEncryptedFile]. The default makes the real ones; a JVM test
 * passes its own (the master key is handed through as an object of its own, so a test needs no real one).
 */
interface EncryptedFileCalls {
    /** MasterKey.Builder(context, masterKeyName)…build(): makes a NEW Keystore key when the Keystore says it is absent. */
    fun masterKey(
        context: Context,
        masterKeyName: String,
    ): Any

    /** The library's create with a master key object (a new file). */
    fun createWithMasterKey(
        context: Context,
        name: String,
        masterKey: Any,
    ): SharedPreferences

    /** The library's create by the key's name: no master-key call, so no key is ever made (a file that exists). */
    fun createByKeyName(
        context: Context,
        name: String,
        masterKeyName: String,
    ): SharedPreferences
}

/** ZC-705: the real library calls; the only place in ui-lib that calls the library's create and builds a master key. */
object LibraryEncryptedFileCalls : EncryptedFileCalls {
    // The library's builder call is kept on one line, under its own name, so a search for it finds it.
    @Suppress("ktlint:standard:chain-method-continuation")
    override fun masterKey(
        context: Context,
        masterKeyName: String,
    ): Any =
        MasterKey.Builder(context, masterKeyName)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    override fun createWithMasterKey(
        context: Context,
        name: String,
        masterKey: Any,
    ): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            name,
            masterKey as MasterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    // The overload that takes the key's name is deprecated in favour of the MasterKey object; it is used on purpose:
    // it asks the Keystore for the key by name and never makes one, which the MasterKey object's build() would.
    @Suppress("DEPRECATION")
    override fun createByKeyName(
        context: Context,
        name: String,
        masterKeyName: String,
    ): SharedPreferences =
        EncryptedSharedPreferences.create(
            name,
            masterKeyName,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
}

/**
 * ZC-705 (should 7, spec 3.4.3): the one way the app opens an encrypted preferences file. Both key entries are in the
 * file: it is opened by the key's name, with no master-key call, so no Keystore key is ever made while a file that
 * exists is opened (the library asks for the key by name and never makes one for an existing entry). Otherwise (a new
 * file, or one a kill left with one entry): the master key first, as before, then the library's create with it. This
 * holds for the app's own openers only: the wallet SDK's own opener still makes the master-key call under the same
 * key name at every wallet start.
 */
fun openEncryptedFile(
    context: Context,
    name: String,
    masterKeyName: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS,
    calls: EncryptedFileCalls = LibraryEncryptedFileCalls,
): SharedPreferences {
    val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    return if (TINK_KEYSET_ENTRIES.all { raw.contains(it) }) {
        calls.createByKeyName(context, name, masterKeyName)
    } else {
        // ZC-868: no master-key call (it makes a key on "absent") while stores made with that key exist and the
        // Keystore says it is absent: the open fails closed instead.
        MasterKeyGuard.checkBeforeMakingKey(context, name, masterKeyName)
        calls.createWithMasterKey(context, name, calls.masterKey(context, masterKeyName))
    }
}

/** Marker in the open-failures file for a kept copy the user has answered (restored it, or continued without it). */
private const val HANDLED_COPY_PREFIX = "handled_copy:"

/**
 * ZC-709: marker in the open-failures file for a kept copy the user declined with "Continue without them". Written in
 * the same commit as its [HANDLED_COPY_PREFIX]; while the copy's wallet is the one in ZCHAT, More → Advanced Settings
 * → Restore old chats offers it again ([KeptChatStoreCopies.askable], [KeptChatStoreCopies.reoffer]).
 */
private const val DISMISSED_COPY_PREFIX = "dismissed_copy:"

/**
 * R4-5: marker in the open-failures file for a store whose kept copy the user has just put back. The next open of
 * that store decides: it opens (the copy is readable again, the mark goes) or it cannot be read either
 * ([RESTORE_FAILED_PREFIX]).
 *
 * D6 (REVIEW_7C F18): kept per wallet, "restore_tried:<owner>:<store>" ([ownerMarkKeys]).
 */
private const val RESTORE_TRIED_PREFIX = "restore_tried:"

/**
 * R5-3: marker in the open-failures file for a store that has already had its one extra restore, because the
 * failure that ended the first one never said a key was lost (the phone's security system did not answer at
 * all). The next failed restore of that store is the verdict. Given back only when a put-back copy really
 * opened; the recreated empty store opening is not that.
 *
 * D6 (REVIEW_7C F18): kept per wallet, "restore_retried:<owner>:<store>" ([ownerMarkKeys]): a wallet never inherits
 * the extra restore another wallet spent.
 */
private const val RESTORE_RETRIED_PREFIX = "restore_retried:"

/**
 * B5: marker in the open-failures file for a store whose put-back copy could not be read again, when nothing ever
 * said its key was lost — the phone's security system simply did not answer. While it is set, the start-up screen
 * does not offer that store's kept copies (so no restore loop runs while the outage lasts); the copies stay
 * unanswered. The first launch whose open of the store succeeds at once removes it, and the copy is offered again.
 */
private const val RESTORE_PAUSED_PREFIX = "restore_paused:"

/** B5: the user has read that the restore of this store is paused ([RESTORE_PAUSED_PREFIX]); ZCHAT opens normally. */
private const val RESTORE_PAUSE_EXPLAINED_PREFIX = "restore_pause_explained:"

/**
 * F10 (fix-all 2.13.0, chat copy #16): the pauses of a store's restore episode so far, "restore_pauses:<owner>:<store>"
 * ([ownerMarkKeys]), an Int. It survives the lifts, so the next failed restore after [PAUSE_REOFFER_DELAYS_MS] runs out
 * parks the copy. It goes when the episode ends: a put-back copy opens, the key is gone for good, or Continue without
 * them while no copy of the store is held.
 */
private const val RESTORE_PAUSES_PREFIX = "restore_pauses:"

/**
 * F10: "restore_held:<copy>" (Long, when it was held) — this kept copy is held back from the start-up offer, by a pause
 * (with [RESTORE_OFFER_AFTER_PREFIX]) or a park ([RESTORE_PARKED_PREFIX]). The hold is the copy's, never its store's:
 * a copy a new outage keeps meanwhile is offered as usual. It follows the copy's owner mark ([COPY_OWNER_PREFIX]).
 */
private const val RESTORE_HELD_PREFIX = "restore_held:"

/** F10: "restore_offer_after:<copy>" (Long) — the paused copy is offered again at a clean launch from this time on. */
private const val RESTORE_OFFER_AFTER_PREFIX = "restore_offer_after:"

/**
 * F10: "restore_parked:<copy>" — the copy failed to open after its last pause and is no longer offered by itself. It
 * stays unanswered and is never called gone; More → Advanced Settings → Restore old chats lets it be offered again
 * ([KeptChatStoreCopies.retryHeld]), and a put-back copy of its store that opens ends the park.
 */
private const val RESTORE_PARKED_PREFIX = "restore_parked:"

/** F10: "restore_park_explained:<copy>" — the user has read the parked screen for this copy. */
private const val RESTORE_PARK_EXPLAINED_PREFIX = "restore_park_explained:"

/**
 * C6 (R6-4 review finding 13): marker in the open-failures file naming the wallet ([chatOwnerTag], a one-way hash)
 * whose chats a kept copy holds. A copy is offered, restored or answered only while that wallet owns the chats; a
 * copy without the mark (kept before copies were tagged, or of a store no wallet owns) counts as the current one's.
 *
 * D6 (REVIEW_7C F17): a copy can lack the mark only while no owner is recorded (a phone upgraded from 2.12.4) or
 * when it is a set-aside bundle's. The moment an owner is recorded or handed over, every untagged copy of a live
 * store is tagged with the wallet that owned the chats ([KeptChatStoreCopies.tagUntaggedWith]), so it never counts
 * as the next wallet's.
 */
private const val COPY_OWNER_PREFIX = "copy_owner:"

/** C6 (R6-4 review finding 13): the wallet whose chats a paused restore ([RESTORE_PAUSED_PREFIX]) holds back. */
private const val RESTORE_PAUSED_OWNER_PREFIX = "restore_paused_owner:"

/**
 * C6 (R6-4 review finding 14): the user tapped Continue on the paused screen, so ZCHAT opened and the chats written
 * since then live in the store. The value is the wallet that owned the chats then ("" when none was recorded). While
 * it is set, a restore keeps what it replaces as an UNANSWERED copy (offered back, never hidden), and the offer says
 * so. It goes when the store's episode ends: Continue without the copies, or a restore that leaves none unanswered.
 *
 * D6 (REVIEW_7C F18): kept per wallet, "used_after_pause:<owner>:<store>" ([ownerMarkKeys]), so another wallet's
 * answer never ends this wallet's episode. An old per-store key "used_after_pause:<store>" belongs to the wallet its
 * value names (the current one when the value is "").
 */
private const val USED_AFTER_PAUSE_PREFIX = "used_after_pause:"

/** The two entries the Tink keyset of an EncryptedSharedPreferences file is stored under; a file holding only them has no chats. */
internal val TINK_KEYSET_ENTRIES =
    setOf(
        "__androidx_security_crypto_encrypted_prefs_key_keyset__",
        "__androidx_security_crypto_encrypted_prefs_value_keyset__",
    )

/** Whether a store's raw [entries] hold no chats at all: nothing, or only the Tink keyset of a recreated store. */
private fun holdsNoChats(entries: Map<String, *>): Boolean = TINK_KEYSET_ENTRIES.containsAll(entries.keys)

/** Writes the owner mark of [copyName]; true when there is none to write or it is on disk. */
private fun tagCopyOwner(
    marks: SharedPreferences,
    copyName: String,
    owner: String?,
): Boolean = owner == null || marks.edit().putString(COPY_OWNER_PREFIX + copyName, owner).commit()

/**
 * C6: the wallet that owns the live chat stores, as [PrefsChatOwnerRecord] wrote it ([chatOwnerTag], [NO_CHAT_OWNER]),
 * or null on an install that never recorded one. Read from the plain file, without the Keystore.
 */
internal fun chatOwnerOf(plainPrefs: (String) -> SharedPreferences): String? =
    plainPrefs(CHAT_OWNER_PREFS_NAME).getString(CHAT_OWNER_KEY, null)

/** D6: separates the wallet from the store in a per-wallet mark ("<prefix><owner>:<store>"); no store name has one. */
private const val OWNER_MARK_SEPARATOR = ':'

/** D6: the restore marks kept per wallet ([ownerMarkKeys]). */
private val OWNER_MARK_PREFIXES = listOf(USED_AFTER_PAUSE_PREFIX, RESTORE_TRIED_PREFIX, RESTORE_RETRIED_PREFIX, RESTORE_PAUSES_PREFIX)

/**
 * D6 (REVIEW_7C F18): the keys of [owner]'s mark [prefix] for [store]. The first is where it is written:
 * "<prefix><owner>:<store>", or the old per-store key "<prefix><store>" when there is no owner (none recorded yet, or a
 * set-aside bundle, which belongs to no live wallet). The old per-store key follows when it differs: written before
 * the marks were kept per wallet, it is read as [owner]'s ([KeptChatStoreCopies.tagUntaggedWith] hands every old key
 * to the wallet that owned the chats before another wallet's chats are loaded) and removed when the mark is touched.
 */
private fun ownerMarkKeys(
    prefix: String,
    owner: String?,
    store: String,
): List<String> =
    if (owner.isNullOrEmpty()) {
        listOf(prefix + store)
    } else {
        listOf("$prefix$owner$OWNER_MARK_SEPARATOR$store", prefix + store)
    }

/**
 * R4-5: marker in the open-failures file for a store whose put-back copy could not be read either — the phone's
 * security key for it is gone for good. The start-up screen says so once instead of offering the same restore at
 * every launch, which put the user in an endless loop. The copies themselves stay on the phone.
 */
private const val RESTORE_FAILED_PREFIX = "restore_failed:"

/**
 * R3-3: the recoverable copies [openEncryptedStore] kept of unreadable stores ("<store>__unreadable_<millis>"), and
 * the way back to them. [plainPrefs] is the raw SharedPreferences view of a file name, [fileNames] the shared_prefs
 * folder listing ("<name>.xml").
 *
 * Nothing here deletes a file: a copy the user answered stays on the phone, and before a restore replaces a store,
 * what that store held is kept as one more copy.
 *
 * C6 (R6-4 review finding 13): only the copies and pauses of the wallet that owns the chats now count here. Another
 * wallet's are not offered, not restored and not answered here, and count again when that wallet is loaded again.
 * A copy with no owner mark counts as the current wallet's. D6 (REVIEW_7C F17): a copy of a live store lacks the mark
 * only while no owner is recorded; [tagUntaggedWith] tags every such copy, pause and mark with the wallet that owned
 * the chats as soon as an owner is recorded or handed over ([ChatWalletOwnership]).
 *
 * ZC-733-B (rule S14; it ends the limit D6 F20 named here): a wipe of one wallet ([ZchatPreferences.clearAll], Delete
 * Wallet without "keep") deletes the kept copies of that wallet ([copiesToWipe]) and its marks ([cleanMarksForWipe]);
 * a copy tagged with another wallet, and a copy of another wallet's set-aside bundle, stay with their marks and are
 * offered when that wallet is loaded again. Destroy All still deletes every file by listing.
 */
@Suppress("TooManyFunctions")
class KeptChatStoreCopies(
    private val plainPrefs: (String) -> SharedPreferences,
    private val fileNames: () -> Collection<String>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : UntaggedChatMarks {
    private val marks: SharedPreferences get() = plainPrefs(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)

    /** C6: the wallet that owns the chats now (see [chatOwnerOf]). */
    private fun owner(): String? = chatOwnerOf(plainPrefs)

    /**
     * D6: the wallet whose restore marks of [store] count now — the chat owner, or none for a set-aside bundle, which
     * belongs to no live wallet (the same owner [openEncryptedStore] is given for it, see [openSetAsideBundle]).
     */
    private fun markOwner(store: String): String? = if (isSetAside(store)) null else owner()

    private fun isSetAside(store: String): Boolean = store.startsWith(SET_ASIDE_PREFS_PREFIX)

    /**
     * D6 (REVIEW_7C F17): gives [owner] — the wallet that owns the chats on the phone right now, about to be recorded
     * or handed over — everything of a live chat store that names no wallet yet: every kept copy without an owner
     * mark (or with an empty one), every paused restore without one, and every old per-store restore mark
     * ("used_after_pause:", "restore_tried:", "restore_retried:" + store; an old "used_after_pause:" mark that already
     * names a wallet goes to that wallet). Written before a copy, pause or mark can be counted as the next wallet's.
     * Set-aside bundles are left as they are: their copies belong to the bundle's own wallet, never to a live one.
     * One commit; true when it is on disk (or there was nothing to tag).
     */
    override fun tagUntaggedWith(owner: String): Boolean {
        val all = marks.all
        val editor = marks.edit()
        var changed = false
        unreadableStoreCopyNames(fileNames())
            .filterNot { isSetAside(storeOf(it)) }
            .filter { (all[COPY_OWNER_PREFIX + it] as? String).isNullOrEmpty() }
            .forEach {
                editor.putString(COPY_OWNER_PREFIX + it, owner)
                changed = true
            }
        storesMarked(all, RESTORE_PAUSED_PREFIX)
            .filterNot { isSetAside(it) }
            .filter { (all[RESTORE_PAUSED_OWNER_PREFIX + it] as? String).isNullOrEmpty() }
            .forEach {
                editor.putString(RESTORE_PAUSED_OWNER_PREFIX + it, owner)
                changed = true
            }
        for ((key, value) in all) {
            val prefix = OWNER_MARK_PREFIXES.firstOrNull { key.startsWith(it) } ?: continue
            val store = key.removePrefix(prefix)
            if (OWNER_MARK_SEPARATOR in store || isSetAside(store)) continue
            editor.remove(key)
            if (prefix == USED_AFTER_PAUSE_PREFIX) {
                val markOwner = (value as? String)?.takeIf { it.isNotEmpty() } ?: owner
                editor.putString(ownerMarkKeys(prefix, markOwner, store).first(), markOwner)
            } else if (value == true) {
                editor.putBoolean(ownerMarkKeys(prefix, owner, store).first(), true)
            } else if (value is Int) {
                // F10: an episode's pause count (RESTORE_PAUSES_PREFIX).
                editor.putInt(ownerMarkKeys(prefix, owner, store).first(), value)
            }
            changed = true
        }
        return !changed || editor.commit()
    }

    /** C6: whether something tagged with [tag] belongs to [owner]; untagged counts as the current wallet's. */
    private fun ownedBy(
        tag: Any?,
        owner: String?,
    ): Boolean = isOwnedBy(tag, owner)

    /** The kept copies of the wallet that owns the chats now that the user has not answered yet, oldest first. */
    fun unhandled(): List<String> {
        val all = marks.all
        val owner = owner()
        return unreadableStoreCopyNames(fileNames())
            .filter { all[HANDLED_COPY_PREFIX + it] != true }
            .filter { ownedBy(all[COPY_OWNER_PREFIX + it], owner) }
            .filter { plainPrefs(it).all.isNotEmpty() }
            .sortedBy { copyMillis(it) }
    }

    /**
     * B5: the unanswered copies the start-up screen may offer now — [unhandled] without those of a store whose
     * restore is paused while the phone's security system does not answer ([restorePaused]).
     * F10: and without the copies held by their own pause or park ([held]).
     */
    fun offered(): List<String> {
        val paused = restorePaused()
        return unhandled().filter { it !in paused.held && storeOf(it) !in paused.stores }
    }

    /**
     * F10 (fix-all 2.13.0, chat copy #16): this wallet's unanswered copies that are held back by a pause or a park —
     * what More → Advanced Settings → Restore old chats offers again ([retryHeld]). The row is shown while this is not
     * empty.
     */
    fun held(): List<String> = heldIn(marks.all, unhandled())

    private fun heldIn(
        all: Map<String, *>,
        unhandled: List<String>,
    ): List<String> = unhandled.filter { all.containsKey(RESTORE_HELD_PREFIX + it) }

    /**
     * F10: Restore old chats (Advanced Settings). This wallet's held copies are let go, so the next start offers them
     * again, and a pause of their store that still holds its copies back ends. The episode's pause count stays: a
     * restore that fails again parks the copy at once, with no new automatic offers; one that opens ends the episode.
     * False, with nothing changed, when no copy is held.
     */
    fun retryHeld(): Boolean {
        val all = marks.all
        val owner = owner()
        val copies = heldIn(all, unhandled())
        if (copies.isEmpty()) return false
        val editor = marks.edit()
        copies.forEach { copy -> heldCopyMarkKeys(copy).forEach { editor.remove(it) } }
        copies
            .map { storeOf(it) }
            .distinct()
            .filter { all[RESTORE_PAUSED_PREFIX + it] == true && ownedBy(all[RESTORE_PAUSED_OWNER_PREFIX + it], owner) }
            .forEach { store ->
                editor
                    .remove(RESTORE_PAUSED_PREFIX + store)
                    .remove(RESTORE_PAUSED_OWNER_PREFIX + store)
                    .remove(RESTORE_PAUSE_EXPLAINED_PREFIX + store)
            }
        return editor.commit()
    }

    /**
     * ZC-709: what More → Advanced Settings → Restore old chats offers again: this wallet's held copies ([held]) and this
     * wallet's copies the user declined with "Continue without them" that hold chats, oldest first. A copy called gone
     * for good, a copy a restore answered and a declined copy that holds no chats are not among them.
     */
    fun askable(): List<String> {
        val all = marks.all
        val owner = owner()
        val held = heldIn(all, unhandled())
        return (held + declinedIn(all, owner)).distinct().sortedBy { copyMillis(it) }
    }

    /** ZC-709: [owner]'s copies declined with "Continue without them" that hold chats. */
    private fun declinedIn(
        all: Map<String, *>,
        owner: String?,
    ): List<String> =
        unreadableStoreCopyNames(fileNames())
            .filter { all[HANDLED_COPY_PREFIX + it] == true && all[DISMISSED_COPY_PREFIX + it] == true }
            .filter { ownedBy(all[COPY_OWNER_PREFIX + it], owner) }
            .filterNot { holdsNoChats(plainPrefs(it).all) }

    /**
     * ZC-709: the Settings row's action. The held copies are let go exactly as [retryHeld] does; each declined copy loses
     * its two marks, so the next start offers it, and its store is marked "used since" for the copy's wallet, so a
     * restore keeps what the store holds now as a copy that is offered back. One commit; false, with nothing changed,
     * when there was nothing to offer again.
     */
    fun reoffer(): Boolean {
        val all = marks.all
        val owner = owner()
        val held = heldIn(all, unhandled())
        val declined = declinedIn(all, owner).filter { it !in held }
        if (held.isEmpty() && declined.isEmpty()) return false
        val editor = marks.edit()
        held.forEach { copy -> heldCopyMarkKeys(copy).forEach { editor.remove(it) } }
        held
            .map { storeOf(it) }
            .distinct()
            .filter { all[RESTORE_PAUSED_PREFIX + it] == true && ownedBy(all[RESTORE_PAUSED_OWNER_PREFIX + it], owner) }
            .forEach { store ->
                editor
                    .remove(RESTORE_PAUSED_PREFIX + store)
                    .remove(RESTORE_PAUSED_OWNER_PREFIX + store)
                    .remove(RESTORE_PAUSE_EXPLAINED_PREFIX + store)
            }
        declined.forEach { copy ->
            editor.remove(HANDLED_COPY_PREFIX + copy).remove(DISMISSED_COPY_PREFIX + copy)
            val copyOwner = (all[COPY_OWNER_PREFIX + copy] as? String)?.takeIf { it.isNotEmpty() } ?: markOwner(storeOf(copy))
            markUsedAfterPause(editor, all, storeOf(copy), copyOwner)
        }
        return editor.commit()
    }

    /**
     * ZC-733-B (rule S14): the kept copies among [listed] that a wipe of the wallet that owns the chats now deletes:
     * every copy tagged with that wallet or untagged, and a copy of that wallet's own set-aside bundle. A copy tagged
     * with another wallet, and a copy of another wallet's bundle (it carries no tag by design, its name carries that
     * wallet's tag), are spared. With no owner recorded, or none: the untagged copies (bundle copies are spared). Deletes
     * nothing itself: ZchatPreferences.clearAll deletes what this names.
     */
    fun copiesToWipe(listed: Collection<String> = unreadableStoreCopyNames(fileNames())): List<String> {
        val all = marks.all
        val wallet = owner()?.takeIf { it.isNotEmpty() && it != NO_CHAT_OWNER }
        return listed.filter { copy ->
            val store = storeOf(copy)
            if (store == AiPreferences.PREFS_NAME) {
                // ZC-718 (rule K-0): a kept copy of the AI store can hold the only access key: spared, with its marks.
                false
            } else if (isSetAside(store)) {
                wallet != null && store == SET_ASIDE_PREFS_PREFIX + wallet
            } else {
                val tag = (all[COPY_OWNER_PREFIX + copy] as? String)?.takeIf { it.isNotEmpty() }
                tag == null || tag == wallet
            }
        }
    }

    /**
     * ZC-733-B (rule S14): after [copiesToWipe]'s copies were deleted, cleans the file of marks in ONE commit: every count,
     * every mark of the wiped wallet and of its copies and every mark that names no wallet goes; what belongs to a copy
     * that is still on the phone, or to its wallet, stays (the copy's owner tag first of all, its answered, declined and
     * held marks, that wallet's per-wallet marks, a paused restore whose owner mark names it). True when anything is
     * left, so the caller deletes the file only when nothing is. Deletes no file. ZC-718: a kept copy of the AI store is
     * never named by [copiesToWipe], so it is still on the phone here and keeps its marks, as another wallet's copy does.
     */
    fun cleanMarksForWipe(): Boolean {
        val all = marks.all
        val spared = unreadableStoreCopyNames(fileNames())
        val sparedSet = spared.toSet()
        val wallets = spared.mapNotNull { (all[COPY_OWNER_PREFIX + it] as? String)?.takeIf { tag -> tag.isNotEmpty() } }.toSet()
        val copyPrefixes = listOf(COPY_OWNER_PREFIX, HANDLED_COPY_PREFIX, DISMISSED_COPY_PREFIX) + HELD_MARK_PREFIXES

        fun stays(key: String): Boolean {
            val copyMark = copyPrefixes.firstOrNull { key.startsWith(it) }
            val walletMark = OWNER_MARK_PREFIXES.firstOrNull { key.startsWith(it) }
            return when {
                copyMark != null -> key.removePrefix(copyMark) in sparedSet
                walletMark != null -> {
                    val rest = key.removePrefix(walletMark)
                    OWNER_MARK_SEPARATOR in rest && rest.substringBefore(OWNER_MARK_SEPARATOR) in wallets
                }
                key.startsWith(RESTORE_PAUSED_OWNER_PREFIX) -> all[key] in wallets
                key.startsWith(RESTORE_PAUSED_PREFIX) || key.startsWith(RESTORE_PAUSE_EXPLAINED_PREFIX) -> {
                    val store = key.removePrefix(RESTORE_PAUSED_PREFIX).removePrefix(RESTORE_PAUSE_EXPLAINED_PREFIX)
                    all[RESTORE_PAUSED_OWNER_PREFIX + store] in wallets
                }
                else -> false
            }
        }

        val keep = all.keys.filter(::stays).toSet()
        val gone = all.keys - keep
        if (gone.isNotEmpty()) {
            val editor = marks.edit()
            gone.forEach { editor.remove(it) }
            if (!editor.commit()) return true
        }
        return keep.isNotEmpty()
    }

    /**
     * B5: the stores whose restore is paused because the phone's security system did not answer, and those of them
     * whose explanation the user has not read yet. Their copies stay unanswered and are offered again once the
     * store opens at the first tries of a launch.
     */
    fun restorePaused(): PausedChatRestores {
        val all = marks.all
        val owner = owner()
        val stores = storesMarked(all, RESTORE_PAUSED_PREFIX).filter { ownedBy(all[RESTORE_PAUSED_OWNER_PREFIX + it], owner) }
        // F10: the copies held by their own pause or park (this wallet's, unanswered).
        val held = heldIn(all, unhandled())
        val parked = held.filter { all[RESTORE_PARKED_PREFIX + it] == true }
        return PausedChatRestores(
            stores = stores,
            unexplained = stores.filter { all[RESTORE_PAUSE_EXPLAINED_PREFIX + it] != true },
            // D6 (REVIEW_7C F19): the mark is set on Continue, before anything is written; the offer says the user
            // has used ZCHAT since only when the store holds chats (as restoreOne judges what it replaces).
            usedAfterPause = usedAfterPause(all, owner).keys.filterNot { holdsNoChats(plainPrefs(it).all) }.sorted(),
            held = held,
            parked = parked.map { storeOf(it) }.distinct().sorted(),
            unexplainedParked =
                parked
                    .filter { all[RESTORE_PARK_EXPLAINED_PREFIX + it] != true }
                    .map { storeOf(it) }
                    .distinct()
                    .sorted(),
        )
    }

    /**
     * B5: the user has read that the restore is paused; ZCHAT opens without offering the copy until it is lifted.
     * C6 (R6-4 review finding 14): from here the user chats in the store, so the store is marked
     * [USED_AFTER_PAUSE_PREFIX]: a later restore keeps those chats as a copy that is offered back.
     * D6 (REVIEW_7C F17 + F18): the mark is the pause's wallet's ("" only while no owner is recorded at all), and
     * replaces only that wallet's old per-store mark.
     */
    fun acknowledgeRestorePaused(): Boolean {
        val all = marks.all
        val stores = restorePaused().unexplained
        if (stores.isEmpty()) return false
        val editor = marks.edit()
        stores.forEach { store ->
            editor.putBoolean(RESTORE_PAUSE_EXPLAINED_PREFIX + store, true)
            val pauseOwner = (all[RESTORE_PAUSED_OWNER_PREFIX + store] as? String)?.takeIf { it.isNotEmpty() } ?: markOwner(store)
            markUsedAfterPause(editor, all, store, pauseOwner)
        }
        return editor.commit()
    }

    /**
     * F10 (fix-all 2.13.0, chat copy #16): the user has read the parked screen; ZCHAT opens without offering the parked
     * copies. Only the PARKED copies not read yet are answered here (review break 3: the paused screen's Continue is
     * [acknowledgeRestorePaused] and never this one). As after a pause, the user chats in the store from here, so it is
     * marked [USED_AFTER_PAUSE_PREFIX] for the copy's wallet: a later restore keeps those chats as a copy that is offered
     * back. False when there is nothing to answer.
     */
    fun acknowledgeRestoreParked(): Boolean {
        val all = marks.all
        val copies =
            heldIn(all, unhandled()).filter {
                all[RESTORE_PARKED_PREFIX + it] == true && all[RESTORE_PARK_EXPLAINED_PREFIX + it] != true
            }
        if (copies.isEmpty()) return false
        val editor = marks.edit()
        copies.forEach { editor.putBoolean(RESTORE_PARK_EXPLAINED_PREFIX + it, true) }
        copies.groupBy { storeOf(it) }.forEach { (store, ofStore) ->
            val copyOwner =
                ofStore.firstNotNullOfOrNull { (all[COPY_OWNER_PREFIX + it] as? String)?.takeIf { tag -> tag.isNotEmpty() } }
                    ?: markOwner(store)
            markUsedAfterPause(editor, all, store, copyOwner)
        }
        return editor.commit()
    }

    /** C6 / D6: marks [store] as used after a pause (or a park) by [owner]'s chats, replacing only its old per-store mark. */
    private fun markUsedAfterPause(
        editor: SharedPreferences.Editor,
        all: Map<String, *>,
        store: String,
        owner: String?,
    ) {
        val keys = ownerMarkKeys(USED_AFTER_PAUSE_PREFIX, owner, store)
        keys.drop(1).filter { ownedBy(all[it], owner) }.forEach { editor.remove(it) }
        editor.putString(keys.first(), owner.orEmpty())
    }

    /**
     * D6 (REVIEW_7C F18): [owner]'s "used after the pause" marks, by store, each with the keys it is kept under: its
     * per-wallet key, and an old per-store key whose value names [owner] or no wallet.
     */
    private fun usedAfterPause(
        all: Map<String, *>,
        owner: String?,
    ): Map<String, List<String>> =
        all.keys
            .filter { it.startsWith(USED_AFTER_PAUSE_PREFIX) }
            .mapNotNull { key ->
                val rest = key.removePrefix(USED_AFTER_PAUSE_PREFIX)
                val mine =
                    if (OWNER_MARK_SEPARATOR in rest) {
                        owner != null && rest.substringBefore(OWNER_MARK_SEPARATOR) == owner
                    } else {
                        ownedBy(all[key], owner)
                    }
                if (mine) rest.substringAfter(OWNER_MARK_SEPARATOR) to key else null
            }.groupBy({ it.first }, { it.second })

    /**
     * Puts the kept copies back: for each store, the unanswered copy with the most entries (the newest of equals)
     * replaces what the store holds, which is first kept as a copy of its own. The stores are raw files here (Tink
     * keyset and ciphertext), so ZCHAT must restart to open them. Returns false, with every store as it was and the
     * copies still offered, when a store could not be written exactly.
     *
     * C6 (R6-4 review finding 14): only the copy put back is answered — with any copy identical to it and any that
     * holds no chats at all (only a keyset). Every other copy of the store stays unanswered and is offered again,
     * so a restore never hides chats. After the user chatted in the store during a pause ([USED_AFTER_PAUSE_PREFIX]),
     * what the restore replaces is kept unanswered too, and offered back.
     */
    fun restore(): Boolean {
        val byStore = offered().groupBy { storeOf(it) }
        if (byStore.isEmpty()) return false
        // D6: every "used after the pause" mark of this wallet, including one whose store holds no chats (F19 words
        // only the offer): what a restore replaces there is answered at once all the same (restoreOne).
        val used = usedAfterPause(marks.all, owner())
        for ((store, copies) in byStore) {
            val chosen = copies.maxWith(compareBy<String>({ plainPrefs(it).all.size }, { copyMillis(it) }))
            val chosenEntries = plainPrefs(chosen).all
            if (!restoreOne(store, chosenEntries, keepReplacedOffered = store in used)) return false
            val answered =
                copies.filter { it == chosen || plainPrefs(it).all.let { entries -> entries == chosenEntries || holdsNoChats(entries) } }
            if (!markHandled(answered)) return false
            // The launch count starts over for the store, and R4-5 records that the next open of it decides
            // whether the copy just put back is readable at all (D6: this wallet's mark; an old per-store one goes).
            val tried = ownerMarkKeys(RESTORE_TRIED_PREFIX, markOwner(store), store)
            val editor = marks.edit().remove(store)
            tried.drop(1).forEach { editor.remove(it) }
            editor.putBoolean(tried.first(), true)
            // C6: the episode that began with the pause ends when no copy of the store is left to offer.
            if (store in used && unhandled().none { storeOf(it) == store }) used.getValue(store).forEach { editor.remove(it) }
            editor.commit()
        }
        return true
    }

    /** Keeps the copies on the phone and stops offering them. */
    fun dismiss(): Boolean {
        val copies = offered()
        // ZC-709: declined, in the same commit that answers each copy, so Settings can offer it again.
        if (!markHandled(copies, declined = true)) return false
        // R5-3: this store's episode is over. Whatever the user chats after this lives in the same store, and
        // the next outage that sets THAT aside must get its own one extra try, not the spent one.
        // C6: so is the episode that began when the user chatted on during a pause.
        // D6 (REVIEW_7C F18): this wallet's episode only; another wallet's marks of the same store stay.
        // F10 (review break 4): not while a copy of the store is still held (paused or parked). Its episode goes on:
        // the held copy is never released here, and the chats written since the park stay protected
        // (USED_AFTER_PAUSE_PREFIX), so a restore of the held copy from Settings keeps them offered.
        val used = usedAfterPause(marks.all, owner())
        val stillHeld = held().map { storeOf(it) }.toSet()
        val editor = marks.edit()
        copies
            .map { storeOf(it) }
            .distinct()
            .filterNot { it in stillHeld }
            .forEach { store ->
                ownerMarkKeys(RESTORE_RETRIED_PREFIX, markOwner(store), store).forEach { editor.remove(it) }
                ownerMarkKeys(RESTORE_PAUSES_PREFIX, markOwner(store), store).forEach { editor.remove(it) }
                used[store].orEmpty().forEach { editor.remove(it) }
            }
        return editor.commit()
    }

    /**
     * R4-5: the stores whose put-back copy could not be read either, so the restore is not offered again. Empty
     * until the user has restored a copy and the next open of that store failed all the same.
     */
    fun restoreFailed(): List<String> = storesMarked(marks.all, RESTORE_FAILED_PREFIX)

    private fun storesMarked(
        all: Map<String, *>,
        prefix: String,
    ): List<String> =
        all
            .filterValues { it == true }
            .keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .sorted()

    private fun storeOf(copyName: String): String = copyName.substringBefore(UNREADABLE_STORE_COPY_MARKER)

    /** R4-5: the user has read the explanation; stop showing it. The copies stay on the phone either way. */
    fun acknowledgeRestoreFailed(): Boolean {
        val stores = restoreFailed()
        if (stores.isEmpty()) return false
        val editor = marks.edit()
        stores.forEach { editor.remove(RESTORE_FAILED_PREFIX + it) }
        return editor.commit()
    }

    private fun restoreOne(
        store: String,
        copy: Map<String, *>,
        keepReplacedOffered: Boolean,
    ): Boolean {
        val target = plainPrefs(store)
        val current = target.all
        if (current == copy) return true
        // ZC-709: a store that holds nothing of the user's (only its own key entries) is not kept as a copy.
        if (current.isNotEmpty() && !holdsNoChats(current)) {
            // What the recreated store holds now is kept too, answered at once (it is not what the user asked back).
            // C6: unless the user chatted in it during a pause — then it is offered back, never hidden — and it
            // belongs to the wallet that owns the chats now.
            val keptName = "$store$UNREADABLE_STORE_COPY_MARKER${nowMillis()}"
            val answered = !keepReplacedOffered || holdsNoChats(current)
            // A set-aside bundle's copy belongs to the bundle's own wallet, not to the live one (see openSetAsideBundle).
            val keptOwner = if (store.startsWith(SET_ASIDE_PREFS_PREFIX)) null else owner()
            if (!tagCopyOwner(marks, keptName, keptOwner) ||
                !writeExactly(plainPrefs(keptName), current) ||
                (answered && !markHandled(listOf(keptName)))
            ) {
                return false
            }
        }
        if (!writeExactly(target, copy)) {
            Log.w(TAG, "Kept copy of $store could not be put back; the store was left as it was")
            if (current.isNotEmpty()) writeExactly(target, current)
            return false
        }
        Log.w(TAG, "Put the kept copy of $store back (${copy.size} entries)")
        return true
    }

    private fun markHandled(
        copies: List<String>,
        declined: Boolean = false,
    ): Boolean {
        if (copies.isEmpty()) return true
        val editor = marks.edit()
        copies.forEach {
            editor.putBoolean(HANDLED_COPY_PREFIX + it, true)
            if (declined) editor.putBoolean(DISMISSED_COPY_PREFIX + it, true)
        }
        return editor.commit()
    }

    private fun copyMillis(copyName: String): Long = copyName.substringAfterLast(UNREADABLE_STORE_COPY_MARKER).toLongOrNull() ?: 0L

    companion object {
        /** The kept copies of this app's shared_prefs folder. */
        fun forApp(context: android.content.Context): KeptChatStoreCopies {
            val appContext = context.applicationContext
            val folder = java.io.File(appContext.applicationInfo.dataDir, "shared_prefs")
            return KeptChatStoreCopies(
                plainPrefs = { appContext.getSharedPreferences(it, android.content.Context.MODE_PRIVATE) },
                fileNames = { folder.list()?.toList().orEmpty() },
            )
        }
    }
}

/**
 * B5: the chat stores whose restore is paused while the phone's security system does not answer ([stores]), and
 * those of them whose explanation the user has not read yet ([unexplained]).
 *
 * C6 (R6-4 review finding 14): [usedAfterPause] are the stores the user chatted in after Continue on the paused
 * screen, whether or not the pause has lifted since; the offer of their copies says that a restore sets the current
 * chats aside (as a copy that is offered back). D6 (REVIEW_7C F19): only those that hold chats now — a store that
 * holds nothing or only its keyset gets the plain offer, since a restore answers what it replaces there at once.
 */
data class PausedChatRestores(
    val stores: List<String>,
    val unexplained: List<String>,
    val usedAfterPause: List<String> = emptyList(),
    /** F10: the kept copies held back by their own pause or park; never offered while held. */
    val held: List<String> = emptyList(),
    /** F10: the stores with a parked copy ([KeptChatStoreCopies.retryHeld] offers it again). */
    val parked: List<String> = emptyList(),
    /** F10: the stores with a parked copy whose parked screen the user has not read yet. */
    val unexplainedParked: List<String> = emptyList(),
) {
    companion object {
        val NONE = PausedChatRestores(emptyList(), emptyList())
    }
}

/** Replaces every entry of [prefs] with [entries] in one commit, and checks they all arrived. */
private fun writeExactly(
    prefs: SharedPreferences,
    entries: Map<String, *>,
): Boolean {
    val editor = prefs.edit().clear()
    entries.forEach { (key, value) ->
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, (value as Set<String>).toMutableSet())
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            null -> Unit
            else -> editor.putString(key, value.toString())
        }
    }
    return editor.commit() && prefs.all == entries.filterValues { it != null }
}
