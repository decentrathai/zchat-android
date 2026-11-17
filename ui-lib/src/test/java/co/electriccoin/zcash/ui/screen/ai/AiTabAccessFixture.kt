package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences

/**
 * AI-KEY-S (W-53): how the tests of the AI tab and the client reach the sign-in that replaced the old sign-up: the real
 * AiAccountAccess over the real client, a synthetic seed and a fence that always holds. A mock server answers
 * [CHALLENGE_PATH] with [CHALLENGE_ANSWER] and [SIGN_IN_PATH] with the account, as it answered the old sign-up.
 */
internal object AiTabAccessFixture {
    const val CHALLENGE_PATH = "/api/v1/ai/auth/key/challenge"
    const val SIGN_IN_PATH = "/api/v1/ai/auth/key/signin"
    private const val SEED_BYTES = 64
    private const val NONCE_CHARS = 43
    private const val EXPIRES_AT = 1_791_800_000L

    val CHALLENGE_ANSWER = """{"challengeId":"synthetic-challenge","nonce":"${"A".repeat(NONCE_CHARS)}","expiresAt":$EXPIRES_AT}"""

    fun access(
        store: AiPreferences,
        client: AiApiClient,
    ): AiAccountAccess =
        AiAccountAccess(
            prefs = store,
            seed = { ByteArray(SEED_BYTES) { it.toByte() } },
            candidates = { emptyList() },
            ownerTag = { "a".repeat(SEED_BYTES) },
            scopeCounter = { 0L },
            walletSaved = { true },
            clock = System::currentTimeMillis,
            send = client::request,
        )

    /** An AI store without the Android Keystore: the real class over an in-memory file, with nothing saved. */
    fun bareStore(): AiPreferences {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val store =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(theUnsafe, AiPreferences::class.java) as AiPreferences
        AiPreferences::class.java
            .getDeclaredField("prefs\$delegate")
            .apply { isAccessible = true }
            .set(store, lazyOf(MemorySharedPreferences()))
        return store
    }
}
