package co.electriccoin.zcash.ui.screen.moveoldbalance

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.OrchardSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.PersistableWalletTorProvider
import co.electriccoin.zcash.ui.common.provider.TorState
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.lang.reflect.Proxy
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RED pin: the move must never tell the user that money is gone for good when it is not.
 *
 * Commit 1f7c1637e added a "stranded" figure to the Consent and Review screens: the account's Orchard
 * `available` minus the proposal's inputs, shown as "... can never be spent or moved again". That
 * figure assumed the SDK counts dust notes in `available` and then leaves them out of the sweep. It
 * does not. In SDK 3.0.0 (zcash_client_sqlite 0.22.0, the crate the shipped .so is built from):
 *  - the balance books any note of value <= ZIP-317 MARGINAL_FEE (5,000 zat) as `uneconomic_value`
 *    (wallet.rs, `if value <= zip317::MARGINAL_FEE`), which `Balance::total()` leaves out and the JNI
 *    never exports, so it is not in `WalletBalance.available` at all;
 *  - the sweep (`propose_send_max_transfer`, `MaxSpendMode::Everything`, same default confirmations
 *    policy) selects every note with `value > MARGINAL_FEE`, and fails outright rather than skipping
 *    a note that is not eligible yet.
 * Both split at the same line, so on one consistent wallet state the inputs equal `available` and
 * the figure is zero. It becomes positive only when the balance snapshot the sheet holds counts a
 * note the wallet DB no longer offers (a balance race: e.g. another device on the same seed spent
 * it). The figure it then shows is neither too small nor stuck, and the screen that commits the move
 * says it is lost forever. That false permanent-loss statement is the defect pinned here.
 *
 * The dust that really does stay behind cannot be named from Kotlin (the JNI does not export
 * `uneconomic_value`), so the only true disclosure is the generic threshold line, which must stay.
 *
 * The state pins go through reflection on purpose: the fix removes the property, and a RED test must
 * compile against the tree it is failing on.
 *
 * All addresses, balances and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveOldBalanceStrandedTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The sheet's snapshot says 0.00142 ZEC, the wallet DB builds a sweep of 0.00139 ZEC. On SDK 3.0.0
     * that gap cannot be dust; it is a race. Nothing may be shown as lost forever.
     */
    @Test
    fun a_balance_race_is_never_shown_as_money_lost_for_good_on_the_consent_screen() =
        runBlocking {
            val vm = harness(orchardAvailable = 142_000, sweptInputs = 139_000)

            val consent = vm.await(MoveOldBalanceState.Consent::class)

            assertEquals(Zatoshi(139_000), consent.amount, "the sheet still shows what the sweep really moves")
            assertNull(
                consent.strandedOrNull(),
                "available and the sweep both exclude notes of 5,000 zat or less, so a gap between them is " +
                    "a stale balance, not money that can never be moved: no permanent-loss figure may be shown",
            )
        }

    /** The Review screen commits the move; a false loss statement there is worse, not better. */
    @Test
    fun a_balance_race_is_never_shown_as_money_lost_for_good_on_the_review_screen() =
        runBlocking {
            val vm = harness(orchardAvailable = 142_000, sweptInputs = 139_000)

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            val review = vm.current<MoveOldBalanceState.Review>()

            assertEquals(Zatoshi(139_000), review.amount, "the review still shows what the sweep really moves")
            assertNull(
                review.strandedOrNull(),
                "Review is the last screen before the biometric prompt; it must not claim a permanent loss " +
                    "that a balance race produced",
            )
        }

    /** The normal case: the sweep takes the whole available balance and nothing is claimed. */
    @Test
    fun nothing_is_claimed_when_the_sweep_takes_the_whole_orchard_balance() =
        runBlocking {
            val vm = harness(orchardAvailable = 139_000, sweptInputs = 139_000)

            val consent = vm.await(MoveOldBalanceState.Consent::class)
            assertNull(consent.strandedOrNull(), "nothing is left behind, so nothing is claimed")

            consent.onContinue()
            assertNull(
                vm.current<MoveOldBalanceState.Review>().strandedOrNull(),
                "and nothing on the review either",
            )
        }

    /**
     * Copy, in both languages: no line of this sheet may state a permanent loss of a figure the app
     * cannot know, and the only true dust disclosure (the generic threshold line) stays.
     */
    @Test
    fun the_copy_makes_no_permanent_loss_claim_and_keeps_the_generic_dust_line() {
        val english = strings("values")
        val spanish = strings("values-es")

        assertFalse(english.containsKey(KEY), "$FILE_NAME must not define $KEY: \"${english[KEY]}\"")
        assertFalse(spanish.containsKey(KEY), "values-es/$FILE_NAME must not define $KEY: \"${spanish[KEY]}\"")

        english.forEach { (name, text) ->
            assertTrue(
                PERMANENCE.none { text.contains(it, ignoreCase = true) },
                "$name must not claim a loss is permanent, none of $PERMANENCE: \"$text\"",
            )
        }
        spanish.forEach { (name, text) ->
            assertTrue(
                PERMANENCE_ES.none { text.contains(it, ignoreCase = true) },
                "values-es $name must not claim a loss is permanent, none of $PERMANENCE_ES: \"$text\"",
            )
            assertFalse(
                text.contains("fondo Orchard", ignoreCase = true),
                "values-es $name: the sealed pool is \"pool Orchard\" everywhere on this sheet: \"$text\"",
            )
        }

        val dust = english[DUST_KEY] ?: fail("$FILE_NAME must keep $DUST_KEY")
        val dustEs = spanish[DUST_KEY] ?: fail("values-es/$FILE_NAME must keep $DUST_KEY")
        assertTrue(dust.contains("%1\$s"), "the dust threshold is a formatted figure: \"$dust\"")
        assertTrue(dust.contains("Orchard"), "say where dust stays: \"$dust\"")
        assertTrue(dustEs.contains("Orchard"), "say where dust stays, in Spanish: \"$dustEs\"")
        assertEquals(placeholders(dust), placeholders(dustEs), "$DUST_KEY: same placeholders in Spanish")
    }

    // --------------------------------------------------------------------------------- harness

    /**
     * The Consent/Review `stranded` figure 1f7c1637e added, read reflectively so this compiles both
     * before and after the property is removed. Absent is the same as null: nothing is claimed.
     */
    private fun Any.strandedOrNull(): Zatoshi? {
        val getter =
            javaClass.methods.firstOrNull { it.name == "getStranded" && it.parameterCount == 0 }
                ?: return null
        return getter.invoke(this) as Zatoshi?
    }

    private suspend fun harness(
        orchardAvailable: Long,
        sweptInputs: Long,
    ): MoveOldBalanceVM {
        val accounts = Zc101FakeAccountDataSource(zashi(orchardAvailable))
        return MoveOldBalanceVM(
            accountDataSource = accounts,
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = SweepSource(sweepProposal(sweptInputs)),
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ),
            biometricRepository = Zc101SuspendingBiometricRepository().apply { approve() },
            navigationRouter = Zc101RecordingNavigationRouter(),
            isTorEnabled = IsTorEnabledUseCase(FakeTor),
            zchatPreferences = noPrefs(),
        )
    }

    private fun <T : MoveOldBalanceState> MoveOldBalanceVM.await(type: KClass<T>): T =
        runBlocking {
            @Suppress("UNCHECKED_CAST")
            withTimeout(TIMEOUT_MS) { state.first { type.isInstance(it) } } as T
        }

    private inline fun <reified T : MoveOldBalanceState> MoveOldBalanceVM.current(): T {
        val value = state.value
        assertTrue(value is T, "expected ${T::class.simpleName}, got $value")
        return value
    }

    /** Builds the sweep from [proposal] and does nothing else. */
    private class SweepSource(
        private val proposal: Proposal,
        recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        override suspend fun createOrchardSweepProposal(account: WalletAccount): OrchardSweepTransactionProposal =
            OrchardSweepTransactionProposal.fromProposal(proposal)
    }

    private object FakeTor : PersistableWalletTorProvider {
        override fun observe(): Flow<TorState?> = flowOf(TorState.EXPLICITLY_ENABLED)

        override suspend fun get(): TorState = TorState.EXPLICITLY_ENABLED
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val FILE_NAME = "move_old_balance_strings.xml"
        const val KEY = "move_old_balance_consent_stranded"
        const val DUST_KEY = "move_old_balance_consent_dust"

        /** Any of these states, of money, a permanence the app cannot prove for a figure it computed. */
        val PERMANENCE =
            listOf("never be spent", "never be moved", "can't be recovered", "cannot be recovered", "gone for good")

        /** The Spanish forms of the same claim ("never" is not a substring of "nunca"). */
        val PERMANENCE_ES =
            listOf("nunca se podrá", "nunca se puede", "no se puede recuperar", "no se podrá recuperar", "para siempre")

        fun sweepProposal(inputs: Long): Proposal =
            SweepProposalBytes.proposal(listOf(SweepProposalBytes.orchard(inputs)), fee = 20_000)

        suspend fun zashi(orchard: Long): ZashiAccount {
            val none = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
            return ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified =
                    UnifiedInfo(
                        WalletAddress.Unified.new("usynthetic-self"),
                        WalletBalance(Zatoshi(orchard), Zatoshi(0), Zatoshi(0)),
                        none,
                    ),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), none),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
        }

        /** No handshake consent and no queued peers: this test is only about the remainder figure. */
        fun noPrefs(): ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getTunnelModePeers" -> emptyList<String>()
                    "toString" -> "noPrefs"
                    "hashCode" -> 0
                    "equals" -> false
                    else ->
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            else -> null
                        }
                }
            } as ZchatPreferences

        fun placeholders(text: String): Set<String> =
            Regex("""%(\d+)\$([sd])""").findAll(text).map { it.value }.toSet()

        fun strings(qualifier: String): Map<String, String> {
            val file = uiLibDir.resolve("src/main/res/ui/send_confirmation/$qualifier/$FILE_NAME")
            assertTrue(file.isFile, "missing ${file.path}")
            val nodes =
                DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .parse(file)
                    .getElementsByTagName("string")
            return (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .associate { it.getAttribute("name") to it.textContent }
        }

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/send_confirmation").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
