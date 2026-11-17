package co.electriccoin.zcash.ui.screen.ai

import android.util.Log
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatStoreStart
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhone
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhoneContext
import co.electriccoin.zcash.ui.screen.chat.datasource.Keysets
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.security.KeyStoreException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * AI-KEY-S (owner W-52, W-53; AI-SIGNIN-KEY-SPEC revision 2a, sections 3, 4, 5.2, 6.3, 8, 9; must points 2, 5, 8, 9, 12):
 * the one place that decides about the AI access key.
 *
 * WHY THIS FAILS TODAY: the class `AiAccountAccess` does not exist; the tab and the chat shortcut still send the
 * wallet's fingerprint to the old sign-up.
 *
 * THE CONTRACT, reached by name so that this class compiles before the fix. Only types that exist today (spec 4.1).
 *
 *   class AiAccountAccess(
 *       prefs: AiPreferences,
 *       seed: suspend () -> ByteArray?,                     // a fresh array each time; AiAccountAccess wipes it
 *       candidates: suspend () -> List<suspend () -> String>, // spec 4.4 item 4: one entry per candidate fingerprint
 *                                                           // (64 hex); an entry that throws is skipped
 *       ownerTag: () -> String?,                            // chatOwnerOf
 *       scopeCounter: () -> Long,                           // ZchatPreferences.walletScopeGeneration
 *       walletSaved: suspend () -> Boolean,                 // the phrase store answers with a wallet
 *       clock: () -> Long,                                  // Unix milliseconds
 *       send: suspend (String, String, String?, String?) -> Pair<Int, String>, // method, path, access key, JSON body;
 *                                                           // gives status and body; a transport failure throws
 *   )
 *
 * Every function below is suspending and gives Pair(access key or null, sentence or null). A sentence is the English
 * text of one key of spec 7.1, as AiApiClient holds it (AiSignInErrorsHaveTextsTest ties the two together):
 *
 *   open()               the AI tab and the /ai shortcut open: no saved key, get one (4.5); no verdict, decide (4.4)
 *   keyForCharge()       the key for a chat or a picture: nothing is handed out before key/state answered (4.2, M9)
 *   keyForUse()          the key for every other request; no network
 *   onForeground()       ZChat came to the foreground (4.3): the start rule
 *   onBalance(signInKeyId: String?, paid: Boolean?)   what /ai/balance said (4.3, change 5)
 *   onRefused(accessKey: String)   the server answered 401 to this key (4.5); the caller repeats its call with the answer
 *   linkAgain()          the tap on "Link again" (change 9)
 *
 *   AiAccountAccess.Companion.seedReader(PersistableWalletProvider): suspend () -> ByteArray?   the production seed read
 *
 * THE STORE (spec 4.2), the real AiPreferences over the in-memory phone of ZC-733 and ZC-718:
 *   ai_link_state   JSON {"accountId", "verdict": "linked"|"own" (absent: none yet), "paid": "yes"|"no"|"unknown", "day"}
 *   ai_parked_keys  JSON [{"token", "userId"}, ...]
 *
 * THE SERVER is a model of spec section 3 inside this test: it issues one-time numbers, REBUILDS each nine-line message
 * from its own values (for register from ITS stored fingerprint) and checks the signature with E2EEncryption.verify.
 *
 * EVERY CASE ends in [everyCaseLeavesNoTrace]: cases (c) and (q) of the batch. Every key, account and phrase is synthetic.
 */
class AiAccountAccessTest {
    private val data: File = Files.createTempDirectory("zchat-aikeys").toFile().apply { deleteOnExit() }
    private val files = KeysetPhone(File(data, "shared_prefs"))
    private val context = KeysetPhoneContext(files, dataDir = data)
    private var keystoreOut = false
    private var now = START_MILLIS
    private val server = ModelServer { now }

    private var tag: String? = WALLET_TAG
    private var counter = 7L
    private var walletSaved = true
    private var seedGives: () -> ByteArray? = { SEED.copyOf() }
    private val handedSeeds = CopyOnWriteArrayList<ByteArray>()
    private var candidates: List<suspend () -> String> = emptyList()

    private lateinit var ai: AiPreferences

    @Before
    fun newProcess() {
        StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())
        ChatStoreStart.userVisible = false
        val open = { name: String ->
            if (keystoreOut) throw KeyStoreException("synthetic: no answer")
            files.Library(name).open()
        }
        StoreOpenerApi.useCalls(
            StoreOpenerApi.aiHolder(context),
            StoreOpenerApi.FileCalls(createWithMasterKey = { name, _ -> open(name) }, createByKeyName = { name, _ -> open(name) }),
        )
        ai = AiPreferences(context)
        Log.startCapture()
    }

    @After
    fun everyCaseLeavesNoTrace() {
        val logged = Log.captured().joinToString("\n")
        Log.stopCapture()
        val entries = entries()
        StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())

        // (c) No request of any case carries a fingerprint or anything made from an address; no old sign-up.
        server.requests.forEach { request ->
            assertNotEquals("the old sign-up was called", OLD_SIGN_UP, request.path)
            val body = request.body.orEmpty()
            assertFalse("a request carries walletPubkey", "walletPubkey" in body)
            listOf(FP_A, FP_B, FP_OTHER).forEach { assertFalse("a request carries a fingerprint", it in body.lowercase()) }
        }

        // (q) Nothing beyond the four entries of spec 4.2 and those that exist today.
        val unknown = entries.keys - EXISTING_ENTRIES - NEW_ENTRIES
        assertEquals("entries nobody named are in the AI store", emptySet<String>(), unknown)
        val privateHalf = AiSignInApi.derive(SEED).privateKey
        val neverStored = server.nonces + server.signatures + privateHalf + Keysets.hex(SEED) + Base64.getEncoder().encodeToString(SEED)
        val publicHalf = AiSignInApi.derive(SEED).publicKey
        entries.forEach { (name, value) ->
            (neverStored + publicHalf + AiSignInApi.keyId(publicHalf)).forEach {
                assertFalse("$name holds a signature, a one-time number, the seed or a key", it in value.toString())
            }
        }
        val accessKeys = server.issuedTokens + server.issuedIds
        entries.filterKeys { it !in setOf("ai_token", "ai_user_id", "ai_parked_keys", "ai_link_state") }.forEach { (name, value) ->
            accessKeys.forEach { assertFalse("$name holds an access key or an account id", it in value.toString()) }
        }
        (entries["ai_link_state"]?.toString().orEmpty()).let { state ->
            server.issuedTokens.forEach { assertFalse("ai_link_state holds an access key", it in state) }
        }
        (neverStored + accessKeys).forEach { assertFalse("a log line holds a secret or an id", it in logged) }
    }

    // ----------------------------------------------------------------------------------- (a), (b): sign-in by key

    @Test
    fun `a - no saved key, unknown key - one question, one sign-in with create, the pair is saved as linked`() =
        run {
            val answer = access().call("open")

            assertEquals(listOf(CHALLENGE, SIGN_IN), server.paths())
            assertTrue("no create 1", server.last(SIGN_IN).json().getBoolean("create"))
            assertNotNull(answer.key)
            assertEquals(server.accounts.single().token, ai.getToken())
            assertEquals(server.accounts.single().id, ai.getUserId())
            assertEquals(answer.key, ai.getToken())
            assertEquals(LINKED, verdict())
        }

    @Test
    fun `a - the wallet's key is sent, made from the seed, and the server's expiry is copied digit for digit`() =
        run {
            access().call("open")

            assertEquals(AiSignInApi.V2_PUBLIC, server.last(SIGN_IN).json().getString("publicKey"))
            assertEquals("the signature did not verify under the server's own nine lines", 1, server.verifiedSignatures)
        }

    @Test
    fun `b - the same seed after the store was emptied gets the same account`() =
        run {
            access().call("open")
            val first = ai.getUserId()

            ai.clear()
            access().call("open")

            assertEquals(first, ai.getUserId())
            assertEquals(1, server.accounts.size)
            assertEquals(LINKED, verdict())
        }

    // ---------------------------------------------------------------------------------------- (d), (e): old server

    @Test
    fun `d - a server without the key calls and a saved key - used as today, nothing changes`() =
        run {
            server.oldServer = true
            ai.saveCredentials(TOKEN_SAVED, USER_SAVED)
            val before = entries()

            val answer = access().call("open")

            assertEquals(TOKEN_SAVED, answer.key)
            assertNull(answer.sentence)
            assertEquals(before, entries())
            assertEquals(listOf(KEY_STATE), server.paths())
        }

    @Test
    fun `e - such a server and no saved key - cannot sign this wallet in yet after one call`() =
        run {
            server.oldServer = true

            val answer = access().call("open")

            assertNull(answer.key)
            assertEquals(aiSignInText("ai_signin_server_not_ready"), answer.sentence)
            assertEquals(listOf(CHALLENGE), server.paths())
            assertNull(ai.getToken())
        }

    // ------------------------------------------------------------------------------------------ (f): the comparison

    @Test
    fun `f - a digest that fits one of two wallet accounts registers with that fingerprint, signed and never sent`() =
        run {
            val account = server.account(fingerprint = FP_B)
            ai.saveCredentials(account.token, account.id)
            candidates = listOf(suspend { FP_A }, suspend { throw IOException("synthetic: no address yet") }, suspend { FP_B })

            val answer = access().call("open")

            assertEquals(account.token, answer.key)
            assertEquals(listOf(KEY_STATE, CHALLENGE, REGISTER), server.paths())
            val register = server.last(REGISTER).json()
            assertEquals("fingerprint", register.getString("walletProof"))
            assertEquals(setOf("challengeId", "publicKey", "signature", "walletProof"), register.keys().asSequence().toSet())
            assertEquals("the signed message does not carry that fingerprint", listOf(FP_B), server.registeredWalletLines)
            assertEquals(1, server.verifiedSignatures)
            assertEquals(LINKED, verdict())
            assertEquals(account.id, linkState()?.getString("accountId"))
        }

    /** REVIEW_2_20261013: the server lower-cases fingerprints and AiSignInProof refuses anything else. */
    @Test
    fun `f - a candidate in upper case is lower-cased before the comparison and before signing`() =
        run {
            val account = server.account(fingerprint = FP_A)
            ai.saveCredentials(account.token, account.id)
            candidates = listOf(suspend { FP_A.uppercase() })

            access().call("open")

            assertEquals(listOf(FP_A), server.registeredWalletLines)
            assertEquals(LINKED, verdict())
        }

    @Test
    fun `f - an account signed up without a fingerprint registers with the proof none`() =
        run {
            val account = server.account(fingerprint = null)
            ai.saveCredentials(account.token, account.id)

            access().call("open")

            assertEquals("none", server.last(REGISTER).json().getString("walletProof"))
            assertEquals(listOf("-"), server.registeredWalletLines)
            assertEquals(LINKED, verdict())
        }

    @Test
    fun `f - key state naming this wallet's key is linked with no comparison and no register`() =
        run {
            val account = server.account(fingerprint = FP_A, keyId = OWN_KEY_ID)
            ai.saveCredentials(account.token, account.id)

            access().call("open")

            assertEquals(listOf(KEY_STATE), server.paths())
            assertEquals(LINKED, verdict())
        }

    // --------------------------------------------------------------------------------- (g): one comparison per account

    @Test
    fun `g - a second digest for the same account id compares nothing and sends nothing, also in a new process`() =
        run {
            val account = server.account(fingerprint = FP_A)
            ai.saveCredentials(account.token, account.id)
            candidates = listOf(suspend { FP_A }, suspend { FP_B })
            server.answerOnce(REGISTER, 500, """{"error":"internal"}""")
            access().call("open")
            assertEquals("the first answer gives no verdict", null, verdict())
            assertEquals(1, server.count(REGISTER))

            // A dishonest server tests a second guess: the digest of another address of this wallet.
            server.digestOf = FP_B
            access().call("open")

            assertEquals("a second comparison was made", 1, server.count(REGISTER))
            assertEquals(account.token, ai.getToken())
            assertNull(verdict())
        }

    // ------------------------------------------------------------- (h): no comparison once linked; change 9

    @Test
    fun `h - an account opened by key sign-in is never compared, whatever key state says`() =
        run {
            val access = access()
            access.call("open")
            server.requests.clear()
            server.accounts.single().keyId = null
            server.accounts.single().fingerprint = FP_A
            candidates = listOf(suspend { FP_A })

            access.call("open")
            access.call("keyForCharge")
            access.call("keyForUse")

            assertEquals("a linked account asked the server again", emptyList<String>(), server.paths())
        }

    @Test
    fun `h - a once-linked account that shows no key - the verdict goes, sign-in with create 0, no comparison`() =
        run {
            val access = access()
            access.call("open")
            val own = server.accounts.single()
            server.requests.clear()
            candidates = listOf(suspend { FP_A })

            val answer = access.call("onBalance", null, false)

            assertEquals(listOf(CHALLENGE, SIGN_IN), server.paths())
            assertFalse("create must be 0 here", server.last(SIGN_IN).json().getBoolean("create"))
            assertEquals(own.token, answer.key)
            assertEquals(own.token, ai.getToken())
            assertEquals(LINKED, verdict())
        }

    @Test
    fun `h - another key's id on the balance - not ours, and this wallet signs in with create`() =
        run {
            val access = access()
            access.call("open")
            server.requests.clear()

            access.call("onBalance", FOREIGN_KEY_ID, false)

            assertEquals(listOf(CHALLENGE, SIGN_IN), server.paths())
            assertTrue(server.last(SIGN_IN).json().getBoolean("create"))
        }

    @Test
    fun `h - the account returned becomes the saved pair by the rule - a paid pair of another account is parked`() =
        run {
            val access = access()
            access.call("open")
            val old = server.accounts.single()
            val other = server.account(fingerprint = null, keyId = OWN_KEY_ID)
            old.keyId = null

            access.call("onBalance", null, true)

            assertEquals(other.token, ai.getToken())
            assertEquals(other.id, ai.getUserId())
            assertEquals("a paid pair was overwritten", listOf(old.token), parked())
        }

    @Test
    fun `h - unknown key leaves the saved key in use as own, says link lost, and compares only on the tap, once`() =
        run {
            val access = access()
            access.call("open")
            val own = server.accounts.single()
            own.keyId = null
            own.fingerprint = FP_A
            candidates = listOf(suspend { FP_A })
            server.requests.clear()

            val lost = access.call("onBalance", null, false)

            assertEquals(own.token, lost.key)
            assertEquals(aiSignInText("ai_notice_link_lost"), lost.sentence)
            assertEquals(OWN, verdict())
            assertEquals(own.token, ai.getToken())
            assertEquals(listOf(CHALLENGE, SIGN_IN), server.paths())

            server.requests.clear()
            access.call("open")
            access.call("keyForCharge")
            access.call("onForeground")
            assertEquals("a comparison ran without the tap", emptyList<String>(), server.paths())

            access.call("linkAgain")

            assertEquals(listOf(KEY_STATE, CHALLENGE, REGISTER), server.paths())
            assertEquals(listOf(FP_A), server.registeredWalletLines)
            assertEquals(LINKED, verdict())
        }

    // ------------------------------------------------------------------------ (i): no charged request before a verdict

    @Test
    fun `i - with a saved key and no verdict the charged key waits for key state`() =
        run {
            val account = server.account(fingerprint = null, keyId = OWN_KEY_ID)
            ai.saveCredentials(account.token, account.id)
            val gate = server.hold(KEY_STATE)

            val charged = background { access().call("keyForCharge") }
            gate.arrived.await()
            delay(SETTLE_MILLIS)
            assertFalse("the key was handed out before key/state answered", charged.isCompleted)

            gate.release.complete(Unit)

            assertEquals(account.token, charged.await().key)
            assertEquals(LINKED, verdict())
        }

    @Test
    fun `i - on 404 the key is handed out as today, with no verdict`() =
        run {
            server.oldServer = true
            ai.saveCredentials(TOKEN_SAVED, USER_SAVED)

            assertEquals(TOKEN_SAVED, access().call("keyForCharge").key)
            assertNull(verdict())
        }

    @Test
    fun `i - on a transport failure no key is handed out, with no verdict`() =
        run {
            server.transportDown = true
            ai.saveCredentials(TOKEN_SAVED, USER_SAVED)

            assertNull(access().call("keyForCharge").key)
            assertEquals(TOKEN_SAVED, ai.getToken())
            assertEquals(1, server.requests.size)
            assertNull(verdict())
        }

    @Test
    fun `i - with a verdict the charged key makes no call`() =
        run {
            val access = access()
            access.call("open")
            server.requests.clear()

            assertEquals(ai.getToken(), access.call("keyForCharge").key)
            assertEquals(emptyList<String>(), server.paths())
        }

    @Test
    fun `i - a mismatch never hands the foreign key to a charged request`() =
        run {
            val foreign = server.account(fingerprint = FP_OTHER, paid = true)
            ai.saveCredentials(foreign.token, foreign.id)
            candidates = listOf(suspend { FP_A })

            val answer = access().call("keyForCharge")

            assertNotEquals(foreign.token, answer.key)
            assertEquals(ai.getToken(), answer.key)
        }

    // ----------------------------------------------------------------------------------- (j), (k), (l): a mismatch

    @Test
    fun `j - no candidate fits, not paid - the pair is dropped with the sentence, the wallet signs in with its own key`() =
        run {
            val foreign = server.account(fingerprint = FP_OTHER, paid = false)
            ai.saveCredentials(foreign.token, foreign.id)
            candidates = listOf(suspend { FP_A })

            val answer = access().call("open")

            assertEquals(aiSignInText("ai_notice_other_wallet_dropped"), answer.sentence)
            assertTrue(server.last(SIGN_IN).json().getBoolean("create"))
            assertNotEquals(foreign.token, ai.getToken())
            assertEquals(answer.key, ai.getToken())
            assertEquals(emptyList<String>(), parked())
            assertEquals(0, server.count(REGISTER))
            assertEquals(LINKED, verdict())
        }

    @Test
    fun `j - key state names another key, not paid - the same`() =
        run {
            val foreign = server.account(fingerprint = null, keyId = FOREIGN_KEY_ID, paid = false)
            ai.saveCredentials(foreign.token, foreign.id)

            val answer = access().call("open")

            assertEquals(aiSignInText("ai_notice_other_wallet_dropped"), answer.sentence)
            assertNotEquals(foreign.token, ai.getToken())
            assertEquals(emptyList<String>(), parked())
        }

    @Test
    fun `k - a mismatch, paid - the pair is parked, and no request of any kind carries it afterwards`() =
        run {
            val foreign = server.account(fingerprint = FP_OTHER, paid = true)
            ai.saveCredentials(foreign.token, foreign.id)
            candidates = listOf(suspend { FP_A })
            val access = access()

            val answer = access.call("open")

            assertEquals(aiSignInText("ai_notice_key_parked"), answer.sentence)
            assertEquals(listOf(foreign.token), parked())
            assertEquals(listOf(foreign.id), parkedIds())
            assertNotEquals(foreign.token, ai.getToken())
            assertEquals(answer.key, ai.getToken())
            assertEquals(LINKED, verdict())

            server.requests.clear()
            repeat(TEN_MORE) {
                now += ONE_DAY_MILLIS
                access.call("open")
                access.call("keyForCharge")
                access.call("keyForUse")
                access.call("onForeground")
                access.call("onBalance", OWN_KEY_ID, false)
                access.call("onBalance", null, false)
                access.call("linkAgain")
                access.call("onRefused", ai.getToken()!!)
            }

            val carried = server.requests.filter { it.accessKey == foreign.token || foreign.token in it.body.orEmpty() }
            assertEquals("a parked key was used", 0, carried.size)
            assertEquals(listOf(foreign.token), parked())
        }

    @Test
    fun `k - key state names another key, paid - parked`() =
        run {
            val foreign = server.account(fingerprint = null, keyId = FOREIGN_KEY_ID, paid = true)
            ai.saveCredentials(foreign.token, foreign.id)

            access().call("open")

            assertEquals(listOf(foreign.token), parked())
        }

    @Test
    fun `l - a second and a third paid mismatch are appended, the saved slot holds the wallet's own account each time`() =
        run {
            val access = access()
            candidates = listOf(suspend { FP_A })
            val tokens = mutableListOf<String>()
            repeat(3) {
                val foreign = server.account(fingerprint = FP_OTHER, paid = true)
                tokens += foreign.token
                ai.saveCredentials(foreign.token, foreign.id)

                access.call("open")

                assertEquals("a parked key was dropped", tokens, parked())
                assertEquals("the saved slot is not the wallet's own", server.accountOfKey(OWN_KEY_ID)?.id, ai.getUserId())
            }

            server.requests.clear()
            now += ONE_DAY_MILLIS
            access.call("onForeground")

            assertEquals("a start-time call for a parked key", emptyList<String>(), server.paths())
        }

    // --------------------------------------------------------------------------------------- (m): a refused key

    @Test
    fun `m - a refused key - one sign-in, and the answer is the new key of the same account`() =
        run {
            val access = access()
            access.call("open")
            val refused = ai.getToken()!!
            val id = ai.getUserId()
            server.requests.clear()
            server.accounts.single().let { it.token = "$refused-newer" }

            val answer = access.call("onRefused", refused)

            assertEquals(listOf(CHALLENGE, SIGN_IN), server.paths())
            assertEquals(ai.getToken(), answer.key)
            assertNotEquals(refused, ai.getToken())
            assertEquals(id, ai.getUserId())
            assertNull(answer.sentence)
        }

    @Test
    fun `m - a failed sign-in leaves the old pair`() =
        run {
            val access = access()
            access.call("open")
            val refused = ai.getToken()!!
            val id = ai.getUserId()
            server.answerOnce(SIGN_IN, 500, """{"error":"internal"}""")

            access.call("onRefused", refused)

            assertEquals(refused, ai.getToken())
            assertEquals(id, ai.getUserId())
        }

    @Test
    fun `m - a sign-in that answers created after a refusal says a new account was opened`() =
        run {
            val foreign = server.account(fingerprint = null)
            ai.saveCredentials(foreign.token, foreign.id)
            foreign.token = "synthetic-no-longer-valid"

            val answer = access().call("onRefused", ai.getToken()!!)

            assertEquals(aiSignInText("ai_notice_new_account_after_refusal"), answer.sentence)
            assertEquals(answer.key, ai.getToken())
        }

    @Test
    fun `m - a second refusal within a minute makes no call and says another phone, after the minute it signs in`() =
        run {
            val access = access()
            access.call("open")
            access.call("onRefused", ai.getToken()!!)
            server.requests.clear()

            now += JUST_UNDER_A_MINUTE
            val second = access.call("onRefused", ai.getToken()!!)

            assertEquals(emptyList<String>(), server.paths())
            assertEquals(aiSignInText("ai_signin_elsewhere"), second.sentence)

            now += TWO_SECONDS
            access.call("onRefused", ai.getToken()!!)

            assertEquals(listOf(CHALLENGE, SIGN_IN), server.paths())
        }

    @Test
    fun `m - key state answering 401 is a refusal, not a mismatch`() =
        run {
            val own = server.account(fingerprint = null, keyId = OWN_KEY_ID)
            ai.saveCredentials("synthetic-refused-token", own.id)

            val answer = access().call("open")

            assertEquals(own.token, answer.key)
            assertEquals(own.token, ai.getToken())
            assertEquals(emptyList<String>(), parked())
        }

    // -------------------------------------------------------------------------------------- (n): the start rule

    @Test
    fun `n - the foreground makes no call when the saved key has a verdict`() =
        run {
            val access = access()
            access.call("open")
            server.requests.clear()

            now += ONE_DAY_MILLIS
            access.call("onForeground")

            assertEquals(emptyList<String>(), server.paths())
        }

    @Test
    fun `n - the foreground makes no call without a saved key`() =
        run {
            access().call("onForeground")

            assertEquals(emptyList<String>(), server.paths())
            assertNull(ai.getToken())
        }

    @Test
    fun `n - one call when there is no verdict, none again that day also in a new process, one the next day`() =
        run {
            server.oldServer = true
            ai.saveCredentials(TOKEN_SAVED, USER_SAVED)

            access().call("onForeground")
            assertEquals(listOf(KEY_STATE), server.paths())
            assertTrue("the day is not stored", linkState()?.has("day") == true)

            access().call("onForeground")
            assertEquals("twice on one day", listOf(KEY_STATE), server.paths())

            now += ONE_DAY_MILLIS
            access().call("onForeground")
            assertEquals(listOf(KEY_STATE, KEY_STATE), server.paths())
        }

    // ---------------------------------------------------------------------------------------- (o): the fence (M12)

    @Test
    fun `o - the pair after a sign-in is not written across a claim for another wallet`() = fencedSignIn(::claim)

    @Test
    fun `o - the pair after a sign-in is not written across a wipe`() = fencedSignIn(::wipe)

    @Test
    fun `o - the pair after a sign-in is not written across a removed owner record`() = fencedSignIn(::ownerRemoved)

    @Test
    fun `o - the verdict after register is not written across a claim for another wallet`() = fencedVerdict(::claim)

    @Test
    fun `o - the verdict after register is not written across a wipe`() = fencedVerdict(::wipe)

    @Test
    fun `o - the verdict after register is not written across a removed owner record`() = fencedVerdict(::ownerRemoved)

    @Test
    fun `o - the parking is not written across a claim for another wallet`() = fencedParking(::claim)

    @Test
    fun `o - the parking is not written across a wipe`() = fencedParking(::wipe)

    @Test
    fun `o - the parking is not written across a removed owner record`() = fencedParking(::ownerRemoved)

    @Test
    fun `o - an operation that starts after clearAll while the owner reader answers the old tag makes no call`() =
        run {
            ai.saveCredentials(TOKEN_SAVED, USER_SAVED)
            walletSaved = false
            val before = entries()
            val access = access()

            listOf<Pair<String, Array<Any?>>>(
                "open" to emptyArray(),
                "keyForCharge" to emptyArray(),
                "onForeground" to emptyArray(),
                "onBalance" to arrayOf(null, false),
                "onRefused" to arrayOf(TOKEN_SAVED),
                "linkAgain" to emptyArray(),
            ).forEach { (name, args) -> access.call(name, *args) }

            assertEquals("a call without a saved wallet", emptyList<String>(), server.paths())
            assertEquals("a write without a saved wallet", before, entries())
        }

    @Test
    fun `o - the saved wallet is removed between the answer and the write - nothing is written`() =
        fencedSignIn { walletSaved = false }

    private fun fencedSignIn(disturb: () -> Unit) =
        fenced(SIGN_IN, disturb) { }

    private fun fencedVerdict(disturb: () -> Unit) =
        fenced(REGISTER, disturb) {
            val account = server.account(fingerprint = FP_A)
            ai.saveCredentials(account.token, account.id)
            candidates = listOf(suspend { FP_A })
        }

    private fun fencedParking(disturb: () -> Unit) =
        fenced(KEY_STATE, disturb) {
            val foreign = server.account(fingerprint = FP_OTHER, paid = true)
            ai.saveCredentials(foreign.token, foreign.id)
            candidates = listOf(suspend { FP_A })
        }

    private fun fenced(
        heldPath: String,
        disturb: () -> Unit,
        arrange: () -> Unit,
    ) = run {
        arrange()
        val gate = server.hold(heldPath)
        val operation = background { access().call("open") }
        gate.arrived.await()
        disturb()
        val before = entries()

        gate.release.complete(Unit)
        operation.await()

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

    // ------------------------------------------------------------------------------------------- (p): the small ones

    @Test
    fun `p - two callers at once - one sign-in`() =
        run {
            val gate = server.hold(CHALLENGE)
            val access = access()
            val first = background { access.call("open") }
            val second = background { access.call("open") }
            gate.arrived.await()
            delay(SETTLE_MILLIS)

            gate.release.complete(Unit)

            assertEquals(first.await().key, second.await().key)
            assertEquals(1, server.count(SIGN_IN))
        }

    @Test
    fun `p - an expired one-time number - exactly one more try`() =
        run {
            server.answerOnce(SIGN_IN, 400, """{"error":"challenge_expired"}""")

            access().call("open")

            assertEquals(listOf(CHALLENGE, SIGN_IN, CHALLENGE, SIGN_IN), server.paths())
            assertNotNull(ai.getToken())
        }

    @Test
    fun `p - a one-time number that stays expired - no loop`() =
        run {
            server.answerAlways(SIGN_IN, 400, """{"error":"challenge_expired"}""")

            val answer = access().call("open")

            assertEquals(2, server.count(SIGN_IN))
            assertNull(answer.key)
        }

    @Test
    fun `p - 429 on the question - no further call, the too-many sentence`() =
        run {
            server.answerAlways(CHALLENGE, 429, """{"error":"rate_limited"}""")

            val answer = access().call("open")

            assertEquals(listOf(CHALLENGE), server.paths())
            assertEquals(aiSignInText("ai_signin_too_many"), answer.sentence)
        }

    @Test
    fun `p - 429 signup_limit on the sign-in - no further call, the new-account sentence`() =
        run {
            server.answerAlways(SIGN_IN, 429, """{"error":"signup_limit"}""")

            val answer = access().call("open")

            assertEquals(listOf(CHALLENGE, SIGN_IN), server.paths())
            assertEquals(aiSignInText("ai_signup_limit"), answer.sentence)
        }

    @Test
    fun `p - a signature the server refuses - no further call, the refused sentence`() =
        run {
            server.answerAlways(SIGN_IN, 401, """{"error":"bad_signature"}""")

            val answer = access().call("open")

            assertEquals(listOf(CHALLENGE, SIGN_IN), server.paths())
            assertEquals(aiSignInText("ai_signin_refused"), answer.sentence)
            assertNull(ai.getToken())
        }

    @Test
    fun `p - no seed - no call, still opening your wallet`() =
        run {
            seedGives = { null }

            val answer = access().call("open")

            assertEquals(emptyList<String>(), server.paths())
            assertEquals(aiSignInText("ai_signin_wallet_not_ready"), answer.sentence)
        }

    @Test
    fun `p - the store cannot be opened - no call`() =
        run {
            files.put(AI_STORE, Keysets.wrappedEntries() + mapOf("ai_token" to TOKEN_SAVED))
            keystoreOut = true
            val access = access()

            listOf("open", "keyForCharge", "onForeground", "linkAgain").forEach { name ->
                runCatching { access.call(name) }
            }

            assertEquals("a call while the store cannot be opened", emptyList<String>(), server.paths())
        }

    // ------------------------------------------------------- the seed: 64 bytes, never the entropy (REVIEW_2 finding 7)

    @Test
    fun `seed - anything but the 64-byte seed makes no key and no call, and the array is wiped`() =
        run {
            val entropy = ByteArray(ENTROPY_BYTES) { (it + 1).toByte() }
            seedGives = { entropy }

            val answer = access().call("open")

            assertEquals("a key was made from the entropy", emptyList<String>(), server.paths())
            assertEquals(aiSignInText("ai_signin_wallet_not_ready"), answer.sentence)
            assertArrayEquals(ByteArray(ENTROPY_BYTES), entropy)
        }

    @Test
    fun `seed - the production reader gives the 64-byte BIP-39 seed of the saved phrase, never its entropy`() =
        run {
            val words = List(SeedPhrase.SEED_PHRASE_SIZE - 1) { "aban" + "don" } + "art"

            val seed = productionSeedReader(ProviderOf(words)).invoke()

            assertNotNull("no seed", seed)
            assertEquals(SEED_BYTES, seed!!.size)
            assertArrayEquals("not BIP-39's PBKDF2 of the phrase", bip39Seed(words), seed)
            assertFalse("the entropy of this phrase is all zero", seed.all { it == 0.toByte() })
        }

    @Test
    fun `seed - the production reader gives null when no wallet is saved`() =
        run {
            assertNull(productionSeedReader(ProviderOf(null)).invoke())
        }

    @Test
    fun `seed - every array read is wiped after use and the private half is in no field`() =
        run {
            val access = access()
            access.call("open")
            access.call("onRefused", ai.getToken()!!)

            assertTrue("no seed was read", handedSeeds.isNotEmpty())
            handedSeeds.forEach { assertArrayEquals("a seed array was not wiped", ByteArray(SEED_BYTES), it) }
            val privateHalf = AiSignInApi.derive(SEED).privateKey
            access.target.javaClass.declaredFields.forEach { field ->
                field.isAccessible = true
                val value = field.get(access.target)
                assertNotEquals("a field holds the private half", privateHalf, value)
                assertFalse("a field holds a key pair", value is AiSignInKeyPair)
            }
        }

    // ---------------------------------------------------------------------------------------------- the harness

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
        val seed: suspend () -> ByteArray? = { seedGives()?.also { handedSeeds += it } }
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

    @Suppress("UNCHECKED_CAST")
    private fun productionSeedReader(provider: PersistableWalletProvider): suspend () -> ByteArray? {
        val type =
            try {
                Class.forName(ACCESS_CLASS)
            } catch (e: ClassNotFoundException) {
                throw AssertionError("AiAccountAccess does not exist (AI-KEY-S)", e)
            }
        val companion = type.getField("Companion").get(null)
        val reader =
            companion.javaClass.methods.firstOrNull { it.name == "seedReader" && it.parameterCount == 1 }
                ?: throw AssertionError("AiAccountAccess.Companion has no seedReader(PersistableWalletProvider)")
        return reader.invoke(companion, provider) as suspend () -> ByteArray?
    }

    private fun bip39Seed(words: List<String>): ByteArray =
        SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(
                PBEKeySpec(
                    words.joinToString(" ").toCharArray(),
                    "mnemonic".toByteArray(Charsets.UTF_8),
                    BIP39_ROUNDS,
                    SEED_BYTES * Byte.SIZE_BITS,
                ),
            ).encoded

    private class ProviderOf(
        words: List<String>?,
    ) : PersistableWalletProvider {
        private val wallet: PersistableWallet? =
            words?.let {
                PersistableWallet(
                    network = ZcashNetwork.Mainnet,
                    endpoint = LightWalletEndpoint(host = "localhost", port = 443, isSecure = true),
                    birthday = BlockHeight.new(BIRTHDAY),
                    seedPhrase = SeedPhrase(it),
                    walletInitMode = WalletInitMode.NewWallet,
                )
            }
        override val persistableWallet: Flow<PersistableWallet?> = flowOf(wallet)

        override suspend fun store(persistableWallet: PersistableWallet) = Unit

        override suspend fun getPersistableWallet(): PersistableWallet? = wallet

        override suspend fun requirePersistableWallet(): PersistableWallet = checkNotNull(wallet)
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
        val method: String,
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
        val paid: Boolean,
    )

    /** Spec section 3, as far as this batch meets it. The batch AI-KEY-P's answers are not modelled. */
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
        var verifiedSignatures = 0
        var oldServer = false
        var transportDown = false

        /** Another fingerprint than the account's own goes into the salted digest (a dishonest server). */
        var digestOf: String? = null

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
            method: String,
            path: String,
            accessKey: String?,
            body: String?,
        ): Pair<Int, String> {
            requests += Request(method, path, accessKey, body)
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
            if (oldServer) return 404 to """{"error":"not found"}"""
            return when (path) {
                CHALLENGE -> challenge()
                KEY_STATE -> state(accessKey)
                REGISTER -> register(accessKey, JSONObject(body.orEmpty()))
                SIGN_IN -> signIn(JSONObject(body.orEmpty()))
                else -> 200 to "{}"
            }
        }

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
            val account = accounts.firstOrNull { it.token == accessKey } ?: return UNAUTHORIZED
            val answer =
                JSONObject()
                    .put("signInKeyId", account.keyId ?: JSONObject.NULL)
                    .put("paid", account.paid)
            val stored = account.fingerprint
            if (account.keyId == null && stored != null) {
                val salt = ByteArray(SALT_BYTES) { it.toByte() }
                val check = AiSignInApi.sha256Hex(salt + (digestOf ?: stored).toByteArray(Charsets.US_ASCII))
                answer.put(
                    "walletCheck",
                    JSONObject()
                        .put("salt", Base64.getUrlEncoder().withoutPadding().encodeToString(salt))
                        .put("check", check),
                )
            }
            return 200 to answer.toString()
        }

        private fun register(
            accessKey: String?,
            body: JSONObject,
        ): Pair<Int, String> {
            val account = accounts.firstOrNull { it.token == accessKey } ?: return UNAUTHORIZED
            val publicKey = body.getString("publicKey")
            val keyId = AiSignInApi.keyId(publicKey)
            if (account.keyId == keyId) return 200 to """{"registered":true}"""
            if (account.keyId != null) return 409 to """{"error":"account_has_other_key"}"""
            val wallet = account.fingerprint ?: "-"
            val (nonce, expires) = burn(body) ?: return 400 to """{"error":"challenge_expired"}"""
            val signed = nineLines("register", nonce, expires, keyId, account.id, wallet, false)
            if (!verified(publicKey, signed, body.getString("signature"))) return BAD_SIGNATURE
            registeredWalletLines += wallet
            account.keyId = keyId
            return 200 to """{"registered":true}"""
        }

        private fun signIn(body: JSONObject): Pair<Int, String> {
            val publicKey = body.getString("publicKey")
            val keyId = AiSignInApi.keyId(publicKey)
            val create = body.getBoolean("create")
            val (nonce, expires) = burn(body) ?: return 400 to """{"error":"challenge_expired"}"""
            val signed = nineLines("signin", nonce, expires, keyId, "-", "-", create)
            if (!verified(publicKey, signed, body.getString("signature"))) return BAD_SIGNATURE
            val found = accountOfKey(keyId)
            if (found == null && !create) return 409 to """{"error":"unknown_key"}"""
            val account = found ?: account(fingerprint = null, keyId = keyId)
            counter++
            account.token = "synthetic-token-$counter"
            issuedTokens += account.token
            return 200 to
                JSONObject()
                    .put("userId", account.id)
                    .put("token", account.token)
                    .put("created", found == null)
                    .toString()
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
        const val LINKED = "linked"
        const val OWN = "own"
        const val TOKEN_SAVED = "synthetic-saved-token"
        const val USER_SAVED = "acct_SYNTHETIC-saved"
        const val START_MILLIS = 1_791_800_000_000L
        const val ONE_DAY_MILLIS = 86_400_000L
        const val JUST_UNDER_A_MINUTE = 59_000L
        const val TWO_SECONDS = 2_000L
        const val MILLIS = 1_000L
        const val CHALLENGE_SECONDS = 120L
        const val NONCE_BYTES = 32
        const val SALT_BYTES = 16
        const val SETTLE_MILLIS = 100L
        const val CASE_TIMEOUT_MILLIS = 30_000L
        const val TEN_MORE = 10
        const val SEED_BYTES = 64
        const val ENTROPY_BYTES = 32
        const val BIP39_ROUNDS = 2048
        const val BIRTHDAY = 3_000_000L

        val UNAUTHORIZED = 401 to """{"error":"Invalid token"}"""
        val BAD_SIGNATURE = 401 to """{"error":"bad_signature"}"""
        val WALLET_TAG = "a".repeat(64)
        val SEED = AiSignInApi.bytes(SEED_BYTES)
        val OWN_KEY_ID: String by lazy { AiSignInApi.sha256Hex(AiSignInApi.der(AiSignInApi.V2_PUBLIC)) }
        val FOREIGN_KEY_ID = AiSignInApi.sha256Hex("synthetic-foreign-key".toByteArray())
        val FP_A = AiSignInApi.sha256Hex("synthetic-address-a".toByteArray())
        val FP_B = AiSignInApi.sha256Hex("synthetic-address-b".toByteArray())
        val FP_OTHER = AiSignInApi.sha256Hex("synthetic-address-of-another-wallet".toByteArray())
        val NEW_ENTRIES = setOf("ai_link_state", "ai_parked_keys")
        val EXISTING_ENTRIES =
            setOf(
                "ai_token",
                "ai_user_id",
                "ai_selected_model",
                "ai_selected_image_model",
                "ai_conversations",
                "ai_images",
                "ai_retention_days",
                "ai_image_aspect",
            )
    }
}
