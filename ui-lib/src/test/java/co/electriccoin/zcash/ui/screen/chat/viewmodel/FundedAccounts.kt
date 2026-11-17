package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount

/**
 * ZC-1822 — the selected account of a fixture that is asked a price: [spendable] zatoshi of confirmed Ironwood, enough for
 * any one-transaction question (the spendable margin rule runs before each). The plain synthetic account holds nothing.
 */
internal suspend fun fundedAccounts(spendable: Long = 1_000_000L): Zc101FakeAccountDataSource {
    val base = syntheticZashiAccount()
    return Zc101FakeAccountDataSource(
        base.copy(unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(spendable), Zatoshi(0), Zatoshi(0)))),
    )
}
