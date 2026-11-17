package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
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

/**
 * R4-1 (round 3 report, section 4 item 2) — a paid chat setup is paid although its durable record was never
 * written.
 *
 * `PaidSetupRecord.stamp` writes "KEX/ZBOOT for this identity was paid at this moment" just before the
 * attempt submits. That record is the ONLY thing that stops the same leg being paid again inside the
 * one-hour cooldown after a restart — the in-memory markers are gone by then. It is written with `commit()`,
 * but the result is discarded, so a write that never reached the disk reads as recorded and the attempt pays
 * anyway.
 *
 * Wanted: an attempt whose durable paid record could not be written sends nothing. Nothing is spent that the
 * app cannot later prove it already spent.
 *
 * All addresses and keys are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaidSetupRecordNotSavedPaysNothingTest {
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

    /** [prefs] whose paid-setup record never reaches the disk; everything else behaves as usual. */
    private fun recordNeverSaved(prefs: AutoSpendPrefs): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "setHandshakePaidRecord" && args?.get(1) != null) {
                false
            } else {
                try {
                    method.invoke(prefs.proxy, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences

    @Test
    fun `a KEX whose paid record could not be written sends nothing`() =
        runBlocking {
            val peer = "usyntheticpaidrecordkex01"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false) }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(recordNeverSaved(prefs), seam).also(AutoSpendVm::wireBootstrapFields)

            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)

            assertEquals(
                "the KEX was paid although the record that stops a restart paying it again was never written",
                0,
                seam.proposalRequests.get(),
            )
            assertTrue("no paid-setup record may be left behind", prefs.paidRecords.isEmpty())
        }

    @Test
    fun `a ZBOOT whose paid record could not be written sends nothing and re-arms`() =
        runBlocking {
            val peer = "usyntheticpaidrecordboot01"
            val prefs =
                AutoSpendPrefs(consent = true).apply { session(peer, complete = true, mode = ConversationMode.TUNNEL) }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(recordNeverSaved(prefs), seam).also(AutoSpendVm::wireBootstrapFields)
            AutoSpendVm.setHistoryRead(true)

            AutoSpendVm.sendNostrBoot(vm, peer)
            awaitIdle(vm)

            assertEquals(
                "the ZBOOT was paid although the record that stops a restart paying it again was never written",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals("an unsent ZBOOT must leave its identity marker re-armed", null, prefs.sentNostrBoot[peer])
        }

    /** Control (passes before and after): with a store that records the write, both legs are paid once. */
    @Test
    fun `control - a written paid record lets the KEX and the ZBOOT pay once`() =
        runBlocking {
            val kexPeer = "usyntheticpaidrecordkex02"
            val kexPrefs = AutoSpendPrefs(consent = true).apply { session(kexPeer, complete = false) }
            val kexSeam = HandshakeSeam(kexPeer)
            val kexVm = chatViewModelWith(kexPrefs.proxy, kexSeam).also(AutoSpendVm::wireBootstrapFields)
            kexVm.sendKEXMessage(kexPeer, HandshakeHarness.SELF)
            awaitIdle(kexVm)
            assertEquals(1, kexSeam.proposalRequests.get())
            assertTrue("the paid record is what the cooldown reads", kexPrefs.paidRecords.isNotEmpty())

            val bootPeer = "usyntheticpaidrecordboot02"
            val bootPrefs =
                AutoSpendPrefs(consent = true).apply {
                    session(bootPeer, complete = true, mode = ConversationMode.TUNNEL)
                }
            val bootSeam = HandshakeSeam(bootPeer)
            val bootVm = chatViewModelWith(bootPrefs.proxy, bootSeam).also(AutoSpendVm::wireBootstrapFields)
            AutoSpendVm.setHistoryRead(true)
            AutoSpendVm.sendNostrBoot(bootVm, bootPeer)
            awaitIdle(bootVm)
            assertEquals(1, bootSeam.proposalRequests.get())
        }
}
