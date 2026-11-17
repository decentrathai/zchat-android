package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.PreferenceHolder
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.common.provider.AddressBookKeyStorageProvider
import co.electriccoin.zcash.ui.common.provider.MetadataKeyStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.serialization.addressbook.AddressBookKey
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataKey
import co.electriccoin.zcash.ui.common.tex.TexCheck
import co.electriccoin.zcash.ui.common.tex.TexSendMonitor
import co.electriccoin.zcash.ui.common.tex.WalletActivity
import co.electriccoin.zcash.ui.screen.ai.AiResetKeep
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatOwnerRecord
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatWalletOwnership
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryLiveStores
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryStore
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryUntaggedMarks
import co.electriccoin.zcash.ui.screen.chat.datasource.SetAsideBundles
import co.electriccoin.zcash.ui.screen.chat.datasource.chatOwnerTag
import co.electriccoin.zcash.ui.screen.chat.util.FakePhone
import co.electriccoin.zcash.ui.screen.walletstartup.AppRestarter
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.util.SecretBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * ZC-090-R (register ZC-090, high, and ZC-755): a Reset ZCHAT driven whole on the JVM, on [FakePhone].
 *
 * The use case is a bare instance (its constructor is not called) whose fields are set by name, so this file compiles
 * before and after batch ZC-090-R: a field the Reset needs and does not have fails the test that drives it
 * ([required]); a field of today's Reset that the new one drops is set only while it exists ([optional]). The new
 * names of `ResetWipe` and `AiResetKeep` are reached by name too.
 *
 * Every stand-in is one step of the phone ([FakePhone.step]): it is written to the phone's events in order, and a
 * test makes it hang (never answer; the wait that gives up on it is counted in [FakePhone.waits] with its limit, the
 * test's own clock), throw an [Error] or throw an exception, by its name. The step names are the constants below.
 *
 * Wallet tags and keys are synthetic; nothing here is a real phrase or address.
 */
internal class ResetDriver(
    val phone: FakePhone = FakePhone(),
) : AutoCloseable {
    val seed = SeedPhrase(List(SeedPhrase.SEED_PHRASE_SIZE) { "reset$it" })
    val tag: String = chatOwnerTag(seed.split)

    /** The unlock prompt: "ok", "cancel", "fail" or "hang" (a prompt that is never answered). */
    @Volatile
    var unlock = "ok"

    /** What the AI store keeps at a Reset without keep (`AiPreferences.entriesKeptAtReset`): a saved key, or nothing. */
    @Volatile
    var aiKept: Map<String, Any?> = mapOf(AI_TOKEN to "synthetic-token", AI_USER_ID to "synthetic-user")

    /** The wallet saved on the phone; null = none. */
    @Volatile
    var wallet: PersistableWallet? =
        PersistableWallet(
            network = ZcashNetwork.Mainnet,
            endpoint = LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true),
            birthday = BlockHeight.new(3_000_000L),
            seedPhrase = seed,
            walletInitMode = WalletInitMode.NewWallet,
        )

    val contactsKey = AddressBookKey(secret(1))
    val notesKeys = listOf(MetadataKey(listOf(secret(2), secret(3))), MetadataKey(listOf(secret(4), secret(5))))

    /** The set-aside files of the phone, by tag (the kept file of S1b is this wallet's). */
    val bundles = ConcurrentHashMap<String, Map<String, Map<String, Any?>>>()

    /** The owner record of the chats. */
    @Volatile
    var owner: String? = null

    /** Runs when the restart is requested (step S7), before the phone's step. */
    @Volatile
    var onRestart: () -> Unit = {}

    /** Runs inside the encrypted settings clear (step S6), before it answers: the place to look at the process then. */
    @Volatile
    var duringWalletClear: () -> Unit = {}

    /** The encrypted settings the use case clears; a test may put its own in their place before [useCase]. */
    var encryptedSettings: PreferenceProvider = LoggingSettings(phone)

    /** The context the use case is given; a test may wrap the phone's own before [useCase]. */
    var context: Context = phone.context

    /** The use case, built on the first call. */
    val useCase: Any by lazy { build() }

    // ------------------------------------------------------------------ driving

    /** Runs invoke(keepFiles) on the caller's coroutine; the answer's name (or what today's Reset returns). */
    suspend fun reset(keepFiles: Boolean): String {
        val method =
            useCase.javaClass.methods.firstOrNull {
                it.name == "invoke" && it.parameterCount == 2 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            } ?: throw AssertionError("ResetZashiUseCase has no invoke(keepFiles)")
        val answer = callSuspend { continuation -> method.invoke(useCase, keepFiles, continuation) }
        return (answer as? Enum<*>)?.name ?: answer.toString()
    }

    /** The Reset record on the phone, or null. */
    fun record(): String? = File(phone.dataDir, "no_backup/zchat_reset_pending").takeIf { it.isFile }?.readText()

    /** The record's lines as name to value ("keep" to "none", …). */
    fun recordLines(): Map<String, String> =
        record()
            ?.lines()
            ?.filter { '=' in it }
            ?.associate { it.substringBefore('=') to it.substringAfter('=') }
            .orEmpty()

    /** The phone-store file with both key entries, so the record's store line can be worked out. */
    fun seedPhraseStore() {
        phone.put("data/shared_prefs/co.electriccoin.zcash.encrypted.xml", FakePhone.phraseStoreXml(KEY_KEYSET, VALUE_KEYSET))
    }

    fun expectedStoreHash(): String = FakePhone.storeHash(KEY_KEYSET, VALUE_KEYSET)

    /** The file names the record's files line must hold with keep unticked: the cached Contacts and notes keys. */
    fun expectedFiles(): List<String> = listOf(contactsKey.fileIdentifier()) + notesKeys.map { it.fileIdentifier() }

    /** Index of the first event that starts with [prefix], or -1. */
    fun at(prefix: String): Int = phone.events.indexOfFirst { it.startsWith(prefix) }

    override fun close() {
        resetState()
        // RESET-PAY: a Reset that started leaves the payment gate closed until its process ends; the next test's is new.
        runCatching { WalletActivity::class.java.getMethod("reopen").invoke(WalletActivity) }
        phone.close()
    }

    init {
        // RESET-PAY: the TEX check is a value of the whole process; a test of the TEX recovery may have left one. A Reset
        // driven here starts with no check finished, unless its test publishes one.
        runCatching {
            val field = TexSendMonitor::class.java.getDeclaredField("mutableLatestCheck").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as MutableStateFlow<TexCheck?>).value = null
        }
    }

    // ------------------------------------------------------------------ ResetWipe, by name

    /** Puts the Reset's state of the process back (`ResetWipe`'s replaceable calls and flags), so no test leaks. */
    fun resetState() {
        val clazz = runCatching { Class.forName(RESET_WIPE) }.getOrNull() ?: return
        for ((name, value) in resetWipePristine) clazz.declaredFields.firstOrNull { it.name == name }?.let { access(it).set(null, value) }
    }

    /** A field of `ResetWipe` (a flag, an outcome or a replaceable call); fails when it is missing. */
    fun resetWipeField(name: String): Any? = access(resetWipeDeclared(name)).get(null)

    /** Calls a public function of `ResetWipe` by name. */
    fun resetWipe(
        name: String,
        vararg args: Any?,
    ): Any? {
        val clazz = runCatching { Class.forName(RESET_WIPE) }.getOrNull() ?: throw AssertionError("the object ResetWipe does not exist")
        val method =
            clazz.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("ResetWipe has no public function $name with ${args.size} parameter(s)")
        try {
            return method.invoke(clazz.getField("INSTANCE").get(null), *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private val resetWipePristine = LinkedHashMap<String, Any?>()

    private fun resetWipeDeclared(name: String): Field {
        val clazz = runCatching { Class.forName(RESET_WIPE) }.getOrNull() ?: throw AssertionError("the object ResetWipe does not exist")
        return clazz.declaredFields.firstOrNull { it.name == name } ?: throw AssertionError("ResetWipe has no field $name")
    }

    private fun setResetWipe(
        name: String,
        value: Any?,
    ) {
        val field = access(resetWipeDeclared(name))
        if (name !in resetWipePristine) resetWipePristine[name] = field.get(null)
        field.set(null, value)
    }

    /** The process-wide flag of a running Reset, and the Reset's own calls to Android as steps of the phone. */
    fun installResetWipeSeams() {
        setResetWipe(RESET_RUNNING, false)
        setResetWipe(ARM_ALARM, { _: Context -> phone.step(STEP_ALARM_ARM) })
        setResetWipe(ENQUEUE_JOB, { _: Context -> phone.step(STEP_JOB_ENQUEUE) })
        setResetWipe(CANCEL_WAKE, { _: Context -> phone.step(STEP_WAKE_CANCEL) })
    }

    // ------------------------------------------------------------------ the use case

    private fun build(): Any {
        val unsafe = Class.forName("sun.misc.Unsafe")
        val theUnsafe = access(unsafe.getDeclaredField("theUnsafe")).get(null)
        val bare = unsafe.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, ResetZashiUseCase::class.java)!!

        installResetWipeSeams()

        required(bare, "biometricRepository", biometrics())
        required(bare, "persistableWalletProvider", wallets())
        required(bare, "chatWalletOwnership", ownership())
        required(bare, "aiResetKeep", aiResetKeep())
        required(bare, "addressBookKeyStorageProvider", contactsKeys())
        required(bare, "metadataKeyStorageProvider", notesKeyStore())
        required(bare, "encryptedPreferenceProvider", holder(EncryptedPreferenceProvider(phone.context), encryptedSettings))
        required(bare, "flexaRepository", flexa())
        required(bare, "context", context)
        required(
            bare,
            "appRestarter",
            AppRestarter {
                onRestart()
                phone.step(STEP_RESTART)
            },
        )
        optional(bare, "homeMessageCacheRepository", standIn<HomeMessageCacheRepository> { _, _ -> Unit })
        optional(bare, "transactionRepository", standIn<TransactionRepository> { _, _ -> Unit })
        return bare
    }

    private fun required(
        target: Any,
        name: String,
        value: Any?,
    ) {
        val field =
            target.javaClass.declaredFields.firstOrNull { it.name == name }
                ?: throw AssertionError("ResetZashiUseCase has no field $name (ZC-090-R)")
        access(field).set(target, value)
    }

    private fun optional(
        target: Any,
        name: String,
        value: Any?,
    ) {
        target.javaClass
            .declaredFields
            .firstOrNull { it.name == name }
            ?.let { access(it).set(target, value) }
    }

    private fun biometrics(): BiometricRepository =
        standIn { name, _ ->
            when (name) {
                "requestBiometrics" ->
                    when (unlock) {
                        "cancel" -> throw BiometricsCancelledException()
                        "fail" -> throw BiometricsFailureException()
                        "hang" -> COROUTINE_SUSPENDED
                        else -> Unit
                    }
                "getAllowedAuthenticators" -> 0
                else -> Unit
            }
        }

    private fun wallets(): PersistableWalletProvider =
        standIn { name, args ->
            when (name) {
                // The Flow property's getter and the suspend getter share a JVM name; only the latter takes a Continuation.
                "getPersistableWallet" -> if (args.isEmpty()) flowOf(wallet) else wallet
                "requirePersistableWallet" -> wallet ?: throw IllegalStateException("no wallet")
                else -> Unit
            }
        }

    private fun ownership(): ChatWalletOwnership {
        val record =
            object : ChatOwnerRecord {
                override fun owner(): String? = owner

                override fun setOwner(tag: String) {
                    phone.step(if (tag == NO_OWNER) STEP_OWNER_NONE else STEP_OWNER_WRITE)
                    owner = tag
                }
            }
        return ChatWalletOwnership(record, LoggingBundles(), InMemoryLiveStores(listOf(InMemoryStore("messages"))), InMemoryUntaggedMarks())
    }

    private fun aiResetKeep(): AiResetKeep {
        val unsafe = Class.forName("sun.misc.Unsafe")
        val theUnsafe = access(unsafe.getDeclaredField("theUnsafe")).get(null)
        val bare = unsafe.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, AiResetKeep::class.java) as AiResetKeep
        val read = {
            phone.step(STEP_AI_READ)
            aiKept
        }
        val kept =
            AiResetKeep::class.java.declaredFields.firstOrNull { it.name == AI_KEPT_FIELD }
                ?: throw AssertionError("AiResetKeep has no field $AI_KEPT_FIELD: its read cannot be stood in (ZC-090-R)")
        access(kept).set(bare, read)
        access(AiResetKeep::class.java.getDeclaredField("bundles")).set(bare, LoggingBundles())
        return bare
    }

    private fun contactsKeys(): AddressBookKeyStorageProvider =
        standIn { name, _ ->
            when (name) {
                "getAddressBookKey" -> {
                    phone.step(STEP_KEYS_CONTACTS)
                    contactsKey
                }
                else -> throw AssertionError("the Reset wrote to the Contacts key store: $name")
            }
        }

    private fun notesKeyStore(): MetadataKeyStorageProvider =
        standIn { name, _ ->
            when (name) {
                "get" -> {
                    // The account list (and the SDK behind it) is never asked: the notes keys come from the cache.
                    phone.step(STEP_ACCOUNTS)
                    null
                }
                else -> {
                    phone.step(STEP_KEYS_NOTES, ":$name")
                    notesKeys
                }
            }
        }

    private fun flexa(): FlexaRepository =
        standIn { name, _ ->
            if (name == "disconnect") phone.step(STEP_FLEXA)
            Unit
        }

    /** The set-aside files: each write and delete is a step of the phone. */
    inner class LoggingBundles : SetAsideBundles {
        override fun write(
            tag: String,
            content: Map<String, Map<String, Any?>>,
        ) {
            phone.step(STEP_KEPT_WRITE, ":" + content.keys.sorted().joinToString(","))
            bundles[tag] = content.mapValues { (_, entries) -> LinkedHashMap(entries) }
        }

        override fun read(tag: String): Map<String, Map<String, Any?>>? = bundles[tag]

        override fun delete(tag: String) {
            phone.step(STEP_KEPT_DELETE)
            bundles.remove(tag)
        }
    }

    /** The encrypted settings: the clear is a step of the phone (S6). */
    inner class LoggingSettings(
        private val phone: FakePhone,
    ) : PreferenceProvider {
        override suspend fun clearPreferences(): Boolean {
            duringWalletClear()
            phone.step(STEP_WALLET_CLEAR)
            return true
        }

        override suspend fun hasKey(key: co.electriccoin.zcash.preference.model.entry.PreferenceKey): Boolean = false

        override suspend fun putString(
            key: co.electriccoin.zcash.preference.model.entry.PreferenceKey,
            value: String?,
        ) = Unit

        override suspend fun putStringSet(
            key: co.electriccoin.zcash.preference.model.entry.PreferenceKey,
            value: Set<String>?,
        ) = Unit

        override suspend fun putLong(
            key: co.electriccoin.zcash.preference.model.entry.PreferenceKey,
            value: Long?,
        ) = Unit

        override suspend fun getLong(key: co.electriccoin.zcash.preference.model.entry.PreferenceKey): Long? = null

        override suspend fun getString(key: co.electriccoin.zcash.preference.model.entry.PreferenceKey): String? = null

        override suspend fun getStringSet(key: co.electriccoin.zcash.preference.model.entry.PreferenceKey): Set<String>? = null

        override fun observe(key: co.electriccoin.zcash.preference.model.entry.PreferenceKey): Flow<String?> = emptyFlow()

        override suspend fun remove(key: co.electriccoin.zcash.preference.model.entry.PreferenceKey) = Unit
    }

    companion object {
        const val RESET_WIPE = "co.electriccoin.zcash.ui.screen.chat.util.ResetWipe"
        const val AI_TOKEN = "ai_token"
        const val AI_USER_ID = "ai_user_id"
        const val NO_OWNER = "none"
        const val KEY_KEYSET = "12a9aabbcc"
        const val VALUE_KEYSET = "1288ddeeff"

        // ResetWipe's names (ZC-090-R)
        const val RESET_RUNNING = "resetRunning"
        const val ARM_ALARM = "armSyncAlarm"
        const val ENQUEUE_JOB = "enqueueWakeJob"
        const val CANCEL_WAKE = "cancelWake"

        // AiResetKeep's read of what the AI store keeps (ZC-090-R)
        const val AI_KEPT_FIELD = "keptEntries"

        // The steps of the phone that the Reset's own stand-ins write
        const val STEP_AI_READ = "ai.read"
        const val STEP_KEPT_WRITE = "keptfile.write"
        const val STEP_KEPT_DELETE = "keptfile.delete"
        const val STEP_OWNER_WRITE = "owner.write"
        const val STEP_OWNER_NONE = "owner.none"
        const val STEP_KEYS_CONTACTS = "keys.contacts"
        const val STEP_KEYS_NOTES = "keys.notes"
        const val STEP_ACCOUNTS = "accounts.asked"
        const val STEP_FLEXA = "flexa.disconnect"
        const val STEP_ALARM_ARM = "alarm.arm"
        const val STEP_JOB_ENQUEUE = "job.enqueue"
        const val STEP_WAKE_CANCEL = "wake.cancel"
        const val STEP_WALLET_CLEAR = "settings.encrypted.clear"
        const val STEP_RESTART = "restart"

        // The steps FakePhone itself writes
        const val STEP_STOP = "stopService"
        const val STEP_START = "startService"
        const val RECORD_RENAME = "rename:data/no_backup/zchat_reset_pending"

        private fun secret(seed: Int): SecretBytes =
            SecretBytes.copyFrom(
                ByteArray(32) { (it + seed).toByte() },
                InsecureSecretKeyAccess.get(),
            )

        fun access(field: Field): Field = field.apply { isAccessible = true }

        fun <T : PreferenceHolder> holder(
            holder: T,
            settings: PreferenceProvider,
        ): T {
            access(PreferenceHolder::class.java.getDeclaredField("preferenceProvider")).set(holder, settings)
            return holder
        }

        /** A stand-in for an interface: [answer] gets the method's name and its arguments (a Continuation last for a suspend one). */
        inline fun <reified T> standIn(noinline answer: (String, Array<Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "stand-in ${T::class.java.simpleName}"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> answer(method.name, args ?: emptyArray())
                }
            } as T

        /** Calls a suspend function by reflection on the caller's own coroutine. */
        suspend fun callSuspend(call: (Continuation<Any?>) -> Any?): Any? =
            suspendCoroutine { outer ->
                val bridge =
                    object : Continuation<Any?> {
                        override val context = outer.context

                        override fun resumeWith(result: Result<Any?>) {
                            result.exceptionOrNull()?.let { outer.resumeWithException(it) } ?: outer.resume(result.getOrNull())
                        }
                    }
                val result =
                    try {
                        call(bridge)
                    } catch (e: InvocationTargetException) {
                        outer.resumeWithException(e.targetException)
                        return@suspendCoroutine
                    }
                if (result != COROUTINE_SUSPENDED) outer.resume(result)
            }
    }
}
