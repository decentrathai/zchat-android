package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AutoSpendVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeSeam
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * KI-25 pair 1, ZC-022 (KI-25-SPEC 3.6b R8, sections 3.3, 3.6 and 8; section 4.1 class 6) — the dialog that makes a
 * chat the group's admin on this phone shows who that is, what is granted and that it is final.
 *
 * At `00f1a349b` there is no confirmation at all. Wanted: `ChatViewModel.loadGroupAdminPreview(groupId)` fills
 * `groupAdminPreview` with the contact's name, its **address**, its key, the safety number and where the candidate
 * came from; the dialog's body (`group_admin_confirm_body`) takes the name, the address, the group's name and the
 * safety number and states the grant; `group_admin_confirm_applies` says that the person's earlier changes are
 * applied right after the confirmation (a moment later, by the history pass: not "as soon as") and that a removal of
 * the user makes them leave the group then; `group_admin_confirm_final` says it cannot be changed later. The dialog
 * in AndroidChat.kt shows the three in that order.
 *
 * The Spanish lines are agent drafts. All addresses, keys and ids are synthetic; nothing is sent or paid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminConfirmDialogShowsAddressGrantAndFinalityTest {
    private val sendScopes = SendHarnessScopes()
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private companion object {
        const val ADMIN_NAME = "Synthetic Admin"
        const val SCREEN = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"
        const val BODY = "R.string.group_admin_confirm_body"
        const val APPLIES = "R.string.group_admin_confirm_applies"
        const val FINAL = "R.string.group_admin_confirm_final"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun the_preview_carries_the_name_the_address_the_key_the_safety_number_and_the_source() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            p.name(Y, ADMIN_NAME)
            p.verified += Y
            p.creatingInvite(p.base.sessionWith(Y), Instant.ofEpochMilli(T))
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))

            val preview = assertNotNull(AdminCheck.loadPreview(vm), "the phone's records name the admin's chat and no preview was made")

            assertEquals(ADMIN_NAME, AdminCheck.property(preview, "name"), "the preview's name")
            assertEquals(Y, AdminCheck.property(preview, "address"), "the preview's address")
            assertEquals(p.admin.publicKey, AdminCheck.property(preview, "contactKey"), "the preview's contact key")
            assertEquals(
                vm.computeSafetyNumber(Y),
                AdminCheck.property(preview, "safetyNumber").toString().filterNot { it.isWhitespace() },
                "the preview's safety number is not the number the chat with the admin shows",
            )
            assertEquals("INVITATION", AdminCheck.property(preview, "source").toString(), "the preview's source")
        }

    @Test
    fun the_body_takes_four_values_and_states_the_grant() {
        val english = assertNotNull(en["group_admin_confirm_body"], "no English group_admin_confirm_body")
        val spanish = assertNotNull(es["group_admin_confirm_body"], "no Spanish group_admin_confirm_body")
        val four = setOf("%1\$s", "%2\$s", "%3\$s", "%4\$s")
        assertEquals(four, SpanishSendPaymentDialogTest.placeholders(english).toSet(), "the English body's placeholders")
        assertEquals(four, SpanishSendPaymentDialogTest.placeholders(spanish).toSet(), "the Spanish body's placeholders")
        for (grant in listOf("remove members", "change the key", "on this phone", "created this group")) {
            assertTrue(grant in english, "the confirm dialog's body does not say \"$grant\": $english")
        }
    }

    @Test
    fun the_final_line_says_it_cannot_be_changed_later() {
        val english = assertNotNull(en["group_admin_confirm_final"], "no English group_admin_confirm_final")
        assertNotNull(es["group_admin_confirm_final"], "no Spanish group_admin_confirm_final")
        assertTrue("can’t change this later" in english, "the final line does not say it cannot be changed later: $english")
    }

    @Test
    fun the_applies_line_says_right_after_you_confirm_and_that_a_removed_user_leaves() {
        val english = assertNotNull(en["group_admin_confirm_applies"], "no English group_admin_confirm_applies")
        assertNotNull(es["group_admin_confirm_applies"], "no Spanish group_admin_confirm_applies")
        assertTrue("right after you confirm" in english, "the line does not say \"right after you confirm\": $english")
        assertFalse("as soon as" in english, "the line promises \"as soon as\"; the pass applies them a moment later: $english")
        assertTrue(
            "removed you" in english && "you leave it then" in english,
            "the line does not say that a removal of the user makes them leave the group then: $english",
        )
    }

    @Test
    fun the_dialog_shows_the_body_with_four_values_then_the_applies_line_then_the_final_line() {
        val code =
            ChatResourceText
                .source(SCREEN)
                .lineSequence()
                .filterNot { line -> line.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                .joinToString("\n")
        val body = code.indexOf(BODY)
        val applies = code.indexOf(APPLIES)
        val finalLine = code.indexOf(FINAL)
        assertTrue(body >= 0, "AndroidChat.kt shows no group_admin_confirm_body")
        assertTrue(applies >= 0, "AndroidChat.kt shows no group_admin_confirm_applies")
        assertTrue(finalLine >= 0, "AndroidChat.kt shows no group_admin_confirm_final")
        assertTrue(body < applies && applies < finalLine, "the dialog's order is not body, applies line, final line")
        assertEquals(
            4,
            valuesPassedAfter(code, body + BODY.length),
            "the body is not given its four values (name, address, group name, safety number)",
        )
    }

    /** How many arguments follow the resource id at [from] inside the call that encloses it. */
    private fun valuesPassedAfter(
        code: String,
        from: Int,
    ): Int {
        var depth = 0
        var commas = 0
        var lastSignificant = ' '
        for (i in from until code.length) {
            val c = code[i]
            when {
                c == '(' || c == '{' || c == '[' -> depth++
                (c == ')' || c == '}' || c == ']') && depth > 0 -> depth--
                c == ')' -> return if (lastSignificant == ',') commas - 1 else commas
                c == ',' && depth == 0 -> commas++
            }
            if (!c.isWhitespace()) lastSignificant = c
        }
        return commas
    }
}
