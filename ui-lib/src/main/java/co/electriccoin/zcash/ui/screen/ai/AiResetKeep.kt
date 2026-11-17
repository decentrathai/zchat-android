package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.chat.datasource.SetAsideBundles

/**
 * ZC-718 (register ZC-718, high; the owner, WAIVERS.md W-52), AI-KEY-R (W-53): a Reset without "keep" deletes the AI chats
 * and the AI pictures and KEEPS what the recovery phrase cannot open, for the same recovery phrase only (the rule:
 * [AiPreferences.entriesKeptAtReset]). ResetZashiUseCase only calls this class
 * (batch ZC-090-R later calls [keep] from its step S1b and [clear] from the start pass).
 *
 * [keep] runs first, before anything is deleted, disconnected or closed: it writes what the AI store keeps
 * ([AiPreferences.entriesKeptAtReset]) as the AI store's part, and the only part, of the reset wallet's own set-aside
 * file, where a later claim for the SAME phrase puts it back and no other phrase ever reads it. A read or a write that
 * fails throws, and the Reset stops with nothing deleted.
 *
 * [clear] runs only AFTER the owner record says nobody: while it still names the reset wallet a claim for the same
 * phrase moves nothing and a claim for another phrase writes that wallet's file anew from the live stores, so the key
 * may leave the live AI store only once the kept file is on disk and the owner says nobody.
 */
class AiResetKeep(
    private val ai: AiPreferences,
    private val images: AiImageStore,
    private val bundles: SetAsideBundles,
) {
    /** What the AI store keeps at a Reset (ZC-090-R: one read, replaceable for the JVM tests of the Reset). */
    private val keptEntries: () -> Map<String, Any?> = { ai.entriesKeptAtReset() }

    /**
     * The keep step for the wallet named [walletTag] (chatOwnerTag of its phrase). Throws when it cannot keep. True
     * when it wrote the kept file; [beforeWrite] runs right before that write (ZC-090-R: a Reset that does not start
     * removes what it may have written, [forget]). [mayPublish] is asked right before the commit: false writes nothing
     * and answers false (W-97: a writer that outlives its Reset).
     */
    @JvmOverloads
    fun keep(
        walletTag: String,
        beforeWrite: () -> Unit = {},
        mayPublish: () -> Boolean = { true },
    ): Boolean {
        val kept = keptEntries()
        if (kept.isEmpty()) return false
        beforeWrite()
        var published = false
        bundles.write(walletTag, mapOf(AiPreferences.PREFS_NAME to kept)) { mayPublish().also { published = it } }
        return published
    }

    /** ZC-090-R: removes the kept file of [walletTag] again (a Reset that wrote it and then did not start). */
    fun forget(walletTag: String) {
        bundles.delete(walletTag)
    }

    /**
     * The clear for [walletTag]: the AI store emptied with a checked write, the wallet's picture folder and every flat
     * picture deleted (another wallet's folder stays); when the keep step kept nothing, an
     * older set-aside file of this wallet is deleted too, so no stale key comes back. Throws when the store could not
     * be cleared.
     */
    fun clear(walletTag: String) {
        val keptNothing = ai.entriesKeptAtReset().isEmpty()
        ai.clearForReset()
        images.deleteWalletPictures(walletTag)
        if (keptNothing) bundles.delete(walletTag)
    }
}
