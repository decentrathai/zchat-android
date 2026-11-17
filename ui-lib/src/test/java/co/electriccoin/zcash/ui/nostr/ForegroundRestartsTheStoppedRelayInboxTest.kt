package co.electriccoin.zcash.ui.nostr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phone finding of train T6b (Fold, Android 15, a fresh wallet, 15 Oct 12:30-12:40) — after about a minute in the
 * background the relays never come back.
 *
 * On the phone: the app went to the background at 12:30:04; at 12:31:04 Android destroyed SyncForegroundService ("Service
 * destroyed"), whose onDestroy stops the relay inbox and unregisters the relay publisher; the app's network was then
 * blocked (APP_BACKGROUND). Back in the foreground no relay REQ was sent (the last one was at 12:29:51): the contact's
 * two messages did not arrive and the next send failed, until a force-stop and a relaunch.
 *
 * The app's only return-to-foreground hook is [NostrChatBridge.refreshInbox] (ZcashApplication, on every process start).
 * It reaches the inbox through the refresher the running service registers, so with the service gone it does nothing,
 * and the service is started only once, when the wallet first reads as ready (MainActivity). ZC-761 / ZC-959 (a
 * subscription whose REQ never left) do not reach this case: the whole relay pool is stopped with the service.
 *
 * Wanted: the foreground hook starts the stopped inbox (the sync service) through a starter the app registers while a
 * wallet is ready; a running inbox is only refreshed, as before, and no starter means nothing is started.
 */
class ForegroundRestartsTheStoppedRelayInboxTest {
    private fun bridgeMethod(name: String): Method? =
        NostrChatBridge::class.java.methods.firstOrNull { it.name == name && it.parameterCount == 1 }

    private fun registerStarter(fn: () -> Unit) {
        val register =
            bridgeMethod("registerInboxStarter")
                ?: throw AssertionError(
                    "the app's return-to-foreground hook (NostrChatBridge.refreshInbox) has no way to start a relay inbox " +
                        "whose service Android stopped in the background: nothing re-subscribes until a force-stop",
                )
        register.invoke(NostrChatBridge, fn)
    }

    private fun unregisterStarter() {
        NostrChatBridge::class.java.methods
            .firstOrNull { it.name == "unregisterInboxStarter" && it.parameterCount == 0 }
            ?.invoke(NostrChatBridge)
    }

    @Before
    fun setUp() {
        NostrChatBridge.unregisterInboxRefresher()
        unregisterStarter()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterInboxRefresher()
        unregisterStarter()
    }

    @Test
    fun `with the service stopped the foreground hook starts the inbox again`() {
        val starts = AtomicInteger()
        registerStarter { starts.incrementAndGet() }
        // The service is gone: its onDestroy unregistered the refresher.
        NostrChatBridge.refreshInbox()
        assertEquals("back in the foreground with the relay inbox stopped, nothing started it", 1, starts.get())
    }

    @Test
    fun `with the inbox running the foreground hook only refreshes it`() {
        val starts = AtomicInteger()
        val refreshes = AtomicInteger()
        registerStarter { starts.incrementAndGet() }
        NostrChatBridge.registerInboxRefresher { refreshes.incrementAndGet() }
        NostrChatBridge.refreshInbox()
        assertEquals(1, refreshes.get())
        assertEquals("a running inbox was started a second time", 0, starts.get())
    }
}
