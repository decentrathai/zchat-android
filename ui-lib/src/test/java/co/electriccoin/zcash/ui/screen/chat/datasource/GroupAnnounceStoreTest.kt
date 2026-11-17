package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceApi
import co.electriccoin.zcash.ui.screen.chat.model.MaBy
import co.electriccoin.zcash.ui.screen.chat.model.MaEntry
import co.electriccoin.zcash.ui.screen.chat.model.MaLedger
import co.electriccoin.zcash.ui.screen.chat.model.MaLedgerState
import co.electriccoin.zcash.ui.screen.chat.model.MaOp
import co.electriccoin.zcash.ui.screen.chat.model.MaPair
import co.electriccoin.zcash.ui.screen.chat.model.MaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Round 8 MA1 (MEMBER_ANNOUNCE_DESIGN.md v2 §5.0, §5.4, §6.0) — the announce records in ZchatPreferences.
 *
 * - The announce state, the ledger, the announce counter and the gp1 capability set live in the group-members store
 *   (encrypted, like the roster) under keys that can never be a group id, and each write is a `commit()`: after a
 *   kill, a fresh process reads them from the disk alone.
 * - `casGroupAnnounce` is a compare-and-set under the per-group lock (RELAY never overwrites CHAIN for the same op).
 * - The counter only grows.
 * - `deleteGroup` removes the ledger and the per-member state but keeps `hw` and `usedInviteIds`, so an old invite or
 *   announce replayed after the delete cannot bring the group back.
 * - `withGroupLock` is one lock per group, shared by every store in the process.
 *
 * The store is a real [ZchatPreferencesImpl] allocated without its constructor, over files whose memory and disk are
 * kept apart: `apply()` changes memory only, `commit()` writes the disk. All values are synthetic.
 */
class GroupAnnounceStoreTest {
    private val api = AnnounceApi

    /** A preferences file with a separate memory and disk. */
    private class SplitPrefs(
        val disk: MutableMap<String, Any> = ConcurrentHashMap(),
    ) : SharedPreferences {
        private val memory = ConcurrentHashMap<String, Any>(disk)

        override fun getAll(): MutableMap<String, *> = HashMap(memory)

        override fun getString(
            key: String,
            defValue: String?
        ): String? = memory[key] as String? ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = (memory[key] as Set<String>?)?.toMutableSet() ?: defValues

        override fun getInt(
            key: String,
            defValue: Int
        ): Int = memory[key] as Int? ?: defValue

        override fun getLong(
            key: String,
            defValue: Long
        ): Long = memory[key] as Long? ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float
        ): Float = memory[key] as Float? ?: defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean
        ): Boolean = memory[key] as Boolean? ?: defValue

        override fun contains(key: String): Boolean = memory.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = LinkedHashMap<String, Any?>()
            private var clear = false

            private fun put(
                key: String,
                value: Any?
            ) = apply { changes[key] = value }

            override fun putString(
                key: String,
                value: String?
            ) = put(key, value)

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ) = put(key, values?.toSet())

            override fun putInt(
                key: String,
                value: Int
            ) = put(key, value)

            override fun putLong(
                key: String,
                value: Long
            ) = put(key, value)

            override fun putFloat(
                key: String,
                value: Float
            ) = put(key, value)

            override fun putBoolean(
                key: String,
                value: Boolean
            ) = put(key, value)

            override fun remove(key: String) = put(key, null)

            override fun clear() = apply { clear = true }

            private fun into(target: MutableMap<String, Any>) {
                if (clear) target.clear()
                changes.forEach { (k, v) -> if (v == null) target.remove(k) else target[k] = v }
            }

            override fun commit(): Boolean {
                into(memory)
                into(disk)
                return true
            }

            override fun apply() {
                into(memory)
            }
        }
    }

    /** The disks of one install: a "kill" is a new process built from these alone. */
    private class Disks {
        val members: MutableMap<String, Any> = ConcurrentHashMap()
        val other: Map<String, MutableMap<String, Any>> = FIELDS.associateWith { ConcurrentHashMap<String, Any>() }
    }

    private fun process(disks: Disks): ZchatPreferencesImpl {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val impl =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl

        fun set(
            field: String,
            prefs: SharedPreferences
        ) = ZchatPreferencesImpl::class.java
            .getDeclaredField(field)
            .apply { isAccessible = true }
            .set(impl, prefs)
        set("groupMembersPrefs", SplitPrefs(disks.members))
        FIELDS.forEach { set(it, SplitPrefs(disks.other.getValue(it))) }
        return impl
    }

    private val state =
        MaState(
            hw = 1_790_000_000_900L,
            rosterAnnounced = true,
            selfJoinN = 1_790_000_000_100L,
            selfJoinEpoch = 2,
            inactiveSince = null,
            usedInviteIds = setOf("txid-gi-1", "event-gi-2"),
            members =
                mapOf(
                    M1 to
                        MaEntry(
                            n = 1_790_000_000_900L,
                            op = MaOp.A,
                            epoch = 2,
                            activatedAtMs = 1_790_000_000_900L,
                            activatedBy = MaBy.AUTH
                        ),
                ),
        )
    private val pair = MaPair(M2, M2, "hash-m2", M1, M1, "hash-m1", MaOp.A, 1_790_000_000_900L, 2)

    private fun led(state: MaLedgerState) = MaLedger("hash-m2", M2, "hash-m1", M1, MaOp.A, state, emptyList(), 7)

    @Test
    fun `the announce state is written with commit and read back after a kill`() {
        val disks = Disks()
        val zp = process(disks)
        assertEquals("nothing stored reads as the empty state", MaState(), api.getGroupAnnounceState(zp, GID))
        assertTrue(api.saveGroupAnnounceState(zp, GID, state))
        assertEquals(state, api.getGroupAnnounceState(process(disks), GID))
        assertFalse("never stored under the bare group id, where the roster lives", disks.members.containsKey(GID))
        assertEquals(MaState(), api.getGroupAnnounceState(process(disks), OTHER_GID))
    }

    @Test
    fun `the ledger compare-and-set is durable and refuses RELAY over CHAIN`() {
        val disks = Disks()
        val zp = process(disks)
        assertTrue(api.casGroupAnnounce(zp, GID, pair, null, led(MaLedgerState.CHAIN_PENDING)))
        assertFalse("stale expectation", api.casGroupAnnounce(zp, GID, pair, null, led(MaLedgerState.CHAIN_SENT)))
        assertTrue(api.casGroupAnnounce(zp, GID, pair, led(MaLedgerState.CHAIN_PENDING), led(MaLedgerState.CHAIN_SENT)))
        assertFalse(
            "RELAY never overwrites CHAIN",
            api.casGroupAnnounce(zp, GID, pair, led(MaLedgerState.CHAIN_SENT), led(MaLedgerState.RELAY_FINAL)),
        )
        assertEquals(listOf(led(MaLedgerState.CHAIN_SENT)), api.getGroupAnnounceLedger(process(disks), GID))
        assertEquals(emptyList<MaLedger>(), api.getGroupAnnounceLedger(process(disks), OTHER_GID))
    }

    @Test
    fun `the announce counter only grows and survives a kill`() {
        val disks = Disks()
        val zp = process(disks)
        assertEquals(0L, api.getGroupAnnounceCounter(zp, GID))
        assertTrue(api.raiseGroupAnnounceCounter(zp, GID, 1_790_000_000_500L))
        assertTrue("a lower value is ignored, not an error", api.raiseGroupAnnounceCounter(zp, GID, 1_790_000_000_100L))
        assertEquals(1_790_000_000_500L, api.getGroupAnnounceCounter(process(disks), GID))
    }

    @Test
    fun `the gp1 capability set remembers addresses and npubs across a kill`() {
        val disks = Disks()
        val zp = process(disks)
        assertFalse(api.isGp1Capable(zp, M1))
        assertTrue(api.markGp1Capable(zp, setOf(M1, NPUB)))
        assertTrue(api.markGp1Capable(zp, setOf(M2)))
        val restarted = process(disks)
        for (id in listOf(M1, NPUB, M2)) assertTrue(id, api.isGp1Capable(restarted, id))
        assertFalse(api.isGp1Capable(restarted, "u1" + "z".repeat(60)))
    }

    @Test
    fun `deleteGroup clears the ledger and the per-member state but keeps hw and usedInviteIds`() {
        val disks = Disks()
        val zp = process(disks)
        assertTrue(api.saveGroupAnnounceState(zp, GID, state))
        assertTrue(api.casGroupAnnounce(zp, GID, pair, null, led(MaLedgerState.CHAIN_SENT)))
        assertTrue(api.raiseGroupAnnounceCounter(zp, GID, 1_790_000_000_950L))
        assertTrue(api.saveGroupAnnounceState(zp, OTHER_GID, state))

        zp.deleteGroup(GID)

        val restarted = process(disks)
        assertEquals(
            "the tombstone: hw and the used invite ids only",
            MaState(hw = state.hw, usedInviteIds = state.usedInviteIds),
            api.getGroupAnnounceState(restarted, GID),
        )
        assertEquals(emptyList<MaLedger>(), api.getGroupAnnounceLedger(restarted, GID))
        assertEquals("the counter only grows, even across a delete", 1_790_000_000_950L, api.getGroupAnnounceCounter(restarted, GID))
        assertEquals("another group is untouched", state, api.getGroupAnnounceState(restarted, OTHER_GID))
    }

    /**
     * ZC-294 (r8-findings SPECS §9.3): an invite seen while this phone was still in the group is not in the used ids any
     * more, so the tombstone keeps those ids too, or a replay of such an invite would bring a deleted group back. The
     * re-invite kept for a later removal goes with the group. Written and read by JSON key name.
     */
    @Test
    fun `deleteGroup keeps the ids of invites seen while the group was active and drops the kept re-invite`() {
        val disks = Disks()
        val zp = process(disks)
        val invite = org.json.JSONObject()
        invite.put("name", "Synthetic group")
        invite.put("inviter", M1)
        val keptInvite = org.json.JSONObject()
        keptInvite.put("id", "event-gi-3")
        keptInvite.put("source_ms", 1_790_000_020_000L)
        keptInvite.put("invite", invite.toString())
        keptInvite.put("sender", M1)
        val seenIds = org.json.JSONArray()
        seenIds.put("event-gi-3")
        seenIds.put("txid-gi-4")
        val json =
            org.json
                .JSONObject(api.announceStateToJson(state))
                .put("seen_active_invite_ids", seenIds)
                .put("pending_reinvite", keptInvite)
                .toString()
        assertTrue(api.saveGroupAnnounceState(zp, GID, api.announceStateFromJson(json)))

        zp.deleteGroup(GID)

        val tombstone = org.json.JSONObject(api.announceStateToJson(api.getGroupAnnounceState(process(disks), GID)))
        val seen = tombstone.optJSONArray("seen_active_invite_ids")
        assertEquals(
            "the tombstone lost the ids of invites seen while the group was active",
            setOf("event-gi-3", "txid-gi-4"),
            seen?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
        )
        assertFalse("the tombstone kept a re-invite for a deleted group", tombstone.has("pending_reinvite"))
        assertEquals("the tombstone lost hw", state.hw, tombstone.optLong("hw"))
        assertEquals(
            "the tombstone lost the used invite ids",
            state.usedInviteIds,
            api.getGroupAnnounceState(process(disks), GID).usedInviteIds,
        )
        assertEquals("the tombstone kept per-member state", 0, tombstone.optJSONObject("members")?.length() ?: 0)
    }

    @Test
    fun `withGroupLock is one lock per group across stores and lets other groups run`() {
        val disks = Disks()
        val a = process(disks)
        val b = process(disks)
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val threads =
            (0 until 8).map { i ->
                thread {
                    repeat(200) {
                        api.withGroupLock(if (i % 2 == 0) a else b, GID) {
                            val now = inside.incrementAndGet()
                            maxInside.accumulateAndGet(now) { x, y -> maxOf(x, y) }
                            Thread.yield()
                            inside.decrementAndGet()
                        }
                    }
                }
            }
        threads.forEach { it.join(10_000) }
        assertEquals("never two holders of one group's lock", 1, maxInside.get())

        // A different group is not blocked by a holder of this one.
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            thread {
                api.withGroupLock(a, GID) {
                    held.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        assertTrue(held.await(10, TimeUnit.SECONDS))
        assertEquals("other group", api.withGroupLock(b, OTHER_GID) { "other group" })
        assertEquals("the lock is re-entrant", 5, api.withGroupLock(b, OTHER_GID) { api.withGroupLock(b, OTHER_GID) { 5 } })
        release.countDown()
        holder.join(10_000)
    }

    private companion object {
        const val GID = "zgrp_0123456789abcdef01234567"
        const val OTHER_GID = "zgrp_89abcdef0123456789abcdef"
        val M1 = "u1" + "m".repeat(60)
        val M2 = "u1" + "n".repeat(60)
        val NPUB = "ab".repeat(32)

        /** The other files deleteGroup writes to. */
        val FIELDS =
            listOf("prefs", "groupInfoPrefs", "heldGroupAcceptPrefs", "modePrefs", "groupDraftPrefs", "groupSeqPrefs", "groupKeysPrefs")
    }
}
