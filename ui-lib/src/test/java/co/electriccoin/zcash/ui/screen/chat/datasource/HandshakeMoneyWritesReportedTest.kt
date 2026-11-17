package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * R4-1 (round 3 report, section 4 item 2) — two money writes ignore whether they reached the disk.
 *
 * - `setHandshakePaidRecord` is the durable "this chat setup is already paid for" record, written just
 *   before a paid attempt submits. It is written with `commit()`, but `commit()`'s result is thrown away:
 *   a failed write reads as a recorded payment, and a restart inside the cooldown pays the same KEX or
 *   ZBOOT again.
 * - `clearConsentedToHandshakeSpend` is written with `apply()`, in the background. Confirming a move of
 *   the old balance calls it immediately BEFORE the move is sent (owner decision 2) precisely so queued
 *   handshakes cannot spend the moved funds. A process kill in that window loses the background write,
 *   consent still stands, and the queued handshakes spend the moved balance without asking.
 *
 * Wanted: both report whether the write reached the disk, and the consent reset is written synchronously
 * with `commit()`, so their callers can stop before spending.
 *
 * No Context: the store is allocated without its constructor and given a fake preferences file whose
 * `commit()` result is chosen here, following InboundKexAwaitingAcceptCommitFailureTest (GPT-6 F3).
 */
class HandshakeMoneyWritesReportedTest {
    private class FakeStore(
        private val commitSucceeds: Boolean,
    ) {
        /** Every editor call made on this store, in order: "commit", "apply", "putString", "remove", … */
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val prefs: SharedPreferences by lazy {
            lateinit var editor: SharedPreferences.Editor
            editor =
                Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { _, method, _ ->
                    calls += method.name
                    when (method.name) {
                        "commit" -> commitSucceeds
                        "apply" -> null
                        else -> editor
                    }
                } as SharedPreferences.Editor
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "edit" -> editor
                    "getBoolean" -> false
                    else -> null
                }
            } as SharedPreferences
        }
    }

    private fun storeWith(
        field: String,
        store: FakeStore
    ): ZchatPreferencesImpl {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val impl =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
        ZchatPreferencesImpl::class.java
            .getDeclaredField(field)
            .apply { isAccessible = true }
            .set(impl, store.prefs)
        return impl
    }

    private fun paidRecord(
        impl: ZchatPreferencesImpl,
        record: String?
    ): Any? =
        ZchatPreferencesImpl::class.java
            .getMethod("setHandshakePaidRecord", String::class.java, String::class.java)
            .invoke(impl, SLOT, record)

    private fun clearConsent(impl: ZchatPreferencesImpl): Any? =
        ZchatPreferencesImpl::class.java
            .getMethod("clearConsentedToHandshakeSpend")
            .invoke(impl)

    @Test
    fun `a paid-setup record that could not be written reports that it was not saved`() {
        val store = FakeStore(commitSucceeds = false)
        assertEquals(
            "the paid-setup record was not written to disk, and the setter did not say so; a restart " +
                "inside the cooldown pays the same chat setup again",
            false,
            paidRecord(storeWith("modePrefs", store), "synthetic-identity|1758499200000"),
        )
    }

    @Test
    fun `a paid-setup record that was written reports that it was saved`() {
        assertEquals(true, paidRecord(storeWith("modePrefs", FakeStore(commitSucceeds = true)), "synthetic-identity|1"))
        assertEquals(
            "withdrawing the record reports its write as well",
            true,
            paidRecord(storeWith("modePrefs", FakeStore(commitSucceeds = true)), null),
        )
    }

    @Test
    fun `the handshake-spend consent reset is written synchronously and reports its write`() {
        val failing = FakeStore(commitSucceeds = false)
        assertEquals(
            "a consent reset that never reached the disk read as done; queued handshakes would spend " +
                "the moved old balance without asking",
            false,
            clearConsent(storeWith("prefs", failing)),
        )
        assertTrue(
            "the consent reset must be written with commit(), not apply(): it runs immediately before " +
                "the move is sent, so a kill must not lose it (calls: ${failing.calls})",
            "commit" in failing.calls && "apply" !in failing.calls,
        )

        assertEquals(true, clearConsent(storeWith("prefs", FakeStore(commitSucceeds = true))))
    }

    private companion object {
        const val SLOT = "ZBOOT:usyntheticpaidrecordpeer01"
    }
}
