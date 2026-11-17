package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.ContextWrapper
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import java.util.Collections

/**
 * KI-25 pair 3, ZC-598 (KI-25-SPEC 3.6b D1, D3; section 4 "pair 3 adds a second fixture file") — the move of 2.12.4's
 * plain group file into the encrypted group store, driven as `HeldGroupAcceptStoredEncryptedTest` drives its own move:
 * a [ZchatPreferencesImpl] allocated without its constructor ([realPreferences]: every preferences field an in-memory
 * file), the store's private `moveLegacyGroupRecords(old, enc, storeIsNew, mainProcess)` reached by name.
 *
 * - [StoreFile]: the encrypted group store's file with a memory layer and a disk layer. A `commit()` that is refused
 *   ([StoreFile.refuseCommit]) changes the memory layer and leaves the disk layer, as the platform does; `apply()`
 *   changes the memory layer only. "The next start" is a new [StoreFile] over the same disk layer ([StoreFile.restart]).
 *   [StoreFile.throwOnPut] makes a put or a commit throw. Every `commit()` is counted.
 * - [OldFile]: 2.12.4's plain group file. The mark's `commit()` can be refused ([OldFile.refuseMark]) or throw
 *   ([OldFile.throwOnMark]); the emptying can be refused ([OldFile.refuseClear]).
 * - [log]: the calls of the old file, the store file and the key store in order (`old.commit:mark`, `e2e.get:<key>`,
 *   `store.commit`, …), which is how a case says what came before what.
 * - [record2124]: a group record in the exact form 2.12.4 wrote (`ZMSGGroupProtocol.kt@v2.12.4:681-692`, copied
 *   in the function's body), with no `creator_pub`.
 * - The look reads the store's own getters over its in-memory files: [finishedChat], [keyWithoutOurPair],
 *   [chatRequest], [ownGroup], [ownAddress] put what they name there; [keyStoreThrowsFor] makes one key read throw.
 * - [move] calls the move; its answer is the line of counts the move writes (null when it found nothing).
 *
 * All addresses, keys and ids are synthetic.
 */
internal class LegacyGroupMoveFixture {
    val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val impl: ZchatPreferencesImpl
    val files: Map<String, MemorySharedPreferences>
    val deletes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The address whose key read throws, when a case asks for it. */
    @Volatile var keyStoreThrowsFor: String? = null

    init {
        val (real, realFiles) = realPreferences()
        impl = real
        files = realFiles
        val prefs = realFiles.getValue("prefs")
        field("selfAddresses\$delegate").set(impl, lazyOf(SelfAddressRegistry(prefs)))
        val e2e = realFiles.getValue("e2ePrefs")
        field("e2ePrefs").set(impl, LoggingKeyStore(e2e))
        field("appContext").set(
            impl,
            object : ContextWrapper(null) {
                override fun deleteSharedPreferences(name: String): Boolean {
                    deletes += name
                    log += "delete:$name"
                    return deleteAnswers
                }
            },
        )
    }

    /** What the store's `deleteSharedPreferences` answers. */
    @Volatile var deleteAnswers: Boolean = true

    private fun field(name: String): java.lang.reflect.Field =
        ZchatPreferencesImpl::class.java.getDeclaredField(name).apply { isAccessible = true }

    // ------------------------------------------------------------------ what the look reads

    /** A finished chat at [address]: its contact key, our own key pair, no chat request waiting. */
    fun finishedChat(address: String): E2EKeyPair {
        val theirs = E2EEncryption.generateKeyPair()
        val ours = E2EEncryption.generateKeyPair()
        impl.setE2EOurKeys(address, ours.publicKey, ours.privateKey)
        impl.setE2EPeerPublicKey(address, theirs.publicKey)
        return theirs
    }

    /** A key at [address] with no key pair of ours (an unsolicited answer's or an invite field's). */
    fun keyWithoutOurPair(address: String): E2EKeyPair =
        E2EEncryption.generateKeyPair().also { impl.setE2EPeerPublicKey(address, it.publicKey) }

    /** A chat request at [address] that still waits for Accept. */
    fun chatRequest(address: String): E2EKeyPair =
        finishedChat(address).also { impl.setInboundKexAwaitingAccept(address, "synthetic-waiting-kex") }

    fun ownGroup(groupId: String) = impl.setGroupSelfCreated(groupId, true)

    fun ownAddress(address: String) = impl.registerSelfAddress(address)

    /** An alias from [from] to [to], as 2.12.4's accept handler leaves one. */
    fun alias(
        from: String,
        to: String,
    ) = impl.setPeerAddressAlias(from, to)

    // ------------------------------------------------------------------ the move

    /**
     * The store's group step: `moveLegacyGroupRecords(old, enc, storeIsNew, mainProcess)`. @return the line of
     * counts it wrote, or null when it found nothing to move.
     */
    fun move(
        old: SharedPreferences,
        enc: SharedPreferences,
        storeIsNew: Boolean,
        mainProcess: () -> Boolean = { true },
    ): String? {
        val method =
            ZchatPreferencesImpl::class.java.declaredMethods.firstOrNull { it.name == MOVE && it.parameterCount == 4 }
                ?: throw AssertionError(
                    "KI-25 pair 3 (ZC-598): no such operation: ZchatPreferencesImpl.$MOVE(old, enc, storeIsNew, mainProcess) " +
                        "(the group step of the plaintext move, with the mark and the one look)",
                )
        method.isAccessible = true
        return try {
            method.invoke(impl, old, enc, storeIsNew, mainProcess) as String?
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** The key store, logging every read of a contact key and throwing for [keyStoreThrowsFor]. */
    private inner class LoggingKeyStore(
        private val base: SharedPreferences,
    ) : SharedPreferences by base {
        override fun getString(
            key: String?,
            defValue: String?,
        ): String? {
            log += "e2e.get:$key"
            val throwing = keyStoreThrowsFor
            if (throwing != null && key != null && key.endsWith(throwing)) {
                throw IllegalStateException("synthetic: key entry unreadable")
            }
            return base.getString(key, defValue)
        }
    }

    // ------------------------------------------------------------------ the files

    /** 2.12.4's plain group file. */
    inner class OldFile : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        @Volatile var refuseMark = false

        @Volatile var throwOnMark = false

        @Volatile var refuseClear = false

        fun snapshot(): Map<String, Any?> = synchronized(values) { LinkedHashMap(values) }

        fun putRaw(
            key: String,
            value: Any?,
        ) = synchronized(values) { values[key] = value }

        override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = synchronized(values) { values[key] as? String ?: defValue }

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = defValues

        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = defValue

        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = defValue

        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = synchronized(values) { values[key] as? Boolean ?: defValue }

        override fun contains(key: String?): Boolean = synchronized(values) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = OldEditor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class OldEditor : RecordingEditor() {
            override fun commit(): Boolean {
                val marks = changes.keys.filter { changes[it] is Boolean }
                if (marks.isNotEmpty()) {
                    log += "old.commit:mark"
                    if (throwOnMark) throw IllegalStateException("synthetic: the mark's write throws")
                    if (refuseMark) return false
                }
                if (clear) {
                    log += "old.commit:clear"
                    if (refuseClear) return false
                }
                if (marks.isEmpty() && !clear) log += "old.commit"
                synchronized(values) {
                    if (clear) values.clear()
                    removals.forEach { values.remove(it) }
                    values.putAll(changes)
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }

    /** The encrypted group store's file: a memory layer and a disk layer (see the class KDoc). */
    inner class StoreFile(
        val disk: MutableMap<String, String> = LinkedHashMap(),
    ) : SharedPreferences {
        private val memory = LinkedHashMap<String, String>(disk)

        @Volatile var refuseCommit = false

        @Volatile var throwOnPut = false

        @Volatile var commits = 0

        /** The next start: a new file object over the same disk layer. */
        fun restart(): StoreFile = StoreFile(disk)

        fun memorySnapshot(): Map<String, String> = synchronized(memory) { LinkedHashMap(memory) }

        fun diskSnapshot(): Map<String, String> = synchronized(memory) { LinkedHashMap(disk) }

        override fun getAll(): MutableMap<String, *> = synchronized(memory) { LinkedHashMap(memory) }

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = synchronized(memory) { memory[key] ?: defValue }

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = defValues

        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = defValue

        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = defValue

        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = defValue

        override fun contains(key: String?): Boolean = synchronized(memory) { memory.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = StoreEditor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class StoreEditor : RecordingEditor() {
            override fun putString(
                key: String?,
                value: String?,
            ): SharedPreferences.Editor {
                if (throwOnPut) throw IllegalStateException("synthetic: the store's put throws")
                return super.putString(key, value)
            }

            private fun toMemory() =
                synchronized(memory) {
                    if (clear) memory.clear()
                    removals.forEach { memory.remove(it) }
                    changes.forEach { (k, v) -> if (v == null) memory.remove(k) else memory[k] = v as String }
                }

            override fun commit(): Boolean {
                log += "store.commit"
                commits++
                if (throwOnPut) throw IllegalStateException("synthetic: the store's commit throws")
                toMemory()
                if (refuseCommit) return false
                synchronized(memory) {
                    disk.clear()
                    disk.putAll(memory)
                }
                return true
            }

            override fun apply() {
                log += "store.apply"
                toMemory()
            }
        }
    }

    abstract class RecordingEditor : SharedPreferences.Editor {
        val changes = LinkedHashMap<String, Any?>()
        val removals = HashSet<String>()
        var clear = false

        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor = apply { changes[key!!] = value }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply { changes[key!!] = values }

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = apply { changes[key!!] = value }

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = apply { changes[key!!] = value }

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = apply { changes[key!!] = value }

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = apply { changes[key!!] = value }

        override fun remove(key: String?): SharedPreferences.Editor = apply { removals += key!! }

        override fun clear(): SharedPreferences.Editor = apply { clear = true }
    }

    companion object {
        const val MOVE = "moveLegacyGroupRecords"

        /** The mark's key and value in 2.12.4's old file (D1, "The mark, exactly"). */
        const val MARK = "admin:look_tried"

        const val T = 1_758_412_800_000L

        /** A record in the exact form 2.12.4 wrote. */
        fun record2124(
            groupId: String,
            creator: String,
            name: String = "Old group",
            groupKey: String? = "c3ludGhldGljLWdyb3VwLWtleS0yMTI0LWZpeHR1cmU=",
            active: Boolean = true,
        ): String =
            frozenSerialize2124(
                GroupInfo(
                    groupId = groupId,
                    name = name,
                    creatorAddress = creator,
                    createdAt = Instant.ofEpochMilli(T),
                    groupKey = groupKey,
                    isActive = active,
                ),
            )

        /** `ZMSGGroupProtocol.serializeGroupInfo` at the tag v2.12.4 (a1fb4136f), lines 681-692, verbatim. */
        @Suppress(
            "ktlint:standard:function-expression-body",
            "ktlint:standard:multiline-expression-wrapping",
            "ktlint:standard:chain-method-continuation",
        )
        fun frozenSerialize2124(info: GroupInfo): String {
            return JSONObject().apply {
                put("group_id", info.groupId)
                put("name", info.name)
                put("creator", info.creatorAddress)
                put("created_at", info.createdAt.toEpochMilli())
                put("admin_policy", info.adminPolicy.name)
                put("current_epoch", info.currentEpoch)
                info.groupKey?.let { put("group_key", it) }
                put("is_active", info.isActive)
            }.toString()
        }
    }
}
