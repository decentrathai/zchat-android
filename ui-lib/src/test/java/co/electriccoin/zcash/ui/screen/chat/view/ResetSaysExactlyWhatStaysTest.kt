package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ZC-090-R (register ZC-090, high; RESET-LEFTOVERS-SPEC.md revision 2d, sections 4.5, 5.1 and 6.3, the review's A1):
 * what stays after a Reset without "keep" has a paragraph of its own, `delete_wallet_stays_unchecked`, under the
 * warning, and the same text in the Full Wallet Reset dialog (`identity_reset_stays_note`). It names each kind of thing
 * that the start pass's kept set holds, and counts none of them: a count goes wrong with every file a later batch keeps.
 * The paragraph and the kept-set constant `ResetWipe.KEPT_WITHOUT_KEEP` cannot drift apart: each entry's `told` is a
 * word of the paragraph, a sentence of its own, or "no wallet content" with a reason.
 *
 * The constant is read by reflection: this class compiles before the fix.
 */
class ResetSaysExactlyWhatStaysTest {
    @Test
    fun `every kept entry is told - by a word of the paragraph in both languages, its own sentence, or no wallet content`() {
        val en = strings(DW, "values").getValue(STAYS)
        val es = strings(DW, "values-es").getValue(STAYS)
        val dw = strings(DW, "values")
        val screen = code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/deletewallet/ResetZashiVM.kt")
        for ((path, told) in kept()) {
            assertTrue("the kept entry $path has no told", told.isNotBlank())
            when {
                told.startsWith(OWN_SENTENCE) -> {
                    val key = told.removePrefix(OWN_SENTENCE).trim()
                    assertTrue("$path: its own sentence $key is not a string", key in dw)
                    assertTrue("$path: its own sentence $key is not shown on the Reset screen", "R.string.$key" in screen)
                }
                told.startsWith(NO_CONTENT) ->
                    assertTrue(
                        "$path: 'no wallet content' without a reason",
                        told.length > NO_CONTENT.length + 2,
                    )
                else -> {
                    val spanish = WORDS[told] ?: throw AssertionError("$path: its told '$told' is not a word of the paragraph's table")
                    assertTrue("$path: '$told' is not in $STAYS (EN)", told in en)
                    assertTrue("$path: «$spanish» is not in $STAYS (ES)", spanish in es)
                }
            }
        }
    }

    @Test
    fun `nothing is told that is not kept - every word of the paragraph is the told of an entry, or a named residual`() {
        val told = kept().values.toSet()
        for (word in WORDS.keys) {
            assertTrue("the paragraph says '$word' and no kept entry tells it", word in told || word in OUTSIDE_THE_KEPT_SET)
        }
    }

    @Test
    fun `no count - neither paragraph nor the warning counts what stays`() {
        val texts =
            listOf(
                strings(DW, "values").getValue(STAYS),
                strings(DW, "values-es").getValue(STAYS),
                strings(DW, "values").getValue(WARNING),
                strings(DW, "values-es").getValue(WARNING),
                strings(IDENTITY, "values").getValue(STAYS_NOTE),
                strings(IDENTITY, "values-es").getValue(STAYS_NOTE),
            )
        val counts =
            listOf(
                Regex("""\b(two|three|four|five)\s+(things|kinds)\b""", RegexOption.IGNORE_CASE),
                Regex("""\b(dos|tres|cuatro|cinco)\s+(cosas|tipos)\b""", RegexOption.IGNORE_CASE),
                Regex("""\b(Two|Three|Four|Dos|Tres|Cuatro)\b"""),
                Regex("""\d"""),
            )
        for (text in texts) {
            for (count in counts) assertFalse("a count of what stays: ${count.find(text)?.value} in: $text", count.containsMatchIn(text))
        }
    }

    @Test
    fun `the relay set is not kept - the counter floor and the TEX file are`() {
        val paths = kept().keys
        assertFalse("zchat_relay_set is in the kept set", paths.any { "zchat_relay_set" in it })
        assertTrue("zchat_ratchet_counter_floor is not kept", paths.any { "zchat_ratchet_counter_floor" in it })
        assertTrue("zchat_tex_recovery is not kept", paths.any { "zchat_tex_recovery" in it })
    }

    @Test
    fun `both screens - the Reset screen shows it only unticked, the Full Wallet Reset dialog the same text`() {
        val stays =
            runCatching {
                val holder = Class.forName("co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiVMKt")
                val function = holder.declaredMethods.first { it.name == "keepDataStays" }.apply { isAccessible = true }
                listOf(function.invoke(null, false), function.invoke(null, true))
            }.getOrElse { throw AssertionError("the Reset screen has no keepDataStays(isChecked)") }
        val id = runCatching { R.string::class.java.getField(STAYS).getInt(null) }.getOrNull()
        assertTrue("there is no string $STAYS", id != null)
        assertEquals("unticked: the paragraph", id, stays[0])
        assertEquals("ticked: no paragraph", null, stays[1])
        val view = code("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/changeidentity/ChangeIdentityView.kt")
        val fullBranch = view.substringAfter("R.string.identity_reset_warning", "").substringBefore("} else {")
        assertTrue("the Full Wallet Reset dialog does not show $STAYS_NOTE", "R.string.$STAYS_NOTE" in fullBranch)
        for (qualifier in listOf("values", "values-es")) {
            assertEquals(
                "$qualifier: the two paragraphs differ",
                strings(DW, qualifier).getValue(STAYS),
                strings(IDENTITY, qualifier).getValue(STAYS_NOTE),
            )
        }
    }

    @Test
    fun `parity - both keys in English and Spanish, different, no placeholder, read only with this recovery phrase`() {
        for ((folder, key) in listOf(DW to STAYS, IDENTITY to STAYS_NOTE)) {
            val en = strings(folder, "values")[key]
            val es = strings(folder, "values-es")[key]
            assertTrue("$key in EN and ES", en != null && es != null)
            assertNotEquals("$key: the Spanish is the English", en, es)
            for (text in listOf(en!!, es!!)) assertFalse("$key has a placeholder", Regex("""%\d*\$?[sd]|\{\d}""").containsMatchIn(text))
            assertTrue("$key (EN)", "can only be read with this recovery phrase" in en)
            assertTrue("$key (ES)", "solo puede leerse con esta frase de recuperación" in es)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** ResetWipe.KEPT_WITHOUT_KEEP as path to told, by reflection. */
    private fun kept(): Map<String, String> {
        val clazz =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.util.ResetWipe") }.getOrNull()
                ?: throw AssertionError("the object ResetWipe does not exist")
        val list = clazz.getMethod("getKEPT_WITHOUT_KEEP").invoke(clazz.getField("INSTANCE").get(null)) as List<*>
        return list.associate { entry ->
            fun read(name: String) = entry!!.javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(entry) as String
            read("path") to read("told")
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private fun code(relative: String): String = File(repoRoot(), relative).takeIf { it.isFile }?.readText().orEmpty()

    private fun strings(
        folder: String,
        qualifier: String,
    ): Map<String, String> =
        File(repoRoot(), "ui-lib/src/main/res/ui/$folder/$qualifier")
            .listFiles()
            .orEmpty()
            .filter { it.extension == "xml" }
            .flatMap { xml ->
                val nodes =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(xml)
                        .getElementsByTagName("string")
                (0 until nodes.length).map {
                    val node = nodes.item(it)
                    node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
                }
            }.toMap()

    private companion object {
        const val DW = "delete_wallet"
        const val IDENTITY = "common"
        const val STAYS = "delete_wallet_stays_unchecked"
        const val STAYS_NOTE = "identity_reset_stays_note"
        const val WARNING = "delete_wallet_checkbox_warning_unchecked"
        const val OWN_SENTENCE = "own sentence:"
        const val NO_CONTENT = "no wallet content:"

        /** The paragraph's words, English to Spanish (spec 6.3). */
        val WORDS =
            mapOf(
                "other recovery phrases" to "otras frases de recuperación",
                "record of payments" to "registro propio de ZCHAT de los pagos",
                "message counters" to "contadores de mensajes",
                "settings" to "ajustes",
                "crash reports" to "informes de fallos",
                "saved-contacts or notes file" to "archivo de contactos guardados o de notas",
            )

        /**
         * Words of the paragraph that no entry of the kept set tells, because what they name is not a file the pass
         * keeps: the crash reports are in the external folder, which no Reset touches (spec row R22); the
         * saved-contacts or notes file of this wallet stays only when its key was not cached (residual R-R1).
         */
        val OUTSIDE_THE_KEPT_SET = setOf("crash reports", "saved-contacts or notes file")
    }
}
