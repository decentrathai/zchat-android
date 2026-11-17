package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.SharedPreferences
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * R2-6 (c) fixtures: the REAL conversation-id store under a faked preferences surface.
 *
 * [RealConversationIds] is a [ZchatPreferencesImpl] allocated without its constructor (which opens encrypted
 * stores the JVM cannot) whose conversation-mapping file is an in-memory SharedPreferences. [wrap] routes every
 * conversation-id call (any method whose name mentions "Conversation", except the transport-mode ones) to that
 * real store and everything else to the test's own fake, so a send that creates, reads or marks a conversation
 * does exactly what the app does, and a second ViewModel over the same store sees what the first one left.
 */
internal class RealConversationIds {
    val file = MemorySharedPreferences()

    val impl: ZchatPreferences =
        run {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
            val prefs = allocate.invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
            ZchatPreferencesImpl::class.java
                .getDeclaredField("convMappingPrefs")
                .apply { isAccessible = true }
                .set(prefs, file)
            prefs
        }

    fun wrap(base: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "toString" -> "RealConversationIds($base)"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> call(if (isConversationCall(method)) impl else base, method, args)
            }
        } as ZchatPreferences

    private fun isConversationCall(method: Method) =
        method.name.contains("Conversation") && !method.name.contains("ConversationMode")

    private fun call(
        target: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
}

/** The first memo of a ZIP 321 URI the use case built, decoded. */
internal fun firstMemo(zip321Uri: String): String {
    val encoded =
        Regex("""[?&]memo=([A-Za-z0-9_-]+)""").find(zip321Uri)?.groupValues?.get(1)
            ?: throw AssertionError("no memo in the proposal")
    val decoder = java.util.Base64.getUrlDecoder()
    return String(decoder.decode(encoded), Charsets.UTF_8)
}

/** How many memo outputs a ZIP 321 URI carries. */
internal fun memoCount(zip321Uri: String): Int = Regex("""[?&]memo(?:\.\d+)?=""").findAll(zip321Uri).count()

/** True when [memo] is a v4 INIT (single or first chunk) that names [sender]. */
internal fun isInitFrom(
    memo: String,
    sender: String,
): Boolean =
    (memo.startsWith("ZMSG|v4|") || memo.startsWith("ZMSG|v4c|1/")) && memo.contains("|INIT|$sender|")

/** A Zashi wallet with [orchard] sealed Orchard and [ironwood] spendable Ironwood. Synthetic addresses. */
internal fun walletWith(
    orchard: Long,
    ironwood: Long,
): ZashiAccount =
    runBlocking {
        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))
        ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(orchard), balance(ironwood)),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )
    }

/** One selected Zashi account whose balances a test can move (Ironwood arriving, for example). */
internal class ChangingAccountDataSource(
    initial: ZashiAccount,
) : AccountDataSource {
    val account = MutableStateFlow(initial)

    override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(initial))
    override val selectedAccount: Flow<WalletAccount?> = account
    override val zashiAccount: Flow<ZashiAccount?> = account

    override suspend fun getAllAccounts(): List<WalletAccount> = listOf(account.value)

    override suspend fun getSelectedAccount(): WalletAccount = account.value

    override suspend fun getZashiAccount(): ZashiAccount = account.value

    override suspend fun selectAccount(account: Account) = Unit

    override suspend fun selectAccount(account: WalletAccount) = Unit

    override suspend fun importKeystoneAccount(
        ufvk: String,
        seedFingerprint: String,
        index: Long,
    ): Account = error("not used in this test")

    override suspend fun requestNextShieldedAddress() = Unit
}

/** In-memory SharedPreferences with commit semantics (writes land on commit/apply). */
internal class MemorySharedPreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    fun snapshot(): Map<String, Any?> = synchronized(values) { HashMap(values) }

    override fun getAll(): MutableMap<String, *> = synchronized(values) { HashMap(values) }

    override fun getString(
        key: String,
        defValue: String?
    ): String? = synchronized(values) { values[key] as String? ?: defValue }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?
    ): MutableSet<String>? = synchronized(values) { values[key] as MutableSet<String>? ?: defValues }

    override fun getInt(
        key: String,
        defValue: Int
    ): Int = synchronized(values) { values[key] as Int? ?: defValue }

    override fun getLong(
        key: String,
        defValue: Long
    ): Long = synchronized(values) { values[key] as Long? ?: defValue }

    override fun getFloat(
        key: String,
        defValue: Float
    ): Float = synchronized(values) { values[key] as Float? ?: defValue }

    override fun getBoolean(
        key: String,
        defValue: Boolean
    ): Boolean = synchronized(values) { values[key] as Boolean? ?: defValue }

    override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clear = false

        override fun putString(
            key: String,
            value: String?
        ) = apply { changes[key] = value }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ) = apply { changes[key] = values?.let { HashSet(it) } }

        override fun putInt(
            key: String,
            value: Int
        ) = apply { changes[key] = value }

        override fun putLong(
            key: String,
            value: Long
        ) = apply { changes[key] = value }

        override fun putFloat(
            key: String,
            value: Float
        ) = apply { changes[key] = value }

        override fun putBoolean(
            key: String,
            value: Boolean
        ) = apply { changes[key] = value }

        override fun remove(key: String) = apply { removals.add(key) }

        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            synchronized(values) {
                if (clear) values.clear()
                removals.forEach { values.remove(it) }
                changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
