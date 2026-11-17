package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.KEY_STATE
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.TOKEN
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.TOKEN_PARKED
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.USER
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.USER_PARKED
import co.electriccoin.zcash.ui.screen.ai.AiKeyScreensFixture.Companion.linkJson
import co.electriccoin.zcash.ui.screen.changeidentity.IdentityManagerImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.AddressCacheImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatClaim
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatWalletOwnership
import co.electriccoin.zcash.ui.screen.chat.datasource.ContactBookImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryBundles
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryOwnerRecord
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.LiveChatStoresImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.NO_CHAT_OWNER
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI-KEY-R (W-52, W-53; AI-SIGNIN-KEY-SPEC.md revision 2b, sections 5.3 and 6.5; must point 6) and ZC-1195.
 *
 * A Reset without "keep" deletes a saved access key only when its account's verdict is `linked` or its last paid answer
 * was no; otherwise exactly its two entries are kept in that wallet's set-aside file. The parked list is always kept.
 *
 * WHY THE RED CASES FAIL TODAY: AiPreferences.entriesKeptAtReset() still answers rule K-0 of ZC-718 (every saved key, and
 * no list). ZC-1195: moveStores drops the live key of nobody's store whenever the claiming wallet's bundle holds a key.
 * The guards ("paid or unknown: the pair", the claim that puts the entries back) pass before and after the fix.
 *
 * The real AiPreferences, AiResetKeep, ChatWalletOwnership and LiveChatStoresImpl; bundles and owner in memory.
 */
class AiKeyKeptAtResetTest {
    private val phone = AiKeyScreensFixture()
    private val ai = phone.ai
    private val bundles = InMemoryBundles()
    private val record = InMemoryOwnerRecord(WALLET_A)
    private val images = AiImageStore(phone.context)
    private val keep = AiResetKeep(ai, images, bundles)
    private val ownership by lazy {
        val preferences = realPreferences().first
        val book = ContactBookImpl(phone.context)
        val avatars = AvatarStore(phone.context)
        val calls =
            StoreOpenerApi.FileCalls(
                createWithMasterKey = { name, _ -> phone.files.Library(name).open() },
                createByKeyName = { name, _ -> phone.files.Library(name).open() },
            )
        StoreOpenerApi.useCalls(StoreOpenerApi.holderOf(book), calls)
        StoreOpenerApi.useCalls(StoreOpenerApi.holderOf(avatars), calls)
        val live =
            LiveChatStoresImpl(
                { preferences },
                { book },
                { avatars },
                { AddressCacheImpl(phone.context) },
                { IdentityManagerImpl(phone.context) },
                { ai.walletScopedStores() },
            )
        ChatWalletOwnership(
            record,
            bundles,
            live,
            images.movingFlatPictures(KeptChatStoreCopies(plainPrefs = phone.files::plain, fileNames = phone.files::listing)),
        )
    }

    @After
    fun endProcess() = phone.close()

    private val pair = mapOf("ai_token" to TOKEN, "ai_user_id" to USER)
    private val pairPaid = pair + (LINK to JSONObject().put("accountId", USER).put("paid", "yes").toString())

    private fun parkedList(): String =
        JSONArray()
            .put(JSONObject().put("token", TOKEN_PARKED).put("userId", USER_PARKED))
            .toString()

    private fun entries(): Map<String, Any?> = phone.files.entries(AI).filterKeys { !it.startsWith(LIBRARY_ENTRY) }

    // ------------------------------------------------------------------------------- what the function answers

    @Test
    fun `linked - nothing is kept, the phrase opens the account, paid or not`() {
        for (paid in listOf("yes", "no", "unknown")) {
            phone.store(verdict = LINKED, paid = paid)
            assertEquals("linked, paid $paid", emptyMap<String, Any?>(), ai.entriesKeptAtReset())
        }
    }

    @Test
    fun `not paid - nothing is kept, with no verdict and with the verdict own`() {
        for (verdict in listOf(null, OWN)) {
            phone.store(verdict = verdict, paid = "no")
            assertEquals("verdict $verdict, paid no", emptyMap<String, Any?>(), ai.entriesKeptAtReset())
        }
    }

    // GUARD, passes before and after the fix: the rule's safe side.
    @Test
    fun `paid or unknown without linked - exactly the pair`() {
        for ((verdict, paid) in listOf(null to "yes", null to "unknown", OWN to "yes", OWN to "unknown", null to null)) {
            phone.store(verdict = verdict, paid = paid)
            assertEquals("verdict $verdict, paid $paid", if (paid == "yes") pairPaid else pair, ai.entriesKeptAtReset())
        }
    }

    // GUARD: a verdict for another account says nothing about this key; nor does a state that cannot be read.
    @Test
    fun `a linked verdict of another account id, or a garbled state - the pair stays`() {
        for (state in listOf(linkJson("synthetic-other", LINKED, "no"), "not json")) {
            ai.saveCredentials(TOKEN, USER)
            ai.writeIfStillThisWallet({ true }) { putLinkStateJson(state) }
            assertEquals(state, pair, ai.entriesKeptAtReset())
        }
    }

    @Test
    fun `the parked list - always and whole, also with no saved key`() {
        phone.store(saved = false, parked = true)
        assertEquals(mapOf(PARKED to parkedList()), ai.entriesKeptAtReset())
    }

    @Test
    fun `a linked key and a parked key - only the list`() {
        phone.store(verdict = LINKED, paid = "yes", parked = true)
        assertEquals(mapOf(PARKED to parkedList()), ai.entriesKeptAtReset())
    }

    @Test
    fun `a key that must be kept and a parked key - the pair and the list`() {
        phone.store(verdict = OWN, paid = "yes", parked = true)
        assertEquals(pairPaid + (PARKED to parkedList()), ai.entriesKeptAtReset())
    }

    // GUARD at RED (no token: nothing); after the fix it stops a retired last key from making a kept file for nothing.
    @Test
    fun `an empty parked list keeps nothing and writes no file`() {
        ai.writeIfStillThisWallet({ true }) { putLinkStateJson(linkJson(USER, LINKED, "no")) }
        ai.walletScopedStores().single().importEntries(entries() + (PARKED to "[]"))
        assertEquals(emptyMap<String, Any?>(), ai.entriesKeptAtReset())
        assertFalse("a kept file was written for nothing", keep.keep(WALLET_A))
    }

    @Test
    fun `never any other entry - not the verdict, a chat list, a picture list, a model or a setting`() {
        phone.store(verdict = OWN, paid = "yes", parked = true)
        ai.setConversationsJson("""[{"id":"synthetic-chat"}]""")
        ai.setImagesJson("""[{"id":"synthetic-picture"}]""")
        ai.setSelectedChatModel("synthetic-chat-model")
        ai.setSelectedImageModel("synthetic-image-model")
        ai.setRetentionDays(3)
        ai.setImageAspect("16:9")

        assertEquals(setOf("ai_token", "ai_user_id", LINK, PARKED), ai.entriesKeptAtReset().keys)
    }

    // GUARD: an AI store that cannot be read answers with an error, never "nothing to keep" (the Reset needs to tell them apart).
    @Test
    fun `a store that cannot be read throws, it does not say nothing to keep`() {
        phone.unreadableStore()
        assertTrue(runCatching { ai.entriesKeptAtReset() }.isFailure)
    }

    // ------------------------------------------------------------------- through the keep step and the real claim

    private fun resetOfWalletA() {
        assertTrue("the keep step kept nothing", keep.keep(WALLET_A))
        // What the Reset's S4 and the start pass do: the stores are nobody's and the AI store is gone.
        ownership.markWiped()
        ai.clear()
    }

    @Test
    fun `a Reset of a linked wallet writes no bundle`() {
        phone.store(verdict = LINKED, paid = "yes")
        assertFalse("the keep step kept a key the phrase opens", keep.keep(WALLET_A))
        assertNull(bundles.read(WALLET_A))
    }

    // GUARD: the pair the rule keeps goes through the unchanged claim. No verdict comes back, so nothing is charged first.
    @Test
    fun `the kept pair - only the AI store in the bundle, another phrase sees nothing, the same phrase gets exactly the pair`() =
        runBlocking {
            phone.store(verdict = OWN, paid = "yes")
            ai.setConversationsJson("""[{"id":"synthetic-chat"}]""")
            resetOfWalletA()
            assertEquals("the bundle", mapOf(AI to pairPaid), bundles.read(WALLET_A))
            assertEquals(NO_CHAT_OWNER, record.owner())

            ownership.claimFor(WALLET_B)
            assertEquals("B's AI store is not empty", emptyMap<String, Any?>(), entries())
            assertEquals("A's bundle is gone", mapOf(AI to pairPaid), bundles.read(WALLET_A)?.filterKeys { it == AI })

            assertEquals(ChatClaim.RESTORED, ownership.claimFor(WALLET_A))
            assertEquals("not exactly the pair", pairPaid, entries())
            assertNull("the bundle stayed", bundles.read(WALLET_A))

            phone.answer = { 500 to """{"error":"internal"}""" }
            val charge = phone.access.keyForCharge()
            assertNull("a charged request got the restored key before key/state answered", charge.first)
            assertEquals(listOf(KEY_STATE), phone.requests.toList())
        }

    @Test
    fun `the parked list comes back as the parked list, never as the saved key`() {
        phone.store(verdict = LINKED, paid = "yes", parked = true)
        resetOfWalletA()
        assertEquals("the bundle", mapOf(AI to mapOf(PARKED to parkedList())), bundles.read(WALLET_A))

        assertEquals(ChatClaim.RESTORED, ownership.claimFor(WALLET_A))

        assertEquals(mapOf(PARKED to parkedList()), entries())
        assertNull("a parked key became the saved key", ai.getToken())
    }

    // ---------------------------------------------------------- ZC-1195: nobody's live key, the wallet's own saved key

    private fun nobodysLiveKey(link: String?) {
        record.setOwner(NO_CHAT_OWNER)
        ai.saveCredentials(LIVE_TOKEN, LIVE_USER)
        if (link != null) ai.writeIfStillThisWallet({ true }) { putLinkStateJson(link) }
    }

    private fun parkedOf(token: String): String = """[{"token":"$token","userId":"synthetic-user-of-it"}]"""

    private fun parkedTokens(): List<String> {
        val list = JSONArray(entries()[PARKED]?.toString() ?: "[]")
        return (0 until list.length()).map { list.getJSONObject(it).getString("token") }
    }

    @Test
    fun `ZC-1195 - paid yes or unknown - the wallet's own key is saved and the live key is parked, not overwritten`() {
        for (link in listOf(linkJson(LIVE_USER, null, "yes"), linkJson(LIVE_USER, OWN, "unknown"), null)) {
            nobodysLiveKey(link)
            bundles.write(WALLET_A, mapOf(AI to pair))

            assertEquals(ChatClaim.RESTORED, ownership.claimFor(WALLET_A))

            assertEquals("the wallet's own key is not the saved one ($link)", TOKEN, ai.getToken())
            assertEquals(USER, ai.getUserId())
            val parked = JSONArray(entries()[PARKED]?.toString() ?: "[]")
            assertEquals("the live key was overwritten and lost ($link)", 1, parked.length())
            assertEquals(LIVE_TOKEN, parked.getJSONObject(0).getString("token"))
            assertEquals(LIVE_USER, parked.getJSONObject(0).getString("userId"))
            assertNull("the live key's verdict came along ($link)", entries()[LINK])
            ai.clear()
        }
    }

    @Test
    fun `ZC-1195 - neither parked list loses an entry beside the parked live key`() {
        nobodysLiveKey(linkJson(LIVE_USER, null, "yes"))
        ai.walletScopedStores().single().importEntries(entries() + (PARKED to parkedOf("synthetic-live-parked")))
        bundles.write(WALLET_A, mapOf(AI to pair + (PARKED to parkedOf("synthetic-own-parked"))))

        ownership.claimFor(WALLET_A)

        assertEquals(TOKEN, ai.getToken())
        assertEquals(setOf(LIVE_TOKEN, "synthetic-live-parked", "synthetic-own-parked"), parkedTokens().toSet())
    }

    // GUARD: a key the wallet's own list already holds stands there once.
    @Test
    fun `ZC-1195 guard - a live key already in the wallet's parked list stands once`() {
        nobodysLiveKey(linkJson(LIVE_USER, null, "yes"))
        bundles.write(WALLET_A, mapOf(AI to pair + (PARKED to parkedOf(LIVE_TOKEN))))

        ownership.claimFor(WALLET_A)

        assertEquals(listOf(LIVE_TOKEN), parkedTokens())
    }

    // GUARD: spec 4.5 (c): an account known to hold no paid credit may be overwritten; the same key is not parked beside itself.
    @Test
    fun `ZC-1195 guard - a live key known to be unpaid, or the very key the bundle holds, is not parked`() {
        nobodysLiveKey(linkJson(LIVE_USER, null, "no"))
        bundles.write(WALLET_A, mapOf(AI to pair))
        ownership.claimFor(WALLET_A)
        assertEquals(TOKEN, ai.getToken())
        assertEquals(emptyList<String>(), parkedTokens())

        record.setOwner(NO_CHAT_OWNER)
        ai.saveCredentials(TOKEN, USER)
        bundles.write(WALLET_A, mapOf(AI to pair))
        ownership.claimFor(WALLET_A)
        assertEquals(TOKEN, ai.getToken())
        assertEquals(emptyList<String>(), parkedTokens())
    }

    private companion object {
        const val AI = "zchat_ai_prefs"
        const val PARKED = "ai_parked_keys"
        const val LINK = "ai_link_state"
        const val LINKED = "linked"
        const val OWN = "own"
        const val LIBRARY_ENTRY = "__androidx_security_crypto_encrypted_prefs_"
        const val LIVE_TOKEN = "synthetic-token-live"
        const val LIVE_USER = "synthetic-user-live"
        val WALLET_A = "a".repeat(64)
        val WALLET_B = "b".repeat(64)
    }
}
