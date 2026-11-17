package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.NostrInboxManager
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN_NOSTR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * T1b-2 (train T1b, finding 6; INVESTIGATION.md §3/6): 4 of 5 group memos (20:37-20:40) never arrived.
 *
 * The recipient, the HONOR release app, was not running (killed by `install -r` at 19:15:49) until its cold start at
 * 20:43:15. At a cold start the relay sockets open, and replay what they hold, before any chat screen exists; at
 * 00089d8d9 NostrChatBridge hands a relay message only to a live ChatViewModel collector and keeps nothing, so the
 * memos were left for a relay replay that never came (our own relay ignores a repeated subscription; relay.damus.io
 * never serves us; nos.lol was down).
 *
 * Wanted: group memos the relays deliver before the ChatViewModel exists reach processGroupMessage, and are stored,
 * once it starts reading. Real ChatViewModel over [AnnouncePhone]; its init's reader (observeNostrInbound) is started
 * the way init starts it. Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMemoToColdStartedMemberArrivesTest {
    private val sendScopes = SendHarnessScopes()
    private val tag = "synthetic-cold-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** The phone's stores, plus what dispatch reads: the admin's NOSTR key maps to the admin, and the seen set. */
    private class RelayPhone(
        val phone: AnnouncePhone,
    ) {
        val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "findPeerByNostrPubkey" -> if (args!![0] == ADMIN_NOSTR) ADMIN else null
                    "hasSeenNostrEvent" -> args!![0] in seen
                    "markNostrEventSeen" -> {
                        seen += args!![0] as String
                        null
                    }
                    "unmarkNostrEventSeen" -> {
                        seen -= args!![0] as String
                        null
                    }
                    "toString" -> "RelayPhone"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args!![0]
                    else ->
                        try {
                            method.invoke(phone.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
    }

    private fun relayDm(
        memo: String,
        n: Int,
    ) = NostrInboxManager.InboundDm(
        senderPubkeyHex = ADMIN_NOSTR,
        content = memo,
        createdAtSec = System.currentTimeMillis() / 1000,
        eventId = "$tag-$n",
        rumorId = "rumor-$tag-$n",
    )

    /** The first chat screen's ChatViewModel, reading the relays the way its init does. */
    private suspend fun coldStartedChatScreen(relayPhone: RelayPhone): ChatViewModel {
        val vm = HandshakeHarness.chatViewModelWith(relayPhone.proxy, HandshakeSeam(relayPhone.phone.self))
        AutoSpendVm.wireBootstrapFields(vm)
        AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(relayPhone.phone.self))
        ChatViewModel::class.java
            .getDeclaredMethod("observeNostrInbound")
            .apply { isAccessible = true }
            .invoke(vm)
        return vm
    }

    private fun storedTexts(phone: AnnouncePhone): List<String> =
        phone.messages[GID]
            ?.let { json -> JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("decrypted") } } }
            .orEmpty()

    @Test
    fun `group memos delivered before any chat screen exists are stored once the first one starts reading`() =
        runBlocking {
            val relayPhone = RelayPhone(AnnouncePhone())
            val admin = AnnounceDriver.memberPhone(relayPhone.phone)
            val texts = (1..4).map { "memo $it while the app was closed" }
            texts.forEachIndexed { i, text ->
                val memo = AnnounceDriver.gm(relayPhone.phone, ADMIN, text, signer = admin, seq = (i + 1).toLong())
                NostrChatBridge.dispatch(relayDm(memo, i + 1), relayPhone.proxy)
            }

            coldStartedChatScreen(relayPhone)
            withTimeoutOrNull(5_000) { while (storedTexts(relayPhone.phone).size < texts.size) delay(20) }

            assertEquals(
                "group memos that reached the relays while the app was not running were lost at its cold start (T1: 4 of 5)",
                texts.toSet(),
                storedTexts(relayPhone.phone).toSet(),
            )
            assertTrue(
                "each memo is marked seen by the reader once handled",
                (1..4).all { "$tag-$it" in relayPhone.seen },
            )
        }

    @Test
    fun `control - a group memo delivered while a chat screen is reading is stored`() =
        runBlocking {
            val relayPhone = RelayPhone(AnnouncePhone())
            val admin = AnnounceDriver.memberPhone(relayPhone.phone)
            coldStartedChatScreen(relayPhone)

            val memo = AnnounceDriver.gm(relayPhone.phone, ADMIN, "memo while reading", signer = admin, seq = 9)
            NostrChatBridge.dispatch(relayDm(memo, 9), relayPhone.proxy)
            withTimeoutOrNull(5_000) { while (storedTexts(relayPhone.phone).isEmpty()) delay(20) }

            assertEquals(listOf("memo while reading"), storedTexts(relayPhone.phone))
        }
}
