package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.fail

/**
 * Round 7 B3 — HELD-ACCEPT-MIGRATION-CLEANUP (GPT Q2).
 *
 * migrateHeldGroupAcceptsToEncrypted copies a held group reply that a test build between R3-4 and R4-1 left in the
 * plaintext conversation-mode file into the encrypted store, then removes the plaintext copy — and ignores whether
 * that removal reached the disk. When it did not, the plaintext row survives. Paying the reply removes only the
 * encrypted row (setHeldGroupAccept(id, null) is the pre-pay removal whose confirmation R4-1 waits for), so the next
 * start finds the reply missing from the encrypted store, imports it again, and the first chat screen pays it a
 * second time.
 *
 * Wanted: the pre-pay removal removes every stored copy, and confirms (returns true) only when all of them are gone;
 * so a reply whose removal was confirmed is never re-imported by a later start.
 *
 * All ids, addresses and memos here are synthetic.
 */
class HeldGroupAcceptMigrationCleanupTest {
    @Test
    fun `a reply paid after a failed plaintext cleanup is not imported again on the next start`() {
        val mode = CommitFails(times = 1)
        mode.store
            .edit()
            .putString(LEGACY_KEY, RECORD)
            .commit()
        val (impl, files) = realPreferences()
        impl.useModePrefs(mode)
        val encrypted = files.getValue("heldGroupAcceptPrefs")

        migrate(impl, mode, encrypted) // start 1: the encrypted copy is written, the plaintext cleanup fails
        assertEquals(mapOf(GROUP to RECORD), impl.getHeldGroupAccepts())

        val removalConfirmed = impl.setHeldGroupAccept(GROUP, null) // the removal the payment waits for
        assertTrue("the removal could be written, so it must be confirmed", removalConfirmed)

        migrate(impl, mode, encrypted) // start 2

        assertTrue(
            "a held reply whose removal was confirmed (and so was paid) came back after a restart and would be paid " +
                "again: ${impl.getHeldGroupAccepts()}",
            impl.getHeldGroupAccepts().isEmpty(),
        )
        assertFalse(mode.store.contains(LEGACY_KEY))
    }

    @Test
    fun `a removal whose plaintext copy cannot be removed is never confirmed`() {
        val mode = CommitFails(times = Int.MAX_VALUE)
        val (impl, files) = realPreferences()
        impl.useModePrefs(mode)
        val encrypted = files.getValue("heldGroupAcceptPrefs")
        mode.store
            .edit()
            .putString(LEGACY_KEY, RECORD)
            .commit()
        encrypted.edit().putString(LEGACY_KEY, RECORD).commit()

        val removalConfirmed = impl.setHeldGroupAccept(GROUP, null)
        migrate(impl, mode, encrypted)

        assertTrue(
            "the removal was confirmed (so the reply was paid) yet a restart imports it again",
            !removalConfirmed || impl.getHeldGroupAccepts().isEmpty(),
        )
        assertFalse("a plaintext copy that could not be removed must not be reported as removed", removalConfirmed)
    }

    // Pin: holds before and after the fix.
    @Test
    fun `putting a reply on hold still writes only the encrypted store`() {
        val mode = CommitFails(times = 0)
        val (impl, files) = realPreferences()
        impl.useModePrefs(mode)

        assertTrue(impl.setHeldGroupAccept(GROUP, RECORD))

        assertTrue(mode.store.snapshot().isEmpty())
        assertEquals(RECORD, files.getValue("heldGroupAcceptPrefs").getString(LEGACY_KEY, null))
    }

    private fun ZchatPreferencesImpl.useModePrefs(mode: SharedPreferences) {
        ZchatPreferencesImpl::class.java
            .getDeclaredField("modePrefs")
            .apply { isAccessible = true }
            .set(this, mode)
    }

    private fun migrate(
        impl: ZchatPreferencesImpl,
        old: SharedPreferences,
        enc: SharedPreferences
    ) {
        val method =
            ZchatPreferencesImpl::class.java.declaredMethods.firstOrNull { it.name == "migrateHeldGroupAcceptsToEncrypted" }
                ?: fail("ZchatPreferencesImpl has no migrateHeldGroupAcceptsToEncrypted")
        method.isAccessible = true
        try {
            method.invoke(impl, old, enc)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /**
     * The plaintext conversation-mode file whose next [times] editor commits do not reach the disk (the edit is
     * dropped and commit() answers false); later commits work. Writes made through [store] directly always land.
     */
    private class CommitFails(
        private var times: Int,
        val store: MemorySharedPreferences = MemorySharedPreferences(),
    ) : SharedPreferences by store {
        override fun edit(): SharedPreferences.Editor {
            val real = store.edit()
            return object : SharedPreferences.Editor by real {
                private var dropped = false

                private fun <T> keep(block: () -> T): SharedPreferences.Editor = also { block() }

                override fun putString(
                    key: String,
                    value: String?
                ) = keep { real.putString(key, value) }

                override fun remove(key: String) = keep { real.remove(key) }

                override fun clear() = keep { real.clear() }

                override fun commit(): Boolean {
                    if (times > 0) {
                        times--
                        dropped = true
                        return false
                    }
                    return !dropped && real.commit()
                }

                override fun apply() {
                    commit()
                }
            }
        }
    }

    private companion object {
        const val GROUP = "GSYNTHMIGR1"
        const val LEGACY_KEY = "heldgroupaccept:$GROUP"
        const val RECORD =
            """{"inviter":"usyntheticmigrinviter01","accepter":"usyntheticmigrself01","memo":"synthetic-accept-memo"}"""
    }
}
