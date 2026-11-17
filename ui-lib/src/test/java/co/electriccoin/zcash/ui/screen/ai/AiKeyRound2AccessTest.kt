package co.electriccoin.zcash.ui.screen.ai

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * AI-KEY-S round 2 (W-97; train-AIKEY/S_ROUND2.md): what AiAccountAccess must answer, seen from its callers. Items 1, 3,
 * 7, 8, 9, 10, 11, 12, 13, 14, 18, 19, 20, 22, 23. Item numbers are in the case names; AiKeyRound2TabTest holds items 2, 4, 5, 6, 15, 16, 17, 21.
 *
 * "A new process" is a new AiAccountAccess over the same store: what it keeps in memory is gone.
 *
 * GUARDS, which pass at 7813669eb and pin what round 2 must not change: every case named "guard". Item 11 and 12 are
 * guards by the worklist's own wording; item 14 is a wording decision, not a defect.
 *
 * Not pinned on purpose: the name of the new field in ai_link_state, and which sentence "Link again" answers (only that
 * it answers one, and not the link-lost one after a link).
 *
 * Reached by name: onBalance and lookForOldCredit, whose form items 5 and 16 may change. The two-argument onBalance is
 * what AiAccountAccessTest and AiOtherAccountsTest call too. Parked entries are written raw where a case needs a form the
 * store's own writer cannot make: that is the format 7813669eb wrote, with no paid.
 *
 * The server is a script, not a model of spec section 3: the signatures are checked by AiAccountAccessTest. Every key,
 * account and fingerprint is synthetic.
 */
class AiKeyRound2AccessTest {
    private var ai = AiTabAccessFixture.bareStore()
    private val server = Script()
    private var tag: String? = "a".repeat(HEX_CHARS)
    private var counter = 7L
    private var walletSaved = true
    private var now = START_MILLIS
    private var seedGives: () -> ByteArray? = { seed() }
    private var candidates: List<suspend () -> String> = emptyList()

    private fun access() =
        AiAccountAccess(
            prefs = ai,
            seed = { seedGives() },
            candidates = { candidates },
            ownerTag = { tag },
            scopeCounter = { counter },
            walletSaved = { walletSaved },
            clock = { now },
            send = server::send,
        )

    // ---------------------------------------------------------------------------------- item 1, ZC-1205: the owed notice

    @Test
    fun `1 - a key refused at the foreground, new account - a new process's open says so once`() =
        case {
            refusedKeySaved()

            access().onForeground()

            val first = access().open()
            assertEquals(aiSignInText(NEW_ACCOUNT), first.second)
            assertEquals(NEW_TOKEN, first.first)
            access().noticeShown(checkNotNull(first.second))
            assertNull("said twice", access().open().second)
        }

    @Test
    fun `1 - a key refused at the foreground, new account - the same process's open says so once`() =
        case {
            refusedKeySaved()
            val access = access()

            access.onForeground()

            val said = access.open().second
            assertEquals(aiSignInText(NEW_ACCOUNT), said)
            access.noticeShown(checkNotNull(said))
            assertNull("said twice", access.open().second)
        }

    @Test
    fun `1 - a key refused on the chat shortcut's charged key, new account - the next open says so once`() =
        case {
            refusedKeySaved()

            access().keyForCharge()

            val said = access().open().second
            assertEquals(aiSignInText(NEW_ACCOUNT), said)
            access().noticeShown(checkNotNull(said))
            assertNull("said twice", access().open().second)
        }

    @Test
    fun `1 - the notice owed is held in ai_link_state and leaves it when shown`() =
        case {
            refusedKeySaved()

            access().onForeground()
            val owing = ai.getLinkStateJson()
            access().let { it.noticeShown(checkNotNull(it.open().second)) }
            val shown = ai.getLinkStateJson()

            assertNotEquals("the shown notice is still in ai_link_state, or was never there", owing, shown)
            assertEquals(NEW_ID, JSONObject(shown.orEmpty()).getString("accountId"))
        }

    @Test
    fun `1 - a dropped key at the foreground - a new process's open says so once`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = OK to state(FOREIGN_KEY_ID, "false")

            access().onForeground()

            val said = access().open().second
            assertEquals(aiSignInText(DROPPED), said)
            access().noticeShown(checkNotNull(said))
            assertNull("said twice", access().open().second)
        }

    @Test
    fun `1 - a dropped key whose sign-in failed - the notice waits for the open that gets the key`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = OK to state(FOREIGN_KEY_ID, "false")
            server.down += SIGN_IN

            access().onForeground()
            server.down.clear()

            val opened = access().open()
            assertEquals(NEW_TOKEN, opened.first)
            assertEquals(aiSignInText(DROPPED), opened.second)
        }

    @Test
    fun `1 guard - a wipe during the sign-in writes no notice and no pair`() =
        case {
            refusedKeySaved()
            server.onArrival[SIGN_IN] = { counter++ }

            access().onForeground()

            assertEquals(TOKEN_A, ai.getToken())
            // Only the foreground's day, written before the sign-in: no notice crossed the fence.
            val fields = JSONObject(ai.getLinkStateJson().orEmpty()).keys().asSequence().toSet()
            assertEquals(setOf("accountId", "paid", "day"), fields)
        }

    // ---------------------------------------------------------------------------- item 3, ZC-1207: a sign-in stays owed

    @Test
    fun `3 - link gone and the sign-in down - the next open signs in with create 0 first, and the account is own`() =
        case {
            val linked = linkGoneWhileSignInFails()

            val opened = access().open()

            assertSignInCameFirst()
            assertEquals(linked, ai.getToken())
            assertEquals(linked, opened.first)
            assertEquals(aiSignInText(LINK_LOST), opened.second)
            assertEquals(OWN, verdict())
            assertEquals("parked instead of own", emptyList<String>(), ai.parkedUserIds())
        }

    @Test
    fun `3 - link gone and the sign-in down - the next charged key signs in with create 0 first`() =
        case {
            val linked = linkGoneWhileSignInFails()

            access().keyForCharge()

            assertSignInCameFirst()
            assertEquals(linked, ai.getToken())
            assertEquals(OWN, verdict())
        }

    @Test
    fun `3 - link gone and the sign-in down - the next foreground signs in with create 0 first`() =
        case {
            val linked = linkGoneWhileSignInFails()

            access().onForeground()

            assertSignInCameFirst()
            assertEquals(linked, ai.getToken())
            assertEquals(OWN, verdict())
        }

    @Test
    fun `3 - link gone and the sign-in answered 503 - it stays owed too`() =
        case {
            linkGoneWhileSignInFails(failure = { server.once[SIGN_IN] = ArrayDeque(listOf(UNAVAILABLE to OUTAGE)) })

            access().open()

            assertSignInCameFirst()
            assertEquals(OWN, verdict())
        }

    @Test
    fun `3 - the owed sign-in brings another account - the paid pair is parked, the debt ends, no further call`() =
        case {
            val linked = linkGoneWhileSignInFails(paid = true)
            server.own = OTHER_TOKEN to OTHER_ID

            access().open()

            assertSignInCameFirst()
            assertEquals(OTHER_TOKEN, ai.getToken())
            assertEquals(LINKED, verdict())
            assertEquals(linked, ai.parkedToken(ID_NEW_SIGNED_IN))
            server.requests.clear()
            access().open()
            assertEquals("the sign-in is still owed", emptyList<String>(), server.paths())
        }

    @Test
    fun `3 - the owed sign-in brings the same account - a newer key, linked, nothing parked`() =
        case {
            linkGoneWhileSignInFails()
            server.own = "$NEW_TOKEN-newer" to ID_NEW_SIGNED_IN

            access().open()

            assertSignInCameFirst()
            assertEquals("$NEW_TOKEN-newer", ai.getToken())
            assertEquals(LINKED, verdict())
            assertEquals(emptyList<String>(), ai.parkedUserIds())
        }

    @Test
    fun `3 - still down - no charged key while the sign-in is owed, and it stays owed`() =
        case {
            val linked = linkGoneWhileSignInFails()
            server.down += ALL

            val charged = access().keyForCharge()

            assertNull("a key with no verdict went to a charge", charged.first)
            assertNotNull(charged.second)
            assertEquals(linked, ai.getToken())

            server.down.clear()
            server.requests.clear()
            access().open()
            assertSignInCameFirst()
        }

    // ---------------------------------------------------------------------------- item 7, GPT 2: register after a fence

    @Test
    fun `7 - the fence moves while register asks its question - no charged key`() = stoppedInQuestion { counter++ }

    @Test
    fun `7 - the owner tag changes while register asks its question - no charged key`() = stoppedInQuestion { tag = "b".repeat(HEX_CHARS) }

    @Test
    fun `7 - the wallet goes while register asks its question - no charged key`() = stoppedInQuestion { walletSaved = false }

    @Test
    fun `7 - the fence moves while register asks its question - open gives no key either`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = OK to state(null, "false")
            server.onArrival[CHALLENGE] = { counter++ }

            assertNull("the old wallet's key came back", access().open().first)
        }

    @Test
    fun `7 - the fence moves and the seed is gone when register signs - the last check stops the charged key`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = OK to state(null, "false")
            var reads = 0
            seedGives = {
                reads++
                if (reads == 1) {
                    seed()
                } else {
                    counter++
                    null
                }
            }

            assertNull("the old wallet's key came back", access().keyForCharge().first)
        }

    private fun stoppedInQuestion(disturb: () -> Unit) =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = OK to state(null, "false")
            server.onArrival[CHALLENGE] = disturb

            assertNull("the old wallet's key came back", access().keyForCharge().first)
            assertEquals(TOKEN_A, ai.getToken())
            assertNull("no verdict across a fence", verdict())
        }

    // ----------------------------------------------------------------- item 8, GPT 1: key/state without a verdict

    @Test
    fun `8 - key state down - no charged key, a sentence, the key stays with no verdict`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.down += ALL

            val charged = access().keyForCharge()

            assertNull("a transport failure permitted a charge", charged.first)
            assertNotNull(charged.second)
            assertEquals(TOKEN_A, ai.getToken())
            assertNull(verdict())
            assertEquals(listOf(KEY_STATE), server.paths())
        }

    @Test
    fun `8 guard - key state 5xx - no charged key, the key stays`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            listOf(500, 502, UNAVAILABLE).forEach { status ->
                server.always[KEY_STATE] = status to OUTAGE
                assertNull("$status permitted a charge", access().keyForCharge().first)
                assertEquals(TOKEN_A, ai.getToken())
            }
        }

    @Test
    fun `8 guard - key state 404, an old server - chargeable as today, with no verdict`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = 404 to """{"error":"not found"}"""

            assertEquals(TOKEN_A, access().keyForCharge().first)
            assertNull(verdict())
        }

    @Test
    fun `8 guard - key state down - open still gives the saved key for what is not charged`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.down += ALL

            assertEquals(TOKEN_A, access().open().first)
        }

    // ------------------------------------------------------------------------ item 9, GPT 5: paid is a JSON Boolean

    @Test
    fun `9 - a paid that is no JSON Boolean, another key on the account - parked, never dropped`() =
        case {
            NOT_BOOLEANS.forEach { value ->
                ai = AiTabAccessFixture.bareStore()
                ai.saveCredentials(TOKEN_A, ID_A)
                server.always[KEY_STATE] = OK to state(FOREIGN_KEY_ID, value)

                access().open()

                assertEquals("paid $value dropped a key", TOKEN_A, ai.parkedToken(ID_A))
            }
        }

    @Test
    fun `9 - a paid that is no JSON Boolean on this wallet's account - stored as unknown`() =
        case {
            NOT_BOOLEANS.forEach { value ->
                ai = AiTabAccessFixture.bareStore()
                ai.saveCredentials(TOKEN_A, ID_A)
                server.always[KEY_STATE] = OK to state(OWN_KEY_ID, value)

                access().open()

                assertEquals("paid $value", "unknown", JSONObject(ai.getLinkStateJson().orEmpty()).getString("paid"))
            }
        }

    @Test
    fun `9 guard - a real false drops the key, a real true parks it`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = OK to state(FOREIGN_KEY_ID, "false")
            access().open()
            assertNull("a real false kept the key", ai.parkedToken(ID_A))

            ai = AiTabAccessFixture.bareStore()
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = OK to state(FOREIGN_KEY_ID, "true")
            access().open()
            assertEquals(TOKEN_A, ai.parkedToken(ID_A))
        }

    // ------------------------------------------------------------------------------------- item 10, ZC-1209: small slips

    @Test
    fun `10 - a refusal whose sign-in never got an answer - the next refusal within a minute signs in`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            val access = access()
            server.down += SIGN_IN

            val first = access.onRefused(TOKEN_A)
            assertNotEquals(aiSignInText(ELSEWHERE), first.second)

            server.down.clear()
            server.requests.clear()
            now += JUST_UNDER_A_MINUTE
            val second = access.onRefused(TOKEN_A)

            assertEquals(listOf(CHALLENGE, SIGN_IN), server.paths())
            assertNotEquals("a network failure read as another phone", aiSignInText(ELSEWHERE), second.second)
            assertEquals(NEW_TOKEN, ai.getToken())
        }

    @Test
    fun `10 - a refusal whose question never got an answer - the same`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            val access = access()
            server.down += ALL

            access.onRefused(TOKEN_A)

            server.down.clear()
            now += JUST_UNDER_A_MINUTE
            assertNotEquals(aiSignInText(ELSEWHERE), access.onRefused(TOKEN_A).second)
            assertEquals(NEW_TOKEN, ai.getToken())
        }

    @Test
    fun `10 guard - after an answered sign-in the pause holds`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            val access = access()
            access.onRefused(TOKEN_A)
            server.requests.clear()

            now += JUST_UNDER_A_MINUTE
            val second = access.onRefused(ai.getToken().orEmpty())

            assertEquals(emptyList<String>(), server.paths())
            assertEquals(aiSignInText(ELSEWHERE), second.second)
        }

    @Test
    fun `10 - Link again that links - says so, not the link-lost sentence`() =
        case {
            ownAccountThatLostItsLink()
            server.always[KEY_STATE] = OK to state(null, "false")

            val answer = access().linkAgain()

            assertEquals(LINKED, verdict())
            assertNotNull("the tap got no outcome", answer.second)
            assertNotEquals(aiSignInText(LINK_LOST), answer.second)
        }

    @Test
    fun `10 - Link again when another key is on the account - an outcome sentence`() =
        case {
            ownAccountThatLostItsLink()
            server.always[KEY_STATE] = OK to state(FOREIGN_KEY_ID, "false")

            assertNotNull("the tap got no outcome", access().linkAgain().second)
            assertEquals(OWN, verdict())
        }

    @Test
    fun `10 - Link again when no candidate fits - an outcome sentence`() =
        case {
            ownAccountThatLostItsLink()
            candidates = listOf(suspend { FP_B })
            server.always[KEY_STATE] = OK to stateWithDigestOf(FP_A)

            assertNotNull("the tap got no outcome", access().linkAgain().second)
            assertEquals(OWN, verdict())
        }

    @Test
    fun `10 - Link again with the server down - an outcome sentence`() =
        case {
            ownAccountThatLostItsLink()
            server.down += ALL

            assertNotNull("the tap got no outcome", access().linkAgain().second)
            assertEquals(OWN, verdict())
        }

    @Test
    fun `10 - an empty candidate list says nothing about whose the key is - it stays, and the comparison is not used up`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = OK to stateWithDigestOf(FP_A)
            candidates = emptyList()

            val charged = access().keyForCharge()

            assertNull(charged.first)
            assertEquals(aiSignInText(NOT_READY), charged.second)
            assertEquals("an empty list dropped the key", TOKEN_A, ai.getToken())
            assertEquals(ID_A, ai.getUserId())
            assertEquals(0, server.paths().count { it == SIGN_IN })

            candidates = listOf(suspend { FP_A })
            assertEquals(TOKEN_A, access().keyForCharge().first)
            assertEquals("the comparison was used up by nothing", 1, server.paths().count { it == REGISTER })
            assertEquals(LINKED, verdict())
        }

    // ------------------------------------------------------------- items 10 and 13: changed_meanwhile on the sign-in

    @Test
    fun `13 - changed_meanwhile once on the sign-in - one more try with a new question, and the key arrives`() =
        case {
            server.once[SIGN_IN] = ArrayDeque(listOf(CONFLICT to refusal(CHANGED)))

            val opened = access().open()

            assertEquals(listOf(CHALLENGE, SIGN_IN, CHALLENGE, SIGN_IN), server.paths())
            assertEquals(NEW_TOKEN, opened.first)
            assertNull(opened.second)
        }

    @Test
    fun `13 - changed_meanwhile twice on the sign-in - no loop, today's try-again sentence, nothing stored`() =
        case {
            server.always[SIGN_IN] = CONFLICT to refusal(CHANGED)

            val opened = access().open()

            assertEquals(listOf(CHALLENGE, SIGN_IN, CHALLENGE, SIGN_IN), server.paths())
            assertNull(opened.first)
            assertEquals(aiSignInText("ai_signin_refused"), opened.second)
            assertNull(ai.getToken())
            assertNull(ai.getLinkStateJson())
        }

    // ------------------------------------------------------------------------ item 11, P gap: 401 bad_token, guards

    @Test
    fun `11 guard - Try to link answered 401 bad_token - the parked key is retired, with its sentence`() =
        case {
            parkedKey()
            server.always[REGISTER] = UNAUTHORIZED to refusal("bad_token")

            val answer = access().tryToLink(ID_A)

            assertNull(ai.parkedToken(ID_A))
            assertEquals(aiSignInText("ai_notice_parked_key_dead"), answer.second)
        }

    @Test
    fun `11 guard - Try to link answered 401 bad_signature - the parked key stays`() =
        case {
            parkedKey()
            server.always[REGISTER] = UNAUTHORIZED to refusal("bad_signature")

            access().tryToLink(ID_A)

            assertEquals(TOKEN_A, ai.parkedToken(ID_A))
        }

    // ---------------------------------------------------------------- item 12, P gap: quiet changed_meanwhile, guards

    @Test
    fun `12 guard - register answered changed_meanwhile - no sentence, nothing stored`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = OK to state(null, "false")
            server.always[REGISTER] = CONFLICT to refusal(CHANGED)
            val before = snapshot()

            val opened = access().open()

            assertNull(opened.second)
            assertEquals(TOKEN_A, opened.first)
            assertEquals(before, snapshot())
        }

    @Test
    fun `12 guard - Try to link answered changed_meanwhile - no sentence, nothing stored`() =
        case {
            parkedKey()
            server.always[REGISTER] = CONFLICT to refusal(CHANGED)
            val before = snapshot()

            assertNull(access().tryToLink(ID_A).second)
            assertEquals(before, snapshot())
        }

    // ------------------------------------------------------------ items 18, 22, 23: what retires a parked key

    @Test
    fun `18 - Try to link answered 401 with no code - the parked key stays`() =
        case {
            parkedKey()
            server.always[REGISTER] = UNAUTHORIZED to """{"error":"Invalid token"}"""

            val answer = access().tryToLink(ID_A)

            assertEquals("a 401 with no code retired a key to paid credit", TOKEN_A, ai.parkedToken(ID_A))
            assertNotEquals(aiSignInText("ai_notice_parked_key_dead"), answer.second)
        }

    @Test
    fun `23 - Try to link answered a bare bad_signature error - the parked key stays`() =
        case {
            parkedKey()
            server.always[REGISTER] = UNAUTHORIZED to """{"error":"bad_signature"}"""

            access().tryToLink(ID_A)

            assertEquals("the other reader of the code retired the key", TOKEN_A, ai.parkedToken(ID_A))
        }

    @Test
    fun `22 - two parked keys of one account, the first refused - only that key is retired`() =
        case {
            parkedRaw("""[{"token":"$TOKEN_A","userId":"$ID_A"},{"token":"$OTHER_TOKEN","userId":"$ID_A"}]""")
            server.always[REGISTER] = UNAUTHORIZED to refusal("bad_token")

            access().tryToLink(ID_A)

            assertEquals("the key nobody refused is gone", OTHER_TOKEN, ai.parkedToken(ID_A))
        }

    // ---------------------------------------------------------------------------- item 19: key_in_use and paid

    @Test
    fun `19 - key_in_use with paid unknown - the saved pair is parked, not overwritten`() =
        keyInUse("""{"signInKeyId":null}""") {
            assertEquals("a key that may hold paid credit was overwritten", TOKEN_A, ai.parkedToken(ID_A))
        }

    @Test
    fun `19 guard - key_in_use with paid no - overwritten, nothing parked`() =
        keyInUse(state(null, "false")) {
            assertEquals(emptyList<String>(), ai.parkedUserIds())
        }

    @Test
    fun `19 guard - key_in_use with paid yes - parked`() =
        keyInUse(state(null, "true")) {
            assertEquals(TOKEN_A, ai.parkedToken(ID_A))
        }

    @Test
    fun `19 guard - an account opened by sign-in starts unknown`() =
        case {
            access().open()

            assertEquals("unknown", paid())
        }

    @Test
    fun `19 guard - a newer key of a paid account stays paid`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = OK to state(OWN_KEY_ID, "true")
            val access = access()
            access.open()
            assertEquals("yes", paid())
            server.own = OTHER_TOKEN to ID_A

            access.onRefused(TOKEN_A)

            assertEquals(OTHER_TOKEN, ai.getToken())
            assertEquals("yes", paid())
        }

    private fun keyInUse(
        stateBody: String,
        check: () -> Unit,
    ) = case {
        ai.saveCredentials(TOKEN_A, ID_A)
        server.always[KEY_STATE] = OK to stateBody
        server.always[REGISTER] = CONFLICT to refusal("key_in_use")
        server.own = OTHER_TOKEN to OTHER_ID

        access().open()

        assertEquals(OTHER_TOKEN, ai.getToken())
        check()
    }

    // ---------------------------------------------------------------------- item 20: paid goes through the parking

    @Test
    fun `20 - a paid key parked and linked by the tap - its account is still paid`() =
        case {
            parkedKey()

            access().tryToLink(ID_A)

            assertEquals(TOKEN_A, ai.getToken())
            assertEquals("paid was reset by the parking", "yes", paid())
        }

    @Test
    fun `20 - a paid key parked and its account found by the claim - still paid`() =
        case {
            parkedKey()
            server.always[CLAIM] = OK to """{"found":true,"token":"$TOKEN_A","userId":"$ID_A"}"""

            byName(access(), "lookForOldCredit", FP_A)

            assertEquals(ID_A, ai.getUserId())
            assertEquals("paid was reset by the parking", "yes", paid())
        }

    @Test
    fun `20 - a paid key parked and its account brought back by the sign-in - still paid`() =
        case {
            parkedKey()
            val access = access()
            server.own = TOKEN_A to ID_A
            server.always[REGISTER] = CONFLICT to refusal("key_in_use")
            ai.saveCredentials("synthetic-token-third", "acct_SYNTHETIC-third")
            ai.writeIfStillThisWallet({ true }) { removeLink() }
            server.always[KEY_STATE] = OK to state(null, "false")

            access.open()

            assertEquals(ID_A, ai.getUserId())
            assertEquals("paid was reset by the parking", "yes", paid())
        }

    @Test
    fun `20 guard - an entry written before this field opens as unknown`() =
        case {
            parkedRaw("""[{"token":"$TOKEN_A","userId":"$ID_A"}]""")

            access().tryToLink(ID_A)

            assertEquals(TOKEN_A, ai.getToken())
            assertEquals("unknown", paid())
        }

    // ---------------------------------------------------------------------------------- item 14: a wording decision

    @Test
    fun `14 guard - a parked trial pair keeps the cautious parked sentence`() =
        case {
            ai.saveCredentials(TOKEN_A, ID_A)
            server.always[KEY_STATE] = OK to """{"signInKeyId":"$FOREIGN_KEY_ID"}"""
            access().open()
            assertEquals(TOKEN_A, ai.parkedToken(ID_A))

            val opened = access().open()

            assertEquals(aiSignInText("ai_notice_key_parked"), opened.second)
            assertTrue("may hold paid" in opened.second.orEmpty())
        }

    // ---------------------------------------------------------------------------------------------- the arrangements

    private fun refusedKeySaved() {
        ai.saveCredentials(TOKEN_A, ID_A)
        // ZC-1250: known not paid, so the refused key is replaced; a key that may hold paid credit is parked instead.
        ai.writeIfStillThisWallet({ true }) { putLinkStateJson("""{"accountId":"$ID_A","paid":"no"}""") }
        server.always[KEY_STATE] = UNAUTHORIZED to """{"error":"Invalid token"}"""
    }

    /** Linked, then a balance answer shows no key on the account while the sign-in cannot be answered. */
    private suspend fun linkGoneWhileSignInFails(
        paid: Boolean = false,
        failure: () -> Unit = { server.down += SIGN_IN },
    ): String {
        server.own = NEW_TOKEN to ID_NEW_SIGNED_IN
        val access = access()
        access.open()
        val linked = checkNotNull(ai.getToken())
        server.own = null
        failure()
        balance(access, null, paid)
        assertEquals(linked, ai.getToken())
        server.down.clear()
        server.requests.clear()
        // What key/state would say today: another key. Only the sign-in may decide here.
        server.always[KEY_STATE] = OK to state(FOREIGN_KEY_ID, "true")
        return linked
    }

    private fun assertSignInCameFirst() {
        assertEquals("the owed sign-in did not come first", listOf(CHALLENGE, SIGN_IN), server.paths().take(2))
        assertFalse("create must be 0", JSONObject(server.requests[1].body.orEmpty()).getBoolean("create"))
    }

    /** Verdict own: linked, then the operator removed the key from the account. */
    private suspend fun ownAccountThatLostItsLink() {
        server.own = NEW_TOKEN to ID_NEW_SIGNED_IN
        val access = access()
        access.open()
        server.own = null
        balance(access, null, false)
        assertEquals(OWN, verdict())
        server.requests.clear()
    }

    /** The key of [ID_A] is parked; the saved slot holds the wallet's own account. */
    private suspend fun parkedKey() {
        ai.saveCredentials(TOKEN_A, ID_A)
        server.always[KEY_STATE] = OK to state(FOREIGN_KEY_ID, "true")
        access().open()
        assertEquals(TOKEN_A, ai.parkedToken(ID_A))
        server.requests.clear()
    }

    // ---------------------------------------------------------------------------------------------- the harness

    private fun case(block: suspend () -> Unit): Unit =
        runBlocking {
            withTimeout(CASE_TIMEOUT_MILLIS) { block() }
        }

    private fun seed() = ByteArray(SEED_BYTES) { it.toByte() }

    private fun verdict(): String? =
        ai
            .getLinkStateJson()
            ?.let { JSONObject(it) }
            ?.takeIf { it.has("verdict") }
            ?.getString("verdict")

    private fun paid(): String = JSONObject(ai.getLinkStateJson().orEmpty()).getString("paid")

    @Suppress("UNCHECKED_CAST")
    private fun parkedRaw(list: String) {
        val prefs =
            AiPreferences::class.java
                .getDeclaredField("prefs\$delegate")
                .apply { isAccessible = true }
                .get(ai) as Lazy<SharedPreferences>
        prefs.value
            .edit()
            .putString(AiPreferences.KEY_PARKED_KEYS, list)
            .commit()
    }

    private suspend fun balance(
        access: AiAccountAccess,
        keyId: String?,
        paid: Boolean?,
    ) = byName(access, "onBalance", keyId, paid)

    /** A suspending function of [access] with these arguments, by name: its form may change in the fix. */
    private suspend fun byName(
        access: AiAccountAccess,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            access.javaClass.methods
                .firstOrNull { it.name == name && it.parameterCount == args.size + 1 }
                ?: throw AssertionError("AiAccountAccess has no suspending $name with ${args.size} parameters")
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(access, *args, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private fun snapshot(): List<Any?> = listOf(ai.getToken(), ai.getUserId(), ai.getLinkStateJson(), ai.parkedUserIds())

    private fun state(
        keyId: String?,
        paid: String,
    ): String = """{"signInKeyId":${keyId?.let { "\"$it\"" } ?: "null"},"paid":$paid}"""

    /** key/state for an account with no key and the salted digest of [fingerprint]. */
    private fun stateWithDigestOf(fingerprint: String): String {
        val salt = ByteArray(SALT_BYTES) { it.toByte() }
        val check = AiSignInApi.sha256Hex(salt + fingerprint.toByteArray(Charsets.US_ASCII))
        val encoded =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(salt)
        return """{"signInKeyId":null,"paid":false,"walletCheck":{"salt":"$encoded","check":"$check"}}"""
    }

    private fun refusal(code: String) = """{"error":"synthetic sentence","code":"$code"}"""

    private class Request(
        val path: String,
        val body: String?,
    )

    /** Answers by path. [own] is the account of this wallet's key: signin create 0 answers unknown_key without one. */
    private class Script {
        val requests = CopyOnWriteArrayList<Request>()
        val down = mutableSetOf<String>()
        val once = mutableMapOf<String, ArrayDeque<Pair<Int, String>>>()
        val always = mutableMapOf<String, Pair<Int, String>>()
        val onArrival = mutableMapOf<String, () -> Unit>()
        var own: Pair<String, String>? = null

        fun paths(): List<String> = requests.map { it.path }

        suspend fun send(
            @Suppress("UNUSED_PARAMETER") method: String,
            path: String,
            @Suppress("UNUSED_PARAMETER") accessKey: String?,
            body: String?,
        ): Pair<Int, String> {
            requests += Request(path, body)
            onArrival[path]?.invoke()
            if (ALL in down || path in down) throw IOException("synthetic: no route")
            once[path]?.removeFirstOrNull()?.let { return it }
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
        const val CLAIM = "/api/v1/ai/auth/key/claim"
        const val ALL = "*"

        const val OK = 200
        const val UNAUTHORIZED = 401
        const val CONFLICT = 409
        const val UNAVAILABLE = 503
        const val OUTAGE = """{"error":"internal"}"""

        const val TOKEN_A = "synthetic-token-a"
        const val ID_A = "acct_SYNTHETIC-a"
        const val NEW_TOKEN = "synthetic-token-new"
        const val NEW_ID = "acct_SYNTHETIC-new"
        const val ID_NEW_SIGNED_IN = "acct_SYNTHETIC-signed-in"
        const val OTHER_TOKEN = "synthetic-token-other"
        const val OTHER_ID = "acct_SYNTHETIC-other"

        const val NEW_ACCOUNT = "ai_notice_new_account_after_refusal"
        const val DROPPED = "ai_notice_other_wallet_dropped"
        const val LINK_LOST = "ai_notice_link_lost"
        const val ELSEWHERE = "ai_signin_elsewhere"
        const val NOT_READY = "ai_signin_wallet_not_ready"
        const val CHANGED = "changed_meanwhile"
        const val LINKED = "linked"
        const val OWN = "own"

        const val START_MILLIS = 1_791_800_000_000L
        const val JUST_UNDER_A_MINUTE = 59_000L
        const val CASE_TIMEOUT_MILLIS = 30_000L
        const val HEX_CHARS = 64
        const val SEED_BYTES = 64
        const val SALT_BYTES = 16

        val NOT_BOOLEANS = listOf("\"unknown\"", "\"false\"", "0", "1", "{}", "[]")
        val OWN_KEY_ID: String by lazy { AiSignInApi.keyId(AiSignInApi.derive(ByteArray(SEED_BYTES) { it.toByte() }).publicKey) }
        val FOREIGN_KEY_ID = AiSignInApi.sha256Hex("synthetic-foreign-key".toByteArray())
        val FP_A = AiSignInApi.sha256Hex("synthetic-address-a".toByteArray())
        val FP_B = AiSignInApi.sha256Hex("synthetic-address-b".toByteArray())
    }
}
