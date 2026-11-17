package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.model.Zatoshi

/**
 * Why a send the SDK refused with "Insufficient balance" cannot go out, judged from the wallet's state
 * rather than from the exception type.
 *
 * The shortfall sheet used to open with "Your previous transaction is still confirming" for every
 * refusal, pending or not. On the owner's phone nothing was pending (443,002 Ironwood and 139,000 sealed
 * Orchard, all confirmed), so the wait it asked for could never help. "Still confirming" is now said
 * only when waiting really would cover the send.
 */
sealed interface ShortfallReason {
    /** Ironwood that is still confirming would cover [need] once it confirms. */
    data class StillConfirming(
        val pending: Zatoshi,
        val need: Zatoshi,
    ) : ShortfallReason

    /**
     * Waiting would not cover [need], but the sealed Orchard [orchard] would if it could pay another
     * person, which is why the balance looks sufficient.
     */
    data class OrchardStranded(
        val orchard: Zatoshi,
        val have: Zatoshi,
        val need: Zatoshi,
    ) : ShortfallReason

    /**
     * Not enough that can reach another person. [need] is null when the SDK gave no figure; [pending] is
     * Ironwood still confirming, shown as a note only.
     */
    data class NotEnough(
        val have: Zatoshi,
        val need: Zatoshi?,
        val pending: Zatoshi,
    ) : ShortfallReason

    companion object {
        /**
         * Extra fee the confirming notes can add once they are spendable: one logical action each,
         * counted conservatively as two notes. Send all's precheck and [classifyWalletSend] use it (ZC-1948); the
         * chat's [classify] follows [SpendableCover.coversOnceConfirmed] since ZC-1909.
         */
        internal val CONFIRMING_NOTES_FEE = Zatoshi(Zip317.MARGINAL_FEE_ZATOSHI * Zip317.GRACE_ACTIONS)

        /** [need] is the SDK's "need R including fee", or null when it gave none. The chat's rule (ZC-1909). */
        fun classify(
            account: WalletAccount,
            need: Zatoshi?,
        ): ShortfallReason = classifyBy(account, need, walletMargin = false)

        /**
         * ZC-1948 — [classify] for the wallet's own Send, Swap and New Chat compose, whose "still confirming" text
         * promises the wallet will cover the send: StillConfirming only when have + pending reach [need] plus
         * [CONFIRMING_NOTES_FEE] (the confirming notes each add an action fee the SDK's need did not count yet).
         */
        fun classifyWalletSend(
            account: WalletAccount,
            need: Zatoshi?,
        ): ShortfallReason = classifyBy(account, need, walletMargin = true)

        private fun classifyBy(
            account: WalletAccount,
            need: Zatoshi?,
            walletMargin: Boolean,
        ): ShortfallReason {
            val ironwood = account.unified.ironwood
            val have = ironwood.available
            val pending = ironwood.changePending + ironwood.valuePending
            val orchard = account.unified.balance.available
            return when {
                need == null -> NotEnough(have = have, need = null, pending = pending)
                walletMargin && pending > Zatoshi(0) && have + pending >= need + CONFIRMING_NOTES_FEE ->
                    StillConfirming(pending = pending, need = need)
                // ZC-1909: the first-message gate's rule (SpendableCover), so a KEX change it accepted is a wait here.
                !walletMargin && SpendableCover.coversOnceConfirmed(have = have, pending = pending, need = need) ->
                    StillConfirming(pending = pending, need = need)
                orchard > Zatoshi(0) && have + pending + orchard >= need ->
                    OrchardStranded(orchard = orchard, have = have, need = need)
                else -> NotEnough(have = have, need = need, pending = pending)
            }
        }
    }
}
