package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.AddressBookKeyStorageProvider
import co.electriccoin.zcash.ui.common.provider.MetadataKeyStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.tex.TexSendMonitor
import co.electriccoin.zcash.ui.common.tex.TexState
import co.electriccoin.zcash.ui.common.tex.WalletActivity
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.ai.AiResetKeep
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatWalletOwnership
import co.electriccoin.zcash.ui.screen.chat.datasource.chatOwnerTag
import co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe
import co.electriccoin.zcash.ui.screen.chat.util.ResetWipe
import co.electriccoin.zcash.ui.screen.walletstartup.AppRestarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** ZC-090-R: what a Reset ZCHAT answers its screen. */
enum class ResetAnswer {
    /** The unlock prompt was cancelled or failed: nothing happened. */
    CANCELLED,

    /** The Reset could not begin (no record could be written, or a key to AI credit could not be kept): nothing was deleted. */
    NOT_STARTED,

    /** RESET-PAY: a payment is being sent in this process (a send or a private-move step): nothing was deleted. */
    NOT_STARTED_PAYMENT_RUNNING,

    /** RESET-PAY: a payment to an exchange address is not finished, its ZEC may still be coming back: nothing was deleted. */
    NOT_STARTED_PAYMENT_OPEN,

    /** The record is on disk and ZCHAT was asked to restart: the next start finishes the Reset. */
    STARTED,
}

/**
 * ZC-090-R (registers ZC-090, high, its Delete Wallet half, and ZC-755; RESET-LEFTOVERS-SPEC.md revision 2d, sections
 * 4.2 RS-1 to RS-5, 4.3 and 4.4): Reset ZCHAT (Delete Wallet). After the unlock prompt nothing can hold it and leaving
 * the screen cannot cut it: every step runs on a thread of its own, joined for at most its limit
 * ([DestroyWipe.runWithin]), and the body runs where its caller cannot cancel it.
 *
 * Nothing is deleted or cleared before the record (`no_backup/zchat_reset_pending`, [ResetWipe.writeResetRecord]). Before it,
 * with the box unticked, what the recovery phrase cannot open of the AI store is kept in the reset wallet's own set-aside
 * file (S1b, W-52, AI-KEY-R),
 * the owner of the chats is written (S1) and the names of the cached Contacts and notes keys are read for the record's
 * files line (S2). A Reset that cannot write its record, or cannot keep a key, answers [ResetAnswer.NOT_STARTED] and
 * removes the kept file it wrote. From the record on: the chats become nobody's (S4, unticked), the sync service,
 * background work and notifications stop, one alarm is armed and one job enqueued so that the next start comes even
 * without a screen (S5), the saved wallet is cleared as the last act (S6), and ZCHAT restarts in a `finally` (S7). The
 * next start deletes everything else (ZC-090-P's pass). No step asks the SDK for anything.
 */
class ResetZashiUseCase(
    private val biometricRepository: BiometricRepository,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val chatWalletOwnership: ChatWalletOwnership,
    private val aiResetKeep: AiResetKeep,
    private val addressBookKeyStorageProvider: AddressBookKeyStorageProvider,
    private val metadataKeyStorageProvider: MetadataKeyStorageProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val flexaRepository: FlexaRepository,
    private val homeMessageCacheRepository: HomeMessageCacheRepository,
    private val transactionRepository: TransactionRepository,
    private val appRestarter: AppRestarter,
    private val context: Context,
) {
    /**
     * Asks for the unlock prompt and then runs the Reset to its end; the caller cannot cancel it after the prompt.
     * [ResetAnswer.STARTED] is answered only after the restart was requested.
     */
    suspend operator fun invoke(keepFiles: Boolean): ResetAnswer = invoke(keepFiles, aiUnreadableWarningShown = false)

    /**
     * AI-KEY-R (RS-9, spec 5.3): [aiUnreadableWarningShown] says that the screen told the user that this Reset deletes a
     * key in an AI store it cannot read. Only then a read of the AI store that fails or is late lets the Reset go on,
     * with nothing kept. A write of the kept file that fails or is late never does.
     */
    suspend operator fun invoke(
        keepFiles: Boolean,
        aiUnreadableWarningShown: Boolean,
    ): ResetAnswer {
        try {
            biometricRepository.requestBiometrics(
                BiometricRequest(
                    message =
                        stringRes(
                            R.string.authentication_system_ui_subtitle,
                            stringRes(R.string.authentication_use_case_delete_wallet)
                        )
                )
            )
        } catch (_: BiometricsFailureException) {
            return ResetAnswer.CANCELLED
        } catch (_: BiometricsCancelledException) {
            return ResetAnswer.CANCELLED
        }
        return withContext(NonCancellable + Dispatchers.IO) { resetAfterThePrompt(keepFiles, aiUnreadableWarningShown) }
    }

    /**
     * RESET-PAY (register ZC-886; spec 4.9 RS-10, owner question 11.2 item 1, answer (a)): before S1 the gate is closed
     * and then the counters are read; a payment in motion, or an unfinished payment to an exchange address in the last
     * finished check of the TEX recovery, means not started, and every Reset that does not start opens the gate again.
     * No check finished yet (null) does not refuse. The check reads two counters and one value in memory, nothing else.
     */
    private fun resetAfterThePrompt(
        keepFiles: Boolean,
        aiUnreadableWarningShown: Boolean,
    ): ResetAnswer {
        var answer = ResetAnswer.NOT_STARTED
        try {
            answer =
                when {
                    !WalletActivity.closeForReset() -> ResetAnswer.NOT_STARTED_PAYMENT_RUNNING
                    texSendOpen() -> ResetAnswer.NOT_STARTED_PAYMENT_OPEN
                    else -> resetBehindTheGate(keepFiles, aiUnreadableWarningShown)
                }
            return answer
        } finally {
            if (answer != ResetAnswer.STARTED) WalletActivity.reopen()
        }
    }

    private fun texSendOpen(): Boolean {
        val check = TexSendMonitor.latestCheck.value ?: return false
        return check.statuses.any { it.unmatched || it.state in OPEN_TEX_STATES }
    }

    @Suppress("ReturnCount")
    private fun resetBehindTheGate(
        keepFiles: Boolean,
        aiUnreadableWarningShown: Boolean,
    ): ResetAnswer {
        if (DestroyWipe.recordFile(context)?.isFile == true) return notStarted("a Destroy record is on the phone")
        val owner = AtomicReference<String?>(null)
        var budget = LIMIT_OWNER_AND_RECORD_MS
        budget -= DestroyWipe.runWithin("zchat-reset-S1-wallet", budget) { owner.set(runBlocking { resetWalletTag() }) }
        val tag = owner.get() ?: return notStarted("no saved wallet answered")

        // S1b, with the box unticked: the key to AI credit is kept before anything else, or the Reset does not start.
        var keptKey = false
        val writing = AtomicBoolean(false)
        if (!keepFiles) {
            val kept = AtomicReference<Boolean?>(null)
            // One answer to "may the late step still write?": a read given up on never writes a kept file after the record.
            val stage = AtomicInteger(S1B_READING)
            DestroyWipe.runWithin("zchat-reset-S1b-ai-key", LIMIT_AI_KEY_MS) {
                kept.set(
                    aiResetKeep.keep(
                        tag,
                        beforeWrite = {
                            check(stage.compareAndSet(S1B_READING, S1B_WRITING)) { "The Reset gave this read up" }
                            writing.set(true)
                        },
                        // W-97: one of the two wins: this commit, or the Reset giving it up below.
                        mayPublish = { stage.compareAndSet(S1B_WRITING, S1B_PUBLISHING) },
                    )
                )
            }
            val answer = kept.get()
            when {
                answer != null -> keptKey = answer
                !stage.compareAndSet(S1B_READING, S1B_GIVEN_UP) -> {
                    stage.compareAndSet(S1B_WRITING, S1B_GIVEN_UP)
                    return notStarted("the key to AI credit could not be kept", tag, writing)
                }
                !aiUnreadableWarningShown -> return notStarted("the AI store could not be read", tag, writing)
            }
        }

        // S1: the owner of the chats.
        val ownerWritten = AtomicBoolean(false)
        budget -=
            DestroyWipe.runWithin("zchat-reset-S1-owner", budget.coerceAtLeast(1L)) {
                runBlocking { recordChatOwner() }
                ownerWritten.set(true)
            }
        if (!ownerWritten.get()) return notStarted("the owner was not written", tag, writing)

        // S2, with the box unticked: the names for the files line, from the cached keys only. It cannot refuse a Reset.
        val names = Collections.synchronizedList(ArrayList<String>())
        if (!keepFiles) {
            DestroyWipe.runWithin("zchat-reset-S2-names", LIMIT_NAMES_MS) { runBlocking { cachedFileNames(names) } }
        }
        val files = synchronized(names) { names.toList() }

        // S3: the record, in what is left of S1's time.
        val keep =
            when {
                keepFiles -> ResetWipe.KEEP_ALL
                keptKey -> ResetWipe.KEEP_AI_KEY
                else -> ResetWipe.KEEP_NONE
            }
        if (!ResetWipe.writeResetRecord(context, keep, tag, files, budget.coerceAtLeast(1L))) {
            return notStarted("the record was not written", tag, writing)
        }

        try {
            if (!keepFiles) DestroyWipe.runWithin("zchat-reset-S4-nobody", LIMIT_NOBODY_MS) { runBlocking { markChatsWiped() } }
            stopEverything()
            DestroyWipe.runWithin("zchat-reset-S6-wallet", LIMIT_WALLET_MS) {
                runBlocking { encryptedPreferenceProvider().clearPreferences() }
            }
        } finally {
            appRestarter.restart()
        }
        return ResetAnswer.STARTED
    }

    /** S5: from here no start of the sync service is let through in this process; then the stops, the wake, Flexa. */
    @Suppress("TooGenericExceptionCaught")
    private fun stopEverything() {
        ResetWipe.holdForReset()
        DestroyWipe.runWithin("zchat-reset-S5-stops", LIMIT_STOPS_MS) {
            quietly("background work") { DestroyWipe.stopBackgroundWork(context) }
            quietly("notifications") { DestroyWipe.stopTraces(context, onlyNotifications = true) }
            quietly("memory") { clearInMemoryData() }
            ResetWipe.armWake(context)
            quietly("Flexa") { runBlocking { flexaRepository.disconnect() } }
        }
    }

    /** The cached Contacts key and every cached notes key, each turned into the name of its file. */
    private suspend fun cachedFileNames(names: MutableList<String>) {
        addressBookKeyStorageProvider.getAddressBookKey()?.let { names += it.fileIdentifier() }
        metadataKeyStorageProvider.cachedKeys().forEach { names += it.fileIdentifier() }
    }

    /** Nothing was deleted; the kept file this Reset wrote, if any, is removed again. */
    private fun notStarted(
        why: String,
        tag: String? = null,
        writing: AtomicBoolean = AtomicBoolean(false),
    ): ResetAnswer {
        Twig.warn { "Reset: not started ($why)" }
        if (tag != null && writing.get()) {
            DestroyWipe.runWithin("zchat-reset-kept-file-back", LIMIT_NOBODY_MS) { aiResetKeep.forget(tag) }
        }
        return ResetAnswer.NOT_STARTED
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun quietly(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Throwable) {
            Twig.warn { "Reset: $what (${e.javaClass.simpleName})" }
        }
    }

    /** The owner tag of the saved wallet (the one [recordChatOwner] writes), or null with no saved wallet. */
    private suspend fun resetWalletTag(): String? =
        persistableWalletProvider.getPersistableWallet()?.let { wallet -> chatOwnerTag(wallet.seedPhrase.split) }

    private suspend fun recordChatOwner() {
        val wallet = persistableWalletProvider.getPersistableWallet() ?: return
        withContext(Dispatchers.IO) { chatWalletOwnership.recordOwnerIfUnset(chatOwnerTag(wallet.seedPhrase.split)) }
    }

    private suspend fun markChatsWiped() = withContext(Dispatchers.IO) { chatWalletOwnership.markWiped() }

    private fun clearInMemoryData() {
        homeMessageCacheRepository.reset()
        // ZC-946-3 (review 4, nit 3): the old wallet's decrypted memos leave memory with it.
        transactionRepository.forgetMemos()
    }
}

// Spec 4.4: S1 with S3 5 s together; S1b, S2 and S6 2 s each; S4 1 s; S5 3 s.
private const val LIMIT_OWNER_AND_RECORD_MS = 5_000L
private const val LIMIT_AI_KEY_MS = 2_000L
private const val LIMIT_NAMES_MS = 2_000L
private const val LIMIT_NOBODY_MS = 1_000L
private const val LIMIT_STOPS_MS = 3_000L
private const val LIMIT_WALLET_MS = 2_000L

// AI-KEY-R: step S1b's read and write of the kept file.
private const val S1B_READING = 0
private const val S1B_WRITING = 1
private const val S1B_GIVEN_UP = 2
private const val S1B_PUBLISHING = 3

// RESET-PAY: the states in which the TEX recovery is still at work on a payment (spec 4.9 rule 3).
private val OPEN_TEX_STATES =
    setOf(
        TexState.SENDING_STEP1,
        TexState.SENDING_STEP2,
        TexState.CHECKING,
        TexState.NEEDS_TOR,
        TexState.RETURNED,
        TexState.SHIELDING,
    )
