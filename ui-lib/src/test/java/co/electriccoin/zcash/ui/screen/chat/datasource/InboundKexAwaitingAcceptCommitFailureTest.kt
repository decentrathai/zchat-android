package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * GPT-6 F3 (22 Sep) — the durable hold on a stranger's chat request ignores a failed write.
 *
 * [ZchatPreferences.setInboundKexAwaitingAccept] is the marker that keeps a stranger's paid KEXACK back until
 * the user taps Accept. It is written with commit(), but commit()'s result is thrown away: when the write to
 * disk fails, the caller carries on storing the stranger's key and E2E state as if the hold were saved. After
 * a restart the hold is gone while the rest survives, and the stranger reads as a peer we already answered.
 *
 * Wanted: the setter reports whether the hold was saved (true) or not (false), so its caller can stop. The
 * setter is reached by name so this file compiles before the result exists. No Context: the store is
 * allocated without its constructor and given a fake preferences file whose commit() result is chosen here.
 */
class InboundKexAwaitingAcceptCommitFailureTest {
    private fun storeWith(commitSucceeds: Boolean): ZchatPreferencesImpl {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val store =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
        setField(store, "modePrefs", fakePrefs(commitSucceeds))
        setField(store, "_e2eHandshakeTicks", MutableStateFlow(0L))
        return store
    }

    private fun setField(
        store: ZchatPreferencesImpl,
        name: String,
        value: Any
    ) {
        ZchatPreferencesImpl::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(store, value)
    }

    private fun fakePrefs(commitSucceeds: Boolean): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor =
            Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "commit" -> commitSucceeds
                    "apply" -> null
                    else -> editor
                }
            } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }

    private fun hold(
        store: ZchatPreferencesImpl,
        pubkey: String?
    ): Any? =
        ZchatPreferencesImpl::class.java
            .getMethod("setInboundKexAwaitingAccept", String::class.java, String::class.java)
            .invoke(store, PEER, pubkey)

    @Test
    fun `a hold that could not be written reports that it was not saved`() {
        assertEquals(
            "the chat-request hold was not written to disk, and the setter did not say so",
            false,
            hold(storeWith(commitSucceeds = false), "synthetic-stranger-key"),
        )
    }

    @Test
    fun `a hold that was written reports that it was saved`() {
        assertEquals(true, hold(storeWith(commitSucceeds = true), "synthetic-stranger-key"))
        assertEquals("clearing reports its write as well", true, hold(storeWith(commitSucceeds = true), null))
    }

    private companion object {
        const val PEER = "usyntheticcommitfailurepeer01"
    }
}
