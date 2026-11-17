package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.HistoricalOwnKeyLedger
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
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
import java.util.Collections

/**
 * P2d (1) — sending to a peer during a restore made that chat's history unreadable.
 *
 * After a seed restore every chat key store is empty until the history scan reinstalls our keys from the seed.
 * At 4e16987f9 a message to a peer we held no key for yet went straight to sendKEXMessage, which created a
 * brand-new key for that peer and paid a KEX carrying it; the scan then refused to replace the stored key, so
 * our historical key was never reinstalled and every earlier message in that chat stayed unreadable. The message
 * itself also went out unencrypted. A send to such a peer must wait until the history has been read: no key, no
 * KEX, nothing broadcast, and the message kept as failed so the user can retry it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreSendWaitsForHistoryTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a message to a keyless peer while the history is still being read creates no key and sends nothing`() =
        runBlocking {
            val prefs = KeylessPeerPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs.base, seam)
            PaymentHarness.setField(vm, "zchatPreferences", prefs.proxy)
            PaymentHarness.setField(vm, "seedKeyProvider", restoringProvider(prefs.proxy))

            doSendMessage(vm, "hello after the restore")
            awaitIdle(vm)

            assertEquals("no key may be created for the peer before the history is read", emptyList<String>(), prefs.keyWrites)
            assertEquals("nothing may be proposed or paid: no KEX and no message", emptyList<String>(), seam.proposalUris)
            assertEquals(0, seam.submits.get())
            val row = PaymentHarness.pendingMessages(vm).single()
            assertEquals("the message is kept as failed so it can be retried", MessageStatus.FAILED, row.status)
            val state = PaymentHarness.sendMessageState(vm)
            assertTrue("the user is told why: $state", state is SendMessageState.Error && state.message.contains("restor"))
        }

    @Test
    fun `an automatic KEX to a keyless peer while the history is still being read creates no key and pays nothing`() =
        runBlocking {
            val prefs = KeylessPeerPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs.base, seam)
            PaymentHarness.setField(vm, "zchatPreferences", prefs.proxy)
            PaymentHarness.setField(vm, "seedKeyProvider", restoringProvider(prefs.proxy))
            prefs.ownBootSent = true // ensureNostrBootstrapSent stamps this before it calls sendKEXMessage

            vm.sendKEXMessage(PEER, PaymentHarness.SELF)
            awaitIdle(vm)

            assertEquals("no key may be created for the peer before the history is read", emptyList<String>(), prefs.keyWrites)
            assertEquals("no KEX may be proposed or paid", emptyList<String>(), seam.proposalUris)
            assertEquals("the bootstrap marker is re-armed so the KEX runs once the history is read", false, prefs.ownBootSent)
        }

    /**
     * R2-4 (b): a reply took none of these precautions. To a peer we hold no key for yet (a restored wallet whose
     * scan has not reinstalled it) it went out on chain unencrypted and was charged. It must wait like a message:
     * nothing sent or charged, the reply kept as failed with Retry, and the user told why.
     */
    @Test
    fun `a reply to a keyless peer while the history is still being read sends nothing and is kept for retry`() =
        runBlocking {
            val prefs = KeylessPeerPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs.base, seam)
            PaymentHarness.setField(vm, "zchatPreferences", prefs.proxy)
            PaymentHarness.setField(vm, "seedKeyProvider", restoringProvider(prefs.proxy))

            vm.sendReply(PEER, "reply after the restore", "synthetic-quoted-txid", "quoted", 1_000L)
            awaitIdle(vm)

            assertEquals("no key may be created for the peer before the history is read", emptyList<String>(), prefs.keyWrites)
            assertEquals("nothing may be proposed or paid: the reply would go out unencrypted", emptyList<String>(), seam.proposalUris)
            assertEquals(0, seam.submits.get())
            val row = PaymentHarness.pendingMessages(vm).single()
            assertEquals("the reply is kept as failed so it can be retried", MessageStatus.FAILED, row.status)
            val storedRows = prefs.base.pending
            val stored = storedRows.values.single()
            assertEquals("the stored row is failed too (Retry survives a restart)", MessageStatus.FAILED.name, stored.status)
            val state = PaymentHarness.sendMessageState(vm)
            assertTrue("the user is told why: $state", state is SendMessageState.Error && state.message.contains("nothing was sent"))
        }

    /** The provider a restoring wallet has: the scan is complete but no pass has read the whole history yet. */
    private fun restoringProvider(store: ZchatPreferences) =
        SeedDerivedE2EKeyProvider(
            store = store,
            seedProvider = { ByteArray(64) { (it * 13 + 1).toByte() } },
            historyScanComplete = { false },
            historicalOwnKeys = HistoricalOwnKeyLedger(),
            ownHistoryRead = { false },
        )

    private fun doSendMessage(
        vm: ChatViewModel,
        text: String,
    ) {
        try {
            ChatViewModel::class.java
                .getDeclaredMethod(
                    "doSendMessage",
                    String::class.java,
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Long::class.javaObjectType,
                ).apply { isAccessible = true }
                .invoke(vm, PEER, text, 1_000L, null, 0, null)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private companion object {
        const val PEER = "usyntheticrestoresendwait001"
    }
}

/**
 * The payment-path preferences fake, except that we hold NO key for the peer (a restored wallet whose history
 * scan has not reinstalled it yet). Key writes are recorded; any other call the payment fake does not answer
 * gets a neutral default, so the send path can run to its end either way.
 */
private class KeylessPeerPrefs(
    private val peer: String,
) {
    val base = PaymentPrefs(peer)
    val keyWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var ownBootSent: Boolean = false

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getE2EOurPublicKey", "getE2EPrivateKey", "getE2EPeerPublicKey" -> null
                "setE2EOurKeys", "setRecoveredE2EKeys" -> {
                    keyWrites += "${method.name}(${(args!![0] as String) == peer})"
                    null
                }
                "setE2EKeyVersion", "setE2EKeyProvenance", "setNextE2ESeedKeyIndex", "setE2ERecoveryFloorPending" -> null
                "getNextE2ESeedKeyIndex" -> 0
                "isE2ERecoveryFloorPending" -> false
                "getE2EKeyProvenance" -> null
                "isOwnBootSent" -> ownBootSent
                "setOwnBootSent" -> {
                    ownBootSent = args!![1] as Boolean
                    // R5-2: a working store reports its write.
                    true
                }
                "isE2EKeyExchangeComplete" -> false
                "toString" -> "KeylessPeerPrefs"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> {
                    val answer = method.invoke(base.proxy, *(args ?: emptyArray()))
                    answer ?: neutral(method.returnType)
                }
            }
        } as ZchatPreferences

    private fun neutral(type: Class<*>): Any? =
        when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
}
