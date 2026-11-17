package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8G-X1 (fix-all 2.13.0, train T6b fixup; T6b cross review, must-fix 2, GPT F8-G F2) — the "Key not delivered"
 * Resend pays only while nothing else is delivering that key.
 *
 * A member's key message (KEY_MEMO) for the current key was proven never to have reached the chain, so Group Settings
 * shows "Key not delivered" with Resend. A key message and a key grant (the automatic answer to the member's own key
 * request, KEY_GRANT) deliver the same key. The badge already hides an offer whose other kind is on its way or mined
 * (`GroupSendAttemptRules.keyOffers`), but the check made before the price question and again at Pay
 * (`GroupViewModel.keyOfferStands`) does not: when the member's automatic key answer goes on-chain while the Resend
 * question is open, Pay builds and pays a second key message for the same key to the same member.
 *
 * Wanted: while the other kind of the same member and key number is on its way (LIVE) or mined, a Resend asks nothing
 * when tapped and builds nothing when confirmed; the screen reloads and shows no badge for the member.
 *
 * Every address, key, group id and transaction id is synthetic. Nothing here pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyResendWhileTheOtherKindIsOnItsWayPaysNothingTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    /** A key message to [MA3.A] for key number 1, proven never to have reached the chain. */
    private fun failedKeyMemo(w: KeyGrantWorld): EntryView =
        w.world.ledger.seedNotSent(w.world.seed("KEY_MEMO", w.world.memberSubject(MA3.A), "epoch:1"), GroupLedgerWorld.H + 60)

    /** The member's automatic key answer for the same key, on its way ([state] LIVE) or mined ([state] MINED). */
    private fun grantFor(
        w: KeyGrantWorld,
        state: String,
    ) {
        val grant = w.world.seed("KEY_GRANT", w.world.memberSubject(MA3.A), "epoch:1")
        if (state == "MINED") w.world.ledger.seedMined(grant, GroupLedgerWorld.H + 5)
    }

    private suspend fun badge(
        w: KeyGrantWorld,
        s: GroupScreen,
    ): Any =
        w.world.keyBadges(s)[MA3.A]
            ?: throw AssertionError("fixture sanity: no \"Key not delivered\" badge for the member (ledger: ${w.ledger.all()})")

    @Test
    fun `a key grant that goes out while the Resend question is open - Pay builds nothing`() =
        runBlocking {
            for (state in listOf("LIVE", "MINED")) {
                GroupLedgerTests.resetProcess()
                val w = KeyGrantWorld(scheduler)
                failedKeyMemo(w)
                val s = w.screen()
                val tapped = w.world.tapResend(s, "resendKeyToMember", MA3.A, badge(w, s))
                assertTrue("fixture sanity: this build has a Resend for a key", tapped)
                assertEquals("fixture sanity: the Resend asks its price", "GROUP_KEY_SYNC", w.world.question(s))

                // While the question is open, the member's own key request is answered on-chain.
                grantFor(w, state)
                w.world.confirm(s)

                assertEquals(
                    "[$state] the member's key answer for this key is ${state.lowercase()}, and Pay on the Resend question " +
                        "built a second key message for the same key to the same member",
                    0,
                    w.world.seam.proposals.size,
                )
                assertEquals("[$state] something was submitted", 0, w.submits())
                assertEquals("[$state] the Resend raised the member's paid count", 0, w.store.count(MA3.A))
                assertTrue("[$state] the member still shows a badge", MA3.A !in w.world.keyBadges(s))
            }
        }

    @Test
    fun `a badge tapped after the other kind went out asks no price`() =
        runBlocking {
            for (state in listOf("LIVE", "MINED")) {
                GroupLedgerTests.resetProcess()
                val w = KeyGrantWorld(scheduler)
                failedKeyMemo(w)
                val s = w.screen()
                val offer = badge(w, s)
                grantFor(w, state)

                assertTrue("fixture sanity: this build has a Resend for a key", w.world.tapResend(s, "resendKeyToMember", MA3.A, offer))

                assertNull(
                    "[$state] the member's key answer for this key is ${state.lowercase()}, and the out-of-date badge still " +
                        "asks the price of another key message",
                    w.world.question(s),
                )
                assertEquals("[$state] something was built", 0, w.world.seam.proposals.size)
            }
        }

    /** Control, green before and after: with the other kind failed too, the Resend still pays exactly once. */
    @Test
    fun `control - with the other kind failed as well the Resend pays once`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            failedKeyMemo(w)
            w.world.ledger.seedNotSent(w.world.seed("KEY_GRANT", w.world.memberSubject(MA3.A), "epoch:1"), GroupLedgerWorld.H + 60)
            val s = w.screen()

            w.world.tapResend(s, "resendKeyToMember", MA3.A, badge(w, s))
            w.world.confirm(s)

            assertEquals("the Resend did not pay exactly once", 1, w.submits())
        }
}
