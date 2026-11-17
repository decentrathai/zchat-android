package co.electriccoin.zcash.ui.screen.send.model

import androidx.compose.runtime.saveable.mapSaver
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeRequiredException
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import co.electriccoin.zcash.ui.common.model.PublicCrossingException
import co.electriccoin.zcash.ui.common.model.SaplingCrossingException

sealed class SendStage {
    data object Form : SendStage()

    data object Proposing : SendStage()

    data class SendFailure(
        val error: String
    ) : SendStage()

    companion object {
        // Stable, locale-independent reason keys persisted in [SendFailure.error]. The view maps
        // these to localized strings; any other value is treated as an unknown/generic failure.
        // See #1276.
        // A fallback only: CreateProposalUseCase routes a wallet shortfall to the InsufficientFunds sheet
        // with its cause and numbers, so the Send screen does not normally see one.
        const val REASON_INSUFFICIENT_FUNDS = "insufficient_funds" // $NON-NLS
        const val REASON_PROPOSAL_NOT_CREATED = "proposal_not_created" // $NON-NLS
        const val REASON_ORCHARD_SEALED = "orchard_sealed" // $NON-NLS
        const val REASON_IRONWOOD_CONFIRMING = "ironwood_confirming" // $NON-NLS
        const val REASON_PUBLIC_CROSSING = "public_crossing" // $NON-NLS
        const val REASON_SAPLING_CROSSING = "sapling_crossing" // $NON-NLS
        const val REASON_UPDATE_REQUIRED = "update_required" // $NON-NLS
        const val REASON_GENERIC = "generic" // $NON-NLS

        /**
         * Map a proposal failure to its reason key. Pure so the mapping is unit-testable: a sealed-pool
         * refusal with Ironwood value still confirming is a "wait", not a "migrate".
         */
        fun reasonFor(e: Throwable): String =
            when (e) {
                is NetworkUpgradeRequiredException -> REASON_UPDATE_REQUIRED
                is PublicCrossingException -> REASON_PUBLIC_CROSSING
                is SaplingCrossingException -> REASON_SAPLING_CROSSING
                is OrchardSealedException ->
                    if (e.ironwoodPending > Zatoshi(0)) REASON_IRONWOOD_CONFIRMING else REASON_ORCHARD_SEALED
                is InsufficientFundsException -> REASON_INSUFFICIENT_FUNDS
                is TransactionProposalNotCreatedException -> REASON_PROPOSAL_NOT_CREATED
                else -> REASON_GENERIC
            }

        private const val TYPE_FORM = "form" // $NON-NLS
        private const val TYPE_PROPOSING = "proposing" // $NON-NLS
        private const val TYPE_FAILURE = "failure" // $NON-NLS
        private const val KEY_TYPE = "type" // $NON-NLS
        private const val KEY_ERROR = "error" // $NON-NLS

        internal val Saver
            get() =
                run {
                    mapSaver<SendStage>(
                        save = { it.toSaverMap() },
                        restore = {
                            if (it.isEmpty()) {
                                null
                            } else {
                                val sendStageString = (it[KEY_TYPE] as String)
                                when (sendStageString) {
                                    TYPE_FORM -> Form
                                    TYPE_PROPOSING -> Proposing
                                    TYPE_FAILURE -> SendFailure((it[KEY_ERROR] as String))
                                    else -> null
                                }
                            }
                        }
                    )
                }

        private fun SendStage.toSaverMap(): HashMap<String, String> {
            val saverMap = HashMap<String, String>()
            when (this) {
                Form -> saverMap[KEY_TYPE] = TYPE_FORM
                Proposing -> saverMap[KEY_TYPE] = TYPE_PROPOSING
                is SendFailure -> {
                    saverMap[KEY_TYPE] = TYPE_FAILURE
                    saverMap[KEY_ERROR] = this.error
                }
            }

            return saverMap
        }
    }
}
