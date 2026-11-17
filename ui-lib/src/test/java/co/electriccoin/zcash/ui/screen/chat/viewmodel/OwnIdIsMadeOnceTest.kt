package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ConversationIdStoreFixture
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLDecoder
import java.util.Base64

/**
 * FC-ID (ZC-892; FIRST-CONTACT-SPEC revision 4a, 6.2a case j) — the user cancels the first-message question of case (h)
 * (the contact wrote first under its own id), then sends a short text later: one own-made id, made once; the later
 * message is a first message under it and is counted so, and the memos the confirm proposes carry that id. A guard over
 * the real conversation-id store ([ConversationIdStoreFixture]). Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnIdIsMadeOnceTest {
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

    @Test
    fun `(j) a cancelled first-message question, then a short text - one own-made id, made once, the text a first message under it`() =
        runBlocking {
            val peer = "usyntheticfcidcancelled00001"
            val chat = FirstMessagePlanChat(peer)
            val ids = ConversationIdStoreFixture()
            ids.store.setConversationMapping(FOREIGN, peer)
            val vm = chat.viewModel(FirstMessagePlanApi.ScriptedCheck("Fundable", "Fundable", "Fundable", "Fundable"))
            PaymentHarness.setField(vm, "zchatPreferences", routed(chat.prefs, ids))

            chat.typeAndSend(vm, "synthetic long first words the user then cancels")
            assertTrue("fixture sanity: the first message is asked about", chat.asked(vm))
            val made = ids.peer(peer)!!
            vm.dismissPaidAction()
            awaitIdle(vm)
            assertEquals("Cancel paid something", 0, chat.proposals())

            chat.typeAndSend(vm, "short")
            assertTrue("the later text was not asked about as a first message (it pays our key exchange)", chat.asked(vm))
            assertEquals("the later text did not use the id made at the first question", made, ids.peer(peer))
            assertEquals("a second own-made id was made for the address", setOf("own:$made"), ids.ownKeys())
            assertTrue("the later text is not counted as a first message", ids.store.needsConversationInit(peer))
            assertEquals("the contact's id moved", peer, ids.conv(FOREIGN))

            vm.confirmPaidAction()
            awaitIdle(vm)

            val sent = memoIds(chat.fixture.seam.proposalUris)
            assertTrue("fixture sanity: the confirm proposed something", sent.isNotEmpty())
            assertEquals("a memo went out under another id than the one made once: $sent", setOf(made), sent)
        }

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
                    "toString" -> return@newProxyInstance "FcIdMadeOncePrefs"
                    in ID_CALLS -> ids.store
                    else -> base
                }
            try {
                method.invoke(target, *a)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ZchatPreferences

    private fun memoIds(uris: List<String>): Set<String> =
        uris
            .flatMap { uri -> Regex("""memo(?:\.\d+)?=([A-Za-z0-9_%-]+)""").findAll(uri).map { it.groupValues[1] }.toList() }
            .mapNotNull { encoded ->
                runCatching { String(Base64.getUrlDecoder().decode(URLDecoder.decode(encoded, "UTF-8").trimEnd('='))) }.getOrNull()
            }.mapNotNull { memo -> Regex("""^ZMSG\|v4\|([A-Z0-9]{8})\|""").find(memo)?.groupValues?.get(1) }
            .toSet()

    private companion object {
        const val FOREIGN = "FRGN8888"
        val ID_CALLS =
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
    }
}
