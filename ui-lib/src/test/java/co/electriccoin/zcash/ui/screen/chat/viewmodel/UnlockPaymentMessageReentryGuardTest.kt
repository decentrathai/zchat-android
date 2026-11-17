package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Finding [5] (MEDIUM, double-send) — RED regression pin for the missing in-flight re-entry guard in
 * [ChatViewModel.unlockPaymentMessage].
 *
 * unlockPaymentMessage is a value-transfer entrypoint (it submits a shielded proposal that pays the
 * unlock amount to the sender), yet — unlike sendPayment / fulfillPaymentRequest / sendReply — it
 * neither checks nor sets SendMessageState.Sending. A double-tap on "Pay to unlock" therefore launches
 * two coroutines that each build and submit their own proposal, double-charging the unlock amount. Every
 * sibling value sender guards this with `if (_sendMessageState.value is SendMessageState.Sending) return`
 * BEFORE viewModelScope.launch, and sets Sending as the first statement inside the coroutine.
 *
 * This pins that guard: with a send already in flight (_sendMessageState == Sending), a fresh
 * unlockPaymentMessage call must be REJECTED before any coroutine runs, leaving the state Sending.
 *
 * Fixture idiom mirrors RetestGap05AcceptRequestKeyChangeGuardTest: the instance is allocated WITHOUT
 * running ChatViewModel's very heavy constructor (sun.misc.Unsafe, the Objenesis trick — no init {}
 * runs, all fields default to null), and only the four fields this path reads are injected. The two
 * standing guards (blockedByKeyChange, blockOnChainFeatureIfNotVault) are satisfied by a dynamic Proxy
 * ZchatPreferences answering isE2EKeyChanged -> false and getConversationMode -> VAULT, so control
 * reaches the re-entry guard. createChunkedMessageProposal is left null on purpose: with the guard
 * REVERTED the launched coroutine reaches it and NPEs into the catch, flipping the state to Error — the
 * observable proof that a second send was (wrongly) started. Main = StandardTestDispatcher so any
 * launched coroutine is deterministically drained with runCurrent().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockPaymentMessageReentryGuardTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Prefs proxy satisfying only the two standing guards on the unlock path. */
    private fun guardPassingPrefs(): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "isE2EKeyChanged" -> false
                "getConversationMode" -> ConversationMode.VAULT
                "toString" -> "GuardPassingZchatPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as ZchatPreferences

    private fun setField(vm: ChatViewModel, name: String, value: Any?) {
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    /** Allocate a ChatViewModel WITHOUT running its heavy constructor, then inject the four read fields. */
    private fun chatViewModel(sendState: MutableStateFlow<SendMessageState>): ChatViewModel {
        val unsafe =
            Class.forName("sun.misc.Unsafe")
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
        val vm =
            unsafe.javaClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
        setField(vm, "_sendMessageState", sendState)
        setField(vm, "_currentUserAddress", MutableStateFlow<String?>("usynthetic-me"))
        setField(vm, "zchatPreferences", guardPassingPrefs())
        // createChunkedMessageProposal left null on purpose (see class doc).
        return vm
    }

    @Test
    fun `unlockPaymentMessage while a send is in flight is rejected, not launched`() {
        val sendState = MutableStateFlow<SendMessageState>(SendMessageState.Sending)
        val vm = chatViewModel(sendState)

        ChatViewModel::class.java
            .getMethod(
                "unlockPaymentMessage",
                String::class.java,
                String::class.java,
                java.lang.Long.TYPE,
            )
            .invoke(vm, "tx_locked", "usynthetic-peer", 1000L)

        // Drain anything that might have been launched. With the guard the launch never happens; with
        // the guard reverted the coroutine runs, hits the null createChunkedMessageProposal and its
        // catch flips the state to Error.
        dispatcher.scheduler.runCurrent()

        assertEquals(
            "a second unlock while Sending must be rejected before launch — state stays Sending",
            SendMessageState.Sending,
            sendState.value,
        )
    }
}
