package co.electriccoin.zcash.ui.screen.chat.datasource

import co.electriccoin.zcash.ui.screen.deletewallet.ResetDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-1031 (register, high; review REVIEW_T7aR_20261019.md finding 2): at a claim of chats that belong to nobody (the
 * owner record says "none") the AI store is emptied like every other wallet-scoped store, so its access key, the only
 * way to credit the user paid for, is lost when no kept file holds it: after a Reset made on an earlier 2.13
 * development build (no keep step), or a Reset whose wallet tag could not be read. The rule (W-52, rule K-0): a claim
 * of nobody's chats keeps the AI store's key entries (the token and its user id, nothing else of it) when the store
 * holds a token and the claiming wallet's own kept file brings no AI part; a kept AI part of the claiming wallet wins.
 * The second way in is closed by ZC-090-R: a Reset with no saved wallet answers "not started" and touches nothing.
 */
class NobodysClaimKeepsTheAiKeyTest {
    @Test
    fun `nobody's chats with a key in the AI store - the claim keeps the token and its user id, nothing else of it`() {
        val chats = chats(owner = NOBODY, ai = mapOf(TOKEN to "synthetic-token", USER to "synthetic-user", CHATS to "[]"))

        chats.ownership.claimFor(WALLET_B)

        assertEquals(
            "the AI key was lost at the claim",
            mapOf(TOKEN to "synthetic-token", USER to "synthetic-user"),
            chats.store(AI).entries,
        )
        assertTrue("the other stores were not emptied", chats.store("messages").entries.isEmpty())
        assertEquals(WALLET_B, chats.record.owner())
    }

    @Test
    fun `guard - the claiming wallet's own kept AI part wins over the live key`() {
        val chats = chats(owner = NOBODY, ai = mapOf(TOKEN to "synthetic-token", USER to "synthetic-user"))
        chats.bundles.write(WALLET_B, mapOf(AI to mapOf(TOKEN to "synthetic-token-b", USER to "synthetic-user-b")))

        chats.ownership.claimFor(WALLET_B)

        // ZC-1195: the live key of unknown paid state is parked beside it, never overwritten.
        val entries = chats.store(AI).entries
        assertEquals(mapOf(TOKEN to "synthetic-token-b", USER to "synthetic-user-b"), entries - "ai_parked_keys")
        assertTrue("the live key was lost", "synthetic-token" in entries["ai_parked_keys"].toString())
    }

    @Test
    fun `guard - nobody's AI store with no key stays empty, and another wallet's chats still go with that wallet`() {
        val empty = chats(owner = NOBODY, ai = mapOf(CHATS to "[]"))
        empty.ownership.claimFor(WALLET_B)
        assertTrue("nobody's AI chats were kept", empty.store(AI).entries.isEmpty())

        val owned = chats(owner = WALLET_A, ai = mapOf(TOKEN to "synthetic-token", USER to "synthetic-user"))
        owned.ownership.claimFor(WALLET_B)
        assertTrue("A's key stayed live for B", owned.store(AI).entries.isEmpty())
        val keptA = owned.bundles.read(WALLET_A)
        assertEquals("A's key is not in A's kept file", "synthetic-token", keptA?.get(AI)?.get(TOKEN))
    }

    @Test
    fun `the second way in - a Reset with no saved wallet does not start and touches nothing`() =
        runBlocking {
            ResetDriver().use { reset ->
                reset.seedPhraseStore()
                reset.wallet = null
                assertEquals("NOT_STARTED", reset.reset(keepFiles = false))
                assertTrue("the Reset touched something: ${reset.phone.events}", reset.at(ResetDriver.STEP_KEPT_WRITE) < 0)
                assertTrue(reset.at(ResetDriver.STEP_OWNER_NONE) < 0 && reset.at(ResetDriver.STEP_WALLET_CLEAR) < 0)
                assertTrue("a record was written", reset.record() == null)
            }
        }

    private fun chats(
        owner: String?,
        ai: Map<String, Any?>,
    ): InMemoryChatOwnership =
        InMemoryChatOwnership(owner, listOf(InMemoryStore("messages", mapOf("m1" to "hello")), InMemoryStore(AI, ai)))

    private companion object {
        const val AI = "zchat_ai_prefs"
        const val TOKEN = "ai_token"
        const val USER = "ai_user_id"
        const val CHATS = "ai_conversations"
        const val NOBODY = "none"
        val WALLET_A = "a".repeat(64)
        val WALLET_B = "b".repeat(64)
    }
}
