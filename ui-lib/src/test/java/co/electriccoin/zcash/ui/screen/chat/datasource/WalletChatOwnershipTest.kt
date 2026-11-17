package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2e (RD-1) — a different wallet never inherits another wallet's chats.
 *
 * Seen on the HONOR: after Delete Wallet with "keep" ticked (the default), a brand-new wallet at once
 * showed a July conversation from the earlier identity. Delete Wallet with keep leaves every chat store
 * in place and nothing records which wallet made them, so the next wallet simply reads them.
 *
 * Done when: a different recovery phrase sees none of the earlier wallet's chats, contacts, chat keys or
 * Nostr state (set aside, never deleted); the same phrase keeps every chat; an install upgraded from
 * 2.12.4 (no owner recorded) gives everything to the current wallet and hides nothing.
 */
class WalletChatOwnershipTest {
    private val phraseA = List(24) { "alpha$it" }
    private val phraseB = List(24) { "bravo$it" }
    private val tagA = chatOwnerTag(phraseA)
    private val tagB = chatOwnerTag(phraseB)

    private fun chatsOfA() =
        InMemoryChatOwnership(
            stores =
                listOf(
                    InMemoryStore("messages", mapOf("conv:1" to "July conversation", "read:1" to 42L)),
                    InMemoryStore("contacts", mapOf("contacts" to "[Bob]")),
                    InMemoryStore("keys", mapOf("e2e_our_priv_bob" to "secret", "nostr_rot" to 3, "seen" to setOf("x", "y"))),
                ),
        )

    @Test
    fun a_different_phrase_after_delete_with_keep_sees_none_of_the_earlier_chats() {
        val chats = chatsOfA() // an install that never recorded an owner (as every install before P2e)
        val before = chats.live.visible()

        chats.ownership.recordOwnerIfUnset(tagA) // Delete Wallet (keep ticked) of wallet A
        chats.ownership.claimFor(tagB) // a new wallet B is created

        assertEquals(
            "wallet B sees wallet A's chats, contacts and chat keys (RD-1)",
            emptyMap<String, Map<String, Any?>>(),
            chats.live.visible(),
        )
        assertEquals("wallet A's chats must be set aside, not deleted", before, chats.bundles.read(tagA))
        assertTrue("in-memory copies must be re-read after the stores changed", chats.live.reloads > 0)
    }

    @Test
    fun the_earlier_chats_come_back_when_their_phrase_is_loaded_again() {
        val chats = chatsOfA()
        val chatsOfWalletA = chats.live.visible()
        chats.ownership.recordOwnerIfUnset(tagA)
        chats.ownership.claimFor(tagB)
        chats.store("messages").entries["conv:9"] = "B's own chat"

        chats.ownership.recordOwnerIfUnset(tagB) // Delete Wallet (keep) of B, then restore phrase A
        val claim = chats.ownership.claimFor(tagA)

        assertEquals(ChatClaim.RESTORED, claim)
        assertEquals("wallet A must get every one of its chats back", chatsOfWalletA, chats.live.visible())
        assertEquals(
            "wallet B's chats must be set aside in turn",
            mapOf("messages" to mapOf("conv:9" to "B's own chat"), "contacts" to emptyMap(), "keys" to emptyMap()),
            chats.bundles.read(tagB),
        )
    }

    @Test
    fun the_same_phrase_keeps_every_chat() {
        val chats = chatsOfA()
        val before = chats.live.visible()
        chats.ownership.recordOwnerIfUnset(tagA)

        val claim = chats.ownership.claimFor(tagA) // Delete Wallet (keep) and restore the same phrase

        assertEquals(ChatClaim.KEPT, claim)
        assertEquals(before, chats.live.visible())
        assertTrue(chats.bundles.bundles.isEmpty())
    }

    @Test
    fun an_install_from_2_12_4_gives_everything_to_the_current_wallet() {
        val chats = chatsOfA() // upgraded install: chats on the phone, no owner recorded
        val before = chats.live.visible()

        val claim = chats.ownership.claimFor(tagA) // the next time the wallet is saved

        assertEquals(ChatClaim.ADOPTED, claim)
        assertEquals("an upgrade must hide nothing", before, chats.live.visible())
        assertEquals("the chats now belong to the current wallet", tagA, chats.record.owner())
    }

    @Test
    fun a_failed_set_aside_leaves_the_chats_where_they_were_and_stops_the_new_wallet() {
        val chats = chatsOfA()
        val before = chats.live.visible()
        chats.ownership.recordOwnerIfUnset(tagA)
        chats.bundles.failWrites = true

        val outcome = runCatching { chats.ownership.claimFor(tagB) }

        assertTrue("a set-aside that could not be written must stop the new wallet", outcome.isFailure)
        assertEquals("nothing may be cleared when the set-aside failed", before, chats.live.visible())
        assertEquals(tagA, chats.record.owner())
    }

    @Test
    fun an_interrupted_set_aside_never_overwrites_the_saved_chats() {
        val chats = chatsOfA()
        val before = chats.live.visible()
        chats.ownership.recordOwnerIfUnset(tagA)
        chats.store("keys").failWrites = true // the clear stops half way (process killed, disk error)

        assertTrue(runCatching { chats.ownership.claimFor(tagB) }.isFailure)
        chats.store("keys").failWrites = false
        chats.ownership.claimFor(tagB) // next attempt, with some stores already cleared

        assertEquals("the retry must not replace the full set-aside with a half-cleared copy", before, chats.bundles.read(tagA))
        assertEquals(emptyMap<String, Map<String, Any?>>(), chats.live.visible())
    }

    @Test
    fun after_a_full_delete_the_next_wallet_starts_empty_and_no_set_aside_chats_are_touched() {
        val chats = chatsOfA()
        chats.bundles.write(chatOwnerTag(List(24) { "charlie$it" }), mapOf("messages" to mapOf("c" to "C's chat")))
        val otherBundles = LinkedHashMap(chats.bundles.bundles)
        chats.ownership.recordOwnerIfUnset(tagA)
        chats.ownership.markWiped() // Delete Wallet without keep: the stores it knows were erased...
        // ...but the address cache and identities are not among them.
        chats.store("contacts").entries["conversation_partners"] = "A's partners"

        chats.ownership.claimFor(tagB)

        assertEquals(emptyMap<String, Map<String, Any?>>(), chats.live.visible())
        assertNull("a full delete must not keep a copy of the deleted wallet's chats", chats.bundles.read(tagA))
        assertEquals(otherBundles, chats.bundles.bundles)
    }

    @Test
    fun the_owner_tag_is_a_one_way_name_of_the_phrase() {
        assertEquals(tagA, chatOwnerTag(phraseA.map { " ${it.uppercase()} " }))
        assertNotEquals(tagA, tagB)
        assertEquals(64, tagA.length)
        assertFalse(phraseA.any { tagA.contains(it) })
    }

    @Test
    fun store_entries_keep_their_types_through_a_bundle() {
        val entries = mapOf("s" to "text", "b" to true, "i" to 7, "l" to 9_000_000_000L, "f" to 1.5f, "t" to setOf("a", "b"))

        assertEquals(entries, decodeStoreEntries(encodeStoreEntries(entries)))
    }
}
