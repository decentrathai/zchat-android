package co.electriccoin.zcash.ui.keystone

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.usecase.syntheticRegularSendProposal
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.util.Collections
import java.util.UUID
import kotlin.reflect.KClass

/**
 * K0 (fix-all 2.13.0, train T1) — shared fakes for the Keystone-close tests. No mocking library: hand-written fakes
 * over the real interfaces. Every address, key and URI is synthetic; the Keystone account uses the SDK's public test
 * fixture with its own synthetic account id.
 */
internal object KeystoneCloseFixture {
    val KEYSTONE_UUID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c0")

    suspend fun zashi(selected: Boolean = true): ZashiAccount = syntheticZashiAccount().copy(isSelected = selected)

    suspend fun keystone(selected: Boolean = true): KeystoneAccount {
        val zero = Zatoshi(0)
        return KeystoneAccount(
            sdkAccount = AccountFixture.new(accountUuid = KEYSTONE_UUID),
            unified =
                UnifiedInfo(
                    WalletAddress.Unified.new("usynthetic-k0-keystone-self"),
                    WalletBalance(zero, zero, zero),
                ),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-k0-keystone-self"), zero),
            isSelected = selected,
        )
    }
}

/**
 * The accounts of one phone: always the ZChat (Zashi) account, and a Keystone account once [addKeystone] is called.
 * [select] switches the selected one; [allAccounts] carries the isSelected flags the real data source sets. Records
 * every import and every selection.
 */
internal class TwoAccountDataSource private constructor(
    private val zashi: ZashiAccount,
    private val keystone: KeystoneAccount,
) : AccountDataSource {
    private var hasKeystone = false
    private val selected = MutableStateFlow<WalletAccount>(zashi)
    private val all = MutableStateFlow<List<WalletAccount>?>(listOf(zashi))

    val imports: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val selections: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun addKeystone(selectIt: Boolean) {
        hasKeystone = true
        publish(if (selectIt) keystone else zashi)
    }

    fun select(which: WalletAccount) = publish(which)

    fun keystoneAccount(): KeystoneAccount = keystone

    fun zashiAccount(): ZashiAccount = zashi

    private fun publish(which: WalletAccount) {
        val isKeystone = which is KeystoneAccount
        val z = zashi.copy(isSelected = !isKeystone)
        val k = keystone.copy(isSelected = isKeystone)
        selected.value = if (isKeystone) k else z
        all.value = if (hasKeystone) listOf(z, k) else listOf(z)
    }

    override val allAccounts: StateFlow<List<WalletAccount>?> = all
    override val selectedAccount: Flow<WalletAccount?> = selected
    override val zashiAccount: Flow<ZashiAccount?> = selected.map { zashi }

    override suspend fun getAllAccounts(): List<WalletAccount> = all.value.orEmpty()

    override suspend fun getSelectedAccount(): WalletAccount = selected.value

    override suspend fun getZashiAccount(): ZashiAccount = zashi

    override suspend fun selectAccount(account: Account) {
        selections += "sdk-account"
    }

    override suspend fun selectAccount(account: WalletAccount) {
        selections += account.javaClass.simpleName
        publish(account)
    }

    override suspend fun importKeystoneAccount(
        ufvk: String,
        seedFingerprint: String,
        index: Long,
    ): Account {
        // The key itself is never recorded: only that an import was asked for.
        imports += "import(index=$index)"
        return keystone.sdkAccount
    }

    override suspend fun requestNextShieldedAddress() = Unit

    companion object {
        suspend fun zashiOnly(): TwoAccountDataSource = TwoAccountDataSource(KeystoneCloseFixture.zashi(), KeystoneCloseFixture.keystone())

        suspend fun keystoneSelected(): TwoAccountDataSource = zashiOnly().apply { addKeystone(selectIt = true) }
    }
}

/** Records every route with its command, so a test can tell `forward(TransactionProgressArgs)` from anything else. */
internal class RouteRecordingNavigationRouter : NavigationRouter {
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun record(
        command: String,
        routes: Array<out Any>,
    ) {
        commands += command + routes.joinToString(prefix = "(", postfix = ")") { routeName(it) }
    }

    override fun forward(vararg routes: Any) = record("forward", routes)

    override fun replace(vararg routes: Any) = record("replace", routes)

    override fun replaceAll(vararg routes: Any) = record("replaceAll", routes)

    override fun back() {
        commands += "back"
    }

    override fun backTo(route: KClass<*>) {
        commands += "backTo(${route.simpleName})"
    }

    override fun backToRoot() {
        commands += "backToRoot"
    }

    override fun observePipeline(): Flow<NavigationCommand> = emptyFlow()

    companion object {
        fun routeName(route: Any): String =
            when (route) {
                is KClass<*> -> route.simpleName.orEmpty()
                else -> route.javaClass.simpleName.ifEmpty { route.toString() }
            }
    }
}

/**
 * The Keystone signing session (a process-wide singleton in the app) with its calls recorded. [holdWalletSession]
 * puts a Wallet-tab proposal and PCZT in it, as if the user were half way through signing a Wallet → Send on the
 * device; a chat send must never replace or clear it.
 */
@Suppress("TooManyFunctions")
internal class RecordingKeystoneProposalRepository : KeystoneProposalRepository {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val proposal = MutableStateFlow<TransactionProposal?>(null)
    var sessionPczt: Pczt? = null
        private set

    suspend fun holdWalletSession() {
        proposal.value = syntheticRegularSendProposal("usynthetic-k0-wallet-send-peer", amount = 50_000L, memo = "")
        sessionPczt = Pczt(byteArrayOf(0x50, 0x43, 0x5A, 0x54, 0x01))
    }

    fun sessionProposal(): TransactionProposal? = proposal.value

    override val transactionProposal: Flow<TransactionProposal?> = proposal
    override val submitState: Flow<SubmitProposalState?> = MutableStateFlow(null)

    override suspend fun createProposal(zecSend: ZecSend) {
        calls += "createProposal"
        proposal.value = syntheticRegularSendProposal("usynthetic-k0-wallet-send-peer", amount = 50_000L, memo = "")
    }

    override suspend fun createExactInputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
    ): ExactInputSwapTransactionProposal = error("not used in the K0 tests")

    override suspend fun createExactOutputSwapProposal(
        zecSend: ZecSend,
        quote: SwapQuote,
    ): ExactOutputSwapTransactionProposal = error("not used in the K0 tests")

    override suspend fun createZip321Proposal(
        zip321Uri: String,
        allowMultiPayment: Boolean,
    ): Zip321TransactionProposal {
        calls += "createZip321Proposal"
        return syntheticZip321Proposal("usynthetic-k0-chat-peer", amount = 1_000L, memo = "synthetic").also {
            proposal.value = it
        }
    }

    override suspend fun createShieldProposal() {
        calls += "createShieldProposal"
    }

    override suspend fun createPCZTFromProposal() {
        calls += "createPCZTFromProposal"
        sessionPczt = Pczt(byteArrayOf(0x43, 0x48, 0x41, 0x54))
    }

    override suspend fun createPCZTEncoder(): UREncoder = error("not used in the K0 tests")

    override suspend fun parsePCZT(ur: UR) = error("not used in the K0 tests")

    override suspend fun submit(proposal: TransactionProposal): SubmitResult {
        calls += "submit"
        return SubmitResult.Failure(txIds = emptyList(), code = 0, description = "synthetic: nothing is ever signed")
    }

    override fun clear() {
        calls += "clear"
        proposal.value = null
        sessionPczt = null
    }

    override suspend fun getTransactionProposal(): TransactionProposal = checkNotNull(proposal.value)

    override fun getProposalPCZT(): Pczt? = sessionPczt
}
