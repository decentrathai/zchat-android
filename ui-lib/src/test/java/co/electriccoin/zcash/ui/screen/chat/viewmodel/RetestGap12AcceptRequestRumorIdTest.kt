package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * RetestGap12 — RED regression pin for [ChatViewModel.acceptMessageRequest]'s message-id derivation
 * (ChatViewModel.kt:540).
 *
 * When a user ACCEPTS an inbound OPEN contact request, the first message is surfaced as a persisted
 * inbound bubble. Its id MUST be keyed on the STABLE rumor id — `"nmsg-<rumorId>"` — exactly like
 * [ChatViewModel.observeNostrInbound] derives it for the SAME rumor (ChatViewModel.kt:771,
 * `"nmsg-${chat.rumorId}"`). Only then does the accept bubble correlate/dedup with the inbound copy
 * of that message and with any reaction/reply the SENDER attaches to its own rumor-keyed copy.
 *
 * The fix at line 540 is:
 *     val msgId = if (request.rumorId.isNotBlank()) "nmsg-${request.rumorId}"
 *                 else "nmsg-req-${request.eventId.ifBlank { System.nanoTime().toString() }}"
 * Reverting it (dropping the rumorId branch so the id always falls to the eventId branch) yields
 * "nmsg-req-<eventId>", which does NOT match observeNostrInbound's "nmsg-<rumorId>" — the accept
 * bubble would no longer correlate/dedup. This test reddens on exactly that revert.
 *
 * Isolation idiom: [ChatViewModel] has a 16-dependency, Context-bound constructor (AvatarStore /
 * DestroyManager are final Context-backed classes) and a heavy init{}. This module has no
 * mockk/Robolectric, so a real construction is not possible on the JVM. We instead allocate a bare
 * instance via sun.misc.Unsafe (skipping the constructor + init entirely), inject the accept-path
 * state, and own its real lifecycle scope. The trailing refresh starts and waits at the real
 * conversation-repair readiness barrier: these pins test accept identity, not full wallet reload. The
 * hand-written Proxy fake of ZchatPreferences answers ONLY the reads/writes that happy path performs
 * and throws loudly on anything else (mirrors GroupReconcileIdentityTest's strict-Proxy discipline).
 * All addresses/ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetestGap12AcceptRequestRumorIdTest {

    /**
     * Captures the single PendingMessageData that acceptMessageRequest persists, and answers ONLY the
     * ZchatPreferences calls its ACCEPT (non-refused) path makes. Any other call throws so the test
     * can never silently drift onto an unexercised branch.
     */
    private class AcceptCaptor {
        var captured: ZchatPreferences.PendingMessageData? = null

        fun prefs(): ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    // ── Accept guards: make all three refusals fall through to the bind+surface path ──
                    "isSelfAddress" -> false // not our own address
                    "getPeerNostrPubkey" -> null // address has no prior NOSTR binding
                    "findPeerByNostrPubkey" -> null // key not bound to a different address
                    // ── Bind + lock OPEN (writes; return Unit/void) ──
                    "setPeerNostrPubkey" -> null
                    "setPeerNostrRelay" -> null
                    "setConversationMode" -> null
                    // 0 (not < 0) so the known-rotation-index seed branch is skipped.
                    "getPeerKnownOurRotationIndex" -> 0
                    "getNostrRotationIndex" -> 0
                    "setPeerKnownOurRotationIndex" -> null
                    // Return value is discarded at the call site (ChatViewModel.kt:539).
                    "getOrCreateConversationId" -> null
                    "getConversationId" -> null
                    // ── The load-bearing capture: the persisted accept bubble ──
                    "addPendingMessage" -> {
                        captured = args!![0] as ZchatPreferences.PendingMessageData
                        null
                    }
                    "removeMessageRequest" -> null
                    "getMessageRequests" -> emptyList<ZchatPreferences.MessageRequest>()
                    "toString" -> "FakeZchatPreferences"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    else -> throw UnsupportedOperationException(
                        "unexpected ZchatPreferences call in acceptMessageRequest: ${method.name}"
                    )
                }
            } as ZchatPreferences
    }

    /**
     * A [ChatViewModel] allocated WITHOUT running its constructor/init (impossible to construct on the
     * JVM here — see class doc). A real lifecycle owner stores the refresh scope so teardown can join it.
     * The repair barrier is deliberately unresolved, as during startup before conversation repair.
     */
    private fun bareChatViewModel(
        prefs: ZchatPreferences,
        refreshState: MutableStateFlow<ChatListState>,
    ): ChatViewModel {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val vm =
            unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(theUnsafe, ChatViewModel::class.java) as ChatViewModel
        val impl =
            Class
                .forName("androidx.lifecycle.viewmodel.internal.ViewModelImpl")
                .getDeclaredConstructor()
                .newInstance()
        ViewModel::class.java
            .getDeclaredField("impl")
            .apply { isAccessible = true }
            .set(vm, impl)
        setField(vm, "repairComplete", CompletableDeferred<Unit>())
        setField(vm, "_chatListState", refreshState)
        setField(vm, "zchatPreferences", prefs)
        setField(vm, "noteCopy", TestChatNoteCopy)
        setField(vm, "pendingMessages", MutableStateFlow<List<ChatMessage>>(emptyList()))
        setField(vm, "_messageRequests", MutableStateFlow<List<ZchatPreferences.MessageRequest>>(emptyList()))
        return vm
    }

    private fun setField(target: Any, name: String, value: Any?) {
        ChatViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun request(rumorId: String) =
        ZchatPreferences.MessageRequest(
            senderNostrPubkeyHex = "npub-A",
            senderAddress = "u1peer",
            relayUrl = "wss://r",
            firstMessage = "hi",
            timestampMillis = 1_000L,
            eventId = "E1",
            rumorId = rumorId,
        )

    private suspend fun TestScope.withAcceptedRequest(rumorId: String, assertions: (AcceptCaptor) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val captor = AcceptCaptor()
            val refreshState = MutableStateFlow<ChatListState>(ChatListState.Loading)
            val vm = bareChatViewModel(captor.prefs(), refreshState)
            try {
                assertEquals(ChatViewModel.AcceptRequestResult.ACCEPTED, vm.acceptMessageRequest(request(rumorId)))
                runCurrent()
                val refreshJob =
                    ChatViewModel::class.java
                        .getDeclaredField("conversationLoadJob")
                        .apply { isAccessible = true }
                        .get(vm) as Job?
                assertNotNull("accept must launch the conversation refresh", refreshJob)
                assertTrue("refresh must wait for conversation repair", refreshJob!!.isActive)
                assertEquals("refresh must not fail while waiting for repair", ChatListState.Loading, refreshState.value)
                assertions(captor)
            } finally {
                // Cancel and JOIN while Main still points at this scheduler. No work escapes to a later test.
                vm.viewModelScope.coroutineContext.job
                    .cancelAndJoin()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `accept bubble id is keyed on the rumor id so it correlates with the inbound copy`() =
        runTest {
            withAcceptedRequest(rumorId = "R1") { captor ->
                val pending = captor.captured
                assertNotNull("acceptMessageRequest must persist the accept bubble via addPendingMessage", pending)
                // RED-ON-REVERT anchor: dropping the rumorId branch produces nmsg-req-E1 instead,
                // breaking correlation with the inbound copy and sender-attached reactions/replies.
                assertEquals("nmsg-R1", pending!!.id)
                assertNotEquals("nmsg-req-E1", pending.id)
            }
        }

    @Test
    fun `accept bubble falls back to the event id only when no rumor id is present`() =
        runTest {
            withAcceptedRequest(rumorId = "") { captor ->
                val pending = captor.captured
                assertNotNull(pending)
                // Legacy peers that send no rumor id: the id is keyed on the gift-wrap event id.
                assertEquals("nmsg-req-E1", pending!!.id)
            }
        }
}
