package co.electriccoin.zcash.ui.service

import co.electriccoin.zcash.ui.call.CallSignalEnvelope
import co.electriccoin.zcash.ui.call.CallSignalType
import co.electriccoin.zcash.ui.common.notification.InAppNotification
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * Round 9 R9-6 (REVIEW_R8 F6 = device D-F6) — a message that arrives over the relay (Open and Tunnel chats, the free
 * default) raises no notification while the app is in the background. Round 8 devices: 2 of 2 background Open-mode
 * messages produced none, under both privacy levels tried.
 *
 * At 8ecb482aa SyncForegroundService hands every relay DM to NostrChatBridge.dispatch and posts nothing; its chat
 * notification (postIncomingChatNotification) serves only on-chain ReceiveTransaction, and NostrChatBridge notifies
 * only for contact requests.
 *
 * Wanted: after dispatch routes a message from a mapped contact, it calls a chat notifier the service registers
 * (NostrChatBridge.registerChatNotifier). The service's notifier (RelayMessageNotifier, over the same privacy / mute /
 * foreground rules as the on-chain alert, see chatAlert in SyncForegroundService.kt) produces:
 *  - in the background, exactly one system notification whose text follows NotificationPrivacy: Full preview names
 *    the contact and shows the text; Sender only names the contact and shows "Open ZCHAT to read"; New message
 *    (hidden) shows "New ZCHAT message" and "Open ZCHAT to read"; Silent nothing;
 *  - nothing for a replay of the same event id (the relay replays stored wraps on every resubscribe);
 *  - nothing for a control payload (ZMODE, KEX, ZCALL, ZEXP, reaction, read receipt, status);
 *  - nothing for a muted conversation;
 *  - with the app in the foreground, no system notification: the in-app banner instead, as on-chain.
 *
 * The notifier and the bridge hook are reached by name, so this file compiles before they exist. Pure JVM: the
 * bridge is a flow singleton, android.util.Log is stubbed (unitTests.isReturnDefaultValues), and the preferences
 * store is a Proxy. All names, keys and addresses are synthetic.
 */
class RelayMessageNotifiesTest {
    private companion object {
        const val SENDER_PUBKEY = "93c9d47100f8aa11bb22cc33dd44ee55ff6677889900aabbccddeeff00112233"
        const val STRANGER_PUBKEY = "0f0e0d0c0b0a09080706050403020100f0e0d0c0b0a090807060504030201000"
        const val PEER = "usyntheticr96relaypeer00000001"
        const val NAME = "Synthetic Alice"
        const val TEXT = "synthetic relay message text"

        // The strings the on-chain system notification already uses for these levels.
        const val FALLBACK = "Open ZCHAT to read"
        const val GENERIC_TITLE = "New ZCHAT message"

        const val NOTIFIER_CLASS = "co.electriccoin.zcash.ui.service.RelayMessageNotifier"
    }

    private var collector: Job? = null

    @After
    fun tearDown() {
        collector?.cancel()
        runCatching { NostrChatBridge::class.java.getMethod("unregisterChatNotifier").invoke(NostrChatBridge) }
    }

    // ---------------------------------------------------------------- background: one notification per privacy level

    @Test
    fun `full preview - a relay message in the background notifies once with the contact's name and the text`() =
        runBlocking {
            val alerts = run(Prefs(privacy = NotificationPrivacy.FULL_PREVIEW), dm("evt-full", TEXT))

            val system = systemAlerts(alerts)
            assertEquals("a background relay message must raise exactly one notification", 1, system.size)
            assertEquals(NAME, text(system.single(), "title"))
            assertTrue("the full preview must show the text", TEXT in text(system.single(), "body"))
        }

    @Test
    fun `sender only - the notification names the contact but never shows the text`() =
        runBlocking {
            val alerts = run(Prefs(privacy = NotificationPrivacy.SENDER_ONLY), dm("evt-sender", TEXT))

            val system = systemAlerts(alerts)
            assertEquals(1, system.size)
            assertTrue("sender only must name the contact", NAME in text(system.single(), "title"))
            assertEquals(FALLBACK, text(system.single(), "body"))
            assertNoText(system.single())
        }

    @Test
    fun `new message (hidden) - the notification names neither the contact nor the text`() =
        runBlocking {
            val alerts = run(Prefs(privacy = NotificationPrivacy.NEW_MESSAGE), dm("evt-hidden", TEXT))

            val system = systemAlerts(alerts)
            assertEquals(1, system.size)
            assertEquals(GENERIC_TITLE, text(system.single(), "title"))
            assertEquals(FALLBACK, text(system.single(), "body"))
            assertNoText(system.single())
            assertFalse("the hidden level must not name the contact", NAME in text(system.single(), "title"))
        }

    @Test
    fun `silent - nothing`() =
        runBlocking {
            val alerts = run(Prefs(privacy = NotificationPrivacy.SILENT), dm("evt-silent", TEXT))

            assertEquals(emptyList<Any>(), alerts)
        }

    // ---------------------------------------------------------------- replays, controls, mute, foreground

    @Test
    fun `a replay of the same event id notifies nothing more`() =
        runBlocking {
            val prefs = Prefs(privacy = NotificationPrivacy.FULL_PREVIEW)
            // No chat screen marks the event seen (the ViewModel is not running), so dispatch hands the replay on.
            val alerts = run(prefs, dm("evt-replayed", TEXT), dm("evt-replayed", TEXT), dm("evt-replayed", TEXT))

            assertEquals("three copies of one event must notify once", 1, systemAlerts(alerts).size)
        }

    @Test
    fun `a replay the chat already marked seen notifies nothing`() =
        runBlocking {
            val prefs = Prefs(privacy = NotificationPrivacy.FULL_PREVIEW, seen = setOf("evt-seen"))

            assertEquals(emptyList<Any>(), run(prefs, dm("evt-seen", TEXT)))
        }

    @Test
    fun `control payloads notify nothing`() =
        runBlocking {
            val now = System.currentTimeMillis()
            val controls =
                listOf(
                    "ZMODE" to ZMSGProtocol.createModeChange(ConversationMode.OPEN, now, PEER),
                    "KEX" to ZMSGProtocol.createV4KEXMessage("R96CONV1", PEER, "synthetic-kex-payload"),
                    "ZCALL" to
                        CallSignalEnvelope(
                            callId = "0123456789abcdef0123456789abcdef",
                            type = CallSignalType.HANGUP,
                            payload = ""
                        ).serialize(),
                    "ZCALL (unparseable)" to "${CallSignalEnvelope.PREFIX}not-a-signal",
                    "ZEXP" to ZMSGProtocol.createDisappearSetting(60L, now, PEER),
                    "reaction" to ZMSGProtocol.createReaction("nmsg-target", "👍", PEER),
                    "read receipt" to ZMSGProtocol.createReadReceipt("nmsg-target", PEER),
                    "status" to ZMSGProtocol.createStatusMessage("synthetic status", PEER),
                )
            for ((i, control) in controls.withIndex()) {
                val alerts = run(Prefs(privacy = NotificationPrivacy.FULL_PREVIEW), dm("evt-control-$i", control.second))
                assertEquals("a ${control.first} control must not notify", emptyList<Any>(), alerts)
            }
        }

    @Test
    fun `a muted conversation notifies nothing`() =
        runBlocking {
            val alerts = run(Prefs(privacy = NotificationPrivacy.FULL_PREVIEW, muted = true), dm("evt-muted", TEXT))

            assertEquals(emptyList<Any>(), alerts)
        }

    @Test
    fun `with the app in the foreground there is no system notification, only the in-app banner as on-chain`() =
        runBlocking {
            val alerts = run(Prefs(privacy = NotificationPrivacy.SENDER_ONLY), dm("evt-fg", TEXT), foreground = true)

            assertEquals("no system notification while the app is open", emptyList<Any>(), systemAlerts(alerts))
            val banners =
                alerts.mapNotNull { a ->
                    a.javaClass.methods
                        .firstOrNull { it.name == "getNotification" }
                        ?.invoke(a)
                }
            assertEquals(listOf(InAppNotification(NAME, FALLBACK, PEER)), banners)
        }

    @Test
    fun `control - a message from an unknown key notifies nothing`() =
        runBlocking {
            val alerts = run(Prefs(privacy = NotificationPrivacy.FULL_PREVIEW), dm("evt-stranger", TEXT, sender = STRANGER_PUBKEY))

            assertEquals(emptyList<Any>(), alerts)
        }

    // ---------------------------------------------------------------- added with the fix (R9-6)

    @Test
    fun `with that chat open on screen in the foreground nothing is raised, and behind the lock screen it still notifies`() =
        runBlocking {
            co.electriccoin.zcash.ui.common.notification.OpenChat
                .opened(PEER)
            try {
                val foreground = run(Prefs(privacy = NotificationPrivacy.FULL_PREVIEW), dm("evt-open-fg", TEXT), foreground = true)
                assertEquals("a message for the open chat shows in it: no banner, no notification", emptyList<Any>(), foreground)

                val background = run(Prefs(privacy = NotificationPrivacy.FULL_PREVIEW), dm("evt-open-bg", TEXT))
                assertEquals("a chat left open in the background must still notify", 1, systemAlerts(background).size)
            } finally {
                co.electriccoin.zcash.ui.common.notification.OpenChat
                    .closed(PEER)
            }
        }

    @Test
    fun `a first Open message in an INIT envelope and a reply show only their text, a file only its label`() =
        runBlocking {
            val init = ZMSGProtocol.createV4InitMessage("R96CONV1", PEER, TEXT)
            val reply = "\u0001RPL:nmsg-quoted\u001fsynthetic quoted text\u0001$TEXT"
            for ((what, content) in listOf("INIT" to init, "reply" to reply)) {
                val alerts = systemAlerts(run(Prefs(privacy = NotificationPrivacy.FULL_PREVIEW), dm("evt-shape-$what", content)))
                assertEquals("$what: one notification", 1, alerts.size)
                assertEquals("$what: the notification must show only the message text", TEXT, text(alerts.single(), "body"))
            }
            val file =
                co.electriccoin.zcash.ui.screen.chat.model
                    .ZFILEMessage(
                        "0123456789abcdef0123456789abcdef",
                        co.electriccoin.zcash.ui.screen.chat.model.ZFILEType.JPEG,
                        2048,
                        "https://blossom.primal.net/synthetic",
                        "synthetic-key",
                        "",
                    ).serialize()
            val fileAlert = systemAlerts(run(Prefs(privacy = NotificationPrivacy.FULL_PREVIEW), dm("evt-shape-file", file))).single()
            assertTrue("a file shows its label", text(fileAlert, "body").startsWith("\uD83D\uDCCE"))
            assertFalse("a file must never show the raw ZFILE string", "ZFILE|" in text(fileAlert, "body"))
        }

    // ---------------------------------------------------------------- fixture

    /** The preferences the dispatch + notifier path reads; anything else answers the type's empty value. */
    private class Prefs(
        val privacy: NotificationPrivacy,
        val muted: Boolean = false,
        seen: Set<String> = emptySet(),
    ) {
        val seenIds: MutableSet<String> = Collections.synchronizedSet(seen.toMutableSet())

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                val a = args ?: emptyArray()
                when (method.name) {
                    "findPeerByNostrPubkey" -> if (a[0] == SENDER_PUBKEY) PEER else null
                    "hasSeenNostrEvent" -> a[0] in seenIds
                    "markNostrEventSeen" -> {
                        seenIds += a[0] as String
                        null
                    }
                    "getNotificationPrivacy" -> privacy
                    "getDisplayName" -> if (a[0] == PEER) NAME else "u1synthetic…"
                    "isConversationMuted" -> muted && a[0] == PEER
                    "getConversationMode" -> ConversationMode.OPEN
                    "resolvePeerAddress" -> a[0] // ZC-1883: the receive gate resolves aliases; no alias here
                    "isNostrPubkeyBlocked" -> false
                    "isNotificationSoundEnabled", "isNotificationVibrationEnabled" -> false
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a.getOrNull(0)
                    "toString" -> "RelayNotifyPrefs"
                    else -> emptyValue(method.returnType)
                }
            } as ZchatPreferences

        private fun emptyValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                List::class.java -> emptyList<Any>()
                Set::class.java -> emptySet<Any>()
                Map::class.java -> emptyMap<Any, Any>()
                else -> null
            }
    }

    private fun dm(
        eventId: String,
        content: String,
        sender: String = SENDER_PUBKEY,
    ) = NostrInboxManager.InboundDm(
        senderPubkeyHex = sender,
        content = content,
        createdAtSec = System.currentTimeMillis() / 1000,
        eventId = eventId,
        rumorId = "rumor-$eventId",
    )

    /**
     * Registers the service's notifier (a fresh one, as a service start makes it) with the bridge, runs every [dms]
     * through the real NostrChatBridge.dispatch with a live chat collector, and returns what the notifier delivered.
     */
    private suspend fun run(
        prefs: Prefs,
        vararg dms: NostrInboxManager.InboundDm,
        foreground: Boolean = false,
    ): List<Any> =
        kotlinx.coroutines.coroutineScope {
            val delivered = Collections.synchronizedList(mutableListOf<Any>())
            val notifier = newNotifier(prefs.proxy, foreground) { delivered += it }
            register(notifier)
            val received = Collections.synchronizedList(mutableListOf<NostrChatBridge.InboundChat>())
            collector =
                launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    NostrChatBridge.inbound.collect { received += it }
                }
            for (dm in dms) NostrChatBridge.dispatch(dm, prefs.proxy)
            repeat(100) { yield() }
            collector?.cancel()
            delivered.toList()
        }

    private fun newNotifier(
        prefs: ZchatPreferences,
        foreground: Boolean,
        deliver: (Any) -> Unit,
    ): Any {
        val cls =
            runCatching { Class.forName(NOTIFIER_CLASS) }.getOrNull()
                ?: throw AssertionError("$NOTIFIER_CLASS is not in this build: nothing notifies a relay message")
        val ctor =
            cls.declaredConstructors.firstOrNull { it.parameterCount == 3 }
                ?: throw AssertionError("RelayMessageNotifier(prefs, isInForeground, deliver) is not in this build")
        ctor.isAccessible = true
        val isInForeground: () -> Boolean = { foreground }
        return try {
            ctor.newInstance(prefs, isInForeground, deliver)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun register(notifier: Any) {
        val onInbound =
            notifier.javaClass.methods.firstOrNull { it.name == "onInbound" && it.parameterCount == 1 }
                ?: throw AssertionError("RelayMessageNotifier.onInbound(InboundChat) is not in this build")
        val register =
            NostrChatBridge::class.java.methods.firstOrNull { it.name == "registerChatNotifier" && it.parameterCount == 1 }
                ?: throw AssertionError("NostrChatBridge.registerChatNotifier is not in this build: dispatch notifies nothing")
        val hook: (NostrChatBridge.InboundChat) -> Unit = { chat ->
            try {
                onInbound.invoke(notifier, chat)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        register.invoke(NostrChatBridge, hook)
    }

    private fun systemAlerts(alerts: List<Any>): List<Any> = alerts.filter { a -> a.javaClass.methods.any { it.name == "getTitle" } }

    private fun text(
        alert: Any,
        property: String,
    ): String {
        val getter = alert.javaClass.methods.firstOrNull { it.name == "get" + property.replaceFirstChar(Char::uppercase) }
        assertNotNull("the notification has no $property", getter)
        return getter!!.invoke(alert) as String
    }

    private fun assertNoText(alert: Any) {
        for (getter in alert.javaClass.methods.filter {
            it.name.startsWith("get") &&
                it.parameterCount == 0 &&
                it.returnType == String::class.java
        }) {
            val value = getter.invoke(alert) as String? ?: continue
            assertFalse("${getter.name} shows the message text at this privacy level", TEXT in value)
        }
    }
}
