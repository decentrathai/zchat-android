package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.GroupInviteWaitRecord
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-517 residual (reviewer, 12 Oct, finding 5; register notes) — a waiting "I joined" reply that is too long for one
 * memo but whose relay try has not failed yet (a creator's re-invite while this phone holds the creator's relay key:
 * the record is written with relayFailed = false) is shown with a price, and its first Join says "You joined".
 *
 * At 1081349f5 the group screen (AndroidGroupDetail) decides "relay only" as `relayFailed && !canSendOnChain`, so such a
 * record gets the priced banner (`group_invite_request_banner`: "it can cost at least X ZEC") for a payment that can
 * never be made, and the tap keeps `joinOutcome = true` ("You joined") while the reply has not gone anywhere.
 *
 * Wanted: whether the reply can be paid is decided from its size alone: a waiting record whose memo does not fit one
 * memo is relay-only whatever its relay flag says, so no price is shown or formatted and the tap claims no success.
 * Before any relay try the banner says plainly that the reply is too long to send on-chain and can only go over the
 * relays, and that nothing has been paid; after a failed try the existing relay-only sentence stays (EN + ES; the Spanish
 * line is an agent draft).
 *
 * Source-driven like GroupJoinRelayOnlyBannerNamesNoPriceTest (no rendered UI claim). All values are synthetic.
 */
class JoinReplyTooLongNamesNoPriceBeforeItsRelayTryTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }
    private val screen by lazy {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        val start = src.indexOf("fun AndroidGroupDetail(")
        val end = src.indexOf("private fun GroupInviteJoinQuestions(", start)
        assertTrue(start >= 0 && end > start, "fixture: AndroidGroupDetail source")
        src.substring(start, end)
    }
    private val view by lazy {
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupDetailView.kt")
    }

    private fun compact(text: String): String = text.replace(Regex("""\s+"""), "")

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sd]""")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    @Test
    fun fixture_an_untried_oversized_record_is_neither_relay_failed_nor_payable() {
        val record =
            JSONObject()
                .put("inviter", "usyntheticzc517creator00001")
                .put("accepter", "u1" + "q".repeat(211))
                .put("memo", "ZMSG:3.0:GROUP:GA:zgrp_5170a1b2c3d4e5f6a7b8c9d0:" + "x".repeat(530))
                .toString()
        assertFalse(GroupInviteWaitRecord.relayFailed(record), "fixture: the record's relay try has not failed")
        assertFalse(GroupInviteWaitRecord.canSendOnChain(record), "fixture: the reply does not fit one memo")
    }

    @Test
    fun the_screen_decides_relay_only_from_the_size_alone() {
        val src = compact(screen)
        val rule = Regex("""valjoinReplyIsRelayOnly=([^\n]*?)varinviteFlow""").find(src)?.groupValues?.get(1)
        assertNotNull(rule, "fixture: the relay-only rule of the screen")
        assertTrue(".canSendOnChain(joinRecord)" in rule, "the screen does not read the reply's size: $rule")
        assertFalse(
            "joinReplyIsPaid&&" in rule,
            "a reply too long for one memo is relay-only only after a failed relay try, so it is priced before one: $rule",
        )
        assertTrue("joinRecord!=null" in rule, "no waiting record is not a relay-only reply: $rule")
    }

    @Test
    fun the_relay_only_banner_has_a_sentence_for_a_reply_not_tried_yet() {
        val start = view.indexOf("private fun GroupInviteRequestBanner(")
        assertTrue(start >= 0, "fixture: banner function")
        val branch =
            Regex("""if\s*\(\s*replyIsRelayOnly\s*\)\s*\{([\s\S]*?)\}\s*else""")
                .find(view.substring(start))
                ?.groupValues
                ?.get(1)
        assertNotNull(branch, "banner has no separate relay-only branch")
        assertTrue("R.string.$UNTRIED" in branch, "a reply not tried over the relays is told it 'couldn’t go over the relays'")
        assertTrue("R.string.group_invite_join_relay_only" in branch, "the failed-try sentence is gone")
        for (forbidden in listOf("PaidActionCopy", "handshakeSpendQuote", "rememberDesiredFormatLocale", "known_paid")) {
            assertFalse(forbidden in branch, "relay-only branch formats a price via $forbidden")
        }
    }

    @Test
    fun the_english_sentence_says_why_and_names_no_price() {
        val text = assertNotNull(en[UNTRIED], "no English $UNTRIED")
        for (required in listOf("too long to send on-chain", "can only go over the relays", "Nothing has been paid")) {
            assertTrue(required in text, "$UNTRIED omits $required")
        }
        for (forbidden in listOf("ZEC", "%3\$s", "cost", "price", "couldn’t", "automatically", "by itself")) {
            assertFalse(forbidden in text, "$UNTRIED says $forbidden")
        }
    }

    @Test
    fun the_spanish_sentence_says_why_and_names_no_price() {
        val text = assertNotNull(es[UNTRIED], "no Spanish $UNTRIED")
        for (required in listOf("demasiado larga para enviarla en la cadena", "solo puede ir por los relays", "No se ha pagado nada")) {
            assertTrue(required in text, "Spanish $UNTRIED omits $required")
        }
        for (forbidden in listOf("ZEC", "%3\$s", "cuesta", "precio", "no pudo", "automáticamente", "por sí sola")) {
            assertFalse(forbidden in text, "Spanish $UNTRIED says $forbidden")
        }
        assertEquals(listOf("%1\$s", "%2\$s"), placeholders(assertNotNull(en[UNTRIED])), "English placeholders")
        assertEquals(listOf("%1\$s", "%2\$s"), placeholders(text), "Spanish placeholders")
    }

    private companion object {
        const val UNTRIED = "group_invite_join_relay_only_untried"
    }
}
