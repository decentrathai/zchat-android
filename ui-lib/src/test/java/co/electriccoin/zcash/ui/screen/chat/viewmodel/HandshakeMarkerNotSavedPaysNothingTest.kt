package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * R5-2 (round 4 report, section 5B) — four handshake markers are paid against although their write was
 * never confirmed.
 *
 * Each marker below is written immediately before an on-chain memo nobody asked for is paid for, and is
 * the record that stops the same memo being paid a second time after a restart. None of the four call
 * sites looks at whether the write reached the disk; three of them are written with `apply()`, in the
 * background, so a process kill in that window loses the marker and the next trigger pays again.
 *
 * Wanted, exactly as R4-1 did for the paid-setup record: an attempt whose marker could not be written
 * sends nothing and re-arms, so nothing is spent that the app cannot later prove it already spent.
 *
 * The fakes below answer the marker write with false and keep nothing, which is what the store reads
 * after a kill. Every "nothing was paid" test is paired with a control through the same fixture.
 * All keys are generated per run and every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeMarkerNotSavedPaysNothingTest {
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

    /**
     * [delegate] with one setter that never saves: called with a non-null / true value it answers false
     * and records nothing; every other call, including the re-arm, goes through.
     */
    private fun writeNeverSaved(
        delegate: ZchatPreferences,
        setter: String,
        valueArg: Int = 1,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            val value = args?.getOrNull(valueArg)
            if (method.name == setter && value != null && value != false) {
                false
            } else {
                try {
                    method.invoke(delegate, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences

    // ---------------------------------------------------------------- KEXACK

    @Test
    fun `a KEXACK whose sent-marker could not be written sends nothing`() =
        runBlocking {
            val peer = "usyntheticackmarkerunsaved01"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs =
                HandshakePrefs(peer).apply {
                    e2ePeerKey[peer] = peerKeys.publicKey
                    receivedKex[peer] = peerKeys.publicKey
                }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(writeNeverSaved(prefs.proxy, "setSentKexAckPubkey"), seam)

            vm.retryKexAckIfResponder(peer)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(
                "the KEXACK was paid although the marker that stops the next chat open paying it again was " +
                    "never written",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals(0, seam.broadcasts.size)
            assertFalse("no sent-marker may be left behind", prefs.sentKexAck.containsKey(peer))
        }

    /** Control (passes before and after): with a store that records the write, the KEXACK is paid once. */
    @Test
    fun `control - a written sent-marker lets the KEXACK pay once`() =
        runBlocking {
            val peer = "usyntheticackmarkerunsaved02"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs =
                HandshakePrefs(peer).apply {
                    e2ePeerKey[peer] = peerKeys.publicKey
                    receivedKex[peer] = peerKeys.publicKey
                }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(prefs.proxy, seam)

            vm.retryKexAckIfResponder(peer)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(peerKeys.publicKey, prefs.sentKexAck[peer])
        }

    // ------------------------------------------------------- bootstrap / KEX

    @Test
    fun `a KEX whose bootstrap-sent marker could not be written sends nothing`() =
        runBlocking {
            val peer = "usyntheticbootmarkerunsaved1"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false) }
            val seam = HandshakeSeam(peer)
            val vm =
                chatViewModelWith(writeNeverSaved(prefs.proxy, "setOwnBootSent"), seam)
                    .also(AutoSpendVm::wireBootstrapFields)

            vm.ensureNostrBootstrapSent(peer)
            awaitIdle(vm)

            assertEquals(
                "the KEX was paid although the marker written immediately before it never reached the disk",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals(0, seam.broadcasts.size)
            assertFalse("the bootstrap must stay re-armed", prefs.ownBootSent[peer] ?: false)
        }

    /** Control (passes before and after): a written bootstrap marker lets the KEX be paid once. */
    @Test
    fun `control - a written bootstrap-sent marker lets the KEX pay once`() =
        runBlocking {
            val peer = "usyntheticbootmarkerunsaved2"
            val prefs = AutoSpendPrefs(consent = true).apply { session(peer, complete = false) }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            vm.ensureNostrBootstrapSent(peer)
            awaitIdle(vm)

            assertEquals(1, seam.proposalRequests.get())
            assertTrue("the bootstrap marker records the paid KEX", prefs.ownBootSent[peer] ?: false)
        }

    // ----------------------------------------------------------------- ZBOOT

    @Test
    fun `a ZBOOT whose delivered-identity marker could not be written sends nothing and re-arms`() =
        runBlocking {
            val peer = "usyntheticidmarkerunsaved001"
            val prefs =
                AutoSpendPrefs(consent = true).apply { session(peer, complete = true, mode = ConversationMode.TUNNEL) }
            val seam = HandshakeSeam(peer)
            val vm =
                chatViewModelWith(writeNeverSaved(prefs.proxy, "setSentNostrBootPubkey"), seam)
                    .also(AutoSpendVm::wireBootstrapFields)
            AutoSpendVm.setHistoryRead(true)

            AutoSpendVm.sendNostrBoot(vm, peer)
            awaitIdle(vm)

            assertEquals(
                "the ZBOOT was paid although the marker that records our delivered identity was never written",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals(0, seam.broadcasts.size)
            assertEquals("an unsent ZBOOT must leave its identity marker re-armed", null, prefs.sentNostrBoot[peer])
        }

    /** Control (passes before and after): a written identity marker lets the ZBOOT be paid once. */
    @Test
    fun `control - a written delivered-identity marker lets the ZBOOT pay once`() =
        runBlocking {
            val peer = "usyntheticidmarkerunsaved002"
            val prefs =
                AutoSpendPrefs(consent = true).apply { session(peer, complete = true, mode = ConversationMode.TUNNEL) }
            val seam = HandshakeSeam(peer)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)
            AutoSpendVm.setHistoryRead(true)

            AutoSpendVm.sendNostrBoot(vm, peer)
            awaitIdle(vm)

            assertEquals(1, seam.proposalRequests.get())
            assertTrue("the identity marker records the paid ZBOOT", prefs.sentNostrBoot.containsKey(peer))
        }

    // ------------------------------------------------------- group-key grant

    @Test
    fun `a group-key grant whose record could not be written sends nothing`() =
        runBlocking {
            val fixture = GroupGrantFixture(member = "usyntheticgrantmarkerunsav01", consent = true)
            val seam = HandshakeSeam(fixture.member)
            val vm = chatViewModelWith(writeNeverSaved(fixture.proxy, "setGroupKeyRequestGranted"), seam)

            fixture.scan(vm, fixture.txId(41))
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(
                "the group-key grant was paid although the record that stops the next history scan paying it " +
                    "again was never written",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals(0, seam.broadcasts.size)
        }

    /** Control (passes before and after): a written grant record lets the grant be paid once. */
    @Test
    fun `control - a written grant record lets the group-key grant pay once`() =
        runBlocking {
            val fixture = GroupGrantFixture(member = "usyntheticgrantmarkerunsav02", consent = true)
            val seam = HandshakeSeam(fixture.member)
            val vm = chatViewModelWith(fixture.proxy, seam)

            fixture.scan(vm, fixture.txId(42))
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(1, seam.proposalRequests.get())
        }

    // --------------------------------------------------------------- consent

    /**
     * The consent itself is the fifth marker. Granting it releases every queued automatic spend at once, so
     * a grant whose record never reached the disk must release nothing: the app would otherwise spend
     * against a consent that no restart can find again, and the user is asked a second time for memos
     * already paid for. Round 8 D10 (DECISIONS_NIGHT §6): the prompt no longer stays on screen with no word (a
     * dead end); it closes and the storage notice says why nothing was paid (StoragePausedPaidStepNoticeTest).
     */
    @Test
    fun `handshake consent that could not be written releases nothing and says why`() =
        runBlocking {
            val fixture = GroupGrantFixture(member = "usyntheticconsentunsaved001", consent = false)
            val seam = HandshakeSeam(fixture.member)
            val vm = chatViewModelWith(consentNeverSaved(fixture.proxy), seam)

            fixture.scan(vm, fixture.txId(43))
            awaitIdle(vm)
            assertTrue(
                "fixture sanity: without consent the grant asks first",
                HandshakeHarness.sendMessageState(vm) is
                    co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.NeedsHandshakeSpendConsent,
            )

            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(
                "a consent that never reached the disk released the queued automatic spends anyway",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals(
                "a consent that could not be recorded must not read as given: the user is told why nothing was paid",
                co.electriccoin.zcash.ui.screen.chat.model.SendMessageState.Error(
                    co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy.STORAGE_PAUSED_PAID_STEP,
                ),
                HandshakeHarness.sendMessageState(vm),
            )
            assertFalse("a consent that could not be recorded must not read as given", fixture.consent)
        }

    /** [delegate] whose handshake-spend consent is never stored: granting it answers false and keeps nothing. */
    private fun consentNeverSaved(delegate: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "setConsentedToHandshakeSpend") {
                false
            } else {
                try {
                    method.invoke(delegate, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as ZchatPreferences
}
