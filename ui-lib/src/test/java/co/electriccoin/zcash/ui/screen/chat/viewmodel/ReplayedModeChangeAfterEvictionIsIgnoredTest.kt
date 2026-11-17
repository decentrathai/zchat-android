package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * KI-24 (fix-all 2.13.0, train T4; SPECS §2.2 B7, §2.6 point 1), a guard, GREEN before and after the fix.
 *
 * The KI-24 design listed an old ZMODE (chat mode) or ZEXP (disappearing timer) control, replayed by a relay after its
 * event id left the seen list, as switching the chat back. That does not happen at 6e24d549f and must keep not
 * happening: each control carries its own "since" time, and the store accepts a control only when its since is newer
 * than the one it holds (advancePeerModeChangeSince, setDisappearingTtl; a per-contact value with no cap, so no
 * eviction reaches it). A replayed older control therefore changes nothing, whatever the seen list remembers.
 *
 * Drives the real handlers (handleModeControl, handleDisappearControl) with a store holding a newer since; the store is
 * a Proxy that keeps the monotonic rule and records every other call, so a handler that went past the refusal would
 * show. Every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayedModeChangeAfterEvictionIsIgnoredTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticki24modereplay00001"
        const val NEWER_SINCE = 1_785_000_000_000L
        const val OLDER_SINCE = NEWER_SINCE - 7L * 24 * 60 * 60 * 1000
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an old mode change replayed after its id was evicted leaves the chat mode as it is`() =
        runBlocking {
            val store = MonotonicStore(modeSince = NEWER_SINCE, ttlSince = NEWER_SINCE)
            val vm = HandshakeHarness.chatViewModelWith(store.proxy, HandshakeSeam(PEER))

            invoke(vm, "handleModeControl", ZMSGProtocol.createModeChange(ConversationMode.TUNNEL, OLDER_SINCE, PEER))

            assertEquals("the handler reached the store's since check", listOf(OLDER_SINCE), store.modeSinceAsked)
            assertEquals("an older control must change nothing", emptyList<String>(), store.otherCalls)
        }

    @Test
    fun `an old disappearing-timer setting replayed after its id was evicted is not applied again`() =
        runBlocking {
            val store = MonotonicStore(modeSince = NEWER_SINCE, ttlSince = NEWER_SINCE)
            val vm = HandshakeHarness.chatViewModelWith(store.proxy, HandshakeSeam(PEER))

            invoke(vm, "handleDisappearControl", ZMSGProtocol.createDisappearSetting(3_600L, OLDER_SINCE, PEER))

            assertEquals("the handler reached the store's since check", listOf(OLDER_SINCE), store.ttlSinceAsked)
            assertEquals("an older control must change nothing", emptyList<String>(), store.otherCalls)
        }

    private fun invoke(
        vm: ChatViewModel,
        name: String,
        memo: String,
    ) {
        val method =
            ChatViewModel::class.java
                .getDeclaredMethod(name, String::class.java, String::class.java)
                .apply { isAccessible = true }
        try {
            method.invoke(vm, PEER, memo)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** The store's monotonic since rule for the two controls; every other call is recorded. */
    private class MonotonicStore(
        @Volatile var modeSince: Long,
        @Volatile var ttlSince: Long,
    ) {
        val modeSinceAsked: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val ttlSinceAsked: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val otherCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                val a = args ?: emptyArray()
                when (method.name) {
                    "advancePeerModeChangeSince" -> {
                        val since = a[1] as Long
                        modeSinceAsked += since
                        (since > modeSince).also { if (it) modeSince = since }
                    }
                    "setDisappearingTtl" -> {
                        val since = a[2] as Long
                        ttlSinceAsked += since
                        (since > ttlSince).also { if (it) ttlSince = since }
                    }
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a.getOrNull(0)
                    "toString" -> "KI24MonotonicStore"
                    else -> {
                        otherCalls += method.name
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Long.TYPE -> 0L
                            java.lang.Integer.TYPE -> 0
                            else -> null
                        }
                    }
                }
            } as ZchatPreferences
    }
}
