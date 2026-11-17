package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round 8 D6 (REVIEW_7C F17): when ChatWalletOwnership asks for the kept chat copies, paused restores and restore
 * marks that name no wallet to be tagged, with which wallet, and what a failed tag write does. The copies' own
 * behaviour across wallets is pinned end to end in ChatCopyMarksBelongToOneWalletTest.
 *
 * Pins the D6 fix (7bebcf84f); these cases need its constructor argument, so they come after it.
 */
class ChatOwnershipTagsKeptCopiesTest {
    private val walletA = "a".repeat(64)
    private val walletB = "b".repeat(64)

    private fun chats(owner: String?) =
        InMemoryChatOwnership(owner = owner, stores = listOf(InMemoryStore("messages", mapOf("m" to "A's chat"))))

    @Test
    fun a_claim_for_another_wallet_tags_with_the_outgoing_wallet() {
        val chats = chats(walletA)

        assertEquals(ChatClaim.STARTED_EMPTY, chats.ownership.claimFor(walletB))

        assertEquals(listOf(walletA), chats.marks.taggedWith)
    }

    @Test
    fun a_failed_tag_write_stops_the_claim_before_anything_is_set_aside() {
        val chats = chats(walletA)
        chats.marks.failWrites = true

        expectFailure { chats.ownership.claimFor(walletB) }

        assertEquals("the owner changed", walletA, chats.record.value)
        assertNull("wallet A's chats were set aside although their copies could not be tagged", chats.bundles.read(walletA))
        assertEquals("wallet A's live chats were touched", mapOf("messages" to mapOf("m" to "A's chat")), chats.live.visible())
    }

    @Test
    fun the_adopting_wallet_tags_everything_on_the_phone() {
        val chats = chats(null)

        assertEquals(ChatClaim.ADOPTED, chats.ownership.claimFor(walletB))

        assertEquals(listOf(walletB), chats.marks.taggedWith)
    }

    @Test
    fun a_failed_tag_write_stops_the_adoption() {
        val chats = chats(null)
        chats.marks.failWrites = true

        expectFailure { chats.ownership.claimFor(walletB) }

        assertNull("the owner was recorded although the copies could not be tagged", chats.record.value)
    }

    @Test
    fun delete_wallet_tags_with_the_wallet_it_records_and_only_when_it_records_one() {
        val unset = chats(null)
        unset.ownership.recordOwnerIfUnset(walletA)
        assertEquals(listOf(walletA), unset.marks.taggedWith)
        assertEquals(walletA, unset.record.value)

        val set = chats(walletB)
        set.ownership.recordOwnerIfUnset(walletA)
        assertEquals("an owner already recorded must be left as it is", emptyList<String>(), set.marks.taggedWith)
        assertEquals(walletB, set.record.value)
    }

    @Test
    fun a_failed_tag_write_stops_delete_wallet_from_recording_the_owner() {
        val chats = chats(null)
        chats.marks.failWrites = true

        expectFailure { chats.ownership.recordOwnerIfUnset(walletA) }

        assertNull(chats.record.value)
    }

    @Test
    fun the_same_wallet_or_a_wiped_phone_tags_nothing() {
        val kept = chats(walletA)
        assertEquals(ChatClaim.KEPT, kept.ownership.claimFor(walletA))
        assertEquals(emptyList<String>(), kept.marks.taggedWith)

        val wiped = chats(NO_CHAT_OWNER)
        assertEquals(ChatClaim.STARTED_EMPTY, wiped.ownership.claimFor(walletB))
        assertEquals("nothing belongs to 'no wallet'", emptyList<String>(), wiped.marks.taggedWith)
        assertTrue(wiped.bundles.bundles.isEmpty())
    }

    /**
     * ZC-296 (T7b triage 13 Oct, SMALL work order): at 828ed7c0a the claim raises the wallet scope
     * (`live.beginChange()`, the generation a Join in progress checks) before it writes the owner tag of the kept
     * chat copies. When that write fails the claim stops and nothing moves, yet the scope change already counted: a
     * Join in progress stops and waits for Join again (the saved copy survives; nothing is lost or paid).
     * Wanted: a failed tag write leaves the wallet scope as it was; a claim that goes on raises it once, before
     * anything is set aside.
     */
    @Test
    fun a_failed_tag_write_changes_no_wallet_scope() {
        val chats = chats(walletA)
        chats.marks.failWrites = true
        val live = CountingLiveStores(chats)
        val ownership = ChatWalletOwnership(chats.record, chats.bundles, live, chats.marks)

        expectFailure { ownership.claimFor(walletB) }

        assertEquals("a claim whose copy tag write failed still changed the wallet scope", 0, live.begins)
        assertEquals("the owner changed", walletA, chats.record.value)
    }

    @Test
    fun control_a_claim_that_goes_on_changes_the_wallet_scope_once_before_anything_is_set_aside() {
        val chats = chats(walletA)
        val live = CountingLiveStores(chats)
        val ownership = ChatWalletOwnership(chats.record, chats.bundles, live, chats.marks)

        assertEquals(ChatClaim.STARTED_EMPTY, ownership.claimFor(walletB))

        assertEquals(1, live.begins)
        assertEquals("the scope changed after wallet A's chats were set aside", false, live.setAsideAtFirstBegin)
        assertEquals(listOf(walletA), chats.marks.taggedWith)
    }

    /** The live stores of [chats], counting `beginChange` and noting whether wallet A's chats were set aside at the first. */
    private inner class CountingLiveStores(
        private val chats: InMemoryChatOwnership,
    ) : LiveChatStores by chats.live {
        var begins = 0
        var setAsideAtFirstBegin: Boolean? = null

        override fun beginChange() {
            if (begins == 0) setAsideAtFirstBegin = chats.bundles.read(walletA) != null
            begins++
        }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalStateException) {
            return
        }
        fail("a failed tag write was ignored")
    }
}
