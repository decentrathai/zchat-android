package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * KI-25 pair 3, ZC-598 (KI-25-SPEC 3.6b D2, D4; section 4.3 class 16) — after this build a creator key is written by a
 * join (from the `k2` session), by the one look ([GroupAdminCheck.firstLook]) and by the confirmation, and by nothing
 * else; the group record has no new field.
 *
 * - Source scan of the main sources: `GroupInfo(` is constructed in exactly four functions
 *   (`ZMSGGroupProtocol.deserializeGroupInfo`, `ChatViewModel.processGroupInviteLocked`,
 *   `ChatViewModel.rebuildGroupFromOwnInvite`, `GroupViewModel.createGroup`); outside the deserializer a
 *   `creatorPublicKey =` that is not null is assigned by exactly three functions: `processGroupInviteLocked` (with the
 *   `k2` capture), `firstLook` and `confirmGroupAdmin`.
 * - The record has no new field: a group serialized by this build has exactly the keys 2.12.4 wrote plus `creator_pub`,
 *   and `GroupModels.kt` and `ZMSGGroupProtocol.kt` contain no `admin_checked`.
 * - The join cases (a plaintext relay join with a key, or a finished chat, already at the inviter's address stores no
 *   creator key) are class 15 (c); the `k2` capture is pinned by the existing join tests.
 *
 * At the base the scan fails: the snapshot in `effectiveCreatorPublicKey` assigns a key and there is no `firstLook`.
 */
class CreatorKeyIsWrittenOnlyByJoinLookAndConfirmTest {
    private fun mainSources(): Map<String, String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        val root = File(dir ?: error("repo root not found"), "ui-lib/src/main/java")
        return root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.associate { it.name to it.readText() }
    }

    /** The name of the function whose body holds [index] in [text] (the last `fun name(` before it). */
    private fun enclosing(
        text: String,
        index: Int,
    ): String =
        Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
            .findAll(text.substring(0, index))
            .lastOrNull()
            ?.groupValues
            ?.get(1) ?: "<top>"

    /** True when [index] in [text] is inside a comment line (`//` before it on its line, or a KDoc line). */
    private fun inComment(
        text: String,
        index: Int,
    ): Boolean {
        val line = text.substring(text.lastIndexOf('\n', index - 1) + 1, index)
        return line.contains("//") || line.trimStart().startsWith("*")
    }

    @Test
    fun `source - GroupInfo is constructed in exactly four functions`() {
        val sites = sortedSetOf<String>()
        for ((file, text) in mainSources()) {
            for (m in Regex("""(?<![\w.])GroupInfo\(""").findAll(text)) {
                if (inComment(text, m.range.first)) continue
                if (text.substring(maxOf(0, m.range.first - 11), m.range.first).contains("data class")) continue
                sites += "$file:${enclosing(text, m.range.first)}"
            }
        }
        assertEquals(
            sortedSetOf(
                "ZMSGGroupProtocol.kt:deserializeGroupInfo",
                "ChatViewModel.kt:processGroupInviteLocked",
                "ChatViewModel.kt:rebuildGroupFromOwnInvite",
                "GroupViewModel.kt:createGroup",
            ),
            sites,
        )
    }

    @Test
    fun `source - a creator key is assigned only by the join's k2 capture, the one look and the confirmation`() {
        val sites = sortedSetOf<String>()
        for ((file, text) in mainSources()) {
            for (m in Regex("""\bcreatorPublicKey\s*=(?!=)\s*([^,\n)]+)""").findAll(text)) {
                if (inComment(text, m.range.first)) continue
                val value = m.groupValues[1].substringBefore("//").trim()
                if (value == "null") continue
                val function = enclosing(text, m.range.first)
                if (file == "ZMSGGroupProtocol.kt" && function == "deserializeGroupInfo") continue
                sites += "$file:$function=$value"
            }
        }
        val functions = sites.map { it.substringBefore("=") }.toSortedSet()
        assertEquals(
            "a creator key is written somewhere else: $sites",
            sortedSetOf("ChatViewModel.kt:processGroupInviteLocked", "GroupAdminCheck.kt:firstLook", "ChatViewModel.kt:confirmGroupAdmin"),
            functions,
        )
        val join = sites.filter { it.startsWith("ChatViewModel.kt:processGroupInviteLocked=") }.map { it.substringAfter("=") }
        assertEquals("the join assigns something other than its k2 capture", listOf("creatorPub"), join)
    }

    @Test
    fun `the record has no new field`() {
        val info =
            GroupInfo(
                groupId = "zgrp_598f00000000000000000006",
                name = "Golden",
                creatorAddress = "usyntheticzc598golden000001",
                createdAt = Instant.ofEpochMilli(1_758_412_800_000L),
                currentEpoch = 2,
                groupKey = "a2V5",
                isActive = true,
                creatorPublicKey = "BFsyntheticGoldenCreatorKey",
            )
        val keys = JSONObject(ZMSGGroupProtocol.serializeGroupInfo(info)).keys().asSequence().toSortedSet()
        assertEquals(
            sortedSetOf(
                "group_id",
                "name",
                "creator",
                "created_at",
                "admin_policy",
                "current_epoch",
                "group_key",
                "is_active",
                "creator_pub",
            ),
            keys,
        )
        val sources = mainSources()
        for (file in listOf("GroupModels.kt", "ZMSGGroupProtocol.kt")) {
            assertTrue("$file names admin_checked", sources.getValue(file).let { !it.contains("admin_checked") })
        }
    }
}
