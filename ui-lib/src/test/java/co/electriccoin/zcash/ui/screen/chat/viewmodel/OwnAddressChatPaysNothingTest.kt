package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.OwnAddressChokepointProbe
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * T3-extra (a) and (b) (fix-all 2.13.0, train T3; T2 phone findings 10 and 11).
 *
 * (a) At 02cc65cdb, in a Shielded chat whose address is one of our own (ZchatPreferences.isSelfAddress) and that has no
 * relay channel, switching to Tunnel asks "Pay for this? … 1 on-chain transaction" and, on Yes, pays a mode-change
 * control to our own address (T2 finding 11, seen twice on the HONOR). The disappearing timer in the same chat does the
 * same. A change the user confirmed earlier (costConfirmed) pays it with no question at all.
 *
 * (b) A message typed in that chat is paid to our own address, because the chat send chokepoint never asks whose address
 * it pays (ChatSendNeverPaysOwnAddressTest pins the chokepoint itself; here it is wired into the real ChatViewModel).
 * And a first-message question's stored ZBOOT allowance outlives "Delete chat" (which only hides the messages): on the
 * HONOR debug app one still stands for the release wallet's address (T2 finding 10), and no action in the app clears it.
 *
 * Must hold: in a chat with one of our own addresses no mode change or timer asks a price or pays anything, even one
 * confirmed earlier; the mode still changes on this phone and the user is told why nothing was sent; the timer is not
 * set on this phone only; a message there pays nothing and says why; deleting a chat withdraws its ZBOOT allowance, and
 * leaves other chats' allowances alone.
 *
 * Drives the real ChatViewModel (HandshakeHarness, the PaidChatFixture store). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnAddressChatPaysNothingTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val OWN = "usynthetict3extraownchat0001"
        const val CONTACT = "usynthetict3extracontact002"
        const val OTHER_CONTACT = "usynthetict3extracontact003"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `switching a Shielded chat with one of our own addresses to Tunnel asks nothing and pays nothing`() =
        runBlocking {
            val chat = Chat(OWN)
            val vm = chat.viewModel()

            vm.changeConversationMode(OWN, ConversationMode.TUNNEL)
            awaitIdle(vm)

            assertNoQuestion(vm)
            assertEquals(
                "a mode change was paid to one of our own addresses",
                0,
                chat.fixture.seam.proposalRequests
                    .get()
            )
            assertEquals("the mode still changes on this phone", ConversationMode.TUNNEL, chat.fixture.mode)
            assertEquals(OwnAddressChokepointProbe.copy("MODE_OWN_ADDRESS_NOT_PAID"), errorText(vm))
        }

    @Test
    fun `a mode change confirmed earlier pays nothing to one of our own addresses`() =
        runBlocking {
            val chat = Chat(OWN)
            val vm = chat.viewModel()

            vm.changeConversationMode(OWN, ConversationMode.TUNNEL, costConfirmed = true)
            awaitIdle(vm)

            assertEquals(
                "a confirmed mode change paid a control to one of our own addresses",
                0,
                chat.fixture.seam.proposalRequests
                    .get(),
            )
            assertEquals(ConversationMode.TUNNEL, chat.fixture.mode)
        }

    @Test
    fun `the timer in a Shielded chat with one of our own addresses asks nothing, pays nothing and is not set`() =
        runBlocking {
            val chat = Chat(OWN)
            val vm = chat.viewModel()

            vm.setDisappearingTtl(OWN, 3_600L)
            awaitIdle(vm)

            assertNoQuestion(vm)
            assertEquals(
                "a timer was paid to one of our own addresses",
                0,
                chat.fixture.seam.proposalRequests
                    .get()
            )
            assertTrue("the timer must not be set on this phone only", chat.timerWrites.isEmpty())
            assertEquals(OwnAddressChokepointProbe.copy("TIMER_OWN_ADDRESS_NOT_PAID"), errorText(vm))
        }

    @Test
    fun `a timer confirmed earlier pays nothing to one of our own addresses`() =
        runBlocking {
            val chat = Chat(OWN)
            val vm = chat.viewModel()

            vm.setDisappearingTtl(OWN, 3_600L, costConfirmed = true)
            awaitIdle(vm)

            assertEquals(
                "a confirmed timer paid a control to one of our own addresses",
                0,
                chat.fixture.seam.proposalRequests
                    .get(),
            )
            assertTrue(chat.timerWrites.isEmpty())
        }

    @Test
    fun `a message in a Shielded chat with one of our own addresses pays nothing and says why`() =
        runBlocking {
            val chat = Chat(OWN)
            val vm = chat.viewModel()
            // The app's chokepoint, with the store's self registry as its own-address check (as the app wires it).
            AutoSpendVm.setField(
                vm,
                "createChunkedMessageProposal",
                OwnAddressChokepointProbe.useCase(
                    accounts = Zc101FakeAccountDataSource(syntheticZashiAccount()),
                    proposals = chat.fixture.seam,
                    biometrics = HandshakeHarness.ApprovingBiometricRepository,
                    isOwnAddress = chat.store::isSelfAddress,
                    onRepository = sendScopes::own,
                ),
            )

            vm.sendMessage(OWN, "synthetic note to self")
            awaitIdle(vm)

            assertEquals(
                "a message was paid to one of our own addresses",
                0,
                chat.fixture.seam.proposalRequests
                    .get()
            )
            assertEquals(OwnAddressChokepointProbe.copy("OWN_ADDRESS_NOT_PAID"), errorText(vm))
        }

    @Test
    fun `deleting a chat withdraws the ZBOOT allowance its first-message question left`() =
        runBlocking {
            val chat = Chat(CONTACT)
            chat.zbootAllowances += CONTACT // the HONOR debug app's leftover (T2 finding 10)
            chat.zbootAllowances += OTHER_CONTACT
            val vm = chat.viewModel()

            vm.hideChat(CONTACT)
            awaitIdle(vm)

            assertFalse("Delete chat left a stored ZBOOT allowance that can still pay", CONTACT in chat.zbootAllowances)
            assertTrue("another chat's allowance must stay", OTHER_CONTACT in chat.zbootAllowances)
        }

    /** Control, green before and after: the same Tunnel switch in a contact's chat still asks first. */
    @Test
    fun `control - the same Tunnel switch in a contact's chat still asks before paying`() =
        runBlocking {
            val chat = Chat(CONTACT)
            val vm = chat.viewModel()

            vm.changeConversationMode(CONTACT, ConversationMode.TUNNEL)
            awaitIdle(vm)

            chat.fixture.assertAskedFirst(vm, messages = 1)
            assertEquals(ConversationMode.VAULT, chat.fixture.mode)
        }

    // --------------------------------------------------------------- harness

    private fun assertNoQuestion(vm: ChatViewModel) {
        val state = HandshakeHarness.sendMessageState(vm)
        assertFalse(
            "a price question was raised for a payment to one of our own addresses: $state",
            state.javaClass.simpleName.startsWith("Needs"),
        )
    }

    private fun errorText(vm: ChatViewModel): String? = (HandshakeHarness.sendMessageState(vm) as? SendMessageState.Error)?.message

    /**
     * A Shielded chat with [peer] (PaidChatFixture: keys held both ways, no relay key, costs acknowledged), where [OWN]
     * is our own address, the stored ZBOOT allowances are [zbootAllowances] and every timer write lands in [timerWrites].
     */
    private class Chat(
        peer: String,
    ) {
        val fixture = PaidChatFixture(peer, ConversationMode.VAULT)
        val zbootAllowances: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val timerWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val store: ZchatPreferences =
            intercept(
                fixture.proxy,
                mapOf(
                    "isSelfAddress" to { a -> a!![0] == OWN },
                    "isZbootQuoted" to { a -> a!![0] in zbootAllowances },
                    "setZbootQuoted" to { a ->
                        if (a!![1] as Boolean) zbootAllowances += a[0] as String else zbootAllowances -= a[0] as String
                        true
                    },
                    "setDisappearingTtl" to { a ->
                        timerWrites += a!![0] as String
                        true
                    },
                ),
            )

        /** PaidChatFixture.viewModel over [store]. */
        suspend fun viewModel(): ChatViewModel {
            val vm = chatViewModelWith(store, fixture.seam).also(AutoSpendVm::wireBootstrapFields)
            val processors = ConcurrentHashMap<String, E2EMessageProcessor>()
            processors["${fixture.peer}:${PaidChatFixture.CONV_ID}"] =
                E2EMessageProcessor(ByteArray(32) { 7 }, PaidChatFixture.CONV_ID, true, InMemoryRatchetStateStore())
            AutoSpendVm.setField(vm, "messageProcessors", processors)
            AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
            AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
            AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
            AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
            AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
            return vm
        }
    }
}

/** [delegate] with the calls named in [handlers] answered by them instead. */
private fun intercept(
    delegate: ZchatPreferences,
    handlers: Map<String, (Array<Any?>?) -> Any?>,
): ZchatPreferences =
    Proxy.newProxyInstance(
        ZchatPreferences::class.java.classLoader,
        arrayOf(ZchatPreferences::class.java),
    ) { _, method, args ->
        val handler = handlers[method.name]
        if (handler != null) {
            handler(args)
        } else {
            try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    } as ZchatPreferences
