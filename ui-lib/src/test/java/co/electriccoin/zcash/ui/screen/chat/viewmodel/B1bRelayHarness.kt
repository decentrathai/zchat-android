package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * OT213n batch B1b test support (ZC-1464, ZC-1465, ZC-1480 item 2): a preferences wrapper that records every call by
 * name and hands it on to [inner], so a test can ask whether the code under test reached a store method that does not
 * exist yet (the fix adds it to [ZchatPreferences]; before the fix no such call can happen, so the test fails on its
 * assertion and still compiles). [answer] may answer a call first; [PASS] hands it on. Every value is synthetic.
 */
internal class CallLogPrefs(
    private val inner: ZchatPreferences,
    private val answer: (String, Array<Any?>) -> Any? = { _, _ -> PASS },
) {
    /** Every call as (method name, arguments), in order. */
    val calls: MutableList<Pair<String, List<Any?>>> = Collections.synchronizedList(mutableListOf())

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            val a: Array<Any?> = args ?: emptyArray()
            when (method.name) {
                "hashCode" -> return@newProxyInstance System.identityHashCode(p)
                "equals" -> return@newProxyInstance p === a.getOrNull(0)
                "toString" -> return@newProxyInstance "CallLogPrefs"
            }
            calls += method.name to a.toList()
            val answered = answer(method.name, a)
            if (answered !== PASS) return@newProxyInstance answered
            try {
                method.invoke(inner, *a)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ZchatPreferences

    /** The distinct method names called, in first-call order (for failure messages). */
    fun names(): List<String> = synchronized(calls) { calls.map { it.first }.distinct() }

    /** The calls whose method name matches [name]. */
    fun named(name: Regex): List<Pair<String, List<Any?>>> = synchronized(calls) { calls.filter { name.containsMatchIn(it.first) } }

    companion object {
        val PASS = Any()
    }
}

internal object B1bRelay {
    /** A live ChatViewModel over [prefs] (the handshake harness allocation), ready for its relay reader. */
    suspend fun chatViewModel(prefs: ZchatPreferences): ChatViewModel {
        val vm = HandshakeHarness.chatViewModelWith(prefs, HandshakeSeam(RelayKeyBinding.SELF))
        AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow<List<ChatMessage>>(emptyList()))
        AutoSpendVm.setField(vm, "_messageRequests", MutableStateFlow<List<ZchatPreferences.MessageRequest>>(emptyList()))
        AutoSpendVm.setField(vm, "replyRefSentinel", '\u0001')
        AutoSpendVm.setField(vm, "replyRefPrefix", "\u0001RPL:")
        AutoSpendVm.setField(vm, "replyPreviewSep", '\u001f')
        return vm
    }

    @Suppress("UNCHECKED_CAST")
    fun pending(vm: ChatViewModel): MutableStateFlow<List<ChatMessage>> =
        ChatViewModel::class.java
            .getDeclaredField("pendingMessages")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<List<ChatMessage>>

    fun dm(
        senderKey: String,
        content: String,
        id: String,
    ) = NostrInboxManager.InboundDm(
        senderPubkeyHex = senderKey,
        content = content,
        createdAtSec = System.currentTimeMillis() / 1000,
        eventId = id,
        rumorId = "rumor-$id",
    )

    /** Waits up to [ms] for [done]; returns whether it came true. */
    suspend fun awaitTrue(
        ms: Long = 3_000,
        done: () -> Boolean,
    ): Boolean =
        withTimeoutOrNull(ms) {
            while (!done()) delay(20)
            true
        } ?: false
}
