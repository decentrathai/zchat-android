package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * FD-6 (fix-all 2.13.0, train T4b) — Destroy All says "After deletion, you will be prompted to uninstall the app." and
 * `destroy_done_body` says "The app will now request uninstallation.", but the uninstall screen closes at once.
 * DestroyManager.requestUninstall() sends ACTION_DELETE for ZCHAT's own package, and the app (targetSdk 35) does not
 * declare REQUEST_DELETE_PACKAGES. Android's package installer (callers targeting 28 or higher) then logs
 * "Uid … does not have android.permission.REQUEST_DELETE_PACKAGES or android.permission.DELETE_PACKAGES" and finishes
 * UninstallerActivity at once (seen on the Fold, Android 15, 28 Sep 16:15:06.417). ZCHAT stays installed.
 *
 * Wanted: the app declares REQUEST_DELETE_PACKAGES, so the phone can show its uninstall screen; and the texts say only
 * what the code does — ZCHAT asks the phone to uninstall it — and tell the user what to do when no screen appears
 * (Settings → Apps). The uninstall request still comes after the wipe and before the process is killed.
 *
 * Reads the manifests, the view's source and the string resources, as the other copy tests do.
 */
class DestroyCanShowTheUninstallScreenTest {
    private val permission = "android.permission.REQUEST_DELETE_PACKAGES"

    private val chatListView: String by lazy {
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt")
    }

    /** The final DESTROY ALL dialog of the chat list, from its heading to its confirm button. */
    private val finalDialog: String by lazy {
        val start = chatListView.indexOf("// Final Destroy Confirmation Dialog")
        val end = chatListView.indexOf("onDestroyAll()", start)
        assertTrue(start >= 0 && end > start, "fixture: the final Destroy dialog and its onDestroyAll() in ChatListView.kt")
        chatListView.substring(start, end)
    }

    @Test
    fun the_app_declares_REQUEST_DELETE_PACKAGES_so_the_uninstall_screen_is_not_closed_at_once() {
        val manifest = ChatResourceText.source("ui-lib/src/main/AndroidManifest.xml")
        assertTrue(
            Regex("""<uses-permission\s+android:name="${Regex.escape(permission)}"\s*/>""").containsMatchIn(manifest),
            "ui-lib/src/main/AndroidManifest.xml does not declare $permission: the uninstall screen closes at once",
        )
        // No other manifest of the app may take it away again in the merge.
        val removers =
            listOf("app/src", "ui-lib/src")
                .map { File(ChatResourceText.repoRoot, it) }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.name == "AndroidManifest.xml" }.toList() }
                .filter { f ->
                    Regex("""<uses-permission[^>]*${Regex.escape(permission)}[^>]*tools:node="remove"""")
                        .containsMatchIn(f.readText())
                }.map { it.relativeTo(ChatResourceText.repoRoot).path }
        assertTrue(removers.isEmpty(), "a manifest removes $permission again: $removers")
    }

    @Test
    fun the_destroy_texts_do_not_promise_a_prompt_the_phone_may_not_show() {
        assertTrue(
            "prompted to uninstall" !in chatListView,
            "ChatListView.kt still promises \"you will be prompted to uninstall the app\"",
        )
        assertTrue(
            "stringResource(R.string.chat_destroy_final_uninstall_note)" in finalDialog,
            "the final Destroy dialog does not show chat_destroy_final_uninstall_note",
        )

        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for (key in listOf("chat_destroy_final_uninstall_note")) {
            val enText = en[key]
            val esText = es[key]
            assertTrue(enText != null && esText != null, "$key must exist in English and Spanish")
            assertTrue("Settings → Apps" in enText, "$key (EN) does not say where to uninstall by hand: $enText")
            assertTrue("Ajustes → Aplicaciones" in esText, "$key (ES) does not say where to uninstall by hand: $esText")
            assertTrue(enText != esText, "$key is not translated")
        }
        val promises = listOf("will be prompted", "will now request uninstallation", "pedirá desinstalarse")
        val stillPromising =
            (en + es.mapKeys { "es:${it.key}" })
                .filter { (_, text) -> promises.any { it in text } }
                .keys
        assertTrue(stillPromising.isEmpty(), "strings still promise an uninstall prompt: $stillPromising")
    }

    @Test
    fun the_uninstall_request_comes_after_the_wipe_and_before_the_kill() {
        // Guard (green before the fix): the texts promise the request, so every Destroy asks for it, after the
        // wipe (the uninstall intent needs no app data) and before the process is killed.
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyManager.kt")
        val start = src.indexOf("suspend fun destroyAll(")
        val end = src.indexOf("private suspend fun performFullWipe()")
        assertTrue(start >= 0 && end > start, "fixture: destroyAll and performFullWipe in DestroyManager.kt")
        // The code of destroyAll only: its comments name these calls too.
        val body =
            src
                .substring(start, end)
                .lines()
                .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
                .joinToString("\n")
        val wipe = body.indexOf("performFullWipe()")
        val uninstall = body.indexOf("requestUninstall()")
        val kill = body.indexOf("forceKillApp()")
        assertTrue(
            wipe >= 0 && uninstall > wipe && kill > uninstall,
            "destroyAll must run performFullWipe() < requestUninstall() < forceKillApp() (at $wipe, $uninstall, $kill)",
        )

        val callers =
            File(ChatResourceText.repoRoot, "ui-lib/src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { f -> Regex("""\.destroyAll\(([^)]*)\)""").findAll(f.readText()).map { f.name to it.groupValues[1] } }
                .toList()
        assertTrue(callers.isNotEmpty(), "fixture: no caller of destroyAll found")
        val silent = callers.filter { (_, args) -> args.replace(" ", "") != "requestUninstall=true" }
        assertTrue(silent.isEmpty(), "a Destroy path does not ask for the uninstall the texts promise: $silent")
    }
}
