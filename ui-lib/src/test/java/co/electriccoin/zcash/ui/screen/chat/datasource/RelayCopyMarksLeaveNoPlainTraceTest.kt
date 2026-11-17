package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import kotlin.test.fail

/**
 * ZC-420 (fix-all 2.13.0, train T6b; ZC-420-SPEC §3.3 R-store, §3.2 R11, §4.2): the relay-copy marks leave no plain trace.
 *
 * A mark's slot names a contact ("zb|<peer>") or a group and a member ("gk|", "gi|", "gm|<groupId>|<member>|…"). The
 * plain files already show every group id and every contact address, so a group slot in a plain file, even hashed with
 * fixed text, would tell a reader which contact is a member of which group: the rosters are encrypted on purpose. Wanted:
 * every mark is in the encrypted held-reply store, and nothing else changes. The twin of
 * HeldGroupAcceptStoredEncryptedTest, on a real [ZchatPreferencesImpl] whose every file is in memory ([realPreferences]).
 * After one mark of each kind for a synthetic group id, member, contact and rumor id: every file but the held-reply store
 * is exactly as before; all of them together (key names and values) hold neither `relaycopy:`, the group id, the two
 * addresses, the rumor id, nor the first 16 hex of SHA-256 of "zc420|g|<groupId>" or "zc420|m|<groupId>|<member>" (the
 * hashes the first design used); the marks' store is opened with createEncryptedPrefs, and the three mark methods name
 * only it. Every value is synthetic.
 */
class RelayCopyMarksLeaveNoPlainTraceTest {
    private companion object {
        const val HELD_STORE_FIELD = "heldGroupAcceptPrefs"
        const val GROUP = "GSYNTHZC420TRACE"
        const val MEMBER = "usyntheticzc420tracemember01"
        const val PEER = "usyntheticzc420tracepeer01"
        const val RUMOR = "rumorzc420tracesynthetic01"
        val METHODS = listOf("getRelayCopyMark", "setRelayCopyMark", "getRelayCopyMarkSlots")

        fun hash16(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
    }

    private fun setMark(
        impl: ZchatPreferencesImpl,
        slot: String,
        value: String,
    ) {
        val method =
            impl.javaClass.methods.firstOrNull { it.name == "setRelayCopyMark" && it.parameterCount == 2 }
                ?: fail("ZchatPreferencesImpl has no setRelayCopyMark (ZC-420): the relay-copy marks have no store")
        try {
            assertTrue("fixture: the mark was written", method.invoke(impl, slot, value) as Boolean)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `one mark of each kind changes only the held-reply store, and no plain file holds or lets one recompute a slot`() {
        val (impl, files) = realPreferences()
        // Some unrelated state first, so "exactly as before" compares something.
        val mode = files.getValue("modePrefs")
        val main = files.getValue("prefs")
        assertTrue(mode.edit().putString("mode:usyntheticzc420unrelated", "TUNNEL").commit())
        assertTrue(main.edit().putString("synthetic-unrelated-key", "synthetic-unrelated-value").commit())
        val before = files.mapValues { it.value.snapshot() }

        val time = System.currentTimeMillis()
        setMark(impl, "zb|$PEER", "$RUMOR|$time|ourrelaykey")
        setMark(impl, "gk|$GROUP|$MEMBER", "$RUMOR|$time|3")
        setMark(impl, "gi|$GROUP|$MEMBER", "$RUMOR|$time|")
        setMark(impl, "gm|$GROUP|$MEMBER|3|41", "$RUMOR|$time|3")

        val forbidden = listOf("relaycopy:", GROUP, MEMBER, PEER, RUMOR, hash16("zc420|g|$GROUP"), hash16("zc420|m|$GROUP|$MEMBER"))
        for ((name, file) in files) {
            if (name == HELD_STORE_FIELD) continue
            assertEquals("the store `$name` changed when the marks were written", before.getValue(name), file.snapshot())
            for ((key, value) in file.snapshot()) {
                val text = key + "\u0000" + value
                forbidden.forEach { assertTrue("`$name` holds or lets one recompute a mark's slot", it !in text) }
            }
        }
        val held = files.getValue(HELD_STORE_FIELD).snapshot()
        assertEquals("the four marks are in the held-reply store", 4, held.keys.count { it.startsWith("relaycopy:") })
    }

    @Test
    fun `the marks' store is opened encrypted and the mark methods name only it`() {
        val source = sourceOf("ZchatPreferences.kt")
        val declaration = source.lines().firstOrNull { it.contains("val $HELD_STORE_FIELD") } ?: fail("no `$HELD_STORE_FIELD` declaration")
        assertTrue("the held-reply store is opened with createEncryptedPrefs: $declaration", declaration.contains("createEncryptedPrefs"))
        val impl = source.substringAfter("class ZchatPreferencesImpl")
        for (name in METHODS) {
            val start = impl.indexOf("override fun $name(")
            assertTrue("ZchatPreferencesImpl does not implement $name (ZC-420)", start >= 0)
            // The member up to the blank line that ends it.
            val text = impl.substring(start, impl.indexOf("\n\n", start).let { if (it < 0) impl.length else it })
            assertTrue("$name does not use the held-reply store", "heldGroupAcceptPrefs" in text)
            assertTrue("$name names a plain file", "modePrefs" !in text && "getSharedPreferences" !in text && " prefs." !in text)
        }
    }

    private fun sourceOf(name: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val file =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/$name") }
                .firstOrNull { it.isFile }
                ?: fail("could not find $name from ${start.path}")
        return file.readText()
    }
}
