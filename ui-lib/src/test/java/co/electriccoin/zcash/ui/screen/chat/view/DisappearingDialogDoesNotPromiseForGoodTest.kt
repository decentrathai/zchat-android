package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1160 — the disappearing-messages dialog promises more than the app keeps.
 *
 * disappearing_dialog_permanent says expired messages are "deleted from this phone for good" ("se borran de este
 * teléfono para siempre"). The register says otherwise: after a restore from the recovery phrase, on-chain messages
 * that a timer had hidden can show again (KI-27, ZC-024), a notification can keep showing a disappearing message,
 * and a preview of a message's text is kept in ZChat's storage (KI-80). app-texts rule 2 (Loss): never "gone for
 * good" when a copy is kept or can come back.
 *
 * Wanted, in English and Spanish: no absolute wording ("for good", "forever", "permanently" / "para siempre",
 * "permanente", "definitivamente"), and the sentence says copies can remain: after a restore, and in a notification
 * or a preview. Matched by word stems, not by an exact sentence, so the wording stays free.
 */
class DisappearingDialogDoesNotPromiseForGoodTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun `English does not say disappearing messages are deleted for good`() {
        val text = permanent(en, "values")
        val absolute = ABSOLUTE_EN.filter { it.containsMatchIn(text) }.map { it.pattern }
        assertTrue(absolute.isEmpty(), "English $KEY promises permanent deletion ($absolute): \"$text\"")
    }

    @Test
    fun `Spanish does not say disappearing messages are deleted para siempre`() {
        val text = permanent(es, "values-es")
        val absolute = ABSOLUTE_ES.filter { it.containsMatchIn(text) }.map { it.pattern }
        assertTrue(absolute.isEmpty(), "Spanish $KEY promises permanent deletion ($absolute): \"$text\"")
    }

    @Test
    fun `English says copies can remain after a restore and in notifications or previews`() {
        val text = permanent(en, "values")
        assertTrue(RESTORE_EN.containsMatchIn(text), "English $KEY must say a restore can bring messages back: \"$text\"")
        assertTrue(
            NOTIFICATION_EN.containsMatchIn(text),
            "English $KEY must say a notification or preview can still show them: \"$text\"",
        )
    }

    @Test
    fun `Spanish says copies can remain after a restore and in notifications or previews`() {
        val text = permanent(es, "values-es")
        assertTrue(RESTORE_ES.containsMatchIn(text), "Spanish $KEY must say a restore can bring messages back: \"$text\"")
        assertTrue(
            NOTIFICATION_ES.containsMatchIn(text),
            "Spanish $KEY must say a notification or preview can still show them: \"$text\"",
        )
    }

    private fun permanent(
        strings: Map<String, String>,
        dir: String
    ): String = strings[KEY] ?: fail("$dir has no $KEY")

    private companion object {
        const val KEY = "disappearing_dialog_permanent"

        private val I = setOf(RegexOption.IGNORE_CASE)

        val ABSOLUTE_EN =
            listOf(
                Regex("""\bfor good\b""", I),
                Regex("""\bfor ?ever\b""", I),
                Regex("""\bpermanent(ly)?\b""", I),
                Regex("""\birreversibl""", I),
                Regex("""\b(can ?not|can't|never) be (recovered|restored|undone)""", I),
                Regex("""\bgone\b""", I),
            )
        val ABSOLUTE_ES =
            listOf(
                Regex("""para siempre""", I),
                Regex("""permanente""", I),
                Regex("""definitiv""", I),
                Regex("""irreversibl""", I),
                Regex("""no se (pueden|podr[aá]n) (recuperar|restaurar)""", I),
            )

        val RESTORE_EN = Regex("""\brestor""", I)
        val NOTIFICATION_EN = Regex("""\b(notification|preview)""", I)
        val RESTORE_ES = Regex("""restaur""", I)
        val NOTIFICATION_ES = Regex("""(notificaci[oó]n|vista previa)""", I)
    }
}
