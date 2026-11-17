package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZPROFMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/**
 * SR-B1 (fix-all 2.13.0, train T4c; photos-privacy SPEC §2 with the review's SR-B1 changes): our own avatar, and its
 * removal, go over the relays only to contacts whose chat is not Shielded; a Shielded contact gets it once the chat
 * leaves Shielded.
 *
 * At 78128221f `broadcastSelfAvatarToContacts` and `broadcastSelfAvatarRemoveToContacts` send ZPROF to every contact
 * with a stored relay key, in any mode, and one global marker ("at least one contact acked") decides whether the launch
 * tick sends again. So a Shielded contact receives repeated, timed relay traffic, and a contact that leaves Shielded
 * after the marker was set never gets the avatar.
 *
 * Wanted (the review's preferred shape): a per-contact "delivered avatar version"; every broadcast and the launch tick
 * send to each contact with a relay key whose mode is not Shielded (read at send time) and whose delivered version is
 * not the current set or removal version; the hooks in `changeConversationMode`, the `routeIncomingBoot` Tunnel adopt
 * and the `handleModeControl` adopt only make delivery faster; the launch tick catches every other mode writer (M1's
 * relay-only Open default is the case here). On upgrade the map is seeded from the old marker for contacts that are
 * not Shielded, so nothing is re-sent in bulk.
 *
 * New names are reached by name only (the launch tick `observeSelfAvatarDelivery`), so this file compiles before the
 * fix. Everything is sent through a recording relay publisher; nothing is paid. All addresses and keys are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldedContactsGetNoAvatarOverRelaysTest {
    private val sendScopes = SendHarnessScopes()
    private val published: MutableList<Publish> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var acks: (recipient: String, text: String) -> Int = { _, _ -> 1 }

    private data class Publish(
        val recipient: String,
        val text: String,
        val acks: Int,
    )

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.registerPublisher { text, recipient ->
            val n = acks(recipient, text)
            published += Publish(recipient, text, n)
            NostrChatBridge.PublishResult(n, "synthetic-srb1-${UUID.randomUUID()}")
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
    }

    // ── fixture ──────────────────────────────────────────────────────────────────────────────────────

    /** A files folder in a temp directory; the avatar store's encrypted index is replaced by an in-memory file. */
    private class AvatarContext : ContextWrapper(null) {
        val dir: File = Files.createTempDirectory("zchat-srb1-avatars").toFile().apply { deleteOnExit() }

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = dir
    }

    private class Avatars {
        val context = AvatarContext()
        val prefs = MemorySharedPreferences()
        val store: AvatarStore =
            AvatarStore(context).apply {
                javaClass.getDeclaredField("prefs\$delegate").apply { isAccessible = true }.set(this, lazyOf<SharedPreferences>(prefs))
            }

        /** Our photo is set at [version] (the index entry AvatarStore.setSelfAvatar writes, without the bitmap step). */
        fun set(
            version: Long,
            jpeg: ByteArray,
        ) {
            File(File(context.dir, "avatars").apply { mkdirs() }, "self.jpg").writeBytes(jpeg)
            prefs
                .edit()
                .putString("self", JSONObject().put("file", "self.jpg").put("updatedAt", version).toString())
                .remove("self_removed_at")
                .commit()
        }

        /** Our photo is removed at [version] (the tombstone AvatarStore.removeSelfAvatar writes). */
        fun remove(version: Long) {
            File(File(context.dir, "avatars"), "self.jpg").delete()
            prefs
                .edit()
                .remove("self")
                .putLong("self_removed_at", version)
                .commit()
        }

        /** The old global marker ("at least one contact acked this version"). */
        fun marker(): Long = store.getLastBroadcastSelfAt()
    }

    private class Contacts {
        val store = RelayKeyStore()
        val peers: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

        // ZC-1516: a paid Tunnel adoption needs its "moved by the contact" mark on disk; this store keeps it.
        val moved: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getAllConversationPeerAddresses" -> synchronized(peers) { LinkedHashSet(peers) }
                    "advancePeerModeChangeSince" -> true
                    "getDisplayName" -> "synthetic contact"
                    "setModeMovedToPaidByPeer" -> {
                        if (args!![1] as Boolean) moved += args[0] as String else moved -= args[0] as String
                        Unit
                    }
                    "getModeSwitchRecord" -> if (args!![0] as String in moved) ZchatPreferences.ModeSwitchRecord(0L, true) else null
                    else ->
                        try {
                            method.invoke(store.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences

        fun add(
            address: String,
            key: String?,
            mode: ConversationMode?,
            explicit: Boolean = false,
        ) {
            peers += address
            if (key != null) store.pubkeys[address] = key
            if (mode != null) store.modes[address] = mode
            if (explicit) store.explicitModes += address
        }
    }

    private class Phone(
        val vm: ChatViewModel,
        val contacts: Contacts,
        val avatars: Avatars,
    )

    /** The five contacts of SPEC §2.2: S (Shielded, pinned), S0 (Shielded, not pinned), T (Tunnel), O (Open), U (no key). */
    private suspend fun phone(
        s0Key: String = KEY_S0,
        extra: (Contacts) -> Unit = {},
    ): Phone {
        val contacts =
            Contacts().apply {
                add(S, KEY_S, ConversationMode.VAULT, explicit = true)
                add(S0, s0Key, ConversationMode.VAULT)
                add(T, KEY_T, ConversationMode.TUNNEL)
                add(O, KEY_O, ConversationMode.OPEN)
                add(U, null, ConversationMode.VAULT)
                extra(this)
            }
        val avatars = Avatars()
        val vm = HandshakeHarness.chatViewModelWith(contacts.proxy, HandshakeSeam(RelayKeyBinding.SELF))
        AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow<List<ChatMessage>>(emptyList()))
        AutoSpendVm.setField(vm, "replyRefSentinel", '\u0001')
        AutoSpendVm.setField(vm, "replyRefPrefix", "\u0001RPL:")
        AutoSpendVm.setField(vm, "replyPreviewSep", '\u001f')
        AutoSpendVm.setField(vm, "avatarStore", avatars.store)
        // ZC-269: handleModeControl (B1-9) calls loadConversations(), whose coroutine on the view model's scope ends in
        // its error path writing _chatListState; a constructor-free allocation leaves that null, so the NPE escaped into
        // the next test's runTest (SendAllStateTest). Set it the way PaymentSendHarness does.
        AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
        AutoSpendVm.wireBootstrapFields(vm)
        // The per-instance guard at 78128221f (a constructor-free allocation leaves it null).
        setFieldIfPresent(vm, "avatarBroadcastInFlight") {
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        }
        return Phone(vm, contacts, avatars)
    }

    private fun setFieldIfPresent(
        vm: ChatViewModel,
        name: String,
        value: () -> Any,
    ) {
        val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name } ?: return
        field.isAccessible = true
        field.set(vm, value())
    }

    private fun zprofTo(key: String): List<Publish> =
        synchronized(published) {
            published.filter {
                it.recipient == key &&
                    ZPROFMessage.isProfileMessage(it.text)
            }
        }

    private fun zprofRecipients(): Set<String> =
        synchronized(published) { published.filter { ZPROFMessage.isProfileMessage(it.text) }.map { it.recipient }.toSet() }

    private fun images(list: List<Publish>): List<Publish> = list.filter { ZPROFMessage.parseRemove(it.text) == null }

    private fun sha16(jpeg: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(jpeg)
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /** Waits for every coroutine the view model launched to finish (no launch tick running). */
    private suspend fun awaitIdle(vm: ChatViewModel) {
        val done =
            withTimeoutOrNull(TIMEOUT_MS) {
                while (vm.viewModelScope.coroutineContext.job.children
                        .any { it.isActive }
                ) {
                    delay(10)
                }
                true
            }
        assertTrue("the view model's work did not finish", done == true)
    }

    /** Waits until [condition] holds, then a short grace so a duplicate send would be seen too. */
    private suspend fun awaitThenSettle(condition: () -> Boolean) {
        withTimeoutOrNull(TIMEOUT_MS) { while (!condition()) delay(10) }
        delay(GRACE_MS)
    }

    private fun invoke(
        vm: ChatViewModel,
        name: String,
        vararg args: Any,
    ): Any? {
        val method =
            ChatViewModel::class.java.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("ChatViewModel has no $name(${args.size} args)")
        method.isAccessible = true
        return try {
            method.invoke(vm, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** The launch tick: the self-avatar version observer the ChatViewModel's init starts. */
    private fun startLaunchTick(vm: ChatViewModel) {
        val method =
            ChatViewModel::class.java.declaredMethods.firstOrNull { it.name == "observeSelfAvatarDelivery" && it.parameterCount == 0 }
                ?: throw AssertionError(
                    "no launch tick of its own (observeSelfAvatarDelivery): at 78128221f the version observer is inline in init and " +
                        "only compares the current version with one global marker",
                )
        method.isAccessible = true
        method.invoke(vm)
    }

    private suspend fun broadcastAndWait(p: Phone) {
        p.vm.broadcastSelfAvatarToContacts()
        awaitIdle(p.vm)
    }

    private suspend fun broadcastRemovalAndWait(p: Phone) {
        p.vm.broadcastSelfAvatarRemoveToContacts()
        awaitIdle(p.vm)
    }

    // ── B1-1 / B1-2 / B1-3 ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `B1-1 setting our avatar sends it only to the contacts whose chat is not Shielded`() =
        runBlocking {
            val p = phone()
            p.avatars.set(V1, JPEG_1)

            broadcastAndWait(p)

            assertEquals("ZPROF went to Shielded contacts too", setOf(KEY_T, KEY_O), zprofRecipients())
            assertEquals("the delivered marker", V1, p.avatars.marker())
        }

    @Test
    fun `B1-2 removing our avatar sends the removal only to the contacts whose chat is not Shielded`() =
        runBlocking {
            val p = phone()
            p.avatars.remove(R1)

            broadcastRemovalAndWait(p)

            assertEquals("the removal went to Shielded contacts too", setOf(KEY_T, KEY_O), zprofRecipients())
            assertTrue(zprofTo(KEY_T).all { ZPROFMessage.parseRemove(it.text) == R1 })
        }

    @Test
    fun `B1-3 with only Shielded contacts holding a relay key nothing is sent and nothing is marked delivered`() =
        runBlocking {
            val p = phone()
            p.contacts.store.pubkeys
                .remove(T)
            p.contacts.store.pubkeys
                .remove(O)
            p.avatars.set(V1, JPEG_1)

            broadcastAndWait(p)
            p.avatars.remove(R1)
            broadcastRemovalAndWait(p)

            assertEquals("ZPROF went to Shielded contacts", emptySet<String>(), zprofRecipients())
            assertEquals("no contact received it, so nothing is marked delivered", 0L, p.avatars.marker())
        }

    // ── B1-4 / B1-6 / B1-7 / B1-10: leaving Shielded with changeConversationMode ─────────────────────

    @Test
    fun `B1-4 a Shielded chat switched to Tunnel gets our avatar once, after the mode change`() =
        runBlocking {
            val p = phone()
            p.avatars.set(V1, JPEG_1)
            broadcastAndWait(p)
            published.clear()

            p.vm.changeConversationMode(S, ConversationMode.TUNNEL)
            awaitThenSettle { zprofTo(KEY_S).isNotEmpty() }

            val toS = synchronized(published) { published.filter { it.recipient == KEY_S } }
            val zprof = toS.filter { ZPROFMessage.isProfileMessage(it.text) }
            assertEquals("one avatar transfer to the contact that left Shielded: $toS", 1, images(zprof).size)
            assertTrue("the avatar is ours", zprof.single().text.contains("|${sha16(JPEG_1)}|"))
            val modeAt = toS.indexOfFirst { ZMSGProtocol.parseModeChange(it.text) != null }
            val avatarAt = toS.indexOfFirst { ZPROFMessage.isProfileMessage(it.text) }
            assertTrue("the mode change goes first ($toS)", modeAt in 0 until avatarAt)
            assertEquals("no other contact is sent it again", setOf(KEY_S), zprofRecipients())
        }

    @Test
    fun `B1-6 an avatar the relays did not take after the switch is sent once by the next launch tick`() =
        runBlocking {
            val p = phone()
            p.avatars.set(V1, JPEG_1)
            broadcastAndWait(p)
            published.clear()
            acks = { recipient, text -> if (recipient == KEY_S && ZPROFMessage.isProfileMessage(text)) 0 else 1 }

            p.vm.changeConversationMode(S, ConversationMode.TUNNEL)
            awaitThenSettle { zprofTo(KEY_S).isNotEmpty() }
            assertEquals("the switch tried once", 1, zprofTo(KEY_S).size)
            assertEquals("no relay took it", 0, zprofTo(KEY_S).single().acks)

            acks = { _, _ -> 1 }
            startLaunchTick(p.vm)
            awaitThenSettle { zprofTo(KEY_S).size >= 2 }

            assertEquals("the launch tick delivers it once", 1, zprofTo(KEY_S).count { it.acks > 0 })
            assertEquals("contacts that already have it are not sent it again", setOf(KEY_S), zprofRecipients())
        }

    @Test
    fun `B1-7 after a removal a contact leaving Shielded gets the removal, not an image`() =
        runBlocking {
            val p = phone()
            p.avatars.remove(R1)
            broadcastRemovalAndWait(p)
            published.clear()

            p.vm.changeConversationMode(S, ConversationMode.TUNNEL)
            awaitThenSettle { zprofTo(KEY_S).isNotEmpty() }

            val toS = zprofTo(KEY_S)
            assertEquals("one removal to the contact that left Shielded: $toS", 1, toS.size)
            assertEquals("the removal, not an image", R1, ZPROFMessage.parseRemove(toS.single().text))
        }

    @Test
    fun `B1-10 a contact leaving Shielded after two changes gets the current avatar once, never the older one`() =
        runBlocking {
            val p = phone()
            p.avatars.set(V1, JPEG_1)
            broadcastAndWait(p)
            p.avatars.set(V2, JPEG_2)
            broadcastAndWait(p)
            assertEquals("T gets each version once", 2, images(zprofTo(KEY_T)).size)
            assertTrue("T has the new one", zprofTo(KEY_T).last().text.contains("|${sha16(JPEG_2)}|"))

            p.vm.changeConversationMode(S, ConversationMode.TUNNEL)
            awaitThenSettle { zprofTo(KEY_S).isNotEmpty() }

            val toS = images(zprofTo(KEY_S))
            assertEquals("S gets one transfer: $toS", 1, toS.size)
            assertTrue("S gets the current avatar, not the older one", toS.single().text.contains("|${sha16(JPEG_2)}|"))
        }

    // ── B1-5 / B1-9: the contact's side moves the chat out of Shielded ───────────────────────────────

    @Test
    fun `B1-5 an unpinned Shielded chat that adopts Tunnel on the contact's ZBOOT gets our avatar once`() =
        runBlocking {
            val identity = NOSTRIdentity.fromSeed(ByteArray(64) { (it + 7).toByte() })
            val s0Key = identity.publicKey.joinToString("") { "%02x".format(it) }
            val p = phone(s0Key = s0Key)
            p.avatars.set(V1, JPEG_1)
            broadcastAndWait(p)
            published.clear()

            val signed = ZBootMessage.signedDataFor(CONV_ID, s0Key, RelayKeyBinding.RELAY, 1L)
            val sig =
                identity
                    .signHashSchnorr(MessageDigest.getInstance("SHA-256").digest(signed.toByteArray(Charsets.UTF_8)))
                    .joinToString("") { "%02x".format(it) }
            val boot =
                ZBootMessage(
                    convId = CONV_ID,
                    senderNostrPubkeyHex = s0Key,
                    relayUrl = RelayKeyBinding.RELAY,
                    signature = sig,
                    epoch = 1L,
                    version = 4,
                ).serialize()
            runCatching { invoke(p.vm, "routeIncomingBoot", S0, boot) }
            assertEquals("the ZBOOT was not adopted", ConversationMode.TUNNEL, p.contacts.store.modes[S0])
            awaitThenSettle { zprofTo(s0Key).isNotEmpty() }

            assertEquals("one avatar transfer to the contact whose chat adopted Tunnel", 1, images(zprofTo(s0Key)).size)
            assertEquals("no other contact is sent it again", setOf(s0Key), zprofRecipients())
        }

    @Test
    fun `B1-9 an unpinned Shielded chat that adopts the contact's Tunnel on a ZMODE gets our avatar once`() =
        runBlocking {
            val p = phone()
            p.avatars.set(V1, JPEG_1)
            broadcastAndWait(p)
            published.clear()

            val zmode = ZMSGProtocol.createModeChange(ConversationMode.TUNNEL, System.currentTimeMillis(), S0)
            runCatching { invoke(p.vm, "handleModeControl", S0, zmode) }
            assertEquals("the ZMODE was not adopted", ConversationMode.TUNNEL, p.contacts.store.modes[S0])
            awaitThenSettle { zprofTo(KEY_S0).isNotEmpty() }

            assertEquals("one avatar transfer to the contact whose chat adopted Tunnel", 1, images(zprofTo(KEY_S0)).size)
            assertEquals("no other contact is sent it again", setOf(KEY_S0), zprofRecipients())
        }

    // ── launch tick: every other mode writer, and the upgrade ────────────────────────────────────────

    @Test
    fun `B1-11 a relay-only chat moved to Open by M1's default gets our avatar at the next launch tick`() =
        runBlocking {
            val m = SyntheticUnifiedAddress.mainnet(8_105)
            val p = phone { it.add(m, KEY_M, mode = null) }
            p.avatars.set(V1, JPEG_1)
            broadcastAndWait(p)
            assertEquals("a chat with no mode is Shielded by default and gets nothing", setOf(KEY_T, KEY_O), zprofRecipients())
            published.clear()

            // M1: the contact's first relay text moves the relay-only chat (no E2E key, no mode) to Open. No hook there.
            RelayKeyChatScreen.start(p.vm, "observeNostrInbound")
            NostrChatBridge.dispatch(
                NostrInboxManager.InboundDm(
                    senderPubkeyHex = KEY_M,
                    content = "synthetic relay text",
                    createdAtSec = System.currentTimeMillis() / 1000,
                    eventId = "synthetic-srb1-${UUID.randomUUID()}",
                    rumorId = "rumor-synthetic-srb1-${UUID.randomUUID()}",
                ),
                p.contacts.proxy,
            )
            withTimeoutOrNull(TIMEOUT_MS) { while (p.contacts.store.modes[m] == null) delay(10) }
            assertEquals("M1 did not open the relay-only chat", ConversationMode.OPEN, p.contacts.store.modes[m])

            startLaunchTick(p.vm)
            awaitThenSettle { zprofTo(KEY_M).isNotEmpty() }

            assertEquals("the launch tick sends the Open chat our avatar once", 1, images(zprofTo(KEY_M)).size)
            assertEquals("contacts that already have it are not sent it again", setOf(KEY_M), zprofRecipients())
        }

    @Test
    fun `B1-12 after the upgrade the launch tick re-sends nothing, and a Shielded contact still gets it when it leaves`() =
        runBlocking {
            val p = phone()
            p.avatars.set(V1, JPEG_1)
            // 2.12.4 (or 2.13.0 before this fix) delivered V1 and set the one global marker.
            p.avatars.store.setLastBroadcastSelfAt(V1)

            startLaunchTick(p.vm)
            delay(GRACE_MS * 2)
            assertEquals("the upgrade re-sent the avatar in bulk", emptySet<String>(), zprofRecipients())

            p.vm.changeConversationMode(S, ConversationMode.TUNNEL)
            awaitThenSettle { zprofTo(KEY_S).isNotEmpty() }

            assertEquals("the Shielded contact gets it once when its chat leaves Shielded", 1, images(zprofTo(KEY_S)).size)
            assertEquals(setOf(KEY_S), zprofRecipients())
        }

    // ── B1-8 control ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `B1-8 control - Tunnel and Open contacts get the avatar, and a relay outage is retried by the next broadcast`() =
        runBlocking {
            val p = phone()
            p.avatars.set(V1, JPEG_1)
            acks = { _, _ -> 0 }

            broadcastAndWait(p)
            assertEquals("nothing was taken, so nothing is marked delivered", 0L, p.avatars.marker())

            acks = { _, _ -> 1 }
            broadcastAndWait(p)

            assertTrue("Tunnel contact", zprofTo(KEY_T).any { it.acks > 0 })
            assertTrue("Open contact", zprofTo(KEY_O).any { it.acks > 0 })
            assertEquals("the delivered marker", V1, p.avatars.marker())
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val GRACE_MS = 300L
        const val CONV_ID = "srb1conv"

        val S = SyntheticUnifiedAddress.mainnet(8_101)
        val S0 = SyntheticUnifiedAddress.mainnet(8_102)
        val T = SyntheticUnifiedAddress.mainnet(8_103)
        val O = SyntheticUnifiedAddress.mainnet(8_104)
        val U = SyntheticUnifiedAddress.mainnet(8_106)
        val KEY_S = relayKey(81)
        val KEY_S0 = relayKey(82)
        val KEY_T = relayKey(83)
        val KEY_O = relayKey(84)
        val KEY_M = relayKey(85)

        // Synthetic avatar versions (ms) and bytes: the store never decodes them on this path.
        const val V1 = 1_790_000_000_001L
        const val V2 = 1_790_000_000_002L
        const val R1 = 1_790_000_000_003L
        val JPEG_1 = ByteArray(600) { (it * 7).toByte() }
        val JPEG_2 = ByteArray(600) { (it * 11 + 3).toByte() }
    }
}
