package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ConversationIdStoreFixture
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLDecoder
import java.util.Base64

/**
 * FC-ID (ZC-892; FIRST-CONTACT-SPEC revision 4a, 5.3a items 3 to 5, 6.2a cases h and l) — this phone's own key exchange
 * goes out under an id this phone made for that address, chosen where the send is planned, before its price is asked.
 *
 * Today `buildKexMemo` takes whatever id `peer:<address>` holds ("getOrCreateConversationIdForMessage returns an existing
 * id unchanged"): (h) when the contact wrote first under its own id, the first message is counted and sent as a reply
 * under the contact's id, and our key exchange goes out under it too; (l) a key exchange reached with an id a memo gave
 * this address (a caller that skipped the plan step) is proposed under that id.
 *
 * The conversation-id calls go to the REAL store functions over an in-memory file ([ConversationIdStoreFixture]);
 * every other call goes to the harness's own store. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnKexGoesOutUnderAnOwnIdTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    private companion object {
        const val FOREIGN = "FRGN7777"
        const val TYPED = "synthetic first words after the contact wrote first"
    }

    // ---- (h) the id is made at the plan, before the question ---------------------------------------------------------

    @Test
    fun `(h) after the contact wrote first, the question and the send use one own-made id, not the contact's`() =
        runBlocking {
            val peer = "usyntheticfcidflow19peer0001"
            val chat = FirstMessagePlanChat(peer)
            val ids = ConversationIdStoreFixture()
            ids.store.setConversationMapping(FOREIGN, peer) // the contact's first message gave this address its id
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable", "Fundable"))
            PaymentHarness.setField(vm, "zchatPreferences", routed(chat.prefs, ids))

            chat.typeAndSend(vm, TYPED)
            assertTrue("fixture sanity: a first message that pays our key exchange is asked about", chat.asked(vm))

            val atQuestion = ids.peer(peer)
            assertNotEquals("the question counted the message under the contact's id (as a reply)", FOREIGN, atQuestion)
            assertEquals("the id at the question is not one this phone made for this address", peer, ids.own(atQuestion!!))
            assertTrue("the message after our key exchange is not counted as a first message", ids.store.needsConversationInit(peer))
            assertEquals("the contact's id must still map to the contact", peer, ids.conv(FOREIGN))

            vm.confirmPaidAction()
            awaitIdle(vm)

            val sentIds = memoIds(chat.fixture.seam.proposalUris)
            assertTrue(
                "fixture sanity: the confirmed send proposed something: ${chat.fixture.seam.proposalUris.size}",
                sentIds.isNotEmpty()
            )
            assertEquals("a memo went out under another id than the one the question used: $sentIds", setOf(atQuestion), sentIds)
        }

    // ---- (l) the fail-closed pin ------------------------------------------------------------------------------------

    @Test
    fun `(l) a key exchange reached with an id a memo gave the address proposes nothing`() =
        runBlocking {
            val peer = "usyntheticfcidforeignkex0001"
            val prefs = HandshakePrefs(peer)
            val seam = HandshakeSeam(peer)
            val vm = handshakeViewModel(prefs, seam)
            val ids = ConversationIdStoreFixture()
            ids.store.setConversationMapping(FOREIGN, peer)
            PaymentHarness.setField(vm, "zchatPreferences", routed(prefs.proxy, ids))

            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)

            assertEquals(
                "our key exchange was proposed under an id this phone did not make: ${memoIds(seam.proposalUris)}",
                0,
                seam.proposalRequests.get(),
            )
        }

    // ---- helpers --------------------------------------------------------------------------------------------------------

    /** [base] with every conversation-id call answered by the real store functions of [ids]. */
    private fun routed(
        base: ZchatPreferences,
        ids: ConversationIdStoreFixture,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            val a = args ?: emptyArray()
            val target: Any =
                when (method.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(p)
                    "equals" -> return@newProxyInstance p === a.getOrNull(0)
                    "toString" -> return@newProxyInstance "FcIdRoutedPrefs"
                    in ROUTED -> ids.store
                    else -> base
                }
            try {
                method.invoke(target, *a)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ZchatPreferences

    /** The conversation ids of the ZMSG v4 memos in [uris] (ZIP 321 requests, memos base64url). */
    private fun memoIds(uris: List<String>): Set<String> =
        uris
            .flatMap { uri -> Regex("""memo(?:\.\d+)?=([A-Za-z0-9_%-]+)""").findAll(uri).map { it.groupValues[1] }.toList() }
            .mapNotNull { encoded ->
                runCatching { String(Base64.getUrlDecoder().decode(URLDecoder.decode(encoded, "UTF-8").trimEnd('='))) }.getOrNull()
            }.mapNotNull { memo -> Regex("""^ZMSG\|v4\|([A-Z0-9]{8})\|""").find(memo)?.groupValues?.get(1) }
            .toSet()
}

private val ROUTED =
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
