package co.electriccoin.zcash.ui.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZC-420 (fix-all 2.13.0, train T6b; ZC-420-SPEC §3.2 R2, R3, §3.3, §4.2): the relay-copy marks on NostrChatBridge.
 *
 * A free-first relay copy that a readable relay took and neither ZChat's relay nor nos.lol took is remembered in a durable
 * mark, "<rumorId>|<markedAtMs>|<extra>" under a slot; a shared answer removes the mark of that copy only; the repair
 * confirms it from the kept wrap or publishes one rebuilt copy over the relays, and removes or replaces the mark only
 * while the slot still holds the value it read. Limits: 1,024 message marks (the oldest goes for a new one) and 256
 * identity, key and invite marks together (one more is not written, nothing is pushed out). Hooks are fakes: an
 * in-memory mark store ([RelayCopyMarksApi.MemoryMarks]), a scripted publisher and kept-wrap resender. Every value is
 * synthetic.
 */
class RelayCopyMarksTest {
    private companion object {
        const val KEY = "relay-key-of-the-contact"
        const val PEER = "usyntheticzc420markspeer01"
        const val GROUP = "synthetic-zc420-group"
        const val MEMBER = "usyntheticzc420member01"
        const val HOUR_MS = 3_600_000L
    }

    private val marks = RelayCopyMarksApi.MemoryMarks()
    private val published: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
    private val resendAsks: MutableList<String> = CopyOnWriteArrayList()

    @Volatile private var answer = Triple(1, "rumor-new", 1)

    @Volatile private var hold: CompletableDeferred<Unit>? = null

    @Volatile private var kept: ((String) -> Triple<Int, String, Int>?)? = null

    private fun result(a: Triple<Int, String, Int>?): NostrChatBridge.PublishResult? =
        a?.let { NostrChatBridge.PublishResult(it.first, it.second, it.third) }

    @Before
    fun setUp() {
        NostrChatBridge.registerPublisher { memo, to ->
            published += memo to to
            hold?.await()
            result(answer)!!
        }
        NostrChatBridge.registerKeptResender { id, _ ->
            resendAsks += id
            result(kept?.invoke(id))
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.unregisterKeptResender()
        RelayCopyMarksApi.unregister()
    }

    private fun withMarks() {
        assertTrue(RelayCopyMarksApi.available(), "NostrChatBridge has no relay-copy marks (ZC-420)")
        RelayCopyMarksApi.register(marks)
    }

    private fun slot() = RelayCopyMarksApi.Slots.identity(PEER)

    private fun mark(
        slot: String,
        rumorId: String,
        ageMs: Long = 0L,
        extra: String = "ours",
    ) {
        marks.map[slot] = "$rumorId|${System.currentTimeMillis() - ageMs}|$extra"
    }

    // ── noteRelayCopy and beginRelayCopy ────────────────────────────────────────────────────────────

    @Test
    fun `a copy only a public relay took is marked, a shared answer removes the mark, and no relay or no result changes nothing`() {
        withMarks()
        RelayCopyMarksApi.note(slot(), result(Triple(0, "rumor-1", 1)), "ours")
        assertEquals("rumor-1", marks.rumorId(slot()))
        assertEquals("ours", marks.extra(slot()))

        RelayCopyMarksApi.note(slot(), result(Triple(0, "rumor-2", 0)), "ours")
        RelayCopyMarksApi.note(slot(), null, "ours")
        assertEquals("rumor-1", marks.rumorId(slot()), "a result no relay took changed the mark")

        RelayCopyMarksApi.note(slot(), result(Triple(1, "rumor-3", 1)), "ours")
        assertNull(marks.map[slot()], "a shared answer left the mark")
    }

    @Test
    fun `a shared answer for a slot with no mark writes nothing, and one for another copy's mark leaves it`() {
        withMarks()
        RelayCopyMarksApi.note(slot(), result(Triple(1, "rumor-1", 1)), "ours")
        assertTrue(marks.puts.isEmpty() && marks.removes.isEmpty(), "a slot never marked was written: ${marks.puts} ${marks.removes}")

        mark(slot(), "rumor-new", extra = "newer")
        RelayCopyMarksApi.note(slot(), result(Triple(1, "rumor-old", 1)), "older")
        assertEquals("newer", marks.extra(slot()), "an older copy's shared answer removed a newer copy's mark")
    }

    @Test
    fun `a mark that could not be written leaves no mark and throws nothing`() {
        withMarks()
        marks.failPuts = true
        RelayCopyMarksApi.note(slot(), result(Triple(0, "rumor-1", 1)), "ours")
        RelayCopyMarksApi.begin(slot(), "ours")
        assertTrue(marks.map.isEmpty())
    }

    @Test
    fun `beginRelayCopy writes a mark with no rumor id that replaces an older mark of the slot`() {
        withMarks()
        mark(slot(), "rumor-old", extra = "older")
        RelayCopyMarksApi.begin(slot(), "ours")
        assertEquals("", marks.rumorId(slot()))
        assertEquals("ours", marks.extra(slot()))
        assertEquals("ours", RelayCopyMarksApi.extraOf(slot()))
    }

    @Test
    fun `an extra that holds the separator round-trips`() =
        runBlocking {
            withMarks()
            val gk = RelayCopyMarksApi.Slots.groupKey(GROUP, MEMBER)
            RelayCopyMarksApi.note(gk, result(Triple(0, "rumor-1", 1)), "7|usyntheticremoved01")
            assertEquals("7|usyntheticremoved01", RelayCopyMarksApi.extraOf(gk))

            answer = Triple(0, "rumor-2", 1)
            assertEquals("RESENT_UNCONFIRMED", RelayCopyMarksApi.repair(gk, KEY) { "rebuilt" })
            assertEquals("rumor-2", marks.rumorId(gk))
            assertEquals("7|usyntheticremoved01", RelayCopyMarksApi.extraOf(gk))
        }

    // ── repairRelayCopy ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the kept wrap is asked first - shared confirms it, not shared keeps the mark, and nothing new is made`() =
        runBlocking {
            withMarks()
            mark(slot(), "rumor-1")
            kept = { Triple(0, it, 1) }
            assertEquals("KEPT_UNCONFIRMED", RelayCopyMarksApi.repair(slot(), KEY) { "rebuilt" })
            assertEquals("rumor-1", marks.rumorId(slot()))

            kept = { Triple(1, it, 1) }
            assertEquals("CONFIRMED", RelayCopyMarksApi.repair(slot(), KEY) { "rebuilt" })
            assertNull(marks.map[slot()])
            assertEquals(listOf("rumor-1", "rumor-1"), resendAsks)
            assertTrue(published.isEmpty(), "a copy was made while the wrap is kept")
        }

    @Test
    fun `with nothing kept one rebuilt copy is published to the given key - shared, readable only, no relay`() =
        runBlocking {
            withMarks()
            mark(slot(), "rumor-1")
            answer = Triple(0, "rumor-2", 1)
            assertEquals("RESENT_UNCONFIRMED", RelayCopyMarksApi.repair(slot(), KEY) { "rebuilt" })
            assertEquals(listOf("rebuilt" to KEY), published)
            assertEquals("rumor-2", marks.rumorId(slot()))
            assertEquals("ours", marks.extra(slot()))

            answer = Triple(0, "rumor-3", 0)
            assertEquals("NOT_SENT", RelayCopyMarksApi.repair(slot(), KEY) { "rebuilt" })
            assertEquals("rumor-2", marks.rumorId(slot()), "a publish no relay took changed the mark")

            answer = Triple(1, "rumor-4", 1)
            assertEquals("CONFIRMED", RelayCopyMarksApi.repair(slot(), KEY) { "rebuilt" })
            assertNull(marks.map[slot()])
            assertEquals(3, published.size)
        }

    @Test
    fun `a rebuild that answers null publishes nothing and keeps the mark`() =
        runBlocking {
            withMarks()
            mark(slot(), "rumor-1")
            assertEquals("NOT_SENT", RelayCopyMarksApi.repair(slot(), KEY) { null })
            assertTrue(published.isEmpty())
            assertEquals("rumor-1", marks.rumorId(slot()))
        }

    @Test
    fun `a mark with no rumor id is rebuilt without asking the resender`() =
        runBlocking {
            withMarks()
            RelayCopyMarksApi.begin(slot(), "ours")
            kept = { Triple(1, it, 1) }
            assertEquals("CONFIRMED", RelayCopyMarksApi.repair(slot(), KEY) { "rebuilt" })
            assertTrue(resendAsks.isEmpty(), "the resender was asked for a copy that has no rumor id")
            assertEquals(1, published.size)
        }

    @Test
    fun `two repairs of one slot at the same time publish once`() =
        runBlocking {
            withMarks()
            mark(slot(), "rumor-1")
            val held = CompletableDeferred<Unit>()
            hold = held
            val first = async { RelayCopyMarksApi.repair(slot(), KEY) { "rebuilt" } }
            withTimeout(10_000) { while (published.isEmpty()) delay(5) }

            assertEquals("KEPT_UNCONFIRMED", RelayCopyMarksApi.repair(slot(), KEY) { "rebuilt" })
            held.complete(Unit)
            assertEquals("CONFIRMED", first.await())
            assertEquals(1, published.size)
        }

    @Test
    fun `a repair whose slot got a newer mark while its publish waited neither removes nor replaces it`() =
        runBlocking {
            for (late in listOf(Triple(1, "rumor-late", 1), Triple(0, "rumor-late", 1))) {
                marks.map.clear()
                published.clear()
                withMarks()
                mark(slot(), "rumor-1", extra = "older")
                val held = CompletableDeferred<Unit>()
                hold = held
                answer = late
                val repair = async { RelayCopyMarksApi.repair(slot(), KEY) { "rebuilt" } }
                withTimeout(10_000) { while (published.isEmpty()) delay(5) }
                RelayCopyMarksApi.begin(slot(), "newer")

                held.complete(Unit)
                hold = null
                assertEquals("KEPT_UNCONFIRMED", repair.await(), "late answer $late")
                assertEquals("newer", marks.extra(slot()), "the late answer $late removed or replaced the newer mark")
                assertEquals("", marks.rumorId(slot()))
            }
        }

    @Test
    fun `with no hook every call is a no-op and a repair is NOT_MARKED`() =
        runBlocking {
            assertTrue(RelayCopyMarksApi.available(), "NostrChatBridge has no relay-copy marks (ZC-420)")
            RelayCopyMarksApi.begin(slot(), "ours")
            RelayCopyMarksApi.note(slot(), result(Triple(0, "rumor-1", 1)), "ours")
            assertTrue(RelayCopyMarksApi.unconfirmed("zb|").isEmpty())
            assertNull(RelayCopyMarksApi.extraOf(slot()))
            RelayCopyMarksApi.forget(slot())
            assertEquals("NOT_MARKED", RelayCopyMarksApi.repair(slot(), KEY) { "rebuilt" })
            assertTrue(published.isEmpty() && resendAsks.isEmpty() && marks.map.isEmpty())
        }

    @Test
    fun `the list is oldest first and by prefix, and forget removes a mark`() {
        withMarks()
        val older = RelayCopyMarksApi.Slots.identity("usyntheticzc420older")
        mark(slot(), "rumor-1", ageMs = 1_000)
        mark(older, "rumor-2", ageMs = 9_000)
        mark(RelayCopyMarksApi.Slots.groupInvite(GROUP, MEMBER), "rumor-3")
        assertEquals(listOf(older, slot()), RelayCopyMarksApi.unconfirmed("zb|"))
        RelayCopyMarksApi.forget(older)
        assertEquals(listOf(slot()), RelayCopyMarksApi.unconfirmed("zb|"))
    }

    // ── the late kept wrap (ZC-658; LateKeptWrapIsStillDeliveredTest decided it) ────────────────────

    @Test
    fun `a mark older than one hour is rebuilt instead of resending its kept wrap, except an invite`() =
        runBlocking {
            withMarks()
            kept = { Triple(1, it, 1) }
            val lateSlots =
                listOf(
                    slot(),
                    RelayCopyMarksApi.Slots.groupKey(GROUP, MEMBER),
                    RelayCopyMarksApi.Slots.groupMessage(GROUP, MEMBER, 3, 41L),
                )
            for (late in lateSlots) {
                mark(late, "rumor-late", ageMs = 2 * HOUR_MS)
                assertEquals("CONFIRMED", RelayCopyMarksApi.repair(late, KEY) { "rebuilt" })
            }
            assertTrue(resendAsks.isEmpty(), "a kept wrap older than an hour was re-sent: ${resendAsks.size}")
            assertEquals(3, published.size)

            val invite = RelayCopyMarksApi.Slots.groupInvite(GROUP, MEMBER)
            mark(invite, "rumor-invite", ageMs = 30 * HOUR_MS)
            assertEquals("CONFIRMED", RelayCopyMarksApi.repair(invite, KEY) { "rebuilt" })
            assertEquals(listOf("rumor-invite"), resendAsks, "an invite mark was not confirmed from its kept wrap")
            assertEquals(3, published.size, "an invite was rebuilt")
        }

    // ── limits ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `at 1,024 message marks a new one pushes out the oldest message mark`() {
        withMarks()
        val now = System.currentTimeMillis()
        for (i in 0 until 1_024) {
            marks.map[RelayCopyMarksApi.Slots.groupMessage(GROUP, MEMBER, 1, i.toLong())] = "rumor-$i|${now - 100_000 + i}|1"
        }
        mark(slot(), "rumor-zb", ageMs = 500_000)
        val newest = RelayCopyMarksApi.Slots.groupMessage(GROUP, MEMBER, 1, 5_000L)

        RelayCopyMarksApi.note(newest, NostrChatBridge.PublishResult(0, "rumor-new", 1), "1")

        assertEquals("rumor-new", marks.rumorId(newest))
        assertNull(marks.map[RelayCopyMarksApi.Slots.groupMessage(GROUP, MEMBER, 1, 0L)], "the oldest message mark stayed")
        assertEquals(1_024, marks.map.keys.count { it.startsWith("gm|") })
        assertEquals("rumor-zb", marks.rumorId(slot()), "an identity mark was pushed out for a message mark")
    }

    @Test
    fun `256 identity, key and invite marks together - one more is not written and nothing is pushed out`() {
        withMarks()
        val now = System.currentTimeMillis()
        for (i in 0 until 256) {
            val s =
                when (i % 3) {
                    0 -> RelayCopyMarksApi.Slots.identity("usyntheticpeer$i")
                    1 -> RelayCopyMarksApi.Slots.groupKey(GROUP, "usyntheticmember$i")
                    else -> RelayCopyMarksApi.Slots.groupInvite(GROUP, "usyntheticmember$i")
                }
            marks.map[s] = "rumor-$i|${now - 100_000 + i}|x"
        }
        marks.map[RelayCopyMarksApi.Slots.groupMessage(GROUP, MEMBER, 1, 1L)] = "rumor-gm|$now|1"
        val before = HashMap(marks.map)

        RelayCopyMarksApi.note(slot(), NostrChatBridge.PublishResult(0, "rumor-257", 1), "ours")
        RelayCopyMarksApi.begin(RelayCopyMarksApi.Slots.groupKey(GROUP, MEMBER), "2")

        assertEquals(before, HashMap(marks.map), "the 257th identity, key or invite mark was written or pushed one out")
    }

    // ── slots ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `slots are stable, differ by kind, member, epoch and sequence, and start with their group's prefix`() {
        val s = RelayCopyMarksApi.Slots
        assertEquals(s.identity(PEER), s.identity(PEER))
        assertEquals(s.groupMessage(GROUP, MEMBER, 3, 41L), s.groupMessage(GROUP, MEMBER, 3, 41L))
        val all =
            listOf(
                s.identity(PEER),
                s.groupKey(GROUP, MEMBER),
                s.groupInvite(GROUP, MEMBER),
                s.groupMessage(GROUP, MEMBER, 3, 41L),
                s.groupMessage(GROUP, MEMBER, 4, 41L),
                s.groupMessage(GROUP, MEMBER, 3, 42L),
                s.groupMessage(GROUP, "usyntheticzc420member02", 3, 41L),
                s.groupKey(GROUP, "usyntheticzc420member02"),
            )
        assertEquals(all.size, all.toSet().size, "two different copies share a slot")
        assertTrue(s.groupKey(GROUP, MEMBER).startsWith(s.ofGroup("gk", GROUP)))
        assertTrue(s.groupInvite(GROUP, MEMBER).startsWith(s.ofGroup("gi", GROUP)))
        assertTrue(s.groupMessage(GROUP, MEMBER, 3, 41L).startsWith(s.ofGroup("gm", GROUP)))
        assertFalse(s.groupKey("$GROUP-2", MEMBER).startsWith(s.ofGroup("gk", GROUP)), "another group shares the prefix")
        assertNotEquals(s.ofGroup("gk", GROUP), s.ofGroup("gm", GROUP))
        assertTrue(s.identity(PEER).startsWith("zb|"))
    }
}
