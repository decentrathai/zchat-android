package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * T1b-1 §2 (fix-all 2.13.0, train T1b; T1 K0 finding "a message in an Open chat still goes out over the relays"): K0
 * closed only the on-chain send chokepoint. The free relay routes never reach it, so with a Keystone account selected
 * an Open chat's message, reply, reaction, payment request, mode change, timer, a file reference and a group message
 * still go out over the relays, and the Open message's v4 INIT carries the Keystone account's chat address, linking it
 * to the user's relay identity for the contact.
 *
 * Wanted (K0 §4 and the texts already say so): with a Keystone account selected the chat is closed on every transport.
 * Each user-started action publishes nothing, shows the Keystone text (ChatSendCopy.KEYSTONE_CHATS_CLOSED, or
 * GROUP_KEYSTONE_REFUSED for a group), and a send that returns a Boolean returns false so the screen keeps the typed
 * text. Control: the same Open message goes out once the ZChat account is selected again.
 *
 * Drives the real ChatViewModel and GroupViewModel entry points; the relay publisher is a counting fake registered
 * with NostrChatBridge. Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeystoneClosesRelayChatSendsTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val groupVms = mutableListOf<GroupViewModel>()
    private val scheduler = TestCoroutineScheduler()

    private companion object {
        const val PEER = "usynthetict1brelaykeystone01"
        const val PEER_NOSTR = "7e5f0c4d2a1b9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f"
        const val QUOTED_ID = "synthetic-quoted-id"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.registerPublisher { plaintext, _ ->
            published += plaintext
            NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-rumor-${published.size}")
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        groupVms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            groupVms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        groupVms.clear()
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an Open chat message on a Keystone account publishes nothing and keeps the typed text`() =
        runBlocking {
            val screen = openChat(ConversationMode.OPEN)

            val accepted = screen.vm.sendMessage(PEER, "synthetic open hello")
            awaitIdle(screen.vm)

            assertNothingPublished("an Open message")
            assertFalse("the refused send must keep the typed text (return false)", accepted)
            assertClosedText(screen.vm)
        }

    @Test
    fun `an Open chat reply on a Keystone account publishes nothing`() =
        runBlocking {
            val screen = openChat(ConversationMode.OPEN)

            val accepted = screen.vm.sendReply(PEER, "synthetic open reply", QUOTED_ID, "synthetic quote")
            awaitIdle(screen.vm)

            assertNothingPublished("an Open reply")
            assertFalse("the refused reply must keep the typed text (return false)", accepted)
            assertClosedText(screen.vm)
        }

    @Test
    fun `an Open chat reaction on a Keystone account publishes nothing`() =
        runBlocking {
            val screen = openChat(ConversationMode.OPEN)

            screen.vm.sendReaction(PEER, QUOTED_ID, "👍")
            awaitIdle(screen.vm)

            assertNothingPublished("an Open reaction")
            assertClosedText(screen.vm)
        }

    @Test
    fun `an Open chat payment request on a Keystone account publishes nothing`() =
        runBlocking {
            val screen = openChat(ConversationMode.OPEN)

            val accepted = screen.vm.sendPaymentRequest(PEER, 10_000_000L, "synthetic rent")
            awaitIdle(screen.vm)

            assertNothingPublished("an Open payment request")
            assertFalse("the refused request must be reported as not sent", accepted)
            assertClosedText(screen.vm)
        }

    @Test
    fun `a file reference in a Shielded chat with a relay key on a Keystone account publishes nothing`() =
        runBlocking {
            val screen = openChat(ConversationMode.VAULT)
            val zfile =
                ZFILEMessage(
                    hash = "0123456789abcdef0123456789abcdef",
                    type = ZFILEType.JPEG,
                    size = 1_024L,
                    url = "https://blossom.primal.net/0123456789abcdef0123456789abcdef",
                    wrappedKey = "c3ludGhldGljLXdyYXBwZWQta2V5",
                    blurhash = "",
                ).serialize()

            val accepted = screen.vm.sendMessage(PEER, zfile, restoreTextOnCancel = false)
            awaitIdle(screen.vm)

            assertNothingPublished("a file reference")
            assertFalse("the refused file send must be reported as not sent", accepted)
            assertClosedText(screen.vm)
        }

    @Test
    fun `a mode change in an Open chat on a Keystone account publishes nothing`() =
        runBlocking {
            val screen = openChat(ConversationMode.OPEN)

            screen.vm.changeConversationMode(PEER, ConversationMode.TUNNEL)
            awaitIdle(screen.vm)

            assertNothingPublished("a mode change")
            assertClosedText(screen.vm)
        }

    @Test
    fun `a disappearing timer in an Open chat on a Keystone account publishes nothing`() =
        runBlocking {
            val screen = openChat(ConversationMode.OPEN)

            screen.vm.setDisappearingTtl(PEER, 3_600L)
            awaitIdle(screen.vm)

            assertNothingPublished("a disappearing timer")
            assertClosedText(screen.vm)
        }

    @Test
    fun `a group message to a relay-reachable member on a Keystone account publishes nothing`() =
        runBlocking {
            val f =
                GroupPaidOnceFixture(
                    consent = true,
                    nostrKeyForP = { GroupPaidOnceFixture.SYNTHETIC_NOSTR_PUBKEY },
                    selectedAccount = ::keystoneAccount,
                )
            f.scheduler = scheduler
            val vm = f.groupViewModel().also { groupVms += it }
            vm.consumeGroupSendEvent()

            f.proxy.setGroupDraft(GroupPaidOnceFixture.GROUP_ID, "synthetic group hello")
            f.awaitNewJobs(vm) { vm.sendGroupMessage(GroupPaidOnceFixture.GROUP_ID, "synthetic group hello") }
            if (vm.paidActionConfirmation.value != null) f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertNothingPublished("a group message")
            assertEquals("nothing may reach the submit", 0, f.submits())
            val event = vm.groupSendEvent.value
            assertEquals(
                "the group banner must say a Keystone account can't send group messages: $event",
                GroupSendResult.AllFailed(GroupPaidOnceFixture.GROUP_ID, ChatSendCopy.GROUP_KEYSTONE_REFUSED),
                event,
            )
        }

    /** Control, green before and after: back on the ZChat account the same Open message goes out once. */
    @Test
    fun `control - an Open chat message on the ZChat account publishes once`() =
        runBlocking {
            val screen = openChat(ConversationMode.OPEN)
            screen.accounts.select(screen.accounts.zashiAccount())

            screen.vm.sendMessage(PEER, "synthetic open hello")
            awaitIdle(screen.vm)

            assertEquals("the Open message goes over the relays on the ZChat account", 1, published.size)
        }

    // --------------------------------------------------------------- harness

    private suspend fun openChat(mode: ConversationMode): KeystoneChatScreen =
        KeystoneChatScreen.open(PEER).also {
            it.prefs.mode = mode
            it.prefs.peerNostrPubkey = PEER_NOSTR
        }

    private fun assertNothingPublished(what: String) {
        assertEquals("$what went out over the relays while a Keystone account is selected", 0, published.size)
    }

    private fun assertClosedText(vm: ChatViewModel) {
        assertEquals(SendMessageState.Error(KeystoneChatScreen.closedText()), PaymentHarness.sendMessageState(vm))
    }

    private suspend fun keystoneAccount(): WalletAccount {
        val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
        return KeystoneAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-t1b-keystone"), zero),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-t1b-keystone"), Zatoshi(0)),
            isSelected = true,
        )
    }
}
