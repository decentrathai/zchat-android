package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * ZC-705 (register ZC-705, medium; store-opener design, revision 2a, sections 2.2 and 5.2, class A): the guard can
 * tell a key set stored WITHOUT encryption from a protected one, never throws on anything a file can hold, and logs a
 * store by its kind, never by a bundle's file name (it ends in the wallet's owner tag).
 *
 * The two shapes are made here with the encryption library (the two templates the stores use) and never written into a
 * source file: the library's unencrypted serialization starts 08 (the primary key id's field), the encrypted one
 * starts 12. `KeysetGuard.isPlain` and `KeysetGuard.kindOf` are reached by name ([StoreOpenerApi]).
 *
 * The last case is a GUARD that passes before the fix: no product source serializes a key set without encryption,
 * except the debug hook's own helper.
 */
class CleartextKeysetIsDetectedTest {
    @Test
    fun `the two shapes - the unencrypted serialization is plain and starts 08, the encrypted one is not and starts 12`() {
        for (template in listOf(Keysets.NAMES_TEMPLATE, Keysets.VALUES_TEMPLATE)) {
            val plain = Keysets.plain(template)
            val wrapped = Keysets.wrapped(template)
            assertTrue("$template: the unencrypted serialization does not start 08", plain.startsWith("08"))
            assertTrue("$template: the encrypted serialization does not start 12", wrapped.startsWith("12"))
            assertTrue("$template: an unencrypted key set is not told plain", StoreOpenerApi.isPlain(plain))
            assertFalse("$template: a protected key set is told plain", StoreOpenerApi.isPlain(wrapped))
        }
    }

    @Test
    fun `200 fresh key sets of each type keep the two prefixes`() {
        for (template in listOf(Keysets.NAMES_TEMPLATE, Keysets.VALUES_TEMPLATE)) {
            repeat(200) {
                val plain = Keysets.plain(template)
                val wrapped = Keysets.wrapped(template)
                assertTrue("$template #$it: plain does not start 08", plain.startsWith("08"))
                assertTrue("$template #$it: wrapped does not start 12", wrapped.startsWith("12"))
                assertTrue("$template #$it: plain not detected", StoreOpenerApi.isPlain(plain))
                assertFalse("$template #$it: wrapped detected as plain", StoreOpenerApi.isPlain(wrapped))
            }
        }
    }

    @Test
    fun `null, empty, odd, not hex, random bytes and an 08 text that is no key set are not plain and throw nothing`() {
        val random = Random(705)
        val texts =
            listOf(
                null,
                "",
                "080",
                "08zz",
                "not hex at all",
                Keysets.hex(random.nextBytes(48)),
                "08" + Keysets.hex(random.nextBytes(40)),
                "08ff",
                "0801",
                "08" + "00".repeat(16),
            )
        for (text in texts) {
            val answer = runCatching { StoreOpenerApi.isPlain(text) }
            assertTrue("isPlain threw for <$text>: ${answer.exceptionOrNull()}", answer.isSuccess)
            assertFalse("<$text> is no key set and was told plain", answer.getOrThrow())
        }
    }

    @Test
    fun `the kind names a fixed store by its name and a bundle or a copy by what it is, never by the owner tag`() {
        val tag = "ab".repeat(32)
        assertEquals("zchat_e2e_keys_encrypted", StoreOpenerApi.kindOf("zchat_e2e_keys_encrypted"))
        assertEquals("co.electriccoin.zcash.encrypted", StoreOpenerApi.kindOf("co.electriccoin.zcash.encrypted"))
        val bundle = StoreOpenerApi.kindOf("zchat_set_aside_$tag")
        assertEquals("a set-aside bundle", bundle)
        assertFalse("the bundle's kind holds its owner tag", bundle.contains(tag))
        assertEquals("a kept copy", StoreOpenerApi.kindOf("zchat_e2e_keys_encrypted__unreadable_1700000000000"))
    }

    // GUARD: passes at the pinned commit; does not need KeysetGuard.kt to exist.
    @Test
    fun `no product source serializes a key set without encryption, except the debug hook's helper`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        val root = dir ?: error("repo root not found")
        val allowed = "ui-lib/src/debug/java/co/electriccoin/zcash/ui/debug/KeysetScratch.kt"
        val hits =
            root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && File(it, "src").isDirectory }
                .flatMap { module ->
                    listOf("main", "debug").flatMap { set ->
                        File(module, "src/$set")
                            .walkTopDown()
                            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                            .toList()
                    }
                }.map { it.relativeTo(root).invariantSeparatorsPath to StoreOpenerApi.stripComments(it.readText()) }
                .filter { (_, code) -> "serializeKeyset(" in code || "CleartextKeysetHandle.write" in code }
                .map { it.first }
                .filter { it != allowed }
        assertEquals("a product source writes a key set in the clear", emptyList<String>(), hits)
    }
}
