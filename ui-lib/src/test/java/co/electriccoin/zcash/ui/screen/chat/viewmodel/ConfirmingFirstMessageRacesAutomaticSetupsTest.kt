package co.electriccoin.zcash.ui.screen.chat.viewmodel

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
 * ZC-1271 (money-path rule 2): every new block runs the automatic setups (onChainTip: retryPendingTunnelZboots first) and,
 * with this fix, the queued first message, and they compete for the wallet's one spendable note.
 *
 * Must hold: a wallet funded for the first time pays no setup its owner did not consent to, while the message it did
 * accept goes; and when a setup takes the note first, the message waits or stops, and is never paid above its accepted
 * total. The controls (green before and after) prove the second contact's ZBOOT really is on offer in this fixture: it pays
 * under the consent and not without it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmingFirstMessageRacesAutomaticSetupsTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1271racepeer0001"
        const val OTHER = "usyntheticzc1271racesetup001"
        const val TYPED = "synthetic first words racing a setup"
        const val BOTH_LEGS = 2
        const val SETTLE_MS = FundsConfirmingChat.SETTLE_MS
        const val FEE_AFTER_THE_NOTE_WENT = 100_000L
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
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
        LiveSenders.resetForTest()
    }

    @Test
    fun `a newly funded wallet with no consent pays the accepted message and no setup`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER).apply { consent = false }
            world.competingSetupTo(OTHER)
            val vm = world.screen()
            val row = world.acceptWhileConfirming(vm, TYPED)

            world.block(vm)
            assertTrue("a stranded wallet paid while its funds confirm", world.staysAt(0))
            assertTrue("the row gave up", world.isWaiting(row.id))

            world.plan = "Fundable"
            world.block(vm)
            world.block(vm)

            assertTrue(
                "the accepted message did not go: ${world.seam.submits.get()} submits",
                PaymentHarness.eventually(SETTLE_MS) { world.funds.paid.count { PEER in it.first } >= BOTH_LEGS },
            )
            assertTrue("something more was paid", world.staysAt(BOTH_LEGS))
            assertEquals("a setup with no consent was paid", 0L, world.funds.paidTo(OTHER))
        }

    @Test
    fun `a setup that took the note leaves the message below its accepted total or stopped for a decision`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            world.competingSetupTo(OTHER)
            val vm = world.screen()
            val row = world.acceptWhileConfirming(vm, TYPED)

            // Whatever is submitted first takes the one note; what the message builds after it costs far more.
            world.funds.feeFor = { uri ->
                if (world.seam.submits.get() >= 1 && PEER in uri) FEE_AFTER_THE_NOTE_WENT else FundsConfirmingChat.NORMAL_FEE
            }
            world.plan = "Fundable"
            world.block(vm)
            world.block(vm)
            assertTrue(
                "nothing took the note, so this proves nothing",
                PaymentHarness.eventually(SETTLE_MS) { world.seam.submits.get() >= 1 },
            )
            world.paymentsSettled()

            assertTrue(
                "the message neither waits nor stopped for a decision",
                world.isWaiting(row.id) || world.needsDecision(vm, row.id),
            )
            assertTrue(
                "paid ${world.funds.paidTo(PEER)} against an accepted total of ${row.acceptedTotal}",
                world.funds.paidTo(PEER) <= row.acceptedTotal,
            )
        }

    // ── controls: the second contact's ZBOOT really is on offer in this fixture ──────────────────────────

    @Test
    fun `control - a funded wallet under the consent pays the competing setup once`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            world.competingSetupTo(OTHER)
            world.plan = "Fundable"
            val vm = world.screen()

            world.block(vm)

            assertTrue(
                "fixture sanity: the second contact's ZBOOT was not paid under the consent, so the race tests prove nothing",
                PaymentHarness.eventually(SETTLE_MS) { world.funds.paidTo(OTHER) > 0L },
            )
            assertTrue("the setup was paid twice", world.staysAt(1))
        }

    @Test
    fun `control - with no consent the same setup pays nothing`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER).apply { consent = false }
            world.competingSetupTo(OTHER)
            world.plan = "Fundable"
            val vm = world.screen()

            world.block(vm)
            world.block(vm)

            assertTrue("a setup with no consent was paid", world.staysAt(0))
        }
}
