package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ZC-538: the existing automatic KEX path retains its standing consent and owed marker after refusal. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefusedAutomaticHandshakeKeepsItsConsentAndPaysNothingWhileUpdateRequiredTest {
    private val scopes = SendHarnessScopes()

    @Before
    fun setUp() {
        NetworkUpgradeTestApi.resetIfPresent()
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        scopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        NetworkUpgradeTestApi.resetIfPresent()
    }

    @Test
    fun control_compatible_kex_pays_once_with_the_existing_consent() =
        runBlocking {
            val peer = "usyntheticzc538control001"
            val prefs =
                AutoSpendPrefs(consent = true).apply {
                    session(peer, complete = false)
                    ownBootSent[peer] = true
                }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            assertEquals(1, seam.submits.get(), "compatible control did not pay through this fixture")
            assertEquals(1, seam.broadcasts.size)
            assertTrue(prefs.consent)
        }

    @Test
    fun control_compatible_kex_without_existing_consent_never_pays() =
        runBlocking {
            val peer = "usyntheticzc538nocontrol1"
            val prefs =
                AutoSpendPrefs(consent = false).apply {
                    session(peer, complete = false)
                    ownBootSent[peer] = true
                }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            assertEquals(0, seam.proposalRequests.get(), "automatic KEX bypassed existing consent")
            assertEquals(0, seam.submits.get())
            assertTrue(seam.broadcasts.isEmpty())
            assertFalse(prefs.consent)
        }

    @Test
    fun refused_kex_keeps_consent_withdraws_paid_stamp_stays_owed_and_does_not_loop() =
        runBlocking {
            val peer = "usyntheticzc538refused001"
            val prefs =
                AutoSpendPrefs(consent = true).apply {
                    session(peer, complete = false)
                    ownBootSent[peer] = true
                }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            NetworkUpgradeTestApi.latch()
            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            assertEquals(0, seam.proposalRequests.get(), "update refusal must happen before a proposal")
            assertEquals(0, seam.submits.get())
            assertTrue(prefs.paidRecords.isEmpty(), "a definite non-send retained its paid stamp")
            assertTrue(prefs.consent, "refusal consumed the existing consent")
            assertFalse(prefs.ownBootSent[peer] ?: true, "KEX no longer remains owed")
            awaitIdle(vm)
            assertEquals(0, seam.proposalRequests.get(), "a refused trigger looped")
            NetworkUpgradeTestApi.started()
            prefs.ownBootSent[peer] = true
            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            assertEquals(1, seam.broadcasts.size, "the same standing consent must still allow the owed KEX")
            assertEquals(1, seam.submits.get())
        }

    @Test
    fun clearing_guard_does_not_release_a_handshake_when_existing_consent_was_withdrawn() =
        runBlocking {
            val peer = "usyntheticzc538noconsent1"
            val prefs =
                AutoSpendPrefs(consent = true).apply {
                    session(peer, complete = false)
                    ownBootSent[peer] = true
                }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            NetworkUpgradeTestApi.latch()
            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            prefs.consent = false
            NetworkUpgradeTestApi.started()
            prefs.ownBootSent[peer] = true
            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitIdle(vm)
            assertEquals(0, seam.proposalRequests.get(), "guard clear bypassed standing consent")
            assertEquals(0, seam.submits.get())
            assertFalse(prefs.consent)
        }
}
