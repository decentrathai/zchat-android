package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import javax.crypto.SecretKey

/**
 * ZC-1044 (cross-family review of 19 Oct 2026: Grok T7aD-1 finding 2, GPT T7aD-1 finding 7) — the copy of the kept names
 * (K1) keeps its own limit of 2 s:
 *
 *  (a) the two sources are read side by side against ONE clock. Today the copy waits up to 1 s for the contact book and
 *      then up to 1 s more for the nicknames, so a contact book that answers late and nicknames that never answer leave
 *      the write no time before the limit closes it: the names that were read never reach the list, and W5 deletes the
 *      contact book. Wanted: the names the contact book gave within its 1 s are on the list.
 *  (b) the Destroy thread never waits without a limit for the copy: today it closes the copy's gate with
 *      synchronized(gate), the lock the copy holds during its rename, so a rename that does not return holds the whole
 *      wipe (W3 keys, W5 files and everything after). Wanted: the wipe goes on.
 *
 * Everything is synthetic.
 */
class KeptNamesCopyKeepsItsLimitTest {
    @get:Rule
    val limit: Timeout = Timeout.seconds(120)

    private val stall = CountDownLatch(1)
    private val nicknamesHang = CountDownLatch(1)

    /** The key of the list's key box (a Keystore held in memory); the test reads the list with it. */
    private val key: SecretKey =
        javax.crypto.KeyGenerator
            .getInstance("AES")
            .apply { init(256) }
            .generateKey()

    private fun setField(
        manager: DestroyManager,
        name: String,
        value: Any?,
    ) {
        val field =
            DestroyManager::class.java.declaredFields.firstOrNull { it.name == name }
                ?: throw AssertionError("DestroyManager has no field $name")
        field.isAccessible = true
        field.set(manager, value)
    }

    /** The rig's preferences, with nicknames that never answer. */
    private fun preferences(rig: KeepRig): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
            when (method.name) {
                "getDestroyKeep" -> setOf(DestroyWipe.KEEP_CONTACTS)
                "walletScopeGeneration" -> rig.generation.get()
                "getAllNicknames" -> {
                    nicknamesHang.await()
                    HashMap<String, String>()
                }
                "clearAll" -> {
                    rig.generation.incrementAndGet()
                    null
                }
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "KeptNamesCopyKeepsItsLimitTest preferences"
                else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
            }
        } as ZchatPreferences

    /**
     * The kept-names store over the phone's own file calls, with a key box whose every call takes [keyDelayMs] (a slow
     * Keystore) and, with [stallRename], a rename of the list that does not return until the test ends.
     */
    private fun store(
        rig: KeepRig,
        keyDelayMs: Long,
        stallRename: Boolean,
    ): Any {
        val phone = rig.phone
        val existing: () -> SecretKey? = {
            Thread.sleep(keyDelayMs)
            if (KeepRig.ALIAS in phone.keys) key else null
        }
        val make: () -> SecretKey = {
            Thread.sleep(keyDelayMs)
            phone.keys += KeepRig.ALIAS
            key
        }
        val remove: () -> Unit = { phone.keys -= KeepRig.ALIAS }
        val box =
            KeptNamesApi
                .type(KeptNamesApi.CIPHER_BOX)
                .getConstructor(Function0::class.java, Function0::class.java, Function0::class.java)
                .newInstance(existing, make, remove)

        @Suppress("UNCHECKED_CAST")
        val write = phone.wipeField("writeFile") as (File, ByteArray) -> Unit

        @Suppress("UNCHECKED_CAST")
        val phoneRename = phone.wipeField("renameFile") as (File, File) -> Boolean
        val rename: (File, File) -> Boolean = { from, to ->
            if (stallRename && to.name == KeptNamesApi.FILE_NAME) stall.await()
            phoneRename(from, to)
        }

        @Suppress("UNCHECKED_CAST")
        val sync = phone.wipeField("syncFolder") as (File) -> Unit
        return KeptNamesApi
            .type(KeptNamesApi.STORE)
            .getConstructor(
                File::class.java,
                KeptNamesApi.boxInterface(),
                Function2::class.java,
                Function2::class.java,
                Function1::class.java,
            ).newInstance(File(phone.dataDir, "no_backup"), box, write, rename, sync)
    }

    /** A store over the list's file with the test's key, for the test's own read. */
    private fun reader(rig: KeepRig): Any {
        val existing: () -> SecretKey? = { key }
        val make: () -> SecretKey = { key }
        val remove: () -> Unit = {}
        val box =
            KeptNamesApi
                .type(KeptNamesApi.CIPHER_BOX)
                .getConstructor(Function0::class.java, Function0::class.java, Function0::class.java)
                .newInstance(existing, make, remove)
        return KeptNamesApi
            .type(KeptNamesApi.STORE)
            .getConstructor(File::class.java, KeptNamesApi.boxInterface())
            .newInstance(File(rig.phone.dataDir, "no_backup"), box)
    }

    private fun manager(
        rig: KeepRig,
        keyDelayMs: Long,
        stallRename: Boolean,
    ): DestroyManager {
        val manager = rig.manager(keep = setOf(DestroyWipe.KEEP_CONTACTS), read = false)
        setField(manager, "zchatPreferences", preferences(rig))
        setField(manager, "keptNames", store(rig, keyDelayMs, stallRename))
        val reload = DestroyManager::class.java.methods.first { it.name == KeepRig.RELOAD && it.parameterCount == 0 }
        try {
            reload.invoke(manager)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
        return manager
    }

    @Test
    fun `(a) a late contact book and nicknames that never answer still leave the names read on the list`() {
        try {
            KeepRig().use { rig ->
                rig.phone.seedEverything()
                rig.contacts += listOf("zs1copylimitalpha0001" to "Ann")
                rig.contactBookDelayMs = 800
                val manager = manager(rig, keyDelayMs = 400, stallRename = false)

                rig.phone.destroy(manager)

                assertTrue(
                    "the kept list is not on the phone: the names read were lost",
                    File(rig.phone.dataDir, "no_backup/zchat_kept_names").isFile
                )
                assertEquals(
                    "the list does not hold the name the contact book gave in time",
                    setOf("zs1copylimitalpha0001" to "Ann"),
                    KeptNamesApi.entries(KeptNamesApi.read(reader(rig))).toSet(),
                )
            }
        } finally {
            nicknamesHang.countDown()
        }
    }

    @Test
    fun `(b) a rename of the list that does not return does not hold the wipe`() {
        try {
            KeepRig().use { rig ->
                rig.phone.seedEverything()
                rig.contacts += listOf("zs1copylimitbravo0001" to "Bo")
                nicknamesHang.countDown()
                val manager = manager(rig, keyDelayMs = 0, stallRename = true)

                val wipe = Thread { runCatching { rig.phone.destroy(manager) } }.apply { start() }
                wipe.join(15_000)

                assertFalse("the wipe waits for a rename that does not return", wipe.isAlive)
                assertFalse(
                    "the contact book is still on the phone: W5 never ran",
                    "data/shared_prefs/zchat_contact_book_enc.xml" in rig.phone.files(),
                )
                assertTrue("the end of the process is not requested", rig.phone.processEndRequested)
            }
        } finally {
            stall.countDown()
        }
    }
}
