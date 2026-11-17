package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KI23-X1 (fix-all 2.13.0, train T6b fixup; T6b cross review, must-fix 1, GPT KI-23 F1 and Grok KI-23 F1) — the
 * automatic answer to a member's key request and the creator's Resend key never both pay for one member and key.
 *
 * KI-23 calls the member's paid count for a key "the one door": once it is 1, an automatic key answer goes over the
 * relays only. But the automatic answer (`ChatViewModel.grantGroupKeyRequestOnce`) and the Resend key
 * (`GroupViewModel`, its `beforeSubmit`) read and write that count with no shared claim, and each put-back
 * (`count == 1 -> 0`) cannot tell its own 1 from the other's:
 *
 * - the automatic answer stamps the count (0 to 1) and is handed to the SDK;
 * - the creator confirms Resend key for the same member; it sees the count at 1, leaves it and pays;
 * - the automatic answer then proves it sent nothing (rejected with nothing stored, or the shortfall the Resend's own
 *   transaction caused) and puts "its" 1 back to 0, although the Resend's payment relied on it;
 * - the member's next key request finds the count at 0 and is paid for again: two payments for one key to one member.
 *
 * Wanted: one process-wide claim per member and key for both payers. A Resend confirmed while the automatic answer
 * holds it pays nothing; the answer's own outcome decides the count; at most one payment reaches the member.
 *
 * The answering ChatViewModel has no send-attempt ledger here, so the rule is tested on its own (the ledger would hide
 * the race for the ten blocks its entry lives). Every address, key, group id and transaction id is synthetic. Nothing
 * here pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticKeyAnswerAndResendKeyPayOnceTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    @Test
    fun `a Resend confirmed while the automatic answer is with the SDK - one payment, and the count stays true`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            // "Key not delivered" for the member, and its Resend question open on the creator's group screen.
            w.world.ledger.seedNotSent(w.world.seed("KEY_MEMO", w.world.memberSubject(MA3.A), "epoch:1"), GroupLedgerWorld.H + 60)
            val s = w.screen()
            val offer =
                w.world.keyBadges(s)[MA3.A]
                    ?: throw AssertionError("fixture sanity: no \"Key not delivered\" badge (ledger: ${w.ledger.all()})")
            assertTrue("fixture sanity: this build has a Resend for a key", w.world.tapResend(s, "resendKeyToMember", MA3.A, offer))
            assertEquals("fixture sanity: the Resend asks its price", "GROUP_KEY_SYNC", w.world.question(s))

            // The member's key request arrives; its automatic answer is stamped and handed to the SDK, and waits there.
            val chat = w.chatVm(w.world.seam, ledger = false)
            val held = CompletableDeferred<Unit>()
            w.world.seam.holdSubmits = held
            // The first submit (the automatic answer) is rejected with nothing stored; every later one is taken.
            w.world.seam.outcome = { n, ids ->
                if (n == 1) SubmitResult.Failure(emptyList(), 18, "rejected") else SubmitResult.Success(ids)
            }
            w.chain(chat, MA3.A, tx = 1)
            awaitTrue { w.submits() == 1 }
            assertEquals("fixture sanity: the automatic answer reached the SDK", 1, w.submits())
            assertEquals("fixture sanity: the automatic answer stamped the paid count", 1, w.store.count(MA3.A))

            // The creator taps Pay meanwhile.
            w.world.seam.holdSubmits = null
            w.world.confirm(s)
            val resendPaid = w.submits() == 2

            // The automatic answer comes back: rejected, nothing stored.
            held.complete(Unit)
            awaitIdle(chat)
            assertFalse("fixture sanity: the rejected answer's request record is withdrawn", w.chainRequestId(1) in w.store.granted)

            if (resendPaid) {
                assertEquals(
                    "the Resend paid for the member's key while the automatic answer was with the SDK; that answer then " +
                        "sent nothing and put the paid count back to 0, so the member's next key request pays again",
                    1,
                    w.store.count(MA3.A),
                )
            }

            // The member asks again.
            w.chain(chat, MA3.A, tx = 2)
            awaitIdle(chat)

            assertEquals(
                "the automatic answer and the creator's Resend key both paid for the same key to the same member " +
                    "(${w.submits()} submits, the first one rejected with nothing stored)",
                1,
                w.submits() - 1,
            )
            assertEquals("after one payment the paid count is not 1", 1, w.store.count(MA3.A))
        }

    /** Control, green before and after: with no automatic answer running, the Resend pays once and closes the door. */
    @Test
    fun `control - a Resend with no automatic answer running pays once and a later request pays nothing`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.world.ledger.seedNotSent(w.world.seed("KEY_MEMO", w.world.memberSubject(MA3.A), "epoch:1"), GroupLedgerWorld.H + 60)
            val s = w.screen()
            val chat = w.chatVm(w.world.seam, ledger = false)

            w.world.tapResend(s, "resendKeyToMember", MA3.A, w.world.keyBadges(s).getValue(MA3.A))
            w.world.confirm(s)
            w.chain(chat, MA3.A, tx = 1)
            awaitIdle(chat)

            assertEquals(1, w.submits())
            assertEquals(1, w.store.count(MA3.A))
        }
}
