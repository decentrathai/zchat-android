package co.electriccoin.zcash.ui.screen.chat.util

import android.content.Context
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest

/**
 * ZC-090-P (register ZC-090, high; RESET-LEFTOVERS-SPEC.md revision 2d, sections 4.2 RS-6 to RS-8, 4.3, 4.5 and 6.2):
 * a Reset that was cut off is finished at the next start, and only its own; one that cannot be finished blocks, and
 * Try again really tries again.
 *
 * The phone is [FakePhone] (batch ZC-692-W): every file the pass may meet under its real name, with a `.bak` twin
 * beside each preferences file of the delete set; two set-aside files of other wallets and the reset wallet's own
 * stale one; another wallet's Contacts, notes and picture folder; five kept copies of stores that could not be read
 * with their marks file; the cache with its three kept folders; files nobody named; the device-protected folder and
 * the external volumes (never touched). Records are written here in the seven-line format of spec 4.3, worked out by
 * this test. Wallet tags are synthetic ('a'.repeat(64)).
 *
 * The product object `ResetWipe` does not exist before the fix: it is reached by name ([pass]), so every case that
 * runs the pass fails at RED, and the source case fails on its missing file. Revision 2d: old wallet data that was set
 * aside is batch ZC-090-O's: the fixture holds `.set-aside-` copies of the SDK folder in the keep=all case only.
 */
class ResetStartPassFinishesOnlyItsOwnResetTest {
    private val phones = ArrayList<FakePhone>()

    @After
    fun tearDown() {
        phones.forEach { it.close() }
        setOutcome("NONE")
    }

    // ------------------------------------------------------------------ cases

    @Test
    fun `no record - nothing is deleted, the start is today's`() {
        val phone = seeded()
        val before = snapshot(phone)
        pass(phone)
        assertEquals("a file changed with no record", before, snapshot(phone))
        assertEquals("no delete without a record", emptyList<String>(), phone.events.filter { it.startsWith("delete") })
        assertEquals("NONE", outcome())
    }

    @Test
    fun `keep=all - the wallet itself and the cache go, every other file stays byte for byte`() {
        val phone = seeded()
        SET_ASIDE_DATA.forEach { phone.put(it) }
        writeRecord(phone, "all")
        val before = snapshot(phone)
        pass(phone)
        val after = snapshot(phone)
        assertEquals("FINISHED", outcome())
        assertGone(after, WALLET_ALWAYS + listOf(PHRASE, "$PHRASE.bak", RECORD))
        assertSame(before, after, before.keys - WALLET_ALWAYS.toSet() - setOf(PHRASE, "$PHRASE.bak", RECORD))
        assertEquals("background sync is not held", true, phone.wipeField("backgroundSyncHeld"))
    }

    @Test
    fun `a bak twin alone is deleted, and counted when its delete is refused`() {
        for (refused in listOf(false, true)) {
            val phone = FakePhone().also { phones += it }
            phone.put(PHRASE, PHRASE_XML)
            phone.put("data/shared_prefs/co.electriccoin.zcash.xml.bak", "<map />\n")
            writeRecord(phone, "all")
            if (refused) phone.refuseDelete = { it.name == "co.electriccoin.zcash.xml.bak" }
            pass(phone)
            val twin = File(phone.root, "data/shared_prefs/co.electriccoin.zcash.xml.bak")
            if (refused) {
                assertTrue("refused: the twin is still there", twin.exists())
                assertEquals("refused: the twin is counted as left", "NOT_FINISHED", outcome())
                assertTrue("refused: the record stays", File(phone.root, RECORD).exists())
            } else {
                assertFalse("the twin alone was not deleted", twin.exists())
                assertEquals("FINISHED", outcome())
            }
        }
    }

    @Test
    fun `keep=none - exactly the kept set is left`() {
        val phone = seeded()
        writeRecord(phone, "none")
        val before = snapshot(phone)
        pass(phone)
        val after = snapshot(phone)
        assertEquals("FINISHED", outcome())
        assertEquals("what is left after keep=none", KEPT_WITHOUT_KEEP.toSortedSet(), after.keys.toSortedSet())
        assertSame(before, after, after.keys)
    }

    @Test
    fun `keep=ai-key - as keep=none, except the reset wallet's own set-aside file and its bak`() {
        val phone = seeded()
        writeRecord(phone, "ai-key")
        val before = snapshot(phone)
        pass(phone)
        val after = snapshot(phone)
        assertEquals("a record with keep=ai-key is valid", "FINISHED", outcome())
        assertEquals("what is left after keep=ai-key", (KEPT_WITHOUT_KEEP + OWN_SET_ASIDE).toSortedSet(), after.keys.toSortedSet())
        assertSame(before, after, after.keys)

        val other = seeded()
        writeRecord(other, "some")
        val untouched = snapshot(other)
        pass(other)
        assertEquals("a fourth keep value is not valid", "IGNORED", outcome())
        assertEquals("a fourth keep value deleted something", untouched - RECORD, snapshot(other))
    }

    @Test
    fun `keep=ai-key with the kept file missing - the AI store stays (guard 1)`() {
        val phone = seeded()
        OWN_SET_ASIDE.forEach { File(phone.root, it).delete() }
        writeRecord(phone, "ai-key")
        val before = snapshot(phone)
        pass(phone)
        val after = snapshot(phone)
        assertEquals("FINISHED", outcome())
        assertEquals((KEPT_WITHOUT_KEEP + AI_STORE).toSortedSet(), after.keys.toSortedSet())
        assertSame(before, after, after.keys)
        assertFalse("the record stayed", File(phone.root, RECORD).exists())
    }

    @Test
    fun `keep=ai-key with the owner still naming the reset wallet - the AI store and the kept file stay (guard 2)`() {
        val phone = seeded()
        phone.put(OWNER_FILE, ownerXml(OWN))
        writeRecord(phone, "ai-key")
        val before = snapshot(phone)
        pass(phone)
        val after = snapshot(phone)
        assertEquals("FINISHED", outcome())
        assertEquals((KEPT_WITHOUT_KEEP + AI_STORE + OWN_SET_ASIDE).toSortedSet(), after.keys.toSortedSet())
        assertSame(before, after, after.keys)
    }

    @Test
    fun `another wallet's kept copies stay (S14), the reset wallet's own go`() {
        for (keep in listOf("none", "ai-key")) {
            val phone = seeded()
            writeRecord(phone, keep)
            val before = snapshot(phone)
            pass(phone)
            val after = snapshot(phone)
            for (kept in COPIES_KEPT + MARKS) assertEquals("$keep: $kept", before[kept], after[kept])
            for (gone in COPIES_OWN) assertFalse("$keep: $gone stayed", gone in after)
        }
        val all = seeded()
        writeRecord(all, "all")
        val before = snapshot(all)
        pass(all)
        val after = snapshot(all)
        for (kept in COPIES_KEPT + COPIES_OWN + MARKS) assertEquals("keep=all: $kept", before[kept], after[kept])

        val none = seeded()
        (COPIES_KEPT).forEach { File(none.root, it).delete() }
        writeRecord(none, "none")
        pass(none)
        assertFalse("with no copy kept the marks file stays", File(none.root, MARKS).exists())

        val unparsable = seeded()
        unparsable.put(MARKS, "<map><string name=\"copy_owner:")
        writeRecord(unparsable, "none")
        val raw = snapshot(unparsable)
        pass(unparsable)
        val left = snapshot(unparsable)
        for (copy in COPIES_KEPT + COPIES_OWN + MARKS) assertEquals("an unreadable marks file: $copy", raw[copy], left[copy])

        val markers = seeded()
        markers.put("data/shared_prefs/zchat_example.set-aside-9.xml")
        markers.put("data/no_backup/co.electricoin.zcash/zcash_sdk__unreadable_9")
        writeRecord(markers, "none")
        pass(markers)
        assertFalse(
            "a '.set-aside-' name in shared_prefs is kept",
            File(markers.root, "data/shared_prefs/zchat_example.set-aside-9.xml").exists(),
        )
        assertFalse(
            "an '__unreadable_' name in the SDK folder is kept",
            File(markers.root, "data/no_backup/co.electricoin.zcash/zcash_sdk__unreadable_9").exists(),
        )
    }

    @Test
    fun `the counter floor and the TEX file stay byte for byte in both`() {
        for (keep in listOf("all", "none", "ai-key")) {
            val phone = seeded()
            writeRecord(phone, keep)
            val before = snapshot(phone)
            pass(phone)
            val after = snapshot(phone)
            for (file in listOf(FLOOR, TEX)) assertEquals("keep=$keep: $file", before[file], after[file])
        }
    }

    @Test
    fun `names that are not names delete nothing, an owner of another shape makes the record not valid`() {
        val phone = seeded()
        val notNames = listOf("../x", "files/address_book/$CONTACTS_OTHER", "", "zashi-address-book-XY", "$NOTES_OTHER/")
        writeRecord(phone, "none", files = notNames)
        val before = snapshot(phone)
        pass(phone)
        val after = snapshot(phone)
        assertEquals("FINISHED", outcome())
        for (kept in listOf(CONTACTS_OTHER_PATH, NOTES_OTHER_PATH)) assertEquals(kept, before[kept], after[kept])
        assertSame(before, after, OUTSIDE.toSet())

        val bad = seeded()
        writeRecord(bad, "none", owner = "A".repeat(64))
        val untouched = snapshot(bad)
        pass(bad)
        assertEquals("IGNORED", outcome())
        assertEquals(untouched - RECORD, snapshot(bad))
    }

    @Test
    fun `a kill after each step of the Reset, then the pass - the same end state`() {
        // S3: the record is written, the owner still names the reset wallet; S4: the owner says none; S6: the saved
        // wallet's entries are cleared (only the key entries are left in the phrase store).
        val expected = KEPT_WITHOUT_KEEP.toSortedSet()
        for (step in listOf("S3", "S4", "S5", "S6", "S7")) {
            val phone = seeded()
            if (step == "S3") phone.put(OWNER_FILE, ownerXml(OWN))
            writeRecord(phone, "none")
            if (step == "S6" || step == "S7") phone.put(PHRASE, keysOnlyXml(KEY_A, VALUE_A))
            pass(phone)
            assertEquals("after $step", "FINISHED", outcome())
            assertEquals("after $step", expected, snapshot(phone).keys.toSortedSet())
        }
    }

    @Test
    fun `a kill inside the pass, then a second pass - the same end state`() {
        val phone = seeded()
        writeRecord(phone, "none")
        phone.killAt = { step, file -> step == "delete" && file.name == "zchat_call_log.xml" }
        pass(phone)
        phone.killAt = null
        phone.newProcess()
        setOutcome("NONE")
        pass(phone)
        assertEquals("FINISHED", outcome())
        assertEquals(KEPT_WITHOUT_KEEP.toSortedSet(), snapshot(phone).keys.toSortedSet())
    }

    @Test
    fun `not finished, then the start makes a new phrase store, then Try again finishes`() {
        val phone = seeded()
        writeRecord(phone, "none")
        phone.refuseDelete = { it.name == "zchat_call_log.xml" }
        pass(phone)
        assertEquals("NOT_FINISHED", outcome())
        assertTrue("the record must stay while something is left", File(phone.root, RECORD).exists())
        // Every start of the app opens the phrase store: a new file with two NEW key entries and nothing else.
        phone.refuseDelete = { false }
        newPhraseStore(phone, keysOnlyXml("12newkey", "12newvalue"))
        phone.newProcess()
        setOutcome("NONE")
        pass(phone)
        assertEquals("the second pass did not find the record valid", "FINISHED", outcome())
        assertFalse("the new phrase-store file was not deleted", File(phone.root, PHRASE).exists())
        assertFalse("the record stayed", File(phone.root, RECORD).exists())
        assertEquals(KEPT_WITHOUT_KEEP.toSortedSet(), snapshot(phone).keys.toSortedSet())
    }

    @Test
    fun `a new store that holds more is not valid - nothing is deleted`() {
        val phone = seeded()
        writeRecord(phone, "none")
        newPhraseStore(phone, FakePhone.phraseStoreXml("12newkey", "12newvalue"))
        val before = snapshot(phone)
        pass(phone)
        assertEquals("IGNORED", outcome())
        assertEquals("a store that may hold a wallet: nothing may go", before - RECORD, snapshot(phone))
    }

    @Test
    fun `a new store cut while it was made - one key entry, or none - is valid`() {
        for (xml in listOf(oneKeyXml(), "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n")) {
            val phone = seeded()
            writeRecord(phone, "none")
            newPhraseStore(phone, xml)
            pass(phone)
            assertEquals("FINISHED", outcome())
            assertFalse(File(phone.root, PHRASE).exists())
        }
    }

    @Test
    fun `a store the scan cannot parse is not valid - nothing is deleted`() {
        val cut = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"${FakePhone.KEY_KEYSET}\">12ab"
        for (text in listOf(cut, "\u0001ÿ\u0010 random bytes \u0000")) {
            val phone = seeded()
            writeRecord(phone, "none")
            newPhraseStore(phone, text)
            val before = snapshot(phone)
            pass(phone)
            assertEquals("IGNORED", outcome())
            assertEquals(before - RECORD, snapshot(phone))
        }
    }

    @Test
    fun `another wallet, another install, a torn or edited record, another keep value - nothing deleted, the record removed`() {
        val cases =
            listOf<(FakePhone) -> Unit>(
                { newPhraseStore(it, FakePhone.phraseStoreXml("12other", "12othervalue")) },
                { it.installTime = it.installTime + 1 },
                { File(it.root, RECORD).writeText(File(it.root, RECORD).readText().dropLast(20)) },
                { File(it.root, RECORD).writeText(File(it.root, RECORD).readText().replace("keep=none", "keep=all")) },
                { File(it.root, RECORD).writeText(record(it.installTime, STORE_A, "keep", OWN, FILES)) },
            )
        cases.forEachIndexed { index, change ->
            val phone = seeded()
            writeRecord(phone, "none")
            change(phone)
            val before = snapshot(phone)
            pass(phone)
            assertEquals("case $index", "IGNORED", outcome())
            assertEquals("case $index", before - RECORD, snapshot(phone))
        }
    }

    @Test
    fun `the start cannot judge - nothing deleted, the record stays byte for byte, the process ends`() {
        for (what in listOf("install time", "process name")) {
            val phone = seeded()
            writeRecord(phone, "none")
            if (what == "install time") phone.installTime = 0L else phone.processName = null
            val before = snapshot(phone)
            pass(phone)
            assertEquals(what, before, snapshot(phone))
            assertTrue("$what: the process end was not requested", phone.processEndRequested)
        }
    }

    @Test
    fun `in another process - nothing deleted, the process ends`() {
        val phone = seeded()
        writeRecord(phone, "none")
        phone.processName = "${phone.packageName}:crash"
        val before = snapshot(phone)
        pass(phone)
        assertEquals(before, snapshot(phone))
        assertTrue(phone.processEndRequested)
    }

    @Test
    fun `the record cannot be removed - the outcome does not block, a later pass with a saved wallet deletes nothing`() {
        val phone = seeded()
        writeRecord(phone, "none")
        phone.refuseDelete = { it.name == ResetName.RECORD }
        pass(phone)
        assertEquals("a record that alone stays must not block", "FINISHED", outcome())
        assertTrue(File(phone.root, RECORD).exists())
        // A wallet is saved afterwards: its phrase store holds more than key entries.
        newPhraseStore(phone, FakePhone.phraseStoreXml("12saved", "12savedvalue"))
        phone.put("data/shared_prefs/zchat_e2e_keys_encrypted.xml", "<map><string name=\"k\">v</string></map>\n")
        phone.newProcess()
        val before = snapshot(phone)
        pass(phone)
        assertEquals("a pass beside a saved wallet deleted something", before.keys - RECORD, snapshot(phone).keys - RECORD)
    }

    @Test
    fun `a record that stayed, and another wallet's claim - only the wallet files go`() {
        for (owner in listOf(OTHER, "none", OWN)) {
            val phone = seeded()
            writeRecord(phone, "none")
            // A wallet setup cut between its claim and its save: the owner names that wallet, its chats are back,
            // the phrase store holds only two key entries.
            phone.put(OWNER_FILE, ownerXml(owner))
            newPhraseStore(phone, keysOnlyXml("12claim", "12claimvalue"))
            pass(phone)
            val after = snapshot(phone).keys
            if (owner == OTHER) {
                for (store in CHAT_STORES + OWN_PICTURES) assertTrue("owner rule: $store was deleted", store in after)
                for (wallet in WALLET_ALWAYS) assertFalse("owner rule: $wallet stayed", wallet in after)
            } else {
                for (store in CHAT_STORES + OWN_PICTURES) assertFalse("owner $owner: $store stayed", store in after)
            }
        }
    }

    @Test
    fun `a Destroy record file beside a valid Reset record - nothing deleted until it is gone`() {
        val phone = seeded()
        writeRecord(phone, "none")
        phone.put("data/no_backup/zchat_destroy_pending", "zchat-destroy 1\n")
        val before = snapshot(phone)
        pass(phone)
        assertEquals("NOT_FINISHED", outcome())
        assertEquals("a Reset delete beside a Destroy record", before, snapshot(phone))
        File(phone.root, "data/no_backup/zchat_destroy_pending").delete()
        phone.newProcess()
        pass(phone)
        assertEquals("FINISHED", outcome())
    }

    @Test
    fun `never - no key, no clipboard, nothing outside the data folder, no notification channel`() {
        for (keep in listOf("all", "none", "ai-key")) {
            val phone = seeded()
            writeRecord(phone, keep)
            val keys = phone.keys.toSet()
            val channels = phone.channels.toList()
            val before = snapshot(phone)
            pass(phone)
            val after = snapshot(phone)
            assertEquals("keep=$keep: a Keystore key was deleted", keys, phone.keys.toSet())
            assertFalse("keep=$keep: the clipboard was cleared", phone.events.any { it.startsWith("clipboard") })
            assertEquals("keep=$keep: a notification channel was deleted", channels, phone.channels.toList())
            assertSame(before, after, OUTSIDE.toSet())
        }
    }

    @Test
    fun `it never throws - a file layer that throws at every call`() {
        val phone = seeded()
        writeRecord(phone, "none")
        phone.killAt = { _, _ -> true }
        pass(phone)
    }

    @Test
    fun `where it is called - right after Destroy's pass, raw files only, the main process only`() {
        val app = SourceScan.stripComments(source("app/src/main/java/co/electriccoin/zcash/app/ZcashApplication.kt"))
        val destroy = app.indexOf("DestroyWipe.atProcessStart(this)")
        val reset = app.indexOf("ResetWipe.atProcessStart(this)")
        assertTrue("ZcashApplication does not call ResetWipe.atProcessStart(this)", reset >= 0)
        val between = app.substring(destroy + "DestroyWipe.atProcessStart(this)".length, reset)
        assertTrue("another statement stands between the two passes", between.isBlank())
        assertTrue("the pass stands after startKoin", reset < app.indexOf("startKoin {"))
        // 450bbd6e3 narrowed Destroy's "called once" to Destroy's own call; the Reset pass is pinned the same way here.
        assertEquals("ResetWipe.atProcessStart is called once", 1, Regex("""ResetWipe\.atProcessStart\(""").findAll(app).count())
        assertTrue("Destroy's pass comes first, the Reset pass after it", destroy in 0 until reset)
        assertFalse("DestroyWipe.kt names ResetWipe", "ResetWipe" in source(DESTROY_WIPE))
        val wipe = source(RESET_WIPE)
        for (name in listOf("getSharedPreferences", "EncryptedSharedPreferences", "MasterKey", "koin", "PreferenceProvider")) {
            assertFalse("ResetWipe.kt names $name: it reads raw files only", name in wipe)
        }
        assertTrue("ResetWipe.kt does not use MainProcess.isMain", "MainProcess.isMain" in wipe)
    }

    // ------------------------------------------------------------------ the phone

    private fun seeded(): FakePhone {
        val phone = FakePhone().also { phones += it }
        phone.put(PHRASE, PHRASE_XML)
        phone.put("$PHRASE.bak", PHRASE_XML)
        (WALLET_ALWAYS + CACHE_KEPT + DATA + OWN_SET_ASIDE + KEPT_PLAIN + COPIES_KEPT + COPIES_OWN + OUTSIDE).forEach { phone.put(it) }
        phone.put(OWNER_FILE, ownerXml("none"))
        phone.put(MARKS, MARKS_XML)
        phone.put(AI_STORE[0], "<map><string name=\"ai_token\">t</string></map>\n")
        phone.keys += listOf("_androidx_security_master_key_", "zchat_ai_master_key")
        phone.channels += listOf("sync_channel_v2", "chat_messages_v3")
        return phone
    }

    /** A phrase-store file written anew (by a start, or by a wallet setup): the file only, no `.bak` twin. */
    private fun newPhraseStore(
        phone: FakePhone,
        text: String,
    ) {
        File(phone.root, "$PHRASE.bak").delete()
        phone.put(PHRASE, text)
    }

    private fun writeRecord(
        phone: FakePhone,
        keep: String,
        owner: String = OWN,
        files: List<String> = FILES,
    ) {
        phone.put(RECORD, record(phone.installTime, STORE_A, "keep=$keep", owner, files))
    }

    /** Every file under the phone's roots and its SHA-256. */
    private fun snapshot(phone: FakePhone): Map<String, String> =
        phone.files().associateWith { path ->
            MessageDigest
                .getInstance("SHA-256")
                .digest(File(phone.root, path).readBytes())
                .joinToString("") { "%02x".format(it) }
        }

    private fun assertGone(
        after: Map<String, String>,
        gone: List<String>,
    ) {
        for (path in gone) assertFalse("$path is still there", path in after)
    }

    private fun assertSame(
        before: Map<String, String>,
        after: Map<String, String>,
        paths: Set<String>,
    ) {
        for (path in paths) assertEquals("$path changed or went", before[path], after[path])
    }

    // ------------------------------------------------------------------ the product, by name

    private object ResetName {
        const val CLASS = "co.electriccoin.zcash.ui.screen.chat.util.ResetWipe"
        const val RECORD = "zchat_reset_pending"
    }

    private fun wipeClass(): Class<*> =
        runCatching { Class.forName(ResetName.CLASS) }.getOrNull() ?: throw AssertionError("the object ResetWipe does not exist")

    private fun pass(phone: FakePhone) {
        val clazz = wipeClass()
        try {
            clazz.getMethod("atProcessStart", Context::class.java).invoke(clazz.getField("INSTANCE").get(null), phone.context)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun outcome(): String {
        val clazz = wipeClass()
        return (clazz.getMethod("getStartOutcome").invoke(clazz.getField("INSTANCE").get(null)) as Enum<*>).name
    }

    private fun setOutcome(name: String) {
        val clazz = runCatching { Class.forName(ResetName.CLASS) }.getOrNull() ?: return
        val type = clazz.getMethod("getStartOutcome").returnType
        val value = type.enumConstants!!.first { (it as Enum<*>).name == name }
        clazz.getMethod("setStartOutcome", type).invoke(clazz.getField("INSTANCE").get(null), value)
    }

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main").isDirectory) dir = dir.parentFile
        val file = File(dir ?: error("repo root not found"), relative)
        return if (file.isFile) file.readText() else ""
    }

    private companion object {
        const val DESTROY_WIPE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyWipe.kt"
        const val RESET_WIPE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/ResetWipe.kt"

        val OWN = "a".repeat(64)
        val OTHER = "b".repeat(64)
        val THIRD = "c".repeat(64)
        const val KEY_A = "12a9aabbcc"
        const val VALUE_A = "1288ddeeff"
        val STORE_A = FakePhone.storeHash(KEY_A, VALUE_A)
        val PHRASE_XML = FakePhone.phraseStoreXml(KEY_A, VALUE_A)

        const val P = "data/shared_prefs/"
        const val SDK = "data/no_backup/co.electricoin.zcash/"
        const val PHRASE = "${P}co.electriccoin.zcash.encrypted.xml"
        const val RECORD = "data/no_backup/zchat_reset_pending"
        const val OWNER_FILE = "${P}zchat_chat_owner.xml"
        const val MARKS = "${P}zchat_encrypted_store_open_failures.xml"
        const val FLOOR = "${P}zchat_ratchet_counter_floor.xml"
        const val TEX = "${P}zchat_tex_recovery.xml"

        const val CONTACTS_OWN = "zashi-address-book-0a0a"
        const val NOTES_OWN = "zashi-metadata-0a0a"
        const val CONTACTS_OTHER = "zashi-address-book-0b0b"
        const val NOTES_OTHER = "zashi-metadata-0b0b"
        const val CONTACTS_OTHER_PATH = "data/files/address_book/$CONTACTS_OTHER"
        const val NOTES_OTHER_PATH = "data/files/metadata/$NOTES_OTHER"
        val FILES = listOf(CONTACTS_OWN, NOTES_OWN)

        /** The wallet itself (without the phrase store and its twin) and the cache except its three kept folders. */
        val WALLET_ALWAYS =
            listOf(
                "${P}co.electriccoin.zcash.xml",
                "${P}co.electriccoin.zcash.xml.bak",
                "${P}cash.z.ecc.android.sdk.xml",
                "${P}cash.z.ecc.android.sdk.xml.bak",
                "${P}cash.z.ecc.android.sdk.encrypted.xml",
                "${P}cash.z.ecc.android.sdk.encrypted.xml.bak",
                "${SDK}zcash_sdk_mainnet_data.sqlite3",
                "${SDK}zcash_sdk_mainnet_data.sqlite3-wal",
                "${SDK}tor_dir/state",
                "data/databases/old_sdk_name.db",
                "data/files/pczt/transaction.pczt",
                "data/cache/share-inbox/shared.bin",
                "data/cache/apk_updates/update.apk",
                "data/cache/ai-share/answer.png",
            )

        /** The three cache folders the kept chats point at: they stay with keep=all only. */
        val CACHE_KEPT =
            listOf(
                "data/cache/zchat_files/photo.jpg",
                "data/cache/zchat_audio/voice.m4a",
                "data/cache/zchat_attachment_retry/retry.bin",
            )

        val CHAT_STORES =
            listOf(
                "${P}zchat_e2e_keys_encrypted.xml",
                "${P}zchat_e2e_keys_encrypted.xml.bak",
                "${P}zchat_nicknames_encrypted.xml",
                "${P}zchat_preferences.xml",
                "${P}zchat_conv_mapping.xml",
                "${P}zchat_call_log.xml",
                "${P}zchat_contact_book_enc.xml",
                "${P}zchat_contact_book.xml",
                "${P}zchat_avatar_index_enc.xml",
                "${P}zchat_address_cache.xml",
                "${P}zchat_identity_manager.xml",
            )

        val OWN_PICTURES = listOf("data/files/ai_images/$OWN/p.png", "data/files/ai_images/flat.png")

        val AI_STORE = listOf("${P}zchat_ai_prefs.xml", "${P}zchat_ai_prefs.xml.bak")

        /** What goes with keep unticked and stays with keep=all (besides [CACHE_KEPT], [OWN_SET_ASIDE] and [COPIES_OWN]). */
        val DATA =
            CHAT_STORES + OWN_PICTURES + AI_STORE +
                listOf(
                    "${P}zchat_relay_set.xml",
                    "data/files/avatars/contact.jpg",
                    "data/files/address_book/$CONTACTS_OWN",
                    "data/files/metadata/$NOTES_OWN",
                    "data/no_backup/address_book",
                    "${P}some_library.xml",
                    "data/app_other/x",
                    "data/files/new_folder/x",
                )

        val OWN_SET_ASIDE = listOf("${P}zchat_set_aside_$OWN.xml", "${P}zchat_set_aside_$OWN.xml.bak")

        val COPIES_KEPT =
            listOf(
                "${P}zchat_contact_book_enc__unreadable_100.xml",
                "${P}zchat_set_aside_${OTHER}__unreadable_103.xml",
                "${P}zchat_ai_prefs__unreadable_104.xml",
            )

        val COPIES_OWN =
            listOf(
                "${P}zchat_e2e_keys_encrypted__unreadable_101.xml",
                "${P}zchat_nicknames_encrypted__unreadable_102.xml",
            )

        /** Kept by every keep value, besides the owner file and the marks file. */
        val KEPT_PLAIN =
            listOf(
                "${P}zchat_set_aside_$OTHER.xml",
                "${P}zchat_set_aside_$THIRD.xml",
                CONTACTS_OTHER_PATH,
                NOTES_OTHER_PATH,
                "data/files/ai_images/$OTHER/q.png",
                FLOOR,
                TEX,
                "${P}zchat_theme_prefs.xml",
                "${P}zchat_update_check.xml",
                "${P}zchat_post_upgrade.xml",
                "${SDK}sapling-spend.params",
                "${SDK}sapling-output.params",
                "data/no_backup/androidx.work.workdb",
                "data/no_backup/androidx.work.workdb-wal",
                "data/code_cache/x",
                "data/lib/libexample.so",
                "data/files/qa/seams.json",
                "data/files/profileInstalled",
            )

        /**
         * Two set-aside database copies (one with its journal files): in the keep=all case only, where they stay for
         * good. Revision 2d: what keep unticked does with them is batch ZC-090-O's.
         */
        val SET_ASIDE_DATA =
            listOf(
                "${SDK}zcash_sdk_mainnet_data.sqlite3.set-aside-1",
                "${SDK}zcash_sdk_mainnet_data.sqlite3.set-aside-2",
                "${SDK}zcash_sdk_mainnet_data.sqlite3.set-aside-2-wal",
                "${SDK}zcash_sdk_mainnet_data.sqlite3.set-aside-2-shm",
            )

        /** Never touched: the device-protected folder and the external volumes. */
        val OUTSIDE =
            listOf(
                "de/shared_prefs/device_protected.xml",
                "vol0/Android/data/xyz.example.zchat/files/log/exception/crash_1.txt",
                "vol1/Android/data/xyz.example.zchat/cache/external.tmp",
            )

        /** Exactly what is left after keep=none (the record removed). */
        val KEPT_WITHOUT_KEEP = KEPT_PLAIN + COPIES_KEPT + MARKS + OWNER_FILE + OUTSIDE

        val MARKS_XML =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
                "    <string name=\"copy_owner:zchat_contact_book_enc__unreadable_100\">$OTHER</string>\n" +
                "    <string name=\"copy_owner:zchat_e2e_keys_encrypted__unreadable_101\">$OWN</string>\n" +
                "    <string name=\"copy_owner:zchat_ai_prefs__unreadable_104\">$OWN</string>\n" +
                "    <boolean name=\"restore_tried:$OWN:zchat_e2e_keys_encrypted\" value=\"true\" />\n" +
                "    <boolean name=\"restore_tried:$OTHER:zchat_contact_book_enc\" value=\"true\" />\n" +
                "    <int name=\"zchat_e2e_keys_encrypted\" value=\"2\" />\n</map>\n"

        fun ownerXml(owner: String): String =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"owner\">$owner</string>\n</map>\n"

        fun keysOnlyXml(
            key: String,
            value: String,
        ): String =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
                "    <string name=\"${FakePhone.KEY_KEYSET}\">$key</string>\n" +
                "    <string name=\"${FakePhone.VALUE_KEYSET}\">$value</string>\n</map>\n"

        fun oneKeyXml(): String =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
                "    <string name=\"${FakePhone.KEY_KEYSET}\">12cut</string>\n</map>\n"

        /** The seven lines of spec 4.3, worked out here and not by the product. */
        fun record(
            install: Long,
            store: String,
            keepLine: String,
            owner: String,
            files: List<String>,
        ): String {
            val body = "zchat-reset 1\ninstall=$install\nstore=$store\n$keepLine\nowner=$owner\nfiles=${files.joinToString(",")}\n"
            return body + "end=" + FakePhone.sha256Hex(body) + "\n"
        }
    }
}
