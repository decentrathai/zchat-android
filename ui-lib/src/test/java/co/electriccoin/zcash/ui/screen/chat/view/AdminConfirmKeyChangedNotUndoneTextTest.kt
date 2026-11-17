package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.AdminConfirmRefusal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * ZC-951 residual, review of 14 Oct finding 5 (skill app-texts) — when a confirmation of a group's admin was written,
 * the contact's key changed meanwhile, and neither the write-back nor the put-back of the record from before took,
 * the dialog has its own true text.
 *
 * What the code leaves then: the group's record holds the key the user compared (the first write was durable, and the
 * store keeps the record it could not replace), while the contact's chat now holds another key; the card reads the
 * group again and asks. So the text says the key was saved and could not be undone, never "nothing was confirmed"
 * (`group_admin_not_saved`) and never that a restart undoes it.
 *
 * - the outcome has its own refusal, `KEY_CHANGED_NOT_UNDONE`;
 * - `group_admin_saved_key_changed` in English (pinned word for word) and Spanish (an agent draft for the
 *   native-speaker pass), no placeholders;
 * - the dialog shows it for that refusal.
 *
 * At the base all three fail: the outcome is STORE_REFUSED and the dialog says "nothing was confirmed".
 */
class AdminConfirmKeyChangedNotUndoneTextTest {
    @Test
    fun `the outcome has its own refusal`() {
        assertTrue(
            "no refusal KEY_CHANGED_NOT_UNDONE",
            AdminConfirmRefusal.values().any { it.name == "KEY_CHANGED_NOT_UNDONE" },
        )
    }

    @Test
    fun `the text in English and Spanish says the key was saved and could not be undone`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        assertEquals(
            "ZChat saved the key you compared, but this person’s key changed while it was being saved, and ZChat couldn’t " +
                "undo that. Check this group’s admin again.",
            en["group_admin_saved_key_changed"],
        )
        val spanish = es["group_admin_saved_key_changed"] ?: throw AssertionError("no Spanish group_admin_saved_key_changed")
        assertNotEquals(en["group_admin_saved_key_changed"], spanish)
        for (text in listOf(en.getValue("group_admin_saved_key_changed"), spanish)) {
            assertFalse("a placeholder in: $text", text.contains("%"))
        }
        for (old in listOf("nothing was confirmed", "restart", "not on the disk")) {
            assertFalse("the text says \"$old\"", en.getValue("group_admin_saved_key_changed").lowercase().contains(old))
        }
    }

    @Test
    fun `the dialog shows the text for that refusal`() {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        val body = src.substringAfter("private fun GroupAdminConfirmDialogs(").substringBefore("\n}\n")
        assertTrue("the dialog never names the refusal", body.contains("AdminConfirmRefusal.KEY_CHANGED_NOT_UNDONE"))
        assertTrue("the dialog never shows the text", body.contains("R.string.group_admin_saved_key_changed"))
    }
}
