package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.PrivacyStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.stillConfirming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * Round 8 D7 (sweep #13) — setting a status with "tell my contacts" sends one paid ZSTAT memo per Shielded contact.
 *
 * At 1b98dd127 broadcastStatusToContacts makes one direct send per contact, with no block retry, inside
 * `catch (_: Exception) {}`. The first memo spends the wallet's only Ironwood note, so the second contact meets the
 * "still confirming" wait and is skipped; the user is told nothing. The same catch also swallows the coroutine's
 * cancellation, so a broadcast whose screen went away goes on to the next contacts.
 *
 * Wanted:
 * - (f) each contact is sent through the block-aware retry that waits out a proven nothing-sent wait, so all three
 *   Shielded contacts get the status, each paid once;
 * - when some contacts could not be reached, the user is told how many were, with ChatSendCopy.STATUS_PARTIAL and
 *   the counts;
 * - (g) cancellation ends the broadcast: no contact after the one in flight is tried;
 * - (h) the new notice has an English resource equal to ChatSendCopy.STATUS_PARTIAL and a Spanish one, both with the
 *   two numbers.
 *
 * All addresses and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBroadcastReachesEveryContactTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** Wraps the lenient chat preferences and records which contact's mode was read (one read per contact tried). */
    private class RecordingPrefs(
        inner: ZchatPreferences,
    ) {
        val modeLookups: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                if (method.name == "getConversationMode") modeLookups += args!![0] as String
                try {
                    method.invoke(inner, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as ZchatPreferences
    }

    private class Broadcast(
        val vm: ChatViewModel,
        val seam: ScriptedSeam,
        val heights: HeightFeed,
        val prefs: RecordingPrefs,
    ) {
        fun state(): SendMessageState = HandshakeHarness.sendMessageState(vm)

        /** broadcastStatusToContacts as setUserStatus calls it once the user confirmed the price. */
        fun start() {
            ChatViewModel::class.java
                .getDeclaredMethod("broadcastStatusToContacts", String::class.java)
                .apply { isAccessible = true }
                .invoke(vm, STATUS)
        }

        /** Mine a block each time a send waits for one, until the broadcast's job is over. Returns the blocks mined. */
        suspend fun runToEnd(): Int {
            var mined = 0
            withTimeout(HandshakeHarness.TIMEOUT_MS) {
                while (vm.viewModelScope.coroutineContext.job.children
                        .any { it.isActive }
                ) {
                    if (heights.isWaiting()) {
                        heights.advance()
                        mined++
                    }
                    delay(5)
                }
            }
            return mined
        }
    }

    private suspend fun broadcast(
        accountsSetup: (OwnChangeAccounts) -> Unit,
        failRequest: (Int) -> Exception?,
    ): Broadcast {
        val fixture = PaidChatFixture(CONTACT_A, ConversationMode.VAULT)
        val prefs = RecordingPrefs(fixture.proxy)
        val accounts = OwnChangeAccounts.create().also(accountsSetup)
        val heights = HeightFeed()
        val vm =
            HandshakeHarness.chatViewModelWith(prefs.proxy, HandshakeSeam(CONTACT_A), heights, accountSource = accounts)
        val seam = ScriptedSeam(failRequest)
        AutoSpendVm.setField(vm, "createChunkedMessageProposal", seam.useCase(accounts))
        AutoSpendVm.setField(
            vm,
            "_chatListState",
            MutableStateFlow<ChatListState>(
                ChatListState.Success(
                    conversations = CONTACTS.map { Conversation(peerAddress = it, messages = emptyList(), lastMessage = null) },
                    currentUserAddress = HandshakeHarness.SELF,
                    privacyStatus = PrivacyStatus.DEFAULT,
                ),
            ),
        )
        return Broadcast(vm, seam, heights, prefs)
    }

    private fun stateName(state: SendMessageState): String = state.javaClass.simpleName

    private fun intProperty(
        state: SendMessageState,
        name: String,
    ): Int = state.javaClass.getMethod("get${name.replaceFirstChar { it.uppercase() }}").invoke(state) as Int

    // ── (f) ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a status broadcast to three Shielded contacts reaches all three when the second one waits for a block`() =
        runBlocking {
            val b = broadcast({ it.ownChangeConfirmingNoOrchard() }) { n -> if (n == 2) stillConfirming() else null }

            b.start()
            val mined = b.runToEnd()

            assertEquals(
                "the second contact met the \"still confirming\" wait and was skipped (state ${b.state()})",
                CONTACTS,
                b.seam.submittedTo.toList(),
            )
            assertEquals("each contact is paid once", 3, b.seam.submits.get())
            assertTrue("the wait must be waited out, block by block", mined >= 1)
            assertNotEquals("every contact was reached", "StatusPartial", stateName(b.state()))
        }

    @Test
    fun `a status broadcast that could not reach a contact says how many of the contacts it reached`() =
        runBlocking {
            // The second contact meets a real shortfall, which no block can fix: it is not retried.
            val shortfall = InsufficientFundsException("Insufficient balance", have = Zatoshi(0), need = Zatoshi(17_000))
            val b = broadcast({ it.ironwoodSpendable() }) { n -> if (n == 2) shortfall else null }

            b.start()
            val mined = b.runToEnd()

            assertEquals("a real shortfall is not waited out", 0, mined)
            assertEquals(listOf(CONTACT_A, CONTACT_C), b.seam.submittedTo.toList())
            val state = b.state()
            assertEquals("the user is told the status did not reach everyone: $state", "StatusPartial", stateName(state))
            assertEquals("reached", 2, intProperty(state, "reached"))
            assertEquals("total", 3, intProperty(state, "total"))
        }

    // ── (g) ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `cancelling the broadcast ends it and no later contact is tried`() =
        runBlocking {
            lateinit var b: Broadcast
            b =
                broadcast({ it.ironwoodSpendable() }) { n ->
                    // The screen's scope ends while the first contact's memo is being built.
                    if (n == 1) b.vm.viewModelScope.cancel()
                    null
                }

            b.start()
            awaitTrue {
                b.vm.viewModelScope.coroutineContext.job.children
                    .none { it.isActive }
            }

            assertFalse(
                "the broadcast went on to the last contact after it was cancelled (tried ${b.prefs.modeLookups})",
                CONTACT_C in b.prefs.modeLookups,
            )
            assertTrue("no contact after the cancel is paid", b.seam.submittedTo.none { it == CONTACT_C })
            assertTrue("at most the contact in flight and the one read next are tried", b.seam.proposalRequests.get() <= 2)
        }

    // ── (h) ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the partial-status notice has an English resource equal to the text and a Spanish one with both counts`() {
        val field =
            ChatSendCopy::class.java.declaredFields.firstOrNull { it.name == "STATUS_PARTIAL" }
                ?: throw AssertionError("ChatSendCopy has no STATUS_PARTIAL notice")
        val english = field.apply { isAccessible = true }.get(null) as String
        assertEquals(EN_TEXT, english)

        val en = strings("values")["chat_send_status_partial"]
        val es = strings("values-es")["chat_send_status_partial"]
        assertEquals("English resource chat_send_status_partial must be the notice's text", english, en)
        assertEquals("Spanish resource chat_send_status_partial", ES_TEXT, es)
        for ((lang, text) in listOf("en" to en!!, "es" to es!!)) {
            assertTrue("$lang: no reached count", "%1\$d" in text)
            assertTrue("$lang: no total count", "%2\$d" in text)
            assertEquals("$lang: formats as a sentence", 2, Regex("%[0-9]\\$").findAll(text).count())
        }
        assertEquals(
            "the chat maps the notice to its resource",
            resourceId("chat_send_status_partial"),
            ChatSendCopy.localizedResource(english),
        )
    }

    private fun resourceId(name: String): Int? =
        Class
            .forName("co.electriccoin.zcash.ui.R\$string")
            .fields
            .firstOrNull { it.name == name }
            ?.getInt(null)

    private fun strings(dir: String): Map<String, String> {
        var root: File? = File("").absoluteFile
        while (root != null && !(File(root, "gradle.properties").isFile && File(root, "README.md").isFile)) root = root.parentFile
        val res = File(root ?: throw AssertionError("repo root not found"), "ui-lib/src/main/res/ui")
        return res
            .listFiles()
            .orEmpty()
            .flatMap { module -> File(module, dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .fold(emptyMap()) { acc, f ->
                acc +
                    Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(f.readText())
                        .associate { m -> m.groupValues[1] to m.groupValues[2].replace("\\'", "'") }
            }
    }

    private companion object {
        const val CONTACT_A = "usyntheticd7statuscontacta01"
        const val CONTACT_B = "usyntheticd7statuscontactb01"
        const val CONTACT_C = "usyntheticd7statuscontactc01"
        val CONTACTS = listOf(CONTACT_A, CONTACT_B, CONTACT_C)
        const val STATUS = "synthetic status"
        const val EN_TEXT = "Your status reached %1\$d of %2\$d contacts. Set it again later to reach the rest."
        const val ES_TEXT = "Tu estado llegó a %1\$d de %2\$d contactos. Vuelve a configurarlo más tarde para llegar al resto."
    }
}
