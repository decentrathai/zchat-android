package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

/**
 * ZC-1178 (AI-KEY-S, orchestrator 30 Oct): after a match, a failed register remembers the matched candidate. The next
 * event tries that one alone, against the server's next salted digest; the wallet's candidates are not read again, so
 * a later digest tests no other guess. Every key, account and address is synthetic.
 */
class AiRegisterRetriesOnlyTheMatchedCandidateTest {
    private val store = AiTabAccessFixture.bareStore()
    private val registers = mutableListOf<JSONObject>()
    private var registerAnswers = mutableListOf(SERVER_ERROR to """{"error":"internal"}""", OK to """{"registered":true}""")
    private var digestOf = FP_MATCHED
    private var salts = 0
    private var candidateReads = 0

    private fun access() =
        AiAccountAccess(
            prefs = store,
            seed = { ByteArray(SEED_BYTES) { it.toByte() } },
            candidates = {
                candidateReads++
                listOf(suspend { FP_OTHER }, suspend { FP_MATCHED })
            },
            ownerTag = { "a".repeat(SEED_BYTES) },
            scopeCounter = { 0L },
            walletSaved = { true },
            clock = { NOW },
            send = { _, path, _, body -> answer(path, body) },
        )

    private fun answer(
        path: String,
        body: String?,
    ): Pair<Int, String> =
        when (path) {
            AiTabAccessFixture.CHALLENGE_PATH -> OK to AiTabAccessFixture.CHALLENGE_ANSWER
            KEY_STATE -> {
                // A fresh salt for each answer, as the server gives (spec 3.2).
                val salt = ByteArray(SALT_BYTES) { (it + ++salts).toByte() }
                val check = AiSignInApi.sha256Hex(salt + digestOf.toByteArray(Charsets.US_ASCII))
                val walletCheck =
                    JSONObject()
                        .put("salt", Base64.getUrlEncoder().withoutPadding().encodeToString(salt))
                        .put("check", check)
                OK to
                    JSONObject()
                        .put("signInKeyId", JSONObject.NULL)
                        .put("paid", false)
                        .put("walletCheck", walletCheck)
                        .toString()
            }
            REGISTER -> {
                registers += JSONObject(body.orEmpty())
                registerAnswers.removeAt(0)
            }
            else -> OK to "{}"
        }

    @Test
    fun `a failed register is tried again with the candidate it matched, and the candidates are not read again`() =
        runBlocking {
            store.saveCredentials(TOKEN, USER)
            access().open()
            assertEquals(1, registers.size)

            access().open()

            assertEquals("the matched candidate was not tried again", 2, registers.size)
            assertEquals("a second comparison read the wallet's candidates", 1, candidateReads)
            val message =
                AiSignInApi.message(
                    "register",
                    "A".repeat(NONCE_CHARS),
                    EXPIRES_AT,
                    AiSignInApi.keyId(registers.last().getString("publicKey")),
                    USER,
                    FP_MATCHED,
                )
            val verified = E2EEncryption.verify(registers.last().getString("publicKey"), message, registers.last().getString("signature"))
            assertEquals("the retry did not sign the matched candidate", true, verified)
            assertFalse("the fingerprint left the phone", registers.any { FP_MATCHED in it.toString() })
            assertEquals("linked", JSONObject(store.getLinkStateJson().orEmpty()).getString("verdict"))
            assertFalse("the candidate stayed after the link", "matched" in store.getLinkStateJson().orEmpty())
        }

    @Test
    fun `a later digest of another fingerprint sends nothing, even when the wallet holds that fingerprint`() =
        runBlocking {
            store.saveCredentials(TOKEN, USER)
            access().open()

            digestOf = FP_OTHER
            access().open()

            assertEquals("a second guess was answered", 1, registers.size)
            assertEquals(1, candidateReads)
        }

    private companion object {
        const val KEY_STATE = "/api/v1/ai/auth/key/state"
        const val REGISTER = "/api/v1/ai/auth/key/register"
        const val OK = 200
        const val SERVER_ERROR = 500
        const val SEED_BYTES = 64
        const val SALT_BYTES = 16
        const val NONCE_CHARS = 43
        const val EXPIRES_AT = 1_791_800_000L
        const val NOW = 1_791_800_000_000L
        const val TOKEN = "synthetic-saved-token"
        const val USER = "acct_SYNTHETIC-1178"
        val FP_MATCHED = AiSignInApi.sha256Hex("synthetic-address-matched".toByteArray())
        val FP_OTHER = AiSignInApi.sha256Hex("synthetic-address-other".toByteArray())
    }
}
