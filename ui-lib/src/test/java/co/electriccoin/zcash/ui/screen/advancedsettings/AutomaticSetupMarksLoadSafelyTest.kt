package co.electriccoin.zcash.ui.screen.advancedsettings

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupKind
import co.electriccoin.zcash.ui.screen.chat.model.QueuedAutomaticSetup
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.ChatViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePrefs
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeSeam
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ZC1013-X4 (recheck of T7aD round 2, 20 Oct 2026, findings 1 and 3) — the stored "Not now" marks are read safely:
 *
 *  (a) a store whose read fails never lets the standing consent pay: today a read that throws leaves the marks empty for
 *      the whole process, so a key reply the user declined is paid under the consent. Wanted: with marks that cannot be
 *      read every automatic setup asks (declined until asked) and nothing is paid;
 *  (b) a check made while another thread is still reading the marks waits for that read: today the marks are marked
 *      "loaded" before they are read, so the second check sees none and lets the consent pay;
 *  (c) a setup that went, or never may go, leaves the stored marks too: today done() drops it from memory only, so after
 *      a restart it still counts as declined.
 *
 * Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticSetupMarksLoadSafelyTest {
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

    private val queueClass = Class.forName("co.electriccoin.zcash.ui.screen.chat.model.AutomaticSetupQueue")
    private val queue = queueClass.getField("INSTANCE").get(null)

    private fun endProcess() {
        for (name in listOf("queuedAutomaticSetups", "dismissedAutomaticSetups")) {
            val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name } ?: continue
            field.isAccessible = true
            (field.get(null) as? MutableSet<*>)?.clear()
        }
        for (field in queueClass.declaredFields) {
            field.isAccessible = true
            when (val value = runCatching { field.get(queue) }.getOrNull()) {
                is MutableSet<*> -> value.clear()
                is AtomicBoolean -> value.set(false)
            }
        }
    }

    private fun call(
        name: String,
        vararg args: Any,
    ): Any? {
        val method =
            queueClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("AutomaticSetupQueue has no $name with ${args.size} parameter(s)")
        return try {
            method.invoke(queue, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `(a) marks that cannot be read never let the standing consent pay a key reply`() =
        runBlocking {
            val peer = "usyntheticzc1013x4peer000001"
            val prefs = HandshakePrefs(peer, consent = true)
            prefs.declinedReadFails = true
            val seam = HandshakeSeam(peer)
            val chat = HandshakeHarness.handshakeViewModel(prefs, seam)

// W-62 (ZC-599), as e8c3da340 did for AutomaticSetupSwitchOffAsksAgainTest: a key exchange at a chat that still waits
            // for the contact's first key now waits for Accept. This case is about the question, so the contact's key is already
            // held (the key exchange repeats it).
            val keys = E2EEncryption.generateKeyPair()
            prefs.e2ePeerKey[peer] = keys.publicKey
            HandshakeHarness.handleKexMemo(chat, HandshakeHarness.kexMemoFrom(peer, keys), "synthetic-x4")
            HandshakeHarness.awaitIdle(chat)

            assertEquals("with marks that cannot be read the standing consent paid a key reply", 0, seam.proposalRequests.get())
            assertTrue(
                "with marks that cannot be read the key reply did not ask",
                HandshakeHarness.sendMessageState(chat) is SendMessageState.NeedsHandshakeSpendConsent,
            )
        }

    @Test
    fun `(b) a check made while the marks are being read waits for them`() {
        val peer = "usyntheticzc1013x4peer000002"
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prefs =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
                when (method.name) {
                    "getDeclinedAutomaticSetups" -> {
                        reading.countDown()
                        release.await(30, TimeUnit.SECONDS)
                        setOf("KEXACK\t$peer\t")
                    }
                    "setDeclinedAutomaticSetups" -> true
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> null
                }
            } as ZchatPreferences
        val setup = QueuedAutomaticSetup(AutomaticSetupKind.KEXACK, peer)
        val first = Thread { call("asksUnderConsent", setup, prefs) }.apply { start() }
        assertTrue("fixture: the first check reads the marks", reading.await(30, TimeUnit.SECONDS))
        val second = AtomicReference<Any?>(null)
        val other = Thread { second.set(call("asksUnderConsent", setup, prefs)) }.apply { start() }
        other.join(300)
        release.countDown()
        other.join(30_000)
        first.join(30_000)
        assertEquals("a check made during the read did not wait for the stored marks", true, second.get())
    }

    @Test
    fun `(c) a setup that is done leaves the stored marks too`() {
        val peer = "usyntheticzc1013x4peer000003"
        val stored = mutableSetOf("KEXACK\t$peer\t")
        val prefs =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
                when (method.name) {
                    "getDeclinedAutomaticSetups" -> stored.toSet()
                    "setDeclinedAutomaticSetups" -> {
                        @Suppress("UNCHECKED_CAST")
                        val marks = args!![0] as Set<String>
                        stored.clear()
                        stored += marks
                        true
                    }
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> null
                }
            } as ZchatPreferences
        val setup = QueuedAutomaticSetup(AutomaticSetupKind.KEXACK, peer)
        assertEquals("fixture: the stored mark is read", true, call("asksUnderConsent", setup, prefs))

        call("done", setup, prefs)

        assertEquals("a setup that is done stays in the stored marks", emptySet<String>(), stored.toSet())
    }
}
