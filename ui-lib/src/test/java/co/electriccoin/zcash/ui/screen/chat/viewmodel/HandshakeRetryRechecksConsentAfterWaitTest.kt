package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.stillConfirming
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

/**
 * HS-4 — an automatic handshake waiting for a block still pays after Move Old Balance reset consent.
 *
 * The handshake sender checks consent once, before its retry loop. When the first attempt hits a "funds
 * still confirming" shortfall it waits for the next block and tries again without looking at consent a
 * second time. Move Old Balance clears consent (owner decision 2: the moved funds must not pay for queued
 * handshakes without asking again), but at 8134b88d3 a handshake already in that wait still pays once the
 * block lands.
 *
 * Driven through the real KEX call site, sendKEXMessage, whose caller re-arms isOwnBootSent when the
 * sender returns false.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeRetryRechecksConsentAfterWaitTest {
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
    fun `consent revoked while the handshake waits for a block means no second proposal`() =
        runBlocking {
            val peer = "usynthetichandshakeconsent01"
            val prefs = HandshakePrefs(peer, consent = true).apply { ownBootSent = true }
            val seam = HandshakeSeam(peer, failFirstProposalWith = stillConfirming())
            val heights = HeightFeed()
            val vm = handshakeViewModel(prefs, seam, heights, ironwoodPending = 200_000L)

            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitTrue { heights.isWaiting() }
            assertEquals("fixture sanity: the first attempt hit the shortfall", 1, seam.proposalRequests.get())

            // The user confirms Move Old Balance, which clears consent, and then the next block lands.
            prefs.proxy.clearConsentedToHandshakeSpend()
            heights.advance()
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(
                "consent was revoked while the handshake waited for a block, but the retry built and paid a " +
                    "second proposal without asking. Re-check consent before every attempt.",
                1,
                seam.proposalRequests.get(),
            )
            assertEquals(0, seam.broadcasts.size)
            assertTrue(
                "the user must be asked again",
                HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsHandshakeSpendConsent,
            )
            assertEquals("the KEX must re-arm so it resumes once consent is given", false, prefs.ownBootSent)
        }

    /**
     * Control, GREEN before and after the fix: with consent still granted, the same wait retries and pays
     * exactly once.
     */
    @Test
    fun `control - with consent kept the handshake retries after the block and pays once`() =
        runBlocking {
            val peer = "usynthetichandshakeconsent02"
            val prefs = HandshakePrefs(peer, consent = true).apply { ownBootSent = true }
            val seam = HandshakeSeam(peer, failFirstProposalWith = stillConfirming())
            val heights = HeightFeed()
            val vm = handshakeViewModel(prefs, seam, heights, ironwoodPending = 200_000L)

            vm.sendKEXMessage(peer, HandshakeHarness.SELF)
            awaitTrue { heights.isWaiting() }
            heights.advance()
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(2, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertEquals(emptyList<Boolean>(), prefs.ownBootSentWrites)
        }
}
