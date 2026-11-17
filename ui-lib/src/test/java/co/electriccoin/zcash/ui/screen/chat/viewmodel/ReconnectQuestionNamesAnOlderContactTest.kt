package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmation
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-535 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §3.4, §3.9) — a contact on ZChat 2.12.4 or older does not see
 * 2.13.0's on-chain messages until it updates and rescans, so the reconnect's two messages reach it only then. The
 * question said nothing about it.
 *
 * Must hold: when the app knows the contact is older (its newest relay rumor carried no Ironwood capability tag,
 * ZchatPreferences.getPeerIronwoodCap(...).capIronwood == false, the rule of the attachment warning), the reconnect's
 * question says so in one more whole sentence (PaidActionConfirmation.contactIsOlder, the text
 * paid_action_key_change_reconnect_older). The quote is unchanged: the same count and the same total.
 *
 * Controls (green before and after): a rumor with the tag, no record at all, and a newer record with the tag after an
 * older one without it get the plain question. Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectQuestionNamesAnOlderContactTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc535olderpeer0001"
        const val OLDER_TEXT = "paid_action_key_change_reconnect_older"
        const val PLAIN_TEXT = "paid_action_key_change_reconnect"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    @Test
    fun `a contact whose newest relay rumor has no Ironwood tag gets the older-contact question text and the same quote`() =
        runBlocking {
            val asked = question(ZchatPreferences.PeerIronwoodCap(capIronwood = false, atSec = 1_000L))

            assertEquals("the question does not know the contact is on 2.12.4 or older", true, contactIsOlder(asked))
            assertEquals(
                "the question does not say the contact will not see these messages until it updates",
                stringId(OLDER_TEXT),
                PaidActionCopy.actionText(asked),
            )
            assertEquals("the quote is the same for an older contact", 2, asked.onChainMessages)
            assertEquals("2 x 17,000 zatoshi", 34_000L, asked.totalCost.value)
            assertEquals(PaidAction.KEY_CHANGE_RECONNECT, asked.action)
        }

    @Test
    fun `control - a contact whose newest relay rumor has the Ironwood tag gets the plain question`() =
        runBlocking { assertPlain(question(ZchatPreferences.PeerIronwoodCap(capIronwood = true, atSec = 1_000L))) }

    @Test
    fun `control - a contact with no capability record gets the plain question`() =
        runBlocking { assertPlain(question(null)) }

    @Test
    fun `control - a newer record with the tag after an older one without it gets the plain question`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
            chat.caps[PEER] = ZchatPreferences.PeerIronwoodCap(capIronwood = false, atSec = 1_000L)
            // The contact updated: its next rumor carries the tag and replaces the record.
            chat.caps[PEER] = ZchatPreferences.PeerIronwoodCap(capIronwood = true, atSec = 2_000L)
            assertPlain(question(chat))
        }

    private fun assertPlain(asked: PaidActionConfirmation) {
        assertFalse("the older-contact sentence is shown without a reason", contactIsOlder(asked) == true)
        assertEquals(stringId(PLAIN_TEXT), PaidActionCopy.actionText(asked))
        assertEquals(2, asked.onChainMessages)
        assertEquals(34_000L, asked.totalCost.value)
    }

    private suspend fun question(cap: ZchatPreferences.PeerIronwoodCap?): PaidActionConfirmation {
        val chat = KeyChangeReconnectChat(PEER, consent = false, exchangeComplete = true)
        if (cap != null) chat.caps[PEER] = cap
        return question(chat)
    }

    private suspend fun question(chat: KeyChangeReconnectChat): PaidActionConfirmation {
        val vm = chat.viewModel()
        vm.dismissE2EKeyChanged(PEER)
        awaitIdle(vm)
        val state = HandshakeHarness.sendMessageState(vm)
        assertTrue("the reconnect must ask its price first, was $state", state is SendMessageState.NeedsPaidActionConfirmation)
        assertEquals("the question paid", 0, chat.seam.proposals())
        return (state as SendMessageState.NeedsPaidActionConfirmation).confirmation
    }

    /** PaidActionConfirmation.contactIsOlder, read by name so this file compiles before the field exists; null when absent. */
    private fun contactIsOlder(confirmation: PaidActionConfirmation): Boolean? =
        confirmation.javaClass.methods
            .firstOrNull { it.name == "getContactIsOlder" }
            ?.invoke(confirmation) as Boolean?

    /** R.string.[name], read by name so this file compiles before the text exists; -1 when absent. */
    private fun stringId(name: String): Int =
        runCatching {
            Class
                .forName("co.electriccoin.zcash.ui.R\$string")
                .getField(name)
                .getInt(null)
        }.getOrDefault(-1)
}
