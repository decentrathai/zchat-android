package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ConversationIdStoreFixture
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
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
import java.net.URLDecoder
import java.util.Base64

/**
 * ZC-599 (FIRST-CONTACT-SPEC revision 4a, 5.4 item 4, FC-2a; 6.3) — every answer this app sends carries the id of the
 * key exchange it answers.
 *
 * Today the automatic answer does, but Accept and the retry at a chat open answer under this phone's own id for the
 * contact ("convIdMutex.withLock { zchatPreferences.getOrCreateConversationId(peerAddress) }.first"), which the starter
 * cannot take as the proof of its own key exchange. Wanted: the id of the received key exchange is kept beside the
 * responder mark, and Accept and the retry answer under it. The conversation-id calls go to the REAL store functions
 * ([ConversationIdStoreFixture]); everything else is [HandshakePrefs]. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnswersGoOutUnderTheIdOfTheirKeyExchangeTest {
    private val sendScopes = SendHarnessScopes()

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

    private companion object {
        const val KEX_ID = "KXID5991"
    }

    @Test
    fun `(a) Accept, in a chat where this phone already has its own id for the starter, answers under the key exchange's id`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 59911)
            val prefs = HandshakePrefs(peer, firstContact = true)
            val seam = HandshakeSeam(peer)
            val ids = ConversationIdStoreFixture()
            val (own, _) = ids.store.getOrCreateConversationId(peer) // an accepted Open chat: our own id for the starter
            val vm = HandshakeHarness.handshakeViewModel(prefs, seam)
            PaymentHarness.setField(vm, "zchatPreferences", routed(prefs.proxy, ids))
            val keys = E2EEncryption.generateKeyPair()

            val payload = E2EEncryption.createKEXPayload(peer, keys.publicKey, keys.privateKey)
            handleKexMemo(vm, ZMSGProtocol.createV4KEXMessage(KEX_ID, peer, payload), "synthetic-zc599-ga")
            awaitIdle(vm)
            vm.acceptInboundKexRequest(peer)
            awaitIdle(vm)

            assertEquals("fixture sanity: Accept paid one answer", 1, seam.proposalRequests.get())
            assertEquals(
                "the answer went out under this phone's own id ($own), not the key exchange's",
                listOf(KEX_ID),
                memoIds(seam.proposalUris),
            )
        }

    @Test
    fun `(b) the retry at a chat open after an answer that was not sent answers under the key exchange's id`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 59912)
            val prefs = HandshakePrefs(peer)
            val seam = HandshakeSeam(peer, failFirstProposalWith = IllegalStateException("synthetic: the first answer is refused"))
            val ids = ConversationIdStoreFixture()
            ids.store.setConversationMapping("OWNB5991", peer)
            ids.store.setConversationId(peer, "OWNB5991")
            val vm = HandshakeHarness.handshakeViewModel(prefs, seam)
            PaymentHarness.setField(vm, "zchatPreferences", routed(prefs.proxy, ids))
            val keys = E2EEncryption.generateKeyPair()
            // A key exchange that repeats a key this phone already holds for the contact (no hold, no W-62 wait).
            prefs.e2ePeerKey[peer] = keys.publicKey

            val payload = E2EEncryption.createKEXPayload(peer, keys.publicKey, keys.privateKey)
            handleKexMemo(vm, ZMSGProtocol.createV4KEXMessage(KEX_ID, peer, payload), "synthetic-zc599-gb")
            awaitIdle(vm)
            vm.retryKexAckIfResponder(peer)
            awaitIdle(vm)

            val ids2 = memoIds(seam.proposalUris)
            assertEquals("fixture sanity: the retry paid one answer: $ids2", 1, seam.broadcasts.size)
            assertEquals("the retry's answer went out under another id than the key exchange's", KEX_ID, ids2.last())
        }

    @Test
    fun `(c) guard - the automatic answer carries the key exchange's id`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 59913)
            val prefs = HandshakePrefs(peer)
            val seam = HandshakeSeam(peer)
            val ids = ConversationIdStoreFixture()
            ids.store.setConversationMapping("OWNC5991", peer)
            ids.store.setConversationId(peer, "OWNC5991")
            val vm = HandshakeHarness.handshakeViewModel(prefs, seam)
            PaymentHarness.setField(vm, "zchatPreferences", routed(prefs.proxy, ids))
            val keys = E2EEncryption.generateKeyPair()
            // A key exchange that repeats a key this phone already holds for the contact (no hold, no W-62 wait).
            prefs.e2ePeerKey[peer] = keys.publicKey

            val payload = E2EEncryption.createKEXPayload(peer, keys.publicKey, keys.privateKey)
            handleKexMemo(vm, ZMSGProtocol.createV4KEXMessage(KEX_ID, peer, payload), "synthetic-zc599-gc")
            awaitIdle(vm)

            assertEquals(listOf(KEX_ID), memoIds(seam.proposalUris))
        }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private fun routed(
        base: ZchatPreferences,
        ids: ConversationIdStoreFixture,
    ): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
            val a = args ?: emptyArray()
            val target: Any =
                when (method.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(p)
                    "equals" -> return@newProxyInstance p === a.getOrNull(0)
                    "toString" -> return@newProxyInstance "Zc599RoutedPrefs"
                    in ROUTED_599 -> ids.store
                    else -> base
                }
            try {
                method.invoke(target, *a)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ZchatPreferences

    /** The conversation ids of the ZMSG v4 memos in [uris] (ZIP 321 requests, memos base64url), in order. */
    private fun memoIds(uris: List<String>): List<String> =
        uris
            .flatMap { uri -> Regex("""memo(?:\.\d+)?=([A-Za-z0-9_%-]+)""").findAll(uri).map { it.groupValues[1] }.toList() }
            .mapNotNull { encoded ->
                runCatching { String(Base64.getUrlDecoder().decode(URLDecoder.decode(encoded, "UTF-8").trimEnd('='))) }.getOrNull()
            }.mapNotNull { memo -> Regex("""^ZMSG\|v4\|([A-Z0-9]{8})\|""").find(memo)?.groupValues?.get(1) }
}

private val ROUTED_599 =
    setOf(
        "getConversationId",
        "setConversationId",
        "getOrCreateConversationId",
        "getOrCreateConversationIdForMessage",
        "needsConversationInit",
        "markConversationInitSubmitted",
        "getPeerByConversationId",
        "setConversationMapping",
        "ownConversationId",
        "isOwnConversationId",
        "ownConversationIdForStart",
        "restoreOwnConversationId",
    )
