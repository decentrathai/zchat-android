package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * ZC-624 (T7B-HANDSHAKE-SPEC rev 2, section 2.5; text T1) — the key-change reconnect's price question can stay open
 * while its reason goes away: the contact's verified relay identity (ZBOOT) arrives, takes the warning down and binds
 * its relay key. Pay then still records the owed key exchange and the allowance, DROPS the fresh relay key
 * (`setPeerNostrPubkey(peer, null)`) and pays up to two set-up messages for a key change that no longer exists.
 *
 * Must hold: Pay on such a question (and the direct confirm of a free reconnect) changes nothing and pays nothing: 0
 * proposals, the relay key still bound, nothing owed, no allowance, and the notice T1
 * (`chat_key_change_reconnect_not_needed`, reached by its ChatSendCopy name).
 *
 * [KeyChangeReconnectChat] (Tunnel, standing consent given): the contact's ZBOOT is modelled by what it leaves (the
 * flag down, its relay key bound). Every key and address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayOnAReconnectQuestionWhoseReasonIsGoneChangesAndPaysNothingTest {
    private val sendScopes = SendHarnessScopes()

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

    private fun assertChangedAndPaidNothing(
        chat: KeyChangeReconnectChat,
        vm: ChatViewModel,
        how: String,
    ) {
        assertEquals("$how: something was built", 0, chat.seam.proposals())
        assertEquals("$how: the contact's fresh relay key was dropped", RELAY, chat.relayKey)
        assertFalse("$how: a key exchange was recorded as owed", PEER in chat.owed.kexOwed)
        assertFalse("$how: an allowance was written", chat.owed.zbootQuoted[PEER] == true)
        assertEquals(
            "$how: the notice T1 was not shown: ${HandshakeHarness.sendMessageState(vm)}",
            KeyAcceptApi.copyConstant("KEY_CHANGE_RECONNECT_NOT_NEEDED"),
            (HandshakeHarness.sendMessageState(vm) as? SendMessageState.Success)?.label,
        )
    }

    @Test
    fun `Pay on the reconnect question after the contact's ZBOOT took the warning down changes and pays nothing`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = true)
            val vm = chat.viewModel()

            vm.dismissE2EKeyChanged(PEER)
            awaitTrue { HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation }
            // The contact's verified ZBOOT arrives while the question is open: the warning goes, a fresh relay key is bound.
            chat.keyChanged = false
            chat.relayKey = RELAY
            vm.confirmPaidAction()
            awaitIdle(vm)
            awaitTrue { PEER !in KeyAcceptApi.reconnectsInFlight() }
            awaitIdle(vm)

            assertChangedAndPaidNothing(chat, vm, "Pay")
        }

    @Test
    fun `the direct confirm of a reconnect whose warning is already gone changes and pays nothing`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = true)
            val vm = chat.viewModel()
            chat.keyChanged = false
            chat.relayKey = RELAY

            vm.dismissE2EKeyChanged(PEER, costConfirmed = true)
            awaitIdle(vm)
            awaitTrue { PEER !in KeyAcceptApi.reconnectsInFlight() }
            awaitIdle(vm)

            assertChangedAndPaidNothing(chat, vm, "the direct confirm")
        }

    @Test
    fun `T1 is a string in English and Spanish and the English one is the notice`() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !(java.io.File(dir, "gradle.properties").isFile && java.io.File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        val root = requireNotNull(dir) { "repo root not found above the test working dir" }

        fun t1(locale: String): String? {
            val xml = java.io.File(root, "ui-lib/src/main/res/ui/common/$locale/chat_send_strings.xml").readText()
            val pattern = Regex("""<string name="chat_key_change_reconnect_not_needed"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            return pattern.find(xml)?.groupValues?.get(1)
        }
        assertEquals("the English T1 is not the notice", KeyAcceptApi.copyConstant("KEY_CHANGE_RECONNECT_NOT_NEEDED"), t1("values"))
        val spanish = t1("values-es")
        assertFalse("T1 has no Spanish line: $spanish", spanish == null || spanish == t1("values"))
    }

    private companion object {
        const val PEER = "usyntheticzc624reasongone001"
        val RELAY = "c4".repeat(32)
    }
}
