package co.electriccoin.zcash.ui.screen.invite

import co.electriccoin.zcash.ui.design.util.QrDrawFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QR-7 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §1.3 and §1.4.5 with §9 row 5; QR-DRAW spec §6 item 3): Invite
 * Friend can share the free-chat code as a picture, next to the address picture it shares today.
 *
 * At the parent "Share QR Code" shared only the address, so a friend who imported the shared picture got no relay key
 * and no free chat. The new share draws the code with the one margin every QR uses (QR-DRAW: no margin parameter, no
 * overload). The code pairs the selected account's address with this phone's relay key, so for a Keystone account the
 * screen builds no code: no code QR and no share of it. Source scan; brace-matched blocks.
 */
class InviteSharesTheFreeChatCodeTest {
    private val base = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/invite"
    private val vm by lazy { QrDrawFixtures.source("$base/viewmodel/InviteFriendVM.kt") }
    private val view by lazy { QrDrawFixtures.source("$base/view/InviteFriendView.kt") }
    private val screen by lazy { QrDrawFixtures.source("$base/AndroidInviteFriend.kt") }

    private fun squash(text: String) = QrDrawFixtures.squash(text)

    @Test
    fun the_vm_shares_the_contact_code_as_a_picture() {
        assertTrue("fun shareContactCodeQr(" in vm, "InviteFriendVM has no share of the contact code")
        val share = squash(QrDrawFixtures.block(vm, "fun shareContactCodeQr("))
        assertTrue("shareQR(" in share, "shareContactCodeQr must draw the picture with ShareQRUseCase")
        assertTrue("qrData = code," in share, "shareContactCodeQr must draw the code")
        assertTrue("filenamePrefix = \"zchat_free_chat\"" in share, "the free-chat picture's file name")
        assertFalse("margin" in share, "the margin is QR_CODE_IMAGE_MARGIN_IN_PIXELS everywhere (QR-DRAW)")
    }

    @Test
    fun the_address_share_is_unchanged() {
        val share = squash(QrDrawFixtures.block(vm, "fun shareQrCode("))
        assertTrue("qrData = address," in share, "Share QR Code must keep drawing the address")
        assertTrue("filenamePrefix = \"zchat_invite\"" in share, "Share QR Code keeps its file name")
    }

    @Test
    fun the_view_offers_the_share_under_the_code_qr_only_when_there_is_a_code() {
        assertTrue(Regex("""onShareCodeQr: \(\) -> Unit""").containsMatchIn(view), "InviteFriendView has no onShareCodeQr")
        val codeBlock = squash(QrDrawFixtures.block(view, "if (contactCode != null)"))
        assertTrue("R.string.invite_share_code_qr" in codeBlock, "the button must sit inside the contact-code block")
        assertTrue("onClick = onShareCodeQr" in codeBlock, "the button must call onShareCodeQr")
        assertEquals(1, Regex("""R\.string\.invite_share_code_qr""").findAll(view).count(), "one free-chat share button")
        val screenText = squash(screen)
        assertTrue(
            Regex("""onShareCodeQr = \{[^}]*viewModel\.shareContactCodeQr\(""").containsMatchIn(screenText),
            "AndroidInviteFriend must wire onShareCodeQr to shareContactCodeQr"
        )
    }

    @Test
    fun a_keystone_account_gets_no_code() {
        val text = squash(vm)
        assertTrue("getSelectedWalletAccount" in text, "InviteFriendVM must read the selected account")
        assertTrue(".forAccount(" in text, "the address must be the one of the account it checked")
        assertTrue(
            Regex("""is KeystoneAccount\) \{?\s*_contactCode\.value = null""").containsMatchIn(text) ||
                Regex("""if \(account is KeystoneAccount\)[^\n]*null""").containsMatchIn(text),
            "InviteFriendVM must build no code (null) for a Keystone account"
        )
    }
}
