package co.electriccoin.zcash.ui.screen.enhanceddestroy

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.chat.util.FakePhone
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-1016 (the DESTROY-ADV-C finding F-C1) — when the store refuses the write of the Destroy choice, the screen "What
 * Destroy All erases" says so with its own true text. Today it shows destroy_options_key_failed ("could not prepare the
 * phone-security key"), which is not what happened: the key was made or not needed, the commit was refused.
 *
 * Wanted: a refused write sets `saveFailed` (not `keyFailed`), the view draws destroy_options_save_failed for it, and the
 * text exists in English and Spanish ("ZCHAT could not save this choice. Nothing was changed; try again."). A key that
 * cannot be made still shows destroy_options_key_failed. The new state field and text are reached by name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DestroyChoiceSaveFailureIsSaidTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun prefs(): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
            when (method.name) {
                "hasDestroyPin" -> true
                "getDestroyKeep" -> emptySet<String>()
                "setDestroyKeep" -> false
                "verifyDestroyPinWithLockout" -> DestroyPinVerifyResult.Success
                "walletScopeGeneration" -> 1L
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
            }
        } as ZchatPreferences

    private val router: NavigationRouter =
        Proxy.newProxyInstance(NavigationRouter::class.java.classLoader, arrayOf(NavigationRouter::class.java)) { p, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> null
            }
        } as NavigationRouter

    private fun call(
        target: Any,
        name: String,
        vararg args: Any,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("${target.javaClass.simpleName} has no $name")
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `a refused write says that the choice was not saved, not that the key failed`() =
        FakePhone().use { phone ->
            val manager = phone.manager()
            val prefs = prefs()
            DestroyManager::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .set(manager, prefs)
            val vm = DestroyOptionsVM(prefs, manager, router, Dispatchers.Unconfined)
            vm.onChooseEverything()
            vm.onSave()
            vm.onPinChange("2468")
            vm.onPinConfirm()
            val state = vm.state.value
            assertFalse("a refused write is not a key failure", state.keyFailed)
            assertEquals("a refused write is said", true, call(state, "getSaveFailed"))
        }

    @Test
    fun `the view draws its own text for a refused write, in English and Spanish`() {
        val view = SourceScan.stripComments(File(ChatResourceText.repoRoot, VIEW).readText())
        assertTrue(
            "the view shows destroy_options_save_failed",
            Regex("""if \(state\.saveFailed\)[^\n]*R\.string\.destroy_options_save_failed""").containsMatchIn(view)
        )
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        assertEquals("ZCHAT could not save this choice. Nothing was changed; try again.", en["destroy_options_save_failed"])
        val spanish = es["destroy_options_save_failed"] ?: throw AssertionError("no Spanish destroy_options_save_failed")
        assertNotEquals(en["destroy_options_save_failed"], spanish)
        assertFalse("key" in en.getValue("destroy_options_save_failed").lowercase())
    }

    private companion object {
        const val VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/enhanceddestroy/view/DestroyOptionsView.kt"
    }
}
