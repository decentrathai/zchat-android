package co.electriccoin.zcash.ui.screen.moveoldbalance

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RED pin for the "Move old balance" copy (sweep spec §1.4, owner decisions 2026-09-10).
 *
 * Value received before NU6.3 sits in the sealed Orchard pool, which can only pay the wallet's own
 * address. 2.13.0 adds one way out: a single, public transaction that moves the whole Orchard balance to
 * the wallet's own Ironwood address. The user must be told, before they agree, exactly what becomes
 * public (the amount AND the time), that an exact amount can be linked, that addresses and messages stay
 * private, that Tor Protection off also shows the server their IP address, and what the network fee is.
 * Today none of this copy exists, so the sheet cannot be built honestly.
 *
 * The copy lives in its own resource file, in English and Spanish. Every `%n$s` / `%n$d` placeholder a key
 * uses in English must be used by its Spanish translation too, or the formatter drops a figure. Owner
 * decision 2 resets handshake consent after a sweep, so the handshake line may no longer promise that
 * waiting handshakes go out automatically.
 *
 * Reads the resource files directly, as InsufficientFundsCopyTest does.
 */
class MoveOldBalanceCopyTest {
    @Test
    fun every_move_old_balance_key_exists_in_english_and_spanish() {
        val english = strings("values")
        val spanish = strings("values-es")

        val missingEnglish = KEYS.filterNot { it in english }
        val missingSpanish = KEYS.filterNot { it in spanish }
        assertTrue(missingEnglish.isEmpty(), "values/$FILE_NAME is missing $missingEnglish")
        assertTrue(missingSpanish.isEmpty(), "values-es/$FILE_NAME is missing $missingSpanish")

        KEYS.forEach { key ->
            assertEquals(
                placeholders(english.getValue(key)),
                placeholders(spanish.getValue(key)),
                "$key: the Spanish text must use the same placeholders as the English",
            )
        }
    }

    /**
     * F9 (fix-all 2.13.0, train T1): English and Spanish define exactly the same keys, with the same placeholders, and
     * neither promises a later or planned private way (the Round 8 D10 line is gone from both).
     */
    @Test
    fun english_and_spanish_define_the_same_keys_and_promise_no_later_way() {
        val english = strings("values")
        val spanish = strings("values-es")

        assertEquals(english.keys.sorted(), spanish.keys.sorted(), "values/ and values-es/$FILE_NAME differ in keys")
        english.keys.forEach { key ->
            assertEquals(
                placeholders(english.getValue(key)),
                placeholders(spanish.getValue(key)),
                "$key: the Spanish text must use the same placeholders as the English",
            )
        }
        (english + spanish.mapKeys { "es:${it.key}" }).forEach { (key, text) ->
            assertFalse(
                Regex("planned|later (ZChat )?version|prevista|versión posterior", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text),
                "$key promises a later or planned private way: \"$text\"",
            )
        }
    }

    @Test
    fun the_consent_copy_tells_the_whole_privacy_story() {
        val english = strings("values")

        english.requireContains("consent_public", "publicly visible", "time", "blockchain")
        english.requireContains("consent_linkable", "matched")
        english.requireContains("consent_tor", "IP address")
        english.requireContains("consent_private", "addresses", "contents")
        assertFalse(
            english.text("consent_private").contains("Only the amount", ignoreCase = true),
            "the amount is not the only thing revealed (the time is too): ${english.text("consent_private")}",
        )
        assertFalse(
            english.text("too_small_body").contains("counted", ignoreCase = true),
            "too_small_body must not talk about what the balance counts: ${english.text("too_small_body")}",
        )
        english.requireContains("consent_dust", "%1\$s")
        assertFalse(
            english.text("consent_dust").contains("0.0000"),
            "the dust limit is a formatted figure, never a literal: ${english.text("consent_dust")}",
        )
        assertFalse(
            english.text("consent_handshakes").contains("automatically", ignoreCase = true),
            "a successful move resets handshake consent (owner decision 2), so waiting handshakes ask " +
                "again instead of going out automatically: ${english.text("consent_handshakes")}",
        )
    }

    @Test
    fun the_spanish_consent_copy_is_translated_not_copied() {
        val spanish = strings("values-es")

        spanish.requireContains("consent_public", "cadena de bloques")
        spanish.requireContains("consent_tor", "dirección IP")
        spanish.requireContains("consent_one_tx", "una sola transacción")
        spanish.requireContains("consent_fee", "Comisión")
    }

    private fun Map<String, String>.text(suffix: String): String =
        this["$PREFIX$suffix"] ?: fail("$FILE_NAME no longer defines $PREFIX$suffix")

    private fun Map<String, String>.requireContains(
        suffix: String,
        vararg needles: String
    ) {
        val text = text(suffix)
        needles.forEach { needle ->
            assertTrue(text.contains(needle, ignoreCase = true), "$PREFIX$suffix must mention \"$needle\": \"$text\"")
        }
    }

    private fun placeholders(text: String): Set<String> =
        Regex("""%(\d+)\$([sd])""").findAll(text).map { it.value }.toSet()

    private fun strings(qualifier: String): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/send_confirmation/$qualifier/$FILE_NAME")
        assertTrue(file.isFile, "missing ${file.path}: the Move old balance copy does not exist yet")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private companion object {
        const val FILE_NAME = "move_old_balance_strings.xml"
        const val PREFIX = "move_old_balance_"

        /** Every key of spec §1.4, without the prefix. */
        val SUFFIXES =
            listOf(
                "action",
                "details",
                "auth_use_case",
                "card_title",
                "card_body_available",
                "card_body_wait",
                "card_body_keystone",
                "card_body_too_small",
                "preparing",
                "consent_title",
                "consent_intro",
                "consent_public",
                "consent_linkable",
                "consent_private",
                "consent_tor",
                "consent_handshakes",
                "consent_one_tx",
                "consent_fee",
                "consent_dust",
                "consent_continue",
                "not_now",
                "review_title",
                "review_moving",
                "review_fee",
                "review_arrives",
                "review_to",
                "review_to_value",
                "review_one_tx",
                "review_public_note",
                "review_confirm",
                "review_back",
                "sending_title",
                "sending_body",
                "success_title",
                "success_body",
                "view_tx",
                "done",
                "failure_title",
                "failure_body",
                "grpc_title",
                "grpc_body",
                "too_small_title",
                "too_small_body",
                "wait_sync_title",
                "wait_sync_body",
                "pending_title",
                "pending_body",
                "keystone_title",
                "keystone_body",
                "nothing_title",
                "nothing_body",
                "error_title",
                "error_body",
                "chat_move_line",
                "chat_too_small",
            )

        val KEYS = SUFFIXES.map { PREFIX + it }

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/send_confirmation").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
