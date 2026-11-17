package co.electriccoin.zcash.ui.screen.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ZC-1250 — mayOverwrite answers true for every Replacing.REFUSED, whatever is known of the account's paid credit. A 401
 * that did not come from our server (a proxy, a restored backend: no code, or no JSON at all) then costs the saved pair
 * of an unlinked account: the sign-in with create opens another account and the only key to the paid credit is gone.
 *
 * Wanted: a refusal replaces the saved pair of another account only when its paid is known to be no. Paid yes or unknown
 * is parked, with what was known of it. Each case walks one way into refused(): onRefused, onTopupRefused, key/state (open,
 * the charged key, the foreground) and register.
 *
 * GUARDS, which pass at d4ebeac61 and pin what the fix must not change: every case named "guard". Paid no is still
 * overwritten, so parking everything cannot pass; a sign-in that brings the same account or none parks nothing.
 *
 * Not pinned: which sentence the user is told. Every key and account is synthetic.
 */
class AiSavedUnlinkedPaidKeyIsParkedOnARefusalTest {
    private var ai = AiTabAccessFixture.bareStore()
    private var server = Script()

    private fun access() =
        AiAccountAccess(
            prefs = ai,
            seed = { ByteArray(SEED_BYTES) { it.toByte() } },
            candidates = { emptyList() },
            ownerTag = { TAG },
            scopeCounter = { COUNTER },
            walletSaved = { true },
            clock = { START_MILLIS },
            send = server::send,
        )

    // ------------------------------------------------------------------------------------------ paid yes or unknown: parked

    @Test
    fun `the balance's 401 - parked`() = parkedOnEveryRefusal(atKeyState = false) { it.onRefused(TOKEN_A) }

    @Test
    fun `the top-up address's 401 - parked`() =
        parkedOnEveryRefusal(atKeyState = false) { it.onTopupRefused(it.scopeNow().forKey(TOKEN_A)) }

    @Test
    fun `key state's 401 on open - parked`() = parkedOnEveryRefusal(atKeyState = true) { it.open() }

    @Test
    fun `key state's 401 on the charged key - parked`() = parkedOnEveryRefusal(atKeyState = true) { it.keyForCharge() }

    @Test
    fun `key state's 401 at the foreground - parked`() = parkedOnEveryRefusal(atKeyState = true) { it.onForeground() }

    @Test
    fun `register's 401 - parked`() =
        forEveryRefusal { paid, body ->
            savedUnlinked(paid)
            server.always[KEY_STATE] = OK to keyStateFor(paid)
            server.always[REGISTER] = UNAUTHORIZED to body

            access().open()

            assertParked(paid, body)
        }

    @Test
    fun `key state's 401 with a bad_token code - parked too, only paid no overwrites`() =
        case {
            forEachOf(PARKED_PAIDS) { paid ->
                savedUnlinked(paid)
                server.always[KEY_STATE] = UNAUTHORIZED to BAD_TOKEN

                access().open()

                assertParked(paid, BAD_TOKEN)
            }
        }

    // --------------------------------------------------------------------------------------------------------------- guards

    @Test
    fun `guard - paid no, the balance's 401 - overwritten, nothing parked`() =
        overwrittenOnEveryRefusal(atKeyState = false) { it.onRefused(TOKEN_A) }

    @Test
    fun `guard - paid no, key state's 401 - overwritten, nothing parked`() =
        overwrittenOnEveryRefusal(atKeyState = true) { it.open() }

    @Test
    fun `guard - paid no, register's 401 - overwritten, nothing parked`() =
        case {
            REFUSALS.forEach { body ->
                fresh()
                savedUnlinked(PAID_NO)
                server.always[KEY_STATE] = OK to keyStateFor(PAID_NO)
                server.always[REGISTER] = UNAUTHORIZED to body

                access().open()

                assertOverwritten(body)
            }
        }

    @Test
    fun `guard - the sign-in brings the same account - a newer key, nothing parked`() =
        forEveryRefusal { paid, body ->
            savedUnlinked(paid)
            server.own = NEW_TOKEN to ID_A
            server.always[KEY_STATE] = UNAUTHORIZED to body

            access().open()

            assertEquals("paid $paid, 401 body [$body]", NEW_TOKEN, ai.getToken())
            assertEquals("paid $paid, 401 body [$body]", ID_A, ai.getUserId())
            assertEquals("paid $paid, 401 body [$body]", emptyList<String>(), ai.parkedUserIds())
        }

    @Test
    fun `guard - the sign-in gets no answer - the saved pair stays, nothing parked`() =
        forEveryRefusal { paid, body ->
            savedUnlinked(paid)
            server.always[KEY_STATE] = UNAUTHORIZED to body
            server.down += SIGN_IN

            access().open()

            assertEquals("paid $paid, 401 body [$body]", TOKEN_A, ai.getToken())
            assertEquals("paid $paid, 401 body [$body]", ID_A, ai.getUserId())
            assertEquals("paid $paid, 401 body [$body]", emptyList<String>(), ai.parkedUserIds())
        }

    // ------------------------------------------------------------------------------------------------------- the arrangements

    private fun parkedOnEveryRefusal(
        atKeyState: Boolean,
        refusal: suspend (AiAccountAccess) -> Unit,
    ) = forEveryRefusal { paid, body ->
        savedUnlinked(paid)
        if (atKeyState) server.always[KEY_STATE] = UNAUTHORIZED to body

        refusal(access())

        assertParked(paid, body)
    }

    private fun overwrittenOnEveryRefusal(
        atKeyState: Boolean,
        refusal: suspend (AiAccountAccess) -> Unit,
    ) = case {
        REFUSALS.forEach { body ->
            fresh()
            savedUnlinked(PAID_NO)
            if (atKeyState) server.always[KEY_STATE] = UNAUTHORIZED to body

            refusal(access())

            assertOverwritten(body)
        }
    }

    private fun forEveryRefusal(block: suspend (paid: String, body: String) -> Unit) =
        case {
            forEachOf(PARKED_PAIDS.flatMap { paid -> REFUSALS.map { body -> paid to body } }) { (paid, body) -> block(paid, body) }
        }

    /** Every combination on a fresh store; all that fail are named, so one paid or body cannot hide behind another. */
    private suspend fun <T> forEachOf(
        combinations: List<T>,
        block: suspend (T) -> Unit,
    ) {
        val failed =
            combinations.mapNotNull { combination ->
                fresh()
                try {
                    block(combination)
                    null
                } catch (e: AssertionError) {
                    e.message
                }
            }
        if (failed.isNotEmpty()) throw AssertionError("${failed.size} of ${combinations.size}: ${failed.joinToString(" | ")}")
    }

    private fun fresh() {
        ai = AiTabAccessFixture.bareStore()
        server = Script()
    }

    /** The saved pair of [ID_A], with no verdict, and [paid] as a balance answer left it (unknown: nothing was ever said). */
    private suspend fun savedUnlinked(paid: String) {
        ai.saveCredentials(TOKEN_A, ID_A)
        when (paid) {
            PAID_YES -> access().onBalance(null, true)
            PAID_NO -> access().onBalance(null, false)
        }
        val link = JSONObject(ai.getLinkStateJson() ?: """{"paid":"$PAID_UNKNOWN"}""")
        assertEquals("fixture: paid", paid, link.getString("paid"))
        assertFalse("fixture: the key is linked", link.has("verdict"))
    }

    private fun keyStateFor(paid: String): String =
        when (paid) {
            PAID_YES -> """{"signInKeyId":null,"paid":true}"""
            PAID_NO -> """{"signInKeyId":null,"paid":false}"""
            else -> """{"signInKeyId":null}"""
        }

    private fun assertSignInWithCreate(what: String) {
        val signIn = server.requests.firstOrNull { it.path == SIGN_IN }
        assertTrue("fixture: $what signed in with create", signIn != null && JSONObject(signIn.body.orEmpty()).getBoolean("create"))
        assertEquals("fixture: $what", NEW_TOKEN, ai.getToken())
        assertEquals("fixture: $what", NEW_ID, ai.getUserId())
    }

    private fun assertParked(
        paid: String,
        body: String,
    ) {
        val what = "paid $paid, 401 body [$body]"
        assertSignInWithCreate(what)
        assertEquals("$what: the only key to the account was overwritten", TOKEN_A, ai.parkedToken(ID_A))
        assertEquals("$what: what was known of the parked account", paid, ai.parkedPaid(ID_A) ?: PAID_UNKNOWN)
    }

    private fun assertOverwritten(body: String) {
        val what = "paid no, 401 body [$body]"
        assertSignInWithCreate(what)
        assertEquals("$what: a key known to hold no credit was parked", emptyList<String>(), ai.parkedUserIds())
        assertNull(what, ai.parkedToken(ID_A))
    }

    private fun case(block: suspend () -> Unit): Unit =
        runBlocking {
            withTimeout(CASE_TIMEOUT_MILLIS) { block() }
        }

    private class Request(
        val path: String,
        val body: String?,
    )

    /** Answers by path. [own] is the account of this wallet's key; with none, a sign-in with create opens [NEW_ID]. */
    private class Script {
        val requests = CopyOnWriteArrayList<Request>()
        val down = mutableSetOf<String>()
        val always = mutableMapOf<String, Pair<Int, String>>()
        var own: Pair<String, String>? = null

        suspend fun send(
            @Suppress("UNUSED_PARAMETER") method: String,
            path: String,
            @Suppress("UNUSED_PARAMETER") accessKey: String?,
            body: String?,
        ): Pair<Int, String> {
            requests += Request(path, body)
            if (path in down) throw IOException("synthetic: no route")
            always[path]?.let { return it }
            return when (path) {
                CHALLENGE -> OK to AiTabAccessFixture.CHALLENGE_ANSWER
                SIGN_IN -> OK to signIn()
                else -> OK to "{}"
            }
        }

        private fun signIn(): String {
            val account = own ?: (NEW_TOKEN to NEW_ID)
            return JSONObject()
                .put("userId", account.second)
                .put("token", account.first)
                .put("created", own == null)
                .toString()
        }
    }

    private companion object {
        const val CHALLENGE = "/api/v1/ai/auth/key/challenge"
        const val KEY_STATE = "/api/v1/ai/auth/key/state"
        const val REGISTER = "/api/v1/ai/auth/key/register"
        const val SIGN_IN = "/api/v1/ai/auth/key/signin"

        const val OK = 200
        const val UNAUTHORIZED = 401

        const val PAID_YES = "yes"
        const val PAID_NO = "no"
        const val PAID_UNKNOWN = "unknown"
        val PARKED_PAIDS = listOf(PAID_YES, PAID_UNKNOWN)

        const val BAD_TOKEN = """{"error":"synthetic sentence","code":"bad_token"}"""

        /** A 401 with no code, and two with no JSON at all: none of them proves our server said it. */
        val REFUSALS = listOf("""{"error":"Invalid token"}""", "Unauthorized", "")

        const val TOKEN_A = "synthetic-token-a"
        const val ID_A = "acct_SYNTHETIC-a"
        const val NEW_TOKEN = "synthetic-token-new"
        const val NEW_ID = "acct_SYNTHETIC-new"

        const val TAG = "synthetic-owner-tag"
        const val COUNTER = 7L
        const val START_MILLIS = 1_791_800_000_000L
        const val CASE_TIMEOUT_MILLIS = 30_000L
        const val SEED_BYTES = 64
    }
}
