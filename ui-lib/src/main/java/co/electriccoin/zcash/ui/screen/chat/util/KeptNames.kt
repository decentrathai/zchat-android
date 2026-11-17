package co.electriccoin.zcash.ui.screen.chat.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import co.electriccoin.zcash.spackle.Twig
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One kept name: the address it was saved under and the name the user gave it. */
data class KeptName(
    val address: String,
    val name: String,
)

/** What a read of the kept list answers. "Cannot be read" is never an empty list. */
sealed interface KeptNamesRead {
    data class Names(
        val entries: List<KeptName>
    ) : KeptNamesRead

    data object NoList : KeptNamesRead

    data object CannotBeRead : KeptNamesRead
}

/** What the key box's encrypt call gives back: the nonce its cipher made, and the encrypted text. */
class KeptNamesSealed(
    val nonce: ByteArray,
    val body: ByteArray,
)

/**
 * The key that protects the kept list, and nothing else. The encrypt call takes no nonce: the cipher makes one for
 * each write and the box gives it back.
 */
interface KeptNamesKeyBox {
    fun seal(
        plain: ByteArray,
        associatedData: ByteArray,
    ): KeptNamesSealed

    fun open(
        nonce: ByteArray,
        body: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    /** Deletes the key. */
    fun eraseKey()

    /** Makes the key when it is not there (at Save, in calm, so a phone that cannot make it says so then). */
    fun ensureKey()
}

/**
 * AES-256-GCM over a key that [existingKey] finds (null when there is none) and [makeKey] makes. The default is the
 * Android Keystore key [KeptNames.KEY_ALIAS] ([androidKeystore]); the tests give a key held in memory.
 */
class CipherKeptNamesKeyBox(
    private val existingKey: () -> SecretKey?,
    private val makeKey: () -> SecretKey,
    private val removeKey: () -> Unit,
) : KeptNamesKeyBox {
    override fun seal(
        plain: ByteArray,
        associatedData: ByteArray,
    ): KeptNamesSealed {
        val key = existingKey() ?: makeKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No nonce is passed: a Keystore key refuses a caller's nonce, so the cipher makes one and it is read back.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        val body = cipher.doFinal(plain)
        val nonce = cipher.iv ?: throw IllegalStateException("the cipher made no nonce")
        check(nonce.size == NONCE_SIZE) { "the cipher made a nonce of ${nonce.size} bytes" }
        return KeptNamesSealed(nonce, body)
    }

    override fun open(
        nonce: ByteArray,
        body: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val key = existingKey() ?: throw IllegalStateException("the kept list's key is not there")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(body)
    }

    override fun eraseKey() = removeKey()

    override fun ensureKey() {
        if (existingKey() == null) makeKey()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        const val NONCE_SIZE = 12
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_BITS = 256

        /** The list's own key in the Android Keystore: AES-256-GCM, no user authentication, never leaves the Keystore. */
        fun androidKeystore(): KeptNamesKeyBox {
            fun keyStore(): KeyStore {
                val store = KeyStore.getInstance(KEYSTORE)
                store.load(null)
                return store
            }
            return CipherKeptNamesKeyBox(
                existingKey = { keyStore().getKey(KeptNames.KEY_ALIAS, null) as SecretKey? },
                makeKey = {
                    KeyGenerator
                        .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                        .apply {
                            init(
                                KeyGenParameterSpec
                                    .Builder(
                                        KeptNames.KEY_ALIAS,
                                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                    .setKeySize(KEY_BITS)
                                    .build()
                            )
                        }.generateKey()
                },
                removeKey = { keyStore().deleteEntry(KeptNames.KEY_ALIAS) },
            )
        }
    }
}

/**
 * DESTROY-ADV-N (register ZC-722; owner W-41, W-43, W-47, W-52; Destroy Advanced spec revision 2c, 3.1): the list of
 * names and addresses a Destroy All with the "keep names" choice keeps for the next wallet on this phone.
 *
 * One file, `no_backup/zchat_kept_names` (never copied out of the phone), under a key of its own ([KEY_ALIAS], behind
 * [keyBox]). It is not a preferences file: no `.bak` twin, no copy in memory. Before encryption: text, one line per
 * entry, the address, a tab, the name; at most [MAX_ENTRIES]. On disk: a four-byte format mark, the 12-byte nonce the
 * cipher made, then AES-256-GCM over the text with the format mark as associated data. Written to [TEMP_NAME], synced,
 * renamed, the folder synced; the temp name is never read.
 *
 * Reading rules (the chat stores' safety rules restated for one file; the class of fault in ZC-733): a read answers
 * the names, "no list" or "cannot be read"; "cannot be read" is never an empty list; nothing deletes or rewrites the
 * file after such a read except [eraseAll]; a change reads first and is refused when the read failed (revision 2c, B7:
 * an add never replaces a list it cannot read).
 *
 * A kept name is a NAME, not a trust decision (decision B): the code that decides whether a first key exchange waits
 * for Accept, whether an invitation needs Join or whether anything is paid reads the contact book and never this
 * list. Only the screen "Names you kept", Advanced Settings and the Destroy code may name this class.
 */
class KeptNames(
    private val folder: File,
    private val keyBox: KeptNamesKeyBox,
    private val writeFile: (File, ByteArray) -> Unit,
    private val renameFile: (File, File) -> Boolean,
    private val syncFolderCall: (File) -> Unit,
) {
    /** The list in [folder] under [keyBox], with the phone's own file steps. */
    constructor(folder: File, keyBox: KeptNamesKeyBox) : this(folder, keyBox, ::writeWhole, ::renameAtomically, ::syncFolderOf)

    private val listFile get() = File(folder, FILE_NAME)
    private val tempFile get() = File(folder, TEMP_NAME)

    /** Whether the list's file is there. Decrypts nothing (the Advanced Settings row). */
    fun listFileExists(): Boolean {
        val file = listFile
        return file.isFile
    }

    @Synchronized
    fun read(): KeptNamesRead {
        val file = listFile
        if (!file.exists()) return KeptNamesRead.NoList
        return try {
            KeptNamesRead.Names(decode(file.readBytes()))
        } catch (e: Exception) {
            Twig.error { "KEPT - the list of kept names cannot be read: ${e.javaClass.simpleName}" }
            KeptNamesRead.CannotBeRead
        }
    }

    /**
     * Adds [entries] to the list (a newer name replaces an older one of the same address), at most [MAX_ENTRIES].
     * Refused (false, nothing written) when an existing list cannot be read or the write fails.
     */
    @Synchronized
    fun add(entries: List<KeptName>): Boolean = addIf(entries, gate = null) { true }

    /**
     * [add] for a write that may be given up from outside (Destroy's copy, at most 2 s): [isOpen] is asked before the
     * key is used and, under [gate], right before the rename; once it answers false nothing is written, then or later,
     * and the temp file is deleted. The one who closes it takes [gate] too, so the rename and the end of the limit are
     * decided under one lock.
     */
    @Synchronized
    fun addIf(
        entries: List<KeptName>,
        gate: Any?,
        isOpen: () -> Boolean,
    ): Boolean {
        if (!open(gate, isOpen)) return false
        val before =
            when (val read = read()) {
                is KeptNamesRead.Names -> read.entries
                KeptNamesRead.NoList -> emptyList()
                KeptNamesRead.CannotBeRead -> return false
            }
        val merged = LinkedHashMap<String, String>()
        for (entry in before + entries.map { clean(it) }) {
            if (entry.address.isEmpty()) continue
            merged[entry.address] = entry.name
        }
        return write(merged.entries.take(MAX_ENTRIES).map { KeptName(it.key, it.value) }, gate, isOpen)
    }

    /** DESTROY-ADV-C: makes the list's key at Save; false when the phone cannot. */
    fun prepareKey(): Boolean =
        try {
            keyBox.ensureKey()
            true
        } catch (e: Exception) {
            Twig.error { "KEPT - the kept list's key could not be made: ${e.javaClass.simpleName}" }
            false
        }

    /** DESTROY-ADV-C: deletes the list's key when "Destroy everything" is saved and no list is on the phone. */
    fun eraseKeyOnly() {
        try {
            keyBox.eraseKey()
        } catch (e: Exception) {
            Twig.error { "KEPT - the kept list's key could not be deleted: ${e.javaClass.simpleName}" }
        }
    }

    /** Deletes a temp file a given-up write may have left. Never the list. */
    fun deleteTemp() {
        try {
            tempFile.delete()
        } catch (e: Exception) {
            Twig.error { "KEPT - the temp file could not be deleted: ${e.javaClass.simpleName}" }
        }
    }

    private fun open(
        gate: Any?,
        isOpen: () -> Boolean,
    ): Boolean = if (gate == null) isOpen() else synchronized(gate) { isOpen() }

    /** Takes [address] off the list. Refused (false, nothing written) when the list cannot be read. */
    @Synchronized
    fun remove(address: String): Boolean {
        val read = read() as? KeptNamesRead.Names ?: return false
        val key = cleanAddress(address)
        return write(read.entries.filterNot { it.address == key }, gate = null) { true }
    }

    /** "Erase the whole list": the file, a temp file and the key. The only way a list that cannot be read goes. */
    @Synchronized
    fun eraseAll(): Boolean {
        val filesGone =
            listOf(tempFile, listFile).all { file -> !file.exists() || file.delete() }
        val keyGone =
            try {
                keyBox.eraseKey()
                true
            } catch (e: Exception) {
                Twig.error { "KEPT - the kept list's key could not be deleted: ${e.javaClass.simpleName}" }
                false
            }
        syncFolder()
        return filesGone && keyGone
    }

    private fun write(
        entries: List<KeptName>,
        gate: Any?,
        isOpen: () -> Boolean,
    ): Boolean =
        try {
            val text = entries.joinToString(separator = "") { "${it.address}\t${it.name}\n" }
            val sealed = keyBox.seal(text.toByteArray(Charsets.UTF_8), FORMAT_MARK)
            check(sealed.nonce.size == CipherKeptNamesKeyBox.NONCE_SIZE) { "a nonce of ${sealed.nonce.size} bytes" }
            folder.mkdirs()
            writeFile(tempFile, FORMAT_MARK + sealed.nonce + sealed.body)
            val renamed =
                if (gate == null) {
                    renameFile(tempFile, listFile)
                } else {
                    synchronized(gate) { isOpen() && renameFile(tempFile, listFile) }
                }
            if (renamed) {
                syncFolder()
            } else {
                tempFile.delete()
            }
            renamed
        } catch (e: Exception) {
            Twig.error { "KEPT - the list of kept names could not be written: ${e.javaClass.simpleName}" }
            tempFile.delete()
            false
        }

    private fun decode(bytes: ByteArray): List<KeptName> {
        val header = FORMAT_MARK.size + CipherKeptNamesKeyBox.NONCE_SIZE
        require(bytes.size > header) { "too short" }
        require(bytes.copyOfRange(0, FORMAT_MARK.size).contentEquals(FORMAT_MARK)) { "unknown format" }
        val nonce = bytes.copyOfRange(FORMAT_MARK.size, header)
        val plain = keyBox.open(nonce, bytes.copyOfRange(header, bytes.size), FORMAT_MARK)
        return String(plain, Charsets.UTF_8)
            .split('\n')
            .filter { it.isNotEmpty() }
            .map { line ->
                val tab = line.indexOf('\t')
                require(tab > 0) { "a line with no address" }
                KeptName(line.substring(0, tab), line.substring(tab + 1))
            }
    }

    /** Makes the rename or delete inside the folder durable; a phone that cannot is not a failed write. */
    private fun syncFolder() {
        try {
            syncFolderCall(folder)
        } catch (e: Throwable) {
            Twig.debug { "KEPT - the folder could not be synced: ${e.javaClass.simpleName}" }
        }
    }

    companion object {
        const val FILE_NAME = "zchat_kept_names"
        const val TEMP_NAME = "zchat_kept_names.tmp"
        const val KEY_ALIAS = "zchat_kept_names_key"
        const val MAX_ENTRIES = 2_000

        /** A tab or a line break in a name becomes a space: the file holds one entry per line, address TAB name. */
        private val LINE_OR_TAB = Regex("[\t\n\r]")

        /** "ZKN" and format 1: the associated data of every write. */
        private val FORMAT_MARK = byteArrayOf(0x5a, 0x4b, 0x4e, 0x01)

        /** The list of this phone: `no_backup/zchat_kept_names` under the Keystore key. Nothing is opened here. */
        fun of(context: Context): KeptNames = KeptNames(context.noBackupFilesDir, CipherKeptNamesKeyBox.androidKeystore())

        private fun cleanAddress(address: String) = address.trim().lowercase()

        /** Writes the whole file and waits until the phone's storage has it. */
        private fun writeWhole(
            file: File,
            bytes: ByteArray,
        ) {
            FileOutputStream(file).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
        }

        /** A rename inside one folder: whole or not at all. An existing target is replaced. */
        private fun renameAtomically(
            from: File,
            to: File,
        ): Boolean {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return true
        }

        /** Makes a rename, a new file or a delete inside [folder] durable. */
        private fun syncFolderOf(folder: File) {
            val descriptor = Os.open(folder.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }

        private fun clean(entry: KeptName) =
            KeptName(
                address = cleanAddress(entry.address),
                name = entry.name.replace(LINE_OR_TAB, " "),
            )
    }
}
