package co.electriccoin.zcash.ui.screen.ai

import android.util.Log
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatStoreStart
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhone
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhoneContext
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * AI-KEY-P (W-52, W-53; AI-SIGNIN-KEY-SPEC revision 2a, sections 3.3, 3.5, 4.4 item 5, 4.5, 5.6, 6.4; must points 1, 2, 7, 10):
 * the other accounts of one wallet. An access key to paid credit is never overwritten or lost.
 *
 * WHY THIS FAILS TODAY: AiAccountAccess does not exist (AI-KEY-S). Once AI-KEY-S is in, key_in_use and
 * key_has_paid_account end with no verdict and no change, and the functions below do not exist.
 *
 * THE CONTRACT: AiAccountAccessTest's (the same eight-parameter constructor, whose fingerprint-free readers stay as they
 * are), plus these suspending functions, each giving Pair(access key or null, sentence or null):
 *
 *   lookForOldCredit(fingerprint: String)  the tap on "Look for AI credit from before 2.13": ONE claim for the fingerprint
 *                                          the caller read on that tap (the constructor stays fingerprint-free, so no other
 *                                          path can reach one)
 *   tryToLink(accountId: String)           the tap on "Try to link" for the parked key of that account
 *   keyForTopup()                          the key to ask for a top-up address; no network; null and ai_topup_not_linked
 *                                          unless the verdict is linked
 *   onTopupAddress()                       the server handed out an address: the stored paid is yes at once
 *
 * The sentence of two paid accounts is answered by open() from the stored state: it survives a new process and ends when
 * onBalance shows this wallet's key id on this account.
 *
 * THE SERVER is a model of spec section 3, with the body shape of the server branch backend/ai-signin-key
 * (aiSignInKey.ts `fail`): a refusal is {"error": <a sentence>, "code": <the code>}; a refused access key is
 * {"error": "Invalid token"} with no code. Every key, account and fingerprint is synthetic.
 */
class AiOtherAccountsTest {
    private val data: File = Files.createTempDirectory("zchat-aikeyp").toFile().apply { deleteOnExit() }
    private val files = KeysetPhone(File(data, "shared_prefs"))
    private val context = KeysetPhoneContext(files, dataDir = data)
    private var now = START_MILLIS
    private val server = ModelServer { now }

    private var tag: String? = WALLET_TAG
    private var counter = 7L
    private var walletSaved = true
    private var seedGives: () -> ByteArray? = { SEED.copyOf() }
    private var candidates: List<suspend () -> String> = emptyList()

    private lateinit var ai: AiPreferences

    @Before
    fun newProcess() {
        StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())
        ChatStoreStart.userVisible = false
        val open = { name: String -> files.Library(name).open() }
        StoreOpenerApi.useCalls(
            StoreOpenerApi.aiHolder(context),
            StoreOpenerApi.FileCalls(createWithMasterKey = { name, _ -> open(name) }, createByKeyName = { name, _ -> open(name) }),
        )
        ai = AiPreferences(context)
        Log.startCapture()
    }

    @After
    fun noFingerprintOutsideTheClaimAndNoSecretInTheLog() {
        val logged = Log.captured().joinToString("\n")
        Log.stopCapture()
        StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())
        server.requests.filter { it.path != CLAIM }.forEach { request ->
            assertNotEquals("the old sign-up was called", OLD_SIGN_UP, request.path)
            val body = request.body.orEmpty()
            assertFalse("a request other than the claim carries walletPubkey", "walletPubkey" in body)
            listOf(FP_A, FP_B, FP_OTHER).forEach { assertFalse("a request carries a fingerprint", it in body.lowercase()) }
        }
        (server.issuedTokens + server.issuedIds + server.nonces + server.signatures).forEach {
            assertFalse("a log line holds a secret or an id", it in logged)
        }
    }

    // ------------------------------------------------------------------------------------------- (a) key_in_use

    @Test
    fun `a - key_in_use - a sign-in with create 0, the trial pair is replaced, linked, the trial-left sentence`() =
        run {
            val trial = server.account(fingerprint = FP_A)
            val own = server.account(fingerprint = null, keyId = OWN_KEY_ID)
            ai.saveCredentials(trial.token, trial.id)
            candidates = listOf(suspend { FP_A })

            val answer = access().call("open")

            assertEquals(listOf(KEY_STATE, CHALLENGE, REGISTER, CHALLENGE, SIGN_IN), server.paths())
            assertFalse("create must be 0 here", server.last(SIGN_IN).json().getBoolean("create"))
            assertEquals(own.token, answer.key)
            assertEquals(own.token, ai.getToken())
            assertEquals(own.id, ai.getUserId())
            assertEquals(LINKED, verdict())
            assertEquals(own.id, linkState()?.getString("accountId"))
            assertEquals(text("ai_notice_trial_account_left"), answer.sentence)
            assertEquals(emptyList<String>(), parked())
        }

    @Test
    fun `a - key_in_use and the sign-in fails - the trial pair stays, with no verdict`() =
        run {
            val trial = server.account(fingerprint = FP_A)
            server.account(fingerprint = null, keyId = OWN_KEY_ID)
            ai.saveCredentials(trial.token, trial.id)
            candidates = listOf(suspend { FP_A })
            server.answerOnce(SIGN_IN, 500, """{"error":"internal"}""")

            access().call("open")

            assertEquals(trial.token, ai.getToken())
            assertEquals(trial.id, ai.getUserId())
            assertNull(verdict())
        }

    // ------------------------------------------------------------------------- (b) a second paid account (must 10)

    @Test
    fun `b - key_has_paid_account - own, the saved pair unchanged and still used, nothing parked, no sign-in`() =
        run {
            val paid = twoPaidAccounts()
            val access = access()

            val answer = access.call("open")

            assertEquals(listOf(KEY_STATE, CHALLENGE, REGISTER), server.paths())
            assertEquals(1, server.verifiedSignatures)
            assertEquals(OWN, verdict())
            assertEquals(paid.id, linkState()?.getString("accountId"))
            assertEquals(paid.token, answer.key)
            assertEquals(paid.token, ai.getToken())
            assertEquals(paid.id, ai.getUserId())
            assertEquals(emptyList<String>(), parked())
            assertEquals(text(TWO_PAID), answer.sentence)

            server.requests.clear()
            assertEquals("a charged request does not use it", paid.token, access.call("keyForCharge").key)
            assertEquals("a verdict asks the server nothing", emptyList<String>(), server.paths())
        }

    @Test
    fun `b - the two-accounts sentence stays in a new process and asks nothing`() =
        run {
            twoPaidAccounts()
            access().call("open")
            server.requests.clear()

            now += ONE_DAY_MILLIS
            val next = access()
            next.call("onForeground")
            val answer = next.call("open")

            assertEquals(text(TWO_PAID), answer.sentence)
            assertEquals(emptyList<String>(), server.paths())
        }

    @Test
    fun `b - a balance with no key or another key on an own account changes nothing and signs in to nothing`() =
        run {
            val paid = twoPaidAccounts()
            val access = access()
            access.call("open")
            val before = entries()
            server.requests.clear()

            access.call("onBalance", null, true)
            access.call("onBalance", FOREIGN_KEY_ID, true)

            assertEquals(emptyList<String>(), server.paths())
            assertEquals(before, entries())
            assertEquals(paid.token, ai.getToken())
        }

    @Test
    fun `b - the sentence ends when a balance answer shows this wallet's key id on this account`() =
        run {
            val paid = twoPaidAccounts()
            val access = access()
            access.call("open")

            access.call("onBalance", OWN_KEY_ID, true)

            assertEquals(LINKED, verdict())
            assertEquals(paid.token, ai.getToken())
            assertNull(access.call("open").sentence)
        }

    // ----------------------------------------------------------------------------------------- (c) F1's sequence

    @Test
    fun `c - an address handed out makes the stored paid yes at once, before any balance answer, also in a new process`() =
        run {
            val access = access()
            access.call("open")

            access.call("onTopupAddress")

            assertEquals("yes", linkState()?.getString("paid"))
            access().call("keyForUse")
            assertEquals("yes", linkState()?.getString("paid"))
        }

    /**
     * F1 as the fragment has it, on a key a build before this one saved (no verdict yet). A once-linked account takes
     * the other way, change 9's sign-in (AI-KEY-S, and the next case), and is never compared again.
     */
    @Test
    fun `c - F1 - an address, another key id on the balance, register answers key_has_paid_account - nothing is overwritten`() =
        run {
            val paid = twoPaidAccounts()
            val access = access()
            access.call("onTopupAddress")
            assertEquals("yes", linkState()?.getString("paid"))

            access.call("onBalance", FOREIGN_KEY_ID, null)
            val answer = access.call("open")

            assertEquals(1, server.count(REGISTER))
            assertEquals("the app signed in to the other account", 0, server.count(SIGN_IN))
            assertEquals(paid.token, ai.getToken())
            assertEquals(paid.id, ai.getUserId())
            assertEquals(OWN, verdict())
            assertEquals(emptyList<String>(), parked())
            assertEquals(paid.token, answer.key)
        }

    @Test
    fun `c - an address, then the sign-in of change 9 returns another account - the paid pair is parked, not gone`() =
        run {
            val access = access()
            access.call("open")
            val paid = server.accountOfKey(OWN_KEY_ID)!!
            access.call("onTopupAddress")
            val other = server.account(fingerprint = null, keyId = null, paid = true)
            paid.keyId = FOREIGN_KEY_ID
            other.keyId = OWN_KEY_ID

            access.call("onBalance", FOREIGN_KEY_ID, null)

            assertEquals(other.id, ai.getUserId())
            assertEquals(listOf(paid.token), parked())
            assertEquals(listOf(paid.id), parkedIds())
        }

    // ------------------------------------------------------------------------------- (d) the replacement rule, 4.5

    @Test
    fun `d allowed a - a newer access key for the same account id replaces a paid pair, and nothing is parked`() =
        run {
            val access = access()
            access.call("open")
            val own = server.accountOfKey(OWN_KEY_ID)!!
            access.call("onTopupAddress")
            val refused = ai.getToken()!!

            val answer = access.call("onRefused", refused)

            assertEquals(own.token, answer.key)
            assertNotEquals(refused, ai.getToken())
            assertEquals(own.id, ai.getUserId())
            assertEquals(emptyList<String>(), parked())
        }

    @Test
    fun `d allowed b - after a 401 a paid linked pair is replaced by a new account, with the sentence`() =
        run {
            val access = access()
            access.call("open")
            val old = server.accountOfKey(OWN_KEY_ID)!!
            access.call("onTopupAddress")
            old.keyId = null
            val refused = old.token
            old.token = "synthetic-no-longer-valid"

            val answer = access.call("onRefused", refused)

            assertNotEquals(old.id, ai.getUserId())
            assertEquals(answer.key, ai.getToken())
            assertEquals(text("ai_notice_new_account_after_refusal"), answer.sentence)
            // ZC-1250: a linked pair is safe to replace, the phrase reopens its account.
            assertEquals(emptyList<String>(), parked())
        }

    @Test
    fun `d allowed c - not this wallet's and not paid - dropped, and the wallet's own account is saved`() =
        run {
            val foreign = server.account(fingerprint = FP_OTHER, paid = false)
            ai.saveCredentials(foreign.token, foreign.id)
            candidates = listOf(suspend { FP_A })

            access().call("open")

            assertNotEquals(foreign.token, ai.getToken())
            assertEquals(emptyList<String>(), parked())
        }

    @Test
    fun `d allowed d - key_in_use replaces the trial pair and parks nothing`() =
        run {
            val trial = server.account(fingerprint = FP_A)
            val own = server.account(fingerprint = null, keyId = OWN_KEY_ID)
            ai.saveCredentials(trial.token, trial.id)
            candidates = listOf(suspend { FP_A })

            access().call("open")

            assertEquals(own.token, ai.getToken())
            assertEquals(emptyList<String>(), parked())
        }

    @Test
    fun `d forbidden - paid yes from the server, not this wallet's - parked`() =
        run {
            val foreign = server.account(fingerprint = FP_OTHER, paid = true)
            ai.saveCredentials(foreign.token, foreign.id)
            candidates = listOf(suspend { FP_A })

            access().call("open")

            assertEquals(listOf(foreign.token), parked())
        }

    /** Spec 4.5: "A stored paid of unknown counts as paid". A key/state with no usable paid is such an answer. */
    @Test
    fun `d forbidden - paid unknown, not this wallet's - parked, not dropped`() =
        run {
            val foreign = server.account(fingerprint = FP_OTHER, paid = false)
            server.omitPaid = true
            ai.saveCredentials(foreign.token, foreign.id)
            candidates = listOf(suspend { FP_A })

            access().call("open")

            assertEquals(listOf(foreign.token), parked())
        }

    @Test
    fun `d forbidden - paid unknown on the balance, the sign-in returns another account - parked, not gone`() =
        run {
            val access = access()
            access.call("open")
            val old = server.accountOfKey(OWN_KEY_ID)!!
            val other = server.account(fingerprint = null)
            old.keyId = null
            other.keyId = OWN_KEY_ID

            access.call("onBalance", null, null)

            assertEquals(other.id, ai.getUserId())
            assertEquals(listOf(old.token), parked())
        }

    /** M1: the app's own record of a handed-out address outlives what a later answer says. */
    @Test
    fun `d forbidden - an address was handed out and key state says not paid - parked, not dropped`() =
        run {
            val old = server.account(fingerprint = null, keyId = FOREIGN_KEY_ID, paid = false)
            ai.saveCredentials(old.token, old.id)
            val access = access()
            access.call("onTopupAddress")

            access.call("open")

            assertEquals(listOf(old.token), parked())
            assertNotEquals(old.token, ai.getToken())
        }

    // -------------------------------------------------------------------------------------------- (e) the claim

    @Test
    fun `e - one tap, one claim, one fingerprint, signed in the wallet line, and no other call about a key`() =
        run {
            val target = server.account(fingerprint = FP_A, paid = true)
            target.ticket = true
            candidates = listOf(suspend { FP_B }, suspend { FP_OTHER })

            access().call("lookForOldCredit", FP_A)

            assertEquals(listOf(CHALLENGE, CLAIM), server.paths())
            val claim = server.last(CLAIM).json()
            assertEquals(setOf("challengeId", "publicKey", "signature", "walletPubkey"), claim.keys().asSequence().toSet())
            assertEquals(FP_A, claim.getString("walletPubkey"))
            assertEquals(AiSignInApi.V2_PUBLIC, claim.getString("publicKey"))
            assertNull("the claim names an access key", server.last(CLAIM).accessKey)
            assertEquals(listOf(FP_A), server.claimedWalletLines)
            assertEquals(1, server.verifiedSignatures)
            server.requests.forEach { request ->
                listOf(FP_B, FP_OTHER).forEach { assertFalse("a second fingerprint", it in request.body.orEmpty()) }
            }
        }

    @Test
    fun `e - a fingerprint in upper case goes out in lower case and is signed so`() =
        run {
            server.account(fingerprint = FP_A, paid = true).ticket = true

            access().call("lookForOldCredit", FP_A.uppercase())

            assertEquals(FP_A, server.last(CLAIM).json().getString("walletPubkey"))
            assertEquals(listOf(FP_A), server.claimedWalletLines)
        }

    @Test
    fun `e - no path but the tap sends a claim or a ticket registration, with a claimable account and a parked key`() =
        run {
            val parkedAccount = parkOneKey()
            parkedAccount.ticket = true
            server.account(fingerprint = FP_A, paid = true).ticket = true
            candidates = listOf(suspend { FP_A })
            val access = access()
            server.requests.clear()

            repeat(3) {
                now += ONE_DAY_MILLIS
                access.call("open")
                access.call("keyForCharge")
                access.call("keyForUse")
                access.call("keyForTopup")
                access.call("onForeground")
                access.call("onBalance", OWN_KEY_ID, false)
                access.call("onBalance", null, false)
                access.call("linkAgain")
                access.call("onRefused", ai.getToken()!!)
            }

            assertEquals("a claim without the tap", 0, server.count(CLAIM))
            val carried = server.requests.filter { it.accessKey == parkedAccount.token || parkedAccount.token in it.body.orEmpty() }
            assertEquals("a parked key without the tap", 0, carried.size)
            assertTrue("a ticket without the tap", server.requests.none { "ticket" in it.body.orEmpty() })
        }

    @Test
    fun `e - nothing, a trial account or an account with a key - found false, the sentence, nothing changes`() =
        run {
            val answers =
                listOf<() -> Unit>(
                    { },
                    { server.account(fingerprint = FP_A, paid = false) },
                    { server.account(fingerprint = FP_B, keyId = FOREIGN_KEY_ID, paid = true) },
                ).mapIndexed { index, arrange ->
                    arrange()
                    val before = entries()
                    val fingerprint = if (index == 2) FP_B else FP_A
                    val answer = access().call("lookForOldCredit", fingerprint)
                    assertEquals("case $index changed the store", before, entries())
                    answer.sentence
                }

            answers.forEach { assertEquals(text("ai_old_credit_none"), it) }
        }

    @Test
    fun `e - found false with a saved pair - the pair stays and is what the answer gives`() =
        run {
            val access = access()
            access.call("open")
            val before = entries()

            val answer = access.call("lookForOldCredit", FP_A)

            assertEquals(before, entries())
            assertEquals(ai.getToken(), answer.key)
        }

    @Test
    fun `e - needs_owner_check - its sentence, nothing changes`() =
        run {
            server.account(fingerprint = FP_A, paid = true)
            val access = access()
            access.call("open")
            val before = entries()

            val answer = access.call("lookForOldCredit", FP_A)

            assertEquals(text("ai_old_credit_needs_check"), answer.sentence)
            assertEquals(before, entries())
        }

    @Test
    fun `e - key_has_paid_account - the two-accounts-found sentence, nothing changes, the ticket is left`() =
        run {
            val access = access()
            access.call("open")
            server.accountOfKey(OWN_KEY_ID)!!.paid = true
            val target = server.account(fingerprint = FP_A, paid = true)
            target.ticket = true
            val before = entries()

            val answer = access.call("lookForOldCredit", FP_A)

            assertEquals(text("ai_old_credit_two_accounts"), answer.sentence)
            assertEquals(before, entries())
            assertTrue(target.ticket)
        }

    @Test
    fun `e - found true on a phone with no key - saved as linked, the opened sentence, no sign-in`() =
        run {
            val target = server.account(fingerprint = FP_A, paid = true)
            target.ticket = true

            val answer = access().call("lookForOldCredit", FP_A)

            assertEquals(target.token, answer.key)
            assertEquals(target.token, ai.getToken())
            assertEquals(target.id, ai.getUserId())
            assertEquals(LINKED, verdict())
            assertEquals(target.id, linkState()?.getString("accountId"))
            assertEquals(text("ai_old_credit_opened"), answer.sentence)
            assertEquals(0, server.count(SIGN_IN))
        }

    @Test
    fun `e - found true over a paid saved pair parks that pair first`() =
        run {
            val paid = ownAccountThatLostItsLink()
            val target = server.account(fingerprint = FP_A, paid = true)
            target.ticket = true

            access().call("lookForOldCredit", FP_A)

            assertEquals(target.token, ai.getToken())
            assertEquals(listOf(paid.token), parked())
            assertEquals(listOf(paid.id), parkedIds())
        }

    @Test
    fun `e - found true over a saved pair with paid unknown parks that pair first`() =
        run {
            val unknown = server.account(fingerprint = null)
            ai.saveCredentials(unknown.token, unknown.id)
            val target = server.account(fingerprint = FP_A, paid = true)
            target.ticket = true

            access().call("lookForOldCredit", FP_A)

            assertEquals(target.token, ai.getToken())
            assertEquals(listOf(unknown.token), parked())
        }

    @Test
    fun `e - no seed - no call, still opening your wallet`() =
        run {
            seedGives = { null }

            val answer = access().call("lookForOldCredit", FP_A)

            assertEquals(emptyList<String>(), server.paths())
            assertEquals(text("ai_signin_wallet_not_ready"), answer.sentence)
        }

    @Test
    fun `e - no saved wallet - no call, no write`() =
        run {
            walletSaved = false
            val before = entries()

            access().call("lookForOldCredit", FP_A)

            assertEquals(emptyList<String>(), server.paths())
            assertEquals(before, entries())
        }

    // ----------------------------------------------------------------------------------- (f) "Try to link", retiring

    @Test
    fun `f - the tap sends one register with the ticket proof and the parked key - 200 makes it the saved pair, linked`() =
        run {
            val foreign = parkOneKey()
            foreign.ticket = true
            val access = access()
            server.requests.clear()

            val answer = access.call("tryToLink", foreign.id)

            assertEquals(listOf(CHALLENGE, REGISTER), server.paths())
            val register = server.last(REGISTER)
            assertEquals(foreign.token, register.accessKey)
            assertEquals("ticket", register.json().getString("walletProof"))
            assertEquals(listOf("ticket"), server.registeredWalletLines)
            assertFalse("the ticket was not used up", foreign.ticket)
            assertEquals(foreign.token, answer.key)
            assertEquals(foreign.token, ai.getToken())
            assertEquals(foreign.id, ai.getUserId())
            assertEquals(LINKED, verdict())
            assertEquals(foreign.id, linkState()?.getString("accountId"))
            assertFalse("the key is still in the list", foreign.token in parked())
        }

    @Test
    fun `f - 200 over a paid saved pair parks that pair`() =
        run {
            val foreign = parkOneKey()
            foreign.ticket = true
            val access = access()
            val own = server.accountOfKey(OWN_KEY_ID)!!
            access.call("onTopupAddress")
            own.keyId = null
            own.paid = true
            access.call("onBalance", null, true)
            assertEquals("own after unknown_key", OWN, verdict())

            access.call("tryToLink", foreign.id)

            assertEquals(foreign.token, ai.getToken())
            assertEquals(listOf(own.token), parked())
            assertEquals(listOf(own.id), parkedIds())
        }

    @Test
    fun `f - 200 over a saved pair with paid unknown parks that pair`() =
        run {
            val foreign = parkOneKey()
            foreign.ticket = true
            val unknown = server.account(fingerprint = null)
            ai.saveCredentials(unknown.token, unknown.id)

            access().call("tryToLink", foreign.id)

            assertEquals(foreign.token, ai.getToken())
            assertEquals(listOf(unknown.token), parked())
        }

    @Test
    fun `f - every refusal other than 401 changes nothing and the key stays parked`() =
        run {
            val foreign = parkOneKey()
            foreign.ticket = true
            val access = access()
            val before = entries()

            listOf(
                409 to refusal("account_has_other_key"),
                409 to refusal("key_in_use"),
                409 to refusal("key_has_paid_account"),
                409 to refusal("changed_meanwhile"),
                400 to refusal("challenge_expired"),
                429 to refusal("rate_limited"),
                500 to """{"error":"internal"}""",
                404 to """{"error":"not found"}""",
            ).forEach { (status, body) ->
                server.answerAlways(REGISTER, status, body)
                access.call("tryToLink", foreign.id)
                assertEquals("$status $body changed the store", before, entries())
            }
        }

    @Test
    fun `f - a transport failure changes nothing and the key stays parked`() =
        run {
            val foreign = parkOneKey()
            val access = access()
            val before = entries()
            server.transportDown = true

            access.call("tryToLink", foreign.id)

            assertEquals(before, entries())
        }

    /**
     * A MISMATCH WITH THE SPEC, on the side of the credit. Spec 4.5 says "Try to link answered 401: the key no longer
     * works". But spec 3.3 and the server branch answer a tap with no live ticket with 401 bad_signature: the server
     * rebuilds the wallet line from its stored fingerprint, and the signature over "ticket" does not fit. A user who taps
     * BEFORE the operator's check would lose the only key to paid credit. Only a refused access key retires it.
     */
    @Test
    fun `f - 401 bad_signature, a tap before the operator's check - the key stays parked, and the same tap works after it`() =
        run {
            val foreign = parkOneKey()
            val access = access()
            val before = entries()

            val answer = access.call("tryToLink", foreign.id)

            assertEquals(1, server.count(REGISTER))
            assertEquals("a key to paid credit was retired by a bad_signature", before, entries())
            assertNotEquals(text(PARKED_DEAD), answer.sentence)

            foreign.ticket = true
            access.call("tryToLink", foreign.id)

            assertEquals(foreign.token, ai.getToken())
            assertEquals(LINKED, verdict())
        }

    @Test
    fun `f - a refused access key retires that key alone, with its sentence, and it is in no entry afterwards`() =
        run {
            val dead = parkOneKey()
            val alive = parkOneKey()
            val deadToken = dead.token
            dead.token = "synthetic-no-longer-valid"
            val access = access()

            val answer = access.call("tryToLink", dead.id)

            assertEquals(text(PARKED_DEAD), answer.sentence)
            assertEquals(listOf(alive.token), parked())
            entries().forEach { (name, value) -> assertFalse("$name still holds the retired key", deadToken in value.toString()) }
        }

    @Test
    fun `f - with the last key retired the parked notice is gone`() =
        run {
            val dead = parkOneKey()
            dead.token = "synthetic-no-longer-valid"
            val access = access()

            access.call("tryToLink", dead.id)

            assertEquals(emptyList<String>(), parked())
            assertNotEquals(text(PARKED_NOTICE), access.call("open").sentence)
        }

    @Test
    fun `f - a sign-in that returns a parked key's account retires it, with its sentence, and it is in no entry`() =
        run {
            val foreign = parkOneKey()
            val oldToken = foreign.token
            val own = server.accountOfKey(OWN_KEY_ID)!!
            own.keyId = null
            foreign.keyId = OWN_KEY_ID
            val refused = own.token
            own.token = "synthetic-no-longer-valid"
            val access = access()

            val answer = access.call("onRefused", refused)

            assertEquals(foreign.id, ai.getUserId())
            assertEquals(text(PARKED_OPENED), answer.sentence)
            assertEquals(emptyList<String>(), parked())
            entries().forEach { (name, value) -> assertFalse("$name still holds the retired key", oldToken in value.toString()) }
            assertNotEquals(text(PARKED_NOTICE), access.call("open").sentence)
        }

    @Test
    fun `f - a claim that returns a parked key's account retires it, with its sentence`() =
        run {
            val foreign = parkOneKey()
            val oldToken = foreign.token
            foreign.fingerprint = FP_A
            foreign.ticket = true

            val answer = access().call("lookForOldCredit", FP_A)

            assertEquals(foreign.id, ai.getUserId())
            assertEquals(foreign.token, ai.getToken())
            assertEquals(text(PARKED_OPENED), answer.sentence)
            assertFalse("the key is still in the list", foreign.id in parkedIds())
            entries().forEach { (name, value) -> assertFalse("$name still holds the retired key", oldToken in value.toString()) }
        }

    @Test
    fun `f - an account id that is not in the list - no call`() =
        run {
            parkOneKey()
            server.requests.clear()
            val before = entries()

            access().call("tryToLink", "acct_SYNTHETIC-nobody")

            assertEquals(emptyList<String>(), server.paths())
            assertEquals(before, entries())
        }

    // -------------------------------------------------------------------------------------------- (g) the top-up

    @Test
    fun `g - a linked account gets its key for the top-up with no call`() =
        run {
            val access = access()
            access.call("open")
            server.requests.clear()

            val answer = access.call("keyForTopup")

            assertEquals(ai.getToken(), answer.key)
            assertNull(answer.sentence)
            assertEquals(emptyList<String>(), server.paths())
        }

    @Test
    fun `g - no verdict - no key, the sentence, no call`() =
        run {
            ai.saveCredentials(TOKEN_SAVED, USER_SAVED)

            val answer = access().call("keyForTopup")

            assertNull(answer.key)
            assertEquals(text(TOPUP_NOT_LINKED), answer.sentence)
            assertEquals(emptyList<String>(), server.paths())
        }

    @Test
    fun `g - verdict own - no key, the sentence, no call`() =
        run {
            twoPaidAccounts()
            val access = access()
            access.call("open")
            server.requests.clear()

            val answer = access.call("keyForTopup")

            assertNull(answer.key)
            assertEquals(text(TOPUP_NOT_LINKED), answer.sentence)
            assertEquals(emptyList<String>(), server.paths())
        }

    @Test
    fun `g - the tab asks by the verdict, and tells of a handed-out address at once`() {
        val body = tabVm.substringAfter("fun loadTopupAddress(").substringBefore("\n    fun ")
        val asks = body.indexOf("keyForTopup(")
        val calls = body.indexOf("client.topupAddress(")
        val tells = body.indexOf("onTopupAddress(")
        assertTrue("loadTopupAddress does not ask keyForTopup", asks >= 0)
        assertTrue("loadTopupAddress no longer asks the server", calls >= 0)
        assertTrue("the verdict is asked after the server", asks < calls)
        assertTrue("a handed-out address is not told before the result leaves", tells in calls until body.indexOf("onResult(", calls))
    }

    // --------------------------------------------------------------------------------------------- (h) the fence

    @Test
    fun `h - the claim's answer is not written across a claim for another wallet`() = fencedClaim(::claim)

    @Test
    fun `h - the claim's answer is not written across a wipe`() = fencedClaim(::wipe)

    @Test
    fun `h - the claim's answer is not written across a removed owner record`() = fencedClaim(::ownerRemoved)

    @Test
    fun `h - the claim's answer is not written across a removed wallet`() = fencedClaim { walletSaved = false }

    @Test
    fun `h - Try to link's answer is not written across a claim for another wallet`() = fencedLink(::claim)

    @Test
    fun `h - Try to link's answer is not written across a wipe`() = fencedLink(::wipe)

    @Test
    fun `h - Try to link's answer is not written across a removed owner record`() = fencedLink(::ownerRemoved)

    @Test
    fun `h - Try to link's retirement is not written across a wipe`() =
        run {
            val dead = parkOneKey()
            dead.token = "synthetic-no-longer-valid"
            fence(REGISTER, ::wipe) { access().call("tryToLink", dead.id) }
        }

    @Test
    fun `h - the stored paid of a handed-out address is not written with no owner record`() =
        run {
            val access = access()
            access.call("open")
            val before = entries()
            ownerRemoved()

            access.call("onTopupAddress")

            assertEquals(before, entries())
        }

    private fun fencedClaim(disturb: () -> Unit) =
        run {
            server.account(fingerprint = FP_A, paid = true).ticket = true
            fence(CLAIM, disturb) { access().call("lookForOldCredit", FP_A) }
        }

    private fun fencedLink(disturb: () -> Unit) =
        run {
            val foreign = parkOneKey()
            foreign.ticket = true
            fence(REGISTER, disturb) { access().call("tryToLink", foreign.id) }
        }

    private suspend fun CoroutineScope.fence(
        heldPath: String,
        disturb: () -> Unit,
        operation: suspend () -> Answer,
    ) {
        val gate = server.hold(heldPath)
        val running = background { operation() }
        gate.arrived.await()
        disturb()
        val before = entries()

        gate.release.complete(Unit)
        running.await()

        assertEquals("a late answer wrote into the AI store", before, entries())
    }

    private fun claim() {
        counter++
        tag = "b".repeat(WALLET_TAG.length)
    }

    private fun wipe() {
        counter++
    }

    private fun ownerRemoved() {
        tag = null
    }

    // ------------------------------------------------------------------------------------------------- the source

    private val tabVm get() = AiSignInApi.read(AiSignInApi.mainAiFile("AiTabVM.kt"))

    /**
     * The permanent place (change 3), since ZC-1265 the small entry that opens the row: nothing conditional encloses it,
     * and nothing returns before it. The row and its note stand behind it (AiOldCreditEntryIsCollapsedTest).
     */
    @Test
    fun `the entry to the Look for row is built in AiTabView under no condition on state`() {
        val source = AiSignInApi.read(AiSignInApi.mainAiFile("AiTabView.kt"))
        val start = source.indexOf("\nfun AiTabView(")
        assertTrue("AiTabView is gone", start >= 0)
        val function = source.substring(start).let { it.substring(0, nextTopLevelFunction(it)) }
        listOf("ai_old_credit_entry").forEach { key ->
            val row = Regex("""R\.string\.$key\b""").find(function)
            assertTrue("$key is not in AiTabView", row != null)
            val before = function.substring(0, row!!.range.first)
            enclosingLines(before).forEach { line ->
                CONDITIONAL.forEach { word -> assertFalse("$key stands under a condition: $line", word.containsMatchIn(line)) }
            }
            assertFalse("something returns before $key", Regex("""\breturn\b""").containsMatchIn(before.substringAfter(") {\n")))
        }
    }

    @Test
    fun `the Try to link button stands in the tab`() {
        assertTrue(
            "R.string.ai_try_to_link is not in AiTabView",
            "R.string.ai_try_to_link" in AiSignInApi.read(AiSignInApi.mainAiFile("AiTabView.kt")),
        )
    }

    /** "ONE function": only the access class, the tab's tap and the two composables that hand the tap on name it. */
    @Test
    fun `lookForOldCredit is reached only by the tab's tap`() =
        reachedOnlyByTheTap("lookForOldCredit")

    @Test
    fun `tryToLink is reached only by the tab's tap`() =
        reachedOnlyByTheTap("tryToLink")

    private fun reachedOnlyByTheTap(name: String) {
        val access = AiSignInApi.read(AiSignInApi.mainAiFile("AiAccountAccess.kt"))
        assertTrue("AiAccountAccess has no $name", "suspend fun $name(" in access)
        val naming =
            AiSignInApi
                .productSources()
                .filter { Regex("""\b$name\b""").containsMatchIn(it.readText()) }
                .map { it.name }
                .toSet()
        assertEquals(setOf("AiAccountAccess.kt", "AiTabVM.kt", "AndroidAiTab.kt"), naming)
        val calls = Regex("""\.$name\(""").findAll(tabVm).count()
        assertEquals("AiTabVM must call $name once", 1, calls)
        val own = tabVm.substringAfter("fun $name(", "").substringBefore("\n    fun ").substringBefore("\n    private fun ")
        assertTrue("the call is not in the tab's own $name", ".$name(" in own)
        val wiring = AiSignInApi.read(AiSignInApi.mainAiFile("AndroidAiTab.kt"))
        assertFalse("the tab's $name runs from an effect", Regex("""LaunchedEffect[^\n]*\n[^\n]*$name""").containsMatchIn(wiring))
    }

    /** What today's sign-up sends: the SHA-256 of the default unified address (AndroidAiTab, WalletPubkey). */
    @Test
    fun `the tap's fingerprint is the one today's sign-up sends`() {
        val own = tabVm.substringAfter("fun lookForOldCredit(", "").substringBefore("\n    fun ").substringBefore("\n    private fun ")
        assertTrue("the tab has no lookForOldCredit", own.isNotEmpty())
        val wiring = AiSignInApi.read(AiSignInApi.mainAiFile("AndroidAiTab.kt"))
        assertTrue("the tab is not handed WalletPubkey.deriveOrNull", "WalletPubkey.deriveOrNull(" in wiring)
    }

    @Test
    fun `the claim's path is named in one file`() {
        val naming = AiSignInApi.productSources().filter { "key/claim" in it.readText() }.map { it.name }
        assertEquals("the claim's path must stand in exactly one file", 1, naming.size)
    }

    private fun nextTopLevelFunction(text: String): Int {
        val next = Regex("""\n(@Composable\n)?(private |internal )?fun """).findAll(text).drop(1).firstOrNull()
        return next?.range?.first ?: text.length
    }

    /** The line of every brace still open at the end of [before]. */
    private fun enclosingLines(before: String): List<String> {
        val open = ArrayDeque<Int>()
        before.forEachIndexed { index, char ->
            if (char == '{') open.addLast(index)
            if (char == '}' && open.isNotEmpty()) open.removeLast()
        }
        return open.map { index ->
            val from = before.lastIndexOf('\n', index) + 1
            before.substring(from, index + 1)
        }
    }

    // ---------------------------------------------------------------------------------------------- the arrangements

    /** The saved pair is paid, matches by its address, holds no key; the phrase's key sits on another paid account. */
    private fun twoPaidAccounts(): Account {
        val paid = server.account(fingerprint = FP_A, paid = true)
        server.account(fingerprint = null, keyId = OWN_KEY_ID, paid = true)
        ai.saveCredentials(paid.token, paid.id)
        candidates = listOf(suspend { FP_A })
        return paid
    }

    /** Verdict own, paid yes: a linked account handed an address, whose key the operator removed; unknown_key. */
    private suspend fun ownAccountThatLostItsLink(): Account {
        val access = access()
        access.call("open")
        val own = server.accountOfKey(OWN_KEY_ID)!!
        access.call("onTopupAddress")
        own.keyId = null
        own.paid = true
        access.call("onBalance", null, true)
        assertEquals(OWN, verdict())
        return own
    }

    /** A paid account the comparison cannot match is parked; the saved slot holds the wallet's own account. */
    private suspend fun parkOneKey(): Account {
        val foreign = server.account(fingerprint = FP_OTHER, paid = true)
        ai.saveCredentials(foreign.token, foreign.id)
        candidates = listOf(suspend { FP_B })
        access().call("open")
        assertTrue("the arrangement did not park the key", foreign.token in parked())
        return foreign
    }

    // ---------------------------------------------------------------------------------------------- the harness

    /** The English of one key of spec 7.1, as AiAccountAccess answers it. */
    private fun text(key: String): String = aiSignInText(key)

    private fun refusal(code: String) = """{"error":"synthetic sentence","code":"$code"}"""

    private class Answer(
        val key: String?,
        val sentence: String?,
    )

    private class Access(
        val target: Any,
    ) {
        suspend fun call(
            name: String,
            vararg args: Any?,
        ): Answer {
            val method =
                target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size + 1 }
                    ?: throw AssertionError("AiAccountAccess has no suspending $name with ${args.size} parameters")
            val result =
                suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                    try {
                        method.invoke(target, *args, continuation)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            val pair = result as? Pair<*, *>
            return Answer(pair?.first as String?, pair?.second as String?)
        }
    }

    private fun access(): Access {
        val type =
            try {
                Class.forName(ACCESS_CLASS)
            } catch (e: ClassNotFoundException) {
                throw AssertionError("AiAccountAccess does not exist (AI-KEY-S)", e)
            }
        val constructor =
            type.constructors.firstOrNull { it.parameterCount == CONSTRUCTOR_PARAMETERS }
                ?: throw AssertionError("AiAccountAccess has no constructor with $CONSTRUCTOR_PARAMETERS parameters")
        val seed: suspend () -> ByteArray? = { seedGives() }
        val readCandidates: suspend () -> List<suspend () -> String> = { candidates }
        val owner: () -> String? = { tag }
        val generation: () -> Long = { counter }
        val saved: suspend () -> Boolean = { walletSaved }
        val clock: () -> Long = { now }
        val send: suspend (String, String, String?, String?) -> Pair<Int, String> = { method, path, key, body ->
            server.send(method, path, key, body)
        }
        return Access(constructor.newInstance(ai, seed, readCandidates, owner, generation, saved, clock, send))
    }

    private fun run(block: suspend CoroutineScope.() -> Unit): Unit =
        runBlocking {
            withTimeout(CASE_TIMEOUT_MILLIS) { block() }
        }

    private fun <T> CoroutineScope.background(block: suspend () -> T) = async(Dispatchers.Default) { block() }

    private fun entries(): Map<String, Any?> =
        files.entries(AI_STORE).filterKeys { !it.startsWith(LIBRARY_ENTRY) }

    private fun linkState(): JSONObject? = entries()["ai_link_state"]?.let { JSONObject(it.toString()) }

    private fun verdict(): String? = linkState()?.takeIf { it.has("verdict") }?.getString("verdict")

    private fun parkedList(): List<JSONObject> {
        val list = entries()["ai_parked_keys"]?.let { JSONArray(it.toString()) } ?: return emptyList()
        return List(list.length()) { list.getJSONObject(it) }
    }

    private fun parked(): List<String> = parkedList().map { it.getString("token") }

    private fun parkedIds(): List<String> = parkedList().map { it.getString("userId") }

    private class Request(
        val path: String,
        val accessKey: String?,
        val body: String?,
    ) {
        fun json(): JSONObject = JSONObject(body.orEmpty())
    }

    private class Gate {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class Account(
        val id: String,
        var token: String,
        var fingerprint: String?,
        var keyId: String?,
        var paid: Boolean,
        var ticket: Boolean = false,
    )

    /** Spec section 3 with the answers of this batch, in the order of 3.3 and of the server branch's register. */
    private class ModelServer(
        private val clock: () -> Long,
    ) {
        val requests = CopyOnWriteArrayList<Request>()
        val accounts = CopyOnWriteArrayList<Account>()
        val nonces = mutableListOf<String>()
        val signatures = mutableListOf<String>()
        val issuedTokens = mutableListOf<String>()
        val issuedIds = mutableListOf<String>()
        val registeredWalletLines = mutableListOf<String>()
        val claimedWalletLines = mutableListOf<String>()
        var verifiedSignatures = 0
        var transportDown = false
        var omitPaid = false

        private val gates = mutableMapOf<String, Gate>()
        private val onceAnswers = mutableMapOf<String, Pair<Int, String>>()
        private val alwaysAnswers = mutableMapOf<String, Pair<Int, String>>()
        private val challenges = mutableMapOf<String, Pair<String, Long>>()
        private var counter = 0

        fun paths(): List<String> = requests.map { it.path }

        fun count(path: String): Int = requests.count { it.path == path }

        fun last(path: String): Request = requests.last { it.path == path }

        fun hold(path: String): Gate = Gate().also { gates[path] = it }

        fun answerOnce(
            path: String,
            status: Int,
            body: String,
        ) {
            onceAnswers[path] = status to body
        }

        fun answerAlways(
            path: String,
            status: Int,
            body: String,
        ) {
            alwaysAnswers[path] = status to body
        }

        fun accountOfKey(keyId: String): Account? = accounts.firstOrNull { it.keyId == keyId }

        fun account(
            fingerprint: String?,
            keyId: String? = null,
            paid: Boolean = false,
        ): Account {
            counter++
            val made = Account("acct_SYNTHETIC-$counter", "synthetic-token-$counter", fingerprint, keyId, paid)
            issuedTokens += made.token
            issuedIds += made.id
            accounts += made
            return made
        }

        suspend fun send(
            @Suppress("UNUSED_PARAMETER") method: String,
            path: String,
            accessKey: String?,
            body: String?,
        ): Pair<Int, String> {
            requests += Request(path, accessKey, body)
            if (transportDown) throw IOException("synthetic: no route")
            val answer = answer(path, accessKey, body)
            gates[path]?.let { gate ->
                gate.arrived.complete(Unit)
                gate.release.await()
            }
            return answer
        }

        @Synchronized
        private fun answer(
            path: String,
            accessKey: String?,
            body: String?,
        ): Pair<Int, String> {
            onceAnswers.remove(path)?.let { return it }
            alwaysAnswers[path]?.let { return it }
            return when (path) {
                CHALLENGE -> challenge()
                KEY_STATE -> state(accessKey)
                REGISTER -> register(accessKey, JSONObject(body.orEmpty()))
                SIGN_IN -> signIn(JSONObject(body.orEmpty()))
                CLAIM -> claim(JSONObject(body.orEmpty()))
                else -> 200 to "{}"
            }
        }

        private fun refused(
            status: Int,
            code: String,
        ) = status to """{"error":"synthetic sentence of the server","code":"$code"}"""

        private fun challenge(): Pair<Int, String> {
            counter++
            val id = "challenge-$counter"
            val source = AiSignInApi.sha256Hex("nonce-$counter".toByteArray()).take(NONCE_BYTES).toByteArray()
            val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(source)
            val expires = clock() / MILLIS + CHALLENGE_SECONDS
            nonces += nonce
            challenges[id] = nonce to expires
            return 200 to """{"challengeId":"$id","nonce":"$nonce","expiresAt":$expires}"""
        }

        private fun state(accessKey: String?): Pair<Int, String> {
            val account = accounts.firstOrNull { it.token == accessKey } ?: return INVALID_TOKEN
            val answer = JSONObject().put("signInKeyId", account.keyId ?: JSONObject.NULL)
            if (!omitPaid) answer.put("paid", account.paid)
            val stored = account.fingerprint
            if (account.keyId == null && stored != null) {
                val salt = ByteArray(SALT_BYTES) { it.toByte() }
                answer.put(
                    "walletCheck",
                    JSONObject()
                        .put("salt", Base64.getUrlEncoder().withoutPadding().encodeToString(salt))
                        .put("check", AiSignInApi.sha256Hex(salt + stored.toByteArray(Charsets.US_ASCII))),
                )
            }
            return 200 to answer.toString()
        }

        private fun register(
            accessKey: String?,
            body: JSONObject,
        ): Pair<Int, String> {
            val account = accounts.firstOrNull { it.token == accessKey } ?: return refused(401, "bad_token")
            val publicKey = body.getString("publicKey")
            val keyId = AiSignInApi.keyId(publicKey)
            val (nonce, expires) = burn(body) ?: return refused(400, "challenge_expired")
            val ticket = body.getString("walletProof") == "ticket" && account.ticket
            val wallet = if (ticket) "ticket" else account.fingerprint ?: "-"
            val signed = nineLines("register", nonce, expires, keyId, account.id, wallet, false)
            if (!verified(publicKey, signed, body.getString("signature"))) return refused(401, "bad_signature")
            if (account.keyId == keyId) return 200 to """{"registered":true}"""
            if (account.keyId != null) return refused(409, "account_has_other_key")
            val holder = accountOfKey(keyId)
            if (holder != null) {
                if (!account.paid) return refused(409, "key_in_use")
                if (holder.paid) return refused(409, "key_has_paid_account")
                holder.keyId = null
            }
            registeredWalletLines += wallet
            account.keyId = keyId
            if (ticket) account.ticket = false
            return 200 to """{"registered":true}"""
        }

        private fun signIn(body: JSONObject): Pair<Int, String> {
            val publicKey = body.getString("publicKey")
            val keyId = AiSignInApi.keyId(publicKey)
            val create = body.getBoolean("create")
            val (nonce, expires) = burn(body) ?: return refused(400, "challenge_expired")
            val signed = nineLines("signin", nonce, expires, keyId, "-", "-", create)
            if (!verified(publicKey, signed, body.getString("signature"))) return refused(401, "bad_signature")
            val found = accountOfKey(keyId)
            if (found == null && !create) return refused(409, "unknown_key")
            val account = found ?: account(fingerprint = null, keyId = keyId)
            newToken(account)
            return 200 to
                JSONObject()
                    .put("userId", account.id)
                    .put("token", account.token)
                    .put("created", found == null)
                    .toString()
        }

        /** Spec 3.5, with its table of who sees what (decision 5a). */
        private fun claim(body: JSONObject): Pair<Int, String> {
            val publicKey = body.getString("publicKey")
            val keyId = AiSignInApi.keyId(publicKey)
            val fingerprint = body.getString("walletPubkey")
            val (nonce, expires) = burn(body) ?: return refused(400, "challenge_expired")
            val signed = nineLines("claim", nonce, expires, keyId, "-", fingerprint, false)
            if (!verified(publicKey, signed, body.getString("signature"))) return refused(401, "bad_signature")
            claimedWalletLines += fingerprint
            val target = accounts.firstOrNull { it.fingerprint == fingerprint && it.paid && it.keyId == null }
            if (target == null) return 200 to """{"found":false}"""
            if (!target.ticket) return refused(409, "needs_owner_check")
            val holder = accountOfKey(keyId)
            if (holder?.paid == true) return refused(409, "key_has_paid_account")
            holder?.keyId = null
            target.keyId = keyId
            target.ticket = false
            newToken(target)
            return 200 to
                JSONObject()
                    .put("found", true)
                    .put("userId", target.id)
                    .put("token", target.token)
                    .toString()
        }

        private fun newToken(account: Account) {
            counter++
            account.token = "synthetic-token-$counter"
            issuedTokens += account.token
        }

        private fun burn(body: JSONObject): Pair<String, Long>? = challenges.remove(body.getString("challengeId"))

        private fun verified(
            publicKey: String,
            signed: String,
            signature: String,
        ): Boolean {
            signatures += signature
            val good = E2EEncryption.verify(publicKey, signed, signature)
            if (good) verifiedSignatures++
            return good
        }

        @Suppress("LongParameterList")
        private fun nineLines(
            purpose: String,
            nonce: String,
            expires: Long,
            keyId: String,
            account: String,
            wallet: String,
            create: Boolean,
        ): String =
            listOf(
                "ZCHAT-AI-AUTH-V1",
                "aud:" + AiSignInApi.serviceName(),
                "purpose:$purpose",
                "nonce:$nonce",
                "exp:$expires",
                "key:$keyId",
                "account:$account",
                "wallet:$wallet",
                "create:" + if (create) "1" else "0",
            ).joinToString("") { it + "\n" }
    }

    private companion object {
        const val ACCESS_CLASS = "co.electriccoin.zcash.ui.screen.ai.AiAccountAccess"
        const val CONSTRUCTOR_PARAMETERS = 8
        const val AI_STORE = "zchat_ai_prefs"
        const val LIBRARY_ENTRY = "__androidx_security_crypto_encrypted_prefs_"
        const val OLD_SIGN_UP = "/api/v1/ai/auth/register"
        const val CHALLENGE = "/api/v1/ai/auth/key/challenge"
        const val KEY_STATE = "/api/v1/ai/auth/key/state"
        const val REGISTER = "/api/v1/ai/auth/key/register"
        const val SIGN_IN = "/api/v1/ai/auth/key/signin"
        const val CLAIM = "/api/v1/ai/auth/key/claim"
        const val LINKED = "linked"
        const val OWN = "own"
        const val TWO_PAID = "ai_notice_two_paid_accounts"
        const val PARKED_NOTICE = "ai_notice_key_parked"
        const val PARKED_DEAD = "ai_notice_parked_key_dead"
        const val PARKED_OPENED = "ai_notice_parked_key_opened"
        const val TOPUP_NOT_LINKED = "ai_topup_not_linked"
        const val TOKEN_SAVED = "synthetic-saved-token"
        const val USER_SAVED = "acct_SYNTHETIC-saved"
        const val START_MILLIS = 1_791_800_000_000L
        const val ONE_DAY_MILLIS = 86_400_000L
        const val MILLIS = 1_000L
        const val CHALLENGE_SECONDS = 120L
        const val NONCE_BYTES = 32
        const val SALT_BYTES = 16
        const val CASE_TIMEOUT_MILLIS = 30_000L
        const val SEED_BYTES = 64

        val INVALID_TOKEN = 401 to """{"error":"Invalid token"}"""
        val WALLET_TAG = "a".repeat(64)
        val SEED = AiSignInApi.bytes(SEED_BYTES)
        val OWN_KEY_ID: String by lazy { AiSignInApi.sha256Hex(AiSignInApi.der(AiSignInApi.V2_PUBLIC)) }
        val FOREIGN_KEY_ID = AiSignInApi.sha256Hex("synthetic-foreign-key".toByteArray())
        val FP_A = AiSignInApi.sha256Hex("synthetic-address-a".toByteArray())
        val FP_B = AiSignInApi.sha256Hex("synthetic-address-b".toByteArray())
        val FP_OTHER = AiSignInApi.sha256Hex("synthetic-address-of-another-wallet".toByteArray())
        val CONDITIONAL =
            listOf(
                Regex("""\bif\s*\("""),
                Regex("""\belse\b"""),
                Regex("""\bwhen\b"""),
                Regex("""\?\.\s*(let|also|run|apply)\b"""),
                Regex("""\bAnimatedVisibility\b"""),
            )
    }
}
