package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.keystone.RouteRecordingNavigationRouter
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/**
 * ZC-1269: "I sent the funds" threw the user onto the chat list, whatever tab the swap started from
 * (`replaceAll(ChatList)`). It pops back to the root instead, which keeps the tab they were on. Which tab the root then
 * shows is a phone check: this test sees the navigation command only.
 */
class SaveORSwapReturnsToStartingTabTest {
    private inline fun <reified T : Any> stub(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method.name)
        } as T

    @Test
    fun i_sent_the_funds_goes_back_to_the_root_and_never_replaces_the_stack() {
        val router = RouteRecordingNavigationRouter()
        val swaps =
            stub<SwapRepository> { name ->
                if (name == "getQuote") MutableStateFlow(null) else error("unexpected SwapRepository.$name")
            }
        val metadata = stub<MetadataRepository> { name -> error("unexpected MetadataRepository.$name") }

        SaveORSwapUseCase(swaps, metadata, router).invoke()

        assertEquals(listOf("backToRoot"), router.commands.toList())
    }
}
