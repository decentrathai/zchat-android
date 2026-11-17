package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1270: a Shielded chat keeps saying the contact may need to update, also when that contact is proven to run 2.13.
 *
 * The seam, read by reflection so the rest of the module's tests still compile before it exists:
 * - `UpgradeReminders.shieldedReminder(mode, canReply, hiddenByUser, contactProven: Boolean = false)`: NONE when proven,
 *   also when hidden (no chip either);
 * - `UpgradeReminders.contactIsProven213(peerCapIronwood: Boolean?, messages: List<ChatMessage>): Boolean`.
 *
 * Proof is the Ironwood capability tag, or an on-chain message from the contact sent after NU6.3 (2026-07-28): 2.12.4
 * cannot send on the chain since then (skill compat-2124), but it could before, so an older row proves nothing. An
 * unauthenticated plaintext memo can come from anyone who pays the address, so it is no proof about this contact.
 */
class ShieldedReminderProvenContactTest {
    // ZC-1941 added a fifth parameter, the local date (the reminder ends on 2026-12-17); this test pins a day before it.
    private val reminder =
        UpgradeReminders::class.java.methods.firstOrNull { it.name == "shieldedReminder" && it.parameterCount == 5 }
            ?: UpgradeReminders::class.java.methods.firstOrNull { it.name == "shieldedReminder" && it.parameterCount == 4 }

    private val proven = UpgradeReminders::class.java.methods.firstOrNull { it.name == "contactIsProven213" }

    private fun shown(
        mode: ConversationMode,
        canReply: Boolean,
        hidden: Boolean,
        contactProven: Boolean,
    ): Any? =
        assertNotNull(reminder, "UpgradeReminders.shieldedReminder has no contactProven parameter").let {
            if (it.parameterCount == 5) {
                it.invoke(UpgradeReminders, mode, canReply, hidden, contactProven, BEFORE_THE_END)
            } else {
                it.invoke(UpgradeReminders, mode, canReply, hidden, contactProven)
            }
        }

    private fun isProven(
        cap: Boolean?,
        messages: List<ChatMessage>,
    ): Boolean =
        assertNotNull(proven, "UpgradeReminders.contactIsProven213 does not exist")
            .invoke(UpgradeReminders, cap, messages) as Boolean

    private fun row(
        id: String = "abc123",
        at: String = "2026-10-01T00:00:00Z",
        outgoing: Boolean = false,
        authenticated: Boolean = true,
        systemNote: Boolean = false,
    ) = ChatMessage(
        id = id,
        txId = null,
        text = "hello",
        timestamp = Instant.parse(at),
        isOutgoing = outgoing,
        peerAddress = "peer",
        authenticated = authenticated,
        isSystemNote = systemNote,
    )

    @Test
    fun a_proven_contact_gets_no_reminder_and_no_chip() {
        assertEquals(ShieldedReminderUi.NONE, shown(ConversationMode.VAULT, true, hidden = false, contactProven = true))
        assertEquals(ShieldedReminderUi.NONE, shown(ConversationMode.VAULT, true, hidden = true, contactProven = true))
    }

    @Test
    fun a_contact_not_proven_still_gets_the_reminder() {
        assertEquals(ShieldedReminderUi.BANNER, shown(ConversationMode.VAULT, true, hidden = false, contactProven = false))
        assertEquals(ShieldedReminderUi.CHIP, shown(ConversationMode.VAULT, true, hidden = true, contactProven = false))
    }

    @Test
    fun the_capability_tag_proves_the_contact() {
        assertTrue(isProven(true, emptyList()))
    }

    @Test
    fun an_on_chain_message_from_the_contact_after_the_upgrade_proves_the_contact() {
        assertTrue(isProven(null, listOf(row())))
    }

    @Test
    fun nothing_else_proves_the_contact() {
        assertFalse(isProven(null, emptyList()), "no sign at all")
        assertFalse(isProven(false, emptyList()), "a relay message without the tag")
        assertFalse(isProven(null, listOf(row(outgoing = true))), "our own message")
        assertFalse(isProven(null, listOf(row(id = "nmsg-1"))), "a free relay message")
        assertFalse(isProven(null, listOf(row(id = "nostr-1"))), "a free relay message without a rumor id")
        assertFalse(isProven(null, listOf(row(systemNote = true))), "a local note")
    }

    @Test
    fun a_message_from_before_the_upgrade_is_no_proof() {
        assertFalse(isProven(null, listOf(row(at = "2026-07-01T00:00:00Z"))), "2.12.4 could still send on-chain then")
    }

    @Test
    fun a_memo_anyone_could_have_paid_for_is_no_proof() {
        assertFalse(isProven(null, listOf(row(authenticated = false))))
    }

    /** Source read: a JVM test cannot compose the chat. The screen must hand the proof to the rule. */
    @Test
    fun the_chat_screen_passes_the_proof_on() {
        val view = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        val chat = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"
        val source = ChatResourceText.source(view) + ChatResourceText.source(chat)
        assertFalse(
            "UpgradeReminders.shieldedReminder(conversationMode, isValidAddress, shieldedReminderHidden)" in source,
            "the chat still asks for the reminder without the proof",
        )
        assertTrue("contactIsProven213" in source, "the chat never asks whether the contact is proven")
    }

    private companion object {
        val BEFORE_THE_END: LocalDate = LocalDate.of(2026, 11, 20)
    }
}
