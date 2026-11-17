package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-517: an oversized kept reply has a relay-only banner with no quote; a failed Join returns to that banner.
 * New resource names and constants are read by name so baseline failures are assertions, not compilation errors.
 * Spanish wording is an agent draft for review. UI checks follow this package's source-driven pattern; this JVM
 * test does not claim rendered 360 dp/font 1.3 verification.
 */
class GroupJoinRelayOnlyBannerNamesNoPriceTest {
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

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sd]""")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    private fun compact(text: String): String = text.replace(Regex("""\s+"""), "")

    @Test
    fun the_english_relay_only_banner_names_its_limit_and_retry_but_no_price() {
        val text = assertNotNull(en[BANNER], "English relay-only banner is missing")
        val requiredText =
            listOf(
                "couldn’t go over the relays",
                "too long to send on-chain",
                "can only go over the relays",
                "Join tries them again",
                "Nothing has been paid",
            )
        for (required in requiredText) {
            assertTrue(required in text, "relay-only banner omits $required")
        }
        for (forbidden in listOf("ZEC", "%3\$s", "cost", "price", "automatically", "by itself")) {
            assertFalse(forbidden in text, "relay-only banner promises $forbidden")
        }
    }

    @Test
    fun the_spanish_relay_only_banner_names_its_limit_and_retry_but_no_price() {
        val text = assertNotNull(es[BANNER], "Spanish relay-only banner is missing")
        val requiredText =
            listOf(
                "no pudo ir por los relays",
                "demasiado larga para enviarla en la cadena",
                "solo puede ir por los relays",
                "Al tocar Unirme, se vuelve a intentar enviarla por ellos",
                "No se ha pagado nada",
            )
        for (required in requiredText) {
            assertTrue(required in text, "Spanish relay-only banner omits $required")
        }
        for (forbidden in listOf("ZEC", "%3\$s", "cost", "precio", "automáticamente", "por sí sola")) {
            assertFalse(forbidden in text, "Spanish relay-only banner promises $forbidden")
        }
        // ZC-1062: the button is «Unirme»; the banner names that button, never «Unirte».
        assertFalse("Unirte" in text, "Spanish relay-only banner names the button «Unirte»: $text")
    }

    @Test
    fun both_new_keys_have_spanish_and_keep_only_their_intended_placeholders() {
        val expected = mapOf(BANNER to listOf("%1\$s", "%2\$s"), NOTICE to emptyList())
        expected.forEach { (key, args) ->
            val english = assertNotNull(en[key], "no English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "$key is not translated")
            assertEquals(args, placeholders(english), "English $key placeholders")
            assertEquals(args, placeholders(spanish), "Spanish $key placeholders")
        }
    }

    @Test
    fun the_notice_says_nothing_went_or_was_paid_and_asks_for_another_join_tap() {
        val english = assertNotNull(en[NOTICE], "no English relay-only notice")
        val spanish = assertNotNull(es[NOTICE], "no Spanish relay-only notice")
        val englishRequired =
            listOf("Not sent:", "can only go over the relays", "couldn’t go over them now", "Nothing was paid", "Tap Join to try again")
        for (required in englishRequired) {
            assertTrue(required in english, "English notice omits $required")
        }
        val spanishRequired =
            listOf(
                "No enviado:",
                "solo puede ir por los relays",
                "en este momento no se pudo enviar por ellos",
                "No se pagó nada",
                "Toca Unirme para intentarlo otra vez",
            )
        for (required in spanishRequired) {
            assertTrue(required in spanish, "Spanish notice omits $required")
        }
        for (text in listOf(english, spanish)) {
            for (forbidden in listOf("automatically", "by itself", "automáticamente", "por sí sola")) {
                assertFalse(forbidden in text, "notice promises an automatic send")
            }
        }
    }

    @Test
    fun the_refusal_constant_maps_to_the_new_localized_notice() {
        val field = runCatching { ChatSendCopy::class.java.getField("GROUP_JOIN_REPLY_RELAY_ONLY_NOT_SENT") }.getOrNull()
        assertNotNull(field, "ChatSendCopy has no relay-only refusal constant")
        val text = field.get(null) as String
        assertEquals(en[NOTICE], text, "the state must carry exactly the English resource's message")
        val id = assertNotNull(ChatSendCopy.localizedResource(text), "notice has no localized mapping")
        assertEquals(NOTICE, ChatResourceText.stringName(id), "notice maps to the wrong resource")
    }

    @Test
    fun the_screen_uses_the_memo_reading_for_the_relay_only_banner() {
        val src = compact(screen)
        assertTrue(".canSendOnChain(joinRecord)" in src, "screen does not use the shared memo-byte reading")
        assertTrue("joinReplyIsRelayOnly=joinReplyIsRelayOnly" in src, "screen does not supply relay-only status to the view")
        assertTrue("replyIsRelayOnly=joinReplyIsRelayOnly" in compact(view), "view does not supply relay-only status to the banner")
    }

    @Test
    fun the_relay_only_banner_branch_never_formats_or_supplies_a_price() {
        val start = view.indexOf("private fun GroupInviteRequestBanner(")
        assertTrue(start >= 0, "fixture: banner function")
        val banner = view.substring(start)
        val branch =
            Regex("""if\s*\(\s*replyIsRelayOnly\s*\)\s*\{([\s\S]*?)\}\s*else""")
                .find(banner)
                ?.groupValues
                ?.get(1)
        assertNotNull(branch, "banner has no separate relay-only branch")
        assertTrue("R.string.group_invite_join_relay_only" in branch, "relay-only branch selects a priced string")
        assertTrue("shortInviter" in branch && "groupName" in branch, "relay-only branch lost inviter/group arguments")
        for (forbidden in listOf("PaidActionCopy", "handshakeSpendQuote", "rememberDesiredFormatLocale", "known_paid")) {
            assertFalse(forbidden in branch, "relay-only branch formats a price via $forbidden")
        }
    }

    @Test
    fun a_waiting_reply_suppresses_the_you_joined_outcome() {
        val rule =
            Class
                .forName("co.electriccoin.zcash.ui.screen.chat.view.GroupDetailViewKt")
                .getDeclaredMethod("joinOutcomeAfterRecordRead", Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
        assertEquals(null, rule.invoke(null, true, true), "a waiting reply must clear successful Join feedback")
        assertEquals(false, rule.invoke(null, false, true), "a waiting reply must retain failure feedback")
        assertTrue(
            "joinOutcome=shownJoinOutcome" in compact(screen),
            "the view must receive normalized success while retaining failed Join feedback",
        )
        assertTrue(
            (
                "LaunchedEffect(joinRecord,joinOutcome){" +
                    "joinOutcome=joinOutcomeAfterRecordRead(joinOutcome,joinRecord!=null)}"
            ) in compact(screen),
            "a returned waiting record must clear the joined outcome and enable Join again",
        )
    }

    @Test
    fun a_confirmed_waiting_record_write_invalidates_the_open_screens_record_read() {
        val src = compact(screen)
        assertTrue(
            "groupStoreVersion.collectAsStateWithLifecycle()" in src,
            "screen does not collect preference changes",
        )
        val keys =
            Regex("""valjoinRecord=remember\(([^)]*)\)""").find(src)?.groupValues?.get(1)
        assertNotNull(keys, "fixture: remembered Join record")
        assertTrue("groupStoreVersions[groupId]" in keys, "a returned waiting record cannot invalidate the remembered read")
        val prefs = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt")
        val start = prefs.indexOf("override fun setGroupInviteNeedsJoin(")
        val end = prefs.indexOf("\n    /**", start)
        assertTrue(start >= 0 && end > start, "fixture: waiting record store implementation")
        val setter = compact(prefs.substring(start, end))
        assertTrue(
            "editor.commit()" in setter && "markGroupStoreChanged(groupId)" in setter,
            "confirmed waiting-record writes do not notify the open screen",
        )
        assertTrue("if(it)" in setter, "a failed waiting-record commit must not notify the open screen")
    }

    @Test
    fun a_relay_only_tap_never_shows_you_joined_while_the_relay_attempt_is_pending() {
        val src = compact(screen)
        assertTrue(
            "valshownJoinOutcome=joinOutcomeAfterRecordRead(joinOutcome,awaitingJoin)" in src,
            "a returned waiting record can render You joined until a later effect runs",
        )
        assertTrue("joinOutcome=shownJoinOutcome" in src, "view does not receive the synchronously normalized outcome")
        assertTrue(
            "joinGroupInvite(groupId,joinRecord)" in src,
            "the screen does not bind Join to the exact record whose price it displayed",
        )
        assertTrue("if(joinOutcome!=true){" in src, "rapid callbacks must read the live Join outcome")
        assertFalse("if(shownJoinOutcome!=true){" in src, "a captured rendered outcome can lose rapid-tap success")
        assertTrue(
            "joinOutcome=if(released&&joinReplyIsRelayOnly)nullelsereleased" in src,
            "an accepted relay-only tap claims You joined before its reply has gone out",
        )
    }

    private companion object {
        const val BANNER = "group_invite_join_relay_only"
        const val NOTICE = "group_join_reply_relay_only_not_sent"
    }
}
