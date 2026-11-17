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
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F9 (fix-all 2.13.0, train T1): the Spendable sheet says what is held for a transaction in progress.
 *
 * The headline counts locked value (TotalBalanceIncludesLockedTest), which the SDK leaves out of every `total` and of
 * `available`. While notes are locked (a Keystone PCZT that is open, a proved transfer of the SDK's private Orchard
 * move) the Spendable sheet has no row for them, and when everything else is spendable it even says "All your funds
 * are shielded and spendable." Wanted: a row "Held for a transaction in progress" with the locked value, a message
 * that says so instead of "all spendable", with the figure hidden when balances are hidden, and nothing new when
 * nothing is locked. The copy promises nothing about when the value comes back: the SDK decides that.
 *
 * Titles and messages are resolved to their English resource text. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendableBalanceHeldRowTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun locked_value_has_its_own_row() {
        val state = spendableState(ironwoodLocked = 50_000)

        val held = state.rows.firstOrNull { english(it.title) == HELD_TITLE }
        assertNotNull(held, "no \"$HELD_TITLE\" row; rows: ${describe(state.rows)}")
        assertEquals(Zatoshi(50_000), (held.value as? StringResource.ByZatoshi)?.zatoshi)
    }

    @Test
    fun a_lock_is_never_called_all_spendable() {
        val state = spendableState(ironwoodLocked = 50_000)

        val message = english(state.message)
        assertFalse(ALL_SPENDABLE in message, "50,000 zat is locked, yet the sheet says: \"$message\"")
        assertTrue(HELD_MESSAGE in message, "the message must say what is held: \"$message\"")
        assertTrue("50000 zat" in message, "the message must name the held figure: \"$message\"")
        val hidden = english(state.hiddenMessage)
        assertTrue(HELD_MESSAGE in hidden, "the hidden-balance message must say it too: \"$hidden\"")
        assertFalse(" zat" in hidden, "a hidden balance must not show the held figure: \"$hidden\"")
    }

    /** Control: nothing locked, nothing new. */
    @Test
    fun without_a_lock_there_is_no_held_row() {
        val state = spendableState(ironwoodLocked = 0)

        assertFalse(state.rows.any { english(it.title) == HELD_TITLE }, "rows: ${describe(state.rows)}")
        assertFalse(HELD_MESSAGE in english(state.message))
    }

    @Test
    fun the_held_texts_exist_in_english_and_spanish_and_promise_nothing() {
        val english = ChatResourceText.strings("values")
        val spanish = ChatResourceText.strings("values-es")
        for (key in listOf("balance_action_info_held", "balance_action_held")) {
            val en = english[key]
            val es = spanish[key]
            assertNotNull(en, "values/ has no $key")
            assertNotNull(es, "values-es/ has no $key")
            assertFalse(en == es, "$key: the Spanish is a translation, not a copy")
            assertEquals(placeholders(en), placeholders(es), "$key: the same placeholders in both languages")
            assertFalse(
                Regex("""\b(soon|minutes?|hours?|automatically|pronto|minutos?|horas?|automáticamente)\b""")
                    .containsMatchIn(en + " " + es),
                "$key must not promise when the held value comes back: \"$en\" / \"$es\"",
            )
        }
    }

    // --------------------------------------------------------------------------------- harness

    private fun placeholders(text: String): Set<String> =
        Regex("""%(\d+)\$([sd])""").findAll(text).map { it.value }.toSet()

    private class NoTransactions : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)

        override suspend fun getMemos(transaction: Transaction): List<String> = emptyList()

        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

        override suspend fun getTransactions(): List<Transaction> = emptyList()

        override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()
    }

    private fun spendableState(ironwoodLocked: Long): SpendableBalanceState {
        val accountDataSource = Zc101FakeAccountDataSource(runBlocking { account(ironwoodLocked) })
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
            is StringResource.ByResource -> {
                val template = englishById[resource.resource] ?: "<string ${resource.resource}>"
                resource.args.foldIndexed(template) { index, text, arg ->
                    val value = if (arg is StringResource) english(arg) else arg.toString()
                    text.replace("%${index + 1}\$s", value).replace("%${index + 1}\$d", value)
                }
            }
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
        const val HELD_TITLE = "Held for a transaction in progress"
        const val HELD_MESSAGE = "is held for a transaction in progress"
        const val ALL_SPENDABLE = "All your funds are shielded and spendable."

        fun balance(
            available: Long,
            locked: Long = 0
        ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0), Zatoshi(locked))

        suspend fun account(ironwoodLocked: Long) =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified =
                    UnifiedInfo(
                        WalletAddress.Unified.new("usynthetic-self"),
                        balance(0),
                        balance(443_002, ironwoodLocked),
                    ),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )

        /** Every English `<string>` in ui-lib, by resource id. */
        val englishById: Map<Int, String> by lazy {
            val byName = ChatResourceText.strings("values")
            R.string::class.java.fields
                .mapNotNull { field -> byName[field.name]?.let { field.getInt(null) to it } }
                .toMap()
        }
    }
}
