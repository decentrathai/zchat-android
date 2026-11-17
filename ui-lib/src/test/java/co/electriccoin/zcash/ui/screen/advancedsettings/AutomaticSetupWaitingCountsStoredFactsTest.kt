package co.electriccoin.zcash.ui.screen.advancedsettings

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ChatViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePrefs
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeSeam
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import kotlinx.coroutines.CoroutineDispatcher
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
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC1013-X3 (T7aD review of 19 Oct, b, finding 1, P1) — the turn-on confirm quotes what waits from the question's queue in
 * this process's memory only. After a restart, before any retry has queued them again, a key reply still owed to a contact
 * (the contact's key exchange received, no answer sent, the chat not set up) is not counted: the confirm says less (or
 * nothing), and turning the payments on pays more than it quoted.
 *
 * Wanted: [AutomaticSetupPayments.waitingNow] also counts the key replies the store says are owed (as the in-chat question
 * would, with its count and price); and the waiting line says what it does not count (group key answers not tried again
 * since ZChat started), EN and ES.
 *
 * Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticSetupWaitingCountsStoredFactsTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        endProcess()
    }

    @After
    fun tearDown() {
        endProcess()
        sendScopes.close()
        Dispatchers.resetMain()
    }

    private fun endProcess() {
        for (name in listOf("queuedAutomaticSetups", "dismissedAutomaticSetups")) {
            val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name } ?: continue
            field.isAccessible = true
            (field.get(null) as? MutableSet<*>)?.clear()
        }
        val queue = runCatching { Class.forName(QUEUE) }.getOrNull() ?: return
        val instance = queue.getField("INSTANCE").get(null)
        for (field in queue.declaredFields) {
            field.isAccessible = true
            when (val value = runCatching { field.get(instance) }.getOrNull()) {
                is MutableSet<*> -> value.clear()
                is AtomicBoolean -> value.set(false)
            }
        }
    }

    private suspend fun waitingNow(store: ZchatPreferences): Any {
        val switch =
            AutomaticSetupPayments::class.java
                .getConstructor(ZchatPreferences::class.java, CoroutineDispatcher::class.java)
                .newInstance(store, Dispatchers.Unconfined)
        val method = switch.javaClass.methods.first { it.name == "waitingNow" && it.parameterCount == 1 }
        return suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            try {
                method.invoke(switch, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } ?: throw AssertionError("nothing is quoted")
    }

    private fun property(
        target: Any,
        name: String,
    ): Any? {
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        return target.javaClass.methods
            .first { it.parameterCount == 0 && (it.name == getter || it.name.startsWith("$getter-")) }
            .invoke(target)
    }

    @Test
    fun `after a restart a key reply the store says is owed is counted with the question's count and price`() =
        runBlocking {
            val peer = "usyntheticzc1013x3peer000001"
            val prefs = HandshakePrefs(peer, consent = false)
            val seam = HandshakeSeam(peer)
            val chat = HandshakeHarness.handshakeViewModel(prefs, seam)
// W-62 (ZC-599), as e8c3da340 did for AutomaticSetupSwitchOffAsksAgainTest: a key exchange at a chat that still waits
            // for the contact's first key now waits for Accept. This case is about the question, so the contact's key is already
            // held (the key exchange repeats it).
            val keys = E2EEncryption.generateKeyPair()
            prefs.e2ePeerKey[peer] = keys.publicKey
            HandshakeHarness.handleKexMemo(chat, HandshakeHarness.kexMemoFrom(peer, keys), "synthetic-x3")
            HandshakeHarness.awaitIdle(chat)
            val ask = HandshakeHarness.sendMessageState(chat)
            assertTrue("fixture: the key reply asks first", ask is SendMessageState.NeedsHandshakeSpendConsent)
            ask as SendMessageState.NeedsHandshakeSpendConsent

            endProcess()
            val waiting = waitingNow(prefs.proxy)

            assertEquals("after a restart the owed key reply is not counted", ask.pendingPeers, property(waiting, "transactions"))
            assertEquals(
                "after a restart the owed key reply is not priced",
                ask.estimatedCost.toString(),
                property(waiting, "cost").toString(),
            )
            assertEquals("the count read something the store does not answer", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("quoting paid something", 0, seam.proposalRequests.get())
        }

    @Test
    fun `the waiting line says what it does not count`() {
        val en = plural("values")
        val es = plural("values-es")
        assertTrue("the English line does not say group key answers are not counted: $en", "group key answers" in en)
        assertTrue("the Spanish line does not say it: $es", "respuestas de clave de grupo" in es)
    }

    private fun plural(folder: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val module =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }
                ?: throw AssertionError("could not locate the ui-lib module from ${start.path}")
        val res = module.resolve("src/main/res/ui/advanced_settings/$folder/strings.xml").readText()
        val at = res.indexOf("<plurals name=\"advanced_settings_auto_setup_payments_waiting\">")
        assertTrue("fixture: the waiting plural in $folder", at >= 0)
        return res.substring(at, res.indexOf("</plurals>", at))
    }

    private companion object {
        const val QUEUE = "co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupQueue"
    }
}
