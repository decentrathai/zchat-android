package co.electriccoin.zcash.ui.qa

import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import co.electriccoin.zcash.ui.debug.FaultSeamsConfig
import co.electriccoin.zcash.ui.screen.chat.datasource.EncryptedStoreUnavailableException
import co.electriccoin.zcash.ui.screen.chat.datasource.openEncryptedStore
import co.electriccoin.zcash.ui.screen.walletstartup.ChatCopyPhoneFiles
import co.electriccoin.zcash.ui.screen.walletstartup.RecordingPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F0 (fix-all 2.13.0): what an armed seam does, on a DEBUG build (unit tests run the debug variant). The release
 * half — nothing can arm a seam and every read answers "not armed" — is DebugFaultSeamsReleaseAbsenceTest.
 */
class DebugFaultSeamsBehaviourTest {
    private val realKiller = FaultSeams.killer

    @BeforeTest
    fun clean() = FaultSeams.disarmAll()

    @AfterTest
    fun restore() {
        FaultSeams.disarmAll()
        FaultSeams.killer = realKiller
    }

    @Test
    fun a_seam_is_off_until_armed_and_off_again_once_disarmed() {
        FaultSeam.entries.forEach { assertFalse(FaultSeams.isArmed(it), "$it armed by default") }
        FaultSeams.arm(FaultSeam.SEEN_KEX_CAP, 3)
        assertTrue(FaultSeams.isArmed(FaultSeam.SEEN_KEX_CAP))
        assertEquals(3L, FaultSeams.valueOf(FaultSeam.SEEN_KEX_CAP))
        assertNull(FaultSeams.valueOf(FaultSeam.SEEN_NOSTR_CAP))
        FaultSeams.disarm(FaultSeam.SEEN_KEX_CAP)
        assertFalse(FaultSeams.isArmed(FaultSeam.SEEN_KEX_CAP))
        FaultSeams.arm(FaultSeam.BLOCK_RELAYS, 1)
        FaultSeams.arm(FaultSeam.KEYSTORE_OUT, 1)
        FaultSeams.disarmAll()
        assertEquals(emptyMap(), FaultSeams.snapshot())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun the_send_delay_waits_the_armed_time_and_not_at_all_when_off() =
        runTest {
            val unarmed = async { FaultSeams.delayIfArmed(FaultSeam.GROUP_SEND_DELAY_MS) }
            runCurrent()
            assertTrue(unarmed.isCompleted, "an unarmed seam must not wait")

            FaultSeams.arm(FaultSeam.GROUP_SEND_DELAY_MS, 5_000)
            val armed = async { FaultSeams.delayIfArmed(FaultSeam.GROUP_SEND_DELAY_MS) }
            advanceTimeBy(4_999)
            assertFalse(armed.isCompleted, "the armed delay ended early")
            advanceTimeBy(2)
            assertTrue(armed.isCompleted, "the armed delay did not end")
        }

    @Test
    fun the_kill_seam_kills_only_when_armed() {
        var kills = 0
        FaultSeams.killer = { kills++ }
        FaultSeams.killHereIfArmed(FaultSeam.KILL_AFTER_CREATE_BEFORE_ON_STORED)
        assertEquals(0, kills)
        FaultSeams.arm(FaultSeam.KILL_AFTER_CREATE_BEFORE_ON_STORED, 1)
        FaultSeams.killHereIfArmed(FaultSeam.KILL_AFTER_CREATE_BEFORE_ON_STORED)
        assertEquals(1, kills)
    }

    @Test
    fun an_armed_keystore_seam_fails_every_open_and_keeps_the_store_on_disk() {
        val files = ChatCopyPhoneFiles()
        (files.file(STORE) as RecordingPrefs).entries.putAll(KEYS_ON_DISK)
        var realOpens = 0
        val open = {
            realOpens++
            RecordingPrefs()
        }
        FaultSeams.arm(FaultSeam.KEYSTORE_OUT, 1)

        val outcome =
            runCatching {
                openEncryptedStore(name = STORE, open = open, plainPrefs = files::file, sleep = {}, userVisible = false)
            }
        assertIs<EncryptedStoreUnavailableException>(outcome.exceptionOrNull())
        assertEquals(0, realOpens, "the Keystore seam let an open through")
        assertEquals(KEYS_ON_DISK, files.entries(STORE), "the store changed while the seam was armed")

        FaultSeams.disarm(FaultSeam.KEYSTORE_OUT)
        openEncryptedStore(name = STORE, open = open, plainPrefs = files::file, sleep = {}, userVisible = false)
        assertEquals(1, realOpens, "the store did not open once the seam was disarmed")
    }

    @Test
    fun the_saved_seams_survive_a_restart_and_a_bad_line_never_stops_a_start() {
        val filesDir = Files.createTempDirectory("fault-seams").toFile()
        try {
            FaultSeamsConfig.write(filesDir, mapOf(FaultSeam.KEYSTORE_OUT to 1L, FaultSeam.GROUP_SEND_DELAY_MS to 90_000L))
            assertEquals(
                mapOf(FaultSeam.KEYSTORE_OUT to 1L, FaultSeam.GROUP_SEND_DELAY_MS to 90_000L),
                FaultSeamsConfig.read(filesDir),
            )
            assertEquals(
                mapOf(FaultSeam.BLOCK_RELAYS to 1L, FaultSeam.SEEN_NOSTR_CAP to 20L),
                FaultSeamsConfig.parse(listOf("# comment", "BLOCK_RELAYS", "SEEN_NOSTR_CAP=20", "NO_SUCH_SEAM=1", "SEEN_KEX_CAP=x")),
            )
            FaultSeamsConfig.write(filesDir, emptyMap())
            assertFalse(FaultSeamsConfig.file(filesDir).exists(), "clearing every seam must remove the file")
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun a_one_shot_input_file_must_be_a_bare_name_in_the_qa_folder() {
        val filesDir = File("/data/data/app/files")
        assertEquals(File(filesDir, "qa/keystone_ufvk.txt"), FaultSeamsConfig.inputFile(filesDir, "keystone_ufvk.txt"))
        listOf(null, "", "../shared_prefs/x.xml", "a/b", "..", ".hidden", "x".repeat(65)).forEach {
            assertNull(FaultSeamsConfig.inputFile(filesDir, it), "accepted the input file name '$it'")
        }
    }

    private companion object {
        const val STORE = "zchat_e2e_keys_encrypted"
        val KEYS_ON_DISK: Map<String, Any?> =
            mapOf(
                "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "keyset-ciphertext-1",
                "AQx1-encrypted-key" to "AUz9-encrypted-value",
            )
    }
}
