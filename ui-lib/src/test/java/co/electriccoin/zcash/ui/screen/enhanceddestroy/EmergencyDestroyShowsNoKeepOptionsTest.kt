package co.electriccoin.zcash.ui.screen.enhanceddestroy

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.DestroyManager
import co.electriccoin.zcash.ui.screen.chat.util.FakePhone
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

/**
 * DESTROY-ADV-C (register ZC-722; owner W-52: every choice is made when the Destroy PIN is set up, nothing is asked when
 * Destroy is triggered; Destroy Advanced spec revision 2c, 7.4 rules E1 to E8, 9.3 and 10.2).
 *
 *  - the Destroy flows (the chat list's Destroy dialogs, Emergency Destroy's view and view model) hold no tick box,
 *    switch, radio button or expander and never write the choice; only the options screen's view model writes it,
 *    and only Advanced Settings opens that screen;
 *  - each of the eight kept-branch sentences of spec 9.3 is used only when the saved choice keeps something, and its
 *    default stays in the other branch; the final button keeps its one text;
 *  - the flows take the choice from DestroyManager, which reads it from the store again when each of the three opens;
 *    ChatViewModel's dormant remote kill still calls destroyAll as before;
 *  - B5: with the default choice and a list from an earlier Destroy on the phone, the chat list's final dialog and
 *    Emergency Destroy's first screen say that this Destroy erases it too (a file check, nothing decrypted); with no
 *    list, or with the tick saved, they do not.
 */
class EmergencyDestroyShowsNoKeepOptionsTest {
    private fun code(path: String): String {
        val file = File(ChatResourceText.repoRoot, path)
        assertTrue("fixture: $path", file.isFile)
        return SourceScan.stripComments(file.readText())
    }

    /** The chat list's Destroy dialogs: from the first of the two PIN dialog marks to the end of the final dialog. */
    private fun chatListDialogs(): String {
        val raw = File(ChatResourceText.repoRoot, CLV).readText()
        val start = listOf("// PIN Setup Dialog", "// PIN Verify Dialog").map { raw.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val end = raw.indexOf("// Group Chat Coming Soon Dialog")
        assertTrue("fixture: the Destroy dialogs of ChatListView", start != null && end > start)
        return SourceScan.stripComments(raw.substring(start!!, end))
    }

    @Test
    fun `no Destroy flow has a tick box, a switch, an expander, or writes the choice`() {
        val flows = mapOf("ChatListView's Destroy dialogs" to chatListDialogs(), EDV to code(EDV), EDM to code(EDM))
        for ((name, text) in flows) {
            for (control in listOf("Checkbox", "Switch(", "RadioButton", "ExpandMore", "ExpandLess", "AnimatedVisibility", "expanded")) {
                assertFalse("$name has $control", control in text)
            }
            for (write in listOf("setDestroyKeep", "setDestroySetup")) {
                assertFalse("$name writes the choice ($write)", write in text)
            }
        }
    }

    @Test
    fun `only the options view model writes the choice, and only Advanced Settings opens its screen`() {
        val main = File(ChatResourceText.repoRoot, "ui-lib/src/main/java")
        val writers =
            main
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { Regex("""\.setDestroyKeep\(|::setDestroyKeep""").containsMatchIn(SourceScan.stripComments(it.readText())) }
                .map { it.name }
                .toList()
        assertEquals("setDestroyKeep is called from the options view model only", listOf("DestroyOptionsVM.kt"), writers)
        val openers =
            main
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { "DestroyOptionsArgs" in SourceScan.stripComments(it.readText()) }
                .map { it.name }
                .toSet()
        assertEquals(
            "the options screen is named by its route, the nav graph and Advanced Settings only",
            setOf("DestroyOptionsArgs.kt", "WalletNavGraph.kt", "AdvancedSettingsVM.kt"),
            openers
        )
    }

    @Test
    fun `each kept sentence is used only for a kept choice, and its default in the other branch`() {
        for ((file, pairs) in SENTENCES) {
            val text = code(file)
            for ((kept, default) in pairs) {
                val branch =
                    Regex(
                        """if\s*\(\w+\)\s*\{?\s*(stringResource\()?R\.string\.$kept\)?\s*\}?\s*else\s*\{?\s*(stringResource\()?R\.string\.$default\b"""
                    )
                assertTrue("$file: $kept is not chosen against $default by the saved choice", branch.containsMatchIn(text))
                assertEquals("$file: $kept is used outside its branch", 1, Regex("""R\.string\.$kept\b""").findAll(text).count())
            }
        }
        val dialogs = chatListDialogs()
        assertEquals(
            "the final button keeps its one text",
            1,
            Regex("""R\.string\.chat_destroy_final_confirm\b""").findAll(dialogs).count()
        )
        val strings = ChatResourceText.strings("values").keys
        assertFalse("a second text for the final button", strings.any { it.startsWith("chat_destroy_final_confirm_") })
    }

    @Test
    fun `the flows take the choice from DestroyManager, read again from the store when each opens`() {
        for (file in listOf(ANDROID_CHAT, EDM, APS)) {
            val text = code(file)
            assertTrue("$file does not read the choice again when it opens", "destroyManager.readDestroyFlowChoice()" in text)
            assertFalse("$file reads the choice past DestroyManager", "getDestroyKeep" in text)
        }
        val manager = code(MANAGER)
        val read = KeptNamesScreenApi.functionBody(manager, "readDestroyFlowChoice") ?: throw AssertionError("no readDestroyFlowChoice")
        assertTrue("it reads the store again", "reloadDestroyKeep()" in read)
        for (decrypt in listOf("read()", "Cipher", "keyBox", "open(")) {
            assertFalse("the earlier-list check decrypts ($decrypt)", decrypt in read)
        }
        val chat = code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt")
        assertEquals(
            "ChatViewModel's remote kill still calls destroyAll",
            1,
            Regex("""destroyManager\.destroyAll\(requestUninstall = true\)""").findAll(chat).count()
        )
    }

    @Test
    fun `B5 - with the default choice an earlier list is said to be erased too, on both paths`() {
        assertTrue("the chat list's final dialog", Regex("""if \(\w+\.erasesEarlierList\)""").containsMatchIn(chatListDialogs()))
        assertTrue("the chat list's final dialog", "R.string.destroy_erases_earlier_list" in chatListDialogs())
        val confirmStep = KeptNamesScreenApi.functionBody(code(EDV), "ConfirmIntentStep") ?: throw AssertionError("no ConfirmIntentStep")
        assertTrue("Emergency Destroy's first screen", "R.string.destroy_erases_earlier_list" in confirmStep)

        for ((case, keep, file, expected) in listOf(
            Case("the default choice and a list on the phone", emptySet(), true, true),
            Case("the default choice and no list", emptySet(), false, false),
            Case("the tick saved and a list on the phone", setOf("contacts"), true, false),
        )) {
            FakePhone().use { phone ->
                val manager = phone.manager()
                set(manager, "zchatPreferences", prefs(keep))
                if (file) {
                    File(phone.dataDir, "no_backup").mkdirs()
                    File(phone.dataDir, "no_backup/zchat_kept_names").writeBytes(byteArrayOf(1, 2, 3))
                }
                val choice = readFlowChoice(manager)
                assertEquals("$case: erases an earlier list", expected, call(choice, "getErasesEarlierList"))
                assertEquals("$case: keeps contacts", "contacts" in keep, call(choice, "getKeepsContacts"))
            }
        }
    }

    private data class Case(
        val name: String,
        val keep: Set<String>,
        val file: Boolean,
        val expected: Boolean,
    )

    private val generation = AtomicLong(3)

    private fun prefs(keep: Set<String>): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
            when (method.name) {
                "getDestroyKeep" -> keep
                "walletScopeGeneration" -> generation.get()
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
            }
        } as ZchatPreferences

    private fun readFlowChoice(manager: DestroyManager): Any {
        val method =
            DestroyManager::class.java.methods.firstOrNull { it.name == "readDestroyFlowChoice" && it.parameterCount == 0 }
                ?: throw AssertionError("DestroyManager has no readDestroyFlowChoice()")
        return try {
            method.invoke(manager)!!
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun call(
        target: Any,
        name: String,
    ): Any? =
        target.javaClass.methods
            .first { it.name == name && it.parameterCount == 0 }
            .invoke(target)

    private fun set(
        manager: DestroyManager,
        name: String,
        value: Any?,
    ) {
        DestroyManager::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(manager, value)
    }

    private companion object {
        const val UI = "ui-lib/src/main/java/co/electriccoin/zcash/ui"
        const val CLV = "$UI/screen/chat/view/ChatListView.kt"
        const val EDV = "$UI/screen/enhanceddestroy/view/EnhancedDestroyView.kt"
        const val EDM = "$UI/screen/enhanceddestroy/EnhancedDestroyVM.kt"
        const val DPS = "$UI/screen/onboarding/view/DestroyPinSetupView.kt"
        const val APS = "$UI/screen/onboarding/AndroidDestroyPinSetup.kt"
        const val ANDROID_CHAT = "$UI/screen/chat/AndroidChat.kt"
        const val MANAGER = "$UI/screen/chat/util/DestroyManager.kt"

        /** Spec 9.3: the kept-branch sentence and the default it stands in for, by the file that shows them. */
        val SENTENCES =
            mapOf(
                CLV to
                    listOf(
                        "chat_destroy_verify_warning_keep_contacts" to "chat_destroy_verify_warning",
                        "chat_destroy_final_kept_contacts" to "chat_destroy_final_item_contacts",
                        "chat_destroy_final_stays_installed_note" to "chat_destroy_final_uninstall_note",
                    ),
                EDV to
                    listOf(
                        "destroy_will_delete_keep_contacts" to "destroy_will_delete",
                        "destroy_kept_contacts" to "destroy_item_contacts",
                        "destroy_after_countdown_keep_contacts" to "destroy_after_countdown",
                        "destroy_please_wait_keep_contacts" to "destroy_please_wait",
                    ),
                DPS to listOf("destroy_pin_setup_what_body_keep_contacts" to "destroy_pin_setup_what_body"),
            )
    }
}
