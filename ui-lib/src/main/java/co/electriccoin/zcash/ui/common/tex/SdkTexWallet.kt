package co.electriccoin.zcash.ui.common.tex

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionRecipient
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeGuard
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * F3: [TexWallet] over the SDK, for the ZCHAT account (the one account with a spending key; a Keystone account
 * cannot send TEX, F2 refuses it).
 *
 * [transactions] reads only what a TEX send can be: the sent transactions whose expiry height is shared with an
 * unmined sent transaction (a pending or expired leg 2), with their outputs from one bulk query, plus every other
 * transaction without outputs (the recovery looks up its own shields by id). A wallet with thousands of mined chat
 * sends costs one list read per pass. The sent transactions of those expiry heights also carry their raw bytes, which
 * name the outpoints they spend (T3-fixup: two sends of one block are told apart by them).
 *
 * An output is "own" ([TexOutput.isOwn]) only when it pays the ZCHAT account itself. SDK 3.0.0 names the account of
 * the wallet an output pays, whichever account that is; a payment to another account of the same wallet (an imported
 * viewing key, a Keystone) is external here, as a payment outside the wallet is (T3b-3: such a TEX send was invisible).
 */
class SdkTexWallet(
    private val synchronizerProvider: SynchronizerProvider,
    private val accountDataSource: AccountDataSource,
    private val proposalDataSource: ProposalDataSource,
    private val spendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val persistableWalletProvider: PersistableWalletProvider,
) : TexWallet {
    private val kinds = ConcurrentHashMap<String, TexAddressKind>()

    /** The overviews of the last [transactions] read, by txIdString (for [resubmit]'s raw bytes). */
    @Volatile
    private var lastOverviews: Map<String, TransactionOverview> = emptyMap()

    override suspend fun scannedTip(): Long? =
        synchronizerProvider.synchronizer.value
            ?.fullyScannedHeight
            ?.value
            ?.value

    override suspend fun transactions(): List<TexTxFacts> {
        val synchronizer = synchronizerProvider.getSynchronizer()
        val account = accountDataSource.getZashiAccount()
        val ourAccount = account.sdkAccount.accountUuid
        val overviews = synchronizer.getTransactions(ourAccount).first()
        lastOverviews = overviews.associateBy { it.txId.txIdString() }
        val openExpiries =
            overviews
                .filter { it.isSentTransaction && it.minedHeight == null && it.expiryHeight != null }
                .mapNotNull { it.expiryHeight?.value }
                .toSet()
        val recipients: Map<String, List<TransactionRecipient>> =
            if (openExpiries.isEmpty()) {
                emptyMap()
            } else {
                synchronizer.getRecipients().entries.associate { (id, list) -> id.txIdString() to list }
            }
        return overviews.map { overview ->
            val txId = overview.txId.txIdString()
            val relevant = overview.isSentTransaction && overview.expiryHeight?.value in openExpiries
            TexTxFacts(
                txId = txId,
                rawIdHex =
                    overview.txId.value.byteArray
                        .toHex(),
                isSent = overview.isSentTransaction,
                isShielding = overview.isShielding,
                minedHeight = overview.minedHeight?.value,
                expiryHeight = overview.expiryHeight?.value,
                outputs =
                    if (relevant) recipients[txId].orEmpty().mapNotNull { it.toTexOutput(ourAccount) } else emptyList(),
                // T3-fixup (G-F3-2): leg 2 names the outpoint of leg 1 it spends; read only where legs are paired.
                rawTxHex = if (relevant) overview.raw?.byteArray?.toHex() else null,
            )
        }
    }

    override suspend fun lookup(ephemeralAddress: String): Boolean {
        val account = accountDataSource.getZashiAccount()
        return synchronizerProvider.getSynchronizer().fetchUtxosByAddress(
            accountUuid = account.sdkAccount.accountUuid,
            address = ephemeralAddress,
        )
    }

    override suspend fun proposeShield(): TexShieldPlan? {
        NetworkUpgradeGuard.refusal()?.let { throw it }
        val account = accountDataSource.getZashiAccount()
        val proposal =
            synchronizerProvider.getSynchronizer().proposeShielding(
                account = account.sdkAccount,
                shieldingThreshold = Zatoshi(TexRules.SHIELD_THRESHOLD_ZAT),
                // An empty memo, as the wallet's own shield (no default memo prefix).
                memo = "",
                // SDK 3.0.0 does not take an ephemeral address here; null selects it when it is the only one due.
                transparentReceiver = null,
            ) ?: return null
        val shape =
            checkNotNull(
                readTexShieldShape(proposal.toByteArray(), proposal.transactionCount(), proposal.totalFeeRequired().value),
            ) { "The shield proposal could not be read" }
        return TexShieldPlan(shape = shape, proposal = proposal)
    }

    override suspend fun submitShield(
        plan: TexShieldPlan,
        onStored: OnTransactionsStored,
    ): SubmitResult =
        proposalDataSource.submitTransaction(
            proposal = checkNotNull(plan.proposal) { "no proposal to submit" },
            usk = spendingKeyDataSource.getZashiSpendingKey(),
            onStored = onStored,
        )

    override suspend fun resubmit(leg: TexTxFacts): Boolean {
        val overview = lastOverviews[leg.txId] ?: return false
        val raw = overview.raw ?: return false
        val synchronizer = synchronizerProvider.getSynchronizer()
        val endpoint = persistableWalletProvider.requirePersistableWallet().endpoint
        synchronizer.broadcaster.submit(CreatedTransaction(overview.txId.value, raw, overview.expiryHeight), endpoint)
        return true
    }

    /** T3b-3: own only when [ourAccount] (the ZCHAT account) is the recipient, not any account of the wallet. */
    private suspend fun TransactionRecipient.toTexOutput(ourAccount: AccountUuid): TexOutput? {
        @Suppress("USELESS_ELVIS")
        val address = addressValue ?: return null
        return TexOutput(address = address, isOwn = accountUuid == ourAccount, kind = kindOf(address))
    }

    private suspend fun kindOf(address: String): TexAddressKind =
        kinds[address] ?: run {
            val kind =
                when (synchronizerProvider.getSynchronizer().validateAddress(address)) {
                    AddressType.Tex -> TexAddressKind.TEX
                    AddressType.Transparent -> TexAddressKind.TRANSPARENT
                    else -> TexAddressKind.OTHER
                }
            kinds[address] = kind
            kind
        }
}

/** F3: the Tor switch. On is stored as the user's own choice would be; the wallet restarts with it and it stays. */
class StoredTexTor(
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val synchronizerProvider: SynchronizerProvider,
) : TexTor {
    override suspend fun isOn(): Boolean = isTorEnabledStorageProvider.get() == true

    override suspend fun isFailing(): Boolean =
        synchronizerProvider.synchronizer.value?.initializationError == Synchronizer.InitializationError.TOR_NOT_AVAILABLE

    override suspend fun isExplicitlyOff(): Boolean = isTorEnabledStorageProvider.get() == false

    override suspend fun turnOn() {
        isTorEnabledStorageProvider.store(true)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
