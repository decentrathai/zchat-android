package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.datasource.NearSwapDataSourceImpl
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.ZecSwapAsset
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.SubmitDepositTransactionRequest
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.model.near.SwapType
import co.electriccoin.zcash.ui.common.provider.GetNearSupportedTokensResponse
import co.electriccoin.zcash.ui.common.provider.NearApiProvider
import co.electriccoin.zcash.ui.common.provider.SwapAssetProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1268 (owner go W-112): every swap quote pays its app fee to ZChat's own NEAR account, read from
 * build config (`BuildConfig.ZCHAT_SWAP_FEE_ACCOUNT`, fed from `ZCHAT_SWAP_FEE_ACCOUNT` in
 * local.properties or the environment), with referral "zchat" and 50 bps — never to the upstream
 * "electriccoinco.near" / "crosspay.near" accounts and never under referral "zashi".
 *
 * Runs the real SwapRepositoryImpl over the real NearSwapDataSourceImpl and captures the QuoteRequest
 * at the NEAR API boundary, so it holds wherever the fix places the constant. Failure messages mask
 * the account id (first 10 / last 8).
 */
class SwapFeeGoesToZchatAccountTest {
    private val chain = SwapBlockchain(chainTicker = "eth", chainName = stringRes("Ethereum"), chainIcon = imageRes(0))

    private val zec =
        ZecSwapAsset(
            tokenTicker = "ZEC",
            tokenName = stringRes("Zcash"),
            tokenIcon = imageRes(0),
            blockchain = chain,
            usdPrice = BigDecimal("30"),
            assetId = "nep141:zec.omft.near",
            decimals = 8
        )

    private val usdc =
        DynamicSwapAsset(
            tokenTicker = "USDC",
            tokenName = stringRes("USDC"),
            tokenIcon = imageRes(0),
            usdPrice = BigDecimal("1"),
            assetId = "nep141:eth-usdc.omft.near",
            decimals = 6,
            blockchain = chain
        )

    private class CapturingNearApi : NearApiProvider {
        val requests = CopyOnWriteArrayList<QuoteRequest>()

        override suspend fun getSupportedTokens(): GetNearSupportedTokensResponse = error("not used")

        override suspend fun requestQuote(request: QuoteRequest): QuoteResponseDto {
            requests += request
            // Stop after capture: the response path is not under test.
            throw IllegalStateException("captured")
        }

        override suspend fun submitDepositTransaction(request: SubmitDepositTransactionRequest) = error("not used")

        override suspend fun checkSwapStatus(depositAddress: String): SwapStatusResponseDto = error("not used")
    }

    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
            error("${T::class.java.simpleName}.${m.name} not used")
        } as T

    private fun capture(block: SwapRepositoryImpl.() -> Unit): QuoteRequest {
        val api = CapturingNearApi()
        val repo =
            SwapRepositoryImpl(
                NearSwapDataSourceImpl(
                    nearApiProvider = api,
                    swapAssetProvider = unused<SwapAssetProvider>(),
                    synchronizerProvider = unused<SynchronizerProvider>()
                )
            )
        repo.assets.value = SwapAssetsData(data = listOf(usdc), zecAsset = zec)
        repo.select(usdc)
        repo.block()
        runBlocking {
            withTimeout(10_000) { repo.quote.first { it != null && it != SwapQuoteData.Loading } }
        }
        repo.clear()
        return api.requests.singleOrNull() ?: fail("expected exactly one quote request, got ${api.requests.size}")
    }

    private fun mask(value: String?): String =
        when {
            value == null -> "null"
            value.length <= 18 -> value
            else -> value.take(10) + "…" + value.takeLast(8)
        }

    /** The configured ZChat fee account; fails if the build has no such field or it is blank. */
    private fun configuredFeeAccount(): String {
        val buildConfig = Class.forName("co.electriccoin.zcash.ui.BuildConfig")
        val field =
            runCatching { buildConfig.getField("ZCHAT_SWAP_FEE_ACCOUNT") }.getOrNull()
                ?: fail("BuildConfig.ZCHAT_SWAP_FEE_ACCOUNT is missing: the swap fee account must come from build config")
        val value = field.get(null) as? String
        assertTrue(!value.isNullOrBlank(), "BuildConfig.ZCHAT_SWAP_FEE_ACCOUNT is blank")
        return value
    }

    private fun assertZchatFee(request: QuoteRequest, expectedType: SwapType) {
        assertTrue(request.swapType == expectedType, "swapType ${request.swapType} != $expectedType")
        val text = request.toString()
        assertTrue(!text.contains("electriccoinco.near"), "$expectedType quote request names electriccoinco.near")
        assertTrue(!text.contains("crosspay.near"), "$expectedType quote request names crosspay.near")
        val fees = request.appFees
        assertTrue(fees.size == 1, "$expectedType: expected exactly one app fee, got ${fees.size}")
        val fee = fees.single()
        assertTrue(fee.fee == 50, "$expectedType app fee bps ${fee.fee} != 50")
        assertTrue(request.referral == "zchat", "$expectedType referral '${request.referral}' != 'zchat'")
        val account = configuredFeeAccount()
        assertTrue(
            fee.recipient == account,
            "$expectedType app fee recipient ${mask(fee.recipient)} != ZChat fee account ${mask(account)}"
        )
    }

    @Test
    fun flexInputIntoZecPaysTheZchatFeeAccount() {
        val request = capture { requestExactInputIntoZec(BigDecimal("10"), "0xrefund", "u1destination") }
        assertZchatFee(request, SwapType.FLEX_INPUT)
    }

    @Test
    fun exactInputOutOfZecPaysTheZchatFeeAccount() {
        val request = capture { requestExactInputQuote(BigDecimal("0.1"), "0xdestination", "u1refund") }
        assertZchatFee(request, SwapType.EXACT_INPUT)
    }

    @Test
    fun exactOutputPaysTheZchatFeeAccount() {
        val request = capture { requestExactOutputQuote(BigDecimal("5"), "0xdestination", "u1refund") }
        assertZchatFee(request, SwapType.EXACT_OUTPUT)
    }

    @Test
    fun theConfiguredFeeAccountIsAnImplicitIdOrANearName() {
        val account = configuredFeeAccount()
        val implicit = Regex("^[0-9a-f]{64}$")
        val named = Regex("^([a-z0-9]+[-_])*[a-z0-9]+(\\.([a-z0-9]+[-_])*[a-z0-9]+)*\\.near$")
        assertTrue(
            implicit.matches(account) || named.matches(account),
            "ZCHAT_SWAP_FEE_ACCOUNT ${mask(account)} is neither a 64-hex implicit id nor a .near name"
        )
        assertTrue(account != "electriccoinco.near" && account != "crosspay.near", "fee account is an upstream account")
    }

    private fun source(path: String): File =
        listOf(File(path), File("ui-lib/$path")).firstOrNull { it.isFile } ?: fail("source not found: $path")

    private val swapSources =
        listOf(
            "src/main/java/co/electriccoin/zcash/ui/common/repository/SwapRepository.kt",
            "src/main/java/co/electriccoin/zcash/ui/common/datasource/NearSwapDataSourceImpl.kt",
        )

    @Test
    fun swapSourcesHoldNoFeeAccountOrReferralLiteral() {
        val literal = Regex("\"([^\"\\n]*)\"")
        val hex64 = Regex("[0-9a-fA-F]{64}")
        for (path in swapSources) {
            val name = path.substringAfterLast('/')
            val literals = literal.findAll(source(path).readText()).map { it.groupValues[1] }.toList()
            for (s in literals) {
                assertTrue(!s.contains("electriccoinco.near"), "$name has literal electriccoinco.near")
                assertTrue(!s.contains("crosspay.near"), "$name has literal crosspay.near")
                assertTrue(s != "zashi", "$name has referral literal \"zashi\"")
                assertTrue(!hex64.containsMatchIn(s), "$name has a 64-hex literal (fee account must come from config)")
                assertTrue(!s.trim().endsWith(".near"), "$name has a .near account literal \"${mask(s)}\"")
            }
        }
    }

    @Test
    fun theFeeAccountValueIsNotWrittenIntoAnySource() {
        val account = configuredFeeAccount()
        val roots =
            listOf("src/main", "build.gradle.kts", "../app/src/main", "../app/build.gradle.kts", "../build.gradle.kts")
                .map { File(it) }
                .filter { it.exists() }
        assertTrue(roots.isNotEmpty(), "no source roots found from ${File(".").absolutePath}")
        val hits =
            roots
                .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
                .filter { it.extension in setOf("kt", "kts", "java", "xml", "properties", "json") }
                .filter { it.readText().contains(account) }
                .map { it.path }
        assertTrue(hits.isEmpty(), "fee account ${mask(account)} is hard-coded in: $hits")
    }
}
