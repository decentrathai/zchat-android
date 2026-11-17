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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC1013-X2 (register, high; follow-up of ZC1013-X1) — the "Not now" answer to the automatic-setup question lives in
 * memory only. After a restart nothing remembers it, so turning "Automatic setup payments" on in Advanced Settings lets
 * the next retry pay a setup the user declined, with no question, and the turn-on confirm says so ("If ZChat is closed and
 * restarted before that, they are paid").
 *
 * Wanted: the marks are stored (an encrypted store, a new key; nothing 2.12.4 wrote changes) and read again by the next
 * process: after a restart and a turn-on, the declined setup pays nothing on a retry, asks again at the next chat open
 * with the same count and price, and is paid once on Allow (which clears the stored marks); the confirm counts the
 * declined setups after a restart too; its text no longer says they are paid after a restart (EN and ES).
 *
 * The restart is the end of the process: every process-wide set of the question is emptied (and a "loaded" flag reset),
 * the preferences store keeps what it holds. Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticSetupNotNowSurvivesRestartTest {
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

    /** The end of the process: what the question keeps in memory is gone; the store is not touched. */
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

    private fun switch(store: ZchatPreferences): Any =
        AutomaticSetupPayments::class.java
            .getConstructor(ZchatPreferences::class.java, CoroutineDispatcher::class.java)
            .newInstance(store, Dispatchers.Unconfined)

    private suspend fun callSuspend(
        target: Any,
        name: String,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 1 }
                ?: throw AssertionError("AutomaticSetupPayments has no $name")
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(target, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
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
    fun `a setup declined with Not now is not paid after a restart and a turn-on, it asks again`() =
        runBlocking {
            val peer = "usyntheticzc1013x2peer000001"
            val prefs = HandshakePrefs(peer, consent = false)
            val seam = HandshakeSeam(peer)
            val before = HandshakeHarness.handshakeViewModel(prefs, seam)
// W-62 (ZC-599), as e8c3da340 did for AutomaticSetupSwitchOffAsksAgainTest: a key exchange at a chat that still waits
            // for the contact's first key now waits for Accept. This case is about the question, so the contact's key is already
            // held (the key exchange repeats it).
            val keys = E2EEncryption.generateKeyPair()
            prefs.e2ePeerKey[peer] = keys.publicKey
            HandshakeHarness.handleKexMemo(before, HandshakeHarness.kexMemoFrom(peer, keys), "synthetic-x2")
            HandshakeHarness.awaitIdle(before)
            val ask = HandshakeHarness.sendMessageState(before)
            assertTrue("fixture: the key reply asks first", ask is SendMessageState.NeedsHandshakeSpendConsent)
            ask as SendMessageState.NeedsHandshakeSpendConsent
            before.dismissOrchardShieldingWarning()

            endProcess()
            val after = HandshakeHarness.handshakeViewModel(prefs, seam)
            val waiting = callSuspend(switch(prefs.proxy), "waitingNow") ?: throw AssertionError("nothing is quoted")
            assertEquals("after a restart the confirm does not count the declined setup", ask.pendingPeers, property(waiting, "declined"))
            assertEquals("after a restart the declined setup is quoted as paid by the turn-on", 0, property(waiting, "transactions"))
            assertEquals(true, callSuspend(switch(prefs.proxy), "turnOn"))

            after.retryKexAckIfResponder(peer)
            HandshakeHarness.awaitIdle(after)
            assertEquals("after a restart a setup declined with Not now was paid with no question", 0, seam.proposalRequests.get())

            after.askAgainForHandshakeConsent()
            after.retryKexAckIfResponder(peer)
            HandshakeHarness.awaitIdle(after)
            assertEquals("after a restart the declined setup was paid at the chat open", 0, seam.proposalRequests.get())
            val again = HandshakeHarness.sendMessageState(after)
            assertTrue("after a restart the declined setup did not ask again: $again", again is SendMessageState.NeedsHandshakeSpendConsent)
            again as SendMessageState.NeedsHandshakeSpendConsent
            assertEquals(ask.pendingPeers, again.pendingPeers)
            assertEquals(ask.estimatedCost, again.estimatedCost)

            after.grantHandshakeSpendConsent()
            HandshakeHarness.awaitIdle(after)
            HandshakeHarness.awaitTrue { seam.proposalRequests.get() >= 1 }
            assertEquals("Allow did not pay the key reply exactly once", 1, seam.proposalRequests.get())
            assertEquals("Allow left stored Not now marks", emptySet<String>(), prefs.declinedSetups.toSet())
        }

    @Test
    fun `the confirm no longer says declined setups are paid after a restart`() {
        val en = plural("values")
        val es = plural("values-es")
        assertFalse("the English text still says they are paid after a restart: $en", "restart" in en)
        assertFalse("the Spanish text still says they are paid after a restart: $es", "reinicia" in es)
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
        val at = res.indexOf("<plurals name=\"advanced_settings_auto_setup_payments_declined\">")
        assertTrue("fixture: the declined plural in $folder", at >= 0)
        return res.substring(at, res.indexOf("</plurals>", at))
    }

    private companion object {
        const val QUEUE = "co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupQueue"
    }
}
