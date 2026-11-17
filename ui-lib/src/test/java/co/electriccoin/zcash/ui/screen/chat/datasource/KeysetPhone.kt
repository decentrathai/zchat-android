package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ZC-705 test support (store-opener design, revision 2a, section 5): key sets made with the encryption library in the
 * test itself, never written into a source file, in the two shapes a file can hold them:
 *
 *  - [plain]: the library's unencrypted serialization (what its fallback writes when the Keystore check fails while a
 *    new file is made), as hex: it starts "08";
 *  - [wrapped]: the encrypted serialization under a key made in the JVM (standing in for the Keystore key), as hex:
 *    it starts "12".
 *
 * The two templates are the ones the stores use: AES256_SIV for the entry names, AES256_GCM for the values.
 */
internal object Keysets {
    const val KEY_ENTRY = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    const val VALUE_ENTRY = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    const val NAMES_TEMPLATE = "AES256_SIV"
    const val VALUES_TEMPLATE = "AES256_GCM"

    init {
        DeterministicAeadConfig.register()
        AeadConfig.register()
    }

    /** A key made in the JVM that wraps the [wrapped] key sets (the Keystore's part on a phone). */
    @Suppress("DEPRECATION") // getPrimitive(Class): the call the library itself makes in this version
    private val wrappingKey: Aead by lazy {
        KeysetHandle.generateNew(KeyTemplates.get(VALUES_TEMPLATE)).getPrimitive(Aead::class.java)
    }

    fun newHandle(template: String): KeysetHandle = KeysetHandle.generateNew(KeyTemplates.get(template))

    fun plain(template: String): String = plainOf(newHandle(template))

    fun plainOf(handle: KeysetHandle): String {
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(out))
        return hex(out.toByteArray())
    }

    @Suppress("DEPRECATION") // write(writer, aead): the encrypted serialization EncryptedSharedPreferences writes
    fun wrapped(template: String): String {
        val out = ByteArrayOutputStream()
        newHandle(template).write(BinaryKeysetWriter.withOutputStream(out), wrappingKey)
        return hex(out.toByteArray())
    }

    /** Both key entries of a file, unencrypted. */
    fun plainEntries(): Map<String, String> = mapOf(KEY_ENTRY to plain(NAMES_TEMPLATE), VALUE_ENTRY to plain(VALUES_TEMPLATE))

    /** Both key entries of a file, protected. */
    fun wrappedEntries(): Map<String, String> =
        mapOf(KEY_ENTRY to wrapped(NAMES_TEMPLATE), VALUE_ENTRY to wrapped(VALUES_TEMPLATE))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

/**
 * ZC-705 test support: a phone's shared_prefs folder for the store opener's JVM tests. Each file is a map; a file
 * "exists" once a commit wrote it (as on Android, a commit writes the file even when it holds nothing) and until it is
 * removed. With a [folder], every commit also writes `<name>.xml` there in Android's form (strings, ints, longs,
 * booleans), so code that looks at the real files (KI-25's openGroupStoreAndTellIfNew, Destroy's store hash) sees them.
 *
 * Every write and every remove is recorded per file, so a test can say "this file saw no write". [refuseRemove] and
 * [refuseCommit] make the system refuse a remove or a commit of a file.
 */
internal class KeysetPhone(
    private val folder: File? = null,
) {
    private val files = linkedMapOf<String, PhoneFile>()
    private val present = mutableSetOf<String>()
    val removed = mutableListOf<String>()
    val writes = mutableMapOf<String, Int>()
    val refuseRemove = mutableSetOf<String>()
    val refuseCommit = mutableSetOf<String>()

    /** ZC-733: the system refuses a commit of every file this answers true for (beside [refuseCommit]). */
    var refuseCommitWhen: (String) -> Boolean = { false }

    /** ZC-733: the file names as a shared_prefs listing ("<name>.xml"). */
    fun listing(): List<String> = synchronized(files) { present.map { "$it.xml" } }

    /** The plain view of [name] (the opener's `plainPrefs`). */
    fun plain(name: String): SharedPreferences = synchronized(files) { files.getOrPut(name) { PhoneFile(name) } }

    fun entries(name: String): Map<String, Any?> = synchronized(files) { files[name]?.map?.toMap().orEmpty() }

    fun exists(name: String): Boolean = synchronized(files) { name in present }

    /** The remove function of the environment (`removeStoreFile`). False, with nothing removed, when refused. */
    fun remove(name: String): Boolean {
        synchronized(files) {
            removed += name
            if (name in refuseRemove) return false
            files.remove(name)
            present.remove(name)
            folder?.let { File(it, "$name.xml").delete() }
            return true
        }
    }

    /** Puts [entries] into [name] as an earlier start left them (not counted as a write). */
    fun put(
        name: String,
        entries: Map<String, Any?>,
    ) {
        synchronized(files) {
            val file = files.getOrPut(name) { PhoneFile(name) }
            file.map.putAll(entries)
            present += name
            writeXml(name, file.map)
        }
    }

    fun copies(): List<String> = synchronized(files) { present.filter { it.contains(UNREADABLE_STORE_COPY_MARKER) } }

    fun writesTo(name: String): Int = synchronized(files) { writes[name] ?: 0 }

    /** The files that exist now, with what they hold. */
    fun snapshot(): Map<String, Map<String, Any?>> = synchronized(files) { present.associateWith { entries(it) } }

    /**
     * A stand-in for the encryption library's create: it writes each key entry [name] lacks, unencrypted while
     * [plainLeft] says so (each such open uses one), protected otherwise; then it returns a new store object over the
     * file. A file that has both entries is only read. [calls] counts the calls.
     */
    inner class Library(
        private val name: String,
        var plainLeft: Int = 0,
    ) {
        var calls = 0
            private set
        var plainWritten = 0
            private set

        fun open(): SharedPreferences {
            calls++
            val file = plain(name)
            val missing = listOf(Keysets.KEY_ENTRY, Keysets.VALUE_ENTRY).filterNot { file.contains(it) }
            if (missing.isNotEmpty()) {
                val entries =
                    if (plainLeft > 0) {
                        plainLeft--
                        plainWritten++
                        Keysets.plainEntries()
                    } else {
                        Keysets.wrappedEntries()
                    }
                // Like the library: each missing key set is made and written; one that is there is only read.
                val editor = file.edit()
                missing.forEach { editor.putString(it, entries.getValue(it)) }
                editor.commit()
            }
            return StoreHandle(calls, file)
        }
    }

    /** One store object the library handed out ([serial]: which call made it). */
    class StoreHandle(
        val serial: Int,
        prefs: SharedPreferences,
    ) : SharedPreferences by prefs

    private fun writeXml(
        name: String,
        map: Map<String, Any?>,
    ) {
        val dir = folder ?: return
        dir.mkdirs()
        val body =
            map.entries.joinToString("") { (key, value) ->
                when (value) {
                    is String -> "    <string name=\"${esc(key)}\">${esc(value)}</string>\n"
                    is Int -> "    <int name=\"${esc(key)}\" value=\"$value\" />\n"
                    is Long -> "    <long name=\"${esc(key)}\" value=\"$value\" />\n"
                    is Boolean -> "    <boolean name=\"${esc(key)}\" value=\"$value\" />\n"
                    else -> ""
                }
            }
        File(dir, "$name.xml").writeText("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n$body</map>\n")
    }

    private fun esc(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private inner class PhoneFile(
        private val name: String,
    ) : SharedPreferences {
        val map = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(files) { LinkedHashMap(map) }

        override fun getString(
            key: String,
            defValue: String?
        ): String? = synchronized(files) { map[key] as? String ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = synchronized(files) { (map[key] as? Set<String>)?.toMutableSet() ?: defValues }

        override fun getInt(
            key: String,
            defValue: Int
        ): Int = synchronized(files) { map[key] as? Int ?: defValue }

        override fun getLong(
            key: String,
            defValue: Long
        ): Long = synchronized(files) { map[key] as? Long ?: defValue }

        override fun getFloat(
            key: String,
            defValue: Float
        ): Float = synchronized(files) { map[key] as? Float ?: defValue }

        override fun getBoolean(
            key: String,
            defValue: Boolean
        ): Boolean = synchronized(files) { map[key] as? Boolean ?: defValue }

        override fun contains(key: String): Boolean = synchronized(files) { map.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = PhoneEditor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
            Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
            Unit

        private inner class PhoneEditor : SharedPreferences.Editor {
            private val puts = linkedMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clear = false

            override fun putString(
                key: String,
                value: String?
            ) = apply { puts[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ) = apply { puts[key] = values?.toSet() }

            override fun putInt(
                key: String,
                value: Int
            ) = apply { puts[key] = value }

            override fun putLong(
                key: String,
                value: Long
            ) = apply { puts[key] = value }

            override fun putFloat(
                key: String,
                value: Float
            ) = apply { puts[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean
            ) = apply { puts[key] = value }

            override fun remove(key: String) = apply { removes += key }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean =
                synchronized(files) {
                    if (name in refuseCommit || refuseCommitWhen(name)) return@synchronized false
                    // Android keeps a file object per name; a removed file starts empty again.
                    val file = files.getOrPut(name) { this@PhoneFile }
                    if (clear) file.map.clear()
                    removes.forEach { file.map.remove(it) }
                    puts.forEach { (key, value) -> if (value == null) file.map.remove(key) else file.map[key] = value }
                    writes[name] = (writes[name] ?: 0) + 1
                    present += name
                    writeXml(name, file.map)
                    true
                }

            override fun apply() {
                commit()
            }
        }
    }
}

/**
 * ZC-705 test support: a Context over a [KeysetPhone]: its shared preferences are the phone's files, a delete is the
 * phone's remove, and it names [packageName] and [dataDir] (the folder whose shared_prefs the app would look at).
 */
internal class KeysetPhoneContext(
    private val phone: KeysetPhone,
    private val packageName: String = "xyz.zsend.zchat.test",
    private val dataDir: File? = null,
) : ContextWrapper(null) {
    override fun getSharedPreferences(
        name: String,
        mode: Int
    ): SharedPreferences = phone.plain(name)

    override fun deleteSharedPreferences(name: String): Boolean = phone.remove(name)

    override fun getPackageName(): String = packageName

    override fun getApplicationContext(): Context = this

    override fun getApplicationInfo(): ApplicationInfo =
        ApplicationInfo().also { info -> info.dataDir = dataDir?.path ?: "/nonexistent-zchat-test" }

    /** ZC-733-B: the files folder (the picture index keeps its pictures there): under [dataDir], or a temporary one. */
    private val files: File by lazy {
        val base =
            dataDir ?: java.nio.file.Files
                .createTempDirectory("zchat-keyset-phone")
                .toFile()
                .apply { deleteOnExit() }
        File(base, "files").apply { mkdirs() }
    }

    override fun getFilesDir(): File = files
}
