package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 8 D4 — the text of a stranger's group invite (DECISIONS_NIGHT §1) and of the Keystone explanation (round 7c
 * C5) exists in English and Spanish, with the reviewed wording and the same placeholders, and promises nothing the
 * code does not do: before Join nothing was joined for the user and nothing was paid; Join sends one reply that can
 * cost money; with a Keystone account nothing was sent or paid.
 *
 * The new resources and the Kotlin constant are read by name, so this file compiles before they exist.
 */
class GroupInviteRequestCopyTest {
    @Test
    fun `the group invite request strings exist in English and Spanish with the reviewed wording`() {
        val en = strings("values", INVITE_FILE)
        val es = strings("values-es", INVITE_FILE)
        for ((key, expected) in ENGLISH) {
            assertEquals(expected, en[key] ?: fail("values/$INVITE_FILE has no $key"), "English $key")
        }
        for ((key, expected) in SPANISH) {
            val spanish = es[key] ?: fail("values-es/$INVITE_FILE has no $key")
            assertEquals(expected, spanish, "Spanish $key")
            assertNotEquals(en[key], spanish, "$key is the English text in the Spanish file")
        }
    }

    @Test
    fun `English and Spanish use the same placeholders`() {
        val en = strings("values", INVITE_FILE) + strings("values", SEND_FILE)
        val es = strings("values-es", INVITE_FILE) + strings("values-es", SEND_FILE)
        for (key in ENGLISH.keys + KEYSTONE_KEY) {
            val enText = en[key] ?: fail("values has no $key")
            val esText = es[key] ?: fail("values-es has no $key")
            assertEquals(placeholders(enText), placeholders(esText), "placeholders of $key")
        }
        assertEquals(listOf("%1\$s", "%2\$s", "%3\$s"), placeholders(en.getValue(BANNER)), "the banner's placeholders")
    }

    @Test
    fun `the banner says nothing was joined or paid, and that Join can cost money`() {
        val en = strings("values", INVITE_FILE).getValue(BANNER).lowercase()
        val es = strings("values-es", INVITE_FILE).getValue(BANNER).lowercase()
        assertTrue("nothing has been paid" in en, en)
        assertTrue("hasn't joined for you" in en, en)
        assertTrue("at least %3\$s zec" in en, en)
        assertTrue("no se ha pagado nada" in es, es)
        assertTrue("no se ha unido por ti" in es, es)
        assertTrue("al menos %3\$s zec" in es, es)
    }

    @Test
    fun `the Keystone explanation is the exact English resource, is translated, and reaches the screen unchanged`() {
        val constant =
            ChatSendCopy::class.java.getField("KEYSTONE_AUTOMATIC_SETUP").get(null) as? String
                ?: fail("ChatSendCopy.KEYSTONE_AUTOMATIC_SETUP is missing")
        val en = strings("values", SEND_FILE)
        val es = strings("values-es", SEND_FILE)
        assertEquals(KEYSTONE_EN, constant, "the constant")
        assertEquals(constant, en[KEYSTONE_KEY], "the English resource must be the exact text the app reports")
        assertEquals(KEYSTONE_ES, es[KEYSTONE_KEY], "the Spanish resource")
        val id = R.string::class.java.getField(KEYSTONE_KEY).getInt(null)
        assertEquals(id, ChatSendCopy.localizedResource(constant), "a Spanish phone must find the translation")
        assertEquals(constant, constant.toZchatUserMessage("fallback"), "the toast path must not replace the text")
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sd]""")
            .findAll(text)
            .map { it.value }
            .toList()
            .sorted()

    private fun strings(
        dir: String,
        file: String,
    ): Map<String, String> {
        val f = File(resRoot, "$dir/$file")
        if (!f.isFile) fail("missing ${f.path}")
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText())
            .associate { m ->
                m.groupValues[1] to
                    m.groupValues[2]
                        .replace("\\'", "'")
                        .replace("\\\"", "\"")
                        .replace("&amp;", "&")
                        .replace(Regex("\\s+"), " ")
                        .trim()
            }
    }

    private companion object {
        const val INVITE_FILE = "group_invite_request_strings.xml"
        const val SEND_FILE = "chat_send_strings.xml"
        const val BANNER = "group_invite_request_banner"
        const val KEYSTONE_KEY = "chat_send_keystone_automatic_setup"

        val ENGLISH =
            mapOf(
                BANNER to
                    "%1\$s invited you to “%2\$s”. You haven't chatted with this address before, so ZChat hasn't " +
                    "joined for you and nothing has been paid. Join sends your “I joined” reply to the group's " +
                    "creator; it can cost at least %3\$s ZEC, including network fees.",
                "group_invite_request_join" to "Join",
                "group_invite_request_ignore" to "Ignore",
                "group_invite_request_list_subtitle" to "Invitation: tap to join or ignore",
            )

        val SPANISH =
            mapOf(
                BANNER to
                    "%1\$s te invitó a «%2\$s». Nunca has chateado con esta dirección, así que ZChat no se ha unido " +
                    "por ti y no se ha pagado nada. Tocar Unirme envía tu respuesta «Me uní» al creador del grupo; puede " +
                    "costar al menos %3\$s ZEC, comisiones de red incluidas.",
                "group_invite_request_join" to "Unirme",
                "group_invite_request_ignore" to "Ignorar",
                "group_invite_request_list_subtitle" to "Invitación: toca para unirte o ignorarla",
            )

        // K0 (fix-all 2.13.0): the old text asked for a switch no screen offered; it now names where to switch.
        const val KEYSTONE_EN =
            "A Keystone account is selected. ZChat can't sign chat setup payments with it in this version, so " +
                "nothing was sent or paid. Tap the account name in the Wallet tab and choose your ZChat account to " +
                "finish setting up this chat."
        const val KEYSTONE_ES =
            "Hay una cuenta Keystone seleccionada. En esta versión ZChat no puede firmar con ella los pagos de " +
                "configuración del chat, así que no se envió ni se pagó nada. Toca el nombre de la cuenta en la " +
                "pestaña Billetera y elige tu cuenta de ZChat para terminar de configurar este chat."

        val resRoot: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/res/ui/common") }
                .firstOrNull { it.resolve("values").isDirectory }
                ?: fail("could not locate ui-lib/src/main/res/ui/common from ${start.path}")
        }
    }
}
