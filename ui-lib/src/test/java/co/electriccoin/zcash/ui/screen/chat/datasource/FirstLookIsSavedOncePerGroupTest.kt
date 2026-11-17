package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.chat.datasource.LegacyGroupMoveFixture.Companion.MARK
import co.electriccoin.zcash.ui.screen.chat.datasource.LegacyGroupMoveFixture.Companion.record2124
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KI-25 pair 3, ZC-598 (KI-25-SPEC 3.6b D1, D3; section 4.3 class 13) — the one look, ever: in the first start of this
 * build, inside the store's move of 2.12.4's plain group file, after a mark was written; its result is saved in the
 * group's own record, in the move's one commit. Driven through [LegacyGroupMoveFixture] (the store's private
 * `moveLegacyGroupRecords(old, enc, storeIsNew, mainProcess)` by name, a store file with a memory and a disk layer).
 *
 * The first start is `storeIsNew` true with no mark. Cases (a) to (v) are the spec's; (g) to (j) are D1's failure table
 * F1 to F5, (m) F2a, (n) F2b, (r) F3a, (s) F3 seen from memory. At the base the move copies strings and has no look:
 * "no such operation".
 *
 * All addresses, keys and ids are synthetic.
 */
class FirstLookIsSavedOncePerGroupTest {
    private fun creatorKey(stored: String?): String? = stored?.let { ZMSGGroupProtocol.deserializeGroupInfo(it)?.creatorPublicKey }

    /** The record as today's serializer writes [raw] with [key] as its creator key. */
    private fun looked(
        raw: String,
        key: String,
    ): String = ZMSGGroupProtocol.serializeGroupInfo(ZMSGGroupProtocol.deserializeGroupInfo(raw)!!.copy(creatorPublicKey = key))

    private fun firstKeyRead(f: LegacyGroupMoveFixture): Int = f.log.indexOfFirst { it.startsWith("e2e.get:") }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - the first start writes the finished chat's key into its record, leaves the other byte for byte, in one commit`() {
        val f = LegacyGroupMoveFixture()
        val admin = f.finishedChat(XA)
        val old = f.OldFile()
        old.apply {
            putRaw(GA, record2124(GA, XA))
            putRaw(GB, record2124(GB, XB))
        }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        val disk = store.diskSnapshot()
        val expected = looked(record2124(GA, XA), admin.publicKey)
        assertEquals("the record with a finished chat at its creator address has no look", expected, disk[GA])
        assertEquals("a record with no key at its creator address was changed", record2124(GB, XB), disk[GB])
        assertEquals("the records did not arrive in one commit", 1, store.commits)
        val mark = f.log.indexOf("old.commit:mark")
        assertTrue("the mark was never written", mark >= 0)
        assertTrue("a key was read before the mark's commit: ${f.log}", firstKeyRead(f) > mark)
        assertTrue("the store committed before the mark", f.log.indexOf("store.commit") > mark)
        assertEquals("the old file is not empty, or the mark stayed", emptyMap<String, Any?>(), old.snapshot())
    }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `b - a key with no key pair of ours and a chat request are not looked at, and a key where our pair exists is`() {
        val f = LegacyGroupMoveFixture()
        f.keyWithoutOurPair(XA)
        f.chatRequest(XB)
        val answered = f.finishedChat(XC) // an answer's key at an address where our own key pair already existed
        val old = f.OldFile()
        old.apply {
            putRaw(GA, record2124(GA, XA))
            putRaw(GB, record2124(GB, XB))
            putRaw(GC, record2124(GC, XC))
        }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        assertEquals("a key with no key pair of ours was written down", record2124(GA, XA), store.diskSnapshot()[GA])
        assertEquals("a chat request still waiting was written down", record2124(GB, XB), store.diskSnapshot()[GB])
        assertEquals("a key where our own key pair exists is a finished chat", answered.publicKey, creatorKey(store.diskSnapshot()[GC]))
    }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `c - the alias of the creator address is not followed`() {
        val f = LegacyGroupMoveFixture()
        f.finishedChat(Z)
        f.alias(XA, Z)
        val old = f.OldFile().apply { putRaw(GA, record2124(GA, XA)) }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        assertEquals("a finished chat at the alias target was written down", record2124(GA, XA), store.diskSnapshot()[GA])
    }

    // ------------------------------------------------------------------ (d)

    @Test
    fun `d - our own group, and a group with our own creator address, are not looked at`() {
        val f = LegacyGroupMoveFixture()
        f.finishedChat(XA)
        f.finishedChat(XB)
        f.ownGroup(GA)
        f.ownAddress(XB)
        val old = f.OldFile()
        old.apply {
            putRaw(GA, record2124(GA, XA))
            putRaw(GB, record2124(GB, XB))
        }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        assertEquals("our own group got a creator key", record2124(GA, XA), store.diskSnapshot()[GA])
        assertEquals("a group with our own creator address got a creator key", record2124(GB, XB), store.diskSnapshot()[GB])
    }

    // ------------------------------------------------------------------ (e)

    @Test
    fun `e - a key-changed warning on the creator's chat - the stored old key is written`() {
        val f = LegacyGroupMoveFixture()
        val stored = f.finishedChat(XA)
        f.impl.setE2EKeyChanged(XA, true)
        val old = f.OldFile().apply { putRaw(GA, record2124(GA, XA)) }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        assertEquals("the stored key was not written down", stored.publicKey, creatorKey(store.diskSnapshot()[GA]))
    }

    // ------------------------------------------------------------------ (f)

    @Test
    fun `f - a string that does not deserialize comes across byte for byte and the others have their look`() {
        val f = LegacyGroupMoveFixture()
        val admin = f.finishedChat(XA)
        val old = f.OldFile()
        old.apply {
            putRaw(GA, record2124(GA, XA))
            putRaw(GB, BROKEN)
        }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        assertEquals(BROKEN, store.diskSnapshot()[GB])
        assertEquals(admin.publicKey, creatorKey(store.diskSnapshot()[GA]))
    }

    // ------------------------------------------------------------------ (g) F1

    @Test
    fun `g - F1, the mark's commit is refused - no key is written, the records come across as 2_12_4 wrote them`() {
        val f = LegacyGroupMoveFixture()
        f.finishedChat(XA)
        val old = f.OldFile()
        old.apply {
            putRaw(GA, record2124(GA, XA))
            refuseMark = true
        }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        assertEquals("a key was written although the mark could not be", record2124(GA, XA), store.diskSnapshot()[GA])
    }

    // ------------------------------------------------------------------ (h) F2

    @Test
    fun `h - F2, the mark is there and the store has no record - no key`() {
        val f = LegacyGroupMoveFixture()
        f.finishedChat(XA)
        val old = f.OldFile()
        old.apply {
            putRaw(GA, record2124(GA, XA))
            putRaw(MARK, true)
        }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        assertEquals("a look ran after the mark was there", record2124(GA, XA), store.diskSnapshot()[GA])
    }

    // ------------------------------------------------------------------ (i) F3 and (s)

    @Test
    fun `i and s - F3, the store's commit is refused after the look - old file kept, memory as 2_12_4 wrote, next start no key`() {
        val f = LegacyGroupMoveFixture()
        f.finishedChat(XA)
        val old = f.OldFile().apply { putRaw(GA, record2124(GA, XA)) }
        val store = f.StoreFile().apply { refuseCommit = true }

        f.move(old, store, storeIsNew = true)

        assertEquals("the old file did not keep its record", record2124(GA, XA), old.snapshot()[GA])
        assertEquals("the old file did not keep its mark", true, old.snapshot()[MARK])
        assertNull("the disk layer holds a record of a refused commit", store.diskSnapshot()[GA])
        assertEquals("memory holds the looked-at record after a refused commit", record2124(GA, XA), store.memorySnapshot()[GA])

        val next = store.restart()
        f.move(old, next, storeIsNew = false)
        assertEquals("the next start brought a looked-at record", record2124(GA, XA), next.diskSnapshot()[GA])
    }

    // ------------------------------------------------------------------ (j) F4, F5

    @Test
    fun `j - F4 and F5, the emptying is refused - the next call adds nothing, writes nothing twice and empties the file`() {
        val f = LegacyGroupMoveFixture()
        val admin = f.finishedChat(XA)
        val old = f.OldFile()
        old.apply {
            putRaw(GA, record2124(GA, XA))
            refuseClear = true
        }
        f.deleteAnswers = false
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)
        assertEquals("fixture: the old file kept its record", record2124(GA, XA), old.snapshot()[GA])
        val after = store.diskSnapshot()
        val commits = store.commits

        old.refuseClear = false
        f.move(old, store, storeIsNew = false)

        assertEquals("the next call changed the store", after, store.diskSnapshot())
        assertEquals("the next call wrote a record twice", commits, store.commits)
        assertEquals(admin.publicKey, creatorKey(store.diskSnapshot()[GA]))
        assertEquals("the old file was not emptied", emptyMap<String, Any?>(), old.snapshot())
    }

    // ------------------------------------------------------------------ (k)

    @Test
    fun `k - not the first start - no look and no mark`() {
        val f = LegacyGroupMoveFixture()
        f.finishedChat(XA)
        val old = f.OldFile().apply { putRaw(GA, record2124(GA, XA)) }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = false)

        assertEquals(record2124(GA, XA), store.diskSnapshot()[GA])
        assertFalse("a mark was written outside the first start", f.log.contains("old.commit:mark"))
    }

    // ------------------------------------------------------------------ (l)

    @Test
    fun `l - source - one caller each, the order inside the move, the guard, and nothing withdrawn is back`() {
        val store = source("datasource/ZchatPreferences.kt")
        val main = mainSources()
        val lookCallers = main.filter { (_, text) -> text.contains("firstLook(") }.keys
        val allowed = setOf("GroupAdminCheck.kt", "ZchatPreferences.kt")
        assertEquals("firstLook( is named outside GroupAdminCheck.kt and ZchatPreferences.kt", allowed, lookCallers)
        assertEquals("firstLook( is called more than once in the store", 1, Regex("""\bfirstLook\(""").findAll(store).count())
        val moveCalls = Regex("""\bmoveLegacyGroupRecords\(""").findAll(store).count()
        assertEquals("moveLegacyGroupRecords has not one caller (and one declaration)", 2, moveCalls)

        val body = memberBody(store, "fun moveLegacyGroupRecords(")
        val markCommit = Regex("""putBoolean\(\s*ADMIN_LOOK_MARK\s*,\s*true\s*\)""").find(body)?.range?.first ?: -1
        assertTrue("the mark is not written with putBoolean(ADMIN_LOOK_MARK, true)", markCommit >= 0)
        assertTrue("firstLook( comes before the mark's write", body.indexOf("firstLook(") > markCommit)
        val beforeLook = body.substring(0, body.indexOf("firstLook("))
        assertTrue("firstLook( is not inside a per-record guard", beforeLook.lastIndexOf("try {") > beforeLook.lastIndexOf("for ("))

        val migrate = memberBody(store, "fun migrateMessagePlaintextPrefs(")
        assertFalse("migrateMessagePlaintextPrefs still moves the group file", migrate.contains("GROUP_INFO_PREFS_NAME,"))

        val opened = store.indexOf("openGroupStoreAndTellIfNew(")
        assertTrue("the group store is not opened through openGroupStoreAndTellIfNew", opened >= 0)
        assertTrue("the group store is opened before the key store", opened > store.indexOf("private val e2ePrefs"))
        val init = store.indexOf("init {", opened)
        assertTrue("the move's init block is not directly below the group store", init in opened..(opened + 1_200))
        val initBlock = store.substring(init, init + 600)
        assertTrue("the init block below the group store does not run the move", initBlock.contains("moveLegacyGroupRecords("))

        for (withdrawn in listOf("retryFirstLook", "isFirstLookSaved", "admin_checked")) {
            assertTrue("the main sources name $withdrawn", main.values.none { it.contains(withdrawn) })
        }
    }

    // ------------------------------------------------------------------ (m) F2a

    @Test
    fun `m - F2a, the look throws for one record - that record byte for byte, the others looked, the call returns`() {
        val f = LegacyGroupMoveFixture()
        val admin = f.finishedChat(XA)
        f.finishedChat(XB)
        f.keyStoreThrowsFor = XB
        val old = f.OldFile()
        old.apply {
            putRaw(GA, record2124(GA, XA))
            putRaw(GB, record2124(GB, XB))
        }
        val store = f.StoreFile()

        val line = f.move(old, store, storeIsNew = true)

        assertEquals(record2124(GB, XB), store.diskSnapshot()[GB])
        assertEquals(admin.publicKey, creatorKey(store.diskSnapshot()[GA]))
        assertTrue("a key was read before the mark's commit", firstKeyRead(f) > f.log.indexOf("old.commit:mark"))
        assertEquals("the old file is not empty after the committed move", emptyMap<String, Any?>(), old.snapshot())
        assertNotNull("no line of counts", line)
        assertTrue("the line of counts does not say that one look threw: $line", line!!.contains("threw=1"))
    }

    // ------------------------------------------------------------------ (n) F2b

    @Test
    fun `n - F2b, the key store is empty in the first start - nothing is written, now or when a chat comes back`() {
        val f = LegacyGroupMoveFixture()
        val old = f.OldFile().apply { putRaw(GA, record2124(GA, XA)) }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)
        assertTrue("the mark was not written", f.log.contains("old.commit:mark"))
        assertEquals(record2124(GA, XA), store.diskSnapshot()[GA])

        f.finishedChat(XA) // a kept copy of the key store put back
        f.move(old, store, storeIsNew = false)
        assertEquals("a record was filled after the first start", record2124(GA, XA), store.diskSnapshot()[GA])
    }

    // ------------------------------------------------------------------ (o)

    @Test
    fun `o - a String under the mark's key is not a mark - it is written over and the others get their look`() {
        val f = LegacyGroupMoveFixture()
        val admin = f.finishedChat(XA)
        val old = f.OldFile()
        old.apply {
            putRaw(MARK, "not a mark")
            putRaw(GA, record2124(GA, XA))
        }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        assertEquals("a String under the mark's key was taken for a mark", admin.publicKey, creatorKey(store.diskSnapshot()[GA]))
        assertNull("the mark's key was moved into the store as a group", store.diskSnapshot()[MARK])
    }

    // ------------------------------------------------------------------ (p)

    @Test
    fun `p - not the main process, or a process-name test that throws - no look and no mark`() {
        for (mainProcess in listOf({ false }, { throw IllegalStateException("synthetic: Unable to determine process name") })) {
            val f = LegacyGroupMoveFixture()
            f.finishedChat(XA)
            val old = f.OldFile().apply { putRaw(GA, record2124(GA, XA)) }
            val store = f.StoreFile()

            f.move(old, store, storeIsNew = true, mainProcess = mainProcess)

            assertEquals("a look ran outside the main process", record2124(GA, XA), store.diskSnapshot()[GA])
            assertFalse("a mark was written outside the main process", f.log.contains("old.commit:mark"))
        }
    }

    // ------------------------------------------------------------------ (q)

    @Test
    fun `q - the mark's write throws - no look, the records come across as they are, the call returns`() {
        val f = LegacyGroupMoveFixture()
        f.finishedChat(XA)
        val old = f.OldFile()
        old.apply {
            putRaw(GA, record2124(GA, XA))
            throwOnMark = true
        }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        assertEquals(record2124(GA, XA), store.diskSnapshot()[GA])
    }

    // ------------------------------------------------------------------ (r) F3a

    @Test
    fun `r - F3a, the store's put or commit throws - the old file keeps its records and the mark, the call returns`() {
        val f = LegacyGroupMoveFixture()
        f.finishedChat(XA)
        val old = f.OldFile().apply { putRaw(GA, record2124(GA, XA)) }
        val store = f.StoreFile().apply { throwOnPut = true }

        f.move(old, store, storeIsNew = true)

        assertEquals(record2124(GA, XA), old.snapshot()[GA])
        assertEquals(true, old.snapshot()[MARK])
        assertNull(store.diskSnapshot()[GA])
    }

    // ------------------------------------------------------------------ (t)

    @Test
    fun `t - both ways of emptying fail - the clear was tried, then the delete, and nothing else changes`() {
        val f = LegacyGroupMoveFixture()
        val admin = f.finishedChat(XA)
        val old = f.OldFile()
        old.apply {
            putRaw(GA, record2124(GA, XA))
            refuseClear = true
        }
        f.deleteAnswers = false
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        val clear = f.log.indexOf("old.commit:clear")
        assertTrue("the clear was not tried", clear >= 0)
        assertTrue("the delete was not tried after the clear", f.log.indexOf("delete:zchat_group_info") > clear)
        assertEquals(admin.publicKey, creatorKey(store.diskSnapshot()[GA]))
    }

    // ------------------------------------------------------------------ (u)

    @Test
    fun `u - a record that already has a creator_pub keeps it`() {
        val f = LegacyGroupMoveFixture()
        f.finishedChat(XA)
        val kept = "BFsyntheticCreatorPubThatIsKept"
        val withKey = looked(record2124(GA, XA), kept)
        val old = f.OldFile().apply { putRaw(GA, withKey) }
        val store = f.StoreFile()

        f.move(old, store, storeIsNew = true)

        assertEquals(withKey, store.diskSnapshot()[GA])
    }

    // ------------------------------------------------------------------ (v)

    @Test
    fun `v - one line of counts per call that found records, with the conditions and the numbers and nothing that names a group`() {
        val f = LegacyGroupMoveFixture()
        val admin = f.finishedChat(XA)
        val old = f.OldFile()
        old.apply {
            putRaw(GA, record2124(GA, XA))
            putRaw(GB, record2124(GB, XB))
        }
        val store = f.StoreFile()

        val line = f.move(old, store, storeIsNew = true) ?: throw AssertionError("the move that found records wrote no line of counts")

        val parts =
            listOf(
                "main=true",
                "noMark=true",
                "newFile=true",
                "markWritten=true",
                "moved=2",
                "looked=2",
                "keys=1",
                "threw=0",
                "committed=true",
            )
        for (part in parts) {
            assertTrue("the line of counts lacks $part: $line", line.contains(part))
        }
        for (secret in listOf(GA, GB, XA, XB, admin.publicKey)) {
            assertFalse("the line of counts names an id, an address or a key", line.contains(secret))
        }
        assertNull("a call that found nothing wrote a line", f.move(old, store, storeIsNew = false))
    }

    // ------------------------------------------------------------------ sources

    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private fun source(path: String): String = File(root(), "$CHAT/$path").readText()

    private fun mainSources(): Map<String, String> =
        File(root(), "ui-lib/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .associate { it.name to it.readText() }

    /** The text of the member that starts at [start] in [text], to its closing brace. */
    private fun memberBody(
        text: String,
        start: String,
    ): String {
        val from = text.indexOf(start)
        if (from < 0) throw AssertionError("KI-25 pair 3 (ZC-598): no such operation: $start")
        var depth = 0
        var i = text.indexOf('{', from)
        val open = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        return text.substring(open)
    }

    private companion object {
        const val CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"
        const val GA = "zgrp_598a00000000000000000001"
        const val GB = "zgrp_598b00000000000000000002"
        const val GC = "zgrp_598c00000000000000000003"
        const val XA = "usyntheticzc598creatora00001"
        const val XB = "usyntheticzc598creatorb00001"
        const val XC = "usyntheticzc598creatorc00001"
        const val Z = "usyntheticzc598aliastarget01"
        val BROKEN = JSONObject().put("not", "a group").toString()
    }
}
