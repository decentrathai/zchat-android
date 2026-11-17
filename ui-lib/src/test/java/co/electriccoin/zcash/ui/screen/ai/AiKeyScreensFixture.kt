package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.chat.datasource.ChatStoreStart
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhone
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhoneContext
import co.electriccoin.zcash.ui.screen.chat.datasource.Keysets
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.security.KeyStoreException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * AI-KEY-R and AI-KEY-T (W-53): one phone for the tests of the Reset rule and of the screens that say what it did: the
 * real AiPreferences over a [KeysetPhone] and the real AiAccountAccess with a synthetic seed, a fence that holds, and
 * [answer] in the place of the server. By default the server cannot be reached. Everything here is synthetic.
 */
internal class AiKeyScreensFixture {
    val data: File = Files.createTempDirectory("zchat-aikey-rt").toFile().apply { deleteOnExit() }
    val files = KeysetPhone(File(data, "shared_prefs"))
    val context = KeysetPhoneContext(files, dataDir = data)
    var oldUnreadable = false

    /** Method, path, access key, body; the requests of the phone, in order. */
    val requests = CopyOnWriteArrayList<String>()
    var answer: suspend (String) -> Pair<Int, String> = { throw IOException("synthetic: no network") }

    val ai: AiPreferences

    init {
        StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())
        ChatStoreStart.userVisible = false
        val open = { name: String -> files.Library(name).open() }
        StoreOpenerApi.useCalls(
            StoreOpenerApi.aiHolder(context),
            StoreOpenerApi.FileCalls(
                createWithMasterKey = { name, _ -> open(name) },
                createByKeyName = { name, _ ->
                    if (oldUnreadable) throw KeyStoreException("synthetic: no answer")
                    open(name)
                },
            ),
        )
        ai = AiPreferences(context)
    }

    val access: AiAccountAccess =
        AiAccountAccess(
            prefs = ai,
            seed = { SEED.copyOf() },
            candidates = { emptyList() },
            ownerTag = { TAG },
            scopeCounter = { 0L },
            walletSaved = { true },
            clock = System::currentTimeMillis,
            send = { _, path, _, _ ->
                requests += path
                answer(path)
            },
        )

    /** The store with a saved pair, its ai_link_state and parked keys; a null leaves that part out. */
    fun store(
        verdict: String? = null,
        paid: String? = null,
        saved: Boolean = true,
        parked: Boolean = false,
    ) {
        if (saved) ai.saveCredentials(TOKEN, USER)
        ai.writeIfStillThisWallet({ true }) {
            if (saved && (verdict != null || paid != null)) putLinkStateJson(linkJson(USER, verdict, paid))
            if (parked) park(TOKEN_PARKED, USER_PARKED)
        }
    }

    /** An AI store on the phone whose key does not answer. */
    fun unreadableStore() {
        files.put(AiPreferences.PREFS_NAME, Keysets.wrappedEntries() + mapOf("ai_token" to "synthetic-unreadable"))
        oldUnreadable = true
    }

    fun close() = StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())

    companion object {
        const val TAG = "wallet-tag-a"
        const val TOKEN = "synthetic-token"
        const val USER = "synthetic-user"
        const val TOKEN_PARKED = "synthetic-token-parked"
        const val USER_PARKED = "synthetic-user-parked"
        const val KEY_STATE = "/api/v1/ai/auth/key/state"
        const val BALANCE = "/api/v1/ai/balance"
        const val CHALLENGE = "/api/v1/ai/auth/key/challenge"
        const val SIGN_IN = "/api/v1/ai/auth/key/signin"
        val SEED = ByteArray(64) { it.toByte() }
        val OWN_KEY_ID: String by lazy { AiSignInKey.keyId(AiSignInKey.derive(SEED.copyOf()).publicKey) }
        val OTHER_KEY_ID = "ab".repeat(32)

        fun linkJson(
            account: String,
            verdict: String?,
            paid: String?,
        ): String =
            buildString {
                append("""{"accountId":"$account"""")
                verdict?.let { append(""","verdict":"$it"""") }
                paid?.let { append(""","paid":"$it"""") }
                append("}")
            }

        /** Calls a suspending function by name, on the caller's coroutine; AssertionError when there is none. */
        suspend fun callSuspending(
            target: Any,
            name: String,
        ): Any? {
            val method =
                target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 1 }
                    ?: throw AssertionError("${target.javaClass.simpleName} has no suspending function $name()")
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try {
                    method.invoke(target, continuation)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        }
    }
}
