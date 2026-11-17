package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.chat.ZchatReceive
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.composeVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.ready
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Collections
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1461 (OT213b owner finding, 2 Nov 2026; lane L6): starting a chat and finding your own code are hard. New Chat has
 * only a toolbar scanner icon and no way to My code; My code shows the payable shielded address QR first and the free
 * chat code as a small second item.
 *
 * What it must be (FIX_PLAN B4, lane L6 RED idea):
 * - a pure model `co.electriccoin.zcash.ui.screen.chat.model.MyCodeTabs` (a Kotlin object) with
 *   `available(supportsOpen: Boolean): List<MyCodeTab>` = `[CHAT, ADDRESS]` when our relay key is derived, `[ADDRESS]`
 *   when it is not, and `defaultTab(supportsOpen: Boolean): MyCodeTab` = `CHAT` when Open is offered, else `ADDRESS`;
 *   `MyCodeTab` is an enum with `CHAT` (Free chat code) and `ADDRESS` (Shielded address);
 * - the New Chat state exposes both actions, always (not only when Open is locked): `onScanQrClick` opens the ZChat
 *   scanner and a new `onMyCodeClick: () -> Unit` opens My code ([ZchatReceive]).
 *
 * The new names are read by reflection (the RelayKeyBindingFixture idiom) so this file compiles before the fix and
 * the batch's other tests keep running; a missing name fails an assertion that names it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyCodeTabsAndNewChatEntryTest {
    private val composeVms = mutableListOf<ZchatComposeVM>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        composeVms.forEach(RelayKeyBinding::close)
        composeVms.clear()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ My code tabs

    @Test
    fun `My code offers the free chat code first and the shielded address second when Open is offered`() {
        assertEquals(listOf("CHAT", "ADDRESS"), available(supportsOpen = true), "tabs with our relay key derived")
        assertEquals("CHAT", defaultTab(supportsOpen = true), "My code must open on the free chat code when Open is offered")
    }

    @Test
    fun `My code offers only the shielded address while our relay key is not derived`() {
        assertEquals(listOf("ADDRESS"), available(supportsOpen = false), "tabs without our relay key")
        assertEquals("ADDRESS", defaultTab(supportsOpen = false), "default tab without our relay key")
    }

    // ------------------------------------------------------------------ New Chat entry

    @Test
    fun `New Chat exposes Scan a code and My code`() {
        val router = RouteRecorder()
        val vm = composeVm(RelayKeyStore(), navigationRouter = router).also { composeVms += it }
        val ready = vm.ready()

        ready.onScanQrClick()
        val scan = router.forwards.toList()
        assertEquals(1, scan.size, "fixture sanity: Scan opens one screen: $scan")
        val scanArgs = scan.single() as? ScanArgs ?: fail("Scan a code did not open the scanner: $scan")
        assertEquals(ScanFlow.ZCHAT, scanArgs.flow, "Scan a code opened another scanner flow")

        router.forwards.clear()
        val onMyCodeClick = myCodeAction(ready)
        onMyCodeClick()
        assertEquals(listOf<Any>(ZchatReceive), router.forwards.toList(), "My code on New Chat did not open My code")
    }

    // ------------------------------------------------------------------ reflection on the planned names

    private fun myCodeAction(state: ZchatComposeState.Ready): () -> Unit {
        val field =
            runCatching { state.javaClass.getDeclaredField("onMyCodeClick") }.getOrNull()
                ?: fail("the New Chat state has no onMyCodeClick action (no way from New Chat to My code)")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return assertNotNull(field.get(state) as? () -> Unit, "onMyCodeClick is not a () -> Unit action")
    }

    private fun tabsObject(): Pair<Class<*>, Any> {
        val cls =
            runCatching { Class.forName(MY_CODE_TABS) }.getOrNull()
                ?: fail("$MY_CODE_TABS does not exist (no My code tab model)")
        val instance = assertNotNull(runCatching { cls.getField("INSTANCE").get(null) }.getOrNull(), "$MY_CODE_TABS is not an object")
        return cls to instance
    }

    private fun available(supportsOpen: Boolean): List<String> {
        val (cls, instance) = tabsObject()
        val method =
            runCatching { cls.getMethod("available", java.lang.Boolean.TYPE) }.getOrNull()
                ?: fail("$MY_CODE_TABS has no available(supportsOpen: Boolean)")
        val tabs = method.invoke(instance, supportsOpen) as? List<*> ?: fail("available() does not return a list")
        assertTrue(tabs.all { it is Enum<*> }, "available() does not return MyCodeTab values: $tabs")
        return tabs.map { (it as Enum<*>).name }
    }

    private fun defaultTab(supportsOpen: Boolean): String {
        val (cls, instance) = tabsObject()
        val method =
            runCatching { cls.getMethod("defaultTab", java.lang.Boolean.TYPE) }.getOrNull()
                ?: fail("$MY_CODE_TABS has no defaultTab(supportsOpen: Boolean)")
        return (method.invoke(instance, supportsOpen) as? Enum<*>)?.name ?: fail("defaultTab() does not return a MyCodeTab")
    }

    /** Records every route passed to forward. */
    private class RouteRecorder : NavigationRouter {
        val forwards: MutableList<Any> = Collections.synchronizedList(mutableListOf())

        override fun forward(vararg routes: Any) {
            forwards.addAll(routes)
        }

        override fun replace(vararg routes: Any) = Unit

        override fun replaceAll(vararg routes: Any) = Unit

        override fun back() = Unit

        override fun backTo(route: KClass<*>) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<NavigationCommand> = emptyFlow()
    }

    private companion object {
        const val MY_CODE_TABS = "co.electriccoin.zcash.ui.screen.chat.model.MyCodeTabs"
    }
}
