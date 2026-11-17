package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * R5-2 (round 4 report, section 5B) — five more money markers save without checking the write.
 *
 * Each of these is the durable record that stops an on-chain memo the user never asked for being paid a
 * second time, or the record of the consent that lets those memos be paid at all:
 *
 * - `setSentKexAckPubkey` — the ONLY durable de-dupe for a paid KEXACK (~1000 zatoshi per key). Written
 *   with `apply()`, in the background, immediately before the submit: a kill in that window loses it and
 *   the next chat open pays the same KEXACK again.
 * - `setOwnBootSent` — written immediately before the paid KEX it guards, also with `apply()`.
 * - `setSentNostrBootPubkey` — the delivered-identity marker the ZBOOT path reads before it pays.
 * - `setGroupKeyRequestGranted` — the ONLY durable de-dupe for a paid group-key grant (~17,000 zatoshi).
 *   It is written with `commit()`, but `commit()`'s result is thrown away, so a failed write reads as a
 *   recorded grant and the next history scan pays the same request again.
 * - `setConsentedToHandshakeSpend` — written with `apply()`. Consent is in the in-memory map at once, so
 *   the queued handshakes spend against it; a kill before the disk write loses the record of the consent
 *   those spends were made under.
 *
 * Wanted, as for the R4-1 pair: written synchronously with `commit()`, and reporting whether the write
 * reached the disk, so the caller can stop before spending. Re-arming a marker is the opposite direction —
 * losing it can only mean NOT paying again — so it stays in the background and always reports true.
 *
 * No Context: the store is allocated without its constructor and given a fake preferences file whose
 * `commit()` result is chosen here, following HandshakeMoneyWritesReportedTest (R4-1) and
 * InboundKexAwaitingAcceptCommitFailureTest (GPT-6 F3).
 */
class HandshakeMarkerWritesReportedTest {
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

    /**
     * A [ZchatPreferencesImpl] with no Context: allocated without its constructor, with [field] pointing at
     * [store]'s fake preferences file and the handshake-tick flow the marker setters bump wired up.
     */
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
        // setOwnBootSent / setSentKexAckPubkey notify this flow; an allocated instance has none.
        ZchatPreferencesImpl::class.java.declaredFields
            .firstOrNull { it.name == "_e2eHandshakeTicks" }
            ?.apply { isAccessible = true }
            ?.set(impl, MutableStateFlow(0L))
        return impl
    }

    private fun call(
        impl: ZchatPreferencesImpl,
        name: String,
        paramTypes: Array<Class<*>>,
        vararg args: Any?
    ): Any? =
        ZchatPreferencesImpl::class.java
            .getMethod(name, *paramTypes)
            .invoke(impl, *args)

    private fun kexAck(
        impl: ZchatPreferencesImpl,
        pubkeyHex: String?
    ) = call(impl, "setSentKexAckPubkey", arrayOf(String::class.java, String::class.java), PEER, pubkeyHex)

    private fun ownBootSent(
        impl: ZchatPreferencesImpl,
        sent: Boolean
    ) = call(impl, "setOwnBootSent", arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!), PEER, sent)

    private fun nostrBoot(
        impl: ZchatPreferencesImpl,
        pubkeyHex: String?
    ) = call(impl, "setSentNostrBootPubkey", arrayOf(String::class.java, String::class.java), PEER, pubkeyHex)

    private fun keyGrant(
        impl: ZchatPreferencesImpl,
        granted: Boolean
    ) = call(impl, "setGroupKeyRequestGranted", arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!), REQUEST, granted)

    private fun consent(impl: ZchatPreferencesImpl) = call(impl, "setConsentedToHandshakeSpend", emptyArray())

    /**
     * A re-arm is the opposite direction: losing it can only mean NOT paying again, and rotateNostrIdentity
     * writes one per peer in a loop, so it stays in the background and reports true even on a store whose
     * synchronous writes fail.
     */
    private fun assertBackgroundRearm(store: FakeStore) =
        assertTrue(
            "re-arming a marker must stay in the background, and report true whatever the store does " +
                "(calls: ${store.calls})",
            "apply" in store.calls && "commit" !in store.calls,
        )

    private fun assertCommittedNotApplied(
        store: FakeStore,
        what: String
    ) = assertTrue(
        "$what must be written with commit(), not apply(): it is written immediately before the memo it " +
            "guards is paid for, so a process kill must not lose it (calls: ${store.calls})",
        "commit" in store.calls && "apply" !in store.calls,
    )

    @Test
    fun `a KEXACK sent-marker that could not be written reports that it was not saved`() {
        val failing = FakeStore(commitSucceeds = false)
        assertEquals(
            "the KEXACK sent-marker never reached the disk and the setter did not say so; it is the only " +
                "durable record that stops the next chat open paying the same KEXACK again",
            false,
            kexAck(storeWith("modePrefs", failing), SYNTHETIC_PUBKEY),
        )
        assertCommittedNotApplied(failing, "the KEXACK sent-marker")

        assertEquals(true, kexAck(storeWith("modePrefs", FakeStore(commitSucceeds = true)), SYNTHETIC_PUBKEY))
        assertBackgroundRearm(FakeStore(commitSucceeds = false).also { assertEquals(true, kexAck(storeWith("modePrefs", it), null)) })
    }

    @Test
    fun `a bootstrap-sent marker that could not be written reports that it was not saved`() {
        val failing = FakeStore(commitSucceeds = false)
        assertEquals(
            "the bootstrap-sent marker never reached the disk and the setter did not say so; it is written " +
                "immediately before the paid KEX it guards",
            false,
            ownBootSent(storeWith("modePrefs", failing), true),
        )
        assertCommittedNotApplied(failing, "the bootstrap-sent marker")

        assertEquals(true, ownBootSent(storeWith("modePrefs", FakeStore(commitSucceeds = true)), true))
        assertBackgroundRearm(FakeStore(commitSucceeds = false).also { assertEquals(true, ownBootSent(storeWith("modePrefs", it), false)) })
    }

    @Test
    fun `a delivered-identity marker that could not be written reports that it was not saved`() {
        val failing = FakeStore(commitSucceeds = false)
        assertEquals(
            "the delivered-identity marker never reached the disk and the setter did not say so; the ZBOOT " +
                "path reads it to decide whether our identity still has to be paid for",
            false,
            nostrBoot(storeWith("modePrefs", failing), SYNTHETIC_PUBKEY),
        )
        assertCommittedNotApplied(failing, "the delivered-identity marker")

        assertEquals(true, nostrBoot(storeWith("modePrefs", FakeStore(commitSucceeds = true)), SYNTHETIC_PUBKEY))
        assertBackgroundRearm(FakeStore(commitSucceeds = false).also { assertEquals(true, nostrBoot(storeWith("modePrefs", it), null)) })
    }

    @Test
    fun `a group-key grant record that could not be written reports that it was not saved`() {
        val failing = FakeStore(commitSucceeds = false)
        assertEquals(
            "the group-key grant record never reached the disk and the setter did not say so; it is the only " +
                "durable record that stops the next history scan paying the same grant again",
            false,
            keyGrant(storeWith("prefs", failing), true),
        )
        assertCommittedNotApplied(failing, "the group-key grant record")

        assertEquals(true, keyGrant(storeWith("prefs", FakeStore(commitSucceeds = true)), true))
        assertEquals(
            "withdrawing the record reports its write as well",
            true,
            keyGrant(storeWith("prefs", FakeStore(commitSucceeds = true)), false),
        )
    }

    @Test
    fun `handshake-spend consent that could not be written reports that it was not saved`() {
        val failing = FakeStore(commitSucceeds = false)
        assertEquals(
            "the handshake-spend consent never reached the disk and the setter did not say so; the queued " +
                "handshakes would spend against a consent no restart can find again",
            false,
            consent(storeWith("prefs", failing)),
        )
        assertCommittedNotApplied(failing, "the handshake-spend consent")

        assertEquals(true, consent(storeWith("prefs", FakeStore(commitSucceeds = true))))
    }

    private companion object {
        const val PEER = "usyntheticmarkerwritespeer01"
        const val REQUEST = "zgrp_syntheticmarkerwrites:synthetic-request-tx"
        const val SYNTHETIC_PUBKEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    }
}
