package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Base64

/**
 * R4-1 (round 3 report, section 4 item 1; and the held-accept half of item 2) — a held GROUP_ACCEPT can be
 * paid twice.
 *
 * Our paid "I joined" answer to a group invite is one on-chain transaction. R2-2 (d) holds it until the user
 * agrees to pay for automatic handshakes, and R3-4 keeps that hold on disk across an app kill. Two windows
 * can still charge for it twice:
 *
 * 1. **A failure that follows a stored transaction.** A direct submit can report failure AFTER the SDK stored
 *    the transaction, which it keeps re-broadcasting (`SubmitRejectedException.nothingStored == false`, the
 *    P2a rule the send path already encodes in `provesNothingSent`). `sendHeldGroupAccept` does not look at
 *    that: on any failure with consent withdrawn it puts the accept back, now on disk too. Confirming a move
 *    of the old balance clears consent exactly while such an attempt is on the wire (owner decision 2), so the
 *    window is real, and the next consent pays a second accept for a group the inviter may already have seen.
 *
 * 2. **A removal that never reached the disk.** The stored copy is removed just before the accept is paid, so
 *    a kill after the payment cannot restore it. That removal is written with `commit()`, but its result is
 *    discarded, so a failed write reads as a removal and the accept is paid anyway — the next start restores
 *    it and pays again.
 *
 * Wanted: the accept is put back ONLY when the failure proves nothing was sent, and it is paid only once the
 * store has confirmed the stored copy is gone. Nothing here may cost a second transaction; a genuinely
 * nothing-sent failure must still put it back, or the join is lost.
 *
 * Round 8 P1 (PHONE_TRIAGE F3): the reply is paid only by the group screen's Join (never under the standing consent),
 * and a send that provably paid nothing puts it back as waiting for Join (it was held for the next block or grant).
 * The two double-payment windows above are guarded on that path.
 *
 * All addresses, keys, group ids and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupAcceptHeldNeverPaidTwiceTest {
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

    private fun invitePayload(inviter: String): String {
        val members = JSONArray()
        members.put(inviter)
        members.put(HandshakeHarness.SELF)
        return JSONObject()
            .put("name", "Synthetic group")
            .put("inviter", inviter)
            .put("group_key", Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
            .put("members", members)
            .toString()
    }

    private fun processInvite(
        vm: ChatViewModel,
        groupId: String,
        inviter: String
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod(
                "processGroupInvite",
                String::class.java,
                String::class.java,
                TransactionId::class.java,
                Instant::class.java,
                String::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            .invoke(vm, groupId, invitePayload(inviter), null, Instant.EPOCH, null, null)
    }

    /** [prefs] whose REMOVAL of a held accept never reaches the disk (a failed commit); storing one works. */
    private fun removalNeverSaved(prefs: AutoSpendPrefs): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "setHeldGroupAccept" && args?.get(1) == null) {
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
    fun `a held GROUP_ACCEPT whose send failed after the transaction was stored is never paid again`() =
        runBlocking {
            val inviter = "usyntheticgroupacceptdouble01"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            // The submit reports failure while NAMING a stored transaction: the SDK may still broadcast it.
            // Consent is withdrawn inside the attempt, as confirming a move of the old balance does.
            val seam =
                HandshakeSeam(
                    peer = inviter,
                    firstSubmitOutcome =
                        SubmitResult.Failure(
                            txIds = listOf("synthetic-stored-accept-txid"),
                            code = 18,
                            description = "synthetic rejection after the transaction was stored",
                        ),
                    onFirstAttempt = { prefs.consent = false },
                )
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTH51", inviter)
            awaitIdle(vm)
            GroupInviteDriver.join(vm, "GSYNTH51")

            assertTrue("fixture sanity: the group was joined", prefs.groupInfo.containsKey("GSYNTH51"))
            assertEquals("fixture sanity: the accept was attempted once", 1, seam.proposalRequests.get())
            assertTrue(
                "the accept was put back on disk although its transaction is stored and may still be " +
                    "broadcast; the next consent pays a second accept",
                prefs.heldGroupAccepts.isEmpty(),
            )
            assertTrue("the accept was put back as waiting for Join although it may be broadcast", prefs.needsJoin.isEmpty())

            // The user agrees to pay again (or the app is restarted and they agree), or taps Join again: nothing more
            // may be paid.
            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            GroupInviteDriver.join(vm, "GSYNTH51")
            assertEquals("the held GROUP_ACCEPT was paid a second time", 1, seam.proposalRequests.get())
            assertEquals(1, seam.submits.get())
        }

    @Test
    fun `a held GROUP_ACCEPT is not paid while its stored copy could not be removed`() =
        runBlocking {
            val inviter = "usyntheticgroupacceptdouble02"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val vm = chatViewModelWith(removalNeverSaved(prefs), seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTH52", inviter)
            awaitIdle(vm)
            GroupInviteDriver.join(vm, "GSYNTH52")

            assertTrue("fixture sanity: the group was joined", prefs.groupInfo.containsKey("GSYNTH52"))
            // C5: the stored copy is now removed right before the submit, so the proposal may be built; what must
            // not happen is the submit.
            assertEquals(
                "the accept was paid although its stored copy is still on disk; the next start restores " +
                    "it and pays again",
                0,
                seam.submits.get(),
            )
            assertTrue(
                "the accept stays held so a working store can still send it",
                prefs.heldGroupAccepts.containsKey("GSYNTH52"),
            )
            // Round 8 P1: and it waits for Join again, so the user can still send it.
            assertTrue("the accept no longer waits for Join", prefs.needsJoin.containsKey("GSYNTH52"))
        }

    @Test
    fun `a held GROUP_ACCEPT whose send provably sent nothing is put back so the join is not lost`() =
        runBlocking {
            val inviter = "usyntheticgroupacceptdouble03"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            // A genuine shortfall: no proposal could be built, so nothing was sent. Consent goes at the
            // same moment, so the accept must be held again — dropping it would leave us INVITED for ever.
            val seam =
                HandshakeSeam(
                    peer = inviter,
                    failFirstProposalWith = InsufficientFundsException("synthetic genuine shortfall"),
                    onFirstAttempt = { prefs.consent = false },
                )
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTH53", inviter)
            awaitIdle(vm)
            GroupInviteDriver.join(vm, "GSYNTH53")
            assertEquals("fixture sanity: the accept was attempted once", 1, seam.proposalRequests.get())

            // Round 8 P1: kept on disk as waiting for Join (it was kept as a held reply).
            assertTrue(
                "a send that provably paid nothing must leave the accept waiting for Join on disk",
                prefs.needsJoin.containsKey("GSYNTH53"),
            )

            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            assertEquals("the standing consent paid a group join", 1, seam.proposalRequests.get())
            GroupInviteDriver.join(vm, "GSYNTH53")
            assertEquals("joining again must send the accept once", 2, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertTrue("a sent accept must not stay stored", prefs.heldGroupAccepts.isEmpty())
        }

    /** Control (passes before and after): a plain successful accept is paid once and leaves nothing stored. */
    @Test
    fun `control - a GROUP_ACCEPT that succeeds is paid once and stays sent`() =
        runBlocking {
            val inviter = "usyntheticgroupacceptdouble04"
            val prefs = AutoSpendPrefs(consent = true).apply { session(inviter, complete = true) }
            val seam = HandshakeSeam(inviter)
            val vm = chatViewModelWith(prefs.proxy, seam).also(AutoSpendVm::wireBootstrapFields)

            processInvite(vm, "GSYNTH54", inviter)
            awaitIdle(vm)
            GroupInviteDriver.join(vm, "GSYNTH54")
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)

            vm.grantHandshakeSpendConsent()
            awaitIdle(vm)
            assertEquals(1, seam.proposalRequests.get())
            assertTrue(prefs.heldGroupAccepts.isEmpty())
        }
}
