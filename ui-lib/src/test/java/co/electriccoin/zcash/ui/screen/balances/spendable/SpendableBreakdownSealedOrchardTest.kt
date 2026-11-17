package co.electriccoin.zcash.ui.screen.balances.spendable

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSourceImpl
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetTransactionsUseCase
import co.electriccoin.zcash.ui.common.usecase.ShieldFundsUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeApplicationStateProvider
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeMetadataRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RED pin for owner decision 4 (2026-09-10): the Spendable sheet counts sealed Orchard as spendable.
 *
 * Its first row, "Shielded (Spendable)", shows `spendableShieldedBalance`, which adds Orchard to
 * Ironwood. After NU6.3 Orchard can pay nobody but the wallet itself, and a balance of 25,000 zatoshi or
 * less cannot even do that (the move would cost the whole of it). On the owner's phone the row read
 * 582,002 zatoshi while only 443,002 could go to anyone. No row that claims to be spendable, available
 * or sendable may count Orchard.
 *
 * The Orchard row and the explanation say it "can only move to your own wallet". For a balance too small
 * to move (the sweep's TooSmall state), that is false: it cannot move at all. The copy must be true in
 * every state, so the TooSmall sheet must not make that claim.
 *
 * Row titles and the message are resolved to their English resource text. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendableBreakdownSealedOrchardTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun no_spendable_row_counts_sealed_orchard() {
        listOf(
            // Owner's HONOR: 443,002 Ironwood + 139,000 Orchard. Orchard only: nothing can go to anyone.
            Wallet(orchard = 139_000, ironwood = 443_002),
            Wallet(orchard = 139_000, ironwood = 0),
        ).forEach { wallet ->
            val rows = spendableState(wallet).rows
            val claimsSpendable =
                rows.filter { row ->
                    val title = english(row.title)
                    SPENDABLE_CLAIM.containsMatchIn(title) && !NOT_A_CLAIM.containsMatchIn(title)
                }
            assertTrue(claimsSpendable.isNotEmpty(), "$wallet: fixture expects a spendable row; rows: ${describe(rows)}")

            claimsSpendable.forEach { row ->
                val shown = (row.value as? StringResource.ByZatoshi)?.zatoshi?.value
                assertFalse(
                    shown != null && shown > wallet.ironwood,
                    "$wallet: \"${english(row.title)}\" shows $shown zatoshi, more than the ${wallet.ironwood} " +
                        "Ironwood anyone can be paid from: sealed Orchard is counted as spendable. Rows: ${describe(rows)}",
                )
            }
        }
    }

    @Test
    fun a_balance_too_small_to_move_is_not_said_to_move_to_your_own_wallet() {
        // 20,000 zatoshi of Orchard: the move's fee alone is 20,000, so nothing could arrive.
        val state = spendableState(Wallet(orchard = 20_000, ironwood = 443_002))

        val texts = state.rows.map { english(it.title) } + english(state.message)
        texts.forEach { text ->
            assertFalse(
                OWN_WALLET_CLAIM.containsMatchIn(text),
                "an Orchard balance too small to move cannot move to your own wallet either: \"$text\"",
            )
        }
    }

    // --------------------------------------------------------------------------------- harness

    private data class Wallet(
        val orchard: Long,
        val ironwood: Long
    )

    private class NoTransactions : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)

        override suspend fun getMemos(transaction: Transaction): List<String> = emptyList()

        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

        override suspend fun getTransactions(): List<Transaction> = emptyList()

        override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()
    }

    private fun spendableState(wallet: Wallet): SpendableBalanceState {
        val accountDataSource = Zc101FakeAccountDataSource(runBlocking { account(wallet) })
        val router = Zc101RecordingNavigationRouter()
        val vm =
            SpendableBalanceVM(
                accountDataSource = accountDataSource,
                getTransactions = GetTransactionsUseCase(NoTransactions(), Zc101FakeMetadataRepository()),
                navigationRouter = router,
                shieldFunds =
                    ShieldFundsUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = accountDataSource,
                                proposalDataSource = Zc101RecordingProposalDataSource(),
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ),
                        navigationRouter = router,
                        accountDataSource = accountDataSource,
                        navigateToError = NavigateToErrorUseCase(router),
                        messageAvailabilityDataSource =
                            MessageAvailabilityDataSourceImpl(Zc101FakeApplicationStateProvider()),
                    ),
            )
        return checkNotNull(vm.state.value)
    }

    private fun describe(rows: List<SpendableBalanceRowState>) =
        rows.joinToString { "\"${english(it.title)}\" = ${english(it.value)}" }

    /** The English text of [resource], resource strings looked up by name in ui-lib's values/ files. */
    private fun english(resource: StringResource): String =
        when (resource) {
            is StringResource.ByResource ->
                (englishById[resource.resource] ?: "<string ${resource.resource}>") +
                    resource.args.joinToString("") { arg -> if (arg is StringResource) " " + english(arg) else " $arg" }
            is StringResource.ByString -> resource.value
            is StringResource.ByZatoshi -> "${resource.zatoshi.value} zat"
            else ->
                composite(resource)?.joinToString("") { english(it) } ?: resource.toString()
        }

    /** The parts of the private CompositeStringResource that `a + b` builds, or null for anything else. */
    @Suppress("UNCHECKED_CAST")
    private fun composite(resource: StringResource): List<StringResource>? =
        runCatching {
            resource.javaClass
                .getDeclaredField("resources")
                .apply { isAccessible = true }
                .get(resource) as List<StringResource>
        }.getOrNull()

    private companion object {
        /** A title that says the figure can be spent, is available, or can be sent... */
        val SPENDABLE_CLAIM = Regex("spendable|available|send", RegexOption.IGNORE_CASE)

        /** ...unless it says the opposite, or is the sealed Orchard row itself. */
        val NOT_A_CLAIM = Regex("""can't|can\\'t|cannot|can not|\bnot\b|\bonly\b|sealed|orchard""", RegexOption.IGNORE_CASE)

        val OWN_WALLET_CLAIM = Regex("only (move )?to your own wallet", RegexOption.IGNORE_CASE)

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun account(wallet: Wallet) =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified =
                    UnifiedInfo(
                        WalletAddress.Unified.new("usynthetic-self"),
                        balance(wallet.orchard),
                        balance(wallet.ironwood),
                    ),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/home").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }

        /** Every English `<string>` in ui-lib, by resource id. */
        val englishById: Map<Int, String> by lazy {
            val byName =
                uiLibDir
                    .resolve("src/main/res")
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "xml" && it.parentFile?.name == "values" }
                    .flatMap { file ->
                        val nodes =
                            DocumentBuilderFactory
                                .newInstance()
                                .newDocumentBuilder()
                                .parse(file)
                                .getElementsByTagName("string")
                        (0 until nodes.length).map { nodes.item(it) as Element }
                    }.associate { it.getAttribute("name") to it.textContent }
            R.string::class.java.fields
                .mapNotNull { field -> byName[field.name]?.let { field.getInt(null) to it } }
                .toMap()
        }
    }
}
