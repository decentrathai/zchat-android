package co.electriccoin.zcash.ui.screen.walletstartup

import cash.z.ecc.android.sdk.model.ZcashNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test

/**
 * P2f, plan change 4: the old wallet data database is set aside by RENAMING it, never deleted, together with
 * its SQLite journal files so it can still be opened later with everything it held. Anything else in the SDK
 * folder (the block cache, another network's database, Tor state) is left alone. When a file cannot be moved,
 * every file goes back to its own name and nothing is lost.
 */
class WalletDataDatabaseFilesTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private val dataDb = "zcash_sdk_mainnet_data.sqlite3"
    private val sidecars = listOf("-wal", "-shm", "-journal")
    private val clock = 1_700_000_000_000L

    private fun sdkDir(): File =
        tempDir.newFolder("co.electricoin.zcash").also { dir ->
            File(dir, dataDb).writeText("old wallet database")
            sidecars.forEach { File(dir, dataDb + it).writeText("old wallet $it") }
            File(dir, "zcash_sdk_testnet_data.sqlite3").writeText("other network")
            File(dir, "zcash_sdk_mainnet_fs_cache").apply { mkdirs() }.resolve("blocks").writeText("block cache")
            File(dir, "tor").apply { mkdirs() }.resolve("state").writeText("tor state")
        }

    private fun snapshot(dir: File): Map<String, String> =
        dir
            .walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(dir).path to it.readText() }

    @Test
    fun `the data database and its journal files are renamed, nothing is deleted and nothing else moves`() {
        val dir = sdkDir()
        val before = snapshot(dir)

        assertTrue(WalletDataDatabaseFiles(dir, now = { clock }).setAside(ZcashNetwork.Mainnet))

        val after = snapshot(dir)
        assertFalse("the SDK must find no data database, so it starts a new one", File(dir, dataDb).exists())
        sidecars.forEach { assertFalse("$it must move with the database", File(dir, dataDb + it).exists()) }
        assertEquals(
            "every file is still on the phone with the same contents (renamed, never deleted)",
            before.values.sorted(),
            after.values.sorted(),
        )
        val setAside = after.keys - before.keys
        assertEquals(4, setAside.size)
        val renamedDb = setAside.single { after[it] == "old wallet database" }
        assertTrue("the set-aside name must say what it is: $renamedDb", renamedDb.startsWith("$dataDb.set-aside-"))
        sidecars.forEach { suffix ->
            assertEquals(
                "$suffix keeps its SQLite pairing with the set-aside database",
                "old wallet $suffix",
                after[renamedDb + suffix],
            )
        }
        (before.keys - dataDb - sidecars.map { dataDb + it }.toSet()).forEach { untouched ->
            assertEquals("$untouched must not move", before[untouched], after[untouched])
        }
    }

    @Test
    fun `an earlier set-aside is never overwritten`() {
        val dir = sdkDir()
        assertTrue(WalletDataDatabaseFiles(dir, now = { clock }).setAside(ZcashNetwork.Mainnet))
        File(dir, dataDb).writeText("second database")

        assertTrue(WalletDataDatabaseFiles(dir, now = { clock }).setAside(ZcashNetwork.Mainnet))

        val contents = snapshot(dir).values
        assertTrue("the first set-aside database is kept", "old wallet database" in contents)
        assertTrue("the second set-aside database is kept", "second database" in contents)
        assertFalse(File(dir, dataDb).exists())
    }

    @Test
    fun `with no data database nothing changes`() {
        val dir = tempDir.newFolder("co.electricoin.zcash")
        File(dir, "tor").mkdirs()

        assertFalse(WalletDataDatabaseFiles(dir, now = { clock }).setAside(ZcashNetwork.Mainnet))

        assertEquals(emptyMap<String, String>(), snapshot(dir))
    }

    @Test
    fun `when a journal file cannot be moved every file goes back to its own name`() {
        val dir = sdkDir()
        val before = snapshot(dir)
        val files =
            WalletDataDatabaseFiles(
                dir,
                now = { clock },
                rename = { from, to -> if (from.name.endsWith("-shm")) false else from.renameTo(to) },
            )

        assertFalse(files.setAside(ZcashNetwork.Mainnet))

        assertEquals("a failed set-aside must leave the folder exactly as it was", before, snapshot(dir))
    }

    @Test
    fun `when the database itself cannot be moved nothing changes`() {
        val dir = sdkDir()
        val before = snapshot(dir)
        val files = WalletDataDatabaseFiles(dir, now = { clock }, rename = { _, _ -> false })

        assertFalse(files.setAside(ZcashNetwork.Mainnet))

        assertEquals(before, snapshot(dir))
    }
}
