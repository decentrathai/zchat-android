package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M2 (fix-all 2.13.0, train T5b): a Shielded chat with a contact we hold no key for, as the chat-detail send meets it
 * (C-M2-1: an Open chat switched to Shielded, a chat opened from the share picker or from a request). Built on
 * [PaidChatFixture] (the real ChatViewModel send path, the real use case and repository over [HandshakeSeam]); this
 * wrapper adds a draft store, the owed-KEX marks (recording every write) and the plan check under test.
 */
internal class FirstMessagePlanChat(
    val peer: String,
    haveOurKey: Boolean = false,
) {
    val fixture = PaidChatFixture(peer, ConversationMode.VAULT, haveOurKey = haveOurKey)
    val drafts = ConcurrentHashMap<String, String>()
    val kexOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val kexOwedWrites: MutableList<Pair<String, Boolean>> = CopyOnWriteArrayList()

    /** Payments the send asked the SDK to build (each one would be paid). */
    fun proposals(): Int = fixture.seam.proposalRequests.get()

    /** Whether our end-to-end key for the contact exists (a refused send must not create it). */
    fun keyCreated(): Boolean = prefs.getE2EOurPublicKey(peer) != null

    /** Whether a send wrote the owed-KEX mark (a refused send must write nothing). */
    fun owedMarkWritten(): Boolean = kexOwedWrites.any { it.second }

    fun storedRow(id: String): ZchatPreferences.PendingMessageData? = prefs.getPendingMessages().firstOrNull { it.id == id }

    val prefs: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "setDraft" -> {
                    drafts[args!![0] as String] = args[1] as String
                    null
                }
                "clearDraft" -> {
                    drafts.remove(args!![0] as String)
                    null
                }
                "getDraft" -> drafts[args!![0] as String]
                "getAllDrafts" -> HashMap(drafts)
                "setKexOwed" -> {
                    val owed = args!![1] as Boolean
                    kexOwedWrites += (args[0] as String) to owed
                    if (owed) kexOwed += args[0] as String else kexOwed -= args[0] as String
                    true
                }
                "isKexOwed" -> args!![0] in kexOwed
                "getKexOwedPeers" -> HashSet(kexOwed)
                "setKexOwedAccount" -> true
                "getKexOwedAccount" -> null
                "toString" -> "FirstMessagePlanChat"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else ->
                    try {
                        method.invoke(fixture.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    /** A chat screen's ViewModel whose plan check is [check] (null: the field left as an unset allocation leaves it). */
    suspend fun viewModel(
        check: FirstMessagePlanApi.ScriptedCheck?,
        leaveCheckNull: Boolean = false,
    ): ChatViewModel {
        val vm = fixture.viewModel()
        PaymentHarness.setField(vm, "zchatPreferences", prefs)
        // The owed KEX belongs to the selected account (a ZChat account here).
        PaymentHarness.setField(vm, "accountDataSource", Zc101FakeAccountDataSource(syntheticZashiAccount()))
        ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "_restoredTypedText" }?.let {
            it.isAccessible = true
            it.set(vm, MutableStateFlow<Any?>(null))
        }
        if (leaveCheckNull) {
            FirstMessagePlanApi.install(vm, null)
        } else {
            FirstMessagePlanApi.install(vm, check?.proxyOrNull())
        }
        return vm
    }

    /** What the chat screen does on Send: the typed text is the draft; an accepted send clears it. */
    suspend fun typeAndSend(
        vm: ChatViewModel,
        text: String,
    ): Boolean {
        vm.saveDraft(peer, text)
        val accepted = vm.sendMessage(peer, text)
        if (accepted) vm.clearDraft(peer)
        awaitIdle(vm)
        return accepted
    }

    /** True when the first-message question is on screen. */
    fun asked(vm: ChatViewModel): Boolean =
        HandshakeHarness.sendMessageState(vm).javaClass.simpleName == "NeedsPaidActionConfirmation"

    /** A FAILED first message to [peer] whose submit never started (no key, nothing paid), on disk and on screen. */
    fun seedFailedFirstMessage(
        vm: ChatViewModel,
        id: String,
        text: String,
    ) {
        val at = Instant.now()
        prefs.commitPendingMessage(
            ZchatPreferences.PendingMessageData(
                id = id,
                text = text,
                timestampMillis = at.toEpochMilli(),
                peerAddress = peer,
                isPending = false,
                status = MessageStatus.FAILED.name,
                sendAmountZatoshi = 1_000L,
                submitTracked = true,
            ),
        )
        PaymentHarness.setField(
            vm,
            "pendingMessages",
            MutableStateFlow(
                listOf(
                    ChatMessage(
                        id = id,
                        txId = null,
                        text = text,
                        timestamp = at,
                        isOutgoing = true,
                        peerAddress = peer,
                        isPending = false,
                        status = MessageStatus.FAILED,
                        sendAmountZatoshi = 1_000L,
                    ),
                ),
            ),
        )
    }

    /** A new block: the ViewModel runs its per-block work (the owed-KEX retry among it). */
    fun tick(
        vm: ChatViewModel,
        height: Long,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, height)
    }

    companion object {
        /** The restored text handed back to the chat input, as (peer, text), or null. */
        fun restored(vm: ChatViewModel): Pair<Any?, Any?>? {
            val flow = ChatViewModel::class.java.getMethod("getRestoredTypedText").invoke(vm) as StateFlow<*>
            val value = flow.value ?: return null
            return value.javaClass.getMethod("getPeerAddress").invoke(value) to value.javaClass.getMethod("getText").invoke(value)
        }

        /** The error the send shows, or null when it shows none. */
        fun error(vm: ChatViewModel): String? = (HandshakeHarness.sendMessageState(vm) as? SendMessageState.Error)?.message

        /** A ChatSendCopy constant by name (the M2 texts do not exist at HEAD). */
        fun copy(name: String): String =
            runCatching { ChatSendCopy::class.java.getField(name).get(null) as String }
                .getOrElse { throw AssertionError("ChatSendCopy.$name does not exist (M2)") }

        /** Clears what an earlier test left in ChatViewModel's process-wide owed-KEX bookkeeping. */
        fun resetOwedKexState() {
            for (name in listOf("kexOwedPeers", "owedKexLocks", "owedKexPlanLogged", "zbootWorkByScope")) {
                val field =
                    ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) }
                        ?: continue
                field.isAccessible = true
                (field.get(null) as? MutableCollection<*>)?.clear()
                (field.get(null) as? MutableMap<*, *>)?.clear()
            }
        }
    }
}
