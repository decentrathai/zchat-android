package co.electriccoin.zcash.ui.screen.enhanceddestroy

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.chat.datasource.DestroyPinVerifyResult
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.chat.util.FakePhone
import co.electriccoin.zcash.ui.screen.chat.util.KeptNamesApi
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.chat.view.destroyPinVerifyErrorText
import co.electriccoin.zcash.ui.screen.onboarding.DestroyPinSetup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * DESTROY-ADV-C (register ZC-722; owner W-41, W-43, W-47, W-52; Destroy Advanced spec revision 2c, 5.1, 5.5, 8, 9.2
 * and 10.2) — the screen "What Destroy All erases" (Advanced Settings) and its choice block.
 *
 *  - first state: "Destroy everything", the Advanced part closed; the tick is usable only under "Keep some things";
 *  - Save asks for the Destroy PIN (Destroy's own check and lockout texts); without the right PIN nothing is written;
 *    with it the choice is written, the list's key is made, and DestroyManager holds the choice with the current
 *    wallet-scope generation; a key that cannot be made saves nothing and says so;
 *  - NO DESTROY PIN (2b): no Save, the line destroy_options_needs_pin and ONE action that opens the Destroy PIN set-up;
 *    nothing is written whatever is tapped; the screen lock is no proof here (source);
 *  - "Destroy everything" and Save writes the empty set and deletes the list's key when no list is on the phone (with
 *    a list the key stays); "Keep some things" with no tick saves the empty set; with an earlier list on the phone and
 *    "Destroy everything" chosen, one line says that list is destroyed too.
 *
 * The screen's classes are reached by name; the preferences are a double; DestroyManager is the real class built by
 * the Destroy tests' FakePhone. PINs are test literals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DestroyOptionsScreenTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The store of the PIN and the choice, as ZchatPreferencesImpl keeps them (the choice only while a PIN is set). */
    private class Prefs(
        var pin: String?,
        var keep: Set<String> = emptySet(),
    ) {
        val writes: MutableList<Set<String>> = Collections.synchronizedList(mutableListOf())
        val generation = AtomicLong(11)
        var failWrite = false
        var verify: (String) -> DestroyPinVerifyResult = {
            if (it == pin) DestroyPinVerifyResult.Success else DestroyPinVerifyResult.Failed(4)
        }
        val verified: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
                when (method.name) {
                    "hasDestroyPin" -> pin != null
                    "getDestroyKeep" -> if (pin == null) emptySet<String>() else keep
                    "setDestroyKeep" -> {
                        @Suppress("UNCHECKED_CAST")
                        val value = args!![0] as Set<String>
                        if (pin == null || failWrite) {
                            false
                        } else {
                            writes += value
                            keep = value
                            true
                        }
                    }
                    "verifyDestroyPinWithLockout" -> {
                        val given = args!![0] as String
                        verified += given
                        verify(given)
                    }
                    "walletScopeGeneration" -> generation.get()
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "Prefs"
                    else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
                }
            } as ZchatPreferences
    }

    /** The list's key in a stand-in Keystore: [made] while it exists; [cannotMake] makes the phone refuse it. */
    private class Box {
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        @Volatile var made = false

        @Volatile var cannotMake = false

        val product: Any =
            run {
                val existing: () -> SecretKey? = { if (made) key else null }
                val make: () -> SecretKey = {
                    if (cannotMake) throw IllegalStateException("synthetic Keystore refusal")
                    made = true
                    key
                }
                val remove: () -> Unit = { made = false }
                KeptNamesApi
                    .type(KeptNamesApi.CIPHER_BOX)
                    .getConstructor(Function0::class.java, Function0::class.java, Function0::class.java)
                    .newInstance(existing, make, remove)
            }
    }

    private class Router {
        val forwards: MutableList<Any> = Collections.synchronizedList(mutableListOf())
        val proxy: NavigationRouter =
            Proxy.newProxyInstance(NavigationRouter::class.java.classLoader, arrayOf(NavigationRouter::class.java)) { p, method, args ->
                when (method.name) {
                    "forward" -> {
                        @Suppress("UNCHECKED_CAST")
                        forwards.addAll(args!![0] as Array<Any>)
                        null
                    }
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> null
                }
            } as NavigationRouter
    }

    private class Rig(
        val phone: FakePhone,
        val prefs: Prefs,
        val box: Box,
        val manager: DestroyManager,
        val router: Router,
        val vm: Any,
    )

    private fun <T> rig(
        prefs: Prefs,
        block: (Rig) -> T,
    ): T =
        FakePhone().use { phone ->
            val box = Box()
            val manager = phone.manager()
            set(manager, "zchatPreferences", prefs.proxy)
            val folder = File(phone.dataDir, "no_backup")
            val store =
                KeptNamesApi
                    .type(KeptNamesApi.STORE)
                    .getConstructor(File::class.java, KeptNamesApi.boxInterface())
                    .newInstance(folder, box.product)
            set(manager, "keptNames", store)
            val router = Router()
            val type =
                runCatching { Class.forName(VM) }.getOrNull()
                    ?: throw AssertionError("there is no $VM: no screen chooses what Destroy All erases (DESTROY-ADV-C)")
            val vm =
                type
                    .getConstructor(
                        ZchatPreferences::class.java,
                        DestroyManager::class.java,
                        NavigationRouter::class.java,
                        CoroutineDispatcher::class.java
                    ).newInstance(prefs.proxy, manager, router.proxy, Dispatchers.Unconfined)
            block(Rig(phone, prefs, box, manager, router, vm))
        }

    @Test
    fun `first state - everything, the Advanced part closed, the tick only under Keep some things`() =
        rig(Prefs(pin = PIN)) { r ->
            assertEquals("EVERYTHING", choice(r.vm))
            assertFalse("the Advanced part is closed", flag(r.vm, "getAdvancedOpen"))
            assertEquals(KeptNamesScreenApi.stringId("advanced_settings_destroy_options_all"), int(r.vm, "getSummary"))
            call(r.vm, "onToggleAdvanced")
            assertTrue(flag(r.vm, "getAdvancedOpen"))

            call(r.vm, "onTickContacts", true)
            assertFalse("the tick cannot be set under Destroy everything", flag(r.vm, "getContactsTicked"))
            assertFalse(flag(r.vm, "getTickEnabled"))
            call(r.vm, "onChooseKeep")
            assertTrue(flag(r.vm, "getTickEnabled"))
            call(r.vm, "onTickContacts", true)
            assertTrue(flag(r.vm, "getContactsTicked"))
            assertTrue("nothing is written before Save", r.prefs.writes.isEmpty())
        }

    @Test
    fun `Save asks for the PIN, and only the right PIN writes the choice, makes the key and sets the manager`() =
        rig(Prefs(pin = PIN)) { r ->
            call(r.vm, "onToggleAdvanced")
            call(r.vm, "onChooseKeep")
            call(r.vm, "onTickContacts", true)
            assertTrue("Save is offered with a PIN", flag(r.vm, "getSaveShown"))
            call(r.vm, "onSave")
            assertTrue("Save asks for the Destroy PIN", flag(r.vm, "getPinDialogShown"))
            assertTrue("nothing is written before the PIN", r.prefs.writes.isEmpty())
            call(r.vm, "onPinDismiss")
            assertTrue("a dismissed question writes nothing", r.prefs.writes.isEmpty())

            call(r.vm, "onSave")
            call(r.vm, "onPinChange", "9999")
            call(r.vm, "onPinConfirm")
            assertTrue("a wrong PIN writes nothing", r.prefs.writes.isEmpty())
            assertEquals(destroyPinVerifyErrorText(DestroyPinVerifyResult.Failed(4)), state(r.vm).let { call(it, "getPinError") })
            assertFalse("a wrong PIN makes no key", r.box.made)

            r.prefs.verify = { DestroyPinVerifyResult.LockedOut(remainingMillis = 90_000L) }
            call(r.vm, "onPinChange", PIN)
            call(r.vm, "onPinConfirm")
            assertTrue("a locked-out PIN writes nothing", r.prefs.writes.isEmpty())
            assertEquals(
                "Destroy's own lockout text",
                destroyPinVerifyErrorText(DestroyPinVerifyResult.LockedOut(remainingMillis = 90_000L)),
                call(state(r.vm), "getPinError")
            )

            r.prefs.verify = { if (it == PIN) DestroyPinVerifyResult.Success else DestroyPinVerifyResult.Failed(3) }
            call(r.vm, "onPinChange", PIN)
            call(r.vm, "onPinConfirm")
            assertEquals("the right PIN writes the choice", listOf(setOf("contacts")), r.prefs.writes)
            assertTrue("the list's key is made at Save", r.box.made)
            assertEquals(setOf("contacts"), managerKeep(r.manager))
            assertEquals("with the current wallet-scope generation", 11L, managerGeneration(r.manager))
            assertFalse(flag(r.vm, "getPinDialogShown"))
            assertEquals(KeptNamesScreenApi.stringId("destroy_options_saved_keep_contacts"), int(r.vm, "getNotice"))
            assertEquals(KeptNamesScreenApi.stringId("advanced_settings_destroy_options_keep_contacts"), int(r.vm, "getSummary"))
        }

    @Test
    fun `a key that cannot be made saves nothing and says so`() =
        rig(Prefs(pin = PIN)) { r ->
            r.box.cannotMake = true
            call(r.vm, "onChooseKeep")
            call(r.vm, "onTickContacts", true)
            call(r.vm, "onSave")
            call(r.vm, "onPinChange", PIN)
            call(r.vm, "onPinConfirm")
            assertTrue("nothing is written", r.prefs.writes.isEmpty())
            assertTrue("the message shows", flag(r.vm, "getKeyFailed"))
            assertFalse("the manager holds no kept choice", managerKeepOrNull(r.manager)?.contains("contacts") == true)
        }

    @Test
    fun `no Destroy PIN - no Save, the needs-PIN line and one action to the set-up, nothing written`() =
        rig(Prefs(pin = null)) { r ->
            assertFalse("no Save without a PIN", flag(r.vm, "getSaveShown"))
            assertTrue("the needs-PIN line", flag(r.vm, "getNeedsPin"))
            call(r.vm, "onToggleAdvanced")
            call(r.vm, "onChooseKeep")
            call(r.vm, "onTickContacts", true)
            call(r.vm, "onSave")
            assertFalse("Save asks nothing without a PIN", flag(r.vm, "getPinDialogShown"))
            call(r.vm, "onSetPin")
            assertEquals(
                "one action opens the Destroy PIN set-up",
                listOf<Any>(DestroyPinSetup(isCreatingWallet = false)),
                r.router.forwards
            )
            assertTrue("nothing is written", r.prefs.writes.isEmpty())
            assertTrue("no PIN was asked", r.prefs.verified.isEmpty())
            assertFalse("no key is made", r.box.made)
        }

    @Test
    fun `source - the screen lock is no proof for the choice, and the block holds the asked-now sentence`() {
        for (path in listOf(VM_SOURCE, VIEW_SOURCE)) {
            val code = source(path)
            for (name in listOf("KeyguardManager", "createConfirmDeviceCredentialIntent")) {
                assertFalse("$path names $name", name in code)
            }
        }
        val view = source(VIEW_SOURCE)
        val block =
            KeptNamesScreenApi.functionBody(view, "DestroyChoiceBlock")
                ?: throw AssertionError("the view has no DestroyChoiceBlock (the choice as one block)")
        for (key in listOf(
            "destroy_options_default_body",
            "destroy_options_everything",
            "destroy_options_everything_list",
            "destroy_options_keep",
            "destroy_keep_contacts_title",
            "destroy_keep_contacts_body",
            "destroy_keep_contacts_after",
            "destroy_keep_note",
            "destroy_keep_messages_hint",
            "destroy_options_asked_now",
        )) {
            assertTrue("the choice block draws $key", "R.string.$key" in block)
        }
        assertFalse("the block has no Save of its own", "R.string.destroy_options_save" in block)
    }

    @Test
    fun `Destroy everything and Save writes the empty set and deletes the key when no list is on the phone`() =
        rig(Prefs(pin = PIN, keep = setOf("contacts"))) { r ->
            r.box.made = true
            assertEquals("the saved choice is read", "KEEP_SOME", choice(r.vm))
            call(r.vm, "onChooseEverything")
            saveWithPin(r)
            assertEquals(listOf(emptySet<String>()), r.prefs.writes)
            assertFalse("with no list on the phone the key goes", r.box.made)
            assertEquals(emptySet<String>(), managerKeep(r.manager))
            assertEquals(KeptNamesScreenApi.stringId("destroy_options_saved_all"), int(r.vm, "getNotice"))
        }

    @Test
    fun `with an earlier list on the phone the key stays, and the extra line shows under Destroy everything`() =
        rig(Prefs(pin = PIN, keep = setOf("contacts"))) { r ->
            r.box.made = true
            File(r.phone.dataDir, "no_backup").mkdirs()
            File(r.phone.dataDir, "no_backup/zchat_kept_names").writeBytes(byteArrayOf(1, 2, 3))
            val vm = r.vm
            call(vm, "onChooseEverything")
            assertTrue("the extra line about the earlier list", flag(vm, "getExistingListShown"))
            saveWithPin(r)
            assertTrue("with a list on the phone the key stays", r.box.made)
        }

    @Test
    fun `Keep some things with no tick saves the empty set`() =
        rig(Prefs(pin = PIN)) { r ->
            call(r.vm, "onChooseKeep")
            saveWithPin(r)
            assertEquals(listOf(emptySet<String>()), r.prefs.writes)
            assertFalse("no key for an empty choice", r.box.made)
        }

    private fun saveWithPin(r: Rig) {
        call(r.vm, "onSave")
        call(r.vm, "onPinChange", PIN)
        call(r.vm, "onPinConfirm")
    }

    // ---- reflection ---------------------------------------------------------------------------------------------------

    private fun set(
        manager: DestroyManager,
        name: String,
        value: Any?,
    ) {
        val field =
            DestroyManager::class.java.declaredFields.firstOrNull { it.name == name }
                ?: throw AssertionError("DestroyManager has no field $name")
        field.isAccessible = true
        field.set(manager, value)
    }

    private fun choiceField(manager: DestroyManager): Any? =
        DestroyManager::class.java.declaredFields
            .firstOrNull { it.name == "keepChoice" }
            ?.apply { isAccessible = true }
            ?.get(manager)

    private fun managerKeepOrNull(manager: DestroyManager): Set<*>? = choiceField(manager)?.let { call(it, "getKeep") as Set<*> }

    private fun managerKeep(manager: DestroyManager): Set<*> =
        managerKeepOrNull(manager) ?: throw AssertionError("DestroyManager holds no choice")

    private fun managerGeneration(manager: DestroyManager): Long = call(choiceField(manager)!!, "getGeneration") as Long

    private fun call(
        target: Any,
        name: String,
        vararg args: Any,
    ): Any? {
        val method =
            (target.javaClass.methods.asList() + target.javaClass.declaredMethods.asList())
                .firstOrNull { it.name == name && it.parameterCount == args.size }
                ?.apply { isAccessible = true }
                ?: throw AssertionError("${target.javaClass.simpleName} has no $name")
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun state(vm: Any): Any = (call(vm, "getState") as StateFlow<*>).value!!

    private fun flag(
        vm: Any,
        getter: String,
    ): Boolean = call(state(vm), getter) as Boolean

    private fun int(
        vm: Any,
        getter: String,
    ): Int? = call(state(vm), getter) as Int?

    private fun choice(vm: Any): String = call(state(vm), "getChoice").toString()

    private fun source(path: String): String {
        val file = File(ChatResourceText.repoRoot, path)
        if (!file.isFile) throw AssertionError("there is no $path")
        return SourceScan.stripComments(file.readText())
    }

    private companion object {
        const val PIN = "2468"
        const val VM = "co.electriccoin.zcash.ui.screen.enhanceddestroy.DestroyOptionsVM"
        const val VM_SOURCE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/enhanceddestroy/DestroyOptionsVM.kt"
        const val VIEW_SOURCE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/enhanceddestroy/view/DestroyOptionsView.kt"
    }
}
