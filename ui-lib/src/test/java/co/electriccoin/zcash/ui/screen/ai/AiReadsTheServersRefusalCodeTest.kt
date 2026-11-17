package co.electriccoin.zcash.ui.screen.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AI-KEY-S: the real server answers a refusal as {error: <sentence>, code: <code>} (apps/backend aiSignInKey.ts). The code
 * decides, not the sentence. Every key and account is synthetic.
 */
class AiReadsTheServersRefusalCodeTest {
    private val signIns = mutableListOf<Pair<Int, String>>()
    private var asked = 0

    private fun open(): Pair<String?, String?> =
        runBlocking {
            AiAccountAccess(
                prefs = AiTabAccessFixture.bareStore(),
                seed = { ByteArray(SEED_BYTES) { it.toByte() } },
                candidates = { emptyList() },
                ownerTag = { "a".repeat(SEED_BYTES) },
                scopeCounter = { 0L },
                walletSaved = { true },
                clock = { NOW },
                send = { _, path, _, _ ->
                    if (path == AiTabAccessFixture.CHALLENGE_PATH) {
                        OK to AiTabAccessFixture.CHALLENGE_ANSWER
                    } else {
                        asked++
                        signIns.removeAt(0)
                    }
                },
            ).open()
        }

    @Test
    fun `the code signup_limit under a sentence gives the new-account sentence`() {
        signIns += 429 to """{"error":"Too many accounts.","code":"signup_limit"}"""

        assertEquals(aiSignInText("ai_signup_limit"), open().second)
    }

    @Test
    fun `the code challenge_expired under a sentence gets exactly one more try`() {
        signIns += 400 to """{"error":"This challenge has expired.","code":"challenge_expired"}"""
        signIns += OK to """{"userId":"acct_SYNTHETIC-code","token":"synthetic-token-code","created":true}"""

        val answer = open()

        assertEquals(2, asked)
        assertEquals("synthetic-token-code", answer.first)
        assertNull(answer.second)
    }

    private companion object {
        const val OK = 200
        const val SEED_BYTES = 64
        const val NOW = 1_791_800_000_000L
    }
}
