package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * ZC-1474 as a feature (OT213n B1c; owner W-123; FIX_PLAN REVISION 2; skill compat-2124 rules 1 and 2): a 2.12.4
 * phone must never receive a `ZATT|v1|…` rumor.
 *
 * [Frozen2124] is the v2.12.4 relay reader (a1fb4136f): it shows anything it does not know as a text bubble, so a ZATT
 * reaching it would put the raw wire text, the attachment's file key included, on the screen as a message. A ZATT is
 * therefore not "ignorable" by 2.12.4 and is allowed only to a contact with the recorded 2.13 capability.
 *
 * At 669cf19e1 AttachmentTransport does not exist (the assertions below fail on the planned name). Wanted: for every
 * mode, bound state and key state, a contact without the 2.13 capability never gets NOSTR_ZATT.
 */
class ZattNeverReaches2124Test {
    /**
     * VERBATIM logic of v2.12.4 (`git show v2.12.4:…`), only the outcomes reduced to a word:
     * - ChatViewModel.kt observeNostrInbound, the branch order: :613 ZBootMessage.isBootMessage, :629
     *   isDisappearSetting, :639 isModeChange, :653 ZPROFMessage.isProfileMessage, :670 isGroupMessage, :683 isStatus,
     *   :692 isReadReceipt, :701 isReaction (each `return@collect` = "dropped"); :745-747 parseV4Init unwrap; :749
     *   untagReply (a text without the U+0001 reply marker is returned unchanged, :2224); :760 ZFILEMessage.isFileMessage
     *   ("file"); payment request ("request"); else :812-818 `ChatMessage(… text = strippedBody …)` ("text:" + body).
     * - prefixes: ZBootMessage.kt:56 "ZBOOT|"; ZMSGConstants.kt:51 GROUP, :54 REACTION, :55 RECEIPT, :56 STATUS, :59
     *   REQUEST, :61 DISAPPEAR, :62 MODE_CHANGE, :63 PROFILE (ZMSGSpecialMessages.kt:81/121/163/450/462/487 and
     *   ZPROFMessage.kt:60, ZMSGGroupProtocol.kt:66 are `startsWith` of these); ZFILEMessage.kt:60-61.
     * The v4 INIT unwrap is left out: a ZATT is never wrapped in an INIT (it goes only to a bound key).
     */
    private object Frozen2124 {
        private const val BOOT = "ZBOOT|"
        private const val GROUP = "ZMSG:3.0:GROUP:"
        private const val REACTION = "ZREACT|"
        private const val RECEIPT = "ZRCPT|"
        private const val STATUS = "ZSTAT|"
        private const val REQUEST = "ZREQ|"
        private const val DISAPPEAR = "ZEXP|"
        private const val MODE_CHANGE = "ZMODE|"
        private const val PROFILE = "ZPROF|"

        private fun isFileMessage(content: String): Boolean = content.startsWith("ZFILE|") && content.indexOf('|', 6) > 6

        fun relayReader(plaintext: String): String {
            for (dropped in listOf(BOOT, DISAPPEAR, MODE_CHANGE, PROFILE, GROUP, STATUS, RECEIPT, REACTION)) {
                if (plaintext.startsWith(dropped)) return "dropped"
            }
            val strippedBody = plaintext
            if (isFileMessage(strippedBody)) return "file"
            if (strippedBody.startsWith(REQUEST)) return "request"
            return "text:$strippedBody"
        }
    }

    @Test
    fun `Frozen2124 control - a 2_12_4 phone shows a ZATT as a text bubble with the file key in it`() {
        val zatt = B1cPlanned.wire()
        assertEquals("text:$zatt", Frozen2124.relayReader(zatt), "the copied v2.12.4 reader moved")
        assertEquals("dropped", Frozen2124.relayReader("ZRCPT|synthetic"))
        assertEquals("file", Frozen2124.relayReader("ZFILE|${B1cPlanned.HASH}|j|1|u|k|"))
    }

    @Test
    fun `the sender never chooses NOSTR_ZATT for a contact without the 2_13 capability`() {
        for (mode in ConversationMode.entries) {
            for (bound in listOf(false, true)) {
                for (sharedKey in listOf(false, true)) {
                    assertNotEquals(
                        "NOSTR_ZATT",
                        B1cPlanned.choose(mode, bound, false, sharedKey),
                        "$mode bound=$bound sharedKey=$sharedKey: a 2.12.4 contact would see the raw ZATT and its key",
                    )
                }
            }
        }
    }
}
