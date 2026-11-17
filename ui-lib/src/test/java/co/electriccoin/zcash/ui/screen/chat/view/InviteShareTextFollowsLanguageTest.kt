package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ES-A (fix-all 2.13.0, train T4c; Spanish fold spec §1.4, §3.3): the Invite Friend text ("Join me on ZCHAT —
 * private messaging that no one can read. Download: … My address: …") is built in English by a companion function,
 * with no locale, and is both the on-screen preview and the text that is shared. The chooser titles "Share Invite"
 * and "Share QR Code" are English literals in the view model.
 *
 * What it must be: one resource, `invite_share_text`, with the download URL and the address as arguments (the URL is
 * never translated), built by the composable in the language the screen shows and passed to both shares, so the
 * preview and what is sent are the same text.
 */
class InviteShareTextFollowsLanguageTest {
    private val en by lazy { ChatResourceText.strings("values").mapValues { it.value.replace("\\n", "\n") } }
    private val es by lazy { ChatResourceText.strings("values-es").mapValues { it.value.replace("\\n", "\n") } }

    private val vmPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/invite/viewmodel/InviteFriendVM.kt"
    private val screenPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/invite/AndroidInviteFriend.kt"

    @Test
    fun the_share_text_is_a_resource_with_the_url_and_address_as_arguments() {
        assertEquals(
            "Join me on ZCHAT — private messaging that no one can read.\nDownload: %1\$s\nMy address: %2\$s",
            en["invite_share_text"],
        )
        val spanish = assertNotNull(es["invite_share_text"], "no Spanish invite_share_text")
        for (text in listOf(en.getValue("invite_share_text"), spanish)) {
            assertFalse("zsend.xyz" in text, "the URL must be an argument, not part of the text: $text")
            assertTrue("%1\$s" in text && "%2\$s" in text, "the URL and the address must both be arguments: $text")
        }
    }

    @Test
    fun the_preview_and_the_share_use_the_same_localized_text() {
        val screen = KotlinUiLiterals.lex(ChatResourceText.source(screenPath)).blanked
        assertTrue(
            Regex("""stringResource\(\s*R\.string\.invite_share_text\s*,\s*InviteFriendVM\.INVITE_DOWNLOAD_URL\s*,""")
                .containsMatchIn(screen),
            "AndroidInviteFriend must build the text from invite_share_text with InviteFriendVM.INVITE_DOWNLOAD_URL",
        )
        assertTrue("shareInvite(" in screen && "shareQrCode(" in screen, "both shares must be called from the screen")
        assertTrue("R.string.invite_share_invite" in screen, "the invite chooser title must be the resource")
        assertTrue("R.string.invite_share_qr" in screen, "the QR chooser title must be the resource")
        val vm = KotlinUiLiterals.lex(ChatResourceText.source(vmPath)).blanked
        assertFalse("fun buildInviteText" in vm, "InviteFriendVM still builds the English invite text")
        assertTrue(Regex("""fun shareInvite\(\s*text: String""").containsMatchIn(vm), "shareInvite must take the shown text")
        assertTrue(
            Regex("""fun shareQrCode\(\s*address: String,\s*text: String""").containsMatchIn(vm),
            "shareQrCode must take the shown text"
        )
    }

    @Test
    fun the_download_url_is_unchanged() {
        val vm = Class.forName("co.electriccoin.zcash.ui.screen.invite.viewmodel.InviteFriendVM")
        val field = runCatching { vm.getField("INVITE_DOWNLOAD_URL") }.getOrNull()
        assertNotNull(field, "InviteFriendVM.INVITE_DOWNLOAD_URL is missing")
        assertEquals("https://zsend.xyz/download", field.get(null))
    }
}
