package co.electriccoin.zcash.ui.screen.ai

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AI key round 3 (W-97; train-AIKEY/ROUND3.md items 2, 3 and 5; review-final1): what AiAccountAccess and the store must answer.
 *
 * Item 2 (GPT 2): a Reset keeps the paid fact with the pair. "A Reset and the same phrase" is the entries of
 * entriesKeptAtReset written as they are into an empty store: what the claim of the same phrase does (AiKeyKeptAtResetTest).
 * The form of the kept fact is not pinned, only what it does.
 *
 * Item 3 (GPT 3, Grok 1): a linked account whose balance names ANOTHER key is not ours: parked if paid or unknown, never
 * linkGone and verdict own. A sign-in owed with no verdict: the key is handed to no call. Only the linked account is
 * pinned: AiOtherAccountsTest pins that a balance changes nothing on an own account or one with no verdict yet.
 *
 * Item 5 (Grok 3): only noticeShown clears an owed notice; open only reports it.
 *
 * WHY IT FAILS TODAY: entriesKeptAtReset keeps the pair alone; balanceSaid sends every key that is not ours to linkGone;
 * open, onForeground, onBalance and keyForUse hand out the key of a sign-in owed; open clears through sayOwed.
 *
 * GUARDS, which pass today and pin what the fix must not change: every case named "guard". The server is a script. Every
 * key, account and fingerprint is synthetic.
 */
class AiKeyRound3AccessTest {
    private var ai = AiTabAccessFixture.bareStore()
    private val server = Script()
    private var phone: AiKeyScreensFixture? = null

    @After
    fun tearDown() {
        phone?.close()
    }

    /** entriesKeptAtReset asks the real store holder whether the file is there: the store a Reset reads is a real one. */
    private fun onAPhone() {
        ai = AiKeyScreensFixture().also { phone = it }.ai
    }

    private fun access(store: AiPreferences = ai) =
        AiAccountAccess(
            prefs = store,
            seed = { ByteArray(SEED_BYTES) { it.toByte() } },
            candidates = { emptyList() },
            ownerTag = { "a".repeat(HEX_CHARS) },
            scopeCounter = { 7L },
            walletSaved = { true },
            clock = { START_MILLIS },
            send = server::send,
        )

    // --------------------------------------------------------------------------------- item 2: the paid fact at a Reset

    @Test
    fun `2 - an issued top-up address, a Reset, the same phrase, the server says not paid - key_in_use parks the key`() =
        case {
            onAPhone()
            ai.saveCredentials(TOKEN_A, ID_A)
            access().onTopupAddress()

            val restored = afterAResetAndTheSamePhrase()
            server.always[KEY_STATE] = OK to state(null, "false")
            server.always[REGISTER] = CONFLICT to refusal("key_in_use")
            server.own = NEW_TOKEN to NEW_ID
            access(restored).open()

            assertEquals(NEW_TOKEN, restored.getToken())
            assertEquals("the key to the issued address is gone", TOKEN_A, restored.parkedToken(ID_A))
        }

    @Test
    fun `2 guard - the kept fact brings no verdict back`() =
        case {
            onAPhone()
            ai.saveCredentials(TOKEN_A, ID_A)
            link(ID_A, "own", "yes")

            val restored = afterAResetAndTheSamePhrase()

            assertEquals(TOKEN_A, restored.getToken())
            assertFalse("a verdict came through the Reset", "verdict" in restored.getLinkStateJson().orEmpty())
        }

    @Test
    fun `2 guard - a paid fact of another account is not taken for this key's`() =
        case {
            onAPhone()
            ai.saveCredentials(TOKEN_A, ID_A)
            link(ID_OTHER, null, "yes")

            val restored = afterAResetAndTheSamePhrase()
            server.always[KEY_STATE] = OK to state(null, "false")
            server.always[REGISTER] = CONFLICT to refusal("key_in_use")
            server.own = NEW_TOKEN to NEW_ID
            access(restored).open()

            assertEquals(emptyList<String>(), restored.parkedUserIds())
        }

    // ------------------------------------------------------------------------------- item 3: another wallet's key on a balance

    @Test
    fun `3 - a linked account, paid, whose balance names another key - parked, and never own`() =
        case {
            linkedKey(paid = "yes")

            val answer = access().onBalance(FOREIGN_KEY_ID, true)

            assertEquals("the key is not parked", TOKEN_A, ai.parkedToken(ID_A))
            assertNotEquals("the other wallet's key stays in use", TOKEN_A, ai.getToken())
            assertNotEquals("the other wallet's key was handed out", TOKEN_A, answer.first)
            assertNotEquals("the account is called this wallet's own", OWN, verdictOf(TOKEN_A))
        }

    @Test
    fun `3 - paid not known - parked too`() =
        case {
            linkedKey(paid = "unknown")

            access().onBalance(FOREIGN_KEY_ID, null)

            assertEquals(TOKEN_A, ai.parkedToken(ID_A))
            assertNotEquals(TOKEN_A, ai.getToken())
        }

    @Test
    fun `3 - known to hold no paid credit - the key goes and is not parked`() =
        case {
            linkedKey(paid = "no")

            access().onBalance(FOREIGN_KEY_ID, false)

            assertNotEquals("the other wallet's key stays in use", TOKEN_A, ai.getToken())
            assertEquals(emptyList<String>(), ai.parkedUserIds())
        }

    @Test
    fun `3 - parked with the sign-in down - no key is handed out, also to the next charge`() =
        case {
            linkedKey(paid = "yes")
            server.down += SIGN_IN
            val access = access()

            val answer = access.onBalance(FOREIGN_KEY_ID, true)

            assertNull("the other wallet's key was handed out", answer.first)
            assertEquals(TOKEN_A, ai.parkedToken(ID_A))
            assertNotEquals(TOKEN_A, access.keyForCharge().first)
        }

    @Test
    fun `3 guard - no key on the account is still a lost link - the sign-in is asked with create 0, and unknown_key is own`() =
        case {
            linkedKey(paid = "yes")

            access().onBalance(null, true)

            assertEquals(TOKEN_A, ai.getToken())
            assertEquals(OWN, verdictOf(TOKEN_A))
            val signIn = server.requests.last { it.path == SIGN_IN }
            assertFalse(JSONObject(signIn.body.orEmpty()).getBoolean("create"))
        }

    // ------------------------------------------------------------------------------- item 3: a sign-in owed with no verdict

    @Test
    fun `3 owed - the balance answer that lost the link hands no key`() =
        case {
            linkedKey(paid = "yes")
            server.down += SIGN_IN

            assertNull(access().onBalance(null, true).first)
        }

    @Test
    fun `3 owed - keyForUse hands no key`() =
        case {
            signInOwed()

            assertNull("the balance, models and trial calls would go out on it", access().keyForUse().first)
        }

    @Test
    fun `3 owed - open hands no key while the sign-in has no answer, and says why`() =
        case {
            signInOwed()

            val opened = access().open()

            assertNull(opened.first)
            assertTrue("nothing is said", opened.second != null)
        }

    @Test
    fun `3 owed - the foreground hands no key while the sign-in has no answer`() =
        case {
            signInOwed()

            assertNull(access().onForeground().first)
        }

    @Test
    fun `3 owed guard - the charged key is refused too, and the store keeps the key`() =
        case {
            signInOwed()

            assertNull(access().keyForCharge().first)
            assertEquals(TOKEN_A, ai.getToken())
        }

    // ------------------------------------------------------------------------------------- item 5: the notice owed

    @Test
    fun `5 - open reports an owed notice and does not clear it, in the same process`() =
        case {
            droppedAtTheForeground()
            val access = access()

            assertEquals(aiSignInText(DROPPED), access.open().second)
            assertEquals("open cleared what nobody showed", aiSignInText(DROPPED), access.open().second)
        }

    @Test
    fun `5 - open reports an owed notice and does not clear it, for a new process`() =
        case {
            droppedAtTheForeground()

            access().open()

            assertEquals("open cleared what nobody showed", aiSignInText(DROPPED), access().open().second)
        }

    @Test
    fun `5 - another sentence shown does not clear it`() =
        case {
            droppedAtTheForeground()
            val access = access()

            access.open()
            access.noticeShown(aiSignInText(LINK_LOST))

            assertEquals(aiSignInText(DROPPED), access.open().second)
        }

    @Test
    fun `5 guard - noticeShown clears it, and the next open says nothing`() =
        case {
            droppedAtTheForeground()
            val access = access()

            access.noticeShown(checkNotNull(access.open().second))

            assertNull("said twice", access.open().second)
        }

    @Test
    fun `5 guard - the foreground and a charged key leave it owed`() =
        case {
            droppedAtTheForeground()
            val access = access()

            access.onForeground()
            access.keyForCharge()

            assertEquals(aiSignInText(DROPPED), access.open().second)
        }

    // ---------------------------------------------------------------------------------------------- the arrangements

    private fun link(
        id: String,
        verdict: String?,
        paid: String,
    ) {
        ai.writeIfStillThisWallet({ true }) {
            putLinkStateJson(
                JSONObject()
                    .put("accountId", id)
                    .put("paid", paid)
                    .apply { verdict?.let { put("verdict", it) } }
                    .toString(),
            )
        }
    }

    private fun linkedKey(paid: String) {
        ai.saveCredentials(TOKEN_A, ID_A)
        link(ID_A, "linked", paid)
    }

    /** Linked, then a balance showed no key on the account while the sign-in could not be answered; the sign-in stays down. */
    private suspend fun signInOwed() {
        linkedKey(paid = "yes")
        server.down += SIGN_IN
        access().onBalance(null, true)
        assertEquals("fixture: the sign-in is owed", true, JSONObject(ai.getLinkStateJson().orEmpty()).optBoolean("signInOwed"))
        assertEquals(TOKEN_A, ai.getToken())
    }

    private suspend fun droppedAtTheForeground() {
        ai.saveCredentials(TOKEN_A, ID_A)
        server.always[KEY_STATE] = OK to state(FOREIGN_KEY_ID, "false")
        access().onForeground()
    }

    private fun afterAResetAndTheSamePhrase(): AiPreferences {
        val restored = AiTabAccessFixture.bareStore()
        val editor = prefsOf(restored).edit()
        ai.entriesKeptAtReset().forEach { (key, value) -> editor.putString(key, value as String) }
        editor.commit()
        return restored
    }

    @Suppress("UNCHECKED_CAST")
    private fun prefsOf(store: AiPreferences): SharedPreferences =
        (
            AiPreferences::class.java
                .getDeclaredField("prefs\$delegate")
                .apply { isAccessible = true }
                .get(store) as Lazy<SharedPreferences>
        ).value

    /** The verdict stored for the account of [token]: null when the store's pair is another account's or none. */
    private fun verdictOf(token: String): String? {
        if (ai.getToken() != token) return null
        val state = JSONObject(ai.getLinkStateJson() ?: return null)
        return if (state.has("verdict")) state.getString("verdict") else null
    }

    private fun case(block: suspend () -> Unit): Unit = runBlocking { withTimeout(CASE_TIMEOUT_MILLIS) { block() } }

    private fun state(
        keyId: String?,
        paid: String,
    ): String = """{"signInKeyId":${keyId?.let { "\"$it\"" } ?: "null"},"paid":$paid}"""

    private fun refusal(code: String) = """{"error":"synthetic sentence","code":"$code"}"""

    private class Request(
        val path: String,
        val body: String?,
    )

    /** Answers by path. [own] is the account of this wallet's key: signin create 0 answers unknown_key without one. */
    private class Script {
        val requests = CopyOnWriteArrayList<Request>()
        val down = mutableSetOf<String>()
        val always = mutableMapOf<String, Pair<Int, String>>()
        var own: Pair<String, String>? = null

        @Suppress("UNUSED_PARAMETER")
        suspend fun send(
            method: String,
            path: String,
            accessKey: String?,
            body: String?,
        ): Pair<Int, String> {
            requests += Request(path, body)
            if (path in down) throw IOException("synthetic: no route")
            always[path]?.let { return it }
            return when (path) {
                CHALLENGE -> OK to AiTabAccessFixture.CHALLENGE_ANSWER
                SIGN_IN -> signIn(JSONObject(body.orEmpty()).getBoolean("create"))
                else -> OK to "{}"
            }
        }

        private fun signIn(create: Boolean): Pair<Int, String> {
            val found = own
            if (found == null && !create) return CONFLICT to """{"error":"synthetic sentence","code":"unknown_key"}"""
            val account = found ?: (NEW_TOKEN to NEW_ID).also { own = it }
            return OK to
                JSONObject()
                    .put("userId", account.second)
                    .put("token", account.first)
                    .put("created", found == null)
                    .toString()
        }
    }

    private companion object {
        const val CHALLENGE = "/api/v1/ai/auth/key/challenge"
        const val KEY_STATE = "/api/v1/ai/auth/key/state"
        const val REGISTER = "/api/v1/ai/auth/key/register"
        const val SIGN_IN = "/api/v1/ai/auth/key/signin"

        const val OK = 200
        const val CONFLICT = 409

        const val TOKEN_A = "synthetic-token-a"
        const val ID_A = "acct_SYNTHETIC-a"
        const val ID_OTHER = "acct_SYNTHETIC-other"
        const val NEW_TOKEN = "synthetic-token-new"
        const val NEW_ID = "acct_SYNTHETIC-new"
        const val OWN = "own"
        const val DROPPED = "ai_notice_other_wallet_dropped"
        const val LINK_LOST = "ai_notice_link_lost"

        const val START_MILLIS = 1_791_800_000_000L
        const val CASE_TIMEOUT_MILLIS = 30_000L
        const val HEX_CHARS = 64
        const val SEED_BYTES = 64

        val FOREIGN_KEY_ID = AiSignInApi.sha256Hex("synthetic-foreign-key".toByteArray())
    }
}
