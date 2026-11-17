package co.electriccoin.zcash.ui.screen.chat.util

import android.content.Context
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.screen.walletstartup.WalletDataDatabaseFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * ZC-090-O (RESET-LEFTOVERS-SPEC.md revision 2d, sections 4.5 "three things are called set aside" and 6.6; the owner,
 * WAIVERS.md W-52, O2 = yes): a Reset without "keep app data" also deletes old wallet data that ZChat had set aside
 * after a recovery-phrase mismatch (a data database renamed `…sqlite3.set-aside-<time>`, with its journal files, in
 * the SDK folder); with keep it stays; when the plain owner file names another wallet (the owner rule) it stays.
 *
 * On [FakePhone] and the pass of batch ZC-090-P, reached by name. The copy is made by the real
 * [WalletDataDatabaseFiles.setAside], as WalletDataDatabaseFilesTest does. The rule deletes by ONE marker in ONE folder:
 * a `.set-aside-` name in shared_prefs/ and an `__unreadable_` name in the SDK folder are not touched by it.
 */
class ResetAlsoDeletesOldSetAsideWalletDataTest {
    private val phones = ArrayList<FakePhone>()

    @After
    fun tearDown() {
        phones.forEach { it.close() }
        runCatching { Class.forName(RESET_CLASS) }.getOrNull()?.let { setOutcome(it, "NONE") }
    }

    @Test
    fun `old set-aside wallet data - kept with keep, deleted without, kept under the owner rule`() {
        for ((keep, owner) in listOf("all" to "none", "none" to "none", "ai-key" to "none", "none" to OTHER, "ai-key" to OTHER)) {
            val phone = phone(owner)
            val copies = setAside(phone)
            val neighbour = phone.put("${SDK}zcash_sdk_mainnet_fs_cache.sqlite3")
            writeRecord(phone, keep)
            val before = copies.associateWith { it.readBytes().toList() }
            pass(phone)
            val gone = keep != "all" && owner != OTHER
            for (copy in copies) {
                if (gone) {
                    assertFalse("keep=$keep owner=$owner: ${copy.name} stayed", copy.exists())
                } else {
                    assertTrue("keep=$keep owner=$owner: ${copy.name} went", copy.exists())
                    assertEquals("keep=$keep owner=$owner: ${copy.name} changed", before[copy], copy.readBytes().toList())
                }
            }
            // A file of that folder whose name does not hold the marker is judged by its own row: the wallet itself.
            assertFalse("keep=$keep owner=$owner: a non-marker file of the SDK folder stayed", neighbour.exists())
        }
    }

    @Test
    fun `the two markers do not meet - each counts in its own folder only`() {
        for (keep in listOf("none", "ai-key")) {
            val phone = phone("none")
            setAside(phone)
            val prefsNamed = phone.put("data/shared_prefs/zchat_example.set-aside-7.xml")
            val sdkNamed = phone.put("${SDK}zcash_sdk__unreadable_7")
            writeRecord(phone, keep)
            pass(phone)
            // Neither is KEPT by these rules: each is judged by the rows of its own folder (both go without keep).
            assertFalse("keep=$keep: a '.set-aside-' name in shared_prefs is kept as old wallet data", prefsNamed.exists())
            assertFalse("keep=$keep: an '__unreadable_' name in the SDK folder is kept as a kept copy", sdkNamed.exists())
        }
    }

    // ------------------------------------------------------------------ the phone

    private fun phone(owner: String): FakePhone {
        val phone = FakePhone().also { phones += it }
        phone.put(PHRASE, FakePhone.phraseStoreXml(KEY, VALUE))
        phone.put(
            "data/shared_prefs/zchat_chat_owner.xml",
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"owner\">$owner</string>\n</map>\n",
        )
        return phone
    }

    /** Old wallet data set aside by the real WalletDataDatabaseFiles, with a -wal journal beside the database. */
    private fun setAside(phone: FakePhone): List<File> {
        val folder = File(phone.root, SDK)
        File(folder, "zcash_sdk_mainnet_data.sqlite3").apply {
            parentFile?.mkdirs()
            writeText("database")
        }
        File(folder, "zcash_sdk_mainnet_data.sqlite3-wal").writeText("journal")
        assertTrue("fixture: the set-aside did not happen", WalletDataDatabaseFiles(folder).setAside(ZcashNetwork.Mainnet))
        val copies = folder.listFiles().orEmpty().filter { ".set-aside-" in it.name }
        assertEquals("fixture: the database and its journal are set aside", 2, copies.size)
        return copies
    }

    private fun writeRecord(
        phone: FakePhone,
        keep: String,
    ) {
        val body =
            "zchat-reset 1\ninstall=${phone.installTime}\nstore=${FakePhone.storeHash(KEY, VALUE)}\nkeep=$keep\nowner=$OWN\nfiles=\n"
        phone.put("data/no_backup/zchat_reset_pending", body + "end=" + FakePhone.sha256Hex(body) + "\n")
    }

    private fun pass(phone: FakePhone) {
        val clazz = runCatching { Class.forName(RESET_CLASS) }.getOrNull() ?: throw AssertionError("the object ResetWipe does not exist")
        try {
            clazz.getMethod("atProcessStart", Context::class.java).invoke(clazz.getField("INSTANCE").get(null), phone.context)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun setOutcome(
        reset: Class<*>,
        name: String,
    ) {
        val type = reset.getMethod("getStartOutcome").returnType
        val value = type.enumConstants!!.first { (it as Enum<*>).name == name }
        reset.getMethod("setStartOutcome", type).invoke(reset.getField("INSTANCE").get(null), value)
    }

    private companion object {
        const val RESET_CLASS = "co.electriccoin.zcash.ui.screen.chat.util.ResetWipe"
        const val SDK = "data/no_backup/co.electricoin.zcash/"
        const val PHRASE = "data/shared_prefs/co.electriccoin.zcash.encrypted.xml"
        const val KEY = "12a9aabbcc"
        const val VALUE = "1288ddeeff"
        val OWN = "a".repeat(64)
        val OTHER = "b".repeat(64)
    }
}
