package co.electriccoin.zcash.ui.screen.swap

import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.DynamicSwapAsset
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.FilterSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.SelectSwapAssetUseCase
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.picker.SwapAssetPickerArgs
import co.electriccoin.zcash.ui.screen.swap.picker.SwapAssetPickerDataState
import co.electriccoin.zcash.ui.screen.swap.picker.SwapAssetPickerVM
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import co.electriccoin.zcash.ui.design.R as DesignR

/**
 * ZC-1458 (b) and (c) (OT213b owner finding, Fold, 2 Nov 2026: the swap service 1Click answered Cloudflare 521; lane L1).
 *
 * At 669cf19e1:
 * - the token picker (`SwapAssetPickerVM.createState`) shows its Error ("Something went wrong" / "We couldn't load the
 *   assets. Please check your connection and try again.") whenever there is no data and nothing is loading. It never
 *   reads `assets.error`, so a load that was never started reads as a failure;
 * - a 521, any other 5xx but 503, or a timeout is blamed on the user's connection: the picker's text above, and on the
 *   main swap screen (`ExactInputVMMapper.createErrorFooterState`) "Unexpected error" / "Please check your connection".
 *
 * What it must be: "not loaded yet" is Loading, never Error; a server-side failure (HTTP 5xx including 521, or a timeout)
 * is a distinct "swap service down" state whose text is not the connection text. The 521 here is a real Ktor
 * [ServerResponseException] from a local MockWebServer (no network). The new state's own wording is pinned by the
 * text tests of the fix, not here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapServiceDownIsNotAConnectionProblemTest {
    private val pickers = mutableListOf<SwapAssetPickerVM>()

    private val chain = SwapBlockchain(chainTicker = "eth", chainName = stringRes("Ethereum"), chainIcon = imageRes(0))
    private val usdc =
        DynamicSwapAsset(
            tokenTicker = "USDC",
            tokenName = stringRes("USDC"),
            tokenIcon = imageRes(0),
            usdPrice = BigDecimal.ONE,
            assetId = "usdc",
            decimals = 6,
            blockchain = chain
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        pickers.forEach { it.viewModelScope.cancel() }
        runBlocking {
            pickers.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        pickers.clear()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ picker

    @Test
    fun `picker - assets never loaded is Loading, not Error`() =
        runBlocking {
            val data = pickerData(SwapAssetsData())
            assertEquals(
                SwapAssetPickerDataState.Loading,
                data,
                "the picker shows an error for a load that was never started (no data, not loading, no error)",
            )
        }

    @Test
    fun `picker - a 521 from the swap service is not shown as a connection problem`() =
        runBlocking {
            assertServiceDownInPicker(http521(), "HTTP 521")
        }

    @Test
    fun `picker - a timeout from the swap service is not shown as a connection problem`() =
        runBlocking {
            assertServiceDownInPicker(timeout(), "timeout")
        }

    // ------------------------------------------------------------------ main swap screen

    @Test
    fun `swap screen - a 521 from the swap service is not blamed on the connection`() =
        runBlocking {
            assertServiceDownOnSwapScreen(http521(), "HTTP 521")
        }

    @Test
    fun `swap screen - a timeout from the swap service is not blamed on the connection`() {
        assertServiceDownOnSwapScreen(timeout(), "timeout")
    }

    // ------------------------------------------------------------------ checks

    private suspend fun assertServiceDownInPicker(
        error: Exception,
        what: String
    ) {
        val data = pickerData(SwapAssetsData(error = error))
        assertFalse(data is SwapAssetPickerDataState.Success, "fixture sanity: no data, so no list")
        assertNotEquals(SwapAssetPickerDataState.Loading, data, "a failed load ($what) shows Loading")
        val shown = data as? SwapAssetPickerDataState.Error
        if (shown != null) {
            assertNotEquals(
                DesignR.string.general_error_message,
                resourceOf(shown.subtitle),
                "a $what from the swap service is shown with the connection text (general_error_message)",
            )
            assertNotEquals(
                DesignR.string.general_error_title,
                resourceOf(shown.title),
                "a $what from the swap service is shown as \"Something went wrong\" (general_error_title)",
            )
        }
    }

    private fun assertServiceDownOnSwapScreen(
        error: Exception,
        what: String
    ) {
        val footer = assertNotNull(swapScreen(SwapAssetsData(error = error)).errorFooter, "a $what shows no error footer at all")
        assertNotEquals(
            DesignR.string.general_check_connection,
            resourceOf(footer.subtitle),
            "a $what from the swap service is blamed on the user's connection (general_check_connection)",
        )
        assertNotEquals(
            DesignR.string.general_unexpected_error,
            resourceOf(footer.title),
            "a $what from the swap service is called an \"Unexpected error\" instead of the service being down",
        )
    }

    private fun resourceOf(text: StringResource): Int? = (text as? StringResource.ByResource)?.resource

    // ------------------------------------------------------------------ fixture

    private suspend fun pickerData(assets: SwapAssetsData): SwapAssetPickerDataState {
        val repository = swapRepository(assets)
        val router = QuietRouter()
        val vm =
            SwapAssetPickerVM(
                args = SwapAssetPickerArgs(chainTicker = null),
                getSwapAssets = GetSwapAssetsUseCase(repository),
                metadataRepository = metadataRepository(),
                selectSwapAsset = SelectSwapAssetUseCase(repository, router),
                navigationRouter = router,
                filterSwapAssets = FilterSwapAssetsUseCase(ContextWrapper(null)),
                swapRepository = repository,
            ).also { pickers += it }
        return withTimeout(TIMEOUT_MS) { vm.state.first { it != null } }!!.data
    }

    private fun swapScreen(assets: SwapAssetsData): SwapState =
        ExactInputVMMapper().createState(
            internalState =
                InternalStateImpl(
                    account = null,
                    swapAsset = usdc,
                    currencyType = CurrencyType.TOKEN,
                    amountTextState = NumberTextFieldInnerState(),
                    addressText = "refund-or-destination",
                    slippage = BigDecimal.ONE,
                    swapAssets = assets,
                    isRequestingQuote = false,
                    selectedContact = null,
                    mode = Mode.SWAP_INTO_ZEC,
                    isEphemeralAddressLocked = false
                ),
            onBack = {},
            onSwapInfoClick = {},
            onSwapAssetPickerClick = {},
            onSwapCurrencyTypeClick = {},
            onSlippageClick = {},
            onRequestSwapQuoteClick = { _, _ -> },
            onTryAgainClick = {},
            onAddressChange = {},
            onTextFieldChange = {},
            onQrCodeScannerClick = {},
            onAddressBookClick = {},
            onDeleteSelectedContactClick = {},
            onBalanceButtonClick = {},
            onChangeButtonClick = {},
            onAddressClick = {}
        )

    /** A real Ktor 5xx: the swap service behind Cloudflare answering 521, served by a local MockWebServer. */
    private fun http521(): ServerResponseException =
        runBlocking {
            val server = MockWebServer()
            try {
                server.enqueue(MockResponse().setResponseCode(CLOUDFLARE_WEB_SERVER_DOWN))
                server.start()
                val client = HttpClient(OkHttp) { expectSuccess = true }
                try {
                    client.get(server.url("/v0/tokens").toString())
                    error("fixture: the 521 was not raised")
                } catch (e: ServerResponseException) {
                    assertEquals(CLOUDFLARE_WEB_SERVER_DOWN, e.response.status.value, "fixture sanity")
                    e
                } finally {
                    client.close()
                }
            } finally {
                server.shutdown()
            }
        }

    private fun timeout(): HttpRequestTimeoutException =
        HttpRequestTimeoutException(
            "https://swap.synthetic.example/v0/tokens",
            15_000L,
            null,
        )

    private fun swapRepository(assets: SwapAssetsData): SwapRepository =
        proxy { name, _ ->
            when (name) {
                "getAssets" -> MutableStateFlow(assets)
                "requestRefreshAssets" -> Unit
                else -> error("unexpected SwapRepository call $name")
            }
        }

    private fun metadataRepository(): MetadataRepository =
        proxy { name, _ ->
            when (name) {
                "observeLastUsedAssetHistory" -> flowOf(null)
                else -> error("unexpected MetadataRepository call $name")
            }
        }

    private inline fun <reified T> proxy(crossinline answer: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { p, m, a ->
            when (m.name) {
                "toString" -> "fake ${T::class.simpleName}"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.get(0)
                else -> answer(m.name, a)
            }
        } as T

    private class QuietRouter : NavigationRouter {
        override fun forward(vararg routes: Any) = Unit

        override fun replace(vararg routes: Any) = Unit

        override fun replaceAll(vararg routes: Any) = Unit

        override fun back() = Unit

        override fun backTo(route: KClass<*>) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<NavigationCommand> = emptyFlow()
    }

    private companion object {
        const val CLOUDFLARE_WEB_SERVER_DOWN = 521
        const val TIMEOUT_MS = 5_000L
    }
}
