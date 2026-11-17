package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-1031 (register, high; review REVIEW_T7aR_2_20261020.md finding 4): at a claim of chats that belong to nobody, the
 * live AI store's access key is kept only when the claiming wallet's saved chats bring no AI part at all. When they
 * bring an AI part WITHOUT a token (its AI chats, say), that part replaces the live store whole and nobody's live
 * token, the only way to the credit paid for, is lost. The rule (W-52, rule K-0, ZC-1031): the claiming wallet's own
 * saved key wins; when its saved AI part holds no token, the live token and its user id are kept beside the saved
 * AI data.
 */
class NobodysClaimKeepsTheLiveKeyOverKeylessAiDataTest {
    @Test
    fun `the claiming wallet's saved AI data has no token - the live token and its user id stay, beside that data`() {
        val chats = chats(ai = mapOf(TOKEN to "synthetic-token", USER to "synthetic-user", CHATS to "[\"nobody\"]"))
        chats.bundles.write(WALLET_B, mapOf(AI to mapOf(CHATS to "[\"b\"]"), "messages" to mapOf("m2" to "b's")))

        chats.ownership.claimFor(WALLET_B)

        assertEquals(
            "the live AI key was lost to the claiming wallet's keyless AI data",
            mapOf(TOKEN to "synthetic-token", USER to "synthetic-user", CHATS to "[\"b\"]"),
            chats.store(AI).entries,
        )
        assertEquals(mapOf("m2" to "b's"), chats.store("messages").entries)
        assertEquals(WALLET_B, chats.record.owner())
    }

    @Test
    fun `guard - the claiming wallet's saved key still wins over the live one`() {
        val chats = chats(ai = mapOf(TOKEN to "synthetic-token", USER to "synthetic-user"))
        chats.bundles.write(WALLET_B, mapOf(AI to mapOf(TOKEN to "synthetic-token-b", USER to "synthetic-user-b", CHATS to "[]")))

        chats.ownership.claimFor(WALLET_B)

        // ZC-1195: the live key of unknown paid state is parked beside it, never overwritten.
        val entries = chats.store(AI).entries
        assertEquals(
            mapOf(TOKEN to "synthetic-token-b", USER to "synthetic-user-b", CHATS to "[]"),
            entries - "ai_parked_keys",
        )
        assertTrue("the live key was lost", "synthetic-token" in entries["ai_parked_keys"].toString())
    }

    private fun chats(ai: Map<String, Any?>): InMemoryChatOwnership =
        InMemoryChatOwnership(NOBODY, listOf(InMemoryStore("messages", mapOf("m1" to "hello")), InMemoryStore(AI, ai)))

    private companion object {
        const val AI = "zchat_ai_prefs"
        const val TOKEN = "ai_token"
        const val USER = "ai_user_id"
        const val CHATS = "ai_conversations"
        const val NOBODY = "none"
        val WALLET_B = "b".repeat(64)
    }
}
