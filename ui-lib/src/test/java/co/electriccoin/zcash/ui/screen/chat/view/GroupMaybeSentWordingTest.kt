package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C4 (round 7 adversarial review of B1, item 3) — the words of the group "may have been sent" banner.
 *
 * `group_send_maybe_sent` reads "%d messages may have been sent … you won't be charged twice":
 *
 * - it is shown for an invite too, so an invite is called a message;
 * - its number is a count of members, not messages;
 * - "you won't be charged twice" is a promise ZChat cannot keep: the SDK may still broadcast the stored
 *   transaction, and nothing stops the user typing the same message again.
 *
 * Wanted: one plural for a message (counting members), one for an invite that never says "message", a line for
 * the members nothing was sent to, no "charged twice" promise, and Spanish that matches the English placeholders.
 */
class GroupMaybeSentWordingTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val resRoot: File get() = File(repoRoot, "ui-lib/src/main/res/ui")

    private val bannerPlurals =
        listOf(
            MESSAGE,
            INVITE,
            MESSAGE_NOT_SENT,
            INVITE_NOT_SENT,
        )

    @Test
    fun the_banner_has_separate_message_invite_and_not_sent_wording_in_english_and_spanish() {
        val en = allPlurals("values")
        val es = allPlurals("values-es")
        for (name in bannerPlurals) {
            val english = assertNotNull(en[name], "values has no plural $name")
            val spanish = assertNotNull(es[name], "values-es has no plural $name")
            assertTrue(setOf("one", "other").all { it in english }, "$name: English needs one and other: $english")
            assertTrue(setOf("one", "many", "other").all { it in spanish }, "$name: Spanish needs one, many and other: $spanish")
            for ((quantity, text) in english.entries + spanish.entries) {
                assertTrue("%1\$d" in text, "$name/$quantity must show the member count: $text")
            }
            assertFalse(english.values.any { it == spanish["other"] }, "$name is not translated")
        }
    }

    @Test
    fun the_invite_wording_never_calls_an_invite_a_message() {
        val en = allPlurals("values")
        val es = allPlurals("values-es")
        for (name in listOf(INVITE, INVITE_NOT_SENT)) {
            en[name].orEmpty().forEach { (q, text) ->
                assertFalse(Regex("(?i)\\bmessages?\\b").containsMatchIn(text), "$name/$q calls the invite a message: $text")
            }
            es[name].orEmpty().forEach { (q, text) ->
                assertFalse(Regex("(?i)\\bmensajes?\\b").containsMatchIn(text), "$name/$q calls the invite a message: $text")
            }
        }
    }

    @Test
    fun the_banner_makes_no_promise_about_being_charged_twice() {
        val en = allPlurals("values")
        val es = allPlurals("values-es")
        for (name in bannerPlurals) {
            en[name].orEmpty().forEach { (q, text) ->
                assertFalse("charged" in text.lowercase(), "$name/$q promises something about charges: $text")
            }
            es[name].orEmpty().forEach { (q, text) ->
                assertFalse("cobrar" in text.lowercase(), "$name/$q promises something about charges: $text")
            }
        }
        for (dir in listOf("values", "values-es")) {
            val old = allPlurals(dir)["group_send_maybe_sent"]
            old?.forEach { (q, text) ->
                assertFalse(
                    "charged" in text.lowercase() || "cobrar" in text.lowercase(),
                    "$dir/group_send_maybe_sent/$q still promises the user won't be charged twice: $text",
                )
            }
        }
    }

    @Test
    fun the_group_screen_words_the_banner_by_kind_and_names_the_members_nothing_was_sent_to() {
        // Round 8 D8: the banner text is built in GroupSendBanner.kt (groupSendBannerText), which GroupDetailView calls.
        val view =
            File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupDetailView.kt").readText() +
                File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupSendBanner.kt").readText()
        for (name in bannerPlurals) {
            assertTrue("R.plurals.$name" in view, "GroupDetailView does not show $name")
        }
        assertFalse("R.plurals.group_send_maybe_sent," in view, "the one-size banner is still shown")
    }

    // ---- helpers ----

    private fun allPlurals(dir: String): Map<String, Map<String, String>> =
        resRoot
            .listFiles()
            .orEmpty()
            .flatMap { module ->
                File(module, dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList()
            }.fold(emptyMap()) { acc, f -> acc + parsePlurals(f.readText()) }

    private fun parsePlurals(xml: String): Map<String, Map<String, String>> =
        Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m ->
                m.groupValues[1] to
                    Regex("""<item quantity="([^"]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(m.groupValues[2])
                        .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
            }

    private companion object {
        const val MESSAGE = "group_send_maybe_sent_message"
        const val INVITE = "group_send_maybe_sent_invite"
        const val MESSAGE_NOT_SENT = "group_send_maybe_sent_message_not_sent"
        const val INVITE_NOT_SENT = "group_send_maybe_sent_invite_not_sent"
    }
}
