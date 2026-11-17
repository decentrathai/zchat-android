package co.electriccoin.zcash.ui.screen.enhanceddestroy

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R2-7 (a), plan step p2-64b6276746af (PRS-005, PRS-006): two screens promise messages that are never sent.
 *
 * - Emergency Destroy offers "Send goodbye to all contacts". EnhancedDestroyVM.onFinalDestroy only waits
 *   500 ms (a TODO) and then wipes the wallet, so nobody is told, and after the wipe the user cannot send
 *   anything any more.
 * - Change Identity offers "Notify All Contacts" with a cost quote (10,000 zatoshi per contact, below the
 *   real 17,000). sendAddressChangeNotifications is a TODO plus a debug log, and the screen then says
 *   "Success!". Full Reset never notifies anyone either.
 *
 * Must hold (English and Spanish): neither option is shown until sending really works; Change Identity
 * quotes no notification cost and says plainly that contacts are not told the new address and must be
 * given it by hand. Pinned: Destroy wipes exactly as before (the countdown ends in
 * destroyAll(requestUninstall = true), also on the error path).
 */
class NoUnsentContactNoticesTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res").isDirectory) dir = dir.parentFile
        assertNotNull(dir, "repo root not found")
    }

    private fun source(path: String) =
        File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/$path").readText()

    // ---- Emergency Destroy ----

    @Test
    fun destroy_offers_no_goodbye_message() {
        val view = source("enhanceddestroy/view/EnhancedDestroyView.kt").lowercase()
        assertFalse("goodbye" in view, "Emergency Destroy still offers a goodbye message it never sends")
        val vm = source("enhanceddestroy/EnhancedDestroyVM.kt").lowercase()
        assertFalse("goodbye" in vm, "EnhancedDestroyVM still carries the unsent goodbye option")
        val fields = EnhancedDestroyState::class.java.declaredFields.map { it.name }
        assertEquals(emptyList(), fields.filter { "goodbye" in it.lowercase() }, "the destroy state still has goodbye fields")
        assertEquals(
            emptyList(),
            DestroyStep.entries.map { it.name }.filter { "GOODBYE" in it },
            "the destroy flow still has a goodbye step",
        )
    }

    /** Pin (passes before and after): the wipe itself is unchanged. */
    @Test
    fun destroy_still_wipes_everything_after_the_countdown() {
        val vm = source("enhanceddestroy/EnhancedDestroyVM.kt")
        assertEquals(
            2,
            Regex("""destroyManager\.destroyAll\(requestUninstall = true\)""").findAll(vm).count(),
            "onFinalDestroy must wipe with requestUninstall = true on the normal and the error path",
        )
        val countdown = vm.substringAfter("private fun onStartCountdown()").substringBefore("private fun onCancelCountdown()")
        assertTrue("for (i in 5 downTo 1)" in countdown, "the 5-second countdown changed")
        assertTrue("onFinalDestroy()" in countdown, "the countdown no longer ends in the wipe")
    }

    // ---- Change Identity ----

    @Test
    fun change_identity_offers_no_notify_all_and_quotes_no_cost() {
        val view = source("changeidentity/ChangeIdentityView.kt")
        for (phrase in listOf("Notify All Contacts", "Notify Contacts?", "Contacts to notify", "Estimated cost", "Cost: ~")) {
            assertFalse(phrase in view, "Change Identity still shows \"$phrase\"")
        }
        val vm = source("changeidentity/ChangeIdentityVM.kt")
        for (phrase in listOf("sendAddressChangeNotifications", "Would notify", "estimatedCost", "0.0001 ZEC per")) {
            assertFalse(phrase in vm, "ChangeIdentityVM still has \"$phrase\"")
        }
        val fields = Class.forName("co.electriccoin.zcash.ui.screen.changeidentity.ChangeIdentityState").declaredFields.map { it.name }
        assertFalse("estimatedCost" in fields, "the Change Identity state still carries a notification cost")
        assertFalse("selectedNotification" in fields, "the Change Identity state still offers a notification choice")
        val notificationOption = runCatching { Class.forName("co.electriccoin.zcash.ui.screen.changeidentity.NotificationOption") }
        assertTrue(notificationOption.isFailure, "NotificationOption (Notify all / Silent) still exists")
    }

    @Test
    fun change_identity_says_contacts_are_not_told_in_english_and_spanish() {
        val en = strings("values")
        val es = strings("values-es")
        for (key in CONTACTS_NOT_TOLD_KEYS) {
            val e = en[key] ?: fail("values has no $key")
            val s = es[key] ?: fail("values-es has no $key")
            assertTrue("not told" in e.lowercase(), "$key must say contacts are not told: $e")
            assertTrue("yourself" in e.lowercase(), "$key must say the user gives the address by hand: $e")
            assertTrue("tú mismo" in s.lowercase() || "tu mismo" in s.lowercase(), "$key (es) must say the user gives it by hand: $s")
            assertNotEquals(e.trim(), s.trim(), "$key is English in the Spanish file")
        }
        val fullReset = en.getValue("change_identity_full_reset_contacts").lowercase()
        assertTrue("new address" in fullReset, fullReset)
        val view = source("changeidentity/ChangeIdentityView.kt")
        for (key in CONTACTS_NOT_TOLD_KEYS) {
            assertTrue("R.string.$key" in view, "Change Identity does not show $key")
        }
    }

    private fun strings(qualifier: String): Map<String, String> {
        val dir = File(repoRoot, "ui-lib/src/main/res/ui/common/$qualifier")
        return dir
            .listFiles { f -> f.extension == "xml" }
            .orEmpty()
            .flatMap { f ->
                val nodes =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(f)
                        .getElementsByTagName("string")
                (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("name") to it.textContent }
            }.toMap()
    }

    private companion object {
        val CONTACTS_NOT_TOLD_KEYS = listOf("change_identity_full_reset_contacts", "change_identity_diversified_contacts")
    }
}
