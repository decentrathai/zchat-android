package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACTS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACT_A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACT_B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACT_C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.OTHER_STATUS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.STATUS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.intProperty
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.peerHash
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.question
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.runToEnd
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.state
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.stateName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-283 (fix-all 2.13.0, train T5b; r8-findings SPECS §2, S1) — a status memo that may have been sent is never paid again.
 *
 * At 998956906 broadcastStatusToContacts counts a contact as reached only when its memo's outcome is SENT: a memo the SDK
 * stored and then reported rejected (MAY_HAVE_BEEN_SENT: the SDK keeps re-broadcasting it) counts as "not reached", and
 * the notice says "Set it again later to reach the rest". Setting the same status again asks for every Shielded contact
 * and pays each one again, including the contact whose first memo may still be mined. Nothing about the broadcast is kept
 * on disk.
 *
 * Wanted (SPECS §2.3):
 * - (a) the contact whose memo may have been sent is counted apart: StatusPartial.maybe = 1, reached = 2;
 * - (b) the same status again asks nothing and sends nothing (A and C have it, B may);
 * - (c) a contact proven not sent (refused before its submit) is the only one the same status goes to again: the
 *   question is STATUS_BROADCAST_REST with 1 on-chain message, and Pay submits to that contact only;
 * - (d) control: another text is a new broadcast, asked for all three;
 * - (e) what each contact got survives a kill: the next process skips A, B and C for the same text.
 *
 * All addresses and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBroadcastMaybeSentIsNeverPaidAgainTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        StatusBroadcastChat.resetStatusBroadcastFlag()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        StatusBroadcastChat.resetStatusBroadcastFlag()
    }

    /** B's memo is stored by the SDK and then rejected: the chokepoint reports MAY_HAVE_BEEN_SENT. */
    private fun maybeSentForB() =
        StatusBroadcastChat(
            submitOutcome = { destination -> if (destination == CONTACT_B) GroupPaidOnceFixture.storedThenRejected() else null },
        )

    /** setUserStatus(text, broadcast = true), Pay on the question, and the broadcast run to its end. */
    private suspend fun setAndPay(
        chat: StatusBroadcastChat,
        vm: ChatViewModel,
        text: String,
    ) {
        vm.setUserStatus(text, broadcast = true)
        assertEquals("fixture: the first broadcast asks for 3", "STATUS_BROADCAST" to 3, question(vm))
        vm.confirmPaidAction()
        runToEnd(vm, chat.heights)
    }

    // ── (a) ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a contact whose status memo may have been sent is counted apart from the contacts it reached`() =
        runBlocking {
            val chat = maybeSentForB()
            val vm = chat.viewModel(OwnChangeAccounts.create())

            setAndPay(chat, vm, STATUS)

            assertEquals("fixture: each contact's memo reached the SDK once", CONTACTS, chat.seam.submittedTo.toList())
            val state = state(vm)
            assertEquals("the user is told the status did not reach everyone: $state", "StatusPartial", stateName(vm))
            assertEquals("reached", 2, intProperty(state, "reached"))
            assertEquals("total", 3, intProperty(state, "total"))
            assertEquals("B's memo may have been sent: it is counted apart, never as not reached", 1, intProperty(state, "maybe"))
        }

    // ── (b) ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `setting the same status again pays nobody when every contact has it or may have it`() =
        runBlocking {
            val chat = maybeSentForB()
            val vm = chat.viewModel(OwnChangeAccounts.create())
            setAndPay(chat, vm, STATUS)
            vm.resetSendState()
            val proposalsBefore = chat.seam.proposalRequests.get()

            vm.setUserStatus(STATUS, broadcast = true)
            vm.confirmPaidAction()
            runToEnd(vm, chat.heights)

            assertNotEquals(
                "the same status was asked about again although A and C have it and B may have it",
                "NeedsPaidActionConfirmation",
                stateName(vm),
            )
            assertEquals(
                "the same status was sent again (to ${chat.seam.proposedTo.drop(proposalsBefore)})",
                proposalsBefore,
                chat.seam.proposalRequests.get(),
            )
            assertEquals("no memo reached the SDK again", 3, chat.seam.submits.get())
        }

    // ── (c) ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the same status again goes only to the contact proven not sent, under a question that counts that one`() =
        runBlocking {
            // B's first proposal meets a real shortfall, which no block can fix: refused before its submit, nothing sent.
            val shortfall = InsufficientFundsException("Insufficient balance", have = Zatoshi(0), need = Zatoshi(17_000))
            var refusedOnce = false
            val chat =
                StatusBroadcastChat(
                    failProposal = { destination, _ ->
                        if (destination == CONTACT_B && !refusedOnce) {
                            refusedOnce = true
                            shortfall
                        } else {
                            null
                        }
                    },
                )
            val vm = chat.viewModel(OwnChangeAccounts.create())
            setAndPay(chat, vm, STATUS)
            assertEquals("fixture: B was not submitted", listOf(CONTACT_A, CONTACT_C), chat.seam.submittedTo.toList())
            assertEquals("fixture: A and C reached", 2, intProperty(state(vm), "reached"))
            vm.resetSendState()

            vm.setUserStatus(STATUS, broadcast = true)

            assertEquals(
                "the question must count only the contact the status has not reached",
                "STATUS_BROADCAST_REST" to 1,
                question(vm),
            )
            vm.confirmPaidAction()
            runToEnd(vm, chat.heights)
            assertEquals(
                "Pay sends the status to B only",
                listOf(CONTACT_A, CONTACT_C, CONTACT_B),
                chat.seam.submittedTo.toList(),
            )
            assertNotEquals("every contact has it now", "StatusPartial", stateName(vm))
        }

    // ── (d) control ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a different status text is a new broadcast asked for every contact`() =
        runBlocking {
            val chat = maybeSentForB()
            val vm = chat.viewModel(OwnChangeAccounts.create())
            setAndPay(chat, vm, STATUS)
            vm.resetSendState()

            vm.setUserStatus(OTHER_STATUS, broadcast = true)

            assertEquals("another text is asked for all three", "STATUS_BROADCAST" to 3, question(vm))
            vm.confirmPaidAction()
            runToEnd(vm, chat.heights)
            assertEquals("each contact gets the new text once", 6, chat.seam.submits.get())
        }

    // ── (e) ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `what each contact got survives a kill and the next process sends the same status to nobody`() =
        runBlocking {
            val chat = maybeSentForB()
            setAndPay(chat, chat.viewModel(OwnChangeAccounts.create()), STATUS)
            val proposalsBefore = chat.seam.proposalRequests.get()
            val onDisk = chat.record
            assertTrue("nothing about the broadcast was saved", onDisk != null)
            for (contact in CONTACTS) {
                assertFalse("the record holds a contact's address", contact in onDisk!!)
                assertTrue("the record does not name $contact by its hash", peerHash(contact) in onDisk)
            }

            val next = chat.viewModel(OwnChangeAccounts.create())
            next.setUserStatus(STATUS, broadcast = true)
            next.confirmPaidAction()
            runToEnd(next, chat.heights)

            assertNotEquals("the next process asked to pay for the same status again", "NeedsPaidActionConfirmation", stateName(next))
            assertEquals(
                "the next process sent the same status again (to ${chat.seam.proposedTo.drop(proposalsBefore)})",
                proposalsBefore,
                chat.seam.proposalRequests.get(),
            )
        }
}
