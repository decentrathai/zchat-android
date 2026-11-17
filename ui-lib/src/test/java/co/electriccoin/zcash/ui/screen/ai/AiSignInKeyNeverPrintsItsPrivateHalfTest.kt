package co.electriccoin.zcash.ui.screen.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * ZC-956 (review of AI-KEY-D, 13 Oct, finding 2): the AI sign-in key is handed out as a type of its own that never
 * prints its private half.
 *
 * `AiSignInKey.derive` returned the chat keys' pair, a `data class`: its generated text holds the private half, so
 * one later log line, string template or failed comparison on the pair would have put the key that owns an AI
 * account and its paid credit into a log or a report. The pair's own type prints a fixed text, compares by identity
 * and cannot be copied or taken apart, and no file of the app puts it or one of its halves into a text or a log.
 */
class AiSignInKeyNeverPrintsItsPrivateHalfTest {
    private val seed = AiSignInApi.bytes(SEED_BYTES)

    @Test
    fun the_text_of_a_derived_key_holds_no_key_material() {
        val key = AiSignInApi.deriveRaw(seed)
        val halves = AiSignInApi.derive(seed)
        val number = AiSignInApi.privateNumber(halves.privateKey).toString(HEX)
        val texts =
            mapOf(
                "toString" to key.toString(),
                "a string template" to "$key",
                "a list's text" to listOf(key).toString(),
            )

        texts.forEach { (where, text) ->
            assertNoPieceOf("the private half", halves.privateKey, text, where)
            assertNoPieceOf("the private number", number, text.lowercase(), where)
            assertNoPieceOf("the public half", halves.publicKey, text, where)
        }
    }

    @Test
    fun the_text_of_a_derived_key_is_the_same_for_every_key() {
        val other = AiSignInApi.deriveRaw(AiSignInApi.bytes(SEED_BYTES).also { it[0] = 1 })

        // No assertEquals: its failure message would print both texts.
        assertTrue("two keys print two texts", AiSignInApi.deriveRaw(seed).toString() == other.toString())
    }

    @Test
    fun the_derived_key_is_a_type_of_its_own_and_no_data_class() {
        val type = AiSignInApi.deriveRaw(seed).javaClass

        assertEquals("derive returns ${type.name}", "co.electriccoin.zcash.ui.screen.ai.$TYPE", type.name)
        val generated =
            type.methods
                .map { it.name }
                .filter { it == "copy" || it.startsWith("copy$") || it.startsWith("component") }
        assertEquals("the type offers a data class's functions", emptyList<String>(), generated)
        assertEquals("equals compares the halves", Any::class.java, type.getMethod("equals", Any::class.java).declaringClass)
        assertEquals("hashCode mixes the halves", Any::class.java, type.getMethod("hashCode").declaringClass)
        assertTrue("the type can be extended, and a subclass can print the halves", Modifier.isFinal(type.modifiers))
        assertEquals(
            "the type's own text is not its own function",
            type,
            type.getMethod("toString").declaringClass,
        )
    }

    @Test
    fun two_derivations_of_one_seed_are_not_equal_objects_and_have_equal_halves() {
        val one = AiSignInApi.deriveRaw(seed)
        val two = AiSignInApi.deriveRaw(seed.copyOf())

        assertFalse("the type compares its halves in equals", one == two)
        // No assertEquals: the halves are a data class, and its failure message would print both private halves.
        assertTrue("two derivations of one seed have different halves", AiSignInApi.derive(seed) == AiSignInApi.derive(seed.copyOf()))
    }

    // ------------------------------------------------------------------------------------------ the source

    @Test
    fun no_file_of_the_app_puts_the_key_into_a_text_or_a_log() {
        val hits =
            AiSignInApi.productSources().flatMap { file ->
                leaks(file.readText()).map { "${file.relativeTo(AiSignInApi.uiLib)}: $it" }
            }

        assertEquals("a file prints or logs the AI sign-in key", emptyList<String>(), hits)
    }

    @Test
    fun no_file_of_the_app_hands_the_key_out_as_the_chat_keys_pair() {
        val source = AiSignInApi.read(AiSignInApi.mainAiFile("AiSignInKey.kt"))

        assertFalse("AiSignInKey.kt names the chat keys' pair", "E2EKeyPair" in source)
        val declared = Regex("""fun derive\([^)]*\): $TYPE\b""").containsMatchIn(source)
        assertTrue("derive does not declare the type", declared)
        val converted =
            AiSignInApi.productSources().filter { file ->
                val text = file.readText()
                TYPE in text && Regex("""E2EKeyPair\([^)]*\b(publicKey|privateKey)\b""").containsMatchIn(text)
            }
        assertEquals("a file turns the key into the chat keys' pair", emptyList<File>(), converted)
    }

    /** The scan itself is proven on small pieces of source: it finds each way of printing, and no honest use. */
    @Test
    fun control_the_scan_finds_every_way_of_printing_and_no_honest_use() {
        val d = "$"
        val leaking =
            listOf(
                "val key = AiSignInKey.derive(seed)\nLog.d(TAG, \"made ${d}key\")",
                "val key = AiSignInKey.derive(seed)\nval text = \"key $d{key.privateKey}\"",
                "val key: $TYPE = AiSignInKey.derive(seed)\nTwig.debug { key.toString() }",
                "fun send(pair: $TYPE) {\n    println(pair)\n}",
                "class Holder(private val signInKey: $TYPE) {\n    fun text() = \"${d}signInKey\"\n}",
                "Log.i(TAG, AiSignInKey.derive(seed).privateKey)",
                "val text = \"$d{AiSignInKey.derive(seed)}\"",
                "val key = AiSignInKey.derive(seed)\nval text = key.toString()",
                "val key = AiSignInKey.derive(seed)\nerror(\"no account for \" + key)",
                "val key = AiSignInKey.derive(seed)\nval secret = key.privateKey\nLog.d(TAG, secret)",
                "val key = AiSignInKey.derive(seed)\nval secret = key.privateKey\nval text = \"${d}secret\"",
            )
        val honest =
            listOf(
                "val key = AiSignInKey.derive(seed)\nval id = AiSignInKey.keyId(key.publicKey)\n" +
                    "Log.d(TAG, \"signed in\")",
                "val key = AiSignInKey.derive(seed)\nAiSignInProof.sign(key.privateKey, purpose, nonce, exp, id)",
                "fun send(pair: $TYPE): String = AiSignInProof.sign(pair.privateKey, purpose, nonce, exp, id)",
                "val keyboard = \"${d}keyboardHeight\"\nLog.d(TAG, \"key $d{event.key}\")",
            )

        leaking.forEach { piece -> assertTrue("not found:\n$piece", leaks(piece).isNotEmpty()) }
        honest.forEach { piece -> assertEquals("found in:\n$piece", emptyList<String>(), leaks(piece)) }
    }

    /**
     * The lines of [source] that put the AI sign-in key, or its private half, into a text or a log. A name counts as
     * the key when it is declared with the key's type or set from `AiSignInKey.derive(`, and as the private half
     * when it is set from such a name's `.privateKey`. A line leaks when it names one of them (or derives in place)
     * inside a string template, in a log, print or `error(` call, next to a `+ "` or `" +` join, or calls its
     * `toString()`.
     */
    private fun leaks(source: String): List<String> {
        val keys =
            (
                Regex("""\b(\w+)\s*:\s*$TYPE\b""").findAll(source) +
                    Regex("""\bva[lr]\s+(\w+)[^=\n]*=\s*AiSignInKey\s*\.\s*derive\(""").findAll(source)
            ).map { it.groupValues[1] }.toSet()
        val secrets =
            keys
                .flatMap { key -> Regex("""\bva[lr]\s+(\w+)[^=\n]*=\s*$key\s*\.\s*privateKey\b""").findAll(source) }
                .map { it.groupValues[1] }
                .toSet()
        val named = (keys + secrets).map { Regex.escape(it) } + listOf("""AiSignInKey\s*\.\s*derive\(""")
        val subject = "(?:" + named.joinToString("|") { if (it.endsWith("\\(")) it else """\b$it\b""" } + ")"
        val dollar = Regex.escape("$")
        val ways =
            listOf(
                Regex("""$dollar(?:${(keys + secrets).joinToString("|") { Regex.escape(it) }.ifEmpty { "(?!)" }})\b"""),
                Regex("""$dollar\{[^}\n]*$subject[^}\n]*\}"""),
                Regex("""\b(?:Log|Twig|Timber)\s*\.[^\n]*$subject"""),
                Regex("""\b(?:println|print|error)\s*\([^\n]*$subject"""),
                Regex("""$subject[^\n]*\.\s*toString\("""),
                Regex(""""\s*\+[^\n]*$subject|$subject[^\n,)]*\+\s*""""),
            )
        return source.lines().map { it.trim() }.filter { line -> ways.any { it.containsMatchIn(line) } }
    }

    /** No [PIECE]-character run of [secret] stands in [text]. */
    private fun assertNoPieceOf(
        what: String,
        secret: String,
        text: String,
        where: String,
    ) {
        val found = secret.windowed(PIECE).any { it in text }
        // The message names the place only: a failure of this test must not print the key either.
        assertFalse("$where holds a piece of $what", found)
    }

    private companion object {
        const val TYPE = "AiSignInKeyPair"
        const val SEED_BYTES = 64
        const val HEX = 16
        const val PIECE = 8
    }
}
