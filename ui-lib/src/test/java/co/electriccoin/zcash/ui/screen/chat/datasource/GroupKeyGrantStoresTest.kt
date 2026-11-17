package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * KI-23 (ZC-020; SPEC D2, D4) — the two stores behind "a key request is paid at most once per member and key, and at
 * most ten times a day": the paid count per grant key and the daily budget of automatic paid key answers.
 *
 * Both guard a payment, so both are written with a checked commit() and report a write that did not reach the disk.
 * The count is independent of the per-request record, is removed by a wipe and puts no group id or address into the
 * plain preferences file. The budget is the automatic key reply's budget again, under its own key: ten units inside
 * the window, the eleventh refused and not recorded, a unit free again exactly one window after it was used, the
 * read-only question agreeing with the consuming one, and wiped with the rest.
 *
 * The real ZchatPreferencesImpl over in-memory files; the new methods are reached by name (they do not exist before
 * the fix, where each test fails on the missing method).
 */
class GroupKeyGrantStoresTest {
    private val grantKey = "zgrp_syntheticki23store|usyntheticki23storemember001|4"

    private fun method(name: String) =
        ZchatPreferencesImpl::class.java.methods.firstOrNull { it.name == name }
            ?: throw AssertionError("ZchatPreferencesImpl has no $name: the store of KI-23 does not exist")

    private fun call(
        impl: ZchatPreferencesImpl,
        name: String,
        vararg args: Any?,
    ): Any? =
        try {
            method(name).invoke(impl, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun count(impl: ZchatPreferencesImpl, key: String = grantKey) = call(impl, "getGroupKeyGrantPaidCount", key)

    private fun setCount(
        impl: ZchatPreferencesImpl,
        value: Int,
        key: String = grantKey,
    ) = call(impl, "setGroupKeyGrantPaidCount", key, value)

    private fun available(impl: ZchatPreferencesImpl, now: Long) = call(impl, "groupKeyGrantBudgetAvailable", now, MAX, WINDOW)

    private fun consume(impl: ZchatPreferencesImpl, now: Long) = call(impl, "tryConsumeGroupKeyGrantBudget", now, MAX, WINDOW)

    /** [memory] for every read, with an editor whose commit() fails and whose apply() is recorded. */
    private class FailingCommits(
        private val memory: MemorySharedPreferences,
    ) {
        val editorCalls = mutableListOf<String>()
        val prefs: SharedPreferences by lazy {
            lateinit var editor: SharedPreferences.Editor
            editor =
                Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { _, m, _ ->
                    editorCalls += m.name
                    when (m.name) {
                        "commit" -> false
                        "apply" -> null
                        else -> editor
                    }
                } as SharedPreferences.Editor
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, m, args ->
                if (m.name == "edit") editor else m.invoke(memory, *(args ?: emptyArray()))
            } as SharedPreferences
        }
    }

    private fun failCommitsOf(
        impl: ZchatPreferencesImpl,
        files: Map<String, MemorySharedPreferences>,
        field: String,
    ): FailingCommits =
        FailingCommits(files.getValue(field)).also { failing ->
            val target = ZchatPreferencesImpl::class.java.getDeclaredField(field)
            target.isAccessible = true
            target.set(impl, failing.prefs)
        }

    @Test
    fun `the paid count is read back, removed at zero and independent of the request record`() {
        val (impl, files) = realPreferences()
        assertEquals("a key nobody paid for has a count", 0, count(impl))

        assertEquals(true, setCount(impl, 1))
        assertEquals(1, count(impl))
        assertEquals("another key's count moved", 0, count(impl, "$grantKey-other"))
        assertFalse("the count touched the per-request record", impl.isGroupKeyRequestGranted(grantKey))
        impl.setGroupKeyRequestGranted(grantKey, true)
        impl.setGroupKeyRequestGranted(grantKey, false)
        assertEquals("withdrawing a request record changed the count", 1, count(impl))

        assertEquals(true, setCount(impl, 0))
        assertEquals(0, count(impl))
        val stored = files.getValue("prefs").snapshot().keys
        assertTrue("a count of 0 is still stored: $stored", stored.none { it.startsWith("gk_grant_paid") })
    }

    @Test
    fun `the paid count is stored in the main file with no group id or address in its key`() {
        val (impl, files) = realPreferences()
        setCount(impl, 1)

        val stored = files.getValue("prefs").snapshot().keys
        val keys = stored.filter { it.startsWith("gk_grant_paid:") }
        assertEquals("the count is not stored under gk_grant_paid: in the main file", 1, keys.size)
        assertFalse("the key names the group: ${keys.single()}", keys.single().contains("zgrp_"))
        assertFalse("the key names the member's address: ${keys.single()}", keys.single().contains("usynthetic"))
    }

    @Test
    fun `a paid count that does not reach the disk is reported and is written with commit`() {
        val (impl, files) = realPreferences()
        val failing = failCommitsOf(impl, files, "prefs")

        assertEquals(
            "the paid count never reached the disk and the setter did not say so; it is what stops a second request " +
                "for the same key being paid for",
            false,
            setCount(impl, 1),
        )
        assertTrue("the paid count is not written with commit(): ${failing.editorCalls}", "commit" in failing.editorCalls)
        assertFalse("the paid count is written with apply()", "apply" in failing.editorCalls)
        assertEquals(0, count(impl))
    }

    @Test
    fun `a wipe removes the paid count and the budget`() {
        val (impl, _) = realPreferences()
        setCount(impl, 1)
        repeat(MAX) { assertEquals(true, consume(impl, NOW)) }
        assertEquals(false, available(impl, NOW))

        impl.clearAll()

        assertEquals("a wipe left a paid count", 0, count(impl))
        assertEquals("a wipe left the budget used", true, available(impl, NOW))
    }

    @Test
    fun `the budget gives ten units a window, refuses the eleventh without recording it, and frees a unit after exactly the window`() {
        val (impl, files) = realPreferences()
        assertEquals(true, available(impl, NOW))
        assertEquals("the first unit", true, consume(impl, NOW))
        for (n in 1 until MAX) {
            assertEquals("the read-only question disagrees before unit ${n + 1}", true, available(impl, NOW + n))
            assertEquals("unit ${n + 1}", true, consume(impl, NOW + n))
        }
        assertEquals("the read-only question says a unit is free when ten are used", false, available(impl, NOW + MAX))
        assertEquals("the eleventh unit of a window was given", false, consume(impl, NOW + MAX))
        val stored = files.getValue("kexSeenPrefs").snapshot()["gkgrant_budget_ts"] as String?
        assertEquals(
            "the budget is not kept as ten times under gkgrant_budget_ts in the KEX-seen file, or the refused unit was recorded",
            MAX,
            stored?.split('\n')?.size,
        )
        assertTrue("the budget shares the key reply's key", files.getValue("kexSeenPrefs").snapshot()["kexack_budget_ts"] == null)

        assertEquals("a unit is free before the window has passed", false, available(impl, NOW + WINDOW - 1))
        assertEquals(false, consume(impl, NOW + WINDOW - 1))
        assertEquals("the oldest unit is not free exactly one window after it was used", true, available(impl, NOW + WINDOW))
        assertEquals(true, consume(impl, NOW + WINDOW))
        assertEquals("only the oldest unit rolled out", false, available(impl, NOW + WINDOW))
    }

    @Test
    fun `a budget unit that does not reach the disk is reported and is written with commit`() {
        val (impl, files) = realPreferences()
        val failing = failCommitsOf(impl, files, "kexSeenPrefs")

        assertEquals("a unit that never reached the disk was given", false, consume(impl, NOW))
        assertTrue("the unit is not written with commit(): ${failing.editorCalls}", "commit" in failing.editorCalls)
        assertFalse("apply" in failing.editorCalls)
    }

    @Test
    fun `the interface answers fail closed and names ten a day`() {
        // The interface's own default bodies (what a store double that does not answer gives).
        val defaults = Class.forName(ZchatPreferences::class.java.name + "\$DefaultImpls")
        val nobody =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, m, _ ->
                throw AssertionError("a default body called ${m.name}")
            } as ZchatPreferences

        fun default(
            name: String,
            vararg args: Any?,
        ): Any? {
            val m =
                defaults.methods.firstOrNull { it.name == name }
                    ?: throw AssertionError("ZchatPreferences.$name has no default body (or does not exist)")
            return m.invoke(null, nobody, *args)
        }
        assertEquals(0, default("getGroupKeyGrantPaidCount", grantKey))
        assertEquals("a store that does not answer lets a count be \"saved\"", false, default("setGroupKeyGrantPaidCount", grantKey, 1))
        assertEquals("a store that does not answer has budget", false, default("groupKeyGrantBudgetAvailable", NOW, MAX, WINDOW))
        assertEquals(false, default("tryConsumeGroupKeyGrantBudget", NOW, MAX, WINDOW))

        val constants = ZchatPreferences::class.java.declaredFields.associateBy { it.name }
        assertEquals(10, constants["MAX_GROUP_KEY_GRANT_PER_WINDOW"]?.get(null))
        assertEquals(86_400_000L, constants["GROUP_KEY_GRANT_BUDGET_WINDOW_MS"]?.get(null))
    }

    private companion object {
        const val MAX = 10
        const val WINDOW = 86_400_000L
        const val NOW = 1_800_000_000_000L
    }
}
