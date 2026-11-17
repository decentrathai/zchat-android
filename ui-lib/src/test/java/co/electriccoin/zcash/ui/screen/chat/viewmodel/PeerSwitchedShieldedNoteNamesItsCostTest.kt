package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.model.zecDisplay
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.invoke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.Locale

/**
 * QA2 Q2-48 (moved from lane U7 to U9, same fix commit as the view-model change; skill app-texts, money-path rule 1).
 *
 * The note a phone gets when its contact moves the chat to Shielded, `chat_note_peer_switched_shielded`, says "Messages
 * here now cost ZEC each." with no figure: the follower reads that every message costs money but not how much. U7's
 * `Qa2ChatScreenCopyTest.q2_48` pins the wording with a second argument, the least one Shielded message costs on-chain
 * (the same figure the composer and the first-message question show: `onChainSendMinimumCost()` in ZEC):
 *
 *   EN  "🔀 %1$s switched this chat to Shielded. Messages here now cost at least %2$s ZEC each."
 *   ES  "🔀 %1$s ha cambiado este chat a Shielded. Los mensajes aquí ahora cuestan al menos %2$s ZEC cada uno."
 *
 * The string and the view model's call (`ChatViewModel.handleModeControl`, the adopted-Shielded branch) must change
 * together: with `%2$s` in the text and one argument, the note throws `MissingFormatArgumentException` on the phone
 * that follows the switch. The note for a switch to a free mode keeps its text and its arguments. Adopting a mode sends
 * and pays nothing (ZC-1478 M1); this change adds a figure to a note and nothing else. No wire or stored format changes.
 *
 * All addresses and keys are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerSwitchedShieldedNoteNamesItsCostTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticq248switchpeer00001"
        val PEER_RELAY_KEY = "ab".repeat(32)
        const val KEY = "chat_note_peer_switched_shielded"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ---- the texts -----------------------------------------------------------------------------------------------

    @Test
    fun `the Shielded switch note has its figure placeholder in English and Spanish`() {
        assertEquals(
            "🔀 %1\$s switched this chat to Shielded. Messages here now cost at least %2\$s ZEC each.",
            ChatResourceText.strings("values").getValue(KEY),
        )
        assertEquals(
            "🔀 %1\$s ha cambiado este chat a Shielded. Los mensajes aquí ahora cuestan al menos %2\$s ZEC cada uno.",
            ChatResourceText.strings("values-es").getValue(KEY),
        )
    }

    @Test
    fun `the free-mode switch note keeps its text and its two arguments`() {
        for (dir in listOf("values", "values-es")) {
            val text = ChatResourceText.strings(dir).getValue("chat_note_peer_switched_followed")
            assertTrue("$dir: $text", text.contains("%1\$s") && text.contains("%2\$s"))
            assertFalse("$dir: $text", text.contains("%3\$s"))
        }
    }

    // ---- the view model passes the figure --------------------------------------------------------------------------

    /** A recording copy of the notes in English: the ids and arguments the view model asked for. */
    private class Notes : ChatNoteCopy {
        val asked: MutableList<Pair<Int, List<Any>>> = Collections.synchronizedList(mutableListOf())

        override fun get(
            id: Int,
            vararg args: Any,
        ): String {
            asked += id to args.toList()
            val text = ChatResourceText.english(id)
            return if (args.isEmpty()) text else String.format(text, *args)
        }
    }

    private fun phone(mode: ConversationMode) =
        RealModeStorePhone(
            PEER,
            mode,
            consent = true,
            exchangeComplete = true,
            ourKey = true,
            relayKey = PEER_RELAY_KEY,
        ).also { it.real.recordPeerIronwoodCap(PEER, true, System.currentTimeMillis() / 1000 - 60) }

    @Test
    fun `adopting a contact's switch to Shielded writes the note with the least one message costs`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL)
            val notes = Notes()
            val vm = phone.viewModel()
            AutoSpendVm.setField(vm, "noteCopy", notes)
            val id = ChatResourceText.stringId(KEY) ?: error("no string resource $KEY")

            invoke(vm, "handleModeControl", PEER, ZMSGProtocol.createModeChange(ConversationMode.VAULT, System.currentTimeMillis(), PEER))
            awaitIdle(vm)

            assertEquals("fixture: the switch to Shielded was adopted", ConversationMode.VAULT, phone.mode())
            val asked = notes.asked.filter { it.first == id }
            assertEquals("the Shielded switch note was written once", 1, asked.size)
            val args = asked.single().second
            assertEquals("the note must be asked with the contact's name and the figure: $args", 2, args.size)
            val figure = zecDisplay(onChainSendMinimumCost(), Locale.getDefault())
            assertEquals("the second argument is the least one on-chain message costs", figure, args[1])
            assertTrue("the figure is a number: $figure", figure.any { it.isDigit() })

            val written = phone.notes().map { it.text }.filter { it.contains("switched this chat to Shielded") }
            assertEquals("one note is stored for the switch: $written", 1, written.size)
            assertTrue("the stored note names the figure: ${written.single()}", written.single().contains("at least $figure ZEC each"))
            assertEquals("adopting a mode spends nothing", 0, phone.seam.proposalRequests.get())
        }

    @Test
    fun `adopting a contact's switch to a free mode still asks the followed note with two arguments`() =
        runBlocking {
            val phone = phone(ConversationMode.VAULT)
            phone.relaysUp()
            val notes = Notes()
            val vm = phone.viewModel()
            AutoSpendVm.setField(vm, "noteCopy", notes)
            val shielded = ChatResourceText.stringId(KEY) ?: error("no string resource $KEY")
            val followed = ChatResourceText.stringId("chat_note_peer_switched_followed") ?: error("no followed note")

            invoke(
                vm,
                "handleModeControl",
                PEER,
                ZMSGProtocol.createModeChange(ConversationMode.TUNNEL, System.currentTimeMillis(), PEER),
            )
            awaitIdle(vm)

            assertEquals("fixture: the switch to Tunnel was adopted", ConversationMode.TUNNEL, phone.mode())
            assertTrue("the Shielded note was written for a switch to Tunnel", notes.asked.none { it.first == shielded })
            val asked = notes.asked.single { it.first == followed }
            assertEquals("the followed note keeps its two arguments: ${asked.second}", 2, asked.second.size)
            assertNotNull(phone.notes().firstOrNull { it.text.contains("switched this chat to") })
        }
}
