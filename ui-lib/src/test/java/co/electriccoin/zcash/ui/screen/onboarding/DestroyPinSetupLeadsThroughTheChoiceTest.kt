package co.electriccoin.zcash.ui.screen.onboarding

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.chat.util.FakePhone
import co.electriccoin.zcash.ui.screen.chat.util.KeptNamesApi
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.enhanceddestroy.KeptNamesScreenApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * DESTROY-ADV-P (register ZC-722; owner W-52: every choice about Destroy All is made when the Destroy PIN is set up;
 * W-57; Destroy Advanced spec revision 2c, 5.3 to 5.5, 9.6 and 10.3) — the Destroy PIN set-up leads through the
 * choice: three steps (what Destroy All does; what it erases, the choice block; the PIN), and ONE write for the PIN
 * and the choice.
 *
 * On the flow's state holder `DestroyPinSetupFlow` (reached by name; built with the preferences, DestroyManager and a
 * reader of "the phone has a screen lock"): the steps; Save refused before the choice was shown; one
 * setDestroySetup(pin, keep) and nothing else; the key first, then the write, then the value in memory; a key that
 * cannot be made or a commit that fails writes nothing and says so; B2: a first Save with the tick asks for the screen
 * lock first when the phone has one; a change of the PIN shows the saved choice; Back, Skip and Cancel write nothing;
 * the FD-8a rules of the PIN fields. Then the sources and the texts. PINs are test literals.
 */
class DestroyPinSetupLeadsThroughTheChoiceTest {
    /** The Destroy PIN store as ZchatPreferencesImpl keeps it, recording every write call. */
    private class Prefs(
        var pin: String?,
        var keep: Set<String> = emptySet(),
    ) {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val setups: MutableList<Pair<String, Set<String>>> = Collections.synchronizedList(mutableListOf())
        val generation = AtomicLong(21)
        var commitFails = false
        var readThrows = false

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
                when (method.name) {
                    "hasDestroyPin" -> pin != null
                    "getDestroyKeep" -> {
                        if (readThrows) throw IllegalStateException("synthetic store fault")
                        if (pin == null) emptySet<String>() else keep
                    }
                    "setDestroySetup" -> {
                        @Suppress("UNCHECKED_CAST")
                        val value = args!![1] as Set<String>
                        log += "write"
                        setups += (args[0] as String) to value
                        if (commitFails) {
                            false
                        } else {
                            pin = args[0] as String
                            keep = value
                            true
                        }
                    }
                    "setDestroyPin", "setDestroyKeep", "clearDestroyPin" -> {
                        log += method.name
                        throw AssertionError("the set-up called ${method.name}")
                    }
                    "walletScopeGeneration" -> generation.get()
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "Prefs"
                    else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
                }
            } as ZchatPreferences
    }

    private class Box(
        private val log: MutableList<String>,
    ) {
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        @Volatile var made = false

        @Volatile var cannotMake = false

        val product: Any =
            run {
                val existing: () -> SecretKey? = { if (made) key else null }
                val make: () -> SecretKey = {
                    if (cannotMake) throw IllegalStateException("synthetic Keystore refusal")
                    log += "key"
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

    private class Rig(
        val prefs: Prefs,
        val box: Box,
        val manager: DestroyManager,
        val flow: Any,
    )

    private fun <T> rig(
        prefs: Prefs,
        screenLock: Boolean = false,
        block: suspend (Rig) -> T,
    ): T =
        FakePhone().use { phone ->
            val box = Box(prefs.log)
            val manager = phone.manager()
            setField(manager, "zchatPreferences", prefs.proxy)
            val store =
                KeptNamesApi
                    .type(KeptNamesApi.STORE)
                    .getConstructor(File::class.java, KeptNamesApi.boxInterface())
                    .newInstance(File(phone.dataDir, "no_backup"), box.product)
            setField(manager, "keptNames", store)
            val type =
                runCatching { Class.forName(FLOW) }.getOrNull()
                    ?: throw AssertionError("there is no $FLOW: the Destroy PIN set-up does not lead through the choice (W-52)")
            val hasLock: () -> Boolean = { screenLock }
            val flow =
                type
                    .getConstructor(ZchatPreferences::class.java, DestroyManager::class.java, Function0::class.java)
                    .newInstance(prefs.proxy, manager, hasLock)
            call(flow, "load")
            runBlocking { block(Rig(prefs, box, manager, flow)) }
        }

    // ---- the steps ------------------------------------------------------------------------------------------------

    @Test
    fun `(1) a first set-up starts at step 1, then the choice with everything selected and Advanced closed, then the PIN`() =
        rig(Prefs(pin = null)) { r ->
            assertEquals(1, step(r.flow))
            call(r.flow, "onSetUp")
            assertEquals(2, step(r.flow))
            assertEquals("EVERYTHING", value(r.flow, "getChoice").toString())
            assertFalse(value(r.flow, "getAdvancedOpen") as Boolean)
            call(r.flow, "onContinue")
            assertEquals(3, step(r.flow))
        }

    @Test
    fun `(2) Save is refused and writes nothing at step 1, at step 2, and at a step 3 the choice was not shown for`() =
        rig(Prefs(pin = null)) { r ->
            pins(r.flow, PIN)
            assertFalse(save(r.flow))
            call(r.flow, "onSetUp")
            pins(r.flow, PIN)
            assertFalse(save(r.flow))
            assertTrue("nothing written", r.prefs.log.isEmpty())
        }.also {
            rig(Prefs(pin = null)) { r ->
                // A holder put at step 3 by hand, without step 2 having been shown in this visit.
                setField(r.flow, "step", 3)
                pins(r.flow, PIN)
                assertFalse("Save without the choice shown", save(r.flow))
                assertTrue("nothing written", r.prefs.log.isEmpty())
            }
        }

    @Test
    fun `(3) Save with the default makes exactly one write, setDestroySetup(pin, empty), and asks no proof`() =
        rig(Prefs(pin = null), screenLock = true) { r ->
            toStep3(r.flow)
            pins(r.flow, PIN)
            assertTrue(save(r.flow))
            assertEquals(listOf("write"), r.prefs.log)
            assertEquals(listOf(PIN to emptySet<String>()), r.prefs.setups)
            assertFalse("a default first set-up asks no proof", value(r.flow, "getProofNeeded") as Boolean)
            assertEquals(R.string.destroy_options_saved_all, value(r.flow, "getNotice"))
            assertTrue("the set-up returns to Advanced Settings", value(r.flow, "getFinished") as Boolean)
        }

    @Test
    fun `(4) the tick - the key first, then one setDestroySetup, then the manager's value with the generation`() =
        rig(Prefs(pin = null), screenLock = false) { r ->
            toStep3(r.flow, tick = true)
            pins(r.flow, PIN)
            assertTrue(save(r.flow))
            assertEquals("the key, then the one write", listOf("key", "write"), r.prefs.log)
            assertEquals(listOf(PIN to setOf("contacts")), r.prefs.setups)
            assertEquals(setOf("contacts"), managerKeep(r.manager))
            assertEquals(21L, managerGeneration(r.manager))
            assertEquals(R.string.destroy_options_saved_keep_contacts, value(r.flow, "getNotice"))
        }

    @Test
    fun `(4 B2) a first Save with the tick on a phone with a screen lock asks for it first`() {
        rig(Prefs(pin = null), screenLock = true) { r ->
            toStep3(r.flow, tick = true)
            pins(r.flow, PIN)
            assertFalse("the first Save only reports that a proof is needed", save(r.flow))
            assertTrue(value(r.flow, "getProofNeeded") as Boolean)
            assertTrue("nothing before the proof", r.prefs.log.isEmpty())
            assertTrue(proof(r.flow, true))
            assertEquals(listOf("key", "write"), r.prefs.log)
            assertEquals(setOf("contacts"), managerKeep(r.manager))
        }
        rig(Prefs(pin = null), screenLock = true) { r ->
            toStep3(r.flow, tick = true)
            pins(r.flow, PIN)
            save(r.flow)
            assertFalse(proof(r.flow, false))
            assertTrue("a cancelled proof writes nothing", r.prefs.log.isEmpty())
            assertEquals(3, step(r.flow))
            assertEquals(KeptNamesScreenApi.stringId("destroy_pin_setup_lock_needed"), value(r.flow, "getMessage"))
        }
        rig(Prefs(pin = PIN), screenLock = true) { r ->
            toStep3(r.flow, tick = true)
            pins(r.flow, "1357")
            assertTrue("a change of an existing PIN never asks a second time", save(r.flow))
            assertEquals(listOf("key", "write"), r.prefs.log)
        }
    }

    @Test
    fun `(5) the key cannot be made - no write at all, the key-failed message, still step 3`() =
        rig(Prefs(pin = null)) { r ->
            r.box.cannotMake = true
            toStep3(r.flow, tick = true)
            pins(r.flow, PIN)
            assertFalse(save(r.flow))
            assertTrue(r.prefs.setups.isEmpty())
            assertEquals(R.string.destroy_options_key_failed, value(r.flow, "getMessage"))
            assertEquals(3, step(r.flow))
        }

    @Test
    fun `(6) the commit fails - the save-failed message, the old PIN and choice as before`() =
        rig(Prefs(pin = "1111", keep = setOf("contacts"))) { r ->
            r.prefs.commitFails = true
            toStep3(r.flow)
            call(r.flow, "onChooseEverything")
            pins(r.flow, PIN)
            assertFalse(save(r.flow))
            assertEquals(KeptNamesScreenApi.stringId("destroy_pin_setup_save_failed"), value(r.flow, "getMessage"))
            assertEquals("1111", r.prefs.pin)
            assertEquals(setOf("contacts"), r.prefs.keep)
            assertEquals(3, step(r.flow))
        }

    @Test
    fun `(7) Keep some things with no tick saves the empty set`() =
        rig(Prefs(pin = null)) { r ->
            call(r.flow, "onSetUp")
            call(r.flow, "onToggleAdvanced")
            call(r.flow, "onChooseKeep")
            call(r.flow, "onContinue")
            pins(r.flow, PIN)
            assertTrue(save(r.flow))
            assertEquals(listOf(PIN to emptySet<String>()), r.prefs.setups)
        }

    @Test
    fun `(8) a change shows the saved choice, a read that throws shows everything, Save replaces both in one write`() {
        rig(Prefs(pin = PIN, keep = setOf("contacts"))) { r ->
            call(r.flow, "onSetUp")
            assertEquals("KEEP_SOME", value(r.flow, "getChoice").toString())
            assertTrue(value(r.flow, "getContactsTicked") as Boolean)
            call(r.flow, "onContinue")
            pins(r.flow, "9753")
            assertTrue(save(r.flow))
            assertEquals(listOf("9753" to setOf("contacts")), r.prefs.setups)
        }
        rig(Prefs(pin = PIN, keep = setOf("contacts")).apply { readThrows = true }) { r ->
            call(r.flow, "onSetUp")
            assertEquals("EVERYTHING", value(r.flow, "getChoice").toString())
        }
    }

    @Test
    fun `(9) Back keeps the selection, Back from step 1 leaves, Skip and every Cancel write nothing`() =
        rig(Prefs(pin = null)) { r ->
            toStep3(r.flow, tick = true)
            call(r.flow, "onBack")
            assertEquals(2, step(r.flow))
            assertEquals("KEEP_SOME", value(r.flow, "getChoice").toString())
            assertTrue(value(r.flow, "getContactsTicked") as Boolean)
            call(r.flow, "onCancel")
            assertEquals(1, step(r.flow))
            call(r.flow, "onBack")
            assertTrue("Back from step 1 leaves", value(r.flow, "getLeft") as Boolean)
            assertTrue("nothing written", r.prefs.log.isEmpty())
        }.also {
            rig(Prefs(pin = null)) { r ->
                call(r.flow, "onSkip")
                assertTrue(value(r.flow, "getLeft") as Boolean)
                assertTrue(r.prefs.log.isEmpty())
            }
        }

    @Test
    fun `(10) a short PIN and two different PINs are said, and Save is offered once both fields hold something`() =
        rig(Prefs(pin = null)) { r ->
            toStep3(r.flow)
            assertFalse(value(r.flow, "getSaveEnabled") as Boolean)
            call(r.flow, "onPinChange", "123")
            assertFalse(value(r.flow, "getSaveEnabled") as Boolean)
            call(r.flow, "onConfirmChange", "123")
            assertTrue("Save is offered as soon as both fields hold something", value(r.flow, "getSaveEnabled") as Boolean)
            assertFalse(save(r.flow))
            assertEquals(R.string.destroy_pin_too_short, value(r.flow, "getMessage"))
            call(r.flow, "onPinChange", "1234")
            call(r.flow, "onConfirmChange", "1235")
            assertFalse(save(r.flow))
            assertEquals(R.string.chat_destroy_pin_mismatch, value(r.flow, "getMessage"))
            assertTrue(r.prefs.log.isEmpty())
        }

    // ---- the sources ----------------------------------------------------------------------------------------------

    @Test
    fun `(11) one set-up place - each write of the PIN and the choice has its one caller`() {
        for (file in listOf(APS, DPS)) {
            assertFalse("$file calls setDestroyPin(", "setDestroyPin(" in code(file))
        }
        assertTrue("the set-up calls setDestroySetup(", "setDestroySetup(" in code(FLOW_SOURCE) || "setDestroySetup(" in code(APS))
        val main = File(ChatResourceText.repoRoot, "ui-lib/src/main/java")

        fun callers(pattern: Regex): Set<String> =
            main
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { pattern.containsMatchIn(SourceScan.stripComments(it.readText())) }
                .map { it.name }
                .toSet()
        assertEquals(
            "setDestroySetup( is called from the set-up's files only",
            emptySet<String>(),
            callers(Regex("""[.:]setDestroySetup\(|::setDestroySetup""")) - setOf("DestroyPinSetupFlow.kt", "AndroidDestroyPinSetup.kt")
        )
        assertEquals(setOf("DestroyOptionsVM.kt"), callers(Regex("""\.setDestroyKeep\(|::setDestroyKeep""")))
        assertEquals(
            "setDestroyPin( is called from ZchatPreferences.kt only",
            setOf("ZchatPreferences.kt"),
            callers(Regex("""\bsetDestroyPin\((?!pin: String\))"""))
        )
    }

    @Test
    fun `(12) the screen-lock prompt still comes first for a change, with the reset texts`() {
        val aps = code(APS)
        assertTrue("the gate when a PIN exists", "val alreadyHasPin = remember { zchatPreferences.hasDestroyPin() }" in aps)
        val gate = KeptNamesScreenApi.functionBody(aps, "AndroidDestroyPinSetup") ?: throw AssertionError("no AndroidDestroyPinSetup")
        val prompt = gate.indexOf("createConfirmDeviceCredentialIntent(")
        assertTrue("the prompt", prompt >= 0)
        val args = gate.substring(prompt, gate.indexOf(')', gate.indexOf("chat_destroy_pin_reset_prompt", prompt)) + 1)
        assertTrue("its title", "R.string.chat_destroy_pin_reset_title" in args)
        assertTrue("its text", "R.string.chat_destroy_pin_reset_prompt" in args)
        assertTrue(
            "nothing is shown before the gate",
            gate.indexOf("if (!credentialConfirmed) return") < gate.indexOf("DestroyPinSetupView(")
        )
    }

    @Test
    fun `(13) no English literal in the two files`() {
        for (file in listOf(APS, DPS)) {
            val text = code(file)
            for (pattern in listOf(
                Regex("""Text\(\s*""""),
                Regex("""text\s*=\s*""""),
                Regex("""label\s*=\s*\{\s*Text\(\s*""""),
                Regex("""pinError\s*=\s*""""),
                Regex("""(title|description)\s*=\s*""""),
            )) {
                assertFalse("$file: ${pattern.find(text)?.value}", pattern.containsMatchIn(text))
            }
            assertFalse(
                "$file passes a quoted text to the screen-lock prompt",
                Regex("""createConfirmDeviceCredentialIntent\([^)]*"""").containsMatchIn(text)
            )
        }
    }

    @Test
    fun `(14) every PIN field is masked and asks for the numeric PIN keyboard`() {
        val text = code(DPS)
        val fields = Regex("""OutlinedTextField\(""").findAll(text).count()
        assertTrue("fixture: the two PIN fields", fields >= 2)
        assertEquals(fields, Regex("""PasswordVisualTransformation\(\)""").findAll(text).count())
        assertEquals(fields, Regex("""KeyboardType\.NumberPassword""").findAll(text).count())
    }

    @Test
    fun `(15) the set-up still draws the five ZC-692-T keys and the saved-files line`() {
        val text = code(DPS)
        for (key in listOf(
            "destroy_pin_setup_intro",
            "destroy_pin_setup_what_title",
            "destroy_pin_setup_what_body",
            "destroy_pin_setup_undo_title",
            "destroy_pin_setup_undo_body",
            "destroy_not_erased_saved_files",
        )) {
            assertTrue("DestroyPinSetupView.kt does not use $key", "R.string.$key" in text)
        }
    }

    @Test
    fun `(16) the place for the AI line is in step 1 after the saved-files line and before the buttons, empty by default`() {
        val text = code(DPS)
        assertTrue(
            "the view has a parameter for the AI line with an empty default",
            Regex("""aiCreditLine:\s*@Composable\s*\(\)\s*->\s*Unit\s*=\s*\{\s*\}""").containsMatchIn(text)
        )
        val step1 = KeptNamesScreenApi.functionBody(text, "SetupStepOne") ?: throw AssertionError("no SetupStepOne")
        val saved = step1.indexOf("R.string.destroy_not_erased_saved_files")
        val line = step1.indexOf("aiCreditLine()")
        val button = step1.indexOf("R.string.destroy_pin_setup_skip")
        assertTrue("saved-files line < AI line < buttons ($saved, $line, $button)", saved in 0 until line && line < button)
        for (file in listOf(APS, DPS, FLOW_SOURCE)) {
            assertFalse("$file names the AI code", Regex("""screen\.ai\.|\bAi[A-Z]\w+""").containsMatchIn(code(file)))
        }
    }

    @Test
    fun `(17) the set-up never leads into a Destroy`() {
        for (file in listOf(APS, DPS, FLOW_SOURCE)) {
            for (name in listOf("destroyAll", "EnhancedDestroyArgs")) assertFalse("$file names $name", name in code(file))
        }
    }

    // ---- the texts --------------------------------------------------------------------------------------------------

    @Test
    fun `(18) the eleven new keys in English and Spanish, and the two changed values`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for ((key, text) in NEW_KEYS) {
            assertEquals("English $key", text, en[key])
            val spanish = es[key] ?: throw AssertionError("no Spanish $key")
            assertNotEquals("$key is not translated", text, spanish)
            assertFalse("$key has a placeholder", '%' in text || '%' in spanish)
        }
        val optional = en.getValue("destroy_pin_setup_optional")
        assertTrue("destroys everything" in optional && "if the phone has one" in optional)
        val optionalEs = es.getValue("destroy_pin_setup_optional")
        assertTrue("lo destruye todo" in optionalEs && "si el teléfono tiene uno" in optionalEs)
        val body = en.getValue("chat_destroy_set_pin_body")
        assertTrue("asks for it" in body)
        assertFalse("need it" in body)
        assertTrue("lo pide" in es.getValue("chat_destroy_set_pin_body"))
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private fun toStep3(
        flow: Any,
        tick: Boolean = false,
    ) {
        call(flow, "onSetUp")
        if (tick) {
            call(flow, "onToggleAdvanced")
            call(flow, "onChooseKeep")
            call(flow, "onTickContacts", true)
        }
        call(flow, "onContinue")
    }

    private fun pins(
        flow: Any,
        pin: String,
    ) {
        call(flow, "onPinChange", pin)
        call(flow, "onConfirmChange", pin)
    }

    private fun step(flow: Any): Int = value(flow, "getStep") as Int

    private fun value(
        flow: Any,
        getter: String,
    ): Any? = call((call(flow, "getState") as StateFlow<*>).value!!, getter)

    private suspend fun save(flow: Any): Boolean = suspendCall(flow, "save") as Boolean

    private suspend fun proof(
        flow: Any,
        ok: Boolean,
    ): Boolean = suspendCall(flow, "onProofResult", ok) as Boolean

    private suspend fun suspendCall(
        target: Any,
        name: String,
        vararg args: Any,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size + 1 }
                ?: throw AssertionError("${target.javaClass.simpleName} has no suspend $name")
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(target, *args, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

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

    private fun setField(
        target: Any,
        name: String,
        value: Any?,
    ) {
        val field =
            generateSequence<Class<*>>(target.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.name == name }
                ?: throw AssertionError("${target.javaClass.simpleName} has no field $name")
        field.isAccessible = true
        field.set(target, value)
    }

    private fun keepChoice(manager: DestroyManager): Any? =
        DestroyManager::class.java.declaredFields
            .first { it.name == "keepChoice" }
            .apply { isAccessible = true }
            .get(manager)

    private fun managerKeep(manager: DestroyManager): Any? = keepChoice(manager)?.let { call(it, "getKeep") }

    private fun managerGeneration(manager: DestroyManager): Any? = keepChoice(manager)?.let { call(it, "getGeneration") }

    private fun code(path: String): String {
        val file = File(ChatResourceText.repoRoot, path)
        if (!file.isFile) throw AssertionError("there is no $path")
        return SourceScan.stripComments(file.readText())
    }

    private companion object {
        const val PIN = "2468"
        const val FLOW = "co.electriccoin.zcash.ui.screen.onboarding.DestroyPinSetupFlow"
        const val UI = "ui-lib/src/main/java/co/electriccoin/zcash/ui"
        const val APS = "$UI/screen/onboarding/AndroidDestroyPinSetup.kt"
        const val DPS = "$UI/screen/onboarding/view/DestroyPinSetupView.kt"
        const val FLOW_SOURCE = "$UI/screen/onboarding/DestroyPinSetupFlow.kt"

        /** Spec 9.6: the eleven new keys, English word for word. */
        val NEW_KEYS =
            linkedMapOf(
                "destroy_pin_setup_title" to "Emergency Data Wipe",
                "destroy_pin_setup_pin_title" to "PIN Protection",
                "destroy_pin_setup_pin_body" to "Set a PIN to protect against accidental data wipe",
                "destroy_pin_setup_start" to "Set Up PIN Now",
                "destroy_pin_setup_change" to "Change the Destroy PIN",
                "destroy_pin_setup_skip" to "Skip for Now",
                "destroy_pin_setup_optional" to
                    "A Destroy PIN is optional. Without one, Destroy All destroys everything, and it asks for your phone's " +
                    "screen lock if the phone has one.",
                "destroy_pin_setup_lock_needed" to "To keep the list of names, confirm this phone's screen lock. Nothing was saved.",
                "destroy_pin_setup_continue" to "Continue",
                "destroy_pin_setup_save_failed" to "ZCHAT could not save the PIN and the choice. Nothing was changed; try again.",
                "chat_destroy_screen_lock_prompt" to "Confirm this phone's screen lock to go on with Destroy All.",
            )
    }
}
