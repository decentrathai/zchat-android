package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheckPhone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * KI-25 pair 1b, ZC-704 (KI-25-SPEC 3.6b R19; section 4.1b class 23) — the pure rules of the list a group keeps of
 * its admin's changes that reached the phone over the relays while it could not confirm the admin: the bounds and
 * which entry leaves, the order of a run, the three-failed-runs rule, the entry kept as accepted, which entries are
 * due at the end of a pass, the stored form, what an outcome keeps, the choice between two invites, and "began after
 * the confirmation".
 *
 * **The new operations are reached by name through reflection**, so this file compiles on the commit before the fix;
 * a missing one fails the test with "no such operation". A function is found by its name and its parameter count.
 * What the fix must provide, as the spec names it or, where the spec gives no name, as chosen here ("chosen"):
 *
 * `GroupMemberAnnounce.kt`: `KeptAdminChange` and `AnnounceState.keptAdminChanges` with the JSON form the spec gives
 * (`kept_admin_changes`; `id`, `t`, `e`, `p`, `from`, `by`, `at`, `kept`, `h`, `f`, `acc`); the property names of
 * `KeptAdminChange` are listed in AdminCheckFixture.kt. Entries are built here only through `announceStateFromJson`.
 *
 * `GroupAdminCheck.kt`:
 * - `AdminChangeOutcome`, a sealed class whose six nested outcomes are (chosen names) the objects `NotParsed`,
 *   `Failed`, `NotSaved`, `NotNewer`, `Applied` and the class `RefusedByGate(epoch: Int, signed: Boolean,
 *   signerChat: String?)`;
 * - `GroupAdminCheck.finishedChatKey(prefs, address): String?` (the spec's name): the contact key of a finished chat
 *   (a contact key, our own key pair, no request waiting for Accept), else null;
 * - (chosen) `signerChatRank(prefs, info: GroupInfo, address: String): Int?`: 0 = the chat at the group's stored admin
 *   address that holds a contact key and is not a request waiting for Accept; 1 = a finished chat whose safety number
 *   the user compared; 2 = any other finished chat; null = no rank;
 * - (chosen) `withKeptChange(kept: List<KeptAdminChange>, incoming: KeptAdminChange, rankOf: (String) -> Int?,
 *   nowMs: Long): List<KeptAdminChange>`: the bounds with the leave rule, as one function. The list after [incoming]
 *   was offered at [nowMs]: entries kept more than 90 days before leave; a second entry of one event id, of one signer
 *   chat, kind and epoch (kept as refused), or of one kind and epoch kept as accepted, is not kept; per signer chat
 *   the eight removals and the eight key changes with the highest epochs and one invite (ZC-668's rule) among the
 *   entries kept as refused; 48 entries per group with the leave rule; `rankOf` gives a signer chat's rank;
 * - (chosen) `orderedKeptChanges(kept): List<KeptAdminChange>`: epoch, removal before key change, source time, id;
 * - (chosen) `keptChangesBeforeRun(kept, nowMs: Long): List<KeptAdminChange>`: step 1 of the hook (entries past the
 *   age limit and entries that failed in three runs leave);
 * - (chosen) `keptChangeAfterRun(entry: KeptAdminChange, outcome: AdminChangeOutcome): KeptAdminChange?`: null when
 *   the entry leaves the list (applied, not newer, refused by the gate, not parsed); the entry with its count raised
 *   by one after `Failed`; the entry unchanged after `NotSaved`;
 * - (chosen) `dueKeptChanges(kept, state: AdminKeyState, groupActive: Boolean, ownGroup: Boolean,
 *   passComplete: Boolean, beganAfterConfirmation: Boolean): List<KeptAdminChange>`: the removals and key changes due
 *   at the end of a pass, in the order of the run (an invite is never run);
 * - (chosen) `keepAs(outcome: AdminChangeOutcome, eventId: String?, sealSender: String?): KeepAs` with
 *   `enum class KeepAs { NOT_KEPT, AS_REFUSED, AS_ACCEPTED }`;
 * - (chosen) `inviteReplaces(newKeyEpoch: Int, newAtMs: Long, keptKeyEpoch: Int, keptAtMs: Long): Boolean`;
 * - (chosen) `passBeganAfterConfirmation(passNumber: Long, confirmedAtPass: Long?): Boolean`.
 *
 * On the commit before the fix only the frozen-writer case passes (a state with nothing kept is written as before).
 *
 * All addresses, keys, ids and times are synthetic; the clock is an argument.
 */
class KeptAdminChangesRulesTest {
    // ------------------------------------------------------------ reflection

    private fun missing(what: String): Nothing = AdminCheck.missingKept(what)

    private fun rule(
        name: String,
        signature: String,
        vararg args: Any?,
    ): Any? {
        val method =
            GroupAdminCheck::class.java.declaredMethods
                .filter { !it.isSynthetic && (it.name == name || it.name.startsWith("$name$")) }
                .firstOrNull { it.parameterCount == args.size }
                ?: missing("GroupAdminCheck.$signature")
        method.isAccessible = true
        return try {
            method.invoke(GroupAdminCheck, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun kept(state: AnnounceState): List<Any> {
        val getter =
            AnnounceState::class.java.methods.firstOrNull { it.name == "getKeptAdminChanges" && it.parameterCount == 0 }
                ?: missing("AnnounceState.keptAdminChanges: List<KeptAdminChange> (JSON `kept_admin_changes`)")
        @Suppress("UNCHECKED_CAST")
        return getter.invoke(state) as List<Any>
    }

    private fun prop(
        entry: Any,
        name: String,
    ): Any? = AdminCheck.property(entry, name)

    private fun outcome(
        name: String,
        vararg args: Any?,
    ): Any {
        val type =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.AdminChangeOutcome") }.getOrNull()
                ?: missing("AdminChangeOutcome (the six outcomes of processGroupKick and processGroupKey)")
        val nested = type.declaredClasses.firstOrNull { it.simpleName == name } ?: missing("AdminChangeOutcome.$name")
        if (args.isEmpty()) return nested.getDeclaredField("INSTANCE").get(null) ?: missing("AdminChangeOutcome.$name (an object)")
        val constructor =
            nested.declaredConstructors.firstOrNull { !it.isSynthetic && it.parameterCount == args.size }
                ?: missing("AdminChangeOutcome.$name(epoch: Int, signed: Boolean, signerChat: String?)")
        constructor.isAccessible = true
        return constructor.newInstance(*args)
    }

    // ----------------------------------------------------------- the entries

    private fun state(vararg entries: JSONObject): AnnounceState =
        announceStateFromJson(JSONObject().put(KEY, JSONArray(entries.toList())).toString())

    /** [entries] as `KeptAdminChange`s, read by the store's own reader; every one of them must be read. */
    private fun entries(vararg entries: JSONObject): List<Any> =
        kept(state(*entries)).also { assertEquals("fixture: the reader did not take every entry", entries.size, it.size) }

    private fun one(entry: JSONObject): Any = entries(entry).single()

    @Suppress("LongParameterList")
    private fun e(
        id: String,
        kind: String = "GK",
        epoch: Int = 2,
        by: String = A,
        at: Long = NOW - HOUR,
        kept: Long = NOW - HOUR,
        failed: Int = 0,
        accepted: Boolean = false,
        from: String = by,
        payload: String = """{"synthetic":"$id"}""",
    ): JSONObject = AdminCheck.keptEntry(id, kind, epoch, payload, from, at, kept, by, null, failed, accepted)

    private fun ids(list: Any?): List<String> = (list as List<*>).map { prop(it!!, "id") as String }

    private fun offer(
        kept: List<Any>,
        incoming: Any,
        now: Long = NOW,
        ranks: (String) -> Int? = { 2 },
    ): List<String> =
        ids(
            rule(
                "withKeptChange",
                "withKeptChange(kept: List<KeptAdminChange>, incoming: KeptAdminChange, rankOf: (String) -> Int?, nowMs: Long)",
                kept,
                incoming,
                ranks,
                now,
            ),
        )

    /** Eight removals (`GK`) and eight key changes (`GY`) of [chat] at the epochs [epochs], kept at [keptAt]. */
    private fun sixteen(
        chat: String,
        keptAt: Long = NOW - HOUR,
        removals: IntRange = 2..9,
        keys: IntRange = 2..9,
    ): List<JSONObject> =
        removals.map { e("$chat-GK-$it", "GK", it, chat, kept = keptAt) } + keys.map { e("$chat-GY-$it", "GY", it, chat, kept = keptAt) }

    // ------------------------------------------------------------ the bounds

    @Test
    fun `bounds - a ninth removal of one chat pushes its lowest epoch out and key changes are counted apart`() {
        val removals = (2..9).map { e("gk-$it", "GK", it) }
        val keys = (2..9).map { e("gy-$it", "GY", it) }

        val afterNinth = offer(entries(*removals.toTypedArray()), one(e("gk-10", "GK", 10)))
        assertEquals("eight removals do not stay", 8, afterNinth.size)
        assertFalse("the lowest epoch did not leave", "gk-2" in afterNinth)
        assertTrue("the ninth removal was not kept", "gk-10" in afterNinth)

        val lowest = offer(entries(*removals.toTypedArray()), one(e("gk-1", "GK", 1)))
        assertEquals("a ninth removal with the lowest epoch of all was kept", (2..9).map { "gk-$it" }.toSet(), lowest.toSet())

        val both = offer(entries(*(removals + keys).toTypedArray()), one(e("gy-10", "GY", 10)))
        assertEquals(
            "a ninth key change pushed a removal out",
            (2..9).map { "gk-$it" }.toSet(),
            both.filter { it.startsWith("gk") }.toSet(),
        )
        assertEquals((3..10).map { "gy-$it" }.toSet(), both.filter { it.startsWith("gy") }.toSet())

        val otherChat = offer(entries(*removals.toTypedArray()), one(e("b-gk-1", "GK", 1, by = B)))
        assertEquals("another chat's removal was counted in this chat's eight", 9, otherChat.size)
    }

    @Test
    fun `bounds - a second change of one chat, kind and epoch, or of one event id, is not kept`() {
        val held = entries(e("first", "GK", 5))

        assertEquals(listOf("first"), offer(held, one(e("second", "GK", 5))))
        assertEquals(listOf("first"), offer(held, one(e("first", "GY", 6))))
        assertEquals("a key change at the same epoch is another kind", setOf("first", "gy"), offer(held, one(e("gy", "GY", 5))).toSet())
        assertEquals("another chat's change at that epoch is kept", setOf("first", "b"), offer(held, one(e("b", "GK", 5, by = B))).toSet())
    }

    // ZC-952 (decision of 13 Oct night, W-57): at a full list a change of an unverified chat is no longer shut out by
    // unverified chats of its own rank; it takes the oldest entry of another of them. The last lines here pinned the
    // old rule ("a chat of the same rank took a place in a full list") and now pin the new one.
    @Test
    fun `bounds - a full list gives a place to a higher rank, and to an unverified chat that finds only other unverified chats`() {
        val full = entries(*(sixteen(A) + sixteen(B) + sixteen(C)).toTypedArray())
        assertEquals("fixture: the 48 places are taken", 48, full.size)
        val ranks = { chat: String ->
            when (chat) {
                ADMIN_CHAT -> 0
                VERIFIED -> 1
                else -> 2
            }
        }

        val admin = offer(full, one(e("admin-change", by = ADMIN_CHAT)), ranks = ranks)
        assertEquals(48, admin.size)
        assertTrue("a change of the chat at the stored admin address was shut out of a full list", "admin-change" in admin)

        val verified = offer(full, one(e("verified-change", by = VERIFIED)), ranks = ranks)
        assertEquals(48, verified.size)
        assertTrue("a change of a verified finished chat was shut out by unverified ones", "verified-change" in verified)

        val fourth = offer(full, one(e("fourth-change", by = D)), ranks = ranks)
        assertEquals(48, fourth.size)
        assertTrue("ZC-952: a change of an unverified chat was shut out by three other unverified chats", "fourth-change" in fourth)
        assertEquals("ZC-952: more than one entry gave its place up", 47, fourth.count { it in ids(full) })
    }

    @Test
    fun `bounds - ZC-952 - the place is the oldest entry of the other unverified chat that holds the most entries`() {
        // A holds 15, B 17 (with its invite), C 16; B's oldest entry is its removal at epoch 5.
        val a = sixteen(A, keptAt = NOW - 30 * DAY).drop(1)
        val b =
            sixteen(B, keptAt = NOW - 10 * DAY).map { if (it.getString("id") == "$B-GK-5") it.put("kept", NOW - 40 * DAY) else it } +
                e("$B-GI", "GI", 9, B, kept = NOW - 10 * DAY)
        val c = sixteen(C, keptAt = NOW - 20 * DAY)
        val full = entries(*(a + b + c).toTypedArray())
        assertEquals("fixture: the 48 places are taken", 48, full.size)

        val after = offer(full, one(e("d-change", by = D)))

        assertTrue("ZC-952: the change of an unverified chat was not kept", "d-change" in after)
        assertEquals(
            "ZC-952: the place was not the oldest entry of the chat with the most entries",
            setOf("$B-GK-5"),
            ids(full).toSet() - after.toSet(),
        )
    }

    @Test
    fun `bounds - ZC-952 - an entry of a verified chat never gives its place to an unverified chat`() {
        val ranks = { chat: String -> if (chat == VERIFIED || chat == V2 || chat == V3) 1 else 2 }
        // The verified chat's entries are the oldest of all and its chat holds as many as the others.
        val withVerified = entries(*(sixteen(VERIFIED, keptAt = NOW - 60 * DAY) + sixteen(A) + sixteen(B)).toTypedArray())
        val after = offer(withVerified, one(e("d-change", by = D)), ranks = ranks)
        assertTrue("ZC-952: the change of an unverified chat was not kept", "d-change" in after)
        assertTrue(
            "ZC-952: an entry of a verified chat gave its place up",
            ids(withVerified).filter { it.startsWith(VERIFIED) }.all { it in after },
        )

        // Only verified chats in the list: an unverified chat's change, and another verified chat's, are not kept.
        val allVerified = entries(*(sixteen(VERIFIED) + sixteen(V2) + sixteen(V3)).toTypedArray())
        assertEquals(
            "an unverified chat pushed a verified chat out",
            ids(allVerified).toSet(),
            offer(allVerified, one(e("d", by = D)), ranks = ranks).toSet(),
        )
        val v4 = offer(allVerified, one(e("v4", by = V4)), ranks = { chat: String -> if (chat == D) 2 else 1 })
        assertEquals("a verified chat pushed another verified chat out", ids(allVerified).toSet(), v4.toSet())
    }

    @Test
    fun `bounds - ZC-952 - a chat never takes a place from itself and never holds more than 17`() {
        val full = entries(*(sixteen(A) + sixteen(B) + sixteen(C)).toTypedArray())

        val withInvite = offer(full, one(e("$A-GI", "GI", 9, A, kept = NOW)))
        assertTrue("ZC-952: the invite of a chat that holds sixteen entries was not kept", "$A-GI" in withInvite)
        assertEquals("ZC-952: the chat took the place from itself", 17, withInvite.count { it.startsWith(A) })
        assertEquals(48, withInvite.size)

        val seventeen = entries(*(sixteen(A) + e("$A-GI", "GI", 9, A) + sixteen(B) + sixteen(C).drop(1)).toTypedArray())
        val ninthRemoval = offer(seventeen, one(e("$A-GK-10", "GK", 10, A)))
        assertEquals("a chat holds more than 17 entries", 17, ninthRemoval.count { it.startsWith(A) })
        assertFalse("its own lowest removal did not leave", "$A-GK-2" in ninthRemoval)
        assertEquals(
            "another chat gave a place to a chat at its own bound",
            ids(seventeen).filterNot { it.startsWith(A) }.toSet(),
            ninthRemoval.filterNot { it.startsWith(A) }.toSet(),
        )
    }

    @Test
    fun `bounds - the place is taken from the lowest rank, the chat kept longest ago, its invite first, then its lowest epoch`() {
        val ranks = { chat: String ->
            when (chat) {
                ADMIN_CHAT -> 0
                VERIFIED -> 1
                else -> 2
            }
        }
        // The verified chat's entries are the oldest of all; of the two unverified chats A's newest is older than B's.
        val verified = sixteen(VERIFIED, keptAt = NOW - 30 * DAY)
        val a = sixteen(A, keptAt = NOW - 20 * DAY, removals = 3..10)
        val b = sixteen(B, keptAt = NOW - 10 * DAY)
        val full = entries(*(verified + a + b).toTypedArray())

        val lostEpoch = ids(full).toSet() - offer(full, one(e("admin-change", by = ADMIN_CHAT)), ranks = ranks).toSet()
        assertEquals(
            "the place was not taken from the lowest-ranking chat kept longest ago, at its lowest epoch",
            setOf("$A-GY-2"),
            lostEpoch,
        )

        // The same with an invite of A in the list: the invite goes first.
        val withInvite = entries(*(verified + a.drop(1) + e("$A-GI", "GI", 9, A, kept = NOW - 20 * DAY) + b).toTypedArray())
        assertEquals(48, withInvite.size)
        val lostInvite = ids(withInvite).toSet() - offer(withInvite, one(e("admin-change", by = ADMIN_CHAT)), ranks = ranks).toSet()
        assertEquals("the chat that loses a place did not lose its invite first", setOf("$A-GI"), lostInvite)
    }

    @Test
    fun `bounds - a chat with no rank is not kept and the ranks are admin address, verified, finished`() {
        val held = entries(e("first"))
        assertEquals(
            "a change of a chat with no rank (a request waiting for Accept at the admin's address) was kept",
            listOf("first"),
            offer(held, one(e("no-rank", by = ADMIN_CHAT)), ranks = { if (it == ADMIN_CHAT) null else 2 }),
        )

        val p = AdminCheck.driftedPhone()
        val info = p.base.info(AdminCheck.GID)

        fun rank(address: String): Any? =
            rule("signerChatRank", "signerChatRank(prefs, info: GroupInfo, address: String): Int?", p.prefs, info, address)
        assertEquals("a finished chat has rank 2", 2, rank(AdminCheck.Y))
        p.verified += AdminCheck.Y
        assertEquals("a finished chat whose safety number was compared has rank 1", 1, rank(AdminCheck.Y))
        assertNull("an address with no chat has a rank", rank(AdminCheck.STRANGER))
        p.base.link(AdminCheck.X_OLD)
        assertEquals("the chat at the stored admin address has rank 0", 0, rank(AdminCheck.X_OLD))
        p.base.awaitingAccept[AdminCheck.X_OLD] = "synthetic-waiting-kex"
        assertNull("a request waiting for Accept at the admin's address has a rank", rank(AdminCheck.X_OLD))
        p.base.awaitingAccept[AdminCheck.Y] = "synthetic-waiting-kex"
        assertNull("a request waiting for Accept has a rank", rank(AdminCheck.Y))
    }

    @Test
    fun `bounds - an entry kept 91 days ago is gone at the next keep and one kept 89 days ago stays`() {
        val held = entries(e("old", "GK", 2, kept = NOW - 91 * DAY), e("recent", "GK", 3, kept = NOW - 89 * DAY))

        assertEquals(setOf("recent", "incoming"), offer(held, one(e("incoming", "GK", 4, kept = NOW))).toSet())
    }

    // -------------------------------------------------------------- the order

    @Test
    fun `order - stored changes sort by epoch, removal before key change, source time, id`() {
        val shuffled =
            entries(
                e("key-3", "GY", 3),
                e("kick-2-late-b", "GK", 2, by = B, at = NOW - 5),
                e("kick-2-late-a", "GK", 2, by = C, at = NOW - 5),
                e("kick-3", "GK", 3),
                e("key-2", "GY", 2),
                e("kick-2-early", "GK", 2, at = NOW - 9),
            )

        assertEquals(
            listOf("kick-2-early", "kick-2-late-a", "kick-2-late-b", "key-2", "kick-3", "key-3"),
            ids(rule("orderedKeptChanges", "orderedKeptChanges(kept: List<KeptAdminChange>)", shuffled)),
        )
    }

    // ------------------------------------------------------- three failed runs

    @Test
    fun `failed runs - an entry with a count of 3 leaves before the run, one with 2 is tried, and age counts there too`() {
        val held =
            entries(
                e("failed-3", "GK", 2, failed = 3),
                e("failed-2", "GK", 3, failed = 2),
                e("aged", "GK", 4, kept = NOW - 91 * DAY),
            )

        assertEquals(
            listOf("failed-2"),
            ids(rule("keptChangesBeforeRun", "keptChangesBeforeRun(kept: List<KeptAdminChange>, nowMs: Long)", held, NOW)),
        )
    }

    @Test
    fun `failed runs - only a handler that threw raises the count and not saved raises none`() {
        val entry = one(e("entry", "GK", 2, failed = 1))

        fun after(outcome: Any): Any? =
            rule("keptChangeAfterRun", "keptChangeAfterRun(entry, outcome: AdminChangeOutcome): KeptAdminChange?", entry, outcome)

        assertEquals("a handler that threw did not raise the count by one", 2, prop(after(outcome("Failed"))!!, "failedRuns"))
        val notSaved = after(outcome("NotSaved")) ?: throw AssertionError("an entry whose key could not be saved left the list")
        assertEquals("a refused key-store commit was counted as a failed run", 1, prop(notSaved, "failedRuns"))
        assertEquals("entry", prop(notSaved, "id"))
        for (ran in listOf(outcome("Applied"), outcome("NotNewer"), outcome("NotParsed"), outcome("RefusedByGate", 2, true, A))) {
            assertNull("an entry whose handler ran (${ran.javaClass.simpleName}) stays in the list", after(ran))
        }
    }

    // ------------------------------------------------------ the accepted entry

    @Test
    fun `accepted - the mark round-trips and an entry without it reads as kept as refused`() {
        val read = state(e("accepted", "GY", 4, accepted = true), e("refused", "GK", 4))
        val both = kept(read)
        assertEquals(listOf(true, false), both.map { prop(it, "accepted") })

        val written = AdminCheck.keptChangesOf(announceStateToJson(read))
        assertEquals(listOf("accepted", "refused"), written.map { it.getString("id") })
        assertTrue("the mark `acc` was not written for an entry kept as accepted", written[0].optBoolean("acc", false))
        assertFalse("the mark `acc` was written for an entry kept as refused", written[1].has("acc"))
    }

    @Test
    fun `accepted - it is always given a place and no refused entry takes it`() {
        val noRank = { chat: String -> if (chat == Z) null else 2 }
        val full = entries(*(sixteen(A, keptAt = NOW - 20 * DAY, removals = 3..10) + sixteen(B) + sixteen(C)).toTypedArray())

        val withAccepted = offer(full, one(e("accepted", "GY", 30, by = Z, accepted = true)), ranks = noRank)
        assertEquals(48, withAccepted.size)
        assertTrue("an accepted entry was not given a place in a full list", "accepted" in withAccepted)
        assertEquals("it did not take the place the leave rule names", setOf("$A-GY-2"), ids(full).toSet() - withAccepted.toSet())

        // 47 entries kept as refused and one kept as accepted: a change of the admin's chat takes a refused one's place.
        val refused47 = (sixteen(A) + sixteen(B) + sixteen(C)).drop(1)
        val mixed = entries(*(refused47 + e("accepted", "GY", 30, by = Z, accepted = true)).toTypedArray())
        val ranks = { chat: String ->
            when (chat) {
                ADMIN_CHAT -> 0
                Z -> null
                else -> 2
            }
        }
        val afterRefused = offer(mixed, one(e("admin-change", by = ADMIN_CHAT)), ranks = ranks)
        assertEquals(48, afterRefused.size)
        assertTrue("a refused entry took the place of an accepted one", "accepted" in afterRefused)
        assertTrue("admin-change" in afterRefused)
    }

    @Test
    fun `accepted - a 49th beside 48 accepted ones is not kept, and it is not counted in its chat's eight`() {
        val accepted48 = (1..48).map { e("accepted-$it", "GY", 100 + it, by = Z, accepted = true) }
        val all = entries(*accepted48.toTypedArray())
        assertEquals(
            "a 49th accepted entry was kept beside 48 accepted ones",
            ids(all).toSet(),
            offer(all, one(e("accepted-49", "GY", 200, by = Z, accepted = true))).toSet(),
        )

        val eight = (2..9).map { e("gk-$it", "GK", it) }
        val withAccepted = offer(entries(*eight.toTypedArray()), one(e("accepted", "GK", 1, accepted = true)))
        assertEquals("an accepted entry was counted in its chat's eight removals", 9, withAccepted.size)

        val nineHeld = entries(*(eight + e("accepted", "GK", 20, accepted = true)).toTypedArray())
        val afterNinth = offer(nineHeld, one(e("gk-10", "GK", 10)))
        assertEquals((3..10).map { "gk-$it" }.toSet() + "accepted", afterNinth.toSet())

        val twice = offer(entries(e("accepted", "GK", 20, accepted = true)), one(e("again", "GK", 20, by = B, accepted = true)))
        assertEquals("a second accepted entry of one kind and epoch was kept", listOf("accepted"), twice)
    }

    // ------------------------------------------------------------------- due

    @Test
    fun `due - an accepted entry at the end of every pass, a refused one only at a complete pass of an OK group begun after`() {
        val held =
            entries(
                e("key-3-accepted", "GY", 3, accepted = true),
                e("kick-3", "GK", 3),
                e("kick-2", "GK", 2),
                e("invite", "GI", 1),
            )

        fun due(
            state: AdminKeyState,
            active: Boolean = true,
            own: Boolean = false,
            complete: Boolean = true,
            after: Boolean = true,
        ): List<String> =
            ids(
                rule(
                    "dueKeptChanges",
                    "dueKeptChanges(kept, state: AdminKeyState, groupActive: Boolean, ownGroup: Boolean, " +
                        "passComplete: Boolean, beganAfterConfirmation: Boolean)",
                    held,
                    state,
                    active,
                    own,
                    complete,
                    after,
                ),
            )

        assertEquals(
            "the due entries of both kinds do not sort together",
            listOf("kick-2", "kick-3", "key-3-accepted"),
            due(AdminKeyState.OK),
        )
        val acceptedOnly = listOf("key-3-accepted")
        assertEquals("a pass that is not complete ran an entry kept as refused", acceptedOnly, due(AdminKeyState.OK, complete = false))
        assertEquals(
            "a pass that began before the confirmation ran an entry kept as refused",
            acceptedOnly,
            due(AdminKeyState.OK, after = false),
        )
        for (waiting in listOf(AdminKeyState.UNKNOWN, AdminKeyState.ORPHANED, AdminKeyState.NOT_APPLICABLE)) {
            assertEquals("in state $waiting", acceptedOnly, due(waiting))
            assertEquals("in state $waiting, at a pass that is not complete", acceptedOnly, due(waiting, complete = false, after = false))
        }
        assertEquals("a group that is not active has due entries", emptyList<String>(), due(AdminKeyState.OK, active = false))
        assertEquals("this phone's own group has due entries", emptyList<String>(), due(AdminKeyState.OK, own = true))
    }

    // ------------------------------------------------------------- the codec

    private val richState =
        AnnounceState(
            hw = 7,
            rosterAnnounced = true,
            selfJoinN = 3,
            selfJoinEpoch = 1,
            inactiveSince = NOW - DAY,
            usedInviteIds = setOf("synthetic-used-b", "synthetic-used-a"),
            members = mapOf(B to MemberAnnounceEntry(n = 5, op = AnnounceOp.R, epoch = 2, leftAtMs = NOW - DAY, removedAtEpoch = 2)),
            adoptedAcceptAtMs = mapOf("synthetic-key-hash" to NOW - DAY),
            rosterNoticeShown = true,
            introduceHidden = setOf("synthetic-pair"),
            rosterQueryAtMs = NOW - HOUR,
            rosterQueryEpoch = 2,
            plainKeyJoin = true,
            seenActiveInviteIds = setOf("synthetic-seen"),
            pendingReinvite = PendingReinvite("synthetic-kept-invite", NOW - HOUR, """{"name":"g","key_epoch":3}""", A),
        )

    @Test
    fun `codec - a state with nothing kept is written byte for byte as the base writes it, without the key`() {
        for (state in listOf(AnnounceState(), richState)) {
            val written = announceStateToJson(state)
            assertEquals("a state with nothing kept is not written as before", FrozenBase.announceStateToJson(state), written)
            assertFalse("a state whose list is empty is written with the key", JSONObject(written).has(KEY))
            assertEquals("the state does not read back as it was written", state, announceStateFromJson(written))
        }
    }

    @Test
    fun `codec - a state with kept changes round-trips and a state without the field reads an empty list`() {
        assertEquals(
            "a state without the field does not read an empty list",
            emptyList<Any>(),
            kept(announceStateFromJson(announceStateToJson(richState))),
        )

        val stored =
            listOf(
                AdminCheck.keptEntry("kick", "GK", 2, """{"kicked":"x"}""", A, NOW - 9, NOW - 8, B, 3_000_100L, 2, false),
                AdminCheck.keptEntry("key", "GY", 3, """{"signer":"x"}""", A, NOW - 7, NOW - 6, A, null, 0, true),
                AdminCheck.keptEntry("invite", "GI", 0, """{"name":"g"}""", C, NOW - 5, NOW - 4, C, null, 0, false),
            )
        val json = JSONObject(announceStateToJson(richState)).put(KEY, JSONArray(stored)).toString()
        val read = announceStateFromJson(json)
        assertEquals("fixture: three entries were read", 3, kept(read).size)

        val again = AdminCheck.keptChangesOf(announceStateToJson(read))
        assertEquals("the kept changes do not round-trip", stored.map(::asMap), again.map(::asMap))
        assertEquals("the same list does not read back", kept(read), kept(announceStateFromJson(announceStateToJson(read))))
        assertEquals(
            "the rest of the state changed with the list",
            FrozenBase.announceStateToJson(richState),
            FrozenBase.announceStateToJson(read),
        )

        val kick = kept(read).first()
        val expected =
            mapOf(
                "id" to "kick",
                "kind" to "GK",
                "epoch" to 2,
                "payload" to """{"kicked":"x"}""",
                "from" to A,
                "by" to B,
                "at" to NOW - 9,
                "kept" to NOW - 8,
                "height" to 3_000_100L,
                "failedRuns" to 2,
                "accepted" to false,
            )
        assertEquals(expected, expected.keys.associateWith { prop(kick, it) })
    }

    private fun asMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { o.get(it).toString() }

    @Test
    fun `codec - a broken list or entry reads as no entry and the rest of the state is intact`() {
        val good = e("good")
        val broken: Map<String, Any> =
            mapOf(
                "not an array" to "synthetic-not-an-array",
                "an object" to JSONObject().put("id", "x"),
                "no id" to JSONArray().put(JSONObject(good.toString()).apply { remove("id") }),
                "an empty id" to JSONArray().put(JSONObject(good.toString()).put("id", "")),
                "an unknown kind" to JSONArray().put(JSONObject(good.toString()).put("t", "GM")),
                "an epoch of 0" to JSONArray().put(JSONObject(good.toString()).put("e", 0)),
                "no payload" to JSONArray().put(JSONObject(good.toString()).apply { remove("p") }),
                "no sender" to JSONArray().put(JSONObject(good.toString()).apply { remove("from") }),
                "an entry that is no object" to JSONArray().put("synthetic-entry"),
            )
        for ((what, value) in broken) {
            val read = announceStateFromJson(JSONObject(announceStateToJson(richState)).put(KEY, value).toString())
            assertEquals("$what: used_invite_ids were lost", richState.usedInviteIds, read.usedInviteIds)
            assertEquals("$what: seen_active_invite_ids were lost", richState.seenActiveInviteIds, read.seenActiveInviteIds)
            assertEquals("$what: pending_reinvite was lost", richState.pendingReinvite, read.pendingReinvite)
            assertEquals("$what: the members were lost", richState.members, read.members)
            assertEquals(
                "$what: the rest of the state changed",
                FrozenBase.announceStateToJson(richState),
                FrozenBase.announceStateToJson(read),
            )
            assertEquals("$what: it was read as an entry", emptyList<Any>(), kept(read))
        }
        // A broken entry costs only itself: the good one beside it is read.
        val mixed = JSONArray().put(JSONObject(good.toString()).put("t", "GM")).put(good)
        assertEquals(listOf("good"), ids(kept(announceStateFromJson(JSONObject().put(KEY, mixed).toString()))))
    }

    @Test
    fun `codec - caps and defaults at the read`() {
        val sixty = (1..60).map { e("entry-$it", "GK", it + 1, by = "chat-$it") }
        assertEquals(
            "not the first 48 of 60 stored entries were read",
            (1..48).map { "entry-$it" },
            ids(kept(state(*sixty.toTypedArray()))),
        )

        fun read(entry: JSONObject): List<Any> = kept(state(entry))
        assertEquals("an id of 128 characters is not read", 1, read(e("i".repeat(128))).size)
        assertEquals("an id of 129 characters is read", 0, read(e("i".repeat(129))).size)
        assertEquals("a sender of 512 characters is not read", 1, read(e("sender", from = "s".repeat(512))).size)
        assertEquals("a sender of 513 characters is read", 0, read(e("sender", from = "s".repeat(513))).size)
        assertEquals("a signer chat of 513 characters is read", 0, read(e("signer", by = "s".repeat(513), from = A)).size)
        assertEquals("a payload of 2,048 characters is not read", 1, read(e("payload", payload = "p".repeat(2_048))).size)
        assertEquals("a payload of 2,049 characters is read", 0, read(e("payload", payload = "p".repeat(2_049))).size)

        val bare = JSONObject(e("bare", from = B).toString()).apply { listOf("by", "kept", "h", "f", "acc").forEach { remove(it) } }
        val before = System.currentTimeMillis()
        val defaults = read(bare).single()
        val future = read(JSONObject(e("future").toString()).put("kept", before + 400 * DAY)).single()
        val notPositive = read(JSONObject(e("zero").toString()).put("kept", 0L)).single()
        val after = System.currentTimeMillis()
        assertEquals("an entry with no signer chat does not read with its sender as the signer", B, prop(defaults, "by"))
        assertEquals(0, prop(defaults, "failedRuns"))
        assertNull(prop(defaults, "height"))
        assertEquals(false, prop(defaults, "accepted"))
        val readNow = listOf("no kept time" to defaults, "a kept time in the future" to future, "a kept time of 0" to notPositive)
        for ((what, entry) in readNow) {
            val keptAt = prop(entry, "kept") as Long
            assertTrue("$what does not read with the current time ($keptAt)", keptAt in before..after)
        }
    }

    // ------------------------------------------------------------ the outcome

    @Test
    fun `outcome - there are six and only a signed refusal with a signer chat or a relay copy not saved is kept`() {
        val type = outcome("Applied").javaClass.enclosingClass ?: outcome("Applied").javaClass.superclass
        assertEquals(
            setOf("NotParsed", "Failed", "NotSaved", "RefusedByGate", "NotNewer", "Applied"),
            type.declaredClasses
                .map { it.simpleName }
                .filter { it != "Companion" }
                .toSet(),
        )

        fun keepAs(
            outcome: Any,
            eventId: String? = "synthetic-event",
            sealSender: String? = A,
        ): String =
            (
                rule(
                    "keepAs",
                    "keepAs(outcome: AdminChangeOutcome, eventId: String?, sealSender: String?): KeepAs",
                    outcome,
                    eventId,
                    sealSender,
                ) as Enum<*>
            ).name

        assertEquals("NOT_KEPT", keepAs(outcome("NotParsed")))
        assertEquals("NOT_KEPT", keepAs(outcome("Failed")))
        assertEquals("NOT_KEPT", keepAs(outcome("NotNewer")))
        assertEquals("NOT_KEPT", keepAs(outcome("Applied")))
        assertEquals("AS_REFUSED", keepAs(outcome("RefusedByGate", 2, true, A)))
        assertEquals("a refusal with no signature was kept", "NOT_KEPT", keepAs(outcome("RefusedByGate", 2, false, A)))
        assertEquals("a refusal with no signer chat was kept", "NOT_KEPT", keepAs(outcome("RefusedByGate", 2, true, null)))
        assertEquals(
            "a refused copy from the chain was kept",
            "NOT_KEPT",
            keepAs(outcome("RefusedByGate", 2, true, A), eventId = null, sealSender = null),
        )
        assertEquals("AS_ACCEPTED", keepAs(outcome("NotSaved")))
        assertEquals(
            "a copy from the chain that was not saved was kept",
            "NOT_KEPT",
            keepAs(outcome("NotSaved"), eventId = null, sealSender = null),
        )
        assertEquals("NOT_KEPT", keepAs(outcome("NotSaved"), eventId = null))
        assertEquals("NOT_KEPT", keepAs(outcome("NotSaved"), sealSender = null))
    }

    // ------------------------------------------------------------- the invite

    @Test
    fun `invite - one per signer chat by key epoch, then by time`() {
        fun replaces(
            newEpoch: Int,
            newAt: Long,
            keptEpoch: Int,
            keptAt: Long,
        ): Any? =
            rule(
                "inviteReplaces",
                "inviteReplaces(newKeyEpoch: Int, newAtMs: Long, keptKeyEpoch: Int, keptAtMs: Long): Boolean",
                newEpoch,
                newAt,
                keptEpoch,
                keptAt,
            )
        assertEquals("an invite of a lower key epoch replaced the kept one because its time is later", false, replaces(2, 900L, 3, 100L))
        assertEquals("a higher key epoch did not win over a later time", true, replaces(4, 50L, 3, 100L))
        assertEquals("at one key epoch the later time did not win", true, replaces(3, 101L, 3, 100L))
        assertEquals("at one key epoch and one time the kept one was replaced", false, replaces(3, 100L, 3, 100L))
        assertEquals(false, replaces(3, 99L, 3, 100L))

        val held = entries(e("a-invite", "GI", 3, at = NOW - 100), e("b-invite", "GI", 9, by = B, at = NOW - 100))
        assertEquals(setOf("a-invite", "b-invite"), offer(held, one(e("lower-later", "GI", 2, at = NOW - 1))).toSet())
        assertEquals(setOf("higher-earlier", "b-invite"), offer(held, one(e("higher-earlier", "GI", 4, at = NOW - 500))).toSet())
        assertEquals(setOf("same-later", "b-invite"), offer(held, one(e("same-later", "GI", 3, at = NOW - 50))).toSet())
        assertEquals(setOf("a-invite", "b-invite"), offer(held, one(e("same-same", "GI", 3, at = NOW - 100))).toSet())
    }

    @Test
    fun `invite - the reader takes an invite whose key epoch is 0 and refuses a removal or a key change with one`() {
        assertEquals(listOf("invite"), ids(kept(state(e("invite", "GI", 0)))))
        assertEquals(emptyList<String>(), ids(kept(state(e("invite", "GI", -1)))))
        assertEquals(emptyList<String>(), ids(kept(state(e("kick", "GK", 0)))))
        assertEquals(emptyList<String>(), ids(kept(state(e("key", "GY", 0)))))
    }

    // ----------------------------------------------- began after the confirmation

    @Test
    fun `a pass began after the confirmation when its number is above the group's, or the group has none`() {
        fun after(
            pass: Long,
            confirmedAt: Long?,
        ): Any? =
            rule("passBeganAfterConfirmation", "passBeganAfterConfirmation(passNumber: Long, confirmedAtPass: Long?)", pass, confirmedAt)
        assertEquals(true, after(8L, 7L))
        assertEquals(true, after(1L, null))
        assertEquals("a pass with the group's own number began after the confirmation", false, after(7L, 7L))
        assertEquals(false, after(6L, 7L))
    }

    // ------------------------------------------------------- the finished chat

    @Test
    fun `finishedChatKey is the contact key of a finished chat and nothing else`() {
        val p = AdminCheckPhone()
        val peer = p.base.link(A)

        fun key(address: String): Any? =
            rule("finishedChatKey", "finishedChatKey(prefs: ZchatPreferences, address: String): String?", p.prefs, address)
        assertEquals(peer.publicKey, key(A))
        assertNull("an address with no chat has a finished chat's key", key(B))
        p.base.awaitingAccept[A] = "synthetic-waiting-kex"
        assertNull("a request waiting for Accept counts as a finished chat", key(A))
        p.base.awaitingAccept.remove(A)
        p.base.ourKeys.remove(A)
        assertNull("a contact key without our own key pair counts as a finished chat", key(A))
    }

    /**
     * `announceStateToJson` as the commit before this pair wrote it, copied whole from `GroupMemberAnnounce.kt` at
     * `0c7a15fc7` (lines 547-594): what "byte for byte as today" is measured against, whatever key order the JSON
     * library gives.
     */
    private object FrozenBase {
        fun announceStateToJson(state: AnnounceState): String {
            val members = JSONObject()
            for ((address, e) in state.members) {
                members.put(
                    address,
                    JSONObject().apply {
                        put("n", e.n)
                        e.op?.let { put("o", it.name) }
                        put("e", e.epoch)
                        put("activated_at", e.activatedAtMs)
                        e.activatedBy?.let { put("activated_by", it.name) }
                        put("left_at", e.leftAtMs)
                        put("removed_at_epoch", e.removedAtEpoch)
                        e.annForm?.let { put("ann_form", it) }
                        e.leftBy?.let { put("left_by", it.name) }
                        if (e.invitedByUs) put("invited_by_us", true)
                    },
                )
            }
            return JSONObject()
                .apply {
                    put("hw", state.hw)
                    put("roster_announced", state.rosterAnnounced)
                    put("self_join_n", state.selfJoinN)
                    put("self_join_epoch", state.selfJoinEpoch)
                    state.inactiveSince?.let { put("inactive_since", it) }
                    put("used_invite_ids", JSONArray(state.usedInviteIds.sorted()))
                    put("members", members)
                    if (state.adoptedAcceptAtMs.isNotEmpty()) put("adopted_accept_at", JSONObject(state.adoptedAcceptAtMs))
                    if (state.rosterNoticeShown) put("roster_notice_shown", true)
                    if (state.introduceHidden.isNotEmpty()) put("introduce_hidden", JSONArray(state.introduceHidden.sorted()))
                    if (state.rosterQueryAtMs != 0L) put("roster_query_at", state.rosterQueryAtMs)
                    if (state.rosterQueryEpoch != 0) put("roster_query_epoch", state.rosterQueryEpoch)
                    if (state.plainKeyJoin) put("plain_key_join", true)
                    if (state.seenActiveInviteIds.isNotEmpty()) put("seen_active_invite_ids", JSONArray(state.seenActiveInviteIds.sorted()))
                    state.pendingReinvite?.let { kept ->
                        put(
                            "pending_reinvite",
                            JSONObject().apply {
                                put("id", kept.inviteId)
                                put("source_ms", kept.sourceMs)
                                put("invite", kept.inviteJson)
                                kept.sealSender?.let { put("sender", it) }
                            },
                        )
                    }
                }.toString()
        }
    }

    private companion object {
        const val KEY = "kept_admin_changes"

        /** The clock every rule here is given (2026-09-21, a time before any run of this test). */
        const val NOW = AdminCheck.T
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val A = "usyntheticki25keptchata00001"
        const val B = "usyntheticki25keptchatb00001"
        const val C = "usyntheticki25keptchatc00001"
        const val D = "usyntheticki25keptchatd00001"
        const val Z = "usyntheticki25keptchatz00001"
        const val ADMIN_CHAT = "usyntheticki25keptadmin00001"
        const val VERIFIED = "usyntheticki25keptverif00001"
        const val V2 = "usyntheticki25keptverif00002"
        const val V3 = "usyntheticki25keptverif00003"
        const val V4 = "usyntheticki25keptverif00004"
    }
}
