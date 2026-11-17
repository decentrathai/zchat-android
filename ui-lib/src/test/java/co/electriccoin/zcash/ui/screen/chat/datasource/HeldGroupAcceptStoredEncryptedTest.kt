package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.test.fail

/**
 * R4-1 (round 3 report, section 4 item 1, last clause) — a held group-invite reply is kept in the clear.
 *
 * R3-4 stores our unsent paid GROUP_ACCEPT under `heldgroupaccept:<groupId>` in `modePrefs`, which is
 * `getSharedPreferences("zchat_conversation_mode", MODE_PRIVATE)` — an ordinary plaintext preferences file.
 * The record holds the inviter's full address, our own address and the whole signed accept memo, so a phone
 * backup, a file-level extraction or another app with the data directory readable learns who invited us to
 * which group and can replay the memo. Every other store carrying addresses or message content (nicknames,
 * drafts, pending messages, group info and rosters) is already AES-256-GCM encrypted.
 *
 * Wanted: the held accept lives in its own encrypted store, nothing is written to the plain conversation-mode
 * file any more, and a record an earlier version left in the plain file is moved across — never lost, and
 * removed from the clear only after the encrypted write is durable.
 *
 * All addresses, group ids and memos here are synthetic.
 */
class HeldGroupAcceptStoredEncryptedTest {
    /** The field the held accepts must live in; fails the test with a readable message while it is missing. */
    private fun heldStoreField(): java.lang.reflect.Field =
        ZchatPreferencesImpl::class.java.declaredFields.firstOrNull { it.name == HELD_STORE_FIELD }
            ?: fail(
                "ZchatPreferencesImpl has no `$HELD_STORE_FIELD`: the held GROUP_ACCEPT (inviter address, " +
                    "our address, signed memo) is still kept in the plaintext conversation-mode file",
            )

    private fun store(
        mode: SharedPreferences,
        held: SharedPreferences?
    ): ZchatPreferencesImpl {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val impl =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
        ZchatPreferencesImpl::class.java
            .getDeclaredField("modePrefs")
            .apply { isAccessible = true }
            .set(impl, mode)
        held?.let { heldStoreField().apply { isAccessible = true }.set(impl, it) }
        return impl
    }

    @Test
    fun `a held group reply is written to its own store and never to the plain conversation-mode file`() {
        val mode = MemorySharedPreferences()
        val heldStore = MemorySharedPreferences()
        val impl = store(mode, heldStore)

        impl.setHeldGroupAccept(GROUP, RECORD)

        assertTrue(
            "the held GROUP_ACCEPT was written to the plaintext conversation-mode file: ${mode.snapshot().keys}",
            mode.snapshot().isEmpty(),
        )
        assertEquals("the held GROUP_ACCEPT must be readable from its own store", mapOf(GROUP to RECORD), impl.getHeldGroupAccepts())

        impl.setHeldGroupAccept(GROUP, null)
        assertTrue("removing must clear it", impl.getHeldGroupAccepts().isEmpty())
        assertTrue(mode.snapshot().isEmpty())
    }

    @Test
    fun `a held group reply an earlier version left in the clear is moved across, not lost`() {
        val mode = MemorySharedPreferences()
        mode.edit().putString("heldgroupaccept:$GROUP", RECORD).commit()
        mode.edit().putString("mode:usyntheticheldpeer01", "TUNNEL").commit()
        val heldStore = MemorySharedPreferences()
        val impl = store(mode, heldStore)

        migrate(impl, mode, heldStore)

        assertEquals("the record must survive the move", mapOf(GROUP to RECORD), impl.getHeldGroupAccepts())
        assertFalse(
            "the plaintext copy must be gone once the encrypted write is durable",
            mode.snapshot().keys.any { it.startsWith("heldgroupaccept:") },
        )
        assertEquals(
            "the move must touch nothing else in the conversation-mode file",
            "TUNNEL",
            mode.getString("mode:usyntheticheldpeer01", null),
        )
    }

    @Test
    fun `a move whose encrypted write fails keeps the plaintext copy for the next try`() {
        val mode = MemorySharedPreferences()
        mode.edit().putString("heldgroupaccept:$GROUP", RECORD).commit()
        val failing = NeverCommits()
        val impl = store(mode, MemorySharedPreferences())

        migrate(impl, mode, failing)

        assertEquals(
            "the unsent group reply must not be dropped when the encrypted store could not take it",
            RECORD,
            mode.getString("heldgroupaccept:$GROUP", null),
        )
    }

    @Test
    fun `the held-accept store is created encrypted`() {
        val source = sourceOf("ZchatPreferences.kt")
        val declaration =
            source
                .lines()
                .firstOrNull { it.contains("val $HELD_STORE_FIELD") }
                ?: fail("no `$HELD_STORE_FIELD` declaration in ZchatPreferences.kt")
        assertTrue(
            "the held GROUP_ACCEPT store must be opened with createEncryptedPrefs (AES-256-GCM), not " +
                "getSharedPreferences: $declaration",
            declaration.contains("createEncryptedPrefs"),
        )
    }

    private fun migrate(
        impl: ZchatPreferencesImpl,
        old: SharedPreferences,
        enc: SharedPreferences
    ) {
        val method =
            ZchatPreferencesImpl::class.java.declaredMethods.firstOrNull { it.name == MIGRATE_METHOD }
                ?: fail("ZchatPreferencesImpl has no `$MIGRATE_METHOD`: held replies left in the clear are never moved")
        method.isAccessible = true
        try {
            method.invoke(impl, old, enc)
        } catch (e: InvocationTargetException) {
            throw e.targetException
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

    /** A store whose writes never reach the disk. */
    private class NeverCommits : SharedPreferences by MemorySharedPreferences() {
        override fun edit(): SharedPreferences.Editor = Editor()

        private class Editor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor = this

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = this

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = this

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this

            override fun remove(key: String): SharedPreferences.Editor = this

            override fun clear(): SharedPreferences.Editor = this

            override fun commit(): Boolean = false

            override fun apply() = Unit
        }
    }

    private companion object {
        const val HELD_STORE_FIELD = "heldGroupAcceptPrefs"
        const val MIGRATE_METHOD = "migrateHeldGroupAcceptsToEncrypted"
        const val GROUP = "GSYNTHENC1"
        const val RECORD =
            """{"inviter":"usyntheticheldinviter01","accepter":"usyntheticheldself01","memo":"synthetic-accept-memo"}"""
    }
}
