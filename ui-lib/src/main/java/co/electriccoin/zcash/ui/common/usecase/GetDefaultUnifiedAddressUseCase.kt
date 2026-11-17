package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.OwnChatIdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Your chat identity: the unified address every chat message carries and "My Address" shows.
 *
 * The wallet computes it as the most recently exposed address that carries all receivers (Orchard,
 * Sapling and transparent). For a ZChat-only wallet that is the address written when the account was
 * created. It is not necessarily diversifier index 0, and a rescan can move it in a rare case (a
 * payment to a pre-generated full address handed out by another wallet using the same phrase).
 *
 * P2g: so it never moves under your peers, the first address read for a wallet account is saved in
 * [identityStore] and returned from then on; for an upgrading install that is the first 2.13.0 read.
 * Every saved address belongs to the account's keys, so payments to it always arrive. [identityStore]
 * is resolved on first use and only touched off the main thread (it opens the chat stores).
 *
 * The rotating Receive address (`account.unified.address`) is a different address on every visit
 * and is never this one.
 */
class GetDefaultUnifiedAddressUseCase(
    private val synchronizerProvider: SynchronizerProvider,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val identityStore: () -> OwnChatIdentityStore = { OwnChatIdentityStore.NotPinned },
) {
    /** The chat identity of the selected account: the saved one, or the wallet's (then saved). */
    suspend operator fun invoke(): String = forAccount(getSelectedWalletAccount())

    /**
     * T1b-1 — the chat identity of [selectedAccount] (the saved one, or the wallet's, then saved), so a caller that
     * also needs to know which account the identity belongs to reads the account once.
     */
    suspend fun forAccount(selectedAccount: WalletAccount): String {
        val accountKey = accountKeyOf(selectedAccount)
        withContext(Dispatchers.IO) { identityStore().pinnedIdentity(accountKey) }?.let { return it }

        val synchronizer = synchronizerProvider.getSynchronizer()

        // Use the SDK account directly from the selected wallet account
        // This avoids UUID matching issues after wallet restore
        val fresh = synchronizer.getUnifiedAddress(selectedAccount.sdkAccount)
        return withContext(Dispatchers.IO) { identityStore().pinIdentityIfAbsent(accountKey, fresh) }
    }

    /**
     * ZC-350: true when the selected account's chat identity is saved (P2g). A saved identity never moves, so the
     * wallet's next shielded address cannot change it; a caller asks this before it asks the wallet for a new address.
     */
    suspend fun isPinned(): Boolean {
        val accountKey = accountKeyOf(getSelectedWalletAccount())
        return withContext(Dispatchers.IO) { identityStore().pinnedIdentity(accountKey) } != null
    }

    companion object {
        /** The key a chat identity (and, T1b-1, an owed KEX) is saved under for [account]: its UUID in hex. */
        fun accountKeyOf(account: WalletAccount): String =
            account.sdkAccount.accountUuid.value
                .joinToString("") { "%02x".format(it) }
    }
}
