package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * ZC-692-W (register ZC-692, high; the Destroy half of ZC-090; ZC-726; owner rule W-41: when the user clicks
 * Destroy All it must not be possible to get anything back): the record of a Destroy.
 *
 * Before anything is deleted, Destroy All writes one small file, `no_backup/zchat_destroy_pending`, so that a wipe
 * that was cut off can be finished at the next start (batch ZC-692-S reads it). It must be written whole, once,
 * only after the last confirming act, and it must not fit a wallet that is made later: it names this install (the
 * first-install time) and this phrase-store file (a hash of the two entries under which the encryption library keeps
 * that file's key material). It holds no name, no address and no phrase.
 *
 * Also here: Destroy All runs once per `DestroyManager` object (ZC-726: the Emergency Destroy screen calls it a
 * second time after an error), and nothing of a wipe leaks into the next test of the same JVM.
 *
 * Every new product name is reached through [FakePhone] by reflection, so this file compiles before the fix.
 */
class DestroyRecordIsWrittenOnceAfterTheLastTapTest {
    private var handlerBefore: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        handlerBefore = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(handlerBefore)
    }

    private val key = "12a9aabbccddeeff"
    private val value = "1288ffeeddccbbaa"

    private fun FakePhone.record(): String? = recordFile.takeIf { it.isFile }?.readText()

    // ------------------------------------------------------------------ who writes it, and when

    @Test
    fun `only performFullWipe writes the record`() {
        val writers =
            DestroySources
                .shipped(setOf("kt", "java"))
                .filter { ".writeRecord(" in SourceScan.stripComments(it.readText()) }
                .map { DestroySources.relative(it) }
        assertEquals("the record writer is called from DestroyManager.kt only", listOf(DestroySources.MANAGER), writers)

        val manager = DestroySources.code(DestroySources.MANAGER)
        val wipe = DestroySources.block(manager, "private suspend fun performFullWipe()")
        assertNotNull("fixture: performFullWipe in DestroyManager.kt", wipe)
        assertEquals(
            "DestroyManager.kt calls the record writer exactly once",
            1,
            Regex("""\.writeRecord\(""").findAll(manager).count()
        )
        assertTrue("the one call of the record writer is inside performFullWipe", ".writeRecord(" in wipe!!)

        val namers =
            DestroySources
                .shipped(setOf("kt", "java", "xml"))
                .filter { "zchat_destroy_pending" in it.readText() }
                .map { DestroySources.relative(it) }
        assertEquals("only DestroyWipe.kt names the record file", listOf(DestroySources.WIPE), namers)
    }

    @Test
    fun `destroyAll is called from exactly three places, each after the last confirming act`() {
        val callers =
            DestroySources
                .shipped(setOf("kt", "java"))
                .associate { file ->
                    val calls = Regex("""\.destroyAll\(""").findAll(SourceScan.stripComments(file.readText()))
                    DestroySources.relative(file) to calls.count()
                }.filterValues { it > 0 }
        assertEquals(
            "the callers of destroyAll (file to number of calls)",
            mapOf(ANDROID_CHAT to 1, CHAT_VIEW_MODEL to 1, EMERGENCY_VM to 2),
            callers.toSortedMap()
        )

        // 1. The chat list: inside onDestroyAll, which the final dialog's confirm button calls and nothing else does.
        val androidChat = DestroySources.code(ANDROID_CHAT)
        val onDestroyAll = DestroySources.block(androidChat, "onDestroyAll = {")
        assertTrue("AndroidChat calls destroyAll inside onDestroyAll", onDestroyAll != null && ".destroyAll(" in onDestroyAll)

        val listRaw = ChatResourceText.source(CHAT_LIST_VIEW)
        val list = SourceScan.stripComments(listRaw)
        assertEquals("ChatListView calls onDestroyAll() exactly once", 1, Regex("""\bonDestroyAll\(\)""").findAll(list).count())
        val dialogAt = listRaw.indexOf("// Final Destroy Confirmation Dialog")
        assertTrue("fixture: the final Destroy dialog of ChatListView.kt", dialogAt >= 0)
        val confirm = DestroySources.block(SourceScan.stripComments(listRaw.substring(dialogAt)), "confirmButton = {")
        assertTrue(
            "the one onDestroyAll() of ChatListView is inside the final dialog's confirm button",
            confirm != null && "onDestroyAll()" in confirm
        )

        // 2. Emergency Destroy: both calls inside onFinalDestroy, which only the end of the countdown reaches.
        val emergency = DestroySources.code(EMERGENCY_VM)
        val finalDestroy = DestroySources.block(emergency, "private fun onFinalDestroy()")
        assertNotNull("fixture: onFinalDestroy in EnhancedDestroyVM.kt", finalDestroy)
        assertEquals("both destroyAll calls are inside onFinalDestroy", 2, Regex("""\.destroyAll\(""").findAll(finalDestroy!!).count())
        assertEquals(
            "EnhancedDestroyVM names onFinalDestroy() twice: where it is declared and at the end of the countdown",
            2,
            Regex("""\bonFinalDestroy\(\)""").findAll(emergency).count()
        )
        val countdown = DestroySources.block(emergency, "private fun onStartCountdown()")
        assertNotNull("fixture: onStartCountdown in EnhancedDestroyVM.kt", countdown)
        val loopAt = countdown!!.indexOf("for (i in 5 downTo 1)")
        val delayAt = countdown.indexOf("delay(1000)", loopAt)
        val callAt = countdown.indexOf("onFinalDestroy()")
        assertTrue(
            "the wipe is reached only after the five-second countdown ran to its end (loop $loopAt, delay $delayAt, call $callAt)",
            loopAt >= 0 && delayAt > loopAt && callAt > delayAt
        )
        // The screen gets the function only as a field of its state and never calls it.
        val namers =
            DestroySources
                .shipped(setOf("kt", "java"))
                .filter { "onFinalDestroy" in SourceScan.stripComments(it.readText()) }
                .map { DestroySources.relative(it) }
        assertEquals("the files that name onFinalDestroy", listOf(EMERGENCY_STATE, EMERGENCY_VM), namers.sorted())
        val allCode = DestroySources.shipped(setOf("kt")).joinToString("\n") { SourceScan.stripComments(it.readText()) }
        assertEquals("nothing calls the state's onFinalDestroy", 0, Regex("""\.onFinalDestroy\b""").findAll(allCode).count())

        // 3. The dormant remote kill: after the signal was recorded as acted on.
        val chat = DestroySources.code(CHAT_VIEW_MODEL)
        val callIndex = chat.indexOf("destroyManager.destroyAll(")
        val markIndex = chat.lastIndexOf("markKillSignalTxProcessed(txId)", callIndex)
        assertTrue("ChatViewModel's one destroyAll follows markKillSignalTxProcessed", markIndex in 0 until callIndex)
        assertFalse(
            "the remote-kill branch and its destroyAll are in one function",
            Regex("""\bfun\s""").containsMatchIn(chat.substring(markIndex, callIndex))
        )
    }

    // ------------------------------------------------------------------ its content

    @Test
    fun `the record holds five lines that name this install and this phrase store`() =
        FakePhone().use { phone ->
            phone.put(FakePhone.PHRASE_STORE, FakePhone.phraseStoreXml(key, value))
            phone.installTime = 1_690_000_000_456L

            phone.destroy()

            val text = phone.record()
            assertNotNull("Destroy All wrote no record at no_backup/zchat_destroy_pending", text)
            val lines = text!!.split("\n")
            assertEquals("five lines, each ended by a line feed, and no other text: $lines", 6, lines.size)
            assertEquals("", lines[5])
            assertEquals("zchat-destroy 1", lines[0])
            assertEquals("install=1690000000456", lines[1])
            assertEquals("store=" + FakePhone.storeHash(key, value), lines[2])
            assertEquals("keep=", lines[3])
            assertEquals("end=" + FakePhone.sha256Hex(lines.take(4).joinToString("") { it + "\n" }), lines[4])
            assertEquals(FakePhone.recordText(1_690_000_000_456L, FakePhone.storeHash(key, value)), text)
            assertTrue("the store line is a SHA-256 in hex", Regex("store=[0-9a-f]{64}").matches(lines[2]))
            assertFalse("the record must not hold the key material itself", key in text || value in text)
        }

    @Test
    fun `with a bak twin the hash is taken from it`() =
        FakePhone().use { phone ->
            phone.put(FakePhone.PHRASE_STORE, FakePhone.phraseStoreXml("12aaaa", "12bbbb"))
            phone.put(FakePhone.PHRASE_STORE + ".bak", FakePhone.phraseStoreXml(key, value))

            phone.destroy()

            assertEquals(FakePhone.recordText(phone.installTime, FakePhone.storeHash(key, value)), phone.record())
        }

    @Test
    fun `with no phrase store, or one that lacks an entry, the record says store=none`() {
        FakePhone().use { phone ->
            phone.destroy()
            assertEquals(FakePhone.recordText(phone.installTime, "none"), phone.record())
        }
        FakePhone().use { phone ->
            phone.put(FakePhone.PHRASE_STORE, "<map>\n    <string name=\"${FakePhone.KEY_KEYSET}\">$key</string>\n</map>\n")
            phone.destroy()
            assertEquals(FakePhone.recordText(phone.installTime, "none"), phone.record())
        }
    }

    @Test
    fun `the install time is not known - install=0 and the wipe goes on`() {
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.failAt += "pm.firstInstallTime"
            phone.destroy()
            assertTrue("the record says install=0: ${phone.record()}", phone.record().orEmpty().contains("\ninstall=0\n"))
            assertEquals((FakePhone.STAYS + "data/no_backup/zchat_destroy_pending").sorted(), phone.files())
            assertTrue("the process end is requested", phone.processEndRequested)
        }
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.hangAt += "pm.firstInstallTime"
            phone.destroy()
            assertTrue("the record says install=0: ${phone.record()}", phone.record().orEmpty().contains("\ninstall=0\n"))
            assertEquals(
                "the one read of the install time is waited for 0.3 s and no longer",
                listOf(300L),
                phone.waits.filter { "install" in it.first }.map { it.second }
            )
            assertEquals((FakePhone.STAYS + "data/no_backup/zchat_destroy_pending").sorted(), phone.files())
        }
    }

    // ------------------------------------------------------------------ whole, once

    @Test
    fun `a kill between the temp write and the rename leaves no record and every file as it was`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            val before = phone.files()
            phone.killAt = { operation, file -> operation == "rename" && file.name == "zchat_destroy_pending" }

            phone.destroy()

            assertTrue("fixture: the kill happened", phone.dead)
            assertFalse("no file of the final name", phone.recordFile.exists())
            assertNull("the temp name is never read as a record", phone.wipe("readRecord", phone.context))
            assertEquals(
                "nothing was deleted before the record was there",
                (before + "data/no_backup/zchat_destroy_pending.tmp").sorted(),
                phone.files()
            )
        }

    @Test
    fun `a kill right after the rename leaves a whole record`() =
        FakePhone().use { phone ->
            phone.put(FakePhone.PHRASE_STORE, FakePhone.phraseStoreXml(key, value))
            phone.killAt = { operation, file -> operation == "sync" && file.name == "no_backup" }

            phone.destroy()

            assertTrue("fixture: the kill happened", phone.dead)
            val expected = FakePhone.recordText(phone.installTime, FakePhone.storeHash(key, value))
            assertEquals(expected, phone.record())
            assertEquals(expected, phone.wipe("readRecord", phone.context))
            assertFalse("the temp file is gone after the rename", File(phone.dataDir, "no_backup/zchat_destroy_pending.tmp").exists())
        }

    @Test
    fun `a record this process wrote is never rewritten`() =
        FakePhone().use { phone ->
            phone.put(FakePhone.PHRASE_STORE, FakePhone.phraseStoreXml(key, value))
            val first = phone.manager()
            phone.destroy(first)
            val written = phone.record()
            assertEquals(FakePhone.recordText(phone.installTime, FakePhone.storeHash(key, value)), written)
            val writes = phone.count("write:")

            // A rewrite would now say another install time and store=none: the phrase store is gone.
            phone.installTime = 5L
            phone.destroy(first)
            assertEquals("a second call on the same object", written, phone.record())
            phone.destroy(phone.manager())
            assertEquals("a second manager object over the same folder", written, phone.record())
            assertEquals("no second write of any record file", writes, phone.count("write:"))
        }

    @Test
    fun `a record of an earlier start is replaced whole`() {
        FakePhone().use { phone ->
            phone.put(FakePhone.PHRASE_STORE, FakePhone.phraseStoreXml(key, value))
            phone.put("data/no_backup/zchat_destroy_pending", "whatever an earlier start left\n")
            phone.destroy()
            assertEquals(FakePhone.recordText(phone.installTime, FakePhone.storeHash(key, value)), phone.record())
        }
        FakePhone().use { phone ->
            phone.put(FakePhone.PHRASE_STORE, FakePhone.phraseStoreXml(key, value))
            phone.put("data/no_backup/zchat_destroy_pending", "whatever an earlier start left\n")
            phone.killAt = { operation, file -> operation == "rename" && file.name == "zchat_destroy_pending" }
            phone.destroy()
            assertEquals(
                "a kill between the temp write and the rename leaves the old bytes",
                "whatever an earlier start left\n",
                phone.record()
            )
        }
    }

    @Test
    fun `the folder refuses the write - the wipe still deletes everything and ends the process`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            phone.refuseWrite = { it.name.startsWith("zchat_destroy_pending") }

            phone.destroy()

            assertTrue("fixture: the write of the record was tried", phone.count("write:data/no_backup/zchat_destroy_pending") > 0)
            assertEquals("everything is deleted and there is no record", FakePhone.STAYS.sorted(), phone.files())
            assertTrue("the process end is requested", phone.processEndRequested)
        }

    // ------------------------------------------------------------------ once per object

    @Test
    fun `destroyAll runs once per manager object, also on an object built without its constructor`() =
        FakePhone().use { phone ->
            phone.seedEverything()
            val first = phone.manager()
            phone.destroy(first)
            assertEquals("fixture: the first call asked for the uninstall", 1, phone.count("startActivity"))
            assertTrue("fixture: the first call ended the process", phone.processEndRequested)
            val events = phone.events.size

            phone.destroy(first)
            assertEquals("a second destroyAll on the same object returns at once: no second pass", events, phone.events.size)
            assertEquals("no second uninstall request", 1, phone.count("startActivity"))

            phone.put("data/files/written_between.json")
            phone.destroy(phone.manager())
            assertEquals("a second object does its own wipe: its own uninstall request", 2, phone.count("startActivity"))
            assertFalse("a second object does its own wipe: the file is gone", File(phone.root, "data/files/written_between.json").exists())
            assertEquals("each object ends the process", 2, phone.count("kill:${phone.ownPid}"))
        }

    // ------------------------------------------------------------------ nothing leaks

    @Test
    fun `after destroyAll has returned the default handler for uncaught errors is the one from before`() {
        val mine = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(mine)
        FakePhone().use { phone ->
            phone.destroy()

            assertEquals("one swap and one restore: ${phone.handlerSwaps}", 2, phone.handlerSwaps.size)
            val during = phone.handlerSwaps[0]
            assertTrue("the wipe's own handler is neither null nor the one from before", during != null && during !== mine)
            assertSame("the restore puts back the handler from before", mine, phone.handlerSwaps[1])
            assertSame(mine, Thread.getDefaultUncaughtExceptionHandler())
            val endAt = phone.events.indexOf("kill:${phone.ownPid}")
            val restoreAt = phone.events.lastIndexOf("handler.swap")
            assertTrue("the handler is put back after the request to end the process", endAt in 0 until restoreAt)
        }
    }

    @Test
    fun `when the phone closes every replaceable call and the state of the process are back`() {
        val phone = FakePhone()
        try {
            assertEquals("DestroyWipe lacks these replaceable fields", emptyList<String>(), phone.missingSeams)
            phone.destroy()
            assertNotNull("this process wrote a record, and DestroyWipe remembers which", phone.wipeField("recordWrittenByThisProcess"))
            phone.setWipeField("backgroundSyncHeld", true)
            phone.setWipeField(
                "startOutcome",
                Class.forName(FakePhone.WIPE_CLASS + "\$StartOutcome\$Finished").getField("INSTANCE").get(null)
            )
        } finally {
            phone.close()
        }

        val wipe = Class.forName(FakePhone.WIPE_CLASS)
        for (name in FakePhone.SEAMS) {
            val field = wipe.getDeclaredField(name)
            assertTrue("$name must be private: no shipped code may replace it", Modifier.isPrivate(field.modifiers))
            field.isAccessible = true
            assertSame("$name has its default again", FakePhone.pristineValue(name), field.get(null))
        }
        val state = FakePhone.STATE.associateWith { wipe.getDeclaredField(it).apply { isAccessible = true }.get(null) }
        assertEquals("the 'background sync held' flag", false, state["backgroundSyncHeld"])
        assertEquals("the start outcome", "None", state["startOutcome"]!!.javaClass.simpleName)
        assertNull("the note 'this process wrote a record'", state["recordWrittenByThisProcess"])
        assertEquals(
            "DestroyWipe keeps exactly three fields of state besides its replaceable calls",
            (FakePhone.SEAMS + FakePhone.STATE).sorted(),
            wipe.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) && !it.isSynthetic }
                .map { it.name }
                .sorted()
        )
    }

    private companion object {
        const val ANDROID_CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"
        const val CHAT_LIST_VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt"
        const val CHAT_VIEW_MODEL = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        const val EMERGENCY_VM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/enhanceddestroy/EnhancedDestroyVM.kt"
        const val EMERGENCY_STATE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/enhanceddestroy/EnhancedDestroyState.kt"
    }
}

/** Source helpers for the Destroy tests: the shipped source sets and the code of one block. */
internal object DestroySources {
    const val MANAGER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyManager.kt"
    const val WIPE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyWipe.kt"

    /** Every module whose code is in the APK. */
    val MODULES =
        listOf(
            "app",
            "ui-lib",
            "crash-android-lib",
            "spackle-android-lib",
            "preference-impl-android-lib",
            "sdk-ext-lib",
        )

    /** The source of [path] without comments; empty when the file does not exist. */
    fun code(path: String): String =
        File(ChatResourceText.repoRoot, path).takeIf { it.isFile }?.let { SourceScan.stripComments(it.readText()) }.orEmpty()

    fun relative(file: File): String = file.relativeTo(ChatResourceText.repoRoot).invariantSeparatorsPath

    /** Every file of every source set that ships: each module's `src/<set>` except `test` and `androidTest`. */
    fun shipped(extensions: Set<String>): List<File> =
        MODULES
            .flatMap { module -> File(ChatResourceText.repoRoot, "$module/src").listFiles().orEmpty().toList() }
            .filter { it.isDirectory && it.name != "test" && it.name != "androidTest" }
            .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension in extensions }.toList() }
            .sortedBy { relative(it) }

    /** The block that follows the first [marker] in [source], from its opening brace to the matching one. */
    fun block(
        source: String,
        marker: String
    ): String? {
        val at = source.indexOf(marker)
        val open = if (at < 0) -1 else source.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        return null
    }
}
