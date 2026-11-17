package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DESTROY-ADV-N (register ZC-722; owner W-41, W-43, W-47, W-52; Destroy Advanced spec revision 2c, 3.1 and 10.1) —
 * the list of names Destroy All may keep: one file, `no_backup/zchat_kept_names`, under a phone-security key of its own
 * (`zchat_kept_names_key`), behind a small replaceable key box. The tests use the product's cipher box over an AES key
 * held in memory, so they run on the JVM.
 *
 * Reading rules (the chat stores' safety rules for one file; the class of fault in register ZC-733): a read answers the
 * names, "no list" or "cannot be read"; "cannot be read" is never an empty list; nothing deletes or rewrites the file
 * after such a read except "erase the whole list"; a change reads first and is refused when the read failed.
 *
 * Every new name is reached by name, so this file compiles before the list exists. All addresses and names are
 * synthetic.
 */
class KeptNamesStoreTest {
    private lateinit var folder: File

    @Before
    fun setUp() {
        folder = Files.createTempDirectory("kept-names").toFile()
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private val listFile get() = File(folder, KeptNamesApi.FILE_NAME)
    private val tempFile get() = File(folder, KeptNamesApi.TEMP_NAME)

    @Test
    fun `written entries read back, addresses trimmed and lower-cased, a tab or line break in a name becomes a space`() {
        val box = KeptNamesApi.MemoryBox()
        val store = KeptNamesApi.store(folder, box)
        assertTrue(
            "the first list is written",
            KeptNamesApi.add(store, listOf("  ZS1ALPHA000  " to "Ann\tLee", "zs1beta000" to "Bo\nB\r\nC"))
        )
        val read = KeptNamesApi.read(store)
        assertEquals(
            "the names come back as written, cleaned",
            listOf("zs1alpha000" to "Ann Lee", "zs1beta000" to "Bo B  C"),
            KeptNamesApi.entries(read)
        )
    }

    @Test
    fun `no file is no list, not an empty list and not cannot be read`() {
        val store = KeptNamesApi.store(folder, KeptNamesApi.MemoryBox())
        assertEquals("NoList", KeptNamesApi.kind(KeptNamesApi.read(store)))
        assertFalse("there is no file", KeptNamesApi.listFileExists(store))
    }

    @Test
    fun `damaged bytes cannot be read, and nothing but erase the whole list touches the file`() {
        val box = KeptNamesApi.MemoryBox()
        val store = KeptNamesApi.store(folder, box)
        KeptNamesApi.add(store, listOf("zs1alpha000" to "Ann"))
        val bytes = listFile.readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0x5a).toByte()
        listFile.writeBytes(bytes)

        assertCannotBeReadAndSpared(store, bytes, box)
    }

    @Test
    fun `a list under another key cannot be read and is spared`() {
        KeptNamesApi.add(KeptNamesApi.store(folder, KeptNamesApi.MemoryBox()), listOf("zs1alpha000" to "Ann"))
        val bytes = listFile.readBytes()
        val other = KeptNamesApi.MemoryBox()
        assertCannotBeReadAndSpared(KeptNamesApi.store(folder, other), bytes, other)
    }

    @Test
    fun `a key box that throws gives cannot be read, never no list and never an empty list`() {
        KeptNamesApi.add(KeptNamesApi.store(folder, KeptNamesApi.MemoryBox()), listOf("zs1alpha000" to "Ann"))
        val bytes = listFile.readBytes()
        val throwing = KeptNamesApi.MemoryBox(throwing = true)
        assertCannotBeReadAndSpared(KeptNamesApi.store(folder, throwing), bytes, throwing)
    }

    private fun assertCannotBeReadAndSpared(
        store: Any,
        bytes: ByteArray,
        box: KeptNamesApi.MemoryBox,
    ) {
        assertEquals("a list that cannot be read is said so", "CannotBeRead", KeptNamesApi.kind(KeptNamesApi.read(store)))
        assertArrayEquals("a failed read leaves the file's bytes as they were", bytes, listFile.readBytes())
        assertFalse("removing one name from a list that cannot be read is refused", KeptNamesApi.remove(store, "zs1alpha000"))
        assertArrayEquals("a refused removal leaves the file's bytes as they were", bytes, listFile.readBytes())
        assertFalse(
            "adding to a list that cannot be read is refused (revision 2c, B7: it is never replaced)",
            KeptNamesApi.add(store, listOf("zs1gamma000" to "Gil"))
        )
        assertArrayEquals("a refused add leaves the file's bytes as they were", bytes, listFile.readBytes())
        assertTrue("the file still exists", KeptNamesApi.listFileExists(store))
        assertEquals("fixture: the key was not erased yet", 0, box.erased)

        assertTrue("erase the whole list works on a list that cannot be read", KeptNamesApi.eraseAll(store))
        assertFalse("erase the whole list deletes the file", listFile.exists())
        assertFalse("erase the whole list leaves no temp file", tempFile.exists())
        assertEquals("erase the whole list deletes the key", 1, box.erased)
        assertEquals("after the erase there is no list", "NoList", KeptNamesApi.kind(KeptNamesApi.read(store)))
    }

    @Test
    fun `a kill between the temp write and the rename leaves the old file whole, and the temp name is never read`() {
        val box = KeptNamesApi.MemoryBox()
        val store = KeptNamesApi.store(folder, box)
        KeptNamesApi.add(store, listOf("zs1alpha000" to "Ann"))
        val old = listFile.readBytes()

        // What a write cut off before its rename leaves: a whole, valid list under the temp name ...
        val other = File(Files.createTempDirectory("kept-names-other").toFile(), "x")
        val otherStore = KeptNamesApi.store(other.parentFile!!, box)
        KeptNamesApi.add(otherStore, listOf("zs1alpha000" to "Ann", "zs1beta000" to "Bo"))
        File(other.parentFile, KeptNamesApi.FILE_NAME).copyTo(tempFile)
        other.parentFile!!.deleteRecursively()

        assertArrayEquals("the old file is whole", old, listFile.readBytes())
        assertEquals(
            "the temp name is never read",
            listOf("zs1alpha000" to "Ann"),
            KeptNamesApi.entries(KeptNamesApi.read(store))
        )

        // ... and with no list file at all, a temp file alone is no list.
        listFile.delete()
        assertEquals("a temp file alone is not a list", "NoList", KeptNamesApi.kind(KeptNamesApi.read(store)))
    }

    @Test
    fun `adding to an older list keeps its entries and takes the newer name`() {
        val store = KeptNamesApi.store(folder, KeptNamesApi.MemoryBox())
        KeptNamesApi.add(store, listOf("zs1alpha000" to "Old Ann", "zs1beta000" to "Bo"))
        assertTrue(KeptNamesApi.add(store, listOf("zs1alpha000" to "New Ann", "zs1gamma000" to "Gil")))
        assertEquals(
            setOf("zs1alpha000" to "New Ann", "zs1beta000" to "Bo", "zs1gamma000" to "Gil"),
            KeptNamesApi.entries(KeptNamesApi.read(store)).toSet()
        )
    }

    @Test
    fun `removing one name keeps the others`() {
        val store = KeptNamesApi.store(folder, KeptNamesApi.MemoryBox())
        KeptNamesApi.add(store, listOf("zs1alpha000" to "Ann", "zs1beta000" to "Bo"))
        assertTrue(KeptNamesApi.remove(store, "zs1alpha000"))
        assertEquals(listOf("zs1beta000" to "Bo"), KeptNamesApi.entries(KeptNamesApi.read(store)))
    }

    @Test
    fun `more than 2,000 entries are cut to 2,000`() {
        val store = KeptNamesApi.store(folder, KeptNamesApi.MemoryBox())
        KeptNamesApi.add(store, (1..2_100).map { "zs1synthetic${it.toString().padStart(5, '0')}" to "Name $it" })
        assertEquals(2_000, KeptNamesApi.entries(KeptNamesApi.read(store)).size)
    }

    @Test
    fun `the file holds a format mark, the cipher's nonce and AES-GCM over the text, and no address and no name`() {
        val box = KeptNamesApi.MemoryBox()
        val store = KeptNamesApi.store(folder, box)
        val address = "zs1alpha0000000000000000000000000000000000000000000000000000000000000000000"
        KeptNamesApi.add(store, listOf(address to "Annabelle Synthetic"))
        val bytes = listFile.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("the file's bytes hold the address", "zs1alpha" in text)
        assertFalse("the file's bytes hold the name", "Annabelle" in text)
        assertFalse("the file's bytes hold the name (UTF-16)", "A\u0000n\u0000n" in text)

        val mark = bytes.copyOfRange(0, 4)
        val nonce = bytes.copyOfRange(4, 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, box.key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(mark)
        val plain = String(cipher.doFinal(bytes.copyOfRange(16, bytes.size)), Charsets.UTF_8)
        assertEquals("one line per entry: the address, a tab, the name", "$address\tAnnabelle Synthetic", plain.trimEnd('\n'))
    }

    @Test
    fun `the key box's encrypt call takes no nonce and gives back the one its cipher made`() {
        val sealMethod =
            KeptNamesApi.boxInterface().methods.firstOrNull { it.name == KeptNamesApi.SEAL }
                ?: throw AssertionError("the key box has no ${KeptNamesApi.SEAL}")
        assertEquals(
            "the encrypt call takes the text and the associated data only, never a nonce",
            listOf(ByteArray::class.java, ByteArray::class.java),
            sealMethod.parameterTypes.toList()
        )
        val box = KeptNamesApi.MemoryBox()
        val plain = "zs1alpha000\tAnn".toByteArray()
        val aad = byteArrayOf(1, 2, 3, 4)
        val (nonce1, body1) = KeptNamesApi.seal(box.product, plain, aad)
        val (nonce2, _) = KeptNamesApi.seal(box.product, plain, aad)
        assertEquals("a 12-byte nonce", 12, nonce1.size)
        assertFalse("two writes of the same list carry different nonces", nonce1.contentEquals(nonce2))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, box.key, GCMParameterSpec(128, nonce1))
        cipher.updateAAD(aad)
        assertArrayEquals("the nonce given back is the one the cipher used", plain, cipher.doFinal(body1))

        val store = KeptNamesApi.store(folder, box)
        KeptNamesApi.add(store, listOf("zs1alpha000" to "Ann"))
        val first = listFile.readBytes().copyOfRange(4, 16)
        KeptNamesApi.add(store, listOf("zs1alpha000" to "Ann"))
        val second = listFile.readBytes().copyOfRange(4, 16)
        assertFalse("two writes of the same list carry different nonces on disk", first.contentEquals(second))
    }

    @Test
    fun `source - the list passes no nonce to an encrypting cipher and names no preferences store and no chat-store opener`() {
        val raw = File(ChatResourceText.repoRoot, KeptNamesApi.SOURCE).takeIf { it.isFile }?.readText()
        assertNotNull("there is no ${KeptNamesApi.SOURCE}", raw)
        val code = SourceScan.stripComments(raw!!)
        val encryptInits = Regex("""\.init\(\s*Cipher\.ENCRYPT_MODE[^)]*\)""").findAll(code).map { it.value }.toList()
        assertTrue("fixture: the list encrypts with a cipher", encryptInits.isNotEmpty())
        for (init in encryptInits) {
            assertFalse("an encrypting cipher is given a nonce: $init", "Spec" in init || init.count { it == ',' } > 1)
        }
        for (name in listOf("IvParameterSpec", "SecureRandom", "setRandomizedEncryptionRequired(false)")) {
            assertFalse("the list's file names $name", name in code)
        }
        for (name in listOf(
            "SharedPreferences",
            "EncryptedSharedPreferences",
            "MasterKey",
            "createEncryptedPrefs",
            "openEncryptedStore",
            "EncryptedStoreOpener",
            "KeptChatStoreCopies",
            "crypto.tink",
            "PreferenceProvider",
        )) {
            assertFalse("the list's file names $name", name in code)
        }
        assertTrue("the list's own Keystore key", "\"zchat_kept_names_key\"" in code)
        assertTrue("the list's file", "\"zchat_kept_names\"" in code)
    }
}

/**
 * The kept-names list (DESTROY-ADV-N) reached by name: `KeptNames` over a folder and a key box, `KeptName`, the read
 * answers `Names` / `NoList` / `CannotBeRead`, and the product's cipher box `CipherKeptNamesKeyBox` over a key that
 * the tests hold in memory.
 */
internal object KeptNamesApi {
    const val PACKAGE = "co.electriccoin.zcash.ui.screen.chat.util"
    const val STORE = "$PACKAGE.KeptNames"
    const val ENTRY = "$PACKAGE.KeptName"
    const val BOX = "$PACKAGE.KeptNamesKeyBox"
    const val CIPHER_BOX = "$PACKAGE.CipherKeptNamesKeyBox"
    const val SEAL = "seal"
    const val FILE_NAME = "zchat_kept_names"
    const val TEMP_NAME = "zchat_kept_names.tmp"
    const val SOURCE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/KeptNames.kt"

    fun type(name: String): Class<*> =
        runCatching { Class.forName(name) }.getOrNull()
            ?: throw AssertionError("there is no $name: Destroy All has no list of kept names (DESTROY-ADV-N)")

    fun boxInterface(): Class<*> = type(BOX)

    /** The product's cipher box over an AES-256 key held in memory; [erased] counts the key's erase calls. */
    class MemoryBox(
        throwing: Boolean = false,
    ) {
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var erased = 0

        val product: Any =
            run {
                val existing: () -> SecretKey? = { if (throwing) throw IllegalStateException("synthetic Keystore fault") else key }
                val make: () -> SecretKey = { if (throwing) throw IllegalStateException("synthetic Keystore fault") else key }
                val remove: () -> Unit = { erased++ }
                type(CIPHER_BOX)
                    .getConstructor(Function0::class.java, Function0::class.java, Function0::class.java)
                    .newInstance(existing, make, remove)
            }
    }

    fun store(
        folder: File,
        box: MemoryBox,
    ): Any = type(STORE).getConstructor(File::class.java, boxInterface()).newInstance(folder, box.product)

    fun entry(
        address: String,
        name: String,
    ): Any = type(ENTRY).getConstructor(String::class.java, String::class.java).newInstance(address, name)

    private fun call(
        target: Any,
        name: String,
        vararg args: Any,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("${target.javaClass.simpleName} has no $name")
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    fun add(
        store: Any,
        entries: List<Pair<String, String>>,
    ): Boolean = call(store, "add", entries.map { entry(it.first, it.second) }) as Boolean

    fun remove(
        store: Any,
        address: String,
    ): Boolean = call(store, "remove", address) as Boolean

    fun eraseAll(store: Any): Boolean = call(store, "eraseAll") as Boolean

    fun listFileExists(store: Any): Boolean = call(store, "listFileExists") as Boolean

    fun read(store: Any): Any = call(store, "read")!!

    /** "Names", "NoList" or "CannotBeRead". */
    fun kind(read: Any): String = read.javaClass.simpleName

    fun entries(read: Any): List<Pair<String, String>> {
        assertEquals("the list was read", "Names", kind(read))
        @Suppress("UNCHECKED_CAST")
        val list = call(read, "getEntries") as List<Any>
        return list.map { (call(it, "getAddress") as String) to (call(it, "getName") as String) }
    }

    fun seal(
        box: Any,
        plain: ByteArray,
        aad: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val sealed = call(box, SEAL, plain, aad)!!
        return (call(sealed, "getNonce") as ByteArray) to (call(sealed, "getBody") as ByteArray)
    }
}
