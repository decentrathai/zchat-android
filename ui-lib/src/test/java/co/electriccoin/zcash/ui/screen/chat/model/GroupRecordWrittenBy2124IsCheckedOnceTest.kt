package co.electriccoin.zcash.ui.screen.chat.model

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.LegacyGroupMoveFixture
import co.electriccoin.zcash.ui.screen.chat.datasource.LegacyGroupMoveFixture.Companion.MARK
import co.electriccoin.zcash.ui.screen.chat.datasource.LegacyGroupMoveFixture.Companion.frozenSerialize2124
import co.electriccoin.zcash.ui.screen.chat.datasource.LegacyGroupMoveFixture.Companion.record2124
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * KI-25 pair 3, ZC-598 (KI-25-SPEC 3.6b D1; section 4.3 class 18; skill compat-2124 rules 1 and 3) — a group record
 * written by 2.12.4 is looked at once, and 2.12.4 can still read what this build writes and use its own file beside the
 * mark.
 *
 * [Frozen2124]: `ZMSGGroupProtocol.serializeGroupInfo` and `deserializeGroupInfo` at the tag v2.12.4 (a1fb4136f),
 * `ZMSGGroupProtocol.kt@v2.12.4:681-692, 697-709` (the serializer is [frozenSerialize2124] of the move fixture), and
 * the four lines with which 2.12.4 uses its plain group file, `ZchatPreferences.kt@v2.12.4:2900` (save:
 * `groupInfoPrefs.edit().putString(groupId, groupInfoJson).apply()`), `:2908` (read: `getString(groupId, null)`),
 * `:2917` (remove: `edit().remove(groupId).apply()`) and `:2330` (the wipe: `edit().clear().commit()`); its file's name
 * at `:1564` (`GROUP_INFO_PREFS_NAME = "zchat_group_info"`).
 *
 * (a), (e), (f) fail at the base (no look, no mark, no such function); (b) and (d) pass and pin; (c) needs the move.
 * All addresses, keys and ids are synthetic.
 */
class GroupRecordWrittenBy2124IsCheckedOnceTest {
    /** Verbatim from v2.12.4 (a1fb4136f), its formatting kept; see the class KDoc for the files and lines. */
    @Suppress(
        "ktlint:standard:function-expression-body",
        "ktlint:standard:multiline-expression-wrapping",
        "ktlint:standard:function-signature",
        "TooGenericExceptionCaught",
    )
    private object Frozen2124 {
        fun deserializeGroupInfo(json: String): GroupInfo? {
            return try {
                val obj = JSONObject(json)
                GroupInfo(
                    groupId = obj.getString("group_id"),
                    name = obj.getString("name"),
                    creatorAddress = obj.getString("creator"),
                    createdAt = java.time.Instant.ofEpochMilli(obj.getLong("created_at")),
                    adminPolicy = AdminPolicy.valueOf(obj.optString("admin_policy", "CREATOR_ONLY")),
                    currentEpoch = obj.optInt("current_epoch", 0),
                    groupKey = if (obj.has("group_key")) obj.getString("group_key") else null,
                    isActive = obj.optBoolean("is_active", true)
                )
            } catch (e: Exception) {
                null
            }
        }

        fun save(file: SharedPreferences, groupId: String, groupInfoJson: String) {
            file.edit().putString(groupId, groupInfoJson).apply()
        }

        fun read(file: SharedPreferences, groupId: String): String? {
            return file.getString(groupId, null)
        }

        fun remove(file: SharedPreferences, groupId: String) {
            file.edit().remove(groupId).apply()
        }

        fun wipe(file: SharedPreferences) {
            file.edit().clear().commit()
        }

        const val GROUP_INFO_PREFS_NAME = "zchat_group_info"
    }

    /** The eight values 2.12.4 reads from a record. */
    private fun eight(info: GroupInfo?): List<Any?> =
        info?.let {
            listOf(it.groupId, it.name, it.creatorAddress, it.createdAt, it.adminPolicy, it.currentEpoch, it.groupKey, it.isActive)
        }
            ?: emptyList()

    @Test
    fun `a - a 2_12_4 record with a finished chat at its creator address gets creator_pub and still loads under 2_12_4`() {
        val f = LegacyGroupMoveFixture()
        val admin = f.finishedChat(X)
        val raw = record2124(G, X)
        val old = f.OldFile().apply { putRaw(G, raw) }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        val moved = store.diskSnapshot().getValue(G)
        val key = ZMSGGroupProtocol.deserializeGroupInfo(moved)?.creatorPublicKey
        assertEquals("the first-start move gave no creator key", admin.publicKey, key)
        val before = eight(Frozen2124.deserializeGroupInfo(raw))
        assertEquals("2.12.4 reads other values from the moved record", before, eight(Frozen2124.deserializeGroupInfo(moved)))
    }

    @Test
    fun `b - the record this build writes at a join loads under 2_12_4 with the same eight values`() {
        val info =
            GroupInfo(
                groupId = G,
                name = "Joined group",
                creatorAddress = X,
                createdAt = Instant.ofEpochMilli(LegacyGroupMoveFixture.T),
                currentEpoch = 3,
                groupKey = "c3ludGhldGljLWpvaW4ta2V5",
                isActive = true,
                creatorPublicKey = "BFsyntheticJoinCreatorKey",
            )
        val written = ZMSGGroupProtocol.serializeGroupInfo(info)
        assertEquals(eight(info.copy(creatorPublicKey = null)), eight(Frozen2124.deserializeGroupInfo(written)))
    }

    @Test
    fun `c - a string this build cannot deserialize is left byte for byte by the move`() {
        val f = LegacyGroupMoveFixture()
        val broken = "{\"group_id\":\"$G\"}"
        val old = f.OldFile().apply { putRaw(G, broken) }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        assertNull(ZMSGGroupProtocol.deserializeGroupInfo(broken))
        assertEquals(broken, store.diskSnapshot()[G])
    }

    @Test
    fun `d - 2_12_4's group file is today's old file, and the store this build reads has another name`() {
        fun constant(name: String): String =
            ZchatPreferencesImpl::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(null) as String
        assertEquals(Frozen2124.GROUP_INFO_PREFS_NAME, constant("GROUP_INFO_PREFS_NAME"))
        val enc = constant("GROUP_INFO_PREFS_NAME_ENC")
        assertNotEquals("2.12.4 could write the store this build reads", Frozen2124.GROUP_INFO_PREFS_NAME, enc)
    }

    @Test
    fun `e - with the mark in its file 2_12_4 saves, reads and removes by group id, never meets the mark, and its wipe removes it`() {
        val f = LegacyGroupMoveFixture()
        f.finishedChat(X)
        val old = f.OldFile().apply { putRaw(G, record2124(G, X)) }
        f.move(old, f.StoreFile().apply { refuseCommit = true }, storeIsNew = true)
        assertEquals("fixture: the first start left the mark in the old file", true, old.snapshot()[MARK])

        Frozen2124.save(old, G2, record2124(G2, X2))
        assertEquals(record2124(G2, X2), Frozen2124.read(old, G2))
        assertNull("2.12.4's read returned the mark", Frozen2124.read(old, MARK))
        Frozen2124.remove(old, G2)
        assertNull(Frozen2124.read(old, G2))
        assertEquals("2.12.4's writes removed the mark", true, old.snapshot()[MARK])

        Frozen2124.wipe(old)
        assertEquals("2.12.4's wipe left the mark", emptyMap<String, Any?>(), old.snapshot())
    }

    @Test
    fun `f - a downgrade and back - a group 2_12_4 writes after the first start comes across with no key`() {
        val f = LegacyGroupMoveFixture()
        val old = f.OldFile().apply { putRaw(G, record2124(G, X)) }
        val store = f.StoreFile()
        f.move(old, store, storeIsNew = true)
        assertTrue("fixture: the first start's mark was written", f.log.contains("old.commit:mark"))

        Frozen2124.save(old, G2, frozenSerialize2124(Frozen2124.deserializeGroupInfo(record2124(G2, X2))!!))
        f.finishedChat(X2)
        f.move(old, store, storeIsNew = false)

        assertEquals("a group 2.12.4 wrote after a downgrade was looked at", record2124(G2, X2), store.diskSnapshot()[G2])
    }

    private companion object {
        const val G = "zgrp_598d00000000000000000004"
        const val G2 = "zgrp_598e00000000000000000005"
        const val X = "usyntheticzc598compatadmin01"
        const val X2 = "usyntheticzc598compatadmin02"
    }
}
